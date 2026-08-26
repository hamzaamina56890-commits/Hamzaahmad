from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable

from backend.market.assets import TIMEFRAMES

MIN_CANDLE_HISTORY = 21
FAST_EMA_PERIOD = 9
SLOW_EMA_PERIOD = 21
RSI_PERIOD = 14
STRUCTURE_WINDOW = 5
LEVEL_WINDOW = 10
MOMENTUM_LOOKBACK = 5
SUPPORTED_TIMEFRAMES = {
    timeframe["seconds"] for timeframe in TIMEFRAMES
}
TIMEFRAME_LABELS = {
    timeframe["seconds"]: timeframe["label"]
    for timeframe in TIMEFRAMES
}


class AnalysisValidationError(ValueError):
    """Raised when analysis input is malformed or unsafe."""


@dataclass(frozen=True)
class NormalizedCandle:
    symbol: str
    timeframe_seconds: int
    start_time: int
    open: float
    high: float
    low: float
    close: float


@dataclass(frozen=True)
class AnalysisRequest:
    symbol: str
    timeframe_seconds: int
    candles: list[NormalizedCandle]
    current_price: float
    data_source: str
    data_verified: bool
    timestamp: str


def analyze(payload: dict[str, Any]) -> dict[str, Any]:
    request = _parse_request(payload)
    candles = request.candles

    closes = [candle.close for candle in candles]
    opens = [candle.open for candle in candles]
    highs = [candle.high for candle in candles]
    lows = [candle.low for candle in candles]
    latest = candles[-1]
    previous = candles[-2]

    ema_fast = _ema(closes, FAST_EMA_PERIOD)
    ema_slow = _ema(closes, SLOW_EMA_PERIOD)
    sma_slow = _sma(closes, SLOW_EMA_PERIOD)
    rsi = _rsi(closes, RSI_PERIOD)
    momentum = _price_change(
        closes[-(MOMENTUM_LOOKBACK + 1)],
        closes[-1],
    )
    structure_bias = _structure_bias(highs, lows, closes)
    candle_strength = _candle_strength(latest)
    support = min(lows[-LEVEL_WINDOW:])
    resistance = max(highs[-LEVEL_WINDOW:])
    support_gap = _relative_distance(request.current_price, support)
    resistance_gap = _relative_distance(resistance, request.current_price)

    bullish_score = 0
    bearish_score = 0
    bullish_confirmation = 0
    bearish_confirmation = 0
    bullish_directional_evidence = 0
    bearish_directional_evidence = 0
    bullish_reasons: list[str] = []
    bearish_reasons: list[str] = []

    if ema_fast > ema_slow and request.current_price > ema_slow:
        bullish_score += 2
        bullish_confirmation += 1
        bullish_reasons.append(
            "Price is above the slow EMA and the fast EMA remains above the slow EMA."
        )
    elif ema_fast < ema_slow and request.current_price < ema_slow:
        bearish_score += 2
        bearish_confirmation += 1
        bearish_reasons.append(
            "Price is below the slow EMA and the fast EMA remains below the slow EMA."
        )

    if ema_slow > sma_slow and request.current_price >= ema_fast:
        bullish_score += 1
        bullish_confirmation += 1
        bullish_reasons.append(
            "The moving-average stack is aligned upward."
        )
    elif ema_slow < sma_slow and request.current_price <= ema_fast:
        bearish_score += 1
        bearish_confirmation += 1
        bearish_reasons.append(
            "The moving-average stack is aligned downward."
        )

    if rsi >= 58:
        bullish_score += 1
        bullish_confirmation += 1
        bullish_directional_evidence += 1
        bullish_reasons.append(
            "RSI shows bullish momentum without implying certainty."
        )
    elif rsi <= 42:
        bearish_score += 1
        bearish_confirmation += 1
        bearish_directional_evidence += 1
        bearish_reasons.append(
            "RSI shows bearish momentum without implying certainty."
        )

    if momentum >= 0.002:
        bullish_score += 1
        bullish_confirmation += 1
        bullish_directional_evidence += 1
        bullish_reasons.append(
            "Recent closing prices show positive momentum."
        )
    elif momentum <= -0.002:
        bearish_score += 1
        bearish_confirmation += 1
        bearish_directional_evidence += 1
        bearish_reasons.append(
            "Recent closing prices show negative momentum."
        )

    if structure_bias > 0:
        bullish_score += 1
        bullish_confirmation += 1
        bullish_directional_evidence += 1
        bullish_reasons.append(
            "Recent price structure is printing higher highs and higher lows."
        )
    elif structure_bias < 0:
        bearish_score += 1
        bearish_confirmation += 1
        bearish_directional_evidence += 1
        bearish_reasons.append(
            "Recent price structure is printing lower highs and lower lows."
        )

    if candle_strength["direction"] == "bullish":
        bullish_score += 1
        bullish_confirmation += 1
        bullish_directional_evidence += 1
        bullish_reasons.append(
            "The latest candle closed with stronger bullish body and lower-wick support."
        )
    elif candle_strength["direction"] == "bearish":
        bearish_score += 1
        bearish_confirmation += 1
        bearish_directional_evidence += 1
        bearish_reasons.append(
            "The latest candle closed with stronger bearish body and upper-wick resistance."
        )

    if support_gap <= 0.003 and latest.close > latest.open:
        bullish_score += 1
        bullish_confirmation += 1
        bullish_reasons.append(
            "Price is holding near recent support while buyers defended the latest candle."
        )

    if resistance_gap <= 0.003 and latest.close < latest.open:
        bearish_score += 1
        bearish_confirmation += 1
        bearish_reasons.append(
            "Price is pressing near recent resistance while sellers controlled the latest candle."
        )

    trend = _trend_label(ema_fast, ema_slow, structure_bias)
    score_gap = abs(bullish_score - bearish_score)
    dominant_score = max(bullish_score, bearish_score)

    if (
        bullish_score >= 4
        and score_gap >= 2
        and bullish_confirmation >= 3
        and bullish_directional_evidence >= 1
    ):
        signal = "UP"
        reasons = bullish_reasons
        confidence = _confidence(dominant_score, score_gap, signal)
    elif (
        bearish_score >= 4
        and score_gap >= 2
        and bearish_confirmation >= 3
        and bearish_directional_evidence >= 1
    ):
        signal = "DOWN"
        reasons = bearish_reasons
        confidence = _confidence(dominant_score, score_gap, signal)
    else:
        signal = "NEUTRAL"
        reasons = _neutral_reasons(
            bullish_score,
            bearish_score,
            bullish_reasons,
            bearish_reasons,
            latest,
            previous,
        )
        confidence = _confidence(dominant_score, score_gap, signal)

    return {
        "symbol": request.symbol,
        "timeframe": {
            "seconds": request.timeframe_seconds,
            "label": TIMEFRAME_LABELS[request.timeframe_seconds],
        },
        "current_price": round(request.current_price, 6),
        "signal": signal,
        "confidence": confidence,
        "trend": trend,
        "indicator_values": {
            "rsi": round(rsi, 2),
            "ema_fast": round(ema_fast, 6),
            "ema_slow": round(ema_slow, 6),
            "sma_slow": round(sma_slow, 6),
            "momentum_pct": round(momentum * 100, 4),
            "support": round(support, 6),
            "resistance": round(resistance, 6),
            "candle_body_ratio": round(
                candle_strength["body_ratio"], 4
            ),
            "upper_wick_ratio": round(
                candle_strength["upper_wick_ratio"], 4
            ),
            "lower_wick_ratio": round(
                candle_strength["lower_wick_ratio"], 4
            ),
            "recent_structure": (
                "higher-highs/higher-lows"
                if structure_bias > 0
                else "lower-highs/lower-lows"
                if structure_bias < 0
                else "mixed"
            ),
        },
        "reasons": reasons,
        "timestamp": request.timestamp,
        "data_source": request.data_source,
        "data_verified": request.data_verified,
        "candle_count": len(candles),
    }


