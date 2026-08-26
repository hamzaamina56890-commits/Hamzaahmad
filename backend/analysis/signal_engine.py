"""
Signal engine: fetches verified candle data and computes technical analysis.

Data source: Twelve Data REST API (TWELVE_DATA_API_KEY env var required).
If no API key is present, returns NEUTRAL / insufficient data — never fake data.
"""

from __future__ import annotations

import json
import os
import urllib.parse
import urllib.request
from typing import Any

# Map timeframe_seconds to Twelve Data REST interval strings.
# Twelve Data minimum granularity is 1 minute.
_INTERVAL_MAP: dict[int, str] = {
    5: "1min",
    10: "1min",
    15: "1min",
    30: "1min",
    60: "1min",
    120: "1min",
    180: "1min",
    300: "5min",
}

_TWELVE_DATA_BASE = "https://api.twelvedata.com"


def _fetch_candles(symbol: str, interval: str, outputsize: int = 80) -> list[dict]:
    """Fetch OHLC candles from Twelve Data REST API. Returns [] when unavailable."""
    api_key = os.getenv("TWELVE_DATA_API_KEY")
    if not api_key:
        return []

    params = urllib.parse.urlencode(
        {
            "symbol": symbol,
            "interval": interval,
            "outputsize": outputsize,
            "apikey": api_key,
            "order": "ASC",
        }
    )
    url = f"{_TWELVE_DATA_BASE}/time_series?{params}"

    try:
        with urllib.request.urlopen(url, timeout=10) as resp:  # noqa: S310
            data = json.loads(resp.read())
    except Exception:
        return []

    if data.get("status") != "ok":
        return []

    candles = []
    for entry in data.get("values", []):
        try:
            candles.append(
                {
                    "open": float(entry["open"]),
                    "high": float(entry["high"]),
                    "low": float(entry["low"]),
                    "close": float(entry["close"]),
                    "datetime": entry.get("datetime", ""),
                }
            )
        except (KeyError, ValueError):
            continue

    return candles


def _compute_rsi(closes: list[float], period: int = 14) -> float | None:
    if len(closes) < period + 1:
        return None

    gains: list[float] = []
    losses: list[float] = []
    for i in range(1, period + 1):
        diff = closes[i] - closes[i - 1]
        gains.append(max(diff, 0.0))
        losses.append(max(-diff, 0.0))

    avg_gain = sum(gains) / period
    avg_loss = sum(losses) / period

    for i in range(period + 1, len(closes)):
        diff = closes[i] - closes[i - 1]
        avg_gain = (avg_gain * (period - 1) + max(diff, 0.0)) / period
        avg_loss = (avg_loss * (period - 1) + max(-diff, 0.0)) / period

    if avg_loss == 0:
        return 100.0

    rs = avg_gain / avg_loss
    return round(100 - (100 / (1 + rs)), 2)


def _sma(values: list[float], period: int) -> float | None:
    if len(values) < period:
        return None
    return round(sum(values[-period:]) / period, 5)


def _ema(values: list[float], period: int) -> float | None:
    if len(values) < period:
        return None
    k = 2 / (period + 1)
    ema = sum(values[:period]) / period
    for v in values[period:]:
        ema = v * k + ema * (1 - k)
    return round(ema, 5)


def _candle_strength(candles: list[dict]) -> str:
    if not candles:
        return "unknown"
    last = candles[-1]
    body = abs(last["close"] - last["open"])
    wick = last["high"] - last["low"]
    if wick == 0:
        return "doji"
    ratio = body / wick
    if ratio > 0.7:
        return "strong"
    if ratio > 0.4:
        return "moderate"
    return "weak / doji-wick"


