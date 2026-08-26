"""
Market Analysis / Signal Engine
================================
Provider-independent technical analysis engine that consumes
Candle objects (or plain dicts with the same keys) and returns a
structured signal with confidence and explanation.

All calculations use only standard Python – no numpy/pandas –
so no extra runtime dependencies are needed.
"""

from __future__ import annotations

import math
from typing import Any


# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------

_REQUIRED_KEYS = ("open", "high", "low", "close")
_MIN_CANDLES_MACD = 35   # 26 slow EMA + 9 signal
_MIN_CANDLES_RSI  = 15   # 14 periods + 1
_MIN_CANDLES_ATR  = 2    # need at least 2 for ATR
_MIN_CANDLES_SR   = 10   # meaningful S/R window


def _is_bad(v: Any) -> bool:
    """Return True if v is not a finite real number."""
    try:
        return not math.isfinite(float(v))
    except (TypeError, ValueError):
        return True


def _validate_candles(candles: list[dict]) -> list[str]:
    """Return a list of validation error strings (empty = ok)."""
    errors: list[str] = []

    if not candles:
        errors.append("Candle list is empty.")
        return errors

    for i, c in enumerate(candles):
        for k in _REQUIRED_KEYS:
            if k not in c:
                errors.append(f"Candle[{i}] missing field '{k}'.")
                return errors  # can't continue without OHLC
            if _is_bad(c[k]):
                errors.append(
                    f"Candle[{i}] field '{k}' is not a finite number "
                    f"(got {c[k]!r})."
                )
                return errors

        o, h, l, cl = float(c["open"]), float(c["high"]), float(c["low"]), float(c["close"])

        if h < l:
            errors.append(f"Candle[{i}] high ({h}) < low ({l}).")
        if o < l or o > h:
            errors.append(f"Candle[{i}] open ({o}) outside [low, high].")
        if cl < l or cl > h:
            errors.append(f"Candle[{i}] close ({cl}) outside [low, high].")
        if o <= 0 or h <= 0 or l <= 0 or cl <= 0:
            errors.append(f"Candle[{i}] contains non-positive price values.")

    # Check ordering (ascending start_time if present)
    times = [c.get("start_time") for c in candles]
    if all(t is not None for t in times):
        for i in range(1, len(times)):
            if times[i] <= times[i - 1]:  # type: ignore[operator]
                errors.append(
                    f"Candle ordering error: candle[{i}].start_time "
                    f"({times[i]}) <= candle[{i-1}].start_time ({times[i-1]})."
                )
                break

    return errors


# ---------------------------------------------------------------------------
# Technical indicator calculations (pure Python, O(n))
# ---------------------------------------------------------------------------

def _ema(values: list[float], period: int) -> list[float]:
    """Exponential Moving Average."""
    if len(values) < period:
        return []
    k = 2.0 / (period + 1)
    result: list[float] = []
    # seed with SMA of first `period` values
    sma = sum(values[:period]) / period
    result.append(sma)
    for v in values[period:]:
        result.append(v * k + result[-1] * (1 - k))
    return result


