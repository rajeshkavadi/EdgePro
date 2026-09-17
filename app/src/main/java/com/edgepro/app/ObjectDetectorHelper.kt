package com.edgepro.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * Wraps the MediaPipe Object Detector in LIVE_STREAM mode.
 *
 * Design notes / where latency actually lives:
 *  - Inference runs async on MediaPipe's own thread; results arrive via the listener.
 *  - The costly per-frame work here is ImageProxy -> Bitmap copy + rotation, NOT the
 *    model. Keep it off the main thread (call [detect] from the CameraX analysis executor).
 *  - Delegate falls back CPU if GPU init fails; there is no portable NPU delegate on
 *    Android through this API, so GPU is the ceiling on most devices.
 */
class ObjectDetectorHelper(
    private val context: Context,
    private var threshold: Float = 0.5f,
    private var maxResults: Int = 5,
    private var delegate: Delegate = Delegate.GPU,
    private val modelAsset: String = "efficientdet-lite0.tflite",
    private val listener: DetectorListener,
) {
    private var detector: ObjectDetector? = null
    private var reusableBitmap: Bitmap? = null

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            result: ObjectDetectorResult,
            inferenceTimeMs: Long,
            inputWidth: Int,
            inputHeight: Int,
        )
    }

    fun setup() {
        clear()
        try {
            val base = BaseOptions.builder()
                .setModelAssetPath(modelAsset)
                .setDelegate(delegate)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, input ->
                    val inferenceMs =
                        SystemClock.uptimeMillis() - result.timestampMs()
                    listener.onResults(
                        result, inferenceMs, input.width, input.height
                    )
                }
                .setErrorListener { e -> listener.onError(e.message ?: "detector error") }
                .build()

            detector = ObjectDetector.createFromOptions(context, options)
        } catch (e: Exception) {
            // GPU delegate can fail on some drivers; retry once on CPU before giving up.
            if (delegate == Delegate.GPU) {
                delegate = Delegate.CPU
                setup()
            } else {
                listener.onError("Detector init failed: ${e.message}")
            }
        }
    }

    /** Convert a camera frame and enqueue it for async detection. */
    fun detect(imageProxy: ImageProxy) {
        val det = detector ?: run { imageProxy.close(); return }
        val frameTime = SystemClock.uptimeMillis()

        val bmp = ensureBitmap(imageProxy.width, imageProxy.height)
        imageProxy.use { it.toBitmap(bmp) }

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height, matrix, true
        )

        val mpImage: MPImage = BitmapImageBuilder(rotated).build()
        det.detectAsync(mpImage, frameTime)
    }

    private fun ensureBitmap(w: Int, h: Int): Bitmap {
        val b = reusableBitmap
        if (b == null || b.width != w || b.height != h) {
            reusableBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        return reusableBitmap!!
    }

    fun clear() {
        detector?.close()
        detector = null
    }
}

/** Copy an ImageProxy's pixels into a pre-allocated bitmap. */
private fun ImageProxy.toBitmap(dest: Bitmap) {
    dest.copyPixelsFromBuffer(planes[0].buffer.also { it.rewind() })
}
