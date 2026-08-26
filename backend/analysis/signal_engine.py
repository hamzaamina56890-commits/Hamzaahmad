"""
Market Analysis / Signal Engine
================================
Accepts a list of OHLC candle dicts (from any MarketDataProvider) and
produces a rule-based signal (UP / DOWN / NEUTRAL) with indicators,
confidence, and a human-readable explanation.

Design principles
-----------------
* Pure Python – no optional C extensions required (uses only stdlib math).
* Deterministic and testable – same input always produces same output.
* Never fabricates missing data; degrades gracefully when history is short.
* Provider-agnostic – accepts any list[dict] that has open/high/low/close keys.
"""
from __future__ import annotations

import math
from typing import Any


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _closes(candles: list[dict]) -> list[float]:
    return [c["close"] for c in candles]


def _highs(candles: list[dict]) -> list[float]:
    return [c["high"] for c in candles]


def _lows(candles: list[dict]) -> list[float]:
    return [c["low"] for c in candles]


def _ema(values: list[float], period: int) -> float | None:
    """Exponential moving average of the last `period` values (standard formula)."""
    if len(values) < period:
        return None
    k = 2.0 / (period + 1)
    ema = sum(values[:period]) / period
    for v in values[period:]:
        ema = v * k + ema * (1 - k)
    return ema


def _rsi(closes: list[float], period: int = 14) -> float | None:
    """Wilder RSI.  Requires at least period+1 values."""
    if len(closes) < period + 1:
        return None
    gains: list[float] = []
    losses: list[float] = []
    for i in range(1, len(closes)):
        delta = closes[i] - closes[i - 1]
        gains.append(max(delta, 0.0))
        losses.append(max(-delta, 0.0))
    # seed with first `period` values
    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period
    for i in range(period, len(gains)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period
    if avg_loss == 0:
        return 100.0
    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1 + rs))


def _macd(closes: list[float]) -> dict[str, float | None]:
    """MACD (12,26,9): returns line, signal, histogram."""
    ema12 = _ema(closes, 12)
    ema26 = _ema(closes, 26)
    if ema12 is None or ema26 is None:
        return {"line": None, "signal": None, "histogram": None}
    line = ema12 - ema26
    # signal line requires at least 9 macd values, but we only have one;
    # use the line itself as a single-sample approximation when history is short.
    # For a full signal line we need 26+9-1 = 34 candles.
    if len(closes) >= 34:
        macd_series: list[float] = []
        # rebuild ema12/ema26 series from index 25 onward
        k12 = 2.0 / 13
        k26 = 2.0 / 27
        e12 = sum(closes[:12]) / 12
        e26 = sum(closes[:26]) / 26
        for v in closes[12:26]:
            e12 = v * k12 + e12 * (1 - k12)
        macd_series.append(e12 - e26)
        for v in closes[26:]:
            e12 = v * k12 + e12 * (1 - k12)
            e26 = v * k26 + e26 * (1 - k26)
            macd_series.append(e12 - e26)
        signal_line = _ema(macd_series, 9)
        if signal_line is not None:
            return {
                "line": round(line, 8),
                "signal": round(signal_line, 8),
                "histogram": round(line - signal_line, 8),
            }
    return {"line": round(line, 8), "signal": None, "histogram": None}


def _atr(candles: list[dict], period: int = 14) -> float | None:
    """Average True Range (Wilder smoothing)."""
    if len(candles) < period + 1:
        return None
    trs: list[float] = []
    for i in range(1, len(candles)):
        h = candles[i]["high"]
        l = candles[i]["low"]
        pc = candles[i - 1]["close"]
        trs.append(max(h - l, abs(h - pc), abs(l - pc)))
    atr = sum(trs[:period]) / period
    for tr in trs[period:]:
        atr = (atr * (period - 1) + tr) / period
    return round(atr, 8)


def _support_resistance(
    highs: list[float],
    lows: list[float],
    lookback: int = 20,
) -> dict[str, float | None]:
    """Simple pivot-based support/resistance over the last `lookback` candles."""
    h = highs[-lookback:] if len(highs) >= lookback else highs
    l = lows[-lookback:] if len(lows) >= lookback else lows
    if not h or not l:
        return {"support": None, "resistance": None}
    return {
        "support": round(min(l), 8),
        "resistance": round(max(h), 8),
    }


