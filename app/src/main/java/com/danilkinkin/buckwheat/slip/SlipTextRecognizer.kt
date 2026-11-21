package com.danilkinkin.buckwheat.slip

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.danilkinkin.buckwheat.slip.ocr.OcrImage
import com.danilkinkin.buckwheat.slip.ocr.SlipOcrEngine

/**
 * Reads text from slip images fully on device (Thai + English), see [SlipOcrEngine].
 * Not thread safe: use one instance from one coroutine at a time, and [close] it when done.
 */
class SlipTextRecognizer(private val context: Context) : AutoCloseable {
    private val engine: SlipOcrEngine by lazy {
        engineCreated = true
        val assets = context.assets
        SlipOcrEngine(
            detModel = assets.open("ocr/det.onnx").use { it.readBytes() },
            recModel = assets.open("ocr/rec.onnx").use { it.readBytes() },
            charset = assets.open("ocr/rec_charset.txt").bufferedReader(Charsets.UTF_8).use { reader ->
                // Line 1 is the CTC blank (empty); a space is a class of its own, so don't trim
                reader.readText().removeSuffix("\n").split('\n')
            },
        )
    }
    private var engineCreated = false

    fun recognize(uri: Uri): String = engine.recognizeText(load(uri))

    override fun close() {
        if (engineCreated) engine.close()
    }

    private fun load(uri: Uri): OcrImage {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // getPixels needs a software bitmap
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longSide
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return OcrImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val MAX_SIDE = 2400
    }
}