def analyze(payload: dict) -> dict[str, Any]:
    symbol: str = (payload.get("symbol") or "").strip()
    timeframe_seconds: int = int(payload.get("timeframe_seconds", 60))

    if not symbol:
        return {
            "symbol": symbol,
            "timeframe_seconds": timeframe_seconds,
            "signal": "NEUTRAL",
            "confidence": 0,
            "trend": "unknown",
            "reasons": ["No symbol provided."],
            "live": False,
            "data_source": "none",
            "price": None,
            "candles": [],
            "indicators": {},
        }

    interval = _INTERVAL_MAP.get(timeframe_seconds, "1min")
    # Note if the requested sub-minute timeframe is coarser than requested
    actual_seconds = {"1min": 60, "5min": 300}.get(interval, 60)
    resolution_note = (
        f"Note: requested timeframe ({timeframe_seconds}s) uses {interval} candles "
        f"(Twelve Data minimum granularity is 1 minute)."
        if actual_seconds > timeframe_seconds
        else None
    )

    candles = _fetch_candles(symbol, interval)

    if len(candles) < 20:
        reasons = [
            "Insufficient verified data. "
            "Set the TWELVE_DATA_API_KEY environment variable to enable live analysis."
        ]
        if resolution_note:
            reasons.append(resolution_note)
        return {
            "symbol": symbol,
            "timeframe_seconds": timeframe_seconds,
            "signal": "NEUTRAL",
            "confidence": 0,
            "trend": "unknown",
            "reasons": reasons,
            "live": False,
            "data_source": "none",
            "price": None,
            "candles": [],
            "indicators": {},
        }

    closes = [c["close"] for c in candles]
    price = closes[-1]

    rsi = _compute_rsi(closes)
    sma20 = _sma(closes, 20)
    sma50 = _sma(closes, 50)
    ema9 = _ema(closes, 9)

    recent = candles[-20:]
    resistance = round(max(c["high"] for c in recent), 5)
    support = round(min(c["low"] for c in recent), 5)
    strength = _candle_strength(candles)

    trend = "neutral"
    if sma20 is not None and sma50 is not None:
        if sma20 > sma50:
            trend = "uptrend"
        elif sma20 < sma50:
            trend = "downtrend"

    reasons: list[str] = []
    bullish = 0
    bearish = 0

    if rsi is not None:
        if rsi < 35:
            bullish += 1
            reasons.append(f"RSI {rsi} — oversold zone")
        elif rsi > 65:
            bearish += 1
            reasons.append(f"RSI {rsi} — overbought zone")
        else:
            reasons.append(f"RSI {rsi} — neutral zone")

    if sma20 is not None and sma50 is not None:
        if sma20 > sma50:
            bullish += 1
            reasons.append("SMA20 above SMA50 — bullish alignment")
        elif sma20 < sma50:
            bearish += 1
            reasons.append("SMA20 below SMA50 — bearish alignment")

    if sma20 is not None:
        if price > sma20:
            bullish += 1
            reasons.append("Price above SMA20")
        else:
            bearish += 1
            reasons.append("Price below SMA20")

    last = candles[-1]
    if last["close"] > last["open"]:
        bullish += 1
        reasons.append("Last candle closed bullish")
    elif last["close"] < last["open"]:
        bearish += 1
        reasons.append("Last candle closed bearish")
    else:
        reasons.append("Last candle is a doji")

    total = bullish + bearish
    if total == 0 or bullish == bearish:
        signal = "NEUTRAL"
        confidence = 0  # split or no signals — not directional
    elif bullish > bearish:
        signal = "UP"
        confidence = round((bullish / total) * 100)
    else:
        signal = "DOWN"
        confidence = round((bearish / total) * 100)

    if resolution_note:
        reasons.append(resolution_note)

    return {
        "symbol": symbol,
        "timeframe_seconds": timeframe_seconds,
        "signal": signal,
        "confidence": confidence,
        "trend": trend,
        "price": round(price, 5),
        "live": True,
        "data_source": "Twelve Data REST API",
        "reasons": reasons,
        "candles": candles[-60:],
        "indicators": {
            "rsi": rsi,
            "sma20": sma20,
            "sma50": sma50,
            "ema9": ema9,
            "support": support,
            "resistance": resistance,
            "candle_strength": strength,
        },
    }
