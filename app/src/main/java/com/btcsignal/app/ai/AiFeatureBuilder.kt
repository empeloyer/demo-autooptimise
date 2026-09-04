package com.btcsignal.app.ai

import com.btcsignal.app.data.model.*
import com.btcsignal.app.engine.EvalContext
import com.btcsignal.app.engine.indicators.Indicators

/**
 * The ONNX model's input contract. This is a CONTRACT, not a trained artifact: this app
 * ships no model and trains none. Whatever .onnx file the person downloads via the AI
 * Engine settings page must accept exactly [FEATURE_NAMES].size float32 inputs in exactly
 * this order and return exactly [OUTPUT_NAMES].size float32 outputs in exactly this order
 * -- [AiInferenceEngine.validate] checks the tensor shapes at load time and refuses to
 * enable AI Assist if they don't match, rather than silently feeding a mismatched model
 * garbage input.
 *
 * Design choices, so a future model author knows what each feature means:
 *   - Every indicator reused here is one CoreSignalEngine/ComponentEvaluator already
 *     computes for the rule-based strategies (see engine/indicators/Indicators.kt) on the
 *     SAME closed-candle data (no look-ahead) -- the AI sees the same information the
 *     deterministic engine does, at the 5-minute timeframe, plus the immediate 1-2 minute
 *     entry move the whole app is built around.
 *   - Regime is one-hotted (9 flags) rather than passed as an index, since trend/vol/
 *     momentum are unordered categories, not a scale.
 *   - The rule engine's own candidate (direction as +1/-1/0, and its Dynamic Score) is
 *     included as a feature deliberately: the intent is an AI that refines the
 *     rule-engine's call using the same broader context a person reviewing the trade would
 *     use, not a model working blind from raw indicators alone.
 */
object AiFeatureBuilder {

    const val FEATURE_VERSION = 1

    val FEATURE_NAMES: List<String> = listOf(
        "move_pct_at_checkpoint",       // 0: (referencePrice-candleOpen)/candleOpen*100
        "checkpoint_is_b",              // 1: 0.0 at Checkpoint A, 1.0 at Checkpoint B
        "rsi_14_5m",                    // 2
        "rsi_7_1m",                     // 3
        "adx_14_5m",                    // 4
        "plus_di_14_5m",                // 5
        "minus_di_14_5m",               // 6
        "cci_20_5m",                    // 7
        "bb_pctb_20_5m",                // 8
        "stoch_k_14_3_5m",              // 9
        "williams_r_14_5m",             // 10
        "obv_slope_20_5m_norm",         // 11: raw OBV slope divided by 5m volume SMA(20) to keep scale bounded
        "ema_diff_pct_20_50_5m",        // 12: (EMA20-EMA50)/EMA50*100
        "macd_hist_12_26_9_5m",         // 13: macdLine - signalLine
        "regime_trend_bullish",         // 14
        "regime_trend_bearish",         // 15
        "regime_trend_sideways",        // 16
        "regime_vol_high",              // 17
        "regime_vol_medium",            // 18
        "regime_vol_low",               // 19
        "regime_mom_strong_up",         // 20
        "regime_mom_strong_down",       // 21
        "regime_mom_weak",              // 22
        "rule_engine_candidate_dir",    // 23: +1.0 GREEN, -1.0 RED
        "rule_engine_score"             // 24: winning strategy's Dynamic Score
    )

    /** probGreen, probRed -- a 2-way softmax/sigmoid pair. The model is expected to
     *  output values that already sum to ~1.0; [AiInferenceEngine] does not renormalize. */
    val OUTPUT_NAMES: List<String> = listOf("prob_green", "prob_red")

    /**
     * Builds the feature vector for the checkpoint the rule engine just decided on.
     * Returns null if any required closed-candle history is still warming up (mirrors
     * every ComponentEvaluator function's own null-on-insufficient-data behavior --
     * AI Assist simply abstains rather than guessing from partial data).
     */
    fun build(
        ctx: EvalContext,
        regime: MarketRegimeState,
        candidateDirection: Direction,
        candidateScore: Double
    ): FloatArray? {
        val candles5m = ctx.store.closed5m()
        val closes5m = candles5m.map { it.close }
        if (candleOpenInvalid(ctx.candleOpen)) return null

        val movePct = (ctx.referencePrice - ctx.candleOpen) / ctx.candleOpen * 100.0

        val rsi5m = Indicators.rsi(closes5m, 14) ?: return null
        // Mirrors ComponentEvaluator.closesForRsi's own convention for 1m-timeframe RSI
        // (see its docstring) exactly, rather than second-guessing it here: the whole point
        // of this feature vector is that the AI sees the same information the rule engine's
        // RSI(1m) components see, including that convention's particulars.
        val closes1m = (ctx.store.closed1m() + listOfNotNull(ctx.minute1Candle, ctx.minute2Candle)).map { it.close }
        val rsi1m = Indicators.rsi(closes1m, 7) ?: return null
        val adx = Indicators.adxDi(candles5m, 14) ?: return null
        val cci = Indicators.cci(candles5m, 20) ?: return null
        val bbPctB = Indicators.bollingerPercentB(closes5m, 20, 2.0) ?: return null
        val stoch = Indicators.stochastic(candles5m, 14, 3) ?: return null
        val willR = Indicators.williamsR(candles5m, 14) ?: return null
        val obvSlope = Indicators.obvSlope(candles5m, 20) ?: return null
        val volSma20 = Indicators.sma(candles5m.map { it.volume }, 20)?.takeIf { it > 0 } ?: 1.0
        val emaFast = Indicators.ema(closes5m, 20) ?: return null
        val emaSlow = Indicators.ema(closes5m, 50) ?: return null
        val macd = Indicators.macd(closes5m, 12, 26, 9) ?: return null

        return floatArrayOf(
            movePct.toFloat(),
            if (ctx.checkpoint == Checkpoint.B) 1f else 0f,
            rsi5m.toFloat(),
            rsi1m.toFloat(),
            adx.adx.toFloat(),
            adx.plusDi.toFloat(),
            adx.minusDi.toFloat(),
            cci.toFloat(),
            bbPctB.toFloat(),
            stoch.first.toFloat(),
            willR.toFloat(),
            (obvSlope / volSma20).toFloat(),
            (if (emaSlow != 0.0) (emaFast - emaSlow) / emaSlow * 100.0 else 0.0).toFloat(),
            (macd.macdLine - macd.signalLine).toFloat(),
            if (regime.trend == TrendRegime.BULLISH) 1f else 0f,
            if (regime.trend == TrendRegime.BEARISH) 1f else 0f,
            if (regime.trend == TrendRegime.SIDEWAYS) 1f else 0f,
            if (regime.volatility == VolatilityRegime.HIGH) 1f else 0f,
            if (regime.volatility == VolatilityRegime.MEDIUM) 1f else 0f,
            if (regime.volatility == VolatilityRegime.LOW) 1f else 0f,
            if (regime.momentum == MomentumRegime.STRONG_UP) 1f else 0f,
            if (regime.momentum == MomentumRegime.STRONG_DOWN) 1f else 0f,
            if (regime.momentum == MomentumRegime.WEAK) 1f else 0f,
            if (candidateDirection == Direction.GREEN) 1f else -1f,
            candidateScore.toFloat()
        )
    }

    private fun candleOpenInvalid(open: Double) = open == 0.0
}
