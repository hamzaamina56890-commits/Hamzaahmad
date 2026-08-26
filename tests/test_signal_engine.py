"""Unit tests for the Signal Engine."""
import pytest
from backend.analysis.signal_engine import analyze, _rsi, _sma, _direction


# ── _rsi ─────────────────────────────────────────────────────────────────────

class TestRsi:
    def test_returns_none_when_insufficient_data(self):
        assert _rsi([1.1, 1.2, 1.3]) is None

    def test_returns_none_with_exactly_period_values(self):
        closes = [float(i) for i in range(1, 15)]  # 14 values
        assert _rsi(closes) is None

    def test_returns_float_with_sufficient_data(self):
        closes = [float(i) for i in range(1, 17)]  # 16 values
        result = _rsi(closes)
        assert result is not None
        assert 0.0 <= result <= 100.0

    def test_all_gains_returns_100(self):
        closes = [float(i) for i in range(1, 17)]  # strictly increasing
        result = _rsi(closes)
        assert result == 100.0

    def test_all_losses_returns_near_zero(self):
        closes = [float(i) for i in range(16, 0, -1)]  # strictly decreasing
        result = _rsi(closes)
        assert result is not None
        assert result < 1.0


# ── _sma ─────────────────────────────────────────────────────────────────────

class TestSma:
    def test_returns_none_when_insufficient(self):
        assert _sma([1.0, 2.0], period=20) is None

    def test_correct_value(self):
        closes = [1.0] * 20
        assert _sma(closes, period=20) == pytest.approx(1.0)

    def test_uses_only_last_n(self):
        closes = [0.0] * 19 + [1.0] * 20
        result = _sma(closes, period=20)
        assert result == pytest.approx(1.0)


# ── _direction ────────────────────────────────────────────────────────────────

class TestDirection:
    def test_neutral_when_no_data(self):
        assert _direction(None, None, 1.1) == "NEUTRAL"

    def test_up_when_rsi_oversold(self):
        assert _direction(25.0, None, 1.1) == "UP"

    def test_down_when_rsi_overbought(self):
        assert _direction(75.0, None, 1.1) == "DOWN"

    def test_up_when_price_above_sma(self):
        assert _direction(None, 1.0, 1.1) == "UP"

    def test_down_when_price_below_sma(self):
        assert _direction(None, 1.1, 1.0) == "DOWN"

    def test_neutral_when_conflicting(self):
        # RSI oversold (up vote) but price below SMA (down vote) → tie → NEUTRAL
        assert _direction(25.0, 1.1, 1.0) == "NEUTRAL"


# ── analyze ───────────────────────────────────────────────────────────────────

def _make_candles(n: int, price: float = 1.1000) -> list[dict]:
    candles = []
    for i in range(n):
        candles.append(
            {"open": price, "high": price + 0.0002,
             "low": price - 0.0002, "close": price, "start_time": i * 60}
        )
    return candles


class TestAnalyze:
    def test_missing_symbol_returns_error(self):
        result = analyze({"timeframe_seconds": 60, "candles": []})
        assert result["error"] is not None

    def test_unsupported_symbol_returns_error(self):
        result = analyze({"symbol": "FAKE/USD", "timeframe_seconds": 60, "candles": []})
        assert result["error"] is not None

    def test_missing_timeframe_returns_error(self):
        result = analyze({"symbol": "EUR/USD", "candles": []})
        assert result["error"] is not None

    def test_unsupported_timeframe_returns_error(self):
        result = analyze({"symbol": "EUR/USD", "timeframe_seconds": 7, "candles": []})
        assert result["error"] is not None

    def test_empty_candles_returns_neutral(self):
        result = analyze({"symbol": "EUR/USD", "timeframe_seconds": 60, "candles": []})
        assert result["error"] is None
        assert result["direction"] == "NEUTRAL"
        assert result["candle_count"] == 0

    def test_insufficient_candles_still_returns_neutral_no_crash(self):
        candles = _make_candles(5)
        result = analyze({"symbol": "EUR/USD", "timeframe_seconds": 60, "candles": candles})
        assert result["error"] is None
        assert result["rsi"] is None
        assert result["direction"] == "NEUTRAL"

    def test_sufficient_candles_produces_result(self):
        candles = _make_candles(30)
        result = analyze({"symbol": "EUR/USD", "timeframe_seconds": 60, "candles": candles})
        assert result["error"] is None
        assert result["candle_count"] == 30
        assert result["rsi"] is not None
        assert result["direction"] in ("UP", "DOWN", "NEUTRAL")

    def test_invalid_candle_format_returns_error(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe_seconds": 60,
            "candles": [{"open": 1.1, "high": 1.2}],  # missing low/close
        })
        assert result["error"] is not None

    def test_zero_price_returns_error(self):
        result = analyze({
            "symbol": "EUR/USD",
            "timeframe_seconds": 60,
            "candles": [{"open": 0, "high": 0, "low": 0, "close": 0, "start_time": 0}],
        })
        assert result["error"] is not None

    def test_all_timeframes_accepted(self):
        from backend.market.assets import TIMEFRAMES
        for tf in TIMEFRAMES:
            candles = _make_candles(30)
            result = analyze({
                "symbol": "EUR/USD",
                "timeframe_seconds": tf["seconds"],
                "candles": candles,
            })
            assert result["error"] is None, f"Failed for timeframe {tf}"

    def test_all_symbols_accepted(self):
        from backend.market.assets import ASSETS
        for asset in ASSETS:
            candles = _make_candles(30)
            result = analyze({
                "symbol": asset["symbol"],
                "timeframe_seconds": 60,
                "candles": candles,
            })
            assert result["error"] is None, f"Failed for symbol {asset['symbol']}"
