"""
Market Analysis / Signal Engine
================================
Consumes a list of OHLC candle dicts (or Candle objects) and produces a
structured analysis result including technical indicators, support/resistance
levels, a directional signal, and a human-readable explanation.

No fake prices, no random values.  Every field is derived purely from the
candles supplied by the caller.  When there is insufficient data the engine
returns a safe NEUTRAL result rather than inventing values.
"""

from __future__ import annotations

import math
from typing import Any

# ---------------------------------------------------------------------------
# Minimum candle counts required for each indicator
# ---------------------------------------------------------------------------
_MIN_EMA9 = 9
_MIN_EMA21 = 21
_MIN_RSI = 15       # RSI-14 needs at least 15 candles for a meaningful value
_MIN_MACD = 35      # MACD(12,26,9) needs ≥ 26 + 9 candles
_MIN_ATR = 14
_MIN_SR = 10        # support/resistance window
_MIN_SIGNAL = 21    # bare minimum to produce a non-NEUTRAL signal


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _to_float_list(values: list[Any]) -> list[float]:
    """Convert a list of values to floats, raising ValueError on bad data."""
    result: list[float] = []
    for v in values:
        f = float(v)
        if math.isnan(f) or math.isinf(f):
            raise ValueError(f"Invalid price value: {v!r}")
        result.append(f)
    return result


def _ema(prices: list[float], period: int) -> list[float]:
    """
    Return the Exponential Moving Average series for *prices*.

    The first value is seeded with the simple mean of the first *period*
    prices; subsequent values use the standard EMA multiplier.

    Returns an empty list when len(prices) < period.
    """
    if len(prices) < period:
        return []
    k = 2.0 / (period + 1)
    ema_values: list[float] = []
    seed = sum(prices[:period]) / period
    ema_values.append(seed)
    for price in prices[period:]:
        ema_values.append(price * k + ema_values[-1] * (1 - k))
    return ema_values


