package com.btcsignal.app.ai

/**
 * AI Engine operating mode (Settings -> AI Engine page). All three modes only ever act on
 * the ConflictResolver's already-chosen winning signal for a checkpoint -- AI Assist never
 * invents a signal the rule-based strategies didn't already produce; it can only leave it
 * alone, silence it, or (OVERRIDE only) flip its direction.
 *
 *  - ADVISORY: AI's prediction + confidence is computed and recorded in the Debug Trace for
 *    every checkpoint, purely for the person to review (History/Debug). Never changes what
 *    fires. Recommended starting mode so you can judge the model's real agreement rate
 *    before it affects anything live.
 *  - CONFIRM (default once AI Assist is turned on): the rule engine's winning signal only
 *    actually fires if the AI agrees with its direction at >= confidenceThreshold. Disagreement
 *    or low confidence silently drops the signal for that candle (same effect as a strategy
 *    being blocked, but decided per-signal instead of per-strategy).
 *  - OVERRIDE: everything CONFIRM does, plus: if the AI disagrees with the rule engine's
 *    direction at >= overrideThreshold confidence, the signal fires in the AI's direction
 *    instead of the rule engine's. This is the only mode that can produce the "rules said
 *    green, final signal is red" behavior. Off by default and flagged as experimental in the
 *    UI, since it depends entirely on the quality of the person's own downloaded model —
 *    validate it via the Backtest tab (which respects the same AI settings) before trusting
 *    it live.
 */
enum class AiMode { ADVISORY, CONFIRM, OVERRIDE }

/** Bundles everything CoreSignalEngine needs to consult AI Assist for one checkpoint.
 *  Passing null for this parameter (the default) fully disables AI Assist, for backward
 *  compatibility with existing callers/tests that don't set it up. */
data class AiAssistConfig(
    val engine: AiPredictor,
    val mode: AiMode,
    val confidenceThreshold: Double,
    val overrideThreshold: Double
)
