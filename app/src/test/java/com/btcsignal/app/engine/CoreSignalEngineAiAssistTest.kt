package com.btcsignal.app.engine

import com.btcsignal.app.ai.AiAssistConfig
import com.btcsignal.app.ai.AiMode
import com.btcsignal.app.ai.AiPrediction
import com.btcsignal.app.ai.AiPredictor
import com.btcsignal.app.data.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Deterministic stand-in for [com.btcsignal.app.ai.AiInferenceEngine] -- always "loaded",
 *  always returns the prediction the test configured, so these tests exercise exactly
 *  CoreSignalEngine's mode-gating logic and nothing about ONNX Runtime itself. */
private class FakeAiPredictor(private val prediction: AiPrediction?) : AiPredictor {
    override val isLoaded: Boolean = true
    override fun predict(features: FloatArray): AiPrediction? = prediction
}

class CoreSignalEngineAiAssistTest {

    private lateinit var store: MarketDataStore
    private lateinit var database: StrategyDatabase

    /** Same always-fires-GREEN-on-a-tiny-uptick synthetic strategy used by
     *  CoreSignalEngineEntryRangeTest, so these tests isolate AI Assist's effect from
     *  strategy-selection logic: without AI Assist this always signals GREEN. */
    private fun syntheticDatabase(): StrategyDatabase {
        val component = StrategyComponent(
            indicator = "micro_momentum",
            primitiveId = "test_micro_momentum",
            hypothesis = "continuation",
            params = mapOf("min_move_pct" to 0.001)
        )
        val strategy = StrategyDef(
            id = "TEST-001",
            marketConditionBucket = "test",
            regimeGateCode = "All",
            regimeGateDescription = "",
            logicDescription = "micro_momentum only",
            components = listOf(component),
            directionDescription = "",
            performance = StrategyPerformance(100, 55.0, 100, 55.0, 0.1, 1.0),
            walkForwardBlocks = emptyList(),
            maxSignalOverlapPct = 0.0,
            confidenceFlag = null
        )
        return StrategyDatabase(
            asset = "BTCUSDT",
            targetTimeframe = "5m",
            hardConstraints = HardConstraints(0.0..0.03, -0.03..0.0, outcomeTieCountsAsRed = true),
            financialModel = FinancialModelSpec(100.0, 1.0, 0.5, -1.0, 50.0),
            strategies = listOf(strategy)
        )
    }

    @Before
    fun setUp() {
        database = syntheticDatabase()
        store = MarketDataStore()
        var price = 50_000.0
        val base = 1_700_000_000_000L / 60_000L * 60_000L
        for (i in 0 until 220 * 5) {
            val wiggle = if (i % 2 == 0) 1.0 else -1.0
            val open = price
            val close = price + wiggle
            price = close
            val t = base + i * 60_000L
            store.addClosed1m(Candle(t, open, maxOf(open, close) + 0.5, minOf(open, close) - 0.5, close, 3.0, t + 59_999, true))
        }
    }

    private fun evaluate(aiConfig: AiAssistConfig?): EngineResult {
        val candleOpen = 50_000.0
        val referencePrice = candleOpen * 1.0002 // +0.02%, well inside the GREEN entry range
        return CoreSignalEngine.evaluateCheckpoint(
            database = database,
            store = store,
            candleOpenTimeMillis = System.currentTimeMillis(),
            candleOpen = candleOpen,
            checkpoint = Checkpoint.A,
            referencePrice = referencePrice,
            minute1Candle = null,
            minute2Candle = null,
            timestampMillis = System.currentTimeMillis(),
            statsProvider = { RecentWindowStats(0, 0.0) },
            aiConfig = aiConfig
        )
    }

    @Test
    fun `with AI disabled the rule engine signal fires unchanged`() {
        val result = evaluate(aiConfig = null)
        assertNotNull(result.signal)
        assertEquals(Direction.GREEN, result.signal!!.direction)
        assertFalse(result.signal!!.aiConsulted)
    }