def _candle_analysis(candle: dict) -> dict[str, float]:
    """Body / wick strength of the most-recent candle (0-1 scale)."""
    body = abs(candle["close"] - candle["open"])
    full_range = candle["high"] - candle["low"]
    if full_range == 0:
        return {"body_ratio": 0.0, "upper_wick_ratio": 0.0, "lower_wick_ratio": 0.0}
    body_ratio = round(body / full_range, 4)
    upper_wick = candle["high"] - max(candle["open"], candle["close"])
    lower_wick = min(candle["open"], candle["close"]) - candle["low"]
    return {
        "body_ratio": body_ratio,
        "upper_wick_ratio": round(upper_wick / full_range, 4),
        "lower_wick_ratio": round(lower_wick / full_range, 4),
    }


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

_MIN_CANDLES = 2  # absolute minimum to do any analysis


def _validate(candles: list[dict]) -> list[str]:
    """Return a list of validation error strings (empty = OK)."""
    errors: list[str] = []
    if not candles:
        errors.append("Empty candle list.")
        return errors
    if len(candles) < _MIN_CANDLES:
        errors.append(
            f"Insufficient candle history: need at least {_MIN_CANDLES}, got {len(candles)}."
        )
        return errors
    for i, c in enumerate(candles):
        for field in ("open", "high", "low", "close"):
            v = c.get(field)
            if v is None:
                errors.append(f"Candle[{i}] missing field '{field}'.")
                continue
            if not isinstance(v, (int, float)):
                errors.append(
                    f"Candle[{i}]['{field}'] is not a number: {v!r}."
                )
                continue
            if math.isnan(v) or math.isinf(v):
                errors.append(
                    f"Candle[{i}]['{field}'] is NaN or Infinity."
                )
                continue
            if v <= 0:
                errors.append(
                    f"Candle[{i}]['{field}'] must be positive, got {v}."
                )
        if errors:
            # stop after first bad candle to keep messages tidy
            break
        # Only run OHLC consistency checks when all fields passed field-level validation
        o, h, l, c_ = (
            c.get("open", 0),
            c.get("high", 0),
            c.get("low", 0),
            c.get("close", 0),
        )
        if h < o or h < c_:
            errors.append(
                f"Candle[{i}] high ({h}) is less than open ({o}) or close ({c_})."
            )
        if l > o or l > c_:
            errors.append(
                f"Candle[{i}] low ({l}) is greater than open ({o}) or close ({c_})."
            )
    return errors


# ---------------------------------------------------------------------------
# Signal logic
# ---------------------------------------------------------------------------

def _determine_signal(
    closes: list[float],
    ema9: float | None,
    ema21: float | None,
    rsi: float | None,
    macd_data: dict,
) -> tuple[str, float, list[str]]:
    """
    Rule-based signal.  Returns (signal, confidence 0-1, reasons).
    Signal is one of: UP / DOWN / NEUTRAL.
    Confidence reflects how many indicators agree.
    """
    up_votes = 0
    down_votes = 0
    reasons: list[str] = []
    total_possible = 0

    # --- EMA cross ---
    if ema9 is not None and ema21 is not None:
        total_possible += 1
        if ema9 > ema21:
            up_votes += 1
            reasons.append("EMA9 above EMA21 (bullish cross).")
        else:
            down_votes += 1
            reasons.append("EMA9 below EMA21 (bearish cross).")

    # --- RSI ---
    if rsi is not None:
        total_possible += 1
        if rsi < 35:
            up_votes += 1
            reasons.append(f"RSI {rsi:.1f} is oversold (<35), potential reversal up.")
        elif rsi > 65:
            down_votes += 1
            reasons.append(f"RSI {rsi:.1f} is overbought (>65), potential reversal down.")
        else:
            reasons.append(f"RSI {rsi:.1f} is neutral (35-65).")

    # --- MACD histogram ---
    hist = macd_data.get("histogram")
    if hist is not None:
        total_possible += 1
        if hist > 0:
            up_votes += 1
            reasons.append("MACD histogram positive (bullish momentum).")
        else:
            down_votes += 1
            reasons.append("MACD histogram negative (bearish momentum).")

    # --- Recent price slope (last 3 closes) ---
    if len(closes) >= 3:
        total_possible += 1
        slope = closes[-1] - closes[-3]
        if slope > 0:
            up_votes += 1
            reasons.append("Price slope positive over last 3 candles.")
        elif slope < 0:
            down_votes += 1
            reasons.append("Price slope negative over last 3 candles.")
        else:
            reasons.append("Price slope flat over last 3 candles.")

    if total_possible == 0:
        return "NEUTRAL", 0.0, ["Insufficient data for any indicator."]

    if up_votes > down_votes:
        signal = "UP"
        raw_conf = up_votes / total_possible
    elif down_votes > up_votes:
        signal = "DOWN"
        raw_conf = down_votes / total_possible
    else:
        signal = "NEUTRAL"
        raw_conf = 0.5

    # cap confidence at 0.85 – never claim certainty
    confidence = round(min(raw_conf * 0.85, 0.85), 4)

    return signal, confidence, reasons


