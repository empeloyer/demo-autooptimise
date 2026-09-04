package com.btcsignal.app.engine

import com.btcsignal.app.data.model.Signal
import com.btcsignal.app.data.model.SignalStatus
import com.btcsignal.app.data.model.StrategyDatabase
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

enum class AutoOptimizeVerdict { UNBLOCK, BLOCK, INSUFFICIENT_DATA }

data class BlockStat(
    val blockIndex: Int,
    val n: Int,
    val wins: Int,
    val winRatePct: Double
)

data class StrategyAutoOptimizeStat(
    val strategyId: String,
    val n: Int,
    val wins: Int,
    val winRatePct: Double,
    val pnlUsd: Double,
    val pnlPerSignalUsd: Double,
    val zScoreVsBreakeven: Double,
    val blocks: List<BlockStat>,
    val passesNoBlowup: Boolean,
    val passesNoConcentration: Boolean
)

data class StrategyAutoOptimizeDecision(
    val strategyId: String,
    val stat: StrategyAutoOptimizeStat?,   // null only when n == 0 (strategy never fired in the window)
    val wasBlocked: Boolean,
    val verdict: AutoOptimizeVerdict,
    val reason: String
) {
    /** The blocked state the decision resolves to applying — INSUFFICIENT_DATA is a
     *  deliberate no-op that keeps whatever the strategy's state already was. */
    val newBlockedState: Boolean get() = when (verdict) {
        AutoOptimizeVerdict.UNBLOCK -> false
        AutoOptimizeVerdict.BLOCK -> true
        AutoOptimizeVerdict.INSUFFICIENT_DATA -> wasBlocked
    }
    val changed: Boolean get() = newBlockedState != wasBlocked
}

data class AutoOptimizeResult(
    val windowDays: Int,
    val blockDays: Int,
    val evaluatedAtMillis: Long,
    val decisions: List<StrategyAutoOptimizeDecision>
) {
    val unblockedCount get() = decisions.count { it.verdict == AutoOptimizeVerdict.UNBLOCK }
    val blockedCount get() = decisions.count { it.verdict == AutoOptimizeVerdict.BLOCK }
    val insufficientCount get() = decisions.count { it.verdict == AutoOptimizeVerdict.INSUFFICIENT_DATA }
    val changedCount get() = decisions.count { it.changed }
}

/**
 * Auto-Optimize (Settings screen "Auto-Optimize" button). Decides which strategies stay
 * eligible to fire live, using the SAME statistical bar the Strategy Research Report
 * itself used to accept a strategy in the first place (section 4: min sample, z-score
 * vs breakeven, no-blowup, no-concentration) -- re-applied here to a recent walk-forward
 * window instead of a one-off historical snapshot.
 *
 * WHY NOT JUST "REFIT ON LAST 30 DAYS": a 1-month-only re-fit was evaluated empirically
 * (BTC_5m 1-year backtest, see project notes) before this class was written. A single
 * calendar month gives too few signals per strategy (many narrow regime_gate strategies
 * see well under 30 signals/month) to tell a real edge from noise, and month-to-month
 * win rate is only moderately persistent (measured Pearson r ~= 0.67 across the same
 * 1-year backtest) -- re-fitting indicator thresholds on that little data chases the
 * market rather than correcting for it. A longer window (default 180 days / ~6 months),
 * split into blockDays-sized blocks for the no-blowup/no-concentration checks, was the
 * configuration validated forward-out-of-sample in that same test: applied at the
 * 2026-03-01 decision point it roughly halved the worst month's drawdown (-$133.5 ->
 * -$33.5 in the following month) while keeping win rate flat-to-better in good months.
 *
 * WHAT THIS CLASS DOES NOT DO: it never touches indicator thresholds, components, or
 * regime gates -- those are the Strategy Database's, untouched. It only ever flips a
 * strategy's blocked flag (the same flag the Strategies screen's manual Block button
 * already writes to [com.btcsignal.app.data.repository.SettingsRepository]), so the
 * result is fully inspectable and fully reversible by hand at any time.
 */
object AutoOptimizeEngine {

    const val DEFAULT_WINDOW_DAYS = 180
    const val DEFAULT_BLOCK_DAYS = 30
    private const val MIN_SAMPLE_SIZE = 30
    private const val MIN_Z_SCORE = 1.0
    private const val BLOWUP_MIN_BLOCK_N = 10
    private const val BLOWUP_MAX_BLOCK_WIN_RATE = 40.0
    private const val MAX_BLOCK_CONCENTRATION = 0.70

