package com.danilkinkin.buckwheat.slip

/**
 * Suggests a tag (the spend's comment) for a slip, learning from the user's own history:
 *
 * 1. The tag the user chose the last time for the same payee
 * 2. An existing tag equal to the slip's note
 * 3. An existing tag contained in the note or payee (or the other way round)
 * 4. An existing tag similar to the note or payee (character bigrams, survives OCR typos)
 * 5. Otherwise nothing: the spend gets the default tag ("Other") when it's added. The note is kept
 *    as the slip's note, not used as a tag
 */
object TagSuggester {
    private const val MIN_SIMILARITY = 0.6

    /**
     * @param tagCounts how many past spends used each tag, most used tags win ties
     * @param payeeTags tag chosen earlier for a payee, keyed by [payeeKey]
     */
    fun suggest(
        note: String?,
        payee: String?,
        tagCounts: Map<String, Int>,
        payeeTags: Map<String, String>,
    ): String {
        payee?.let { payeeTags[payeeKey(it)] }?.let { return it }

        val tags = tagCounts.keys.filter { it.isNotBlank() }
        val byUsage = compareByDescending<String> { tagCounts[it] ?: 0 }
        val cleanNote = note?.trim().orEmpty()
        val texts = listOfNotNull(cleanNote.takeIf { it.isNotEmpty() }, payee?.trim())
            .map { it.lowercase() }

        tags.firstOrNull { it.trim().equals(cleanNote, ignoreCase = true) }?.let { return it }

        tags
            .filter { tag ->
                val t = tag.trim().lowercase()
                t.length >= 2 && texts.any { text -> text.contains(t) || (text.length >= 3 && t.contains(text)) }
            }
            .sortedWith(byUsage)
            .firstOrNull()
            ?.let { return it }

        tags
            .map { tag -> tag to texts.maxOfOrNull { similarity(it, tag.lowercase()) }.orZero() }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy(byUsage) { it.first })
            .firstOrNull()
            ?.let { return it.first }

        return ""
    }

    /** Payee name in a form that is stable between slips: no spaces, punctuation, letter case. */
    fun payeeKey(payee: String): String =
        payee.lowercase().filter { it.isLetterOrDigit() }

    /** Dice coefficient of character bigrams */
    internal fun similarity(a: String, b: String): Double {
        val x = bigrams(a.filterNot { it.isWhitespace() })
        val y = bigrams(b.filterNot { it.isWhitespace() })
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val common = x.groupingBy { it }.eachCount().entries.sumOf { (gram, count) ->
            minOf(count, y.count { it == gram })
        }
        return 2.0 * common / (x.size + y.size)
    }

    private fun bigrams(text: String): List<String> =
        if (text.length < 2) emptyList() else (0 until text.length - 1).map { text.substring(it, it + 2) }

    private fun Double?.orZero() = this ?: 0.0
}