def _rsi(closes: list[float], period: int = 14) -> float | None:
    """
    Compute the most-recent RSI value.

    Returns None when there are fewer than period + 1 close prices.
    Uses the Wilder smoothed method (exponential smoothing with α=1/period).
    """
    if len(closes) < period + 1:
        return None

    gains: list[float] = []
    losses: list[float] = []
    for i in range(1, len(closes)):
        diff = closes[i] - closes[i - 1]
        gains.append(max(diff, 0.0))
        losses.append(max(-diff, 0.0))

    # Seed with simple average of the first *period* changes
    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period

    for i in range(period, len(gains)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period

    if avg_loss == 0.0:
        return 100.0
    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


def _macd(
    closes: list[float],
    fast: int = 12,
    slow: int = 26,
    signal_period: int = 9,
) -> dict[str, float | None]:
    """
    Return MACD line, signal line, and histogram.

    All three are None when there are insufficient closes.
    """
    empty: dict[str, float | None] = {
        "macd_line": None,
        "signal_line": None,
        "histogram": None,
    }
    if len(closes) < slow + signal_period:
        return empty

    ema_fast = _ema(closes, fast)
    ema_slow = _ema(closes, slow)

    # Align the two series (ema_fast is longer by slow - fast values)
    offset = slow - fast
    macd_line = [
        ema_fast[offset + i] - ema_slow[i] for i in range(len(ema_slow))
    ]

    signal_series = _ema(macd_line, signal_period)
    if not signal_series:
        return empty

    ml = macd_line[-1]
    sl = signal_series[-1]
    return {
        "macd_line": round(ml, 8),
        "signal_line": round(sl, 8),
        "histogram": round(ml - sl, 8),
    }


def _atr(
    highs: list[float],
    lows: list[float],
    closes: list[float],
    period: int = 14,
) -> float | None:
    """
    Compute Average True Range (Wilder smoothing).

    Returns None when there are fewer than period + 1 candles.
    """
    if len(closes) < period + 1:
        return None

    trs: list[float] = []
    for i in range(1, len(closes)):
        tr = max(
            highs[i] - lows[i],
            abs(highs[i] - closes[i - 1]),
            abs(lows[i] - closes[i - 1]),
        )
        trs.append(tr)

    atr = sum(trs[:period]) / period
    for i in range(period, len(trs)):
        atr = (atr * (period - 1) + trs[i]) / period

    return round(atr, 8)


def _support_resistance(
    highs: list[float],
    lows: list[float],
    window: int = 10,
) -> tuple[float, float]:
    """
    Return (support, resistance) as the min low and max high over the last
    *window* candles.
    """
    w_highs = highs[-window:]
    w_lows = lows[-window:]
    return min(w_lows), max(w_highs)


def _candle_info(candle: dict[str, Any]) -> dict[str, float]:
    """
    Compute body size, upper wick, lower wick, and total range for a candle.
    All values are absolute (non-negative).
    """
    o = float(candle["open"])
    h = float(candle["high"])
    l = float(candle["low"])
    c = float(candle["close"])
    body = abs(c - o)
    total_range = h - l if h != l else 0.0
    upper_wick = h - max(o, c)
    lower_wick = min(o, c) - l
    body_ratio = (body / total_range) if total_range > 0 else 0.0
    return {
        "body": round(body, 8),
        "upper_wick": round(upper_wick, 8),
        "lower_wick": round(lower_wick, 8),
        "total_range": round(total_range, 8),
        "body_ratio": round(body_ratio, 4),
        "is_bullish": c >= o,
    }


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    """
    Entry point called by the FastAPI /api/analyze endpoint.

    Expected payload keys:
        symbol      (str)  – e.g. "EUR/USD"
        timeframe   (int)  – timeframe in seconds
        candles     (list) – list of OHLC dicts from a MarketDataProvider
                             Each dict must contain: open, high, low, close
                             (and optionally start_time / tick_count).

    Returns a structured AnalysisResult dict.
    """
    symbol: str = str(payload.get("symbol", "UNKNOWN"))
    timeframe: int = int(payload.get("timeframe", 0))
    raw_candles: list[dict[str, Any]] = payload.get("candles") or []

    # ------------------------------------------------------------------
    # Validate and extract price series
    # ------------------------------------------------------------------
    try:
        candles = _validate_candles(raw_candles)
    except (ValueError, TypeError, KeyError):
        return _insufficient_result(
            symbol, timeframe, reason="Invalid candle data: check OHLC values."
        )

    n = len(candles)

    if n < _MIN_SIGNAL:
        return _insufficient_result(
            symbol,
            timeframe,
            reason=f"Need at least {_MIN_SIGNAL} candles, got {n}.",
        )

    opens = [c["open"] for c in candles]
    highs = [c["high"] for c in candles]
    lows = [c["low"] for c in candles]
    closes = [c["close"] for c in candles]

    current_price = closes[-1]
    last_candle = candles[-1]

    # ------------------------------------------------------------------
    # Technical indicators
    # ------------------------------------------------------------------
    ema9_series = _ema(closes, 9) if n >= _MIN_EMA9 else []
    ema21_series = _ema(closes, 21) if n >= _MIN_EMA21 else []
    ema9: float | None = round(ema9_series[-1], 8) if ema9_series else None
    ema21: float | None = round(ema21_series[-1], 8) if ema21_series else None

    rsi: float | None = _rsi(closes) if n >= _MIN_RSI else None
    if rsi is not None:
        rsi = round(rsi, 2)

    macd_result = _macd(closes) if n >= _MIN_MACD else {
        "macd_line": None, "signal_line": None, "histogram": None,
    }

    atr: float | None = _atr(highs, lows, closes) if n >= _MIN_ATR else None

    support, resistance = _support_resistance(highs, lows)
    candle_stats = _candle_info(last_candle)

    # ------------------------------------------------------------------
    # Trend direction
    # ------------------------------------------------------------------
    trend = _determine_trend(closes, ema9, ema21)

    # ------------------------------------------------------------------
    # Signal logic
    # ------------------------------------------------------------------
    signal, confidence, explanation = _generate_signal(
        current_price=current_price,
        trend=trend,
        ema9=ema9,
        ema21=ema21,
        rsi=rsi,
        macd=macd_result,
        atr=atr,
        support=support,
        resistance=resistance,
        candle_stats=candle_stats,
        n_candles=n,
    )

    return {
        "symbol": symbol,
        "timeframe_seconds": timeframe,
        "current_price": round(current_price, 8),
        "trend": trend,
        "rsi": rsi,
        "macd": macd_result,
        "ema": {
            "ema9": ema9,
            "ema21": ema21,
        },
        "atr": atr,
        "support": round(support, 8),
        "resistance": round(resistance, 8),
        "candle": {
            "open": round(last_candle["open"], 8),
            "high": round(last_candle["high"], 8),
            "low": round(last_candle["low"], 8),
            "close": round(last_candle["close"], 8),
            **candle_stats,
        },
        "signal": signal,
        "confidence": confidence,
        "explanation": explanation,
        "candles_used": n,
    }


# ---------------------------------------------------------------------------
# Helpers used inside analyze()
# ---------------------------------------------------------------------------

def _validate_candles(
    raw: list[Any],
) -> list[dict[str, float]]:
    """
    Validate and normalise a list of raw candle dicts.

    Each candle must contain open, high, low, close as finite positive floats
    with high ≥ open, close, low and low ≤ open, close.
    """
    if not isinstance(raw, list):
        raise TypeError("candles must be a list")
    result: list[dict[str, float]] = []
    for i, c in enumerate(raw):
        if not isinstance(c, dict):
            raise TypeError(f"Candle {i} is not a dict")
        o = _safe_float(c, "open", i)
        h = _safe_float(c, "high", i)
        l = _safe_float(c, "low", i)  # noqa: E741
        cl = _safe_float(c, "close", i)
        if not (h >= l and l <= o <= h and l <= cl <= h):
            raise ValueError(
                f"Candle {i} OHLC constraint violated: "
                f"O={o} H={h} L={l} C={cl}"
            )
        result.append({"open": o, "high": h, "low": l, "close": cl})
    return result


def _safe_float(d: dict[str, Any], key: str, idx: int) -> float:
    """Extract and validate a single float from a candle dict."""
    if key not in d:
        raise KeyError(f"Candle {idx} missing key '{key}'")
    v = float(d[key])
    if math.isnan(v) or math.isinf(v):
        raise ValueError(f"Candle {idx} '{key}' is not finite: {v}")
    if v < 0:
        raise ValueError(f"Candle {idx} '{key}' is negative: {v}")
    return v


def _determine_trend(
    closes: list[float],
    ema9: float | None,
    ema21: float | None,
) -> str:
    """
    Classify trend as 'UP', 'DOWN', or 'SIDEWAYS'.

    Rules (in priority order):
    1. EMA9 vs EMA21 crossover relationship when both are available.
    2. Simple linear regression slope of the last 10 closes otherwise.
    """
    if ema9 is not None and ema21 is not None:
        if ema9 > ema21 * 1.0001:
            return "UP"
        if ema9 < ema21 * 0.9999:
            return "DOWN"
        return "SIDEWAYS"

    # Fallback: slope of last 10 closes
    window = closes[-10:]
    n = len(window)
    if n < 2:
        return "SIDEWAYS"
    mean_x = (n - 1) / 2
    mean_y = sum(window) / n
    num = sum((i - mean_x) * (window[i] - mean_y) for i in range(n))
    denom = sum((i - mean_x) ** 2 for i in range(n))
    slope = num / denom if denom else 0.0
    threshold = mean_y * 0.0002  # 0.02% per candle
    if slope > threshold:
        return "UP"
    if slope < -threshold:
        return "DOWN"
    return "SIDEWAYS"


def _generate_signal(
    *,
    current_price: float,
    trend: str,
    ema9: float | None,
    ema21: float | None,
    rsi: float | None,
    macd: dict[str, float | None],
    atr: float | None,
    support: float,
    resistance: float,
    candle_stats: dict[str, Any],
    n_candles: int,
) -> tuple[str, float, str]:
    """
    Produce (signal, confidence, explanation) using transparent rule-based logic.

    Signal values: "UP" | "DOWN" | "NEUTRAL"
    Confidence: 0.0 – 1.0

    The function accumulates weighted votes from independent signals:
        +1 vote → bullish evidence
        -1 vote → bearish evidence
         0 vote → neutral / absent

    Final score is normalised to [0, 1] relative to the total weight of
    *available* (non-None) indicators.  This avoids penalising results where
    some indicators cannot be computed yet.

    Note: This engine describes statistical tendencies in historical data.
    It does NOT guarantee the direction of the next candle.
    """
    reasons: list[str] = []
    score = 0.0
    max_score = 0.0

    # ---- Trend (weight 2) -----------------------------------------------
    max_score += 2.0
    if trend == "UP":
        score += 2.0
        if ema9 is not None and ema21 is not None:
            reasons.append("trend is UP (EMA9 > EMA21)")
        else:
            reasons.append("trend is UP (rising price slope)")
    elif trend == "DOWN":
        score -= 2.0
        if ema9 is not None and ema21 is not None:
            reasons.append("trend is DOWN (EMA9 < EMA21)")
        else:
            reasons.append("trend is DOWN (falling price slope)")
    else:
        reasons.append("trend is SIDEWAYS")

    # ---- EMA crossover (weight 1.5) ------------------------------------
    if ema9 is not None and ema21 is not None:
        max_score += 1.5
        if ema9 > ema21:
            score += 1.5
            reasons.append(f"EMA9 ({ema9:.5f}) > EMA21 ({ema21:.5f}) — bullish alignment")
        else:
            score -= 1.5
            reasons.append(f"EMA9 ({ema9:.5f}) < EMA21 ({ema21:.5f}) — bearish alignment")

    # ---- RSI (weight 1.5) -----------------------------------------------
    if rsi is not None:
        max_score += 1.5
        if rsi < 30:
            score += 1.5
            reasons.append(f"RSI={rsi:.1f} — oversold, potential reversal UP")
        elif rsi > 70:
            score -= 1.5
            reasons.append(f"RSI={rsi:.1f} — overbought, potential reversal DOWN")
        elif 40 <= rsi <= 60:
            reasons.append(f"RSI={rsi:.1f} — neutral zone")
        elif rsi > 60:
            score += 0.5
            reasons.append(f"RSI={rsi:.1f} — mildly bullish momentum")
        else:
            score -= 0.5
            reasons.append(f"RSI={rsi:.1f} — mildly bearish momentum")

    # ---- MACD histogram (weight 1.5) ------------------------------------
    ml = macd.get("macd_line")
    sl = macd.get("signal_line")
    hist = macd.get("histogram")
    if ml is not None and sl is not None and hist is not None:
        max_score += 1.5
        if hist > 0 and ml > sl:
            score += 1.5
            reasons.append(f"MACD histogram {hist:.6f} positive — bullish momentum")
        elif hist < 0 and ml < sl:
            score -= 1.5
            reasons.append(f"MACD histogram {hist:.6f} negative — bearish momentum")
        else:
            reasons.append(f"MACD histogram {hist:.6f} — mixed signal")

    # ---- Price vs support / resistance (weight 1) ----------------------
    max_score += 1.0
    sr_range = resistance - support
    if sr_range > 0:
        rel_pos = (current_price - support) / sr_range
        if rel_pos < 0.25:
            score += 1.0
            reasons.append(
                f"Price ({current_price:.5f}) near support ({support:.5f}) — potential bounce"
            )
        elif rel_pos > 0.75:
            score -= 1.0
            reasons.append(
                f"Price ({current_price:.5f}) near resistance ({resistance:.5f}) — potential rejection"
            )
        else:
            reasons.append(
                f"Price ({current_price:.5f}) mid-range between support/resistance"
            )

    # ---- Candle body direction (weight 0.5) ----------------------------
    max_score += 0.5
    if candle_stats["is_bullish"] and candle_stats["body_ratio"] > 0.5:
        score += 0.5
        reasons.append("Last candle is a strong bullish bar")
    elif not candle_stats["is_bullish"] and candle_stats["body_ratio"] > 0.5:
        score -= 0.5
        reasons.append("Last candle is a strong bearish bar")
    else:
        reasons.append("Last candle shows indecision (small body or long wicks)")

    # ---- Convert to signal + confidence --------------------------------
    if max_score == 0:
        return "NEUTRAL", 0.0, "Insufficient indicators to produce a signal."

    normalised = score / max_score          # range [-1, +1]
    confidence = round(abs(normalised), 2)  # how strongly we lean either way

    if normalised > 0.2:
        signal = "UP"
    elif normalised < -0.2:
        signal = "DOWN"
    else:
        signal = "NEUTRAL"

    explanation = (
        f"Signal: {signal} (confidence {confidence:.0%}). "
        + " | ".join(reasons)
        + ". Note: past candle patterns do not guarantee future price direction."
    )

    return signal, confidence, explanation


def _insufficient_result(
    symbol: str,
    timeframe: int,
    reason: str,
) -> dict[str, Any]:
    """Return a safe NEUTRAL result when analysis cannot be performed."""
    return {
        "symbol": symbol,
        "timeframe_seconds": timeframe,
        "current_price": None,
        "trend": "UNKNOWN",
        "rsi": None,
        "macd": {"macd_line": None, "signal_line": None, "histogram": None},
        "ema": {"ema9": None, "ema21": None},
        "atr": None,
        "support": None,
        "resistance": None,
        "candle": None,
        "signal": "NEUTRAL",
        "confidence": 0.0,
        "explanation": reason,
        "candles_used": 0,
    }
