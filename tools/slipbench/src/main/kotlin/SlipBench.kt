import com.danilkinkin.buckwheat.slip.SlipData
import com.danilkinkin.buckwheat.slip.SlipParser
import com.danilkinkin.buckwheat.slip.ocr.OcrImage
import com.danilkinkin.buckwheat.slip.ocr.SlipLayout
import com.danilkinkin.buckwheat.slip.ocr.SlipOcrEngine
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/*
 * Slip OCR benchmark. Runs the app's own slip OCR (SlipOcrEngine + SlipLayout) and parser (SlipParser)
 * on a folder of slip images with several text detector / recognizer setups, and compares them.
 *
 * Everything runs locally. Writes:
 *  - summary.txt   counts, rates and timings only, no text from the slips: safe to share
 *  - results.csv   what every setup read from every slip (private)
 *  - report.html   slips where the setups disagree, with the image and what each setup read (private)
 */

private const val USAGE = """
Usage: ./gradlew :slipbench:run --args="<slips folder or files...> [options]"

Options:
  --out DIR        where to write the results (default: slipbench-out)
  --truth FILE     CSV with the right answers: file,amount[,date][,payee]
                   date as yyyy-MM-dd HH:mm or yyyy-MM-dd; file is the image's file name
  --threads N      CPU threads per model (default 1, closest to a phone's single big core)
  --limit N        only the first N images
  --setups LIST    comma-separated setup ids to run (default: all), e.g. current,iapp
  --file-dates     use each image's file time as its save time, like the app uses the
                   phone's photo library date (helps when OCR loses the day).
                   Copy with `adb pull -a` so the files keep their times.
"""

/** iApp OpenThai2.1-OCR-Text, pinned to a release; files checked by SHA-256 */
private const val IAPP_REPO = "https://huggingface.co/iapp/OpenThai2.1-OCR-Text-20261118/resolve/v2.1.4"
private val IAPP_FILES = mapOf(
    "det.onnx" to "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
    "rec.onnx" to "82ec89533346faadcb30df96703dad2dd7521de65709e60d83fd9745b8968124",
    "rec_dict.txt" to "259ef2636cec0eeae225ae9fd9a63fa9305285650c22d7ec34fe8a82b24c2f1a",
)

/** Same downscaling as the app (SlipTextRecognizer) */
private const val MAX_SIDE = 2400

private class Setup(
    val id: String,
    val description: String,
    val det: File,
    val rec: File,
    val charset: List<String>,
    val flatten: Boolean,
)

private class Reading(
    val detectMs: Long,
    val recognizeMs: Long,
    val text: String,
    val slip: SlipData,
    val looksLikeSlip: Boolean,
)

private class Truth(val amount: BigDecimal?, val date: LocalDate?, val time: LocalDateTime?, val payee: String?)

