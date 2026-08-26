"""
Deterministic unit tests for the Market Analysis / Signal Engine.

All candle data is fixed in this file – no live market data, no network calls.
Tests verify correctness of indicators, validation, signal logic, and the
public `analyze()` entry-point used by POST /api/analyze.
"""
import math
import pytest

from backend.analysis.signal_engine import (
    _ema,
    _rsi,
    _macd,
    _atr,
    _support_resistance,
    _candle_analysis,
    _validate,
    analyze,
)


# ---------------------------------------------------------------------------
# Fixed OHLC candle data
# ---------------------------------------------------------------------------

def _make_candle(o, h, l, c):
    return {"open": o, "high": h, "low": l, "close": c}


# 40 rising candles (deterministic)
RISING_CANDLES = [
    _make_candle(100 + i, 101 + i, 99 + i, 100.5 + i)
    for i in range(40)
]

# 40 falling candles
FALLING_CANDLES = [
    _make_candle(140 - i, 141 - i, 139 - i, 139.5 - i)
    for i in range(40)
]

# Minimal 2-candle set
TWO_CANDLES = [
    _make_candle(1.0, 1.2, 0.9, 1.1),
    _make_candle(1.1, 1.3, 1.0, 1.25),
]

# Single candle (below minimum)
ONE_CANDLE = [_make_candle(1.0, 1.2, 0.9, 1.1)]

# ---------------------------------------------------------------------------
# _validate
# ---------------------------------------------------------------------------

class TestValidate:
    def test_empty_list(self):
        errors = _validate([])
        assert any("Empty" in e for e in errors)

    def test_single_candle(self):
        errors = _validate(ONE_CANDLE)
        assert any("Insufficient" in e for e in errors)

    def test_two_candles_ok(self):
        assert _validate(TWO_CANDLES) == []

    def test_nan_field(self):
        bad = [_make_candle(1.0, float("nan"), 0.9, 1.1),
               _make_candle(1.0, 1.2, 0.9, 1.1)]
        errors = _validate(bad)
        assert any("NaN" in e for e in errors)

    def test_inf_field(self):
        bad = [_make_candle(1.0, float("inf"), 0.9, 1.1),
               _make_candle(1.0, 1.2, 0.9, 1.1)]
        errors = _validate(bad)
        assert any("Infinity" in e for e in errors)

    def test_negative_price(self):
        bad = [_make_candle(-1.0, 1.2, 0.9, 1.1),
               _make_candle(1.0, 1.2, 0.9, 1.1)]
        errors = _validate(bad)
        assert any("positive" in e for e in errors)

    def test_zero_price(self):
        bad = [_make_candle(0.0, 1.2, 0.9, 1.1),
               _make_candle(1.0, 1.2, 0.9, 1.1)]
        errors = _validate(bad)
        assert any("positive" in e for e in errors)

    def test_high_less_than_close(self):
        bad = [_make_candle(1.0, 0.8, 0.7, 1.1),
               _make_candle(1.0, 1.2, 0.9, 1.1)]
        errors = _validate(bad)
        assert any("high" in e for e in errors)

    def test_low_greater_than_open(self):
        bad = [_make_candle(1.0, 1.2, 1.5, 1.1),
               _make_candle(1.0, 1.2, 0.9, 1.1)]
        errors = _validate(bad)
        assert any("low" in e for e in errors)

    def test_rising_candles_valid(self):
        assert _validate(RISING_CANDLES) == []


# ---------------------------------------------------------------------------
# _ema
# ---------------------------------------------------------------------------

class TestEma:
    def test_insufficient_data(self):
        assert _ema([1.0, 2.0], 9) is None

    def test_exact_period(self):
        values = [1.0] * 9
        result = _ema(values, 9)
        assert result == pytest.approx(1.0)

    def test_rising_series(self):
        closes = [float(i) for i in range(1, 30)]
        ema9 = _ema(closes, 9)
        ema21 = _ema(closes, 21)
        assert ema9 is not None
        assert ema21 is not None
        # In a rising series EMA9 should be higher than EMA21
        assert ema9 > ema21

    def test_deterministic(self):
        closes = [1.0, 1.1, 1.05, 1.2, 1.15, 1.3, 1.25, 1.4, 1.35, 1.5]
        r1 = _ema(closes, 9)
        r2 = _ema(closes, 9)
        assert r1 == r2


# ---------------------------------------------------------------------------
# _rsi
# ---------------------------------------------------------------------------

class TestRsi:
    def test_insufficient_data(self):
        assert _rsi(list(range(10)), 14) is None

    def test_all_gains_returns_100(self):
        # strictly increasing prices → RSI = 100
        closes = [float(i) for i in range(1, 20)]
        assert _rsi(closes, 14) == pytest.approx(100.0)

    def test_all_losses_returns_0(self):
        closes = [float(i) for i in range(20, 0, -1)]
        result = _rsi(closes, 14)
        assert result is not None
        assert result < 5.0

    def test_mid_range(self):
        # alternate up/down
        prices = [100.0]
        for i in range(20):
            prices.append(prices[-1] + (1 if i % 2 == 0 else -0.5))
        result = _rsi(prices, 14)
        assert result is not None
        assert 0.0 <= result <= 100.0


# ---------------------------------------------------------------------------
# _macd
# ---------------------------------------------------------------------------

class TestMacd:
    def test_insufficient_returns_none(self):
        result = _macd([1.0] * 10)
        assert result["line"] is None
        assert result["signal"] is None

    def test_enough_for_line(self):
        closes = [float(i) for i in range(1, 28)]  # 27 candles → line but no signal
        result = _macd(closes)
        assert result["line"] is not None

    def test_enough_for_signal(self):
        closes = [float(i) for i in range(1, 36)]  # 35 candles → full MACD
        result = _macd(closes)
        assert result["line"] is not None
        assert result["signal"] is not None
        assert result["histogram"] is not None

    def test_deterministic(self):
        closes = [float(i) for i in range(1, 36)]
        r1 = _macd(closes)
        r2 = _macd(closes)
        assert r1 == r2


