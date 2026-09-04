package com.btcsignal.app.engine

import com.btcsignal.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class AutoOptimizeEngineTest {

    private fun strategy(id: String): StrategyDef = StrategyDef(
        id = id,
        marketConditionBucket = "test",
        regimeGateCode = "All",
        regimeGateDescription = "",
        logicDescription = "",
        components = emptyList(),
        directionDescription = "",
        performance = StrategyPerformance(100, 55.0, 100, 55.0, 0.1, 1.0),
        walkForwardBlocks = emptyList(),
        maxSignalOverlapPct = 0.0,
        confidenceFlag = null
    )

    private fun database(vararg ids: String): StrategyDatabase = StrategyDatabase(
        asset = "BTCUSDT",
        targetTimeframe = "5m",
        hardConstraints = HardConstraints(0.0..0.03, -0.03..0.0, outcomeTieCountsAsRed = true),
        financialModel = FinancialModelSpec(100.0, 1.0, 0.5, -1.0, breakevenWinRatePct = 66.667),
        strategies = ids.map { strategy(it) }
    )

    private fun signal(strategyId: String, dayOffset: Int, won: Boolean, windowStart: Long): Signal = Signal(
        signalId = java.util.UUID.randomUUID().toString(),
        candleId = "candle-$strategyId-$dayOffset-${System.nanoTime()}",
        candleOpenTimeMillis = windowStart + dayOffset * 24L * 60 * 60 * 1000,
        signalTimestampMillis = windowStart + dayOffset * 24L * 60 * 60 * 1000,
        candleOpen = 50_000.0,
        signalPrice = 50_010.0,
        direction = Direction.GREEN,
        activeStrategyId = strategyId,
        activeStrategyName = strategyId,
        marketRegime = MarketRegimeState(TrendRegime.SIDEWAYS, VolatilityRegime.MEDIUM, MomentumRegime.WEAK),
        strategyScore = 1.0,
        confidencePct = 70.0,
        entryMovePct = 0.01,
        checkpoint = Checkpoint.A,
        status = if (won) SignalStatus.WON else SignalStatus.LOST,
        pnlUsd = if (won) 0.5 else -1.0
    )

    /** Spreads [n] signals evenly across the window with the given win rate, so no single
     *  30-day block ends up over-concentrated (keeps the no-concentration check passing
     *  unless a test is deliberately checking it). */
    private fun evenSignals(strategyId: String, n: Int, winRate: Double, windowStart: Long, windowDays: Int = 180): List<Signal> {
        val step = windowDays.toDouble() / n
        return (0 until n).map { i ->
            val won = i < (n * winRate).toInt()
            signal(strategyId, (i * step).toInt(), won, windowStart)
        }
    }

    @Test
    fun `a strategy with a strong win rate and enough signals is unblocked`() {
        val db = database("STRAT-001")
        val windowStart = 0L
        val windowEnd = 180L * 24 * 60 * 60 * 1000
        val signals = evenSignals("STRAT-001", n = 100, winRate = 0.80, windowStart)

        val result = AutoOptimizeEngine.evaluate(
            db, signals, currentlyBlocked = setOf("STRAT-001"),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        )

        val decision = result.decisions.single()
        assertEquals(AutoOptimizeVerdict.UNBLOCK, decision.verdict)
        assertFalse(decision.newBlockedState)
        assertTrue(decision.changed)
    }

    @Test
    fun `a strategy with a losing win rate and enough signals is blocked`() {
        val db = database("STRAT-002")
        val windowStart = 0L
        val windowEnd = 180L * 24 * 60 * 60 * 1000
        val signals = evenSignals("STRAT-002", n = 100, winRate = 0.50, windowStart) // below breakeven 66.667%

        val result = AutoOptimizeEngine.evaluate(
            db, signals, currentlyBlocked = emptySet(),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        )

        val decision = result.decisions.single()
        assertEquals(AutoOptimizeVerdict.BLOCK, decision.verdict)
        assertTrue(decision.newBlockedState)
        assertTrue(decision.changed)
    }

    @Test
    fun `a strategy with too few signals is left unchanged regardless of win rate`() {
        val db = database("STRAT-003")
        val windowStart = 0L
        val windowEnd = 180L * 24 * 60 * 60 * 1000
        val signals = evenSignals("STRAT-003", n = 10, winRate = 1.0, windowStart) // perfect but tiny sample

        val resultWasBlocked = AutoOptimizeEngine.evaluate(
            db, signals, currentlyBlocked = setOf("STRAT-003"),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        ).decisions.single()
        assertEquals(AutoOptimizeVerdict.INSUFFICIENT_DATA, resultWasBlocked.verdict)
        assertTrue("insufficient data must preserve the prior blocked=true state", resultWasBlocked.newBlockedState)
        assertFalse(resultWasBlocked.changed)

        val resultWasUnblocked = AutoOptimizeEngine.evaluate(
            db, signals, currentlyBlocked = emptySet(),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        ).decisions.single()
        assertEquals(AutoOptimizeVerdict.INSUFFICIENT_DATA, resultWasUnblocked.verdict)
        assertFalse("insufficient data must preserve the prior blocked=false state", resultWasUnblocked.newBlockedState)
    }

    @Test
    fun `a strategy that never fired in the window is left unchanged with a null stat`() {
        val db = database("STRAT-004")
        val windowStart = 0L
        val windowEnd = 180L * 24 * 60 * 60 * 1000

        val decision = AutoOptimizeEngine.evaluate(
            db, emptyList(), currentlyBlocked = emptySet(),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        ).decisions.single()

        assertEquals(AutoOptimizeVerdict.INSUFFICIENT_DATA, decision.verdict)
        assertNull(decision.stat)
    }

    @Test
    fun `high overall win rate concentrated in a single block fails no-concentration and is blocked`() {
        val db = database("STRAT-005")
        val windowStart = 0L
        val windowEnd = 180L * 24 * 60 * 60 * 1000
        // 80 signals, all won, but every single one crammed into day 0-1 (one block) --
        // e.g. a strategy that only ever fires during one freak volatility spike.
        val signals = (0 until 80).map { i -> signal("STRAT-005", dayOffset = 0, won = true, windowStart) }

        val decision = AutoOptimizeEngine.evaluate(
            db, signals, currentlyBlocked = emptySet(),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        ).decisions.single()

        assertEquals(AutoOptimizeVerdict.BLOCK, decision.verdict)
        assertFalse(decision.stat!!.passesNoConcentration)
    }

    @Test
    fun `a bad block among otherwise good ones fails no-blowup and is blocked`() {
        val db = database("STRAT-006")
        val windowStart = 0L
        val windowEnd = 180L * 24 * 60 * 60 * 1000
        val goodBlocks = evenSignals("STRAT-006", n = 90, winRate = 0.85, windowStart) // spread across most blocks
        // Force a concentrated bad block: 15 signals, 20% win rate, all in block 5 (days 150-179).
        val badBlock = (0 until 15).map { signal("STRAT-006", dayOffset = 165, won = it < 3, windowStart) }

        val decision = AutoOptimizeEngine.evaluate(
            db, goodBlocks + badBlock, currentlyBlocked = emptySet(),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        ).decisions.single()

        assertFalse(decision.stat!!.passesNoBlowup)
        assertEquals(AutoOptimizeVerdict.BLOCK, decision.verdict)
    }

    @Test
    fun `signals outside the evaluation window are ignored`() {
        val db = database("STRAT-007")
        val windowStart = 100L * 24 * 60 * 60 * 1000
        val windowEnd = windowStart + 180L * 24 * 60 * 60 * 1000
        // All signals dated before the window starts.
        val signals = evenSignals("STRAT-007", n = 100, winRate = 0.9, windowStart = 0L)

        val decision = AutoOptimizeEngine.evaluate(
            db, signals, currentlyBlocked = emptySet(),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        ).decisions.single()

        assertEquals(AutoOptimizeVerdict.INSUFFICIENT_DATA, decision.verdict)
        assertNull(decision.stat)
    }

    @Test
    fun `result summary counts add up across multiple strategies`() {
        val db = database("A", "B", "C")
        val windowStart = 0L
        val windowEnd = 180L * 24 * 60 * 60 * 1000
        val signals = evenSignals("A", 100, 0.80, windowStart) +
            evenSignals("B", 100, 0.50, windowStart) +
            evenSignals("C", 5, 1.0, windowStart)

        val result = AutoOptimizeEngine.evaluate(
            db, signals, currentlyBlocked = emptySet(),
            windowStartMillis = windowStart, windowEndMillis = windowEnd
        )

        assertEquals(1, result.unblockedCount)
        assertEquals(1, result.blockedCount)
        assertEquals(1, result.insufficientCount)
        assertEquals(2, result.changedCount) // A flips (unblocked -> unblocked = no-op is false since it started unblocked)
    }
}
