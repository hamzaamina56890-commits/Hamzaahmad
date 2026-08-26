import math
import unittest

from backend.analysis.signal_engine import (
    AnalysisValidationError,
    analyze,
)


def make_candles(
    closes: list[float],
    timeframe_seconds: int = 60,
    start_time: int = 1_700_000_000,
) -> list[dict]:
    candles = []
    previous_close = closes[0] - 0.0008

    for index, close in enumerate(closes):
        open_price = previous_close
        high_price = max(open_price, close) + 0.0004
        low_price = min(open_price, close) - 0.0003
        candles.append(
            {
                "symbol": "EUR/USD",
                "timeframe_seconds": timeframe_seconds,
                "start_time": start_time + (index * timeframe_seconds),
                "open": round(open_price, 6),
                "high": round(high_price, 6),
                "low": round(low_price, 6),
                "close": round(close, 6),
            }
        )
        previous_close = close

    return candles


class SignalEngineTests(unittest.TestCase):
    def test_bullish_verified_data_returns_up_signal(self):
        closes = [
            1.1000,
            1.1004,
            1.1008,
            1.1010,
            1.1014,
            1.1018,
            1.1021,
            1.1025,
            1.1029,
            1.1032,
            1.1036,
            1.1040,
            1.1044,
            1.1047,
            1.1050,
            1.1054,
            1.1058,
            1.1062,
            1.1067,
            1.1071,
            1.1076,
            1.1081,
            1.1087,
            1.1092,
            1.1098,
        ]
        result = analyze(
            {
                "symbol": "EUR/USD",
                "timeframe": 60,
                "candles": make_candles(closes),
                "data_source": "Twelve Data",
                "data_verified": True,
            }
        )

        self.assertEqual(result["signal"], "UP")
        self.assertEqual(result["trend"], "UPTREND")
        self.assertGreater(result["indicator_values"]["rsi"], 50)
        self.assertTrue(result["reasons"])
        self.assertLess(result["confidence"], 1.0)

    def test_bearish_verified_data_returns_down_signal(self):
        closes = [
            1.2090,
            1.2086,
            1.2081,
            1.2078,
            1.2073,
            1.2069,
            1.2064,
            1.2060,
            1.2055,
            1.2051,
            1.2047,
            1.2042,
            1.2037,
            1.2033,
            1.2029,
            1.2024,
            1.2019,
            1.2014,
            1.2009,
            1.2005,
            1.2000,
            1.1994,
            1.1989,
            1.1983,
            1.1978,
        ]
        result = analyze(
            {
                "symbol": "EUR/USD",
                "timeframe": 60,
                "candles": make_candles(closes),
                "data_source": "Verified Feed",
                "data_verified": True,
            }
        )

        self.assertEqual(result["signal"], "DOWN")
        self.assertEqual(result["trend"], "DOWNTREND")
        self.assertLess(result["indicator_values"]["rsi"], 50)
        self.assertTrue(result["reasons"])

    def test_mixed_data_returns_neutral_signal(self):
        closes = [
            1.3000,
            1.3004,
            1.3001,
            1.3005,
            1.3002,
            1.3006,
            1.3003,
            1.3005,
            1.3002,
            1.3004,
            1.3001,
            1.3005,
            1.3002,
            1.3004,
            1.3003,
            1.3005,
            1.3002,
            1.3004,
            1.3001,
            1.3003,
            1.3002,
            1.3004,
            1.3002,
            1.3003,
            1.3002,
        ]
        result = analyze(
            {
                "symbol": "EUR/USD",
                "timeframe": 60,
                "candles": make_candles(closes),
                "data_source": "Verified Feed",
                "data_verified": True,
            }
        )

        self.assertEqual(result["signal"], "NEUTRAL")
        self.assertIn(result["trend"], {"SIDEWAYS", "UPTREND", "DOWNTREND"})
        self.assertGreaterEqual(result["confidence"], 0.3)

    def test_insufficient_candle_history_is_rejected(self):
        closes = [1.1000 + (step * 0.0002) for step in range(10)]

        with self.assertRaises(AnalysisValidationError):
            analyze(
                {
                    "symbol": "EUR/USD",
                    "timeframe": 60,
                    "candles": make_candles(closes),
                    "data_source": "Twelve Data",
                    "data_verified": True,
                }
            )

    def test_missing_candles_are_rejected(self):
        candles = make_candles(
            [1.1000 + (step * 0.0003) for step in range(25)]
        )
        candles[12]["start_time"] += 120

        with self.assertRaises(AnalysisValidationError):
            analyze(
                {
                    "symbol": "EUR/USD",
                    "timeframe": 60,
                    "candles": candles,
                    "data_source": "Twelve Data",
                    "data_verified": True,
                }
            )

    def test_invalid_prices_and_nan_are_rejected(self):
        candles = make_candles(
            [1.1000 + (step * 0.0003) for step in range(25)]
        )
        candles[-1]["close"] = math.nan

        with self.assertRaises(AnalysisValidationError):
            analyze(
                {
                    "symbol": "EUR/USD",
                    "timeframe": 60,
                    "candles": candles,
                    "data_source": "Twelve Data",
                    "data_verified": True,
                }
            )

    def test_unverified_or_unsupported_requests_are_rejected(self):
        candles = make_candles(
            [1.1000 + (step * 0.0003) for step in range(25)]
        )

        with self.assertRaises(AnalysisValidationError):
            analyze(
                {
                    "symbol": "EUR/USD",
                    "timeframe": 7,
                    "candles": candles,
                    "data_source": "Twelve Data",
                    "data_verified": True,
                }
            )

        with self.assertRaises(AnalysisValidationError):
            analyze(
                {
                    "symbol": "EUR/USD",
                    "timeframe": 60,
                    "candles": candles,
                    "data_source": "Twelve Data",
                    "data_verified": False,
                }
            )

    def test_timeframe_seconds_alias_is_supported(self):
        closes = [1.1500 + (step * 0.0003) for step in range(25)]
        result = analyze(
            {
                "symbol": "EUR/USD",
                "timeframe_seconds": 60,
                "candles": make_candles(closes),
                "data_source": "Twelve Data",
                "data_verified": True,
            }
        )

        self.assertEqual(result["timeframe"]["seconds"], 60)


if __name__ == "__main__":
    unittest.main()
