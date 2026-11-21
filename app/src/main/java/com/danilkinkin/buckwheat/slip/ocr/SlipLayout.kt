package com.danilkinkin.buckwheat.slip.ocr

/** Turns recognized pieces of text into rows, the plain-text form the slip parser reads */
object SlipLayout {
    /** Wide gaps inside a row, "label      value", the parser splits on 2+ spaces */
    private const val COLUMN_GAP = "     "

    fun toText(lines: List<OcrLine>, imageWidth: Int): String {
        val rows = mutableListOf<MutableList<OcrLine>>()
        lines.sortedBy { (it.top + it.bottom) / 2 }.forEach { line ->
            val center = (line.top + line.bottom) / 2
            val height = line.bottom - line.top
            val row = rows.firstOrNull { row ->
                val first = row.first()
                val rowCenter = (first.top + first.bottom) / 2
                kotlin.math.abs(center - rowCenter) < 0.5 * minOf(height, first.bottom - first.top)
            }
            if (row != null) row += line else rows += mutableListOf(line)
        }

        val kept = rows
            .onEach { row -> row.sortBy { it.left } }
            // Decorations stand alone on the right: theme art ("มีสุข สมหวัง"), mascots,
            // "scan to verify" captions. Values on the right have digits or share a row with a label.
            .filterNot { row -> row.first().left > imageWidth * 0.6 && row.none { line -> line.text.any { it.isDigit() } } }

        if (kept.isEmpty()) return ""

        val rowHeight = kept.map { row -> row.maxOf { it.bottom } - row.minOf { it.top } }.sorted()[kept.size / 2]
        val text = StringBuilder()
        var previousBottom: Int? = null

        kept.forEach { row ->
            val top = row.minOf { it.top }
            // A large vertical gap separates blocks, e.g. the sender from the recipient
            if (previousBottom != null && top - previousBottom!! > rowHeight * 1.2) text.append('\n')
            previousBottom = row.maxOf { it.bottom }

            val height = row.map { it.bottom - it.top }.sorted()[row.size / 2]
            row.forEachIndexed { index, line ->
                if (index > 0) text.append(if (line.left - row[index - 1].right > height) COLUMN_GAP else " ")
                text.append(line.text)
            }
            text.append('\n')
        }

        return text.toString()
    }
}
