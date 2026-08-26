"""
Signal Engine — technical analysis over OHLC candle history.

Analyses the supplied candles and returns a trading signal
together with supporting indicator values.

Input (payload dict):
    symbol        : str
    timeframe     : int   (seconds)
    candles       : list[dict]   — each dict is a Candle.to_dict()

Output dict:
    symbol, timeframe, signal, confidence,
    indicators (sma_fast, sma_slow, rsi, last_close),
    candle_count, error (if any)
"""

from __future__ import annotations

from typing import Any


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _sma(values: list[float], period: int) -> float | None:
    if len(values) < period:
        return None
    return sum(values[-period:]) / period


def _rsi(values: list[float], period: int = 14) -> float | None:
    if len(values) < period + 1:
        return None

    gains, losses = [], []
    for i in range(-period, 0):
        change = values[i] - values[i - 1]
        (gains if change > 0 else losses).append(abs(change))

    avg_gain = sum(gains) / period if gains else 0.0
    avg_loss = sum(losses) / period if losses else 0.0

    if avg_loss == 0:
        return 100.0

    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


# ---------------------------------------------------------------------------
# public interface
# ---------------------------------------------------------------------------

def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    """
    Run signal analysis on a candle history payload.

    Returns a result dict that always includes 'signal' (BUY / SELL /
    NEUTRAL / INSUFFICIENT_DATA) and an optional 'error' key when the
    input cannot be processed.
    """
    symbol = payload.get("symbol", "")
    timeframe = payload.get("timeframe", 0)
    candles: list[dict] = payload.get("candles", [])

    base = {
        "symbol": symbol,
        "timeframe": timeframe,
        "candle_count": len(candles),
        "signal": "INSUFFICIENT_DATA",
        "confidence": 0.0,
        "indicators": {},
    }

    if not symbol:
        return {**base, "error": "symbol is required"}

    if not timeframe:
        return {**base, "error": "timeframe is required"}

    if not candles:
        return base

    # validate candle shape
    required_keys = {"open", "high", "low", "close", "start_time"}
    for i, c in enumerate(candles):
        missing = required_keys - set(c.keys())
        if missing:
            return {
                **base,
                "error": f"candle[{i}] missing keys: {sorted(missing)}",
            }
        try:
            for k in ("open", "high", "low", "close"):
                v = float(c[k])
                if v <= 0:
                    return {
                        **base,
                        "error": f"candle[{i}] {k}={v} is not positive",
                    }
        except (TypeError, ValueError):
            return {**base, "error": f"candle[{i}] price value is not a valid number"}

    closes = [float(c["close"]) for c in candles]

    sma_fast = _sma(closes, 7)
    sma_slow = _sma(closes, 21)
    rsi = _rsi(closes, 14)
    last_close = closes[-1]

    indicators = {
        "last_close": last_close,
        "sma_fast": sma_fast,
        "sma_slow": sma_slow,
        "rsi": rsi,
    }

    # -----------------------------------------------------------------------
    # signal logic
    # -----------------------------------------------------------------------
    signal = "NEUTRAL"
    confidence = 0.0

    if sma_fast is not None and sma_slow is not None and rsi is not None:
        bullish = sma_fast > sma_slow and rsi < 70
        bearish = sma_fast < sma_slow and rsi < 70

        if bullish:
            signal = "BUY"
            gap = (sma_fast - sma_slow) / sma_slow
            confidence = min(round(gap * 1000, 2), 100.0)
        elif bearish:
            signal = "SELL"
            gap = (sma_slow - sma_fast) / sma_slow
            confidence = min(round(gap * 1000, 2), 100.0)
        else:
            signal = "NEUTRAL"
            confidence = 0.0
    elif len(closes) >= 2:
        # fallback: last-tick direction
        if closes[-1] > closes[-2]:
            signal = "BUY"
            confidence = 10.0
        elif closes[-1] < closes[-2]:
            signal = "SELL"
            confidence = 10.0
        else:
            signal = "NEUTRAL"

    return {
        **base,
        "signal": signal,
        "confidence": confidence,
        "indicators": indicators,
        "candle_count": len(closes),
    }
