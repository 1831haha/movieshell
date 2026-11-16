package com.movieshell.data.store

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.14：EmbyServer 新字段（备注 / 忽略证书验证）的旧数据兼容测试。
 * 老版本存的 JSON 里没有 remark / ignoreCert，解码必须用默认值补齐、不丢旧服务器。
 */
class EmbyServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old json without new fields decodes with defaults`() {
        val server = json.decodeFromString<EmbyServer>(
            """{"id":"1","name":"家里","type":"emby","baseUrl":"http://192.168.1.10:8096","username":"u","authMode":"password","token":"t","userId":"uid"}""",
        )
        assertEquals("", server.remark)
        assertFalse(server.ignoreCert)
        assertEquals("http://192.168.1.10:8096", server.baseUrl)
    }

    @Test
    fun `new json keeps remark and ignoreCert`() {
        val server = json.decodeFromString<EmbyServer>(
            """{"id":"1","name":"家里","type":"jellyfin","baseUrl":"https://x.example.com/proxy","username":"u","authMode":"password","token":"t","userId":"uid","remark":"宿舍小主机","ignoreCert":true}""",
        )
        assertEquals("宿舍小主机", server.remark)
        assertTrue(server.ignoreCert)
        assertEquals("jellyfin", server.type)
    }
}
