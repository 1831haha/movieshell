package com.movieshell.data.sync

import com.movieshell.data.store.MusicSource
import com.movieshell.domain.model.BookSource
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.33：完整备份升 version 2 的纯逻辑单测（不碰 Context/磁盘备份流程，
 * 只测版本接受与新段解析/重映射——importJson 的整单拒绝与回滚链在真机/云
 * 构建跑通后以手工备份→恢复验证为准，见汇报）。
 */
class BackupV2Test {

    private val filesDir = File(System.getProperty("java.io.tmpdir"), "movieshell-backup-v2-test")

    @Test
    fun versionAcceptsOneAndTwoOnly() {
        assertTrue(BackupManager.isSupportedBackupVersion(1))
        assertTrue(BackupManager.isSupportedBackupVersion(2))
        assertFalse(BackupManager.isSupportedBackupVersion(3))
        assertFalse(BackupManager.isSupportedBackupVersion(0))
        assertFalse(BackupManager.isSupportedBackupVersion(null))
    }

    @Test
    fun bookSourcesSection_validParses() {
        val src = BookSource(name = "全本小说网", url = "https://www.quanben.io")
        val el = Json.parseToJsonElement("[${src.toJson()}]")
        val parsed = BackupManager.parseBookSourcesSection(el)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.sources.size)
        assertEquals("www.quanben.io", parsed.sources[0].key)
    }

    @Test
    fun bookSourcesSection_badEntryRejects() {
        // 空 url 的书源 key 为空，整段拒绝（不能让坏段覆盖旧书源）。
        val el = Json.parseToJsonElement("""[{"bookSourceName": "坏源", "bookSourceUrl": ""}]""")
        assertNull(BackupManager.parseBookSourcesSection(el))
        assertNull(BackupManager.parseBookSourcesSection(Json.parseToJsonElement("{}")))
        // 重复 key 整段拒绝。
        val src = BookSource(name = "同名", url = "https://dup.example.com")
        val dup = Json.parseToJsonElement("[${src.toJson()}, ${src.toJson()}]")
        assertNull(BackupManager.parseBookSourcesSection(dup))
    }

    @Test
    fun musicSourcesSection_remapsLocalScriptUrl() {
        val el = buildJsonObject {
            put(
                "registry",
                Json.parseToJsonElement(
                    """[{"id": "s1", "name": "本地源", "url": "file:///data/old/files/lx_sources/abc.js", "builtin": false, "enabled": true, "addedAt": 0}]""",
                ),
            )
            put(
                "scripts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "abc.js")
                            put("content", "console.log(1)")
                        },
                    )
                },
            )
        }
        val parsed = BackupManager.parseMusicSourcesSection(filesDir, el)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.registry.size)
        assertEquals(
            "file://" + File(File(filesDir, "lx_sources"), "abc.js").absolutePath,
            parsed.registry[0].url,
        )
        assertEquals("abc.js", parsed.scripts[0].name)
    }

    @Test
    fun musicSourcesSection_badScriptNameRejects() {
        val el = buildJsonObject {
            put("registry", Json.parseToJsonElement("[]"))
            put(
                "scripts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "../evil.js")
                            put("content", "x")
                        },
                    )
                },
            )
        }
        assertNull(BackupManager.parseMusicSourcesSection(filesDir, el))
    }

    @Test
    fun ruleFilesSection_validAndBadName() {
        val el = buildJsonObject {
            put(
                "drpy",
                buildJsonObject {
                    put("name", "rule.js")
                    put("content", "var rule={}")
                },
            )
            put("py", Json.parseToJsonElement("null"))
            put("php", Json.parseToJsonElement("null"))
            put("web", Json.parseToJsonElement("[]"))
        }
        val parsed = BackupManager.parseRuleFilesSection(el)
        assertNotNull(parsed)
        assertEquals("rule.js", parsed!!.drpy!!.name)
        assertNull(parsed.py)
        assertTrue(parsed.web.isEmpty())

        // 规则文件名带路径穿越即整段拒绝。
        val bad = buildJsonObject {
            put(
                "drpy",
                buildJsonObject {
                    put("name", "sub/rule.js")
                    put("content", "x")
                },
            )
            put("py", Json.parseToJsonElement("null"))
            put("php", Json.parseToJsonElement("null"))
            put("web", Json.parseToJsonElement("[]"))
        }
        assertNull(BackupManager.parseRuleFilesSection(bad))
        // 网页源文件后缀不对也拒绝。
        val badWeb = buildJsonObject {
            put("drpy", Json.parseToJsonElement("null"))
            put("py", Json.parseToJsonElement("null"))
            put("php", Json.parseToJsonElement("null"))
            put(
                "web",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "site.exe")
                            put("content", "x")
                        },
                    )
                },
            )
        }
        assertNull(BackupManager.parseRuleFilesSection(badWeb))
    }

    @Test
    fun validRuleFileName_rules() {
        assertTrue(BackupManager.validRuleFileName("rule.js", ".js"))
        assertTrue(BackupManager.validRuleFileName("RULE.JS", ".js"))
        assertFalse(BackupManager.validRuleFileName("rule.py", ".js"))
        assertFalse(BackupManager.validRuleFileName("../rule.js", ".js"))
        assertFalse(BackupManager.validRuleFileName("a/b.js", ".js"))
        assertFalse(BackupManager.validRuleFileName("", ".js"))
        assertTrue(BackupManager.validWebFileName("site.json"))
        assertTrue(BackupManager.validWebFileName("page.html"))
        assertFalse(BackupManager.validWebFileName("page.php"))
    }

    @Test
    fun remapMusicSourceUrls_onlyRemapsBackedUpScripts() {
        val registry = listOf(
            MusicSource(id = "a", name = "本地", url = "file:///old/lx_sources/a.js"),
            MusicSource(id = "b", name = "悬空", url = "file:///old/lx_sources/missing.js"),
            MusicSource(id = "c", name = "远程", url = "https://example.com/x.js"),
        )
        val scripts = listOf(BackupManager.RuleFileEntry("a.js", "code"))
        val out = BackupManager.remapMusicSourceUrls(filesDir, registry, scripts)
        assertEquals(
            "file://" + File(File(filesDir, "lx_sources"), "a.js").absolutePath,
            out[0].url,
        )
        // 没备份脚本的悬空项原样保留（恢复后由 MusicSourceStore 自检标 scriptMissing）。
        assertEquals("file:///old/lx_sources/missing.js", out[1].url)
        assertEquals("https://example.com/x.js", out[2].url)
    }
}
