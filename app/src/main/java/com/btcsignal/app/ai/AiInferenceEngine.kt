package com.btcsignal.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.btcsignal.app.data.model.Direction
import java.io.File
import java.nio.FloatBuffer

data class AiPrediction(
    val probGreen: Float,
    val probRed: Float,
    val direction: Direction,
    val confidence: Float   // = max(probGreen, probRed)
)

/**
 * Loads and runs the user-downloaded ONNX model on-device (ai.onnxruntime, CPU execution
 * provider -- no network call at inference time; the model only ever touches local files).
 * Never throws out of [predict]/[loadFromFile]: a malformed or incompatible model degrades
 * to "AI unavailable" (null / Result.failure) rather than crashing the signal engine, since
 * CoreSignalEngine must keep producing rule-based signals with or without AI Assist.
 *
 * One instance is shared app-wide via [com.btcsignal.app.AppContainer.aiInferenceEngine] so
 * the model is loaded into memory once, not once per checkpoint.
 */
/**
 * Minimal predictor surface CoreSignalEngine depends on. [AiInferenceEngine] is the real,
 * ONNX-backed implementation; tests use a fake implementation instead, since the real one
 * needs the native onnxruntime .so libraries that aren't present in a plain JVM unit test.
 */
interface AiPredictor {
    val isLoaded: Boolean
    fun predict(features: FloatArray): AiPrediction?
}

class AiInferenceEngine : AiPredictor {

    @Volatile private var session: OrtSession? = null
    @Volatile private var environment: OrtEnvironment? = null
    @Volatile var loadedModelPath: String? = null
        private set

    override val isLoaded: Boolean get() = session != null

    /** Loads [file] and validates its input/output tensor shapes against
     *  [AiFeatureBuilder.FEATURE_NAMES]/[AiFeatureBuilder.OUTPUT_NAMES] before accepting it.
     *  Replaces any previously-loaded model. */
    fun loadFromFile(file: File): Result<Unit> = runCatching {
        close()
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        val newSession = env.createSession(file.absolutePath, opts)

        val inputInfo = newSession.inputInfo.values.firstOrNull()
            ?: throw IllegalStateException("Model declares no input tensor")
        val inputShape = (inputInfo.info as? ai.onnxruntime.TensorInfo)?.shape
            ?: throw IllegalStateException("Model input is not a tensor")
        // Accept either [N] or [1, N] -- both are common single-sample export shapes.
        val declaredFeatureCount = inputShape.lastOrNull()?.toInt() ?: -1
        if (declaredFeatureCount != AiFeatureBuilder.FEATURE_NAMES.size) {
            newSession.close()
            throw IllegalStateException(
                "Model expects $declaredFeatureCount input features, this app's contract is " +
                    "${AiFeatureBuilder.FEATURE_NAMES.size} (AiFeatureBuilder.FEATURE_VERSION=" +
                    "${AiFeatureBuilder.FEATURE_VERSION}). Model rejected -- see AI Engine settings " +
                    "for the exact feature list."
            )
        }

        environment = env
        session = newSession
        loadedModelPath = file.absolutePath
    }

    /** Runs a single all-zero inference to confirm the loaded model actually executes
     *  end-to-end (catches output-shape problems loadFromFile's input-only check can't). */
    fun validate(): Result<Unit> = runCatching {
        val zeros = FloatArray(AiFeatureBuilder.FEATURE_NAMES.size)
        predict(zeros) ?: throw IllegalStateException("Validation inference produced no output")
        Unit
    }

    /** Returns null if no model is loaded, or if the model's output couldn't be parsed as
     *  the expected 2-value [prob_green, prob_red] pair -- never throws. */
    override fun predict(features: FloatArray): AiPrediction? {
        val env = environment ?: return null
        val ses = session ?: return null
        if (features.size != AiFeatureBuilder.FEATURE_NAMES.size) return null

        return try {
            val inputName = ses.inputNames.iterator().next()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, features.size.toLong())).use { tensor ->
                ses.run(mapOf(inputName to tensor)).use { result ->
                    // Iterator access (Map.Entry<String, OnnxValue>) rather than any
                    // index-based getter, since that's the one access pattern OrtSession.Result
                    // is guaranteed to support across onnxruntime versions.
                    val firstOutput = result.iterator().asSequence().firstOrNull()?.value ?: return null
                    val raw = firstOutput.value
                    val flat: FloatArray = when (raw) {
                        is Array<*> -> (raw.firstOrNull() as? FloatArray) ?: return null
                        is FloatArray -> raw
                        else -> return null
                    }
                    if (flat.size < 2) return null
                    val probGreen = flat[0]
                    val probRed = flat[1]
                    val direction = if (probGreen >= probRed) Direction.GREEN else Direction.RED
                    AiPrediction(probGreen, probRed, direction, maxOf(probGreen, probRed))
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        session?.close()
        session = null
        environment = null
        loadedModelPath = null
    }
}