    @Test
    fun `ADVISORY mode never blocks or changes a signal, even when AI disagrees`() {
        val ai = FakeAiPredictor(AiPrediction(probGreen = 0.1f, probRed = 0.9f, direction = Direction.RED, confidence = 0.9f))
        val config = AiAssistConfig(ai, AiMode.ADVISORY, confidenceThreshold = 0.55, overrideThreshold = 0.75)

        val result = evaluate(config)

        assertNotNull("ADVISORY must never suppress a rule-engine signal", result.signal)
        assertEquals(Direction.GREEN, result.signal!!.direction)
        assertTrue(result.signal!!.aiConsulted)
        assertEquals(false, result.signal!!.aiAgreed)
        assertFalse(result.signal!!.aiOverridden)
    }

    @Test
    fun `CONFIRM mode lets the signal through when AI agrees confidently`() {
        val ai = FakeAiPredictor(AiPrediction(probGreen = 0.9f, probRed = 0.1f, direction = Direction.GREEN, confidence = 0.9f))
        val config = AiAssistConfig(ai, AiMode.CONFIRM, confidenceThreshold = 0.55, overrideThreshold = 0.75)

        val result = evaluate(config)

        assertNotNull(result.signal)
        assertEquals(Direction.GREEN, result.signal!!.direction)
        assertEquals(true, result.signal!!.aiAgreed)
        assertFalse(result.signal!!.aiOverridden)
    }

    @Test
    fun `CONFIRM mode silently drops the signal when AI disagrees`() {
        val ai = FakeAiPredictor(AiPrediction(probGreen = 0.1f, probRed = 0.9f, direction = Direction.RED, confidence = 0.9f))
        val config = AiAssistConfig(ai, AiMode.CONFIRM, confidenceThreshold = 0.55, overrideThreshold = 0.75)

        val result = evaluate(config)

        assertNull("CONFIRM must drop a signal the AI disagrees with", result.signal)
        assertTrue(result.trace.signalDecision.contains("NO_SIGNAL"))
    }

    @Test
    fun `CONFIRM mode drops the signal when AI agrees but confidence is below threshold`() {
        val ai = FakeAiPredictor(AiPrediction(probGreen = 0.51f, probRed = 0.49f, direction = Direction.GREEN, confidence = 0.51f))
        val config = AiAssistConfig(ai, AiMode.CONFIRM, confidenceThreshold = 0.55, overrideThreshold = 0.75)

        val result = evaluate(config)

        assertNull(result.signal)
    }

    @Test
    fun `OVERRIDE mode flips direction when AI strongly disagrees`() {
        val ai = FakeAiPredictor(AiPrediction(probGreen = 0.1f, probRed = 0.9f, direction = Direction.RED, confidence = 0.9f))
        val config = AiAssistConfig(ai, AiMode.OVERRIDE, confidenceThreshold = 0.55, overrideThreshold = 0.75)

        val result = evaluate(config)

        assertNotNull("OVERRIDE with high-confidence disagreement must still produce a (flipped) signal", result.signal)
        assertEquals(Direction.RED, result.signal!!.direction)
        assertTrue(result.signal!!.aiOverridden)
    }

    @Test
    fun `OVERRIDE mode drops the signal when AI disagrees but not confidently enough to override`() {
        val ai = FakeAiPredictor(AiPrediction(probGreen = 0.35f, probRed = 0.65f, direction = Direction.RED, confidence = 0.65f))
        val config = AiAssistConfig(ai, AiMode.OVERRIDE, confidenceThreshold = 0.55, overrideThreshold = 0.75)

        val result = evaluate(config)

        assertNull("0.65 confidence disagreement is below the 0.75 override bar and below confirm too", result.signal)
    }

    @Test
    fun `AI Assist configured but not loaded behaves exactly like AI disabled`() {
        val ai = object : AiPredictor {
            override val isLoaded: Boolean = false
            override fun predict(features: FloatArray): AiPrediction? =
                throw IllegalStateException("must not be called when not loaded")
        }
        val config = AiAssistConfig(ai, AiMode.CONFIRM, confidenceThreshold = 0.55, overrideThreshold = 0.75)

        val result = evaluate(config)

        assertNotNull(result.signal)
        assertFalse(result.signal!!.aiConsulted)
    }
}
