import os

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pathlib import Path

from backend.market.assets import ASSETS, TIMEFRAMES
from backend.market.candle_builder import CandleBuilder
from backend.analysis.signal_engine import analyze

app = FastAPI(title="Chinese-boot", version="0.1.0")

ROOT = Path(__file__).resolve().parent.parent


@app.get("/api/assets")
def assets():
    return {
        "assets": ASSETS,
        "timeframes": TIMEFRAMES,
    }


@app.post("/api/analyze")
def analyze_market(payload: dict):
    result = analyze(payload)
    if result.get("error"):
        raise HTTPException(status_code=422, detail=result["error"])
    return result


@app.get("/api/status")
def status():
    api_key_configured = bool(os.getenv("TWELVE_DATA_API_KEY"))
    return {
        "status": "ok",
        "provider": "Twelve Data",
        "api_key_configured": api_key_configured,
        "supported_timeframes": [t["seconds"] for t in TIMEFRAMES],
        "supported_symbols": [a["symbol"] for a in ASSETS],
    }


@app.get("/")
def index():
    html_path = ROOT / "frontend" / "index.html"
    if not html_path.exists():
        raise HTTPException(status_code=503, detail="Frontend not built.")
    return FileResponse(html_path)
