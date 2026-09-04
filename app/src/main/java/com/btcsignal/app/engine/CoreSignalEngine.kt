package com.btcsignal.app.engine

import com.btcsignal.app.ai.AiAssistConfig
import com.btcsignal.app.ai.AiFeatureBuilder
import com.btcsignal.app.ai.AiMode
import com.btcsignal.app.data.model.*
import java.time.Instant
import java.util.UUID

data class EngineResult(
    val signal: Signal?,
    val trace: DebugTraceEntry
)

/**
 * The ONE Core Signal Engine (spec sections 11, 13, 39). LiveMonitoringService and
 * BacktestEngine both call [evaluateCheckpoint] with data of the same shape — the
 * former sourced from the Binance WebSocket, the latter from Binance historical REST
 * klines replayed in order — and get identical decisions for identical inputs. No
 * strategy logic is duplicated anywhere else in the app.
 *
 * Order of operations exactly matches spec section 7 (Minute 2 continuous-monitoring
 * procedure), reinterpreted at the discrete Checkpoint A / Checkpoint B granularity
 * that the Strategy Database itself defines (see StrategyRegistry / README for why):
 *   1. Hard Constraints (entry range, per candidate direction)
 *   2. Strategy conditions (AND of all components, same direction)
 *   3. Market Regime (strategy's regime_gate vs current 3-dimension regime state)
 *   4. Strategy Score (Dynamic Score)
 *   5. Conflict Resolver
 *   6. Build + lock Signal
 */
object CoreSignalEngine {

    fun evaluateCheckpoint(
        database: StrategyDatabase,
        store: MarketDataStore,
        candleOpenTimeMillis: Long,
        candleOpen: Double,
        checkpoint: Checkpoint,
        referencePrice: Double,
        minute1Candle: Candle?,
        minute2Candle: Candle?,
        timestampMillis: Long,
        statsProvider: (String) -> RecentWindowStats,
        blockedStrategyIds: Set<String> = emptySet(),
        /** Optional AI Assist. Null (the default) means every existing call site and test
         *  keeps behaving exactly as before AI Assist existed. */
        aiConfig: AiAssistConfig? = null
    ): EngineResult {
        val candleId = Instant.ofEpochMilli(candleOpenTimeMillis).toString()
        val movePct = if (candleOpen == 0.0) 0.0 else (referencePrice - candleOpen) / candleOpen * 100.0

        val closed5m = store.closed5m()
        val regime = MarketRegimeClassifier.classify(closed5m)

        if (regime == null) {
            val trace = DebugTraceEntry(
                candleId, timestampMillis, checkpoint, referencePrice, movePct,
                null, emptyList(), "N/A - insufficient regime data", "NO_SIGNAL: insufficient historical data to classify market regime", false
            )
            return EngineResult(null, trace)
        }

        val ctx = EvalContext(store, candleOpen, referencePrice, checkpoint, minute1Candle, minute2Candle)
        val strategyTraces = ArrayList<StrategyTraceEntry>()
        val votes = ArrayList<StrategyVote>()

        for (strategy in database.strategies) {
            if (strategy.id in blockedStrategyIds) {
                // User-blocked from the Strategies screen (spec: Block button) — treated
                // like a regime-ineligible strategy so it can never win the vote while blocked.
                strategyTraces.add(StrategyTraceEntry(strategy.id, false, emptyList(), emptyList(), false, null, null))
                continue
            }
            val eligible = strategy.regimeGateCode == "All" || strategy.regimeGateCode in regime.activeCodes()
            if (!eligible) {
                strategyTraces.add(StrategyTraceEntry(strategy.id, false, emptyList(), emptyList(), false, null, null))
                continue
            }

            val results = strategy.components.map { ComponentEvaluator.evaluate(it, ctx) }
            val dirs = results.map { it.direction }
            val nonNullDirs = dirs.filterNotNull().toSet()
            val allFired = dirs.none { it == null } && nonNullDirs.size == 1
            val firedDirection = if (allFired) nonNullDirs.first() else null

            var passesEntryRange = false
            if (firedDirection != null) {
                val range = if (firedDirection == Direction.GREEN)
                    database.hardConstraints.entryRangeGreenPct else database.hardConstraints.entryRangeRedPct
                passesEntryRange = movePct in range
            }

            val finalFired = allFired && passesEntryRange
            var score: Double? = null
            if (finalFired && firedDirection != null) {
                score = DynamicScore.compute(strategy, statsProvider(strategy.id))
                votes.add(StrategyVote(strategy, firedDirection, score))
            }

            strategyTraces.add(
                StrategyTraceEntry(
                    strategyId = strategy.id,
                    regimeEligible = true,
                    componentDetails = results.map { it.detail },
                    componentDirections = dirs,
                    fired = finalFired,
                    firedDirection = if (finalFired) firedDirection else null,
                    score = score
                )
            )
        }

        val resolution = ConflictResolver.resolve(votes)

        return when (resolution) {
            is ConflictResolution.NoSignal -> {
                val trace = DebugTraceEntry(
                    candleId, timestampMillis, checkpoint, referencePrice, movePct,
                    regime, strategyTraces, resolution.reason, "NO_SIGNAL: ${resolution.reason}", false
                )
                EngineResult(null, trace)
            }
            is ConflictResolution.Decision -> {
                val winner = resolution.winner
                val ai = consultAi(aiConfig, ctx, regime, winner.direction, winner.score)

                if (!ai.allowed) {
                    val trace = DebugTraceEntry(
                        candleId, timestampMillis, checkpoint, referencePrice, movePct,
                        regime, strategyTraces, "Decision: ${winner.strategy.id} -> ${winner.direction} (blocked by AI Assist)",
                        "NO_SIGNAL: ${ai.reason}", false
                    )
                    return EngineResult(null, trace)
                }

                val finalDirection = ai.finalDirection ?: winner.direction
                val signal = Signal(
                    signalId = UUID.randomUUID().toString(),
                    candleId = candleId,
                    candleOpenTimeMillis = candleOpenTimeMillis,
                    signalTimestampMillis = timestampMillis,
                    candleOpen = candleOpen,
                    signalPrice = referencePrice,
                    direction = finalDirection,
                    activeStrategyId = winner.strategy.id,
                    activeStrategyName = if (ai.overridden)
                        "${winner.strategy.id} (${winner.strategy.marketConditionBucket}) \u2022 AI-overridden"
                    else "${winner.strategy.id} (${winner.strategy.marketConditionBucket})",
                    marketRegime = regime,
                    strategyScore = winner.score,
                    confidencePct = winner.strategy.performance.oosWinRatePct,
                    entryMovePct = movePct,
                    checkpoint = checkpoint,
                    aiConsulted = ai.consulted,
                    aiConfidence = ai.confidence,
                    aiAgreed = ai.agreed,
                    aiOverridden = ai.overridden
                )
                val aiNote = if (ai.consulted) " [AI: ${"%.2f".format(ai.confidence)} conf, agreed=${ai.agreed}${if (ai.overridden) ", OVERRODE" else ""}]" else ""
                val trace = DebugTraceEntry(
                    candleId, timestampMillis, checkpoint, referencePrice, movePct,
                    regime, strategyTraces, "Decision: ${winner.strategy.id} -> ${winner.direction}",
                    "SIGNAL_GENERATED: ${winner.strategy.id} -> $finalDirection (score=${"%.4f".format(winner.score)})$aiNote",
                    true
                )
                EngineResult(signal, trace)
            }
        }
    }

