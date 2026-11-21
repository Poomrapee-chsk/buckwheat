package com.danilkinkin.buckwheat.slip.watch

/** Recognizes photo folders that Thai bank and wallet apps save slips into */
object SlipFolderHints {
    // Short names, matched as whole words: "make" must not match "makeup"
    private val WORDS = listOf(
        "k plus", "kplus", "k+", "make", "kbank", "scb", "ktb", "next", "kma", "ttb", "gsb", "uob",
        "cimb", "lhb", "kkp", "mymo",
    )

    // Matched anywhere: "slips", "Krungthai NEXT", "สลิปโอนเงิน"
    private val PARTS = listOf(
        "slip", "สลิป", "krungthai", "bangkok bank", "bualuang", "krungsri", "truemoney",
        "true money", "paotang", "เป๋าตัง",
    )

    fun isBankFolder(name: String, relativePath: String): Boolean {
        val text = "$relativePath $name".lowercase()
        return PARTS.any { text.contains(it) } || WORDS.any { word ->
            Regex("(^|[^a-z0-9])${Regex.escape(word)}([^a-z0-9]|$)").containsMatchIn(text)
        }
    }
}