def _parse_request(payload: Any) -> AnalysisRequest:
    if not isinstance(payload, dict):
        raise AnalysisValidationError(
            "Malformed request data: payload must be an object."
        )

    symbol = payload.get("symbol")
    if not isinstance(symbol, str) or not symbol.strip():
        raise AnalysisValidationError(
            "Malformed request data: symbol is required."
        )
    symbol = symbol.strip()

    timeframe_seconds = payload.get(
        "timeframe_seconds",
        payload.get("timeframe"),
    )
    if not isinstance(timeframe_seconds, int):
        raise AnalysisValidationError(
            "Malformed request data: timeframe must be an integer number of seconds."
        )
    if timeframe_seconds not in SUPPORTED_TIMEFRAMES:
        raise AnalysisValidationError(
            f"Unsupported timeframe: {timeframe_seconds}"
        )

    candles_payload = payload.get("candles")
    if not isinstance(candles_payload, list) or not candles_payload:
        raise AnalysisValidationError("Missing candles in request data.")

    candles = _normalize_candles(
        candles_payload,
        symbol,
        timeframe_seconds,
    )
    if len(candles) < MIN_CANDLE_HISTORY:
        raise AnalysisValidationError(
            f"Insufficient candle history: need at least {MIN_CANDLE_HISTORY} candles."
        )
    _validate_candle_sequence(candles, timeframe_seconds)

    current_price = payload.get("current_price", candles[-1].close)
    current_price = _validate_price(current_price, "current_price")

    data_source = payload.get("data_source")
    if not isinstance(data_source, str) or not data_source.strip():
        raise AnalysisValidationError(
            "Malformed request data: data_source is required."
        )

    data_verified = payload.get("data_verified")
    if data_verified is not True:
        raise AnalysisValidationError(
            "Verified OHLC data is required for analysis."
        )

    timestamp = payload.get("timestamp")
    if not isinstance(timestamp, str) or not timestamp.strip():
        timestamp = datetime.now(timezone.utc).isoformat()

    return AnalysisRequest(
        symbol=symbol,
        timeframe_seconds=timeframe_seconds,
        candles=candles,
        current_price=current_price,
        data_source=data_source.strip(),
        data_verified=True,
        timestamp=timestamp,
    )


