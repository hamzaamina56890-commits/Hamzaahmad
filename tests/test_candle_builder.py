"""Unit tests for CandleBuilder."""
import pytest
from backend.market.candle_builder import CandleBuilder, Candle


class TestCandleBuilderBasic:
    def setup_method(self):
        self.cb = CandleBuilder()

    def test_first_tick_creates_candle(self):
        result = self.cb.update("EUR/USD", 1.1000, 1000, 60)
        c = result["current_candle"]
        assert c["open"] == 1.1000
        assert c["high"] == 1.1000
        assert c["low"] == 1.1000
        assert c["close"] == 1.1000
        assert c["tick_count"] == 1
        assert result["closed_candle"] is None

    def test_within_same_bucket_updates_ohlc(self):
        # Use timestamps 960, 970, 980 which all fall in the [960, 1020) bucket
        self.cb.update("EUR/USD", 1.1000, 960, 60)
        self.cb.update("EUR/USD", 1.1050, 970, 60)
        result = self.cb.update("EUR/USD", 1.0990, 980, 60)
        c = result["current_candle"]
        assert c["open"] == 1.1000
        assert c["high"] == 1.1050
        assert c["low"] == 1.0990
        assert c["close"] == 1.0990
        assert c["tick_count"] == 3
        assert result["closed_candle"] is None

    def test_new_bucket_closes_previous_candle(self):
        self.cb.update("EUR/USD", 1.1000, 0, 60)
        result = self.cb.update("EUR/USD", 1.1100, 60, 60)
        closed = result["closed_candle"]
        assert closed is not None
        assert closed["open"] == 1.1000
        assert closed["close"] == 1.1000
        current = result["current_candle"]
        assert current["open"] == 1.1100
        assert current["start_time"] == 60

    def test_unsupported_timeframe_raises(self):
        with pytest.raises(ValueError):
            self.cb.update("EUR/USD", 1.1000, 1000, 7)

    def test_zero_price_raises(self):
        with pytest.raises(ValueError):
            self.cb.update("EUR/USD", 0.0, 1000, 60)

    def test_negative_price_raises(self):
        with pytest.raises(ValueError):
            self.cb.update("EUR/USD", -1.0, 1000, 60)

    def test_bucket_start_alignment(self):
        self.cb.update("EUR/USD", 1.0, 65, 60)
        c = self.cb.get_current("EUR/USD", 60)
        assert c["start_time"] == 60

    def test_all_supported_timeframes(self):
        for tf in CandleBuilder.SUPPORTED_TIMEFRAMES:
            result = self.cb.update("GBP/USD", 1.25, 1000, tf)
            assert result["current_candle"] is not None

    def test_multiple_symbols_independent(self):
        self.cb.update("EUR/USD", 1.1000, 0, 60)
        self.cb.update("GBP/USD", 1.2500, 0, 60)
        eu = self.cb.get_current("EUR/USD", 60)
        gu = self.cb.get_current("GBP/USD", 60)
        assert eu["close"] != gu["close"]

    def test_reset_clears_all(self):
        self.cb.update("EUR/USD", 1.1000, 0, 60)
        self.cb.reset()
        assert self.cb.get_current("EUR/USD", 60) is None

    def test_reset_by_symbol(self):
        self.cb.update("EUR/USD", 1.1000, 0, 60)
        self.cb.update("GBP/USD", 1.2500, 0, 60)
        self.cb.reset(symbol="EUR/USD")
        assert self.cb.get_current("EUR/USD", 60) is None
        assert self.cb.get_current("GBP/USD", 60) is not None

    def test_candle_to_dict_keys(self):
        result = self.cb.update("EUR/USD", 1.1, 0, 60)
        keys = set(result["current_candle"].keys())
        assert keys == {"symbol", "timeframe_seconds", "start_time",
                        "open", "high", "low", "close", "tick_count"}
