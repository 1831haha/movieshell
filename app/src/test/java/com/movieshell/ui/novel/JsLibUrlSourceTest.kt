package com.movieshell.ui.novel

import com.movieshell.domain.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.3.33：书源列表里 URL 形式的 jsLib 识别（导入/编辑保存入口共用，命中即明确拒绝）。 */
class JsLibUrlSourceTest {

    @Test
    fun firstJsLibUrlSourceName_findsUrlLib() {
        val sources = listOf(
            BookSource(name = "内联源", url = "https://a.example.com", jsLib = "function h() {}"),
            BookSource(name = "链接源", url = "https://b.example.com", jsLib = "https://cdn.example.com/lib.js"),
        )
        assertEquals("链接源", firstJsLibUrlSourceName(sources))
    }

    @Test
    fun firstJsLibUrlSourceName_noneWhenAllInline() {
        val sources = listOf(
            BookSource(name = "源一", url = "https://a.example.com", jsLib = ""),
            BookSource(name = "源二", url = "https://b.example.com", jsLib = "var x = 1;"),
        )
        assertNull(firstJsLibUrlSourceName(sources))
        assertNull(firstJsLibUrlSourceName(emptyList()))
    }
}
