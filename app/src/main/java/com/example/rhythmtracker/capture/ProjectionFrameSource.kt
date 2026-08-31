package com.example.rhythmtracker.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns MediaProjection surfaces and nothing else. OCR, result detection and state do not live here.
 */
class ProjectionFrameSource(
    private val handler: Handler
) {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var probeReader: ImageReader? = null
    private var nativeReader: ImageReader? = null

    private var sourceWidth = 0
    private var sourceHeight = 0
    private var densityDpi = 0
    private var probeWidth = 0
    private var probeHeight = 0
    private var nativeCaptureActive = false
    private var nativeFramesSeen = 0
    private var nativeCallback: ((Result<Bitmap>) -> Unit)? = null

    private val timeoutRunnable = Runnable {
        if (!nativeCaptureActive) return@Runnable
        val callback = nativeCallback
        restoreProbe()
        callback?.invoke(Result.failure(IllegalStateException("Native capture timed out")))
    }

    fun start(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int
    ) {
        close()
        projection = mediaProjection
        sourceWidth = width
        sourceHeight = height
        densityDpi = dpi
        createProbeReader()

        val reader = requireNotNull(probeReader)
        display = mediaProjection.createVirtualDisplay(
            "RenderMyMindProbeV2",
            probeWidth,
            probeHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        ) ?: error("createVirtualDisplay() returned null")
    }

    fun acquireLatestProbe(): Bitmap? {
        if (nativeCaptureActive) return null
        val image = runCatching { probeReader?.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            imageToBitmap(image)
        } finally {
            image.close()
        }
    }

    fun captureNative(
        timeoutMs: Long = 1_600L,
        callback: (Result<Bitmap>) -> Unit
    ): Boolean {
        if (nativeCaptureActive || sourceWidth <= 0 || sourceHeight <= 0) return false
        val currentDisplay = display ?: return false

        nativeCaptureActive = true
        nativeFramesSeen = 0
        nativeCallback = callback
        handler.removeCallbacks(timeoutRunnable)

        runCatching { currentDisplay.setSurface(null) }
        nativeReader?.close()
        nativeReader = ImageReader.newInstance(
            sourceWidth,
            sourceHeight,
            PixelFormat.RGBA_8888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ onNativeImage(reader) }, handler)
        }

        currentDisplay.resize(sourceWidth, sourceHeight, densityDpi)
        currentDisplay.setSurface(requireNotNull(nativeReader).surface)
        handler.postDelayed(timeoutRunnable, timeoutMs)
        return true
    }

    fun updateSourceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        sourceWidth = width
        sourceHeight = height
        if (!nativeCaptureActive) rebuildProbeSurface()
    }

    fun probeSizeLabel(): String = "${probeWidth}x${probeHeight} low-res probe"

    fun nativeSizeLabel(): String = "${sourceWidth}x${sourceHeight} native capture"

    fun isNativeCaptureActive(): Boolean = nativeCaptureActive

    private fun onNativeImage(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        if (!nativeCaptureActive) {
            image.close()
            return
        }

        nativeFramesSeen += 1
        if (nativeFramesSeen < NATIVE_FRAME_TARGET) {
            image.close()
            return
        }

        val bitmap = try {
            imageToBitmap(image)
        } finally {
            image.close()
        }
        val callback = nativeCallback
        restoreProbe()
        callback?.invoke(Result.success(bitmap))
    }

    private fun restoreProbe() {
        handler.removeCallbacks(timeoutRunnable)
        val currentDisplay = display
        runCatching { currentDisplay?.setSurface(null) }

        nativeReader?.setOnImageAvailableListener(null, null)
        nativeReader?.close()
        nativeReader = null
        nativeCaptureActive = false
        nativeFramesSeen = 0
        nativeCallback = null

        val reader = probeReader
        if (currentDisplay != null && reader != null) {
            runCatching { reader.acquireLatestImage()?.close() }
            currentDisplay.resize(probeWidth, probeHeight, densityDpi)
            currentDisplay.setSurface(reader.surface)
        }
    }

    private fun rebuildProbeSurface() {
        val currentDisplay = display ?: return
        runCatching { currentDisplay.setSurface(null) }
        probeReader?.close()
        createProbeReader()
        currentDisplay.resize(probeWidth, probeHeight, densityDpi)
        currentDisplay.setSurface(requireNotNull(probeReader).surface)
    }

    private fun createProbeReader() {
        val (width, height) = probeDimensions(sourceWidth, sourceHeight)
        probeWidth = width
        probeHeight = height
        probeReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )
    }

    private fun probeDimensions(width: Int, height: Int): Pair<Int, Int> {
        val longest = max(width, height).coerceAtLeast(1)
        val scale = (PROBE_LONG_EDGE_PX.toFloat() / longest.toFloat()).coerceAtMost(1f)
        return (width * scale).roundToInt().coerceAtLeast(2) to
            (height * scale).roundToInt().coerceAtLeast(2)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    fun close() {
        handler.removeCallbacks(timeoutRunnable)
        runCatching { display?.setSurface(null) }
        runCatching { display?.release() }
        display = null
        probeReader?.close()
        probeReader = null
        nativeReader?.close()
        nativeReader = null
        projection = null
        nativeCaptureActive = false
        nativeFramesSeen = 0
        nativeCallback = null
    }

    companion object {
        private const val PROBE_LONG_EDGE_PX = 480
        private const val NATIVE_FRAME_TARGET = 2
    }
}