def _normalize_candles(
    candles_payload: list[Any],
    symbol: str,
    timeframe_seconds: int,
) -> list[NormalizedCandle]:
    normalized: list[NormalizedCandle] = []

    for index, raw_candle in enumerate(candles_payload):
        if not isinstance(raw_candle, dict):
            raise AnalysisValidationError(
                f"Malformed candle at index {index}: expected an object."
            )

        candle_symbol = raw_candle.get("symbol", symbol)
        if candle_symbol != symbol:
            raise AnalysisValidationError(
                f"Candle symbol mismatch at index {index}."
            )

        candle_timeframe = raw_candle.get(
            "timeframe_seconds",
            timeframe_seconds,
        )
        if candle_timeframe != timeframe_seconds:
            raise AnalysisValidationError(
                f"Candle timeframe mismatch at index {index}."
            )

        start_time = raw_candle.get("start_time")
        if not isinstance(start_time, int):
            raise AnalysisValidationError(
                f"Malformed candle at index {index}: start_time must be an integer."
            )

        open_price = _validate_price(raw_candle.get("open"), f"candles[{index}].open")
        high_price = _validate_price(raw_candle.get("high"), f"candles[{index}].high")
        low_price = _validate_price(raw_candle.get("low"), f"candles[{index}].low")
        close_price = _validate_price(
            raw_candle.get("close"),
            f"candles[{index}].close",
        )

        if high_price < low_price:
            raise AnalysisValidationError(
                f"Invalid prices in candle at index {index}: high must be >= low."
            )
        if high_price < max(open_price, close_price):
            raise AnalysisValidationError(
                f"Invalid prices in candle at index {index}: high is inconsistent."
            )
        if low_price > min(open_price, close_price):
            raise AnalysisValidationError(
                f"Invalid prices in candle at index {index}: low is inconsistent."
            )

        normalized.append(
            NormalizedCandle(
                symbol=symbol,
                timeframe_seconds=timeframe_seconds,
                start_time=start_time,
                open=open_price,
                high=high_price,
                low=low_price,
                close=close_price,
            )
        )

    normalized.sort(key=lambda candle: candle.start_time)
    return normalized


def _validate_candle_sequence(
    candles: list[NormalizedCandle],
    timeframe_seconds: int,
) -> None:
    seen_times: set[int] = set()

    for candle in candles:
        if candle.start_time in seen_times:
            raise AnalysisValidationError(
                "Malformed request data: duplicate candle start_time values are not allowed."
            )
        seen_times.add(candle.start_time)

    for previous, current in zip(candles, candles[1:]):
        gap = current.start_time - previous.start_time
        if gap != timeframe_seconds:
            raise AnalysisValidationError(
                "Missing candles detected in the provided history."
            )


def _validate_price(value: Any, field_name: str) -> float:
    if not isinstance(value, (int, float)):
        raise AnalysisValidationError(
            f"Malformed request data: {field_name} must be numeric."
        )

    numeric_value = float(value)
    if not math.isfinite(numeric_value):
        raise AnalysisValidationError(
            f"Invalid prices: {field_name} must be finite."
        )
    if numeric_value <= 0:
        raise AnalysisValidationError(
            f"Invalid prices: {field_name} must be greater than zero."
        )

    return numeric_value


def _ema(values: Iterable[float], period: int) -> float:
    sequence = list(values)
    multiplier = 2 / (period + 1)
    ema_value = sum(sequence[:period]) / period

    for value in sequence[period:]:
        ema_value = (value - ema_value) * multiplier + ema_value

    return ema_value


