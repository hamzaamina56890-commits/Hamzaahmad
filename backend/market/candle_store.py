from __future__ import annotations

from collections import defaultdict

from backend.market.candle_builder import Candle


class CandleStore:
    """In-memory candle history grouped by symbol and timeframe."""

    def __init__(self) -> None:
        self._candles: dict[
            tuple[str, int],
            list[Candle],
        ] = defaultdict(list)
        self._timestamps: dict[
            tuple[str, int],
            set[int],
        ] = defaultdict(set)

    def add_candle(self, candle: Candle) -> bool:
        """
        Add a candle to history if it is not already stored.

        Returns True when a candle is added, or False when a
        candle with the same symbol, timeframe and start time
        already exists.
        """

        key = (candle.symbol, candle.timeframe_seconds)

        if candle.start_time in self._timestamps[key]:
            return False

        self._candles[key].append(candle)
        self._timestamps[key].add(candle.start_time)
        return True

    def get_recent_candles(
        self,
        symbol: str,
        timeframe_seconds: int,
        limit: int = 100,
    ) -> list[Candle]:
        """Return up to `limit` most recent candles for the key."""

        if limit <= 0:
            return []

        candles = self._candles.get((symbol, timeframe_seconds), [])
        ordered = sorted(
            candles,
            key=lambda candle: candle.start_time,
        )
        return ordered[-limit:]

    def get_all_candles(
        self,
        symbol: str,
        timeframe_seconds: int,
    ) -> list[Candle]:
        """Return all stored candles for the symbol and timeframe."""

        candles = self._candles.get((symbol, timeframe_seconds), [])
        return sorted(
            candles,
            key=lambda candle: candle.start_time,
        )
