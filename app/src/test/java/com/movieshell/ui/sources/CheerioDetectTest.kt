package com.movieshell.ui.sources

import com.movieshell.data.source_mgr.needsCheerioEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.33：cheerio 类 drpy 规则识别（导入与激活两道口共用）。 */
class CheerioDetectTest {

    @Test
    fun needsCheerioEngine_firstLevelParseStrings() {
        assertTrue(needsCheerioEngine("var rule = { title: 'x', 一级: 'jsp:pdfa:http://x/list&&li' }"))
        assertTrue(needsCheerioEngine("var rule={一级:\"json:$.data&&name\"}"))
        assertTrue(needsCheerioEngine("var rule = { '一级': 'html:div.item&&a' }"))
    }

    @Test
    fun needsCheerioEngine_pdfaPdfhCheerio() {
        assertTrue(needsCheerioEngine("var items = pdfa(html, 'div.item');"))
        assertTrue(needsCheerioEngine("var t = pdfh(html, 'a&&Text');"))
        assertTrue(needsCheerioEngine("// cheerio 垫片"))
    }

    @Test
    fun needsCheerioEngine_normalRulesPass() {
        assertFalse(needsCheerioEngine("var rule = { title: 'x', 一级: function() { return []; } }"))
        assertFalse(needsCheerioEngine("var rule = { title: 'x', homeUrl: 'https://example.com/' }"))
        assertFalse(needsCheerioEngine(""))
    }
}