def _trend_direction(ema9: float | None, ema21: float | None, closes: list[float]) -> str:
    if ema9 is not None and ema21 is not None:
        if ema9 > ema21 * 1.001:
            return "UP"
        if ema9 < ema21 * 0.999:
            return "DOWN"
        return "SIDEWAYS"
    if len(closes) >= 5:
        if closes[-1] > closes[-5]:
            return "UP"
        if closes[-1] < closes[-5]:
            return "DOWN"
    return "SIDEWAYS"


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    """
    Analyse a payload of the form:
    {
        "symbol":    str,
        "timeframe": int | str,
        "candles": [{"open": float, "high": float, "low": float, "close": float, ...}, ...]
    }

    Returns a structured result dict suitable for direct JSON serialisation.
    """
    symbol: str = payload.get("symbol", "UNKNOWN")
    timeframe: Any = payload.get("timeframe", "unknown")
    raw_candles: list[dict] = payload.get("candles", [])

    errors = _validate(raw_candles)
    if errors:
        return {
            "symbol": symbol,
            "timeframe": timeframe,
            "signal": "NEUTRAL",
            "confidence": 0.0,
            "trend": "UNKNOWN",
            "indicators": {},
            "support_resistance": {},
            "candle_analysis": {},
            "explanation": "Validation failed – see errors.",
            "errors": errors,
        }

    closes = _closes(raw_candles)
    highs = _highs(raw_candles)
    lows = _lows(raw_candles)

    ema9 = _ema(closes, 9)
    ema21 = _ema(closes, 21)
    rsi = _rsi(closes, 14)
    macd_data = _macd(closes)
    atr = _atr(raw_candles, 14)
    sr = _support_resistance(highs, lows)
    candle_stats = _candle_analysis(raw_candles[-1])
    trend = _trend_direction(ema9, ema21, closes)

    signal, confidence, reasons = _determine_signal(closes, ema9, ema21, rsi, macd_data)

    indicators: dict[str, Any] = {}
    if ema9 is not None:
        indicators["ema9"] = round(ema9, 8)
    if ema21 is not None:
        indicators["ema21"] = round(ema21, 8)
    if rsi is not None:
        indicators["rsi14"] = round(rsi, 4)
    indicators["macd"] = macd_data
    if atr is not None:
        indicators["atr14"] = atr

    explanation = (
        f"Signal: {signal} | Trend: {trend} | Confidence: {confidence:.0%}. "
        + " ".join(reasons)
        + " Note: this is a probabilistic indicator only, not a guarantee."
    )

    return {
        "symbol": symbol,
        "timeframe": timeframe,
        "signal": signal,
        "confidence": confidence,
        "trend": trend,
        "indicators": indicators,
        "support_resistance": sr,
        "candle_analysis": candle_stats,
        "explanation": explanation,
        "errors": [],
    }
