package com.btcsignal.app.ai

import com.btcsignal.app.data.model.*
import com.btcsignal.app.engine.EvalContext
import com.btcsignal.app.engine.MarketDataStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiFeatureBuilderTest {

    private lateinit var store: MarketDataStore

    @Before
    fun setUp() {
        store = MarketDataStore()
        // Enough mildly-oscillating history for every indicator AiFeatureBuilder touches --
        // the longest lookback is EMA(50)/MACD(26,9) on 5m candles, so >= 250 5m candles
        // (>= 1250 1m candles) comfortably clears every warmup requirement.
        var price = 50_000.0
        val base = 1_700_000_000_000L / 60_000L * 60_000L
        for (i in 0 until 260 * 5) {
            val wiggle = if (i % 2 == 0) 3.0 else -3.0
            val open = price
            val close = price + wiggle
            price = close
            val t = base + i * 60_000L
            store.addClosed1m(Candle(t, open, maxOf(open, close) + 0.5, minOf(open, close) - 0.5, close, 3.0, t + 59_999, true))
        }
    }

    private fun context(movePct: Double = 0.01): EvalContext {
        val candleOpen = store.closed5m().last().close
        return EvalContext(
            store = store,
            candleOpen = candleOpen,
            referencePrice = candleOpen * (1 + movePct / 100.0),
            checkpoint = Checkpoint.A,
            minute1Candle = store.closed1m().last(),
            minute2Candle = null
        )
    }

    private fun regime() = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK)

    @Test
    fun `feature vector has exactly as many values as FEATURE_NAMES declares`() {
        val features = AiFeatureBuilder.build(context(), regime(), Direction.GREEN, 1.0)
        assertNotNull("expected a feature vector once history is warmed up", features)
        assertEquals(AiFeatureBuilder.FEATURE_NAMES.size, features!!.size)
    }

    @Test
    fun `feature vector contains no NaN or infinite values`() {
        val features = AiFeatureBuilder.build(context(), regime(), Direction.GREEN, 1.0)!!
        features.forEachIndexed { i, v ->
            assertFalse("feature '${AiFeatureBuilder.FEATURE_NAMES[i]}' is NaN", v.isNaN())
            assertFalse("feature '${AiFeatureBuilder.FEATURE_NAMES[i]}' is infinite", v.isInfinite())
        }
    }

    @Test
    fun `checkpoint_is_b flag reflects the passed checkpoint`() {
        val ctxA = context()
        val ctxB = ctxA.copy(checkpoint = Checkpoint.B, minute2Candle = store.closed1m().last())

        val featuresA = AiFeatureBuilder.build(ctxA, regime(), Direction.GREEN, 1.0)!!
        val featuresB = AiFeatureBuilder.build(ctxB, regime(), Direction.GREEN, 1.0)!!

        val idx = AiFeatureBuilder.FEATURE_NAMES.indexOf("checkpoint_is_b")
        assertEquals(0f, featuresA[idx])
        assertEquals(1f, featuresB[idx])
    }

    @Test
    fun `exactly one regime one-hot flag is set per regime group`() {
        val features = AiFeatureBuilder.build(context(), regime(), Direction.GREEN, 1.0)!!
        val names = AiFeatureBuilder.FEATURE_NAMES

        val trendFlags = listOf("regime_trend_bullish", "regime_trend_bearish", "regime_trend_sideways").map { features[names.indexOf(it)] }
        val volFlags = listOf("regime_vol_high", "regime_vol_medium", "regime_vol_low").map { features[names.indexOf(it)] }
        val momFlags = listOf("regime_mom_strong_up", "regime_mom_strong_down", "regime_mom_weak").map { features[names.indexOf(it)] }

        assertEquals(1f, trendFlags.sum())
        assertEquals(1f, volFlags.sum())
        assertEquals(1f, momFlags.sum())
        // regime() above is SIDEWAYS/MEDIUM/WEAK specifically
        assertEquals(1f, features[names.indexOf("regime_trend_sideways")])
        assertEquals(1f, features[names.indexOf("regime_vol_medium")])
        assertEquals(1f, features[names.indexOf("regime_mom_weak")])
    }

    @Test
    fun `rule engine candidate direction feature is plus or minus one`() {
        val names = AiFeatureBuilder.FEATURE_NAMES
        val idx = names.indexOf("rule_engine_candidate_dir")

        val green = AiFeatureBuilder.build(context(), regime(), Direction.GREEN, 1.0)!!
        val red = AiFeatureBuilder.build(context(), regime(), Direction.RED, 1.0)!!

        assertEquals(1f, green[idx])
        assertEquals(-1f, red[idx])
    }

    @Test
    fun `move_pct_at_checkpoint feature matches the requested move`() {
        val names = AiFeatureBuilder.FEATURE_NAMES
        val idx = names.indexOf("move_pct_at_checkpoint")
        val features = AiFeatureBuilder.build(context(movePct = 0.02), regime(), Direction.GREEN, 1.0)!!
        assertEquals(0.02f, features[idx], 1e-4f)
    }

    @Test
    fun `returns null when candle history has not warmed up yet`() {
        val emptyStore = MarketDataStore()
        val ctx = EvalContext(
            store = emptyStore, candleOpen = 50_000.0, referencePrice = 50_005.0,
            checkpoint = Checkpoint.A, minute1Candle = null, minute2Candle = null
        )
        assertNull(AiFeatureBuilder.build(ctx, regime(), Direction.GREEN, 1.0))
    }
}
