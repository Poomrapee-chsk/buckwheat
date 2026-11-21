package com.danilkinkin.buckwheat.slip.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlipFolderHintsTest {
    @Test
    fun bankFolders() {
        assertTrue(SlipFolderHints.isBankFolder("K PLUS", "Pictures/K PLUS/"))
        assertTrue(SlipFolderHints.isBankFolder("MAKE by KBank", "Pictures/MAKE by KBank/"))
        assertTrue(SlipFolderHints.isBankFolder("SCB EASY", "DCIM/SCB EASY/"))
        assertTrue(SlipFolderHints.isBankFolder("Krungthai NEXT", "Pictures/Krungthai NEXT/"))
        assertTrue(SlipFolderHints.isBankFolder("Slips", "Pictures/Slips/"))
        assertTrue(SlipFolderHints.isBankFolder("สลิปโอนเงิน", "Pictures/สลิปโอนเงิน/"))
    }

    @Test
    fun otherFolders() {
        assertFalse(SlipFolderHints.isBankFolder("Camera", "DCIM/Camera/"))
        assertFalse(SlipFolderHints.isBankFolder("Screenshots", "Pictures/Screenshots/"))
        assertFalse(SlipFolderHints.isBankFolder("Makeup", "Pictures/Makeup/"))
        assertFalse(SlipFolderHints.isBankFolder("WhatsApp Images", "Android/media/com.whatsapp/"))
    }
}
