package com.movieshell.core.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.28：safeDst 回归测试（不碰 Android：只用 java.io.File 的
 * canonicalPath）。此前只认“dir/ 前缀”，workDir "."（clash 的
 * server.json 就是 "."）被误判成 zip-slip，服务启动直接抛
 * “插件包路径非法：.”；逃逸的真义是跑到 dir 外面，等于 dir 本身合法。
 */
class ZipInstallTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ziptest").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun safeDst_dotIsTheDirItself() {
        withTempDir { dir ->
            assertEquals(dir.canonicalFile, ZipInstall.safeDst(dir, ".").canonicalFile)
        }
    }

    @Test
    fun safeDst_dotSlashIsTheDirItself() {
        withTempDir { dir ->
            assertEquals(dir.canonicalFile, ZipInstall.safeDst(dir, "./").canonicalFile)
        }
    }

    @Test
    fun safeDst_plainChildInside() {
        withTempDir { dir ->
            val dst = ZipInstall.safeDst(dir, "sub/file.txt")
            assertTrue(dst.canonicalPath.startsWith(dir.canonicalPath + File.separator))
        }
    }

    @Test
    fun safeDst_dotDotThatStaysInsideIsAllowed() {
        withTempDir { dir ->
            val dst = ZipInstall.safeDst(dir, "sub/../file.txt")
            assertEquals(File(dir, "file.txt").canonicalPath, dst.canonicalPath)
        }
    }

    @Test
    fun safeDst_parentEscapeRejected() {
        withTempDir { dir ->
            assertThrows(IllegalStateException::class.java) {
                ZipInstall.safeDst(dir, "../evil")
            }
        }
    }

    @Test
    fun safeDst_nestedEscapeRejected() {
        withTempDir { dir ->
            assertThrows(IllegalStateException::class.java) {
                ZipInstall.safeDst(dir, "sub/../../evil")
            }
        }
    }

    @Test
    fun safeDst_absolutePathRejected() {
        withTempDir { dir ->
            assertThrows(IllegalStateException::class.java) {
                ZipInstall.safeDst(dir, "/etc/passwd")
            }
        }
    }
}
