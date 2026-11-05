package io.github.mangi.eta.local.richcontent

import org.junit.Assert.*
import org.junit.Test

class RichOutputRepairTest {
    @Test fun percentDecodingIsSinglePassAndPreservesPlusAndInvalidSequences() {
        assertEquals("/sdcard/Download/中文 图+片.png", percentDecodedPath("/sdcard/Download/%E4%B8%AD%E6%96%87%20%E5%9B%BE+%E7%89%87.png"))
        assertEquals("%20", percentDecodedPath("%2520"))
        assertEquals("bad%zz+name", percentDecodedPath("bad%zz+name"))
        assertEquals("图+片.png", percentName("/sdcard/Download/%E5%9B%BE+%E7%89%87.png"))
    }
    @Test fun imagePreviewKeepsWideImagesWholeAndLongImagesBounded() {
        val wide = fittedImageBox(2f, 300f, 360f)
        assertEquals(300f, wide.widthDp, 0.01f)
        assertEquals(150f, wide.heightDp, 0.01f)
        assertFalse(wide.cropped)
        val long = fittedImageBox(0.1f, 300f, 360f)
        assertEquals(300f, long.widthDp, 0.01f)
        assertEquals(360f, long.heightDp, 0.01f)
        assertTrue(long.cropped)
    }
    @Test fun scrollRestoreHappensOnceAndHeightFeedbackIgnoresSameMeasurement() {
        assertEquals(200, pendingScrollRestore(200, false))
        assertNull(pendingScrollRestore(200, true))
        assertEquals(0, pendingScrollRestore(-1, false))
        assertNull(acceptedReportedHeight(100, 100, 2))
        assertNull(acceptedReportedHeight(100, 101, 2))
        assertEquals(150, acceptedReportedHeight(100, 150, 2))
    }
    @Test fun imageFailuresAreNotSwallowedIntoBlankContent() {
        assertEquals("文件不存在或已移走", imageFailureReason(IllegalArgumentException("文件不存在或已移走")))
        assertTrue(imageFailureReason(Exception()).isNotBlank())
    }
}