fun main(args: Array<String>) {
    val inputs = mutableListOf<File>()
    var out = File("slipbench-out")
    var truthFile: File? = null
    var threads = 1
    var limit = Int.MAX_VALUE
    var only: Set<String>? = null
    var fileDates = false

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--out" -> out = File(args[++i])
            "--truth" -> truthFile = File(args[++i])
            "--threads" -> threads = args[++i].toInt()
            "--limit" -> limit = args[++i].toInt()
            "--setups" -> only = args[++i].split(',').map { it.trim() }.toSet()
            "--file-dates" -> fileDates = true
            "-h", "--help" -> { println(USAGE); return }
            else -> inputs += File(a)
        }
        i++
    }
    if (inputs.isEmpty()) {
        println(USAGE)
        exitProcess(1)
    }

    val images = inputs.flatMap { input ->
        if (input.isDirectory) input.walkTopDown().filter { it.isFile && it.extension.lowercase() in IMAGE_TYPES }.toList()
        else listOf(input)
    }.sortedBy { it.path }.take(limit)
    if (images.isEmpty()) {
        println("No images (${IMAGE_TYPES.joinToString()}) found in ${inputs.joinToString()}")
        exitProcess(1)
    }

    val setups = setups().filter { only == null || it.id in only!! }
    val truth = truthFile?.let { readTruth(it) } ?: emptyMap()
    out.mkdirs()

    println("${images.size} images, setups: ${setups.joinToString { it.id }}, $threads thread(s) per model")
    if (fileDates) println("Using file times as image dates")
    if (truth.isNotEmpty()) println("Right answers for ${truth.size} images from $truthFile")

    val engines = setups.associateWith { s ->
        SlipOcrEngine(s.det.readBytes(), s.rec.readBytes(), s.charset, flattenLines = s.flatten, threads = threads)
    }

    // Warm up, so the first image isn't slower for the setup that runs first
    loadImage(images.first())?.let { image -> engines.values.forEach { it.recognize(image) } }

    val results = LinkedHashMap<File, Map<Setup, Reading>>()
    var unreadable = 0
    val started = System.currentTimeMillis()

    images.forEachIndexed { index, file ->
        val image = loadImage(file)
        if (image == null) {
            unreadable++
        } else {
            val imageDate = if (fileDates) {
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(file.lastModified()), java.time.ZoneId.systemDefault())
            } else {
                null
            }
            results[file] = setups.associateWith { setup ->
                val engine = engines.getValue(setup)
                val lines = engine.recognize(image)
                val text = SlipLayout.toText(lines, image.width)
                Reading(
                    engine.lastDetectMs, engine.lastRecognizeMs, text,
                    SlipParser.parse(text, imageDate), SlipParser.looksLikeSlip(text, imageDate),
                )
            }
        }
        if ((index + 1) % 10 == 0 || index == images.lastIndex) {
            val elapsed = (System.currentTimeMillis() - started) / 1000.0
            val left = elapsed / (index + 1) * (images.size - index - 1)
            println("  ${index + 1}/${images.size} images, ${"%.0f".format(elapsed)} s, about ${"%.0f".format(left)} s left")
        }
    }
    engines.values.forEach { it.close() }

    val summary = summary(results, setups, truth, unreadable, threads, fileDates)
    File(out, "summary.txt").writeText(summary)
    writeCsv(File(out, "results.csv"), results)
    writeReport(File(out, "report.html"), results, setups, truth)
    File(out, "details.txt").writeText(details(results, setups))

    println()
    println(summary)
    println("Written to ${out.absolutePath}:")
    println("  summary.txt  counts and timings only, safe to share")
    println("  details.txt  date/time and amount lines of problem slips, no names: meant to share")
    println("  results.csv  what each setup read from each slip (private)")
    println("  report.html  slips where the setups disagree, with images (private)")
}

private val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "bmp", "gif")

// ---- setups and models ----

private fun setups(): List<Setup> {
    val app = listOf(File("app/src/main/assets/ocr"), File("../../app/src/main/assets/ocr")).firstOrNull { it.isDirectory }
        ?: error("Run from the repository root: app/src/main/assets/ocr not found")
    val akson = File(app, "rec.onnx")
    val aksonCharset = File(app, "rec_charset.txt").readText(Charsets.UTF_8).removeSuffix("\n").split('\n')

    val iapp = downloadIapp()
    val iappCharset = listOf("") + File(iapp, "rec_dict.txt").readText(Charsets.UTF_8).removeSuffix("\n").split('\n') + listOf(" ")
    check(iappCharset.size == 530) { "Unexpected iApp charset size ${iappCharset.size}" }

    return listOf(
        Setup("current", "PaddleOCR v5 mobile det + AksonOCR-Lite rec (the app now)", File(app, "det.onnx"), akson, aksonCharset, false),
        Setup("tinydet", "iApp PP-OCRv6 tiny det + AksonOCR-Lite rec", File(iapp, "det.onnx"), akson, aksonCharset, false),
        Setup("iapp", "iApp PP-OCRv6 tiny det + iApp OpenThai rec", File(iapp, "det.onnx"), File(iapp, "rec.onnx"), iappCharset, true),
        Setup("iapprec", "PaddleOCR v5 mobile det + iApp OpenThai rec", File(app, "det.onnx"), File(iapp, "rec.onnx"), iappCharset, true),
    )
}

