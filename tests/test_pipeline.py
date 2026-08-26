"""Integration tests for the FastAPI endpoints."""
import pytest
from fastapi.testclient import TestClient

from backend.app import app

client = TestClient(app)


def _make_candles(n: int, price: float = 1.1000) -> list[dict]:
    return [
        {"open": price, "high": price + 0.0002,
         "low": price - 0.0002, "close": price, "start_time": i * 60}
        for i in range(n)
    ]


class TestAssetsEndpoint:
    def test_returns_200(self):
        resp = client.get("/api/assets")
        assert resp.status_code == 200

    def test_contains_assets_and_timeframes(self):
        resp = client.get("/api/assets")
        data = resp.json()
        assert "assets" in data
        assert "timeframes" in data
        assert len(data["assets"]) > 0
        assert len(data["timeframes"]) > 0

    def test_assets_have_required_fields(self):
        data = client.get("/api/assets").json()
        for a in data["assets"]:
            assert "symbol" in a
            assert "name" in a
            assert "category" in a

    def test_timeframes_have_required_fields(self):
        data = client.get("/api/assets").json()
        for t in data["timeframes"]:
            assert "label" in t
            assert "seconds" in t


class TestStatusEndpoint:
    def test_returns_200(self):
        resp = client.get("/api/status")
        assert resp.status_code == 200

    def test_has_expected_keys(self):
        data = client.get("/api/status").json()
        assert "status" in data
        assert "provider" in data
        assert "api_key_configured" in data
        assert data["provider"] == "Twelve Data"

    def test_api_key_not_configured_without_env(self, monkeypatch):
        monkeypatch.delenv("TWELVE_DATA_API_KEY", raising=False)
        data = client.get("/api/status").json()
        assert data["api_key_configured"] is False


class TestAnalyzeEndpoint:
    def test_valid_payload_returns_200(self):
        payload = {
            "symbol": "EUR/USD",
            "timeframe_seconds": 60,
            "candles": _make_candles(30),
        }
        resp = client.post("/api/analyze", json=payload)
        assert resp.status_code == 200

    def test_result_has_required_keys(self):
        payload = {
            "symbol": "EUR/USD",
            "timeframe_seconds": 60,
            "candles": _make_candles(30),
        }
        data = client.post("/api/analyze", json=payload).json()
        for key in ("symbol", "timeframe_seconds", "candle_count", "rsi", "sma", "direction", "error"):
            assert key in data

    def test_invalid_symbol_returns_422(self):
        payload = {
            "symbol": "FAKE/SYM",
            "timeframe_seconds": 60,
            "candles": _make_candles(5),
        }
        resp = client.post("/api/analyze", json=payload)
        assert resp.status_code == 422

    def test_invalid_timeframe_returns_422(self):
        payload = {
            "symbol": "EUR/USD",
            "timeframe_seconds": 999,
            "candles": _make_candles(5),
        }
        resp = client.post("/api/analyze", json=payload)
        assert resp.status_code == 422

    def test_empty_candles_returns_neutral_200(self):
        payload = {
            "symbol": "EUR/USD",
            "timeframe_seconds": 60,
            "candles": [],
        }
        resp = client.post("/api/analyze", json=payload)
        assert resp.status_code == 200
        assert resp.json()["direction"] == "NEUTRAL"

    def test_direction_is_valid(self):
        payload = {
            "symbol": "EUR/USD",
            "timeframe_seconds": 60,
            "candles": _make_candles(30),
        }
        data = client.post("/api/analyze", json=payload).json()
        assert data["direction"] in ("UP", "DOWN", "NEUTRAL")
