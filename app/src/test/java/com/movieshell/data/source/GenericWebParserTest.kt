package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.12：通用网页源分类——本站导航挂在外站上时的兜底。
 *
 * 固件是 2026-11-09 沙箱实抓的首页原样（dy2018 为 GBK 编码，人民网、优酷为 UTF-8）。
 * 认什么、挡什么见 GenericWebParser.parseCategories 注释。
 */
class GenericWebParserTest {

    private fun resBytes(name: String): ByteArray =
        javaClass.classLoader!!.getResource(name)!!.readBytes()

    private fun dy2018Html(): String =
        String(resBytes("dy2018-home.html"), charset("GBK"))

    private fun peopleHtml(): String =
        resBytes("people-home.html").toString(Charsets.UTF_8)

    private fun youkuHtml(): String =
        resBytes("youku-home.html").toString(Charsets.UTF_8)

    /** 本站导航全在外站：15 个分类都出来，留言板与设为主页不过滤不进来。 */
    @Test fun dy2018CrossDomainMenuAdmitted() {
        val cats = GenericWebParser.parseHome(dy2018Html(), "https://www.dy2018.com/").categories
        assertEquals(15, cats.size)
        val names = cats.map { it.typeName }
        assertTrue(names.contains("动作片"))
        assertTrue(names.contains("喜剧片"))
        assertTrue(names.contains("美剧"))
        assertTrue(names.contains("动漫资源"))
        assertFalse(names.any { it.contains("留言") || it.contains("设为") })
        assertTrue(cats.all { java.net.URL(it.typeId).host == "www.dytt8899.com" })
    }

    /** 294.50 的受益站不走回头路：已有本站分类时，导航区外站链接一个也不进。 */
    @Test fun peopleExternalNavLinksStayBlocked() {
        val cats = GenericWebParser.parseHome(peopleHtml(), "https://www.people.com.cn/").categories
        assertTrue(cats.isNotEmpty())
        assertTrue(cats.all { c ->
            val host = java.net.URL(c.typeId).host
            host == "www.people.com.cn" || host.endsWith(".people.com.cn")
        })
    }

    /** 本站一个分类都没有、导航区也抓不到外站分类时，不硬凑出垃圾分类。 */
    @Test fun youkuStaysEmpty() {
        val cats = GenericWebParser.parseHome(youkuHtml(), "https://www.youku.com/").categories
        assertTrue(cats.isEmpty())
    }

    /** 本站已有分类时，导航区的外站链接不进分类（只认本站）。 */
    @Test fun crossDomainNavIgnoredWhenOwnCategoriesExist() {
        val html = "<html><body><nav>" +
            "<a href=\"/type/1/\">电影</a>" +
            "<a href=\"https://other.example.com/x/\">外站</a>" +
            "</nav></body></html>"
        val cats = GenericWebParser.parseHome(html, "https://www.mysite.example/").categories
        assertEquals(listOf("电影"), cats.map { it.typeName })
    }

    /** 本站一个分类都没有时，导航区的外站链接当分类认。 */
    @Test fun crossDomainNavAdmittedWhenNoOwnCategories() {
        val html = "<html><body><div id=\"menu\"><ul>" +
            "<li><a href=\"https://cdn.example.com/2/\">动作片</a></li>" +
            "<li><a href=\"https://cdn.example.com/0/\">剧情片</a></li>" +
            "<li><a href=\"https://cdn.example.com/1/\">喜剧片</a></li>" +
            "<li><a href=\"https://cdn.example.com/4/\">科幻片</a></li>" +
            "<li><a href=\"https://cdn.example.com/8/\">恐怖片</a></li>" +
            "</ul></div></body></html>"
        val cats = GenericWebParser.parseHome(html, "https://www.mysite.example/").categories
        assertEquals(listOf("动作片", "剧情片", "喜剧片", "科幻片", "恐怖片"), cats.map { it.typeName })
    }

    /** 留言板不是栏目：兜底关里也不进分类。 */
    @Test fun guestBookFilteredInFallback() {
        val html = "<html><body><div id=\"menu\"><ul>" +
            "<li><a href=\"https://cdn.example.com/2/\">动作片</a></li>" +
            "<li><a href=\"https://cdn.example.com/support/GuestBook.php\">留言板</a></li>" +
            "</ul></div></body></html>"
        val cats = GenericWebParser.parseHome(html, "https://www.mysite.example/").categories
        assertEquals(listOf("动作片"), cats.map { it.typeName })
    }
}
