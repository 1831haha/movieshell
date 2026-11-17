package com.movieshell.data.sharelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3.5：分享链接识别与人话格式化（纯函数）。 */
class ShareLinkTest {

    @Test
    fun platformOf_recognizesSixPlatforms() {
        assertEquals(SharePlatform.QUARK, ShareLink.platformOf("https://pan.quark.cn/s/94920962092b"))
        assertEquals(SharePlatform.QUARK, ShareLink.platformOf("https://drive.quark.cn/s/abc123"))
        assertEquals(SharePlatform.UC, ShareLink.platformOf("https://drive.uc.cn/s/abc123"))
        assertEquals(SharePlatform.BAIDU, ShareLink.platformOf("https://pan.baidu.com/s/1abcDEF"))
        assertEquals(SharePlatform.ALIYUN, ShareLink.platformOf("https://www.alipan.com/s/abc123"))
        assertEquals(SharePlatform.ALIYUN, ShareLink.platformOf("https://www.aliyundrive.com/s/abc123"))
        assertEquals(SharePlatform.GUANGYA, ShareLink.platformOf("https://guangyapan.com/s/1234567890123456789_abc"))
        assertEquals(SharePlatform.XUNLEI, ShareLink.platformOf("https://pan.xunlei.com/s/abc-123"))
    }

    @Test
    fun platformOf_unknownReturnsNull() {
        assertNull(ShareLink.platformOf("https://example.com/s/abc"))
        assertNull(ShareLink.platformOf(""))
        assertNull(ShareLink.platformOf("不是链接"))
    }

    @Test
    fun isShare_matchesPlatformOf() {
        assertTrue(ShareLink.isShare("https://pan.quark.cn/s/94920962092b"))
        assertFalse(ShareLink.isShare("https://pan.quark.cn/"))
    }

    @Test
    fun parse_extractsIdAndPwdQuery() {
        val (id, pwd) = ShareLink.parse("https://pan.quark.cn/s/94920962092b?pwd=ab12")!!
        assertEquals("94920962092b", id)
        assertEquals("ab12", pwd)
    }

    @Test
    fun parse_extractsChinesePasswordLabel() {
        val (_, pwd) = ShareLink.parse("https://pan.baidu.com/s/1abcDEF 密码:xyz9")!!
        assertEquals("xyz9", pwd)
    }

    @Test
    fun parse_noPasswordGivesEmpty() {
        val (id, pwd) = ShareLink.parse("https://drive.uc.cn/s/abc123")!!
        assertEquals("abc123", id)
        assertEquals("", pwd)
    }

    @Test
    fun parse_unknownReturnsNull() {
        assertNull(ShareLink.parse("https://example.com/abc"))
    }

    @Test
    fun isVideoName_matchesExtensions() {
        assertTrue(ShareLink.isVideoName("01.mp4"))
        assertTrue(ShareLink.isVideoName("第01集.MKV"))
        assertTrue(ShareLink.isVideoName("movie.ts"))
        assertFalse(ShareLink.isVideoName("海报.jpg"))
        assertFalse(ShareLink.isVideoName("文件夹"))
    }

    @Test
    fun humanSize_formats() {
        assertEquals("", ShareLink.humanSize(0))
        assertEquals("500 B", ShareLink.humanSize(500))
        assertEquals("1.0 KB", ShareLink.humanSize(1024))
        assertEquals("1.5 KB", ShareLink.humanSize(1536))
        assertEquals("5.1 GB", ShareLink.humanSize(5428969805L))
    }

    @Test
    fun humanDuration_formats() {
        assertEquals("", ShareLink.humanDuration(0))
        assertEquals("1:30", ShareLink.humanDuration(90))
        assertEquals("45:25", ShareLink.humanDuration(2725))
        assertEquals("1:01:01", ShareLink.humanDuration(3661))
    }
}
