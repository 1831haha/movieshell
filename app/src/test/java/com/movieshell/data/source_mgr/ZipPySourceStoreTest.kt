package com.movieshell.data.source_mgr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v1.1.14：压缩包源纯 zip 逻辑测试（JVM 能跑的部分；桥的 Python 部分测不了，等云构建与真机验）。 */
class ZipPySourceStoreTest {

    private fun makeZip(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val zip = File(dir, name)
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (path, bytes) ->
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zip
    }

    private fun spiderCode(className: String): ByteArray =
        ("class $className:\n    def homeContent(self):\n        return {}\n").toByteArray(Charsets.UTF_8)

    @Test
    fun `identity round trip`() {
        val id = ZipPySourceStore.zipIdentity("a.zip", "sub/b.py")
        assertEquals("zip:a.zip!sub/b.py", id)
        assertTrue(ZipPySourceStore.isZipIdentity(id))
        assertEquals("a.zip" to "sub/b.py", ZipPySourceStore.parseIdentity(id))
    }

    @Test
    fun `identity parse failures return null`() {
        assertFalse(ZipPySourceStore.isZipIdentity("a.py"))
        assertFalse(ZipPySourceStore.isZipIdentity("zip:a.zip"))
        assertNull(ZipPySourceStore.parseIdentity("a.py"))
        assertNull(ZipPySourceStore.parseIdentity("zip:a.zip"))
        assertNull(ZipPySourceStore.parseIdentity("zip:!b.py"))
        assertNull(ZipPySourceStore.parseIdentity("zip:a.zip!"))
    }

    @Test
    fun `listZips only zips sorted by name`() {
        val tmp = Files.createTempDirectory("zip-test").toFile()
        try {
            File(tmp, "b.zip").writeBytes(byteArrayOf(1))
            File(tmp, "a.zip").writeBytes(byteArrayOf(2))
            File(tmp, "c.txt").writeBytes(byteArrayOf(3))
            assertEquals(listOf("a.zip", "b.zip"), ZipPySourceStore.listZips(tmp))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `listZipRules filters entries and sorts by zip then entry`() {
        val tmp = Files.createTempDirectory("zip-test").toFile()
        try {
            makeZip(
                tmp, "b.zip",
                mapOf(
                    "z.py" to spiderCode("ZSpider"),
                    "a.py" to spiderCode("ASpider"),
                ),
            )
            makeZip(
                tmp, "a.zip",
                mapOf(
                    "sub/b.py" to spiderCode("BSpider"),
                    "a.py" to spiderCode("ASpider"),
                    "big.py" to ByteArray(3000) { 'x'.code.toByte() },
                    "__MACOSX/a.py" to spiderCode("MacSpider"),
                    ".hidden/c.py" to spiderCode("HiddenSpider"),
                    ".dotfile.py" to spiderCode("DotSpider"),
                    "d.txt" to "not python".toByteArray(),
                    "e.PY" to spiderCode("UpperSpider"),
                    "sub/" to ByteArray(0),
                ),
            )
            val rules = ZipPySourceStore.listZipRules(tmp)
            assertEquals(
                listOf(
                    "zip:a.zip!a.py",
                    "zip:a.zip!big.py",
                    "zip:a.zip!e.PY",
                    "zip:a.zip!sub/b.py",
                    "zip:b.zip!a.py",
                    "zip:b.zip!z.py",
                ),
                rules.map { it.name },
            )
            // 标题一律取 entry 文件名去掉 .py 后缀
            assertEquals(
                listOf("a", "big", "e", "b", "a", "z"),
                rules.map { it.title },
            )
            // 大小是 entry 未压缩大小除以 1024
            assertEquals(2L, rules.first { it.name == "zip:a.zip!big.py" }.sizeKb)
            assertEquals(0L, rules.first { it.name == "zip:a.zip!a.py" }.sizeKb)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `listZipEntries single zip`() {
        val tmp = Files.createTempDirectory("zip-test").toFile()
        try {
            makeZip(tmp, "only.zip", mapOf("x.py" to spiderCode("XSpider")))
            assertEquals(
                listOf("zip:only.zip!x.py"),
                ZipPySourceStore.listZipEntries(tmp, "only.zip").map { it.name },
            )
            assertTrue(ZipPySourceStore.listZipEntries(tmp, "missing.zip").isEmpty())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `hasSpiderSubclass reads first 4k head`() {
        val tmp = Files.createTempDirectory("zip-test").toFile()
        try {
            makeZip(
                tmp, "s.zip",
                mapOf(
                    "yes.py" to spiderCode("YesSpider"),
                    "no.py" to "print('hello')\n".toByteArray(),
                ),
            )
            assertTrue(
                ZipPySourceStore.hasSpiderSubclass(tmp, "zip:s.zip!yes.py"),
            )
            assertFalse(
                ZipPySourceStore.hasSpiderSubclass(tmp, "zip:s.zip!no.py"),
            )
            assertFalse(
                ZipPySourceStore.hasSpiderSubclass(tmp, "zip:s.zip!gone.py"),
            )
            assertFalse(ZipPySourceStore.hasSpiderSubclass(tmp, "not-an-identity"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `zipEntryExists checks zip and entry`() {
        val tmp = Files.createTempDirectory("zip-test").toFile()
        try {
            makeZip(tmp, "s.zip", mapOf("x.py" to spiderCode("XSpider")))
            assertTrue(ZipPySourceStore.zipEntryExists(tmp, "zip:s.zip!x.py"))
            assertFalse(ZipPySourceStore.zipEntryExists(tmp, "zip:s.zip!y.py"))
            assertFalse(ZipPySourceStore.zipEntryExists(tmp, "zip:gone.zip!x.py"))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