# ---------------------------------------------------------------------------
# _atr
# ---------------------------------------------------------------------------

class TestAtr:
    def test_insufficient_data(self):
        assert _atr(RISING_CANDLES[:5], 14) is None

    def test_returns_float(self):
        result = _atr(RISING_CANDLES, 14)
        assert isinstance(result, float)
        assert result > 0

    def test_constant_candles_atr_equals_range(self):
        # Each candle has the same range (high-low = 2)
        candles = [_make_candle(10.0, 11.0, 9.0, 10.5)] * 20
        result = _atr(candles, 14)
        # TR = max(2, |11-10.5|, |9-10.5|) = max(2, 0.5, 1.5) = 2
        assert result == pytest.approx(2.0, abs=1e-4)


# ---------------------------------------------------------------------------
# _support_resistance
# ---------------------------------------------------------------------------

class TestSupportResistance:
    def test_basic(self):
        highs = [10.0, 12.0, 11.0, 13.0, 9.5]
        lows =  [8.0,  9.0,  7.5,  8.5,  7.0]
        sr = _support_resistance(highs, lows)
        assert sr["resistance"] == pytest.approx(13.0)
        assert sr["support"] == pytest.approx(7.0)

    def test_with_real_candles(self):
        highs = [c["high"] for c in RISING_CANDLES]
        lows = [c["low"] for c in RISING_CANDLES]
        sr = _support_resistance(highs, lows, lookback=20)
        assert sr["support"] is not None
        assert sr["resistance"] is not None
        assert sr["resistance"] > sr["support"]


# ---------------------------------------------------------------------------
# _candle_analysis
# ---------------------------------------------------------------------------

class TestCandleAnalysis:
    def test_full_body(self):
        c = _make_candle(10.0, 10.0, 10.0, 10.0)  # doji / zero range
        result = _candle_analysis(c)
        assert result["body_ratio"] == 0.0

    def test_body_plus_wicks_sum_to_one(self):
        c = _make_candle(10.0, 12.0, 8.0, 11.0)
        result = _candle_analysis(c)
        total = result["body_ratio"] + result["upper_wick_ratio"] + result["lower_wick_ratio"]
        assert total == pytest.approx(1.0, abs=1e-4)

    def test_bearish_candle(self):
        c = _make_candle(11.0, 12.0, 8.0, 9.0)
        result = _candle_analysis(c)
        assert 0.0 <= result["body_ratio"] <= 1.0


# ---------------------------------------------------------------------------
# analyze() – public entry point
# ---------------------------------------------------------------------------

class TestAnalyze:
    def test_empty_payload(self):
        result = analyze({})
        assert result["signal"] == "NEUTRAL"
        assert result["errors"]
        assert "Empty" in result["errors"][0]

    def test_single_candle_errors(self):
        result = analyze({"candles": ONE_CANDLE})
        assert result["errors"]
        assert result["signal"] == "NEUTRAL"

    def test_rising_signal(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe": 60,
            "candles": RISING_CANDLES,
        })
        assert result["signal"] in ("UP", "NEUTRAL")
        assert result["confidence"] >= 0.0
        assert result["confidence"] <= 0.85
        assert result["trend"] in ("UP", "DOWN", "SIDEWAYS")
        assert result["errors"] == []

    def test_falling_signal(self):
        result = analyze({
            "symbol": "GBP/USD",
            "timeframe": 60,
            "candles": FALLING_CANDLES,
        })
        assert result["signal"] in ("DOWN", "NEUTRAL")
        assert result["errors"] == []

    def test_response_structure(self):
        result = analyze({
            "symbol": "USD/JPY",
            "timeframe": 300,
            "candles": RISING_CANDLES,
        })
        required_keys = {
            "symbol", "timeframe", "signal", "confidence",
            "trend", "indicators", "support_resistance",
            "candle_analysis", "explanation", "errors",
        }
        assert required_keys.issubset(result.keys())
        assert result["symbol"] == "USD/JPY"
        assert result["timeframe"] == 300

    def test_deterministic(self):
        payload = {
            "symbol": "EUR/USD",
            "timeframe": 60,
            "candles": RISING_CANDLES,
        }
        r1 = analyze(payload)
        r2 = analyze(payload)
        assert r1 == r2

    def test_no_certainty_in_explanation(self):
        result = analyze({"candles": RISING_CANDLES})
        assert "guarantee" in result["explanation"].lower() or \
               "probabilistic" in result["explanation"].lower()

    def test_nan_candle_returns_error(self):
        bad = [_make_candle(1.0, float("nan"), 0.9, 1.1)] + list(RISING_CANDLES)
        result = analyze({"candles": bad})
        assert result["errors"]

    def test_minimum_two_candles(self):
        result = analyze({"candles": TWO_CANDLES})
        assert result["errors"] == []
        assert result["signal"] in ("UP", "DOWN", "NEUTRAL")

    def test_confidence_bounded(self):
        for candles in (RISING_CANDLES, FALLING_CANDLES):
            result = analyze({"candles": candles})
            assert 0.0 <= result["confidence"] <= 0.85

    def test_indicators_present_with_enough_data(self):
        result = analyze({"candles": RISING_CANDLES})
        inds = result["indicators"]
        assert "ema9" in inds
        assert "ema21" in inds
        assert "rsi14" in inds
        assert "atr14" in inds
