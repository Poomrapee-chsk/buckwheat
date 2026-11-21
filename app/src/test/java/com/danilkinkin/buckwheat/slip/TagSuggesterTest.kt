package com.danilkinkin.buckwheat.slip

import org.junit.Assert.assertEquals
import org.junit.Test

class TagSuggesterTest {
    private val tags = mapOf("Coffee" to 12, "กาแฟ" to 5, "Food" to 30, "Taxi" to 4)

    @Test
    fun rememberedPayeeWins() {
        val tag = TagSuggester.suggest(
            note = "กาแฟเย็น",
            payee = "ร้าน กาแฟดี",
            tagCounts = tags,
            payeeTags = mapOf(TagSuggester.payeeKey("ร้านกาแฟดี") to "Coffee"),
        )
        assertEquals("Coffee", tag)
    }

    @Test
    fun noteEqualToTag() {
        assertEquals("Food", TagSuggester.suggest("food", null, tags, emptyMap()))
    }

    @Test
    fun tagContainedInNote() {
        assertEquals("กาแฟ", TagSuggester.suggest("กาแฟเย็น", null, tags, emptyMap()))
    }

    @Test
    fun similarTagDespiteTypo() {
        assertEquals("Coffee", TagSuggester.suggest("Cofee", null, tags, emptyMap()))
    }

    @Test
    fun payeeMatchesTag() {
        assertEquals("Taxi", TagSuggester.suggest(null, "GRAB TAXI", tags, emptyMap()))
    }

    @Test
    fun fallsBackToNote() {
        assertEquals("ค่าเช่า", TagSuggester.suggest("ค่าเช่า", "นาย สมชาย", tags, emptyMap()))
    }

    @Test
    fun emptyWhenNothingKnown() {
        assertEquals("", TagSuggester.suggest(null, "นาย สมชาย", tags, emptyMap()))
    }
}
