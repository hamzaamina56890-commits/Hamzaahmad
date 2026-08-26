"""Tests for the real-time market pipeline integration layer."""
import asyncio
import unittest
from collections import deque
from unittest.mock import AsyncMock, MagicMock, patch

from backend.market.pipeline import MarketPipeline


class TestPipelineStatus(unittest.TestCase):
    def test_initial_status(self):
        pipeline = MarketPipeline()
        status = pipeline.status()
        self.assertEqual(status["provider"], "Twelve Data")
        self.assertFalse(status["connected"])
        self.assertFalse(status["live_data"])
        self.assertIsNone(status["symbol"])
        self.assertIsNone(status["timeframe_seconds"])
        self.assertIsNone(status["latest_price"])

    def test_get_latest_price_no_symbol(self):
        pipeline = MarketPipeline()
        self.assertIsNone(pipeline.get_latest_price())

    def test_get_current_candle_no_symbol(self):
        pipeline = MarketPipeline()
        self.assertIsNone(pipeline.get_current_candle())

    def test_get_candles_empty(self):
        pipeline = MarketPipeline()
        self.assertEqual(pipeline.get_candles(), [])

    def test_get_analysis_no_config(self):
        pipeline = MarketPipeline()
        result = pipeline.get_analysis()
        self.assertEqual(result["signal"], "INSUFFICIENT_DATA")


class TestPipelineProcessMessage(unittest.TestCase):
    def setUp(self):
        self.pipeline = MarketPipeline()
        self.pipeline._symbol = "EUR/USD"
        self.pipeline._timeframe = 60

    def _process(self, msg):
        self.pipeline._process_message(msg, "EUR/USD", 60)

    def test_ignore_heartbeat(self):
        self._process({"event": "heartbeat"})
        self.assertEqual(len(self.pipeline._candle_history), 0)

    def test_ignore_wrong_symbol(self):
        self._process(
            {"event": "price", "symbol": "GBP/USD", "price": "1.25", "timestamp": "1700000060"}
        )
        self.assertEqual(len(self.pipeline._candle_history), 0)

    def test_ignore_non_positive_price(self):
        self._process(
            {"event": "price", "symbol": "EUR/USD", "price": "-1.0", "timestamp": "1700000060"}
        )
        self.assertEqual(len(self.pipeline._candle_history), 0)

    def test_ignore_missing_price(self):
        self._process({"event": "price", "symbol": "EUR/USD", "timestamp": "1700000060"})
        self.assertEqual(len(self.pipeline._candle_history), 0)

    def test_ignore_malformed_price(self):
        self._process(
            {"event": "price", "symbol": "EUR/USD", "price": "not-a-number", "timestamp": "1700000060"}
        )
        self.assertEqual(len(self.pipeline._candle_history), 0)

    def test_valid_tick_builds_candle_no_close_yet(self):
        self._process(
            {"event": "price", "symbol": "EUR/USD", "price": "1.1000", "timestamp": "1700000000"}
        )
        # No closed candle yet, but current candle should exist.
        candle = self.pipeline.get_current_candle()
        self.assertIsNotNone(candle)
        self.assertAlmostEqual(candle["close"], 1.1)

    def test_tick_closes_candle_on_new_bucket(self):
        # First tick in bucket 0
        self._process(
            {"event": "price", "symbol": "EUR/USD", "price": "1.1000", "timestamp": "1700000000"}
        )
        # Second tick in bucket 1 (next minute)
        self._process(
            {"event": "price", "symbol": "EUR/USD", "price": "1.1050", "timestamp": "1700000060"}
        )
        self.assertEqual(len(self.pipeline._candle_history), 1)

    def test_duplicate_candle_suppressed(self):
        # Simulate two messages that would produce the same closed candle.
        self._process(
            {"event": "price", "symbol": "EUR/USD", "price": "1.1000", "timestamp": "1700000000"}
        )
        self._process(
            {"event": "price", "symbol": "EUR/USD", "price": "1.1050", "timestamp": "1700000060"}
        )
        # Manually inject a duplicate
        dup = dict(self.pipeline._candle_history[-1])
        self.pipeline._append_candle(dup)
        self.assertEqual(len(self.pipeline._candle_history), 1)

    def test_analysis_uses_live_candles(self):
        for i in range(30):
            ts = 1700000000 + i * 60
            self._process(
                {"event": "price", "symbol": "EUR/USD", "price": str(1.1 + i * 0.001), "timestamp": str(ts)}
            )
        result = self.pipeline.get_analysis()
        self.assertIn(result["signal"], ("BUY", "SELL", "NEUTRAL", "INSUFFICIENT_DATA"))


class TestPipelineSetSubscription(unittest.IsolatedAsyncioTestCase):
    async def test_invalid_timeframe_raises(self):
        pipeline = MarketPipeline()
        with self.assertRaises(ValueError):
            await pipeline.set_subscription("EUR/USD", 999)

    async def test_set_subscription_creates_task(self):
        pipeline = MarketPipeline()
        with patch.object(pipeline, "_run_stream", new_callable=AsyncMock):
            await pipeline.set_subscription("EUR/USD", 60)
            self.assertEqual(pipeline._symbol, "EUR/USD")
            self.assertEqual(pipeline._timeframe, 60)
            await pipeline.shutdown()


if __name__ == "__main__":
    unittest.main()