    fun evaluate(
        database: StrategyDatabase,
        signals: List<Signal>,
        currentlyBlocked: Set<String>,
        windowStartMillis: Long,
        windowEndMillis: Long,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
        blockDays: Int = DEFAULT_BLOCK_DAYS,
        evaluatedAtMillis: Long = System.currentTimeMillis()
    ): AutoOptimizeResult {
        val breakeven = database.financialModel.breakevenWinRatePct / 100.0
        val resolved = signals.filter {
            it.status != SignalStatus.ACTIVE &&
                it.candleOpenTimeMillis in windowStartMillis until windowEndMillis
        }
        val byStrategy = resolved.groupBy { it.activeStrategyId }
        val blockMillis = blockDays * 24L * 60 * 60 * 1000

        val decisions = database.strategies.map { strategy ->
            val strategySignals = (byStrategy[strategy.id] ?: emptyList()).sortedBy { it.candleOpenTimeMillis }
            val wasBlocked = strategy.id in currentlyBlocked
            if (strategySignals.isEmpty()) {
                return@map StrategyAutoOptimizeDecision(
                    strategyId = strategy.id, stat = null, wasBlocked = wasBlocked,
                    verdict = AutoOptimizeVerdict.INSUFFICIENT_DATA,
                    reason = "No signals fired in the last $windowDays days \u2014 left unchanged rather than guessing."
                )
            }

            val n = strategySignals.size
            val wins = strategySignals.count { it.status == SignalStatus.WON }
            val winRate = wins.toDouble() / n
            val pnl = strategySignals.sumOf { it.pnlUsd ?: 0.0 }
            val se = sqrt(breakeven * (1 - breakeven) / n)
            val z = if (se > 0) (winRate - breakeven) / se else 0.0

            val blocks = strategySignals
                .groupBy { ((it.candleOpenTimeMillis - windowStartMillis) / blockMillis).toInt() }
                .map { (idx, sigs) ->
                    val bw = sigs.count { it.status == SignalStatus.WON }
                    BlockStat(idx, sigs.size, bw, bw.toDouble() / sigs.size * 100.0)
                }
                .sortedBy { it.blockIndex }

            val noBlowup = blocks.none { it.n >= BLOWUP_MIN_BLOCK_N && it.winRatePct < BLOWUP_MAX_BLOCK_WIN_RATE }
            val noConcentration = (blocks.maxOfOrNull { it.n } ?: 0).toDouble() / n <= MAX_BLOCK_CONCENTRATION

            val stat = StrategyAutoOptimizeStat(
                strategyId = strategy.id, n = n, wins = wins, winRatePct = winRate * 100.0,
                pnlUsd = pnl, pnlPerSignalUsd = pnl / n, zScoreVsBreakeven = z, blocks = blocks,
                passesNoBlowup = noBlowup, passesNoConcentration = noConcentration
            )

            if (n < MIN_SAMPLE_SIZE) {
                StrategyAutoOptimizeDecision(
                    strategyId = strategy.id, stat = stat, wasBlocked = wasBlocked,
                    verdict = AutoOptimizeVerdict.INSUFFICIENT_DATA,
                    reason = "Only $n signals in $windowDays days (need $MIN_SAMPLE_SIZE+) \u2014 left unchanged rather than guessing."
                )
            } else {
                val eligible = z >= MIN_Z_SCORE && noBlowup && noConcentration && pnl > 0
                if (eligible) {
                    StrategyAutoOptimizeDecision(
                        strategyId = strategy.id, stat = stat, wasBlocked = wasBlocked,
                        verdict = AutoOptimizeVerdict.UNBLOCK,
                        reason = "n=$n, win rate ${"%.1f".format(winRate * 100)}%%, z=${"%.2f".format(z)} " +
                            "\u2265 $MIN_Z_SCORE vs breakeven, passes no-blowup/no-concentration, PnL positive."
                    )
                } else {
                    val failReasons = buildList {
                        if (z < MIN_Z_SCORE) add("z=${"%.2f".format(z)} < $MIN_Z_SCORE")
                        if (!noBlowup) add("failed no-blowup (a block had \u226510 signals & <40% win rate)")
                        if (!noConcentration) add("failed no-concentration (one block held >70% of signals)")
                        if (pnl <= 0) add("PnL \u2264 0 over the window ($${"%.1f".format(pnl)})")
                    }
                    StrategyAutoOptimizeDecision(
                        strategyId = strategy.id, stat = stat, wasBlocked = wasBlocked,
                        verdict = AutoOptimizeVerdict.BLOCK,
                        reason = "n=$n, win rate ${"%.1f".format(winRate * 100)}%% \u2014 " + failReasons.joinToString("; ")
                    )
                }
            }
        }

        return AutoOptimizeResult(windowDays, blockDays, evaluatedAtMillis, decisions)
    }
}
