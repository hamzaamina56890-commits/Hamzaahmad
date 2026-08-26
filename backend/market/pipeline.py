"""
MarketPipeline — real-time integration layer.

Wires together:
    TwelveDataProvider (WebSocket)
    → price ticks
    → CandleBuilder (OHLC)
    → candle history ring-buffer
    → Signal Engine (/api/analyze)

One pipeline instance is shared across the FastAPI application.
It is started/stopped with the app lifespan.

Usage
-----
    pipeline = MarketPipeline()
    await pipeline.set_subscription("EUR/USD", 60)
    status  = pipeline.status()
    candles = pipeline.get_candles()
    result  = pipeline.get_analysis()
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import deque
from typing import Any

from backend.market.candle_builder import CandleBuilder
from backend.market.twelvedata import TwelveDataProvider
from backend.analysis.signal_engine import analyze

logger = logging.getLogger(__name__)

# Maximum number of closed candles to retain per (symbol, timeframe).
MAX_CANDLE_HISTORY = 200

# Twelve Data uses "EUR/USD" → WebSocket symbol format is "EUR/USD".
# No transformation needed.


class MarketPipeline:
    """Manages one active symbol/timeframe subscription."""

    def __init__(self) -> None:
        self._provider = TwelveDataProvider()
        self._builder = CandleBuilder()
        self._candle_history: deque[dict[str, Any]] = deque(
            maxlen=MAX_CANDLE_HISTORY
        )
        self._symbol: str | None = None
        self._timeframe: int | None = None
        self._task: asyncio.Task | None = None  # type: ignore[type-arg]
        self._last_error: str | None = None
        self._data_timestamp: float | None = None

    # ------------------------------------------------------------------
    # public control API
    # ------------------------------------------------------------------

    async def set_subscription(
        self,
        symbol: str,
        timeframe_seconds: int,
    ) -> None:
        """
        Switch to a new symbol/timeframe.
        Stops any running stream, clears history, starts fresh.
        """
        await self._stop_stream()

        if timeframe_seconds not in CandleBuilder.SUPPORTED_TIMEFRAMES:
            raise ValueError(
                f"Unsupported timeframe: {timeframe_seconds}. "
                f"Supported: {CandleBuilder.SUPPORTED_TIMEFRAMES}"
            )

        self._symbol = symbol
        self._timeframe = timeframe_seconds
        self._candle_history.clear()
        self._builder.reset()
        self._last_error = None
        self._data_timestamp = None

        self._task = asyncio.create_task(
            self._run_stream(), name=f"pipeline-{symbol}"
        )
        logger.info("Pipeline started for %s @ %ss", symbol, timeframe_seconds)

    async def shutdown(self) -> None:
        """Gracefully stop the pipeline."""
        await self._stop_stream()
        await self._provider.close()

    # ------------------------------------------------------------------
    # status / data accessors
    # ------------------------------------------------------------------

    def status(self) -> dict[str, Any]:
        latest = self._provider.get_latest_price(self._symbol or "")
        return {
            "provider": "Twelve Data",
            "connected": self._provider.connected,
            "live_data": self._provider.connected and bool(latest),
            "symbol": self._symbol,
            "timeframe_seconds": self._timeframe,
            "latest_price": latest.get("price") if latest else None,
            "data_timestamp": latest.get("timestamp") if latest else None,
            "candle_count": len(self._candle_history),
            "last_error": self._last_error,
            "streaming": self._task is not None and not self._task.done(),
        }

    def get_latest_price(self) -> dict[str, Any] | None:
        if self._symbol is None:
            return None
        return self._provider.get_latest_price(self._symbol)

    def get_current_candle(self) -> dict[str, Any] | None:
        if self._symbol is None or self._timeframe is None:
            return None
        return self._builder.get_current(self._symbol, self._timeframe)

    def get_candles(self, limit: int = 100) -> list[dict[str, Any]]:
        candles = list(self._candle_history)
        return candles[-limit:] if limit else candles

    def get_analysis(self) -> dict[str, Any]:
        if not self._symbol or not self._timeframe:
            return {
                "signal": "INSUFFICIENT_DATA",
                "error": "No symbol/timeframe configured.",
                "candle_count": 0,
            }

        candles = self.get_candles()
        return analyze(
            {
                "symbol": self._symbol,
                "timeframe": self._timeframe,
                "candles": candles,
            }
        )

    # ------------------------------------------------------------------
    # internal stream loop
    # ------------------------------------------------------------------

    async def _run_stream(self) -> None:
        symbol = self._symbol
        timeframe = self._timeframe

        if not symbol or not timeframe:
            return

        while True:
            try:
                async for message in self._provider.stream([symbol]):
                    self._process_message(message, symbol, timeframe)
            except asyncio.CancelledError:
                logger.info("Pipeline stream cancelled for %s", symbol)
                break
            except Exception as exc:  # pylint: disable=broad-except
                self._last_error = str(exc)
                logger.warning(
                    "Pipeline stream error for %s: %s — reconnecting in 5s",
                    symbol,
                    exc,
                )
                await asyncio.sleep(5)

                # Reset provider state before reconnecting
                self._provider.reset()

    def _process_message(
        self,
        message: dict[str, Any],
        symbol: str,
        timeframe: int,
    ) -> None:
        """Validate and ingest one provider message."""
        if not isinstance(message, dict):
            logger.debug("Ignoring non-dict message: %r", message)
            return

        event = message.get("event")

        if event == "heartbeat":
            return

        if event == "subscribe-status":
            status = message.get("status")
            if status != "ok":
                logger.warning("Subscribe status: %s", message)
            return

        if event != "price":
            return

        # --- validate price tick ---
        msg_symbol = message.get("symbol")
        if msg_symbol != symbol:
            return

        raw_price = message.get("price")
        raw_ts = message.get("timestamp")

        if raw_price is None or raw_ts is None:
            logger.debug("Incomplete price tick: %r", message)
            return

        try:
            price = float(raw_price)
            timestamp = int(raw_ts)
        except (TypeError, ValueError) as exc:
            logger.warning("Malformed price tick %r: %s", message, exc)
            return

        if price <= 0:
            logger.warning("Non-positive price %s for %s, discarding", price, symbol)
            return

        if timestamp <= 0:
            logger.warning("Invalid timestamp %s for %s, discarding", timestamp, symbol)
            return

        self._data_timestamp = time.time()

        # --- build candle ---
        try:
            result = self._builder.update(symbol, price, timestamp, timeframe)
        except ValueError as exc:
            logger.warning("CandleBuilder error: %s", exc)
            return

        closed = result.get("closed_candle")
        if closed:
            self._append_candle(closed)

    def _append_candle(self, candle: dict[str, Any]) -> None:
        """Append a closed candle, preventing duplicates by start_time."""
        if self._candle_history:
            last = self._candle_history[-1]
            if (
                last.get("start_time") == candle.get("start_time")
                and last.get("symbol") == candle.get("symbol")
            ):
                logger.debug("Duplicate candle suppressed: %s", candle)
                return

        self._candle_history.append(candle)

    async def _stop_stream(self) -> None:
        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        self._task = None
        await self._provider.close()
        # Reset provider so it can reconnect fresh
        self._provider = TwelveDataProvider()
