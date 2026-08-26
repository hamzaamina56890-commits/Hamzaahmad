"""
Signal Engine: computes RSI, SMA, and UP/DOWN/NEUTRAL direction
from a list of real OHLC candles.

No fake data is generated. If candle history is insufficient,
indicators return None and the signal is NEUTRAL.
"""
from __future__ import annotations

from typing import Any

from backend.market.assets import ASSETS, TIMEFRAMES

# ── constants ────────────────────────────────────────────────────────────────
RSI_PERIOD = 14
SMA_PERIOD = 20
MIN_CANDLES_FOR_RSI = RSI_PERIOD + 1   # need at least 15 candles
RSI_OVERBOUGHT = 70
RSI_OVERSOLD = 30

_VALID_SYMBOLS = {a["symbol"] for a in ASSETS}
_VALID_TIMEFRAMES = {t["seconds"] for t in TIMEFRAMES}


# ── helpers ───────────────────────────────────────────────────────────────────

def _rsi(closes: list[float], period: int = RSI_PERIOD) -> float | None:
    """Return RSI value or None when history is insufficient."""
    if len(closes) < period + 1:
        return None

    gains: list[float] = []
    losses: list[float] = []

    recent = closes[-(period + 1):]
    for i in range(1, period + 1):
        delta = recent[i] - recent[i - 1]
        if delta > 0:
            gains.append(delta)
            losses.append(0.0)
        else:
            gains.append(0.0)
            losses.append(abs(delta))

    avg_gain = sum(gains) / period
    avg_loss = sum(losses) / period

    if avg_loss == 0:
        return 100.0

    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


def _sma(closes: list[float], period: int = SMA_PERIOD) -> float | None:
    """Return simple moving average or None when history is insufficient."""
    if len(closes) < period:
        return None
    return sum(closes[-period:]) / period


def _direction(rsi: float | None, sma: float | None, last_close: float) -> str:
    """
    Derive UP / DOWN / NEUTRAL from RSI and SMA.

    Rules:
      UP   – RSI < RSI_OVERSOLD  OR  price above SMA (if SMA available)
      DOWN – RSI > RSI_OVERBOUGHT  OR  price below SMA (if SMA available)
      NEUTRAL – insufficient data or conflicting signals
    """
    if rsi is None and sma is None:
        return "NEUTRAL"

    up_votes = 0
    down_votes = 0

    if rsi is not None:
        if rsi < RSI_OVERSOLD:
            up_votes += 1
        elif rsi > RSI_OVERBOUGHT:
            down_votes += 1

    if sma is not None:
        if last_close > sma:
            up_votes += 1
        elif last_close < sma:
            down_votes += 1

    if up_votes > down_votes:
        return "UP"
    if down_votes > up_votes:
        return "DOWN"
    return "NEUTRAL"


# ── public API ────────────────────────────────────────────────────────────────

def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    """
    Analyse a list of OHLC candles and return RSI, SMA, and direction.

    Expected payload:
      {
        "symbol":            str,
        "timeframe_seconds": int,
        "candles": [
          {
            "open": float, "high": float,
            "low": float,  "close": float,
            "start_time": int
          },
          ...
        ]
      }

    Returns:
      {
        "symbol": str,
        "timeframe_seconds": int,
        "candle_count": int,
        "rsi": float | null,
        "sma": float | null,
        "direction": "UP" | "DOWN" | "NEUTRAL",
        "error": str | null
      }
    """
    # ── validate inputs ───────────────────────────────────────────────────
    symbol: str = payload.get("symbol", "")
    timeframe_seconds = payload.get("timeframe_seconds")
    candles: list[dict[str, Any]] = payload.get("candles", [])

    if not symbol:
        return _error_response("symbol is required")

    if symbol not in _VALID_SYMBOLS:
        return _error_response(f"Unsupported symbol: {symbol!r}")

    if timeframe_seconds is None:
        return _error_response("timeframe_seconds is required")

    if timeframe_seconds not in _VALID_TIMEFRAMES:
        return _error_response(
            f"Unsupported timeframe_seconds: {timeframe_seconds!r}"
        )

    if not isinstance(candles, list):
        return _error_response("candles must be a list")

    # ── validate candles and extract closes ───────────────────────────────
    closes: list[float] = []
    for idx, c in enumerate(candles):
        if not isinstance(c, dict):
            return _error_response(f"candles[{idx}] is not a dict")
        try:
            close = float(c["close"])
        except (KeyError, TypeError, ValueError):
            return _error_response(
                f"candles[{idx}] missing or invalid 'close' field"
            )
        if close <= 0:
            return _error_response(
                f"candles[{idx}] close price must be > 0"
            )

        # validate candle structure with CandleBuilder constraints
        for field in ("open", "high", "low", "close"):
            try:
                val = float(c[field])
            except (KeyError, TypeError, ValueError):
                return _error_response(
                    f"candles[{idx}] missing or invalid '{field}' field"
                )
            if val <= 0:
                return _error_response(
                    f"candles[{idx}] {field} must be > 0"
                )

        closes.append(close)

    candle_count = len(closes)

    if candle_count == 0:
        return {
            "symbol": symbol,
            "timeframe_seconds": timeframe_seconds,
            "candle_count": 0,
            "rsi": None,
            "sma": None,
            "direction": "NEUTRAL",
            "error": None,
        }

    # ── compute indicators ────────────────────────────────────────────────
    rsi_value = _rsi(closes)
    sma_value = _sma(closes)
    last_close = closes[-1]
    direction = _direction(rsi_value, sma_value, last_close)

    return {
        "symbol": symbol,
        "timeframe_seconds": timeframe_seconds,
        "candle_count": candle_count,
        "rsi": round(rsi_value, 4) if rsi_value is not None else None,
        "sma": round(sma_value, 6) if sma_value is not None else None,
        "direction": direction,
        "error": None,
    }


def _error_response(message: str) -> dict[str, Any]:
    return {
        "symbol": None,
        "timeframe_seconds": None,
        "candle_count": 0,
        "rsi": None,
        "sma": None,
        "direction": "NEUTRAL",
        "error": message,
    }
