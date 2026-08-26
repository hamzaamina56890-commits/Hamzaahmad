from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pathlib import Path

from backend.market.assets import ASSETS, TIMEFRAMES
from backend.analysis.signal_engine import (
    AnalysisValidationError,
    analyze,
)

app = FastAPI(title="Chinese-boot", version="0.1.0")

ROOT = Path(__file__).resolve().parent.parent


@app.get("/api/assets")
def assets():
    return {
        "assets": ASSETS,
        "timeframes": TIMEFRAMES
    }


@app.post("/api/analyze")
def analyze_market(payload: dict):
    try:
        return analyze(payload)
    except AnalysisValidationError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/")
def index():
    return FileResponse(
        ROOT / "frontend" / "index.html"
    )
