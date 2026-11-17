package com.movieshell.data.sharelink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.5：百度 wxlist 解析（纯函数部分）。
 * wxlist 本身打固定域名 pan.baidu.com，单测不发真请求；取直链要 SVIP 账号，真机验。
 */
class BaiduApiTest {

    @Test
    fun isDir_stringOneIsTrue() {
        assertTrue(BaiduApi.isDir(JSONObject().put("isdir", "1")))
        assertFalse(BaiduApi.isDir(JSONObject().put("isdir", "0")))
        assertFalse(BaiduApi.isDir(JSONObject()))
    }

    @Test
    fun toShareFile_mapsFields() {
        val f = JSONObject()
            .put("fs_id", "123456789")
            .put("server_filename", "01.mp4")
            .put("size", 987654321L)
            .put("isdir", "0")
            .put("path", "/分享/01.mp4")

        val file = BaiduApi.toShareFile(f, "")

        assertEquals("123456789", file.fid)
        assertEquals("01.mp4", file.name)
        assertEquals(987654321L, file.size)
        assertFalse(file.isDir)
        assertEquals("/分享/01.mp4", file.dirKey)
    }

    @Test
    fun toShareFile_dirRowIsDir() {
        val f = JSONObject()
            .put("fs_id", "999")
            .put("server_filename", "花絮")
            .put("size", 0L)
            .put("isdir", "1")
            .put("path", "/分享/花絮")

        val file = BaiduApi.toShareFile(f, "")

        assertTrue(file.isDir)
        assertEquals("花絮", file.name)
    }

    @Test
    fun toShareFile_prefixJoinsSubdirName() {
        val f = JSONObject()
            .put("fs_id", "5")
            .put("server_filename", "02.mp4")
            .put("size", 100L)
            .put("isdir", "0")
            .put("path", "/分享/花絮/02.mp4")

        assertEquals("花絮/02.mp4", BaiduApi.toShareFile(f, "花絮/").name)
    }
}
