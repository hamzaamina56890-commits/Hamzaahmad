"""Unit tests for signal_engine.analyze()."""
import pytest
from backend.analysis.signal_engine import analyze, _rsi


# ─── RSI helper ────────────────────────────────────────────────────────────────

class TestRSI:
    def test_none_when_too_few_prices(self):
        assert _rsi([1.0] * 14) is None

    def test_returns_100_when_no_losses(self):
        closes = [float(i) for i in range(1, 20)]  # always going up
        result = _rsi(closes)
        assert result == 100.0

    def test_returns_0_when_no_gains(self):
        closes = [float(20 - i) for i in range(20)]  # always going down
        result = _rsi(closes)
        assert result == 0.0

    def test_result_in_valid_range(self):
        import random
        random.seed(42)
        closes = [1.0 + random.uniform(-0.01, 0.01) for _ in range(50)]
        result = _rsi(closes)
        assert result is not None
        assert 0.0 <= result <= 100.0

    def test_minimum_data_for_rsi14(self):
        closes = [float(i) for i in range(1, 16)]  # 15 values = period+1
        assert _rsi(closes) is not None


# ─── analyze() ──────────────────────────────────────────────────────────────────

def _make_candles(closes: list[float]) -> list[dict]:
    return [{"close": c, "open": c, "high": c, "low": c, "start_time": i * 60, "tick_count": 1}
            for i, c in enumerate(closes)]


class TestAnalyze:
    def test_missing_symbol_returns_error(self):
        result = analyze({"timeframe": 60, "candles": []})
        assert result["signal"] == "NEUTRAL"
        assert "Error" in result["reason"]

    def test_zero_timeframe_returns_error(self):
        result = analyze({"symbol": "EUR/USD", "timeframe": 0, "candles": []})
        assert "Error" in result["reason"]

    def test_empty_candles_returns_neutral(self):
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": []})
        assert result["signal"] == "NEUTRAL"
        assert result["candle_count"] == 0
        assert result["rsi"] is None

    def test_insufficient_candles_returns_neutral(self):
        candles = _make_candles([1.1] * 10)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        assert result["signal"] == "NEUTRAL"
        assert result["rsi"] is None

    def test_uptrend_returns_up(self):
        # Strongly rising prices → RSI > 60
        closes = [1.0 + i * 0.005 for i in range(30)]
        candles = _make_candles(closes)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        assert result["signal"] == "UP"
        assert result["rsi"] is not None
        assert result["rsi"] > 60

    def test_downtrend_returns_down(self):
        # Strongly falling prices → RSI < 40
        closes = [1.3 - i * 0.005 for i in range(30)]
        candles = _make_candles(closes)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        assert result["signal"] == "DOWN"
        assert result["rsi"] < 40

    def test_sideways_returns_neutral(self):
        # Alternating slight moves keep RSI near 50
        closes = [1.1000 + (0.0001 if i % 2 == 0 else -0.0001) for i in range(30)]
        candles = _make_candles(closes)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        assert result["signal"] == "NEUTRAL"

    def test_invalid_candle_entries_are_skipped(self):
        candles = [
            {"close": None},
            {"close": "bad"},
            {},
            {"close": 0},
            {"close": 1.1},
        ]
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        # Only 1 valid close → insufficient → NEUTRAL, no crash
        assert result["signal"] == "NEUTRAL"

    def test_candles_not_a_list_returns_error(self):
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": "bad"})
        assert "Error" in result["reason"]

    def test_result_structure(self):
        candles = _make_candles([1.1] * 20)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        assert set(result.keys()) == {"symbol", "timeframe", "signal", "rsi", "candle_count", "reason"}

    def test_no_fake_price_in_result(self):
        """Ensure analyze() never manufactures prices – it only reads from candles."""
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": []})
        assert result["rsi"] is None
        assert result["candle_count"] == 0
