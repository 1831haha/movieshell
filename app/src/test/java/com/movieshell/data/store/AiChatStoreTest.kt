package com.movieshell.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v1.1.12：AiChatStore.decodeTextBytes——先认 BOM，再严格校验 UTF-8，
 * 不是合法 UTF-8 就按 GBK 解。GBK 向量是写死的字节，不靠编码器反推。
 */
class AiChatStoreTest {

    @Test fun utf8Chinese() {
        assertEquals("你好，世界", AiChatStore.decodeTextBytes("你好，世界".toByteArray(Charsets.UTF_8)))
    }

    @Test fun gbkChinese() {
        // “中文” 的 GBK 编码：中 = D6 D0，文 = CE C4。
        val gbk = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        val decoded = AiChatStore.decodeTextBytes(gbk)
        assertEquals("中文", decoded)
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test fun gbkLongerText() {
        val text = "这是一段中文规则说明，包含标点符号。"
        val decoded = AiChatStore.decodeTextBytes(text.toByteArray(charset("GBK")))
        assertEquals(text, decoded)
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test fun utf8BomStripped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray(Charsets.UTF_8)
        assertEquals("hi", AiChatStore.decodeTextBytes(bytes))
    }

    @Test fun utf16LeBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)
        assertEquals("hi", AiChatStore.decodeTextBytes(bytes))
    }

    @Test fun utf16BeBom() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hi".toByteArray(Charsets.UTF_16BE)
        assertEquals("hi", AiChatStore.decodeTextBytes(bytes))
    }

    @Test fun asciiPassthrough() {
        assertEquals("hello 123", AiChatStore.decodeTextBytes("hello 123".toByteArray(Charsets.US_ASCII)))
    }

    @Test fun emptyBytes() {
        assertEquals("", AiChatStore.decodeTextBytes(ByteArray(0)))
    }
}
