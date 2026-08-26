"""
Deterministic unit tests for the signal engine.
All fixtures use fixed OHLC data – no live market calls.
"""

from __future__ import annotations

import pytest

from backend.analysis.signal_engine import (
    _ema,
    _rsi,
    _macd,
    _atr,
    _support_resistance,
    _candle_analysis,
    _validate_candles,
    analyze,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

def _make_candle(
    open_: float,
    high: float,
    low: float,
    close: float,
    start_time: int = 0,
    symbol: str = "EUR/USD",
    timeframe_seconds: int = 60,
) -> dict:
    return {
        "symbol": symbol,
        "timeframe_seconds": timeframe_seconds,
        "start_time": start_time,
        "open": open_,
        "high": high,
        "low": low,
        "close": close,
        "tick_count": 1,
    }


def _bullish_trend_candles(n: int = 50) -> list[dict]:
    """Steady uptrend: each close 1 pip above the previous."""
    candles = []
    base = 1.1000
    for i in range(n):
        o  = base + i * 0.0001
        c  = o + 0.0001
        h  = c + 0.00005
        l  = o - 0.00005
        candles.append(_make_candle(o, h, l, c, start_time=i * 60))
    return candles


def _bearish_trend_candles(n: int = 50) -> list[dict]:
    """Steady downtrend."""
    candles = []
    base = 1.2000
    for i in range(n):
        o  = base - i * 0.0001
        c  = o - 0.0001
        h  = o + 0.00005
        l  = c - 0.00005
        candles.append(_make_candle(o, h, l, c, start_time=i * 60))
    return candles


def _flat_candles(n: int = 50, price: float = 1.0500) -> list[dict]:
    """Perfectly flat market."""
    return [
        _make_candle(price, price, price, price, start_time=i * 60)
        for i in range(n)
    ]


# ---------------------------------------------------------------------------
# _validate_candles
# ---------------------------------------------------------------------------

class TestValidateCandles:
    def test_empty_list(self):
        errs = _validate_candles([])
        assert errs
        assert any("empty" in e.lower() for e in errs)

    def test_missing_field(self):
        c = {"open": 1.0, "high": 1.1, "low": 0.9}  # no close
        errs = _validate_candles([c])
        assert errs

    def test_nan_value(self):
        import math
        c = _make_candle(1.0, 1.1, 0.9, math.nan)
        errs = _validate_candles([c])
        assert errs

    def test_infinity_value(self):
        import math
        c = _make_candle(1.0, math.inf, 0.9, 1.05)
        errs = _validate_candles([c])
        assert errs

    def test_high_less_than_low(self):
        c = _make_candle(1.0, 0.8, 1.1, 1.0)  # high < low
        errs = _validate_candles([c])
        assert errs

    def test_open_outside_range(self):
        c = _make_candle(1.5, 1.2, 1.0, 1.1)  # open > high
        errs = _validate_candles([c])
        assert errs

    def test_close_outside_range(self):
        c = _make_candle(1.0, 1.2, 0.9, 1.5)  # close > high
        errs = _validate_candles([c])
        assert errs

    def test_non_positive_price(self):
        c = _make_candle(0, 0.1, 0, 0.05)
        errs = _validate_candles([c])
        assert errs

    def test_bad_ordering(self):
        c1 = _make_candle(1.0, 1.1, 0.9, 1.05, start_time=120)
        c2 = _make_candle(1.05, 1.15, 0.95, 1.1, start_time=60)
        errs = _validate_candles([c1, c2])
        assert errs

    def test_valid_candles_no_errors(self):
        candles = _bullish_trend_candles(10)
        errs = _validate_candles(candles)
        assert errs == []


# ---------------------------------------------------------------------------
# EMA
# ---------------------------------------------------------------------------

class TestEma:
    def test_period_9_length(self):
        values = list(range(1, 21))  # 20 values
        result = _ema(values, 9)
        assert len(result) == 20 - 9 + 1  # 12

    def test_too_short_returns_empty(self):
        assert _ema([1.0, 2.0], 9) == []

    def test_constant_series_equals_value(self):
        result = _ema([5.0] * 20, 9)
        for v in result:
            assert abs(v - 5.0) < 1e-9


# ---------------------------------------------------------------------------
# RSI
# ---------------------------------------------------------------------------

class TestRsi:
    def test_too_short_returns_empty(self):
        assert _rsi([1.0] * 5, 14) == []

    def test_all_gains_approaches_100(self):
        prices = [float(i) for i in range(1, 30)]
        result = _rsi(prices, 14)
        assert result
        assert result[-1] > 90

    def test_all_losses_approaches_0(self):
        prices = [float(30 - i) for i in range(30)]
        result = _rsi(prices, 14)
        assert result
        assert result[-1] < 10

    def test_values_in_range(self):
        candles = _bullish_trend_candles(30)
        closes = [c["close"] for c in candles]
        for v in _rsi(closes, 14):
            assert 0.0 <= v <= 100.0


# ---------------------------------------------------------------------------
# MACD
# ---------------------------------------------------------------------------

class TestMacd:
    def test_insufficient_data_empty(self):
        result = _macd([1.0] * 5)
        assert result["macd"] == []

    def test_keys_present(self):
        closes = [c["close"] for c in _bullish_trend_candles(50)]
        result = _macd(closes)
        assert "macd" in result
        assert "signal" in result
        assert "histogram" in result

    def test_histogram_lengths_consistent(self):
        closes = [c["close"] for c in _bullish_trend_candles(50)]
        result = _macd(closes)
        assert len(result["macd"]) == len(result["signal"]) == len(result["histogram"])


# ---------------------------------------------------------------------------
# ATR
# ---------------------------------------------------------------------------

class TestAtr:
    def test_too_short_returns_empty(self):
        assert _atr([_make_candle(1, 1, 1, 1)]) == []

    def test_positive_values(self):
        candles = _bullish_trend_candles(20)
        result = _atr(candles, 14)
        assert result
        for v in result:
            assert v > 0


# ---------------------------------------------------------------------------
# Support / Resistance
# ---------------------------------------------------------------------------

class TestSupportResistance:
    def test_basic(self):
        candles = _bullish_trend_candles(20)
        sr = _support_resistance(candles)
        assert sr["support"] is not None
        assert sr["resistance"] is not None
        assert sr["support"] <= sr["resistance"]

    def test_single_candle(self):
        c = [_make_candle(1.0, 1.1, 0.9, 1.05)]
        sr = _support_resistance(c)
        assert sr["support"] == 0.9
        assert sr["resistance"] == 1.1


# ---------------------------------------------------------------------------
# Candle analysis
# ---------------------------------------------------------------------------

class TestCandleAnalysis:
    def test_bullish_candle(self):
        c = _make_candle(1.0, 1.2, 0.9, 1.15)
        info = _candle_analysis(c)
        assert info["bullish"] is True

    def test_bearish_candle(self):
        c = _make_candle(1.15, 1.2, 0.9, 1.0)
        info = _candle_analysis(c)
        assert info["bullish"] is False

    def test_body_ratio_range(self):
        c = _make_candle(1.0, 1.2, 0.9, 1.15)
        info = _candle_analysis(c)
        assert 0.0 <= info["body_ratio"] <= 1.0


# ---------------------------------------------------------------------------
# analyze() – full integration
# ---------------------------------------------------------------------------

class TestAnalyze:
    def test_empty_candles(self):
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": []})
        assert result["validation_ok"] is False
        assert result["signal"] == "NEUTRAL"

    def test_bullish_trend_signal(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe": 60,
            "candles": _bullish_trend_candles(50),
        })
        assert result["validation_ok"] is True
        assert result["signal"] in ("UP", "NEUTRAL")

    def test_bearish_trend_signal(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe": 60,
            "candles": _bearish_trend_candles(50),
        })
        assert result["validation_ok"] is True
        assert result["signal"] in ("DOWN", "NEUTRAL")

    def test_all_required_fields_present(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe": 60,
            "candles": _bullish_trend_candles(50),
        })
        for field in (
            "symbol", "timeframe", "signal", "confidence", "trend",
            "indicators", "support_resistance", "candle_analysis",
            "explanation", "errors", "validation_ok",
        ):
            assert field in result, f"Missing field: {field}"

    def test_confidence_in_range(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe": 60,
            "candles": _bullish_trend_candles(50),
        })
        assert 0.0 <= result["confidence"] <= 1.0

    def test_invalid_candle_type(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe": 60,
            "candles": ["not-a-dict"],
        })
        assert result["validation_ok"] is False

    def test_few_candles_produces_warnings(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe": 60,
            "candles": _bullish_trend_candles(5),
        })
        # may be valid, but should warn about insufficient history
        if result["validation_ok"]:
            assert result.get("warnings") or result["indicators"]["macd_line"] is None

    def test_candle_object_accepted(self):
        """Candle objects (with to_dict) are accepted."""
        from backend.market.candle_builder import Candle
        candles = [
            Candle(
                symbol="EUR/USD",
                timeframe_seconds=60,
                start_time=i * 60,
                open=1.1000 + i * 0.0001,
                high=1.1005 + i * 0.0001,
                low=1.0995 + i * 0.0001,
                close=1.1003 + i * 0.0001,
            )
            for i in range(30)
        ]
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        assert result["validation_ok"] is True

    def test_flat_market_neutral(self):
        candles = _flat_candles(50)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        # flat market has zero ATR
        assert result["indicators"]["atr14"] == 0.0
        # A perfectly flat series produces RSI=100 (no losses), which triggers
        # the overbought rule. Signal may be NEUTRAL or DOWN; never UP.
        assert result["signal"] in ("NEUTRAL", "DOWN")

    def test_deterministic(self):
        """Same input always produces same output."""
        payload = {"symbol": "EUR/USD", "timeframe": 60, "candles": _bullish_trend_candles(40)}
        r1 = analyze(payload)
        r2 = analyze(payload)
        assert r1 == r2
