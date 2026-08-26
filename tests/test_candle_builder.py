"""Unit tests for CandleBuilder."""
import pytest
from backend.market.candle_builder import CandleBuilder, Candle


class TestCandleBuilder:
    def setup_method(self):
        self.builder = CandleBuilder()

    def test_first_tick_creates_candle(self):
        result = self.builder.update("EUR/USD", 1.1000, 1000, 60)
        assert result["closed_candle"] is None
        c = result["current_candle"]
        assert c["open"] == 1.1000
        assert c["high"] == 1.1000
        assert c["low"] == 1.1000
        assert c["close"] == 1.1000
        assert c["tick_count"] == 1

    def test_second_tick_same_bucket_updates_ohlc(self):
        self.builder.update("EUR/USD", 1.1000, 1000, 60)
        result = self.builder.update("EUR/USD", 1.1050, 1010, 60)
        c = result["current_candle"]
        assert c["high"] == 1.1050
        assert c["close"] == 1.1050
        assert c["low"] == 1.1000
        assert c["tick_count"] == 2
        assert result["closed_candle"] is None

    def test_new_bucket_closes_old_candle(self):
        self.builder.update("EUR/USD", 1.1000, 0, 60)
        result = self.builder.update("EUR/USD", 1.2000, 60, 60)
        assert result["closed_candle"] is not None
        assert result["closed_candle"]["close"] == 1.1000
        assert result["current_candle"]["open"] == 1.2000

    def test_unsupported_timeframe_raises(self):
        with pytest.raises(ValueError, match="Unsupported timeframe"):
            self.builder.update("EUR/USD", 1.1, 1000, 7)

    def test_zero_price_raises(self):
        with pytest.raises(ValueError, match="Price must be greater than zero"):
            self.builder.update("EUR/USD", 0.0, 1000, 60)

    def test_negative_price_raises(self):
        with pytest.raises(ValueError, match="Price must be greater than zero"):
            self.builder.update("EUR/USD", -1.0, 1000, 60)

    def test_all_supported_timeframes_work(self):
        for tf in CandleBuilder.SUPPORTED_TIMEFRAMES:
            b = CandleBuilder()
            result = b.update("EUR/USD", 1.1, 1000, tf)
            assert result["current_candle"] is not None

    def test_bucket_start_calculation(self):
        b = CandleBuilder()
        assert b._bucket_start(65, 60) == 60
        assert b._bucket_start(0, 60) == 0
        assert b._bucket_start(119, 60) == 60
        assert b._bucket_start(120, 60) == 120

    def test_get_current_returns_none_for_unknown(self):
        assert self.builder.get_current("FAKE/USD", 60) is None

    def test_reset_clears_all(self):
        self.builder.update("EUR/USD", 1.1, 1000, 60)
        self.builder.reset()
        assert self.builder.get_current("EUR/USD", 60) is None

    def test_candle_to_dict_has_all_fields(self):
        result = self.builder.update("EUR/USD", 1.1, 1000, 60)
        c = result["current_candle"]
        for field in ("symbol", "timeframe_seconds", "start_time", "open", "high", "low", "close", "tick_count"):
            assert field in c
