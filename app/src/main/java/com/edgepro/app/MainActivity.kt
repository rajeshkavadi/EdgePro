package com.edgepro.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.edgepro.app.databinding.ActivityMainBinding
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera -> detector -> overlay wiring, plus an on-screen fps / inference HUD.
 *
 * The HUD is deliberate: "good latency / low power" claims are unfalsifiable
 * without measured numbers on the target device. Read the HUD on your Pixel 9a,
 * then decide whether the model / resolution needs to change.
 */
class MainActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var helper: ObjectDetectorHelper
    private lateinit var analysisExecutor: ExecutorService

    // Rolling fps over the delivered-frame timestamps.
    private var lastFrameTs = 0L
    private var emaFps = 0f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()
        helper = ObjectDetectorHelper(context = this, listener = this)

        analysisExecutor.execute { helper.setup() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // Cap analysis resolution: lower input = fewer FLOPs = higher fps / less heat.
            // 640x480 is the sweet spot for EfficientDet-Lite0 (320px model input).
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(analysisExecutor) { proxy ->
                        trackFps()
                        helper.detect(proxy)
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun trackFps() {
        val now = SystemClock.uptimeMillis()
        if (lastFrameTs != 0L) {
            val dt = (now - lastFrameTs).coerceAtLeast(1L)
            val inst = 1000f / dt
            emaFps = if (emaFps == 0f) inst else emaFps * 0.9f + inst * 0.1f
        }
        lastFrameTs = now
    }

    // --- DetectorListener ---

    override fun onResults(
        result: ObjectDetectorResult,
        inferenceTimeMs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        runOnUiThread {
            binding.overlay.setResults(result, inputWidth, inputHeight)
            binding.hud.text = getString(
                R.string.hud_fmt, emaFps, inferenceTimeMs, result.detections().size
            )
        }
    }

    override fun onError(error: String) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        helper.clear()
    }
}
