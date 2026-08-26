import asyncio
import json
import os

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from pathlib import Path

from backend.market.assets import ASSETS, TIMEFRAMES
from backend.market.candle_builder import CandleBuilder
from backend.market.twelvedata import TwelveDataProvider
from backend.analysis.signal_engine import analyze

app = FastAPI(title="Chinese-boot", version="0.1.0")

ROOT = Path(__file__).resolve().parent.parent

_provider: TwelveDataProvider | None = None


def _get_provider() -> TwelveDataProvider:
    global _provider
    if _provider is None:
        _provider = TwelveDataProvider()
    return _provider


@app.get("/api/assets")
def assets():
    return {
        "assets": ASSETS,
        "timeframes": TIMEFRAMES,
    }


@app.post("/api/analyze")
def analyze_market(payload: dict):
    return analyze(payload)


@app.get("/api/status")
async def status():
    provider = _get_provider()
    health = await provider.health_check()
    return {
        "service": "Chinese-boot",
        "version": "0.1.0",
        "api_key_configured": bool(provider.api_key),
        "provider": health,
    }


@app.websocket("/ws/stream")
async def ws_stream(websocket: WebSocket):
    """
    Client sends: {"action": "subscribe", "symbol": "EUR/USD", "timeframe": 60}
    Server sends:
      {"type": "price", "symbol": "...", "price": 1.0835, "timestamp": 1234567890}
      {"type": "candle_closed", "candle": {...}}
      {"type": "error", "message": "..."}
    """
    await websocket.accept()

    subscribe_msg = await websocket.receive_text()
    try:
        req = json.loads(subscribe_msg)
        symbol = req.get("symbol", "")
        timeframe = int(req.get("timeframe", 60))
    except (ValueError, KeyError):
        await websocket.send_text(
            json.dumps({"type": "error", "message": "Invalid subscribe payload."})
        )
        await websocket.close()
        return

    if not symbol:
        await websocket.send_text(
            json.dumps({"type": "error", "message": "symbol is required."})
        )
        await websocket.close()
        return

    provider = _get_provider()

    if not provider.api_key:
        await websocket.send_text(
            json.dumps(
                {
                    "type": "error",
                    "message": "TWELVE_DATA_API_KEY is not configured on the server.",
                }
            )
        )
        await websocket.close()
        return

    # Each connection gets its own CandleBuilder to avoid shared-state races.
    candle_builder = CandleBuilder()
    conn_provider = TwelveDataProvider(api_key=provider.api_key)

    try:
        async for frame in conn_provider.stream([symbol]):
            if frame.get("event") == "price":
                price_info = conn_provider.get_latest_price(symbol)
                if price_info:
                    await websocket.send_text(
                        json.dumps(
                            {
                                "type": "price",
                                "symbol": price_info["symbol"],
                                "price": price_info["price"],
                                "timestamp": price_info["timestamp"],
                            }
                        )
                    )
                    result = candle_builder.update(
                        symbol=symbol,
                        price=price_info["price"],
                        timestamp=int(price_info["timestamp"] or 0),
                        timeframe_seconds=timeframe,
                    )
                    if result.get("closed_candle"):
                        await websocket.send_text(
                            json.dumps(
                                {
                                    "type": "candle_closed",
                                    "candle": result["closed_candle"],
                                }
                            )
                        )
    except WebSocketDisconnect:
        pass
    except Exception as exc:
        try:
            await websocket.send_text(
                json.dumps({"type": "error", "message": str(exc)})
            )
        except Exception:
            pass
    finally:
        await conn_provider.close()


@app.get("/")
def index():
    return FileResponse(ROOT / "frontend" / "index.html")
