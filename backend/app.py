from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

from backend.market.assets import ASSETS, TIMEFRAMES
from backend.market.pipeline import MarketPipeline
from backend.analysis.signal_engine import analyze

ROOT = Path(__file__).resolve().parent.parent

# Shared pipeline instance — started/stopped with the application lifespan.
_pipeline = MarketPipeline()


@asynccontextmanager
async def lifespan(app: FastAPI):  # noqa: ARG001
    yield
    await _pipeline.shutdown()


app = FastAPI(title="Chinese-boot", version="0.2.0", lifespan=lifespan)


# ---------------------------------------------------------------------------
# request models
# ---------------------------------------------------------------------------

class AnalyzePayload(BaseModel):
    symbol: str
    timeframe: int
    candles: list[dict[str, Any]] = []


class SubscribePayload(BaseModel):
    symbol: str
    timeframe: int


# ---------------------------------------------------------------------------
# routes
# ---------------------------------------------------------------------------

@app.get("/api/assets")
def get_assets():
    return {"assets": ASSETS, "timeframes": TIMEFRAMES}


@app.post("/api/subscribe")
async def subscribe(payload: SubscribePayload):
    """
    Subscribe the pipeline to a symbol + timeframe.
    The pipeline will stream live prices from Twelve Data and
    accumulate candles for analysis.
    """
    valid_symbols = {a["symbol"] for a in ASSETS}
    if payload.symbol not in valid_symbols:
        raise HTTPException(
            status_code=400,
            detail=f"Unknown symbol '{payload.symbol}'. "
                   f"Valid: {sorted(valid_symbols)}",
        )

    valid_tf = {t["seconds"] for t in TIMEFRAMES}
    if payload.timeframe not in valid_tf:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported timeframe {payload.timeframe}s. "
                   f"Valid: {sorted(valid_tf)}",
        )

    await _pipeline.set_subscription(payload.symbol, payload.timeframe)
    return {"ok": True, "symbol": payload.symbol, "timeframe": payload.timeframe}


@app.get("/api/live-market/status")
def live_market_status():
    """
    Report the live-market pipeline status.

    Returns:
        provider, connection status, selected symbol/timeframe,
        latest verified price, latest candle, data timestamp,
        whether data is live/verified, recent candles, analysis result.
    """
    status = _pipeline.status()
    latest_price_info = _pipeline.get_latest_price()
    current_candle = _pipeline.get_current_candle()
    recent_candles = _pipeline.get_candles(limit=20)
    analysis = _pipeline.get_analysis()

    return {
        "provider": status["provider"],
        "connected": status["connected"],
        "live_data": status["live_data"],
        "selected_symbol": status["symbol"],
        "selected_timeframe": status["timeframe_seconds"],
        "latest_price": (
            latest_price_info.get("price") if latest_price_info else None
        ),
        "data_timestamp": (
            latest_price_info.get("timestamp") if latest_price_info else None
        ),
        "is_live": status["connected"] and bool(latest_price_info),
        # is_verified will diverge from is_live once additional
        # data-integrity checks are layered in.
        "is_verified": status["connected"] and bool(latest_price_info),
        "streaming": status["streaming"],
        "last_error": status["last_error"][:200] if status["last_error"] else None,
        "latest_candle": current_candle,
        "recent_candles": recent_candles,
        "analysis": analysis,
        "candle_count": status["candle_count"],
    }


@app.post("/api/analyze")
def analyze_market(payload: AnalyzePayload):
    """
    Run signal analysis on a supplied candle history.

    When no candles are provided, the pipeline's live candle history
    is used (if a subscription is active).
    """
    candles = payload.candles

    if not candles:
        # fall back to live pipeline candle history
        candles = _pipeline.get_candles()

    return analyze(
        {
            "symbol": payload.symbol,
            "timeframe": payload.timeframe,
            "candles": candles,
        }
    )


@app.get("/")
def index():
    html_path = ROOT / "frontend" / "index.html"
    if not html_path.exists():
        return {"message": "Chinese-boot API is running."}
    return FileResponse(html_path)
