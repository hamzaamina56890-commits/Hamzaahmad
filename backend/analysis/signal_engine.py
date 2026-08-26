"""
Signal Engine – technical-analysis layer.

Receives a list of OHLC candle dicts (as produced by CandleBuilder)
and returns a structured analysis result.

Supported indicators
--------------------
* RSI (Relative Strength Index, 14-period default)

Signal logic
------------
* UP     – RSI > 60
* DOWN   – RSI < 40
* NEUTRAL – otherwise, or when there is insufficient history
"""

from __future__ import annotations

from typing import Any


# ─────────────────────────────────────────────
# RSI
# ─────────────────────────────────────────────

def _rsi(closes: list[float], period: int = 14) -> float | None:
    """Return the RSI value for the given closing prices.

    Returns None if there are not enough data points (need at least
    period + 1 values to compute one RSI reading).
    """
    if len(closes) < period + 1:
        return None

    gains: list[float] = []
    losses: list[float] = []

    for i in range(1, len(closes)):
        change = closes[i] - closes[i - 1]
        if change >= 0:
            gains.append(change)
            losses.append(0.0)
        else:
            gains.append(0.0)
            losses.append(abs(change))

    # Wilder's smoothed averages
    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period

    for i in range(period, len(gains)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period

    if avg_loss == 0.0:
        return 100.0

    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


# ─────────────────────────────────────────────
# Public API
# ─────────────────────────────────────────────

def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    """Analyse a list of OHLC candles and return a signal.

    Expected payload keys
    ---------------------
    symbol       : str  – e.g. "EUR/USD"
    timeframe    : int  – timeframe in seconds
    candles      : list – list of candle dicts with at least a "close" key

    Returns a dict with:
    --------------------
    symbol       : str
    timeframe    : int
    signal       : "UP" | "DOWN" | "NEUTRAL"
    rsi          : float | None
    candle_count : int
    reason       : str  – human-readable explanation
    """
    symbol: str = payload.get("symbol", "")
    timeframe: int = int(payload.get("timeframe", 0))
    candles: list[dict[str, Any]] = payload.get("candles", [])

    if not symbol:
        return _error_response("symbol is required")

    if timeframe <= 0:
        return _error_response("timeframe must be a positive integer (seconds)")

    if not isinstance(candles, list):
        return _error_response("candles must be a list")

    closes = _extract_closes(candles)
    candle_count = len(closes)

    rsi_value = _rsi(closes)

    if rsi_value is None:
        signal = "NEUTRAL"
        reason = (
            f"Insufficient candle history ({candle_count} candles). "
            "Need at least 15 closed candles for RSI-14."
        )
    elif rsi_value > 60:
        signal = "UP"
        reason = f"RSI={rsi_value:.2f} is above 60 – bullish momentum."
    elif rsi_value < 40:
        signal = "DOWN"
        reason = f"RSI={rsi_value:.2f} is below 40 – bearish momentum."
    else:
        signal = "NEUTRAL"
        reason = f"RSI={rsi_value:.2f} is between 40 and 60 – no clear momentum."

    return {
        "symbol": symbol,
        "timeframe": timeframe,
        "signal": signal,
        "rsi": round(rsi_value, 4) if rsi_value is not None else None,
        "candle_count": candle_count,
        "reason": reason,
    }


# ─────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────

def _extract_closes(candles: list[dict[str, Any]]) -> list[float]:
    closes: list[float] = []

    for c in candles:
        try:
            close = float(c["close"])
            if close > 0:
                closes.append(close)
        except (KeyError, TypeError, ValueError):
            continue

    return closes


def _error_response(message: str) -> dict[str, Any]:
    return {
        "symbol": None,
        "timeframe": None,
        "signal": "NEUTRAL",
        "rsi": None,
        "candle_count": 0,
        "reason": f"Error: {message}",
    }