private fun downloadIapp(): File {
    val dir = listOf(File("tools/slipbench"), File(".")).first { File(it, "build.gradle.kts").exists() }
        .let { File(it, "models/iapp-OpenThai2.1-OCR-Text-v2.1.4") }
    dir.mkdirs()
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    IAPP_FILES.forEach { (name, sha256) ->
        val file = File(dir, name)
        if (file.exists() && sha256(file) == sha256) return@forEach
        println("Downloading $name from Hugging Face...")
        val response = client.send(
            HttpRequest.newBuilder(URI("$IAPP_REPO/$name")).build(),
            HttpResponse.BodyHandlers.ofFile(file.toPath()),
        )
        check(response.statusCode() == 200) { "Download of $name failed: HTTP ${response.statusCode()}" }
        check(sha256(file) == sha256) { "$name doesn't match the tested version (SHA-256)" }
    }
    return dir
}

private fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

// ---- images ----

private fun loadImage(file: File): OcrImage? {
    var image = try {
        ImageIO.read(file)
    } catch (e: Exception) {
        null
    } ?: return null

    val longSide = maxOf(image.width, image.height)
    if (longSide > MAX_SIDE) {
        val scale = MAX_SIDE.toDouble() / longSide
        val scaled = BufferedImage((image.width * scale).toInt(), (image.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, scaled.width, scaled.height, null)
            dispose()
        }
        image = scaled
    }
    return OcrImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
}

// ---- right answers ----

private fun readTruth(file: File): Map<String, Truth> {
    val rows = file.readLines(Charsets.UTF_8).map { it.removePrefix("﻿") }.filter { it.isNotBlank() }
    val header = csvSplit(rows.first()).map { it.trim().lowercase() }
    val col = { name: String -> header.indexOf(name).takeIf { it >= 0 } }
    val fileCol = col("file") ?: error("$file: needs a 'file' column")
    return rows.drop(1).associate { row ->
        val cells = csvSplit(row)
        fun cell(name: String) = col(name)?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
        val dateText = cell("date")
        val time = dateText?.let { runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull() }
        val date = time?.toLocalDate() ?: dateText?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        File(cells[fileCol].trim()).name to Truth(
            amount = cell("amount")?.replace(",", "")?.toBigDecimalOrNull(),
            date = date,
            time = time,
            payee = cell("payee"),
        )
    }
}

private fun csvSplit(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { cells += cell.toString(); cell.clear() }
            else -> cell.append(c)
        }
        i++
    }
    cells += cell.toString()
    return cells
}

// ---- comparing ----

private fun normalizePayee(text: String?) = text?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

private fun sameAmount(a: BigDecimal?, b: BigDecimal?) = a != null && b != null && a.compareTo(b) == 0

private fun payeeMatches(read: String?, right: String?): Boolean {
    val a = normalizePayee(read) ?: return false
    val b = normalizePayee(right) ?: return false
    return a == b || a.contains(b) || b.contains(a)
}

private fun Truth.amountOk(r: Reading) = amount == null || sameAmount(r.slip.amount, amount)
private fun Truth.dateOk(r: Reading) = when {
    time != null -> r.slip.dateTime == time
    date != null -> r.slip.dateTime?.toLocalDate() == date
    else -> true
}
private fun Truth.payeeOk(r: Reading) = payee == null || payeeMatches(r.slip.payee, payee)