def _rsi(closes: list[float], period: int = 14) -> list[float]:
    """Relative Strength Index using Wilder smoothing."""
    if len(closes) < period + 1:
        return []

    gains: list[float] = []
    losses: list[float] = []
    for i in range(1, len(closes)):
        diff = closes[i] - closes[i - 1]
        gains.append(max(diff, 0.0))
        losses.append(max(-diff, 0.0))

    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period

    # Emit the first RSI value from the seed averages, then update.
    rsi_values: list[float] = []

    def _append_rsi() -> None:
        if avg_loss == 0.0:
            rsi_values.append(100.0)
        else:
            rs = avg_gain / avg_loss
            rsi_values.append(100 - 100 / (1 + rs))

    _append_rsi()
    for i in range(period, len(gains)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period
        _append_rsi()

    return rsi_values


def _macd(
    closes: list[float],
    fast: int = 12,
    slow: int = 26,
    signal: int = 9,
) -> dict[str, list[float]]:
    """MACD line, signal line, histogram."""
    ema_fast = _ema(closes, fast)
    ema_slow = _ema(closes, slow)

    # align both lists to the same length
    offset = slow - fast
    if not ema_fast or not ema_slow or offset >= len(ema_fast):
        return {"macd": [], "signal": [], "histogram": []}

    ema_fast_aligned = ema_fast[offset:]
    macd_line = [f - s for f, s in zip(ema_fast_aligned, ema_slow)]

    signal_line = _ema(macd_line, signal)
    # align macd_line to signal_line
    macd_aligned = macd_line[signal - 1:]

    histogram = [m - s for m, s in zip(macd_aligned, signal_line)]
    return {
        "macd": macd_aligned,
        "signal": signal_line,
        "histogram": histogram,
    }


def _atr(candles: list[dict], period: int = 14) -> list[float]:
    """Average True Range using Wilder smoothing."""
    if len(candles) < 2:
        return []

    trs: list[float] = []
    for i in range(1, len(candles)):
        h = float(candles[i]["high"])
        l = float(candles[i]["low"])
        prev_c = float(candles[i - 1]["close"])
        tr = max(h - l, abs(h - prev_c), abs(l - prev_c))
        trs.append(tr)

    if len(trs) < period:
        # return simple average of available TRs
        return [sum(trs) / len(trs)]

    atr = sum(trs[:period]) / period
    result = [atr]
    for tr in trs[period:]:
        atr = (atr * (period - 1) + tr) / period
        result.append(atr)
    return result


def _support_resistance(
    candles: list[dict],
    window: int = 10,
) -> dict[str, float | None]:
    """Estimate recent support and resistance from highs/lows."""
    if len(candles) < window:
        window = len(candles)
    recent = candles[-window:]
    highs = [float(c["high"]) for c in recent]
    lows  = [float(c["low"])  for c in recent]
    return {
        "support":    min(lows)  if lows  else None,
        "resistance": max(highs) if highs else None,
    }


def _candle_analysis(candle: dict) -> dict[str, Any]:
    """Characterise the most recent candle."""
    o  = float(candle["open"])
    h  = float(candle["high"])
    l  = float(candle["low"])
    cl = float(candle["close"])

    body = abs(cl - o)
    full_range = h - l
    upper_wick = h - max(o, cl)
    lower_wick = min(o, cl) - l

    body_ratio  = body / full_range if full_range > 0 else 0.0
    bullish = cl > o

    return {
        "bullish":     bullish,
        "body_size":   round(body, 6),
        "body_ratio":  round(body_ratio, 4),
        "upper_wick":  round(upper_wick, 6),
        "lower_wick":  round(lower_wick, 6),
        "full_range":  round(full_range, 6),
    }


# ---------------------------------------------------------------------------
# Signal generation
# ---------------------------------------------------------------------------

def _trend_from_ema(ema9: list[float], ema21: list[float]) -> str:
    if not ema9 or not ema21:
        return "NEUTRAL"
    if ema9[-1] > ema21[-1]:
        return "UP"
    if ema9[-1] < ema21[-1]:
        return "DOWN"
    return "NEUTRAL"


def _generate_signal(
    trend: str,
    rsi_values: list[float],
    macd_data: dict[str, list[float]],
    candle_info: dict,
) -> tuple[str, float, list[str]]:
    """
    Return (signal, confidence, reasons).
    signal  : "UP" | "DOWN" | "NEUTRAL"
    confidence: 0.0 – 1.0
    """
    bull_score = 0
    bear_score = 0
    reasons: list[str] = []

    # --- EMA trend
    if trend == "UP":
        bull_score += 2
        reasons.append("EMA9 is above EMA21 (bullish trend).")
    elif trend == "DOWN":
        bear_score += 2
        reasons.append("EMA9 is below EMA21 (bearish trend).")

    # --- RSI
    if rsi_values:
        rsi = rsi_values[-1]
        if rsi < 30:
            bull_score += 2
            reasons.append(f"RSI {rsi:.1f} is oversold (< 30), potential reversal UP.")
        elif rsi > 70:
            bear_score += 2
            reasons.append(f"RSI {rsi:.1f} is overbought (> 70), potential reversal DOWN.")
        elif 40 <= rsi <= 60:
            reasons.append(f"RSI {rsi:.1f} is neutral.")
        elif rsi > 50:
            bull_score += 1
            reasons.append(f"RSI {rsi:.1f} is mildly bullish.")
        else:
            bear_score += 1
            reasons.append(f"RSI {rsi:.1f} is mildly bearish.")

    # --- MACD histogram
    hist = macd_data.get("histogram", [])
    if hist:
        if hist[-1] > 0:
            bull_score += 1
            reasons.append("MACD histogram is positive (bullish momentum).")
        elif hist[-1] < 0:
            bear_score += 1
            reasons.append("MACD histogram is negative (bearish momentum).")
        # MACD cross
        if len(hist) >= 2 and hist[-2] < 0 < hist[-1]:
            bull_score += 1
            reasons.append("MACD histogram crossed above zero (bullish crossover).")
        elif len(hist) >= 2 and hist[-2] > 0 > hist[-1]:
            bear_score += 1
            reasons.append("MACD histogram crossed below zero (bearish crossover).")

    # --- Candle body
    if candle_info["body_ratio"] > 0.6:
        if candle_info["bullish"]:
            bull_score += 1
            reasons.append("Strong bullish candle body.")
        else:
            bear_score += 1
            reasons.append("Strong bearish candle body.")

    # --- Decide
    total = bull_score + bear_score
    if total == 0:
        return "NEUTRAL", 0.0, reasons + ["Insufficient evidence for a directional signal."]

    if bull_score > bear_score:
        confidence = min(bull_score / (total + 2), 0.95)
        return "UP", round(confidence, 4), reasons
    if bear_score > bull_score:
        confidence = min(bear_score / (total + 2), 0.95)
        return "DOWN", round(confidence, 4), reasons

    return "NEUTRAL", 0.0, reasons + ["Bull and bear evidence is balanced."]


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    """
    Analyse a list of OHLC candles and return a signal response.

    Expected payload keys:
        symbol      (str)          – e.g. "EUR/USD"
        timeframe   (int|str)      – seconds, e.g. 60
        candles     (list[dict])   – list of Candle.to_dict() / raw dicts

    Returns a structured dict with signal, confidence, indicators, etc.
    """
    symbol    = payload.get("symbol", "UNKNOWN")
    timeframe = payload.get("timeframe", None)
    raw       = payload.get("candles", [])

    # Normalise candles: accept Candle objects or dicts
    candles: list[dict] = []
    for c in raw:
        if hasattr(c, "to_dict"):
            candles.append(c.to_dict())
        elif isinstance(c, dict):
            candles.append(c)
        else:
            return {
                "symbol": symbol,
                "timeframe": timeframe,
                "signal": "NEUTRAL",
                "confidence": 0.0,
                "trend": "NEUTRAL",
                "indicators": {},
                "support_resistance": {},
                "candle_analysis": {},
                "explanation": "Invalid candle type supplied.",
                "errors": ["Each candle must be a dict or Candle object."],
                "validation_ok": False,
            }

    # Validate
    errors = _validate_candles(candles)
    if errors:
        return {
            "symbol": symbol,
            "timeframe": timeframe,
            "signal": "NEUTRAL",
            "confidence": 0.0,
            "trend": "NEUTRAL",
            "indicators": {},
            "support_resistance": {},
            "candle_analysis": {},
            "explanation": "Validation failed. " + " ".join(errors),
            "errors": errors,
            "validation_ok": False,
        }

    closes = [float(c["close"]) for c in candles]

    # Indicators
    ema9  = _ema(closes, 9)
    ema21 = _ema(closes, 21)
    rsi   = _rsi(closes, 14)
    macd  = _macd(closes)
    atr   = _atr(candles, 14)
    sr    = _support_resistance(candles)
    ca    = _candle_analysis(candles[-1])
    trend = _trend_from_ema(ema9, ema21)

    signal, confidence, reasons = _generate_signal(trend, rsi, macd, ca)

    n = len(candles)
    warnings: list[str] = []
    if n < _MIN_CANDLES_MACD:
        warnings.append(
            f"Only {n} candles supplied; MACD requires ≥{_MIN_CANDLES_MACD} "
            "for full accuracy."
        )
    if n < _MIN_CANDLES_RSI:
        warnings.append(
            f"Only {n} candles supplied; RSI14 requires ≥{_MIN_CANDLES_RSI}."
        )

    return {
        "symbol":    symbol,
        "timeframe": timeframe,
        "signal":    signal,
        "confidence": confidence,
        "trend":     trend,
        "indicators": {
            "ema9":       round(ema9[-1],  6) if ema9  else None,
            "ema21":      round(ema21[-1], 6) if ema21 else None,
            "rsi14":      round(rsi[-1],   4) if rsi   else None,
            "macd_line":  round(macd["macd"][-1],      6) if macd["macd"]      else None,
            "macd_signal":round(macd["signal"][-1],    6) if macd["signal"]    else None,
            "macd_hist":  round(macd["histogram"][-1], 6) if macd["histogram"] else None,
            "atr14":      round(atr[-1],   6) if atr   else None,
        },
        "support_resistance": sr,
        "candle_analysis": ca,
        "explanation":  " ".join(reasons),
        "warnings":     warnings,
        "errors":       [],
        "validation_ok": True,
    }
