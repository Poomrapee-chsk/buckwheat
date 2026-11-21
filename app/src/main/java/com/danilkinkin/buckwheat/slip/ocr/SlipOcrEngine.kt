package com.danilkinkin.buckwheat.slip.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Image as ARGB pixels, row by row (what Bitmap.getPixels returns) */
class OcrImage(val width: Int, val height: Int, val argb: IntArray)

/** A recognized piece of text and where it is, in image pixels */
data class OcrLine(
    val text: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * On-device OCR for payment slips, plain Kotlin on top of ONNX Runtime:
 *
 * 1. Text detection: PaddleOCR PP-OCRv6 tiny DB detector finds text regions. Chosen over PP-OCRv5
 *    mobile with tools/slipbench on 50 real K PLUS slips: dates found 97.5% vs 85%, 1.5x faster.
 * 2. Recognition: AksonOCR-Lite Thai + English recognizer reads each region. It predicts whole Thai
 *    grapheme clusters (consonant with its vowels and tone marks), so marks can't come out scrambled.
 * 3. Layout: regions are joined into text rows for [com.danilkinkin.buckwheat.slip.SlipParser].
 *
 * Steps mirror the AksonOCR-Lite reference pipeline. Text regions are axis-aligned boxes, which is
 * enough for screenshots. Not thread safe; [close] when done.
 */
class SlipOcrEngine(
    detModel: ByteArray,
    recModel: ByteArray,
    /** Recognizer classes, index 0 is the CTC blank */
    private val charset: List<String>,
    /**
     * Turn each line crop into dark text on a flat white background before recognition, see
     * [LineFlattener]. Needed by recognizers trained that way (iApp OpenThai OCR-Text); AksonOCR-Lite
     * reads colour crops as they are.
     */
    private val flattenLines: Boolean = false,
    threads: Int = 2,
) : AutoCloseable {
    /** How long the last [recognize] call spent finding text and reading it, in milliseconds */
    var lastDetectMs = 0L
        private set
    var lastRecognizeMs = 0L
        private set

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
    }
    private val det = env.createSession(detModel, options)
    private val rec = env.createSession(recModel, options)

    fun recognize(image: OcrImage): List<OcrLine> {
        val start = System.nanoTime()
        val boxes = detect(image)
        val detected = System.nanoTime()
        val lines = boxes.mapNotNull { box -> read(image, box) }
        lastDetectMs = (detected - start) / 1_000_000
        lastRecognizeMs = (System.nanoTime() - detected) / 1_000_000
        return lines
    }

    /** Text as rows, the format [com.danilkinkin.buckwheat.slip.SlipParser] reads */
    fun recognizeText(image: OcrImage): String = SlipLayout.toText(recognize(image), image.width)

    // ---- detection ----

    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun detect(image: OcrImage): List<Box> {
        val scale = minOf(1.0, DET_MAX_SIDE.toDouble() / maxOf(image.width, image.height))
        val inW = maxOf(32, (image.width * scale / 32).roundToInt() * 32)
        val inH = maxOf(32, (image.height * scale / 32).roundToInt() * 32)

        val input = ImageOps.toTensor(image, 0, 0, image.width, image.height, inW, inH) { channel, value ->
            (value / 255f - DET_MEAN[channel]) / DET_STD[channel]
        }
        val prob = run(det, input, longArrayOf(1, 3, inH.toLong(), inW.toLong()))

        val sx = image.width.toDouble() / inW
        val sy = image.height.toDouble() / inH

        return components(prob, inW, inH).map { c ->
            // DB "unclip": grow the shrunk text kernel back, by area * ratio / perimeter
            val w = c.right - c.left
            val h = c.bottom - c.top
            val d = w.toDouble() * h * UNCLIP_RATIO / (2.0 * (w + h))
            Box(
                left = ((c.left - d) * sx).toInt().coerceIn(0, image.width),
                top = ((c.top - d) * sy).toInt().coerceIn(0, image.height),
                right = ((c.right + d) * sx).toInt().coerceIn(0, image.width),
                bottom = ((c.bottom + d) * sy).toInt().coerceIn(0, image.height),
            )
        }
    }

    private class Component(var left: Int, var top: Int, var right: Int, var bottom: Int, var count: Int, var sum: Double)

    /** 4-connected regions of the probability map above [BINARY_THRESHOLD], scored by mean probability */
    private fun components(prob: FloatArray, width: Int, height: Int): List<Component> {
        val seen = BooleanArray(prob.size)
        val queue = IntArray(prob.size)
        val result = mutableListOf<Component>()

        for (start in prob.indices) {
            if (seen[start] || prob[start] <= BINARY_THRESHOLD) continue

            val c = Component(start % width, start / width, start % width + 1, start / width + 1, 0, 0.0)
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true

            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                c.count += 1
                c.sum += prob[i]
                if (x < c.left) c.left = x
                if (x + 1 > c.right) c.right = x + 1
                if (y < c.top) c.top = y
                if (y + 1 > c.bottom) c.bottom = y + 1

                fun visit(n: Int) {
                    if (!seen[n] && prob[n] > BINARY_THRESHOLD) {
                        seen[n] = true
                        queue[tail++] = n
                    }
                }
                if (x > 0) visit(i - 1)
                if (x < width - 1) visit(i + 1)
                if (y > 0) visit(i - width)
                if (y < height - 1) visit(i + width)
            }

            if (c.count >= MIN_COMPONENT_PIXELS && c.sum / c.count >= BOX_THRESHOLD) result += c
        }

        return result
    }

    // ---- recognition ----

    private fun read(image: OcrImage, box: Box): OcrLine? {
        val w = box.right - box.left
        val h = box.bottom - box.top
        if (w < 4 || h < 4) return null

        val inW = ceil(REC_HEIGHT.toDouble() * w / h).toInt().coerceIn(16, REC_MAX_WIDTH)
        val input = if (flattenLines) {
            val bgr = ImageOps.toTensor(image, box.left, box.top, w, h, inW, REC_HEIGHT) { _, value -> value }
            LineFlattener.flattenToTensor(bgr, inW, REC_HEIGHT)
        } else {
            ImageOps.toTensor(image, box.left, box.top, w, h, inW, REC_HEIGHT) { _, value ->
                (value / 255f - 0.5f) / 0.5f
            }
        }
        val output = run(rec, input, longArrayOf(1, 3, REC_HEIGHT.toLong(), inW.toLong()))

        // Greedy CTC: best class per time step, merge repeats, drop blanks
        val classes = charset.size
        val steps = output.size / classes
        val text = StringBuilder()
        var confidenceSum = 0f
        var kept = 0
        var previous = -1
        for (t in 0 until steps) {
            var best = 0
            var bestScore = output[t * classes]
            for (k in 1 until classes) {
                val score = output[t * classes + k]
                if (score > bestScore) {
                    best = k
                    bestScore = score
                }
            }
            if (best != previous && best != 0) {
                text.append(charset[best])
                confidenceSum += bestScore
                kept += 1
            }
            previous = best
        }

        val confidence = if (kept == 0) 0f else confidenceSum / kept
        if (text.isBlank() || confidence <= MIN_CONFIDENCE) return null

        return OcrLine(text.toString(), confidence, box.left, box.top, box.right, box.bottom)
    }

    private fun run(session: OrtSession, input: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val value = result.get(0) as OnnxTensor
                val buffer = value.floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    override fun close() {
        det.close()
        rec.close()
        options.close()
    }

    companion object {
        private const val DET_MAX_SIDE = 1600
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val BINARY_THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.6
        private const val UNCLIP_RATIO = 1.5
        private const val MIN_COMPONENT_PIXELS = 10

        private const val REC_HEIGHT = 48
        private const val REC_MAX_WIDTH = 3200
        private const val MIN_CONFIDENCE = 0.5f
    }
}
