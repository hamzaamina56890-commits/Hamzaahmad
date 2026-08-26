"""Integration-style pipeline tests (no external network required)."""
import pytest
from backend.market.candle_builder import CandleBuilder
from backend.analysis.signal_engine import analyze
from backend.market.assets import ASSETS, TIMEFRAMES
from backend.market.twelvedata import TwelveDataProvider
from backend.market.provider import MarketDataProvider


# ─── Assets / TIMEFRAMES ───────────────────────────────────────────────────────

class TestAssets:
    def test_assets_list_non_empty(self):
        assert len(ASSETS) > 0

    def test_each_asset_has_required_keys(self):
        for asset in ASSETS:
            assert "symbol" in asset
            assert "name" in asset
            assert "category" in asset

    def test_timeframes_non_empty(self):
        assert len(TIMEFRAMES) > 0

    def test_each_timeframe_has_label_and_seconds(self):
        for tf in TIMEFRAMES:
            assert "label" in tf
            assert "seconds" in tf
            assert isinstance(tf["seconds"], int)
            assert tf["seconds"] > 0

    def test_timeframes_match_supported(self):
        from backend.market.candle_builder import CandleBuilder
        for tf in TIMEFRAMES:
            assert tf["seconds"] in CandleBuilder.SUPPORTED_TIMEFRAMES


# ─── Full pipeline: ticks → candles → analysis ────────────────────────────────

class TestTickToCandlePipeline:
    def test_30_ticks_build_candles_and_analyze(self):
        cb = CandleBuilder()
        closed = []

        for i in range(30):
            price = 1.1000 + i * 0.001
            ts = i * 2  # 2 s per tick, 30 s timeframe
            result = cb.update("EUR/USD", price, ts, 30)
            if result["closed_candle"]:
                closed.append(result["closed_candle"])

        analysis = analyze({
            "symbol": "EUR/USD",
            "timeframe": 30,
            "candles": closed,
        })

        assert analysis["symbol"] == "EUR/USD"
        assert analysis["signal"] in ("UP", "DOWN", "NEUTRAL")

    def test_pipeline_with_insufficient_history(self):
        cb = CandleBuilder()
        result = cb.update("GBP/USD", 1.25, 0, 60)
        closed = []
        if result["closed_candle"]:
            closed.append(result["closed_candle"])

        analysis = analyze({
            "symbol": "GBP/USD",
            "timeframe": 60,
            "candles": closed,
        })
        assert analysis["signal"] == "NEUTRAL"

    def test_unsupported_timeframe_does_not_reach_signal_engine(self):
        cb = CandleBuilder()
        with pytest.raises(ValueError):
            cb.update("EUR/USD", 1.1, 0, 7)  # 7 s not supported


# ─── TwelveDataProvider – unit (no network) ───────────────────────────────────

class TestTwelveDataProviderUnit:
    def test_raises_without_api_key(self):
        import asyncio
        provider = TwelveDataProvider(api_key=None)
        # Patch env to be sure
        import os
        os.environ.pop("TWELVE_DATA_API_KEY", None)
        provider.api_key = None
        with pytest.raises(RuntimeError, match="TWELVE_DATA_API_KEY"):
            asyncio.run(provider.connect())

    def test_subscribe_raises_when_not_connected(self):
        import asyncio
        provider = TwelveDataProvider(api_key="dummy")
        with pytest.raises(RuntimeError, match="not connected"):
            asyncio.run(provider.subscribe(["EUR/USD"]))

    def test_get_latest_price_none_initially(self):
        provider = TwelveDataProvider(api_key="dummy")
        assert provider.get_latest_price("EUR/USD") is None

    def test_health_check_returns_dict(self):
        import asyncio
        provider = TwelveDataProvider(api_key="dummy")
        health = asyncio.run(provider.health_check())
        assert "provider" in health
        assert "connected" in health

    def test_api_key_not_hardcoded(self):
        """Verify that no real API key is committed in the source file."""
        import pathlib
        src = pathlib.Path(__file__).parent.parent / "backend" / "market" / "twelvedata.py"
        text = src.read_text()
        # The source must not contain a raw API key (32-char hex pattern typical of Twelve Data)
        import re
        assert not re.search(r'["\']([a-f0-9]{32})["\']', text), \
            "Possible hardcoded API key detected in twelvedata.py"

    def test_provider_implements_interface(self):
        """TwelveDataProvider does NOT inherit MarketDataProvider but has compatible methods."""
        provider = TwelveDataProvider(api_key="dummy")
        assert callable(getattr(provider, "health_check", None))
        assert callable(getattr(provider, "get_latest_price", None))
        assert callable(getattr(provider, "connect", None))
        assert callable(getattr(provider, "subscribe", None))
