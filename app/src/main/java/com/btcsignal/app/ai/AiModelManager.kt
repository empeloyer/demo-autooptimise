package com.btcsignal.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AiModelInfo(val file: File, val sizeBytes: Long, val downloadedAtMillis: Long)

sealed class AiDownloadProgress {
    data class Downloading(val bytesRead: Long, val totalBytes: Long, val percent: Int) : AiDownloadProgress()
    data class Done(val info: AiModelInfo) : AiDownloadProgress()
    data class Failed(val message: String) : AiDownloadProgress()
}

/**
 * Manages the optional, person-supplied ONNX model file: where it lives on disk, whether
 * legacy storage permission is needed to put it there, and downloading it from a URL the
 * person enters on the AI Engine settings page.
 *
 * Storage location: the model is written to `context.getExternalFilesDir(null)/ai_models/`
 * -- app-scoped external storage. On Android 10+ (API 29, scoped storage) writing here
 * needs NO runtime permission at all; `WRITE_EXTERNAL_STORAGE` is only requested/needed on
 * API 28 and below (declared `maxSdkVersion="28"` in the manifest for exactly that reason).
 * [hasStoragePermission] reflects this honestly rather than always asking, but MainActivity
 * still triggers the OS permission prompt once on first run on old OS versions per the
 * person's request, and [ensureAccessible] re-checks defensively before every download.
 */
object AiModelManager {

    private const val MODEL_DIR = "ai_models"
    private const val MODEL_FILE_NAME = "strategy_ai_model.onnx"
    private const val PART_SUFFIX = ".part"

    fun needsRuntimeStoragePermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P // <= 28

    fun hasStoragePermission(context: Context): Boolean {
        if (!needsRuntimeStoragePermission()) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, MODEL_DIR).apply { mkdirs() }

    fun modelFile(context: Context): File = File(modelDir(context), MODEL_FILE_NAME)

    fun currentModelInfo(context: Context): AiModelInfo? {
        val f = modelFile(context)
        if (!f.exists() || f.length() == 0L) return null
        return AiModelInfo(f, f.length(), f.lastModified())
    }

    fun deleteModel(context: Context) {
        modelFile(context).delete()
        File(modelDir(context), MODEL_FILE_NAME + PART_SUFFIX).delete()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // model files can be tens of MB on a slow connection
        .build()

    /**
     * Streams [url] to disk with progress callbacks. Downloads to a `.part` file first and
     * only renames to the final name on success, so a crashed/cancelled download can never
     * be mistaken for a complete, loadable model.
     */
    suspend fun download(context: Context, url: String, onProgress: (AiDownloadProgress) -> Unit) {
        if (!hasStoragePermission(context)) {
            onProgress(AiDownloadProgress.Failed("Storage permission not granted"))
            return
        }
        if (url.isBlank()) {
            onProgress(AiDownloadProgress.Failed("Enter a model URL first"))
            return
        }

        val target = modelFile(context)
        val partFile = File(target.parentFile, target.name + PART_SUFFIX)

        // withContext(IO): OkHttp's .execute() and all the stream/file writes below are
        // blocking calls. This suspend function must not block whatever dispatcher its
        // caller happens to be on (Compose's rememberCoroutineScope() defaults to Main).
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onProgress(AiDownloadProgress.Failed("Download failed: HTTP ${response.code}"))
                        return@withContext
                    }
                    val body = response.body ?: run {
                        onProgress(AiDownloadProgress.Failed("Empty response body"))
                        return@withContext
                    }
                    val totalBytes = body.contentLength()
                    var bytesRead = 0L

                    body.byteStream().use { input ->
                        partFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                bytesRead += read
                                val pct = if (totalBytes > 0) ((bytesRead.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 99) else 0
                                onProgress(AiDownloadProgress.Downloading(bytesRead, totalBytes, pct))
                            }
                        }
                    }

                    if (!partFile.renameTo(target)) {
                        onProgress(AiDownloadProgress.Failed("Could not finalize downloaded file"))
                        return@withContext
                    }
                    onProgress(AiDownloadProgress.Done(AiModelInfo(target, target.length(), target.lastModified())))
                }
            } catch (e: IOException) {
                partFile.delete()
                onProgress(AiDownloadProgress.Failed(e.message ?: "Network error during download"))
            } catch (e: Exception) {
                partFile.delete()
                onProgress(AiDownloadProgress.Failed(e.message ?: "Unexpected error during download"))
            }
        }
    }
}