def _sma(values: Iterable[float], period: int) -> float:
    sequence = list(values)
    return sum(sequence[-period:]) / period


def _rsi(closes: list[float], period: int) -> float:
    if len(closes) < period + 1:
        raise AnalysisValidationError(
            "Insufficient candle history for RSI calculation."
        )

    changes = [
        current - previous
        for previous, current in zip(closes, closes[1:])
    ]
    gains = [max(change, 0.0) for change in changes]
    losses = [abs(min(change, 0.0)) for change in changes]

    average_gain = sum(gains[:period]) / period
    average_loss = sum(losses[:period]) / period

    for gain, loss in zip(gains[period:], losses[period:]):
        average_gain = ((average_gain * (period - 1)) + gain) / period
        average_loss = ((average_loss * (period - 1)) + loss) / period

    if average_loss < 1e-10:
        return 100.0

    relative_strength = average_gain / average_loss
    return 100 - (100 / (1 + relative_strength))


def _price_change(previous: float, current: float) -> float:
    return (current - previous) / previous


def _structure_bias(
    highs: list[float],
    lows: list[float],
    closes: list[float],
) -> int:
    recent_highs = highs[-STRUCTURE_WINDOW:]
    recent_lows = lows[-STRUCTURE_WINDOW:]
    recent_closes = closes[-STRUCTURE_WINDOW:]

    ascending = (
        recent_highs[-1] > recent_highs[-2]
        and recent_lows[-1] > recent_lows[-2]
        and recent_closes[-1] > recent_closes[0]
    )
    descending = (
        recent_highs[-1] < recent_highs[-2]
        and recent_lows[-1] < recent_lows[-2]
        and recent_closes[-1] < recent_closes[0]
    )

    if ascending:
        return 1
    if descending:
        return -1
    return 0


def _candle_strength(candle: NormalizedCandle) -> dict[str, float | str]:
    total_range = candle.high - candle.low
    if total_range <= 0:
        raise AnalysisValidationError(
            "Invalid prices: candle range must be greater than zero."
        )

    body = abs(candle.close - candle.open)
    upper_wick = candle.high - max(candle.open, candle.close)
    lower_wick = min(candle.open, candle.close) - candle.low
    close_position = (candle.close - candle.low) / total_range
    body_ratio = body / total_range
    upper_wick_ratio = upper_wick / total_range
    lower_wick_ratio = lower_wick / total_range

    direction = "neutral"
    if (
        candle.close > candle.open
        and body_ratio >= 0.45
        and close_position >= 0.6
        and lower_wick_ratio >= upper_wick_ratio
    ):
        direction = "bullish"
    elif (
        candle.close < candle.open
        and body_ratio >= 0.45
        and close_position <= 0.4
        and upper_wick_ratio >= lower_wick_ratio
    ):
        direction = "bearish"

    return {
        "direction": direction,
        "body_ratio": body_ratio,
        "upper_wick_ratio": upper_wick_ratio,
        "lower_wick_ratio": lower_wick_ratio,
    }


def _relative_distance(reference: float, value: float) -> float:
    return abs(reference - value) / reference


def _trend_label(
    ema_fast: float,
    ema_slow: float,
    structure_bias: int,
) -> str:
    if ema_fast > ema_slow and structure_bias > 0:
        return "UPTREND"
    if ema_fast < ema_slow and structure_bias < 0:
        return "DOWNTREND"
    return "SIDEWAYS"


def _confidence(
    dominant_score: int,
    score_gap: int,
    signal: str,
) -> float:
    if signal == "NEUTRAL":
        return round(min(0.55, 0.3 + (score_gap * 0.05)), 2)

    score = 0.45 + (dominant_score * 0.05) + (score_gap * 0.04)
    return round(min(0.82, score), 2)


def _neutral_reasons(
    bullish_score: int,
    bearish_score: int,
    bullish_reasons: list[str],
    bearish_reasons: list[str],
    latest: NormalizedCandle,
    previous: NormalizedCandle,
) -> list[str]:
    reasons: list[str] = []

    if bullish_score == bearish_score:
        reasons.append(
            "Bullish and bearish signals are balanced, so the setup remains neutral."
        )
    else:
        reasons.append(
            "Available verified candles do not provide enough directional confluence."
        )

    if latest.close == previous.close:
        reasons.append(
            "The last two closes are flat, which weakens short-term momentum."
        )

    reasons.extend(bullish_reasons[:1])
    reasons.extend(bearish_reasons[:1])
    return reasons[:4]
