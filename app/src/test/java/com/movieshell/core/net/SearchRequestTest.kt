package com.movieshell.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.2.23：searchUrl 求值判定、相对地址补全、JSON 列表规则判定。 */
class SearchRequestTest {

    @Test fun jsPrefixNeedsJs() {
        assertTrue(searchUrlNeedsJs("<js>result='https://x.com/s?q='+key</js>"))
        assertTrue(searchUrlNeedsJs("@js:if(key.match(/@/)){result='https://a.com/'}"))
    }

    @Test fun plainUrlDoesNotNeedJs() {
        assertFalse(searchUrlNeedsJs("https://x.com/s?q={{key}}"))
        assertFalse(searchUrlNeedsJs("https://x.com/s?q={{key}}&page={{page}}"))
        assertFalse(searchUrlNeedsJs("/search?kw={{key}}"))
    }

    @Test fun jsTemplateBlockNeedsJs() {
        assertTrue(searchUrlNeedsJs("{{url=source.getKey(); cookie.removeCookie(url)}}/search/"))
        assertEquals(
            listOf("url=source.getKey(); cookie.removeCookie(url)"),
            jsTemplateBlocks("{{url=source.getKey(); cookie.removeCookie(url)}}/search/"),
        )
    }

    @Test fun keyPageBlocksDoNotNeedJs() {
        assertTrue(jsTemplateBlocks("https://x.com/s?q={{key}}&page={{page}}").isEmpty())
    }

    @Test fun absoluteUrlUnchanged() {
        val t = "https://x.com/s?q={{key}}"
        assertEquals(t, resolveSearchUrlTemplate(t, "https://www.example.com"))
    }

    @Test fun relativePathResolvedAgainstBase() {
        assertEquals(
            "https://m.51eshu.com/search?kw={{key}}",
            resolveSearchUrlTemplate("/search?kw={{key}}", "https://m.51eshu.com"),
        )
        assertEquals(
            "https://v2.stemcells.click/modules/article/search.php",
            resolveSearchUrlTemplate("/modules/article/search.php", "https://v2.stemcells.click"),
        )
    }

    @Test fun relativeFileResolvedAgainstBaseDir() {
        assertEquals(
            "https://site.com/dir/search.php",
            resolveSearchUrlTemplate("search.php", "https://site.com/dir/"),
        )
    }

    @Test fun postOptionsSuffixPreserved() {
        assertEquals(
            "https://site.com/search.php,{\"method\":\"POST\",\"body\":\"q={{key}}\"}",
            resolveSearchUrlTemplate(
                "/search.php,{\"method\":\"POST\",\"body\":\"q={{key}}\"}",
                "https://site.com",
            ),
        )
    }

    @Test fun jsonListRuleDetected() {
        assertTrue(isJsonListRule("$.data.content[*]"))
        assertTrue(isJsonListRule("<js>var x = 1;</js>$.data.content[*]"))
        assertFalse(isJsonListRule("div.list2"))
        assertFalse(isJsonListRule("<js>var x = 1;</js>div.list2"))
    }

    @Test fun buildSearchRequestStillReplacesKey() {
        val req = buildSearchRequest("https://x.com/s?q={{key}}&page={{page}}", "我的")
        assertEquals("https://x.com/s?q=%E6%88%91%E7%9A%84&page=1", req.url)
    }

    // v1.2.24：GBK 站查询段关键词按 GBK 编码（阅读 encodeUrlParams 同款），
    // 路径段仍按 UTF-8 编码；{{page}} 按传入页码替换。
    @Test fun gbkQueryKeyUsesCharset() {
        val req = buildSearchRequest(
            "https://x.com/s/{{key}}?q={{key}},{\"charset\":\"GBK\"}",
            "我的",
        )
        assertEquals("https://x.com/s/%E6%88%91%E7%9A%84?q=%CE%D2%B5%C4", req.url)
        assertEquals("GBK", req.charsetName)
    }

    @Test fun buildSearchRequestPageParam() {
        val req = buildSearchRequest("https://x.com/s?q={{key}}&page={{page}}", "我的", 3)
        assertEquals("https://x.com/s?q=%E6%88%91%E7%9A%84&page=3", req.url)
    }

    @Test fun buildSearchRequestParsesRetryAndJsOption() {
        val req = buildSearchRequest(
            "https://x.com/s?q={{key}},{\"retry\":2,\"js\":\"result\"}",
            "a",
        )
        assertEquals(2, req.retry)
        assertEquals("result", req.jsOption)
        assertEquals("https://x.com/s?q=a", req.url)
    }

    @Test fun midStringJsNeedsJs() {
        assertTrue(searchUrlNeedsJs("https://x.com/s/@js:result+encodeURIComponent(key)"))
        assertTrue(searchUrlNeedsJs("https://x.com/s/<js>result+key</js>/more"))
    }

    @Test fun formatJsTemplateValueDropsDecimal() {
        assertEquals("2", formatJsTemplateValue(2.0))
        assertEquals("2.5", formatJsTemplateValue(2.5))
        assertEquals("abc", formatJsTemplateValue("abc"))
        assertEquals("", formatJsTemplateValue(null))
        assertEquals("3", formatJsTemplateValue(3))
    }
}
