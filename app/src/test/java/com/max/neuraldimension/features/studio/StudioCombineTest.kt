package com.max.neuraldimension.features.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Studio single-file export/preview combiner on the JVM:
 * local CSS/JS inlining, script-attribute preservation, and honest
 * pass-through of external CDN dependencies (never bundled).
 */
class StudioCombineTest {

    private val css = "body { color: #fff; }"
    private val js = "console.log('studio-ready');"

    @Test
    fun blankTemplate_inlinesLocalCssAndJs() {
        val index = StudioStore.Template.BLANK.files.getValue("index.html")
        val out = StudioStore.combineDocument(index, css, js)
        assertTrue("css inlined into <style>", out.contains("<style>\n$css\n</style>"))
        assertTrue("js inlined into <script>", out.contains("<script>\n$js\n</script>"))
        assertFalse("no leftover local stylesheet link", out.contains("href=\"style.css\""))
        assertFalse("no leftover local script src", out.contains("src=\"script.js\""))
    }

    @Test
    fun reactTemplate_preservesScriptAttributesAndCdnUrls() {
        val t = StudioStore.Template.REACT.files
        val out = StudioStore.combineDocument(t.getValue("index.html"), t.getValue("style.css"), t.getValue("script.js"))
        assertTrue("text/babel attribute preserved on inlined script",
            out.contains("<script type=\"text/babel\">"))
        assertTrue("react CDN left as external dependency", out.contains("https://unpkg.com/react@18/umd/react.production.min.js"))
        assertTrue("react-dom CDN left as external dependency", out.contains("https://unpkg.com/react-dom@18/umd/react-dom.production.min.js"))
        assertTrue("babel CDN left as external dependency", out.contains("https://unpkg.com/@babel/standalone/babel.min.js"))
        assertFalse("local script src consumed", out.contains("src=\"script.js\""))
    }

    @Test
    fun missingLocalReferences_appendsBeforeBodyClose() {
        val index = "<!DOCTYPE html>\n<html>\n<head></head>\n<body>\n<p>x</p>\n</body>\n</html>"
        val out = StudioStore.combineDocument(index, css, js)
        assertTrue(out.contains("<style>\n$css\n</style>"))
        assertTrue(out.contains("<script>\n$js\n</script>\n</body>"))
    }

    @Test
    fun emptyCssAndJs_leaveDocumentUntouched() {
        val index = StudioStore.Template.BLANK.files.getValue("index.html")
        assertTrue(StudioStore.combineDocument(index, "", "") == index)
    }
}
