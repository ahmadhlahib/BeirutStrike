package com.example.beirutrun

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Opens the front camera with a round face guide. ML Kit finds the face in the photo, and a circle
 * centred on it is saved as the player's face, which is then drawn as the character's head.
 * Returns RESULT_OK when a face was saved.
 */
class FaceCaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var guide: FaceGuideView
    private lateinit var captureButton: MaterialButton
    private lateinit var controller: LifecycleCameraController
    private val worker = Executors.newSingleThreadExecutor()
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, R.string.face_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_capture)
        previewView = findViewById(R.id.previewView)
        guide = findViewById(R.id.faceGuide)
        captureButton = findViewById(R.id.captureButton)

        captureButton.setOnClickListener { capture() }
        findViewById<MaterialButton>(R.id.skipButton).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        controller = LifecycleCameraController(this).apply {
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        controller.bindToLifecycle(this)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.controller = controller
    }

    private fun capture() {
        if (!::controller.isInitialized) return
        captureButton.isEnabled = false
        controller.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // Runs on the worker thread, so waiting for the detector here is fine.
                val result = runCatching { image.use { cropFace(it) } }
                val face = result.getOrNull()
                val saved = face != null && runCatching {
                    Session.faceFile(this@FaceCaptureActivity).outputStream().use {
                        face.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }.isSuccess
                runOnUiThread {
                    if (saved) {
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        captureButton.isEnabled = true
                        val message = if (result.isSuccess && face == null) R.string.face_not_found else R.string.face_failed
                        Toast.makeText(this@FaceCaptureActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    captureButton.isEnabled = true
                    Toast.makeText(this@FaceCaptureActivity, R.string.face_failed, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /**
     * The controller gives the capture the same viewport as the preview, so [ImageProxy.getCropRect]
     * is exactly what was on screen. Crop to it, turn it upright and mirror it like the preview,
     * then let ML Kit find the face and cut a circle centred on it.
     * Returns null when no face is found.
     */
    private fun cropFace(image: ImageProxy): Bitmap? {
        val full = image.toBitmap()
        val crop = Rect(image.cropRect).apply { intersect(0, 0, full.width, full.height) }
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f)
        }
        val visible = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height(), matrix, true)

        val faces = Tasks.await(detector.process(InputImage.fromBitmap(visible, 0)))
        val box = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
            ?: return null

        // ML Kit's box runs from the eyebrows to the chin; widen it to include hair and ears,
        // and nudge the centre up a little so the forehead is not cut off.
        val side = (max(box.width(), box.height()) * 1.6f).roundToInt()
            .coerceAtMost(min(visible.width, visible.height))
        val centerX = box.exactCenterX()
        val centerY = box.exactCenterY() - box.height() * 0.08f
        val left = (centerX - side / 2f).roundToInt().coerceIn(0, visible.width - side)
        val top = (centerY - side / 2f).roundToInt().coerceIn(0, visible.height - side)
        val square = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(visible, left, top, side, side), FACE_SIZE, FACE_SIZE, true
        )

        val round = Bitmap.createBitmap(FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(round)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(FACE_SIZE / 2f, FACE_SIZE / 2f, FACE_SIZE / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(square, 0f, 0f, paint)
        return round
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.execute { detector.close() }
        worker.shutdown()
    }

    companion object {
        private const val FACE_SIZE = 384
    }
}

/** Darkens everything except a round hole where the player should put their face. */
class FaceGuideView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFB300.toInt()
        strokeWidth = 4f * resources.displayMetrics.density
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** The guide circle's bounds in this view's (and the preview's) coordinates. */
    fun circleRect(): RectF {
        val r = min(width, height) * 0.36f
        val cx = width / 2f
        val cy = height * 0.45f
        return RectF(cx - r, cy - r, cx + r, cy + r)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = circleRect()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawOval(c, clear)
        canvas.drawOval(c, ring)
    }
}