/** Setups read the slip differently: amount, date and time, or payee */
private fun disagrees(readings: Map<Setup, Reading>): Boolean {
    val values = readings.values
    return values.map { it.slip.amount?.stripTrailingZeros() }.distinct().size > 1 ||
            values.map { it.slip.dateTime }.distinct().size > 1 ||
            values.map { normalizePayee(it.slip.payee) }.distinct().size > 1
}

private fun pct(n: Int, of: Int) = if (of == 0) "-" else "%5.1f%%".format(100.0 * n / of)

private fun percentile(values: List<Long>, p: Double): Long =
    values.sorted().let { if (it.isEmpty()) 0 else it[((it.size - 1) * p).toInt()] }

private fun summary(
    results: Map<File, Map<Setup, Reading>>,
    setups: List<Setup>,
    truth: Map<String, Truth>,
    unreadable: Int,
    threads: Int,
    fileDates: Boolean,
): String = buildString {
    val n = results.size
    val anySlip = results.values.count { r -> r.values.any { it.looksLikeSlip } }
    appendLine("Slip OCR benchmark")
    appendLine("=================")
    appendLine("Images read: $n${if (unreadable > 0) " ($unreadable couldn't be opened)" else ""}")
    appendLine("Recognized as a slip by at least one setup: $anySlip")
    appendLine("File times used as image dates: ${if (fileDates) "yes" else "no"}")
    appendLine("Threads per model: $threads, CPU: ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cores, ${System.getProperty("os.name")}")
    appendLine()
    setups.forEach { appendLine("  ${it.id.padEnd(8)} ${it.description}") }

    appendLine()
    appendLine("Speed, ms per image          median     p90    mean   (find text + read text)")
    setups.forEach { s ->
        val totals = results.values.map { it.getValue(s).let { r -> r.detectMs + r.recognizeMs } }
        val det = results.values.map { it.getValue(s).detectMs }.average()
        val rec = results.values.map { it.getValue(s).recognizeMs }.average()
        appendLine("  ${s.id.padEnd(26)} ${"%7d".format(percentile(totals, 0.5))} ${"%7d".format(percentile(totals, 0.9))} ${"%7.0f".format(totals.average())}   (${"%.0f".format(det)} + ${"%.0f".format(rec)})")
    }

    // Rates over images that at least one setup thinks are slips
    val slips = results.filter { (_, r) -> r.values.any { it.looksLikeSlip } }
    appendLine()
    appendLine("Found, out of the $anySlip slips    slip   amount    date  payee  reference  note")
    setups.forEach { s ->
        val rs = slips.values.map { it.getValue(s) }
        appendLine(
            "  ${s.id.padEnd(30)} ${pct(rs.count { it.looksLikeSlip }, rs.size)} ${pct(rs.count { it.slip.amount != null }, rs.size)} " +
                    "${pct(rs.count { it.slip.dateTime != null }, rs.size)} ${pct(rs.count { it.slip.payee != null }, rs.size)} " +
                    "${pct(rs.count { it.slip.reference != null }, rs.size)} ${pct(rs.count { it.slip.note != null }, rs.size)}"
        )
    }

    val base = setups.first()
    if (setups.size > 1) {
        appendLine()
        appendLine("Same as '${base.id}', where both found a value      amount    date+time  payee")
        setups.drop(1).forEach { s ->
            val pairs = slips.values.map { it.getValue(base) to it.getValue(s) }
            val amounts = pairs.filter { (a, b) -> a.slip.amount != null && b.slip.amount != null }
            val dates = pairs.filter { (a, b) -> a.slip.dateTime != null && b.slip.dateTime != null }
            val payees = pairs.filter { (a, b) -> a.slip.payee != null && b.slip.payee != null }
            appendLine(
                "  ${s.id.padEnd(45)} ${pct(amounts.count { (a, b) -> sameAmount(a.slip.amount, b.slip.amount) }, amounts.size)}" +
                        "     ${pct(dates.count { (a, b) -> a.slip.dateTime == b.slip.dateTime }, dates.size)}  " +
                        pct(payees.count { (a, b) -> normalizePayee(a.slip.payee) == normalizePayee(b.slip.payee) }, payees.size)
            )
        }
        appendLine("Slips where the setups disagree: ${slips.values.count { disagrees(it) }} (listed in report.html)")
    }

    val judged = results.filterKeys { it.name in truth }
    if (judged.isNotEmpty()) {
        appendLine()
        appendLine("Correct, against the right answers for ${judged.size} images      amount    date   payee")
        setups.forEach { s ->
            val withAmount = judged.filter { truth.getValue(it.key.name).amount != null }
            val withDate = judged.filter { truth.getValue(it.key.name).date != null }
            val withPayee = judged.filter { truth.getValue(it.key.name).payee != null }
            appendLine(
                "  ${s.id.padEnd(50)} ${pct(withAmount.count { (f, r) -> truth.getValue(f.name).amountOk(r.getValue(s)) }, withAmount.size)} " +
                        "${pct(withDate.count { (f, r) -> truth.getValue(f.name).dateOk(r.getValue(s)) }, withDate.size)} " +
                        pct(withPayee.count { (f, r) -> truth.getValue(f.name).payeeOk(r.getValue(s)) }, withPayee.size)
            )
        }
    }
}

// ---- shareable details ----

// Date and time or amount: "21 พ.ย. 69 16:39 น.", "27 Oct 2026 19:10", "120.00 บาท", "1,330.00"
private val TIME = Regex("""\d{1,2}\s*[:.]\s*\d{2}(?!\d)|\d{1,2}\s*[ก-๛A-Za-z.]{2,12}\s*\d{2,4}""")
private val MONEY = Regex("""\d[\d,]*[.,]\d{2}(?!\d)""")
// Nothing that looks like an account or reference number
private val LONG_NUMBER = Regex("""[0-9xX*]{6,}|[0-9xX*]+-[0-9xX*]+-""")

/**
 * For slips where a date is missing or the setups disagree on the amount or date: only the
 * recognized lines with a date, time or amount, and what the parser made of them. No file names,
 * payees, notes, references or account numbers, so it can be shared to improve the parser.
 */
private fun details(results: Map<File, Map<Setup, Reading>>, setups: List<Setup>): String = buildString {
    appendLine("Date/time and amount lines of problem slips (no names, references or file names)")
    appendLine()
    var n = 0
    results.values.forEach { readings ->
        if (readings.values.none { it.looksLikeSlip || it.slip.amount != null }) return@forEach
        n++
        val amounts = readings.values.map { it.slip.amount?.stripTrailingZeros() }.distinct()
        val dates = readings.values.map { it.slip.dateTime }.distinct()
        val missingDate = readings.values.any { it.slip.dateTime == null }
        if (amounts.size == 1 && dates.size == 1 && !missingDate) return@forEach

        appendLine("slip $n")
        setups.forEach { setup ->
            val r = readings.getValue(setup)
            val lines = r.text.lines()
                .map { it.trim() }
                .filter { line -> (TIME.containsMatchIn(line) || MONEY.containsMatchIn(line)) && !LONG_NUMBER.containsMatchIn(line) }
            appendLine("  ${setup.id.padEnd(8)} -> amount ${r.slip.amount?.toPlainString() ?: "-"}, date ${r.slip.dateTime ?: "-"}")
            lines.forEach { appendLine("             | $it") }
        }
        appendLine()
    }
}

// ---- private outputs ----

private fun csvCell(value: Any?): String {
    val text = value?.toString() ?: ""
    return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
}

private fun writeCsv(file: File, results: Map<File, Map<Setup, Reading>>) {
    file.bufferedWriter(Charsets.UTF_8).use { w ->
        w.write("﻿") // Excel reads Thai correctly
        w.write("file,setup,find_ms,read_ms,looks_like_slip,amount,date_time,payee,reference,note\n")
        results.forEach { (image, readings) ->
            readings.forEach { (setup, r) ->
                w.write(
                    listOf(
                        image.path, setup.id, r.detectMs, r.recognizeMs, r.looksLikeSlip,
                        r.slip.amount?.toPlainString(), r.slip.dateTime, r.slip.payee, r.slip.reference, r.slip.note,
                    ).joinToString(",") { csvCell(it) } + "\n"
                )
            }
        }
    }
}

private fun html(text: Any?) = (text?.toString() ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun writeReport(file: File, results: Map<File, Map<Setup, Reading>>, setups: List<Setup>, truth: Map<String, Truth>) {
    val wrong = { f: File, r: Map<Setup, Reading> ->
        truth[f.name]?.let { t -> r.values.any { !t.amountOk(it) || !t.dateOk(it) || !t.payeeOk(it) } } ?: false
    }
    val shown = results.filter { (f, r) -> r.values.any { it.looksLikeSlip } && (disagrees(r) || wrong(f, r)) }

    file.bufferedWriter(Charsets.UTF_8).use { w ->
        w.write(
            """
            <!doctype html><html><head><meta charset="utf-8"><title>Slip OCR benchmark</title>
            <style>
              body { font-family: system-ui, sans-serif; margin: 16px; color: #222; }
              .slip { display: flex; gap: 16px; border-top: 1px solid #ccc; padding: 16px 0; }
              .slip img { width: 260px; height: auto; border: 1px solid #ddd; }
              table { border-collapse: collapse; font-size: 14px; }
              td, th { border: 1px solid #ddd; padding: 4px 8px; text-align: left; vertical-align: top; }
              .diff { background: #fff3cd; } .bad { background: #f8d7da; } .truth { background: #d1e7dd; }
              pre { font-size: 12px; white-space: pre-wrap; max-width: 520px; }
            </style></head><body>
            <h1>Slips where the setups disagree</h1>
            <p>${shown.size} of ${results.size} images. Yellow: differs from '${html(setups.first().id)}'. Red: differs from the right answer.</p>
            """.trimIndent()
        )
        shown.forEach { (image, readings) ->
            val base = readings.getValue(setups.first())
            val t = truth[image.name]
            w.write("<div class=\"slip\"><a href=\"${image.toURI()}\"><img src=\"${image.toURI()}\" loading=\"lazy\"></a><div>")
            w.write("<b>${html(image.path)}</b><table><tr><th>setup</th><th>amount</th><th>date and time</th><th>payee</th><th>reference</th><th>note</th></tr>")
            if (t != null) {
                w.write("<tr class=\"truth\"><td>right answer</td><td>${html(t.amount?.toPlainString())}</td><td>${html(t.time ?: t.date)}</td><td>${html(t.payee)}</td><td></td><td></td></tr>")
            }
            readings.forEach { (setup, r) ->
                fun cls(differs: Boolean, bad: Boolean) = when {
                    bad -> " class=\"bad\""
                    differs -> " class=\"diff\""
                    else -> ""
                }
                val s = r.slip
                w.write("<tr><td>${html(setup.id)}</td>")
                w.write("<td${cls(!sameAmount(s.amount, base.slip.amount) && s.amount != base.slip.amount, t?.amountOk(r) == false)}>${html(s.amount?.toPlainString())}</td>")
                w.write("<td${cls(s.dateTime != base.slip.dateTime, t?.dateOk(r) == false)}>${html(s.dateTime)}</td>")
                w.write("<td${cls(normalizePayee(s.payee) != normalizePayee(base.slip.payee), t?.payeeOk(r) == false)}>${html(s.payee)}</td>")
                w.write("<td>${html(s.reference)}</td><td>${html(s.note)}</td></tr>")
            }
            w.write("</table>")
            readings.forEach { (setup, r) ->
                w.write("<details><summary>${html(setup.id)}: recognized text</summary><pre>${html(r.text)}</pre></details>")
            }
            w.write("</div></div>")
        }
        w.write("</body></html>")
    }
}