    private data class AiOutcome(
        val allowed: Boolean,
        val reason: String,
        val consulted: Boolean,
        val confidence: Double?,
        val agreed: Boolean?,
        val overridden: Boolean,
        val finalDirection: Direction?
    )

    /** Applies AiMode semantics (see ai/AiTypes.kt) to the rule engine's winning direction.
     *  Abstains (treated as "allowed, not consulted") whenever the model isn't loaded, the
     *  feature vector can't be built yet (indicator warmup), or inference fails for any
     *  reason -- AI Assist degrading never blocks a signal the rule engine alone approved. */
    private fun consultAi(
        aiConfig: AiAssistConfig?,
        ctx: EvalContext,
        regime: MarketRegimeState,
        candidateDirection: Direction,
        candidateScore: Double
    ): AiOutcome {
        val abstain = AiOutcome(true, "", consulted = false, confidence = null, agreed = null, overridden = false, finalDirection = null)
        if (aiConfig == null || !aiConfig.engine.isLoaded) return abstain

        val features = AiFeatureBuilder.build(ctx, regime, candidateDirection, candidateScore) ?: return abstain
        val prediction = aiConfig.engine.predict(features) ?: return abstain

        val agreed = prediction.direction == candidateDirection
        val agreesConfidently = agreed && prediction.confidence >= aiConfig.confidenceThreshold

        return when (aiConfig.mode) {
            AiMode.ADVISORY -> AiOutcome(
                true, "", consulted = true, confidence = prediction.confidence.toDouble(),
                agreed = agreed, overridden = false, finalDirection = null
            )
            AiMode.CONFIRM -> if (agreesConfidently) {
                AiOutcome(true, "", true, prediction.confidence.toDouble(), agreed, false, null)
            } else {
                AiOutcome(
                    false, "AI Assist did not confirm (agreed=$agreed, confidence=${"%.2f".format(prediction.confidence)}, " +
                        "threshold=${aiConfig.confidenceThreshold})", true, prediction.confidence.toDouble(), agreed, false, null
                )
            }
            AiMode.OVERRIDE -> when {
                agreesConfidently -> AiOutcome(true, "", true, prediction.confidence.toDouble(), agreed, false, null)
                !agreed && prediction.confidence >= aiConfig.overrideThreshold -> AiOutcome(
                    true, "", true, prediction.confidence.toDouble(), agreed, true, prediction.direction
                )
                else -> AiOutcome(
                    false, "AI Assist did not confirm and confidence too low to override " +
                        "(agreed=$agreed, confidence=${"%.2f".format(prediction.confidence)})",
                    true, prediction.confidence.toDouble(), agreed, false, null
                )
            }
        }
    }

    /** Result determination per spec section 28: compare final close to candle open; tie counts as Red. */
    fun evaluateResult(database: StrategyDatabase, signal: Signal, finalClose: Double): Pair<SignalStatus, Double> {
        val actualDirection = when {
            finalClose > signal.candleOpen -> Direction.GREEN
            finalClose < signal.candleOpen -> Direction.RED
            else -> if (database.hardConstraints.outcomeTieCountsAsRed) Direction.RED else Direction.RED
        }
        val won = actualDirection == signal.direction
        val pnl = if (won) database.financialModel.winUsd else database.financialModel.lossUsd
        val status = if (won) SignalStatus.WON else SignalStatus.LOST
        return status to pnl
    }
}
