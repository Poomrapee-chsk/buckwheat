package com.danilkinkin.buckwheat.slip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Reads text from slip images fully on device with Tesseract (Thai + English).
 * Not thread safe: use one instance from one coroutine at a time, and [close] it when done.
 */
class SlipTextRecognizer(private val context: Context) : AutoCloseable {
    private var apiCreated = false

    private val api: TessBaseAPI by lazy {
        val dataPath = prepareTessData()
        apiCreated = true
        TessBaseAPI().apply {
            if (!init(dataPath.absolutePath, LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) {
                recycle()
                throw IllegalStateException("Failed to init Tesseract")
            }
            // Keeps "label      value" on one line, which the parser relies on
            pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        }
    }

    fun recognize(uri: Uri): String {
        val bitmap = binarize(loadBitmap(uri))
        try {
            api.setImage(bitmap)
            return api.utF8Text.orEmpty()
        } finally {
            api.clear()
            bitmap.recycle()
        }
    }

    override fun close() {
        if (apiCreated) api.recycle()
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Tesseract needs a software bitmap
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longSide
                decoder.setTargetSize(
                    (info.size.width * scale).toInt(),
                    (info.size.height * scale).toInt(),
                )
            }
        }
    }

    private fun binarize(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        source.recycle()

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(SlipBinarizer.binarize(pixels, width, height), 0, width, 0, 0, width, height)
        return result
    }

    /** Copies bundled language data out of assets once, Tesseract can only read real files. */
    private fun prepareTessData(): File {
        val root = File(context.filesDir, "ocr")
        val tessData = File(root, "tessdata").apply { mkdirs() }

        LANGUAGES.split("+").forEach { language ->
            val fileName = "$language.traineddata"
            val target = File(tessData, fileName)
            val assetPath = "tessdata/$fileName"
            val assetSize = context.assets.openFd(assetPath).use { it.length }

            if (!target.exists() || target.length() != assetSize) {
                val temp = File(tessData, "$fileName.tmp")
                context.assets.open(assetPath).use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                }
                temp.renameTo(target)
            }
        }

        return root
    }

    companion object {
        private const val LANGUAGES = "tha+eng"
        private const val MAX_SIDE = 2400
    }
}
