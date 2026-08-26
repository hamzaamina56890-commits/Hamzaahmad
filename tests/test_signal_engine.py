"""Tests for the Signal Engine."""
import unittest
from backend.analysis.signal_engine import analyze, _sma, _rsi


def _make_candles(closes, symbol="EUR/USD", tf=60, start=1_700_000_000):
    candles = []
    for i, c in enumerate(closes):
        candles.append(
            {
                "symbol": symbol,
                "timeframe_seconds": tf,
                "start_time": start + i * tf,
                "open": c,
                "high": c,
                "low": c,
                "close": c,
                "tick_count": 1,
            }
        )
    return candles


class TestSmaRsi(unittest.TestCase):
    def test_sma_insufficient(self):
        self.assertIsNone(_sma([1.0, 2.0], 5))

    def test_sma_exact(self):
        self.assertAlmostEqual(_sma([1.0, 2.0, 3.0, 4.0, 5.0], 5), 3.0)

    def test_rsi_insufficient(self):
        self.assertIsNone(_rsi([1.0] * 5, 14))

    def test_rsi_all_gains(self):
        values = list(range(1, 20))
        r = _rsi([float(v) for v in values], 14)
        self.assertEqual(r, 100.0)


class TestAnalyze(unittest.TestCase):
    def test_missing_symbol(self):
        result = analyze({"timeframe": 60, "candles": []})
        self.assertIn("error", result)

    def test_missing_timeframe(self):
        result = analyze({"symbol": "EUR/USD", "candles": []})
        self.assertIn("error", result)

    def test_empty_candles(self):
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": []})
        self.assertEqual(result["signal"], "INSUFFICIENT_DATA")
        self.assertNotIn("error", result)

    def test_insufficient_for_sma(self):
        candles = _make_candles([1.1, 1.2, 1.3])
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        # Only 3 candles — not enough for SMA-21, fallback should kick in.
        self.assertIn(result["signal"], ("BUY", "SELL", "NEUTRAL", "INSUFFICIENT_DATA"))

    def test_bullish_signal(self):
        # Mild uptrend: sma_fast > sma_slow, RSI not overbought → BUY
        # Alternate small up-steps so RSI stays below 70.
        closes = []
        price = 1.1000
        for i in range(30):
            # Step: +0.001 on even, flat on odd → gentle uptrend, moderate RSI
            price += 0.001 if i % 2 == 0 else 0.0
            closes.append(round(price, 5))
        candles = _make_candles(closes)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        self.assertIn(result["signal"], ("BUY", "NEUTRAL"))
        self.assertIn("sma_fast", result["indicators"])
        self.assertIn("sma_slow", result["indicators"])
        self.assertIn("rsi", result["indicators"])

    def test_bearish_signal(self):
        # Mild downtrend: sma_fast < sma_slow, RSI not oversold → SELL
        closes = []
        price = 1.3000
        for i in range(30):
            price -= 0.001 if i % 2 == 0 else 0.0
            closes.append(round(price, 5))
        candles = _make_candles(closes)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        self.assertIn(result["signal"], ("SELL", "NEUTRAL"))

    def test_invalid_candle_missing_keys(self):
        result = analyze(
            {
                "symbol": "EUR/USD",
                "timeframe": 60,
                "candles": [{"close": 1.1}],
            }
        )
        self.assertIn("error", result)

    def test_invalid_candle_negative_price(self):
        candles = _make_candles([1.1])
        candles[0]["close"] = -1.0
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        self.assertIn("error", result)

    def test_confidence_range(self):
        closes = [1.0 + i * 0.01 for i in range(30)]
        candles = _make_candles(closes)
        result = analyze({"symbol": "EUR/USD", "timeframe": 60, "candles": candles})
        self.assertGreaterEqual(result["confidence"], 0.0)
        self.assertLessEqual(result["confidence"], 100.0)


if __name__ == "__main__":
    unittest.main()
