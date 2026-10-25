package com.movieshell.data.novel

import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * v0.35.0 TXT 小说导入。
 *
 * - 章节切分：按行匹配 `^第[一二三四五六七八九十百千万0-9]+[章节回集]`；
 * - 第一章之前的文字算简介（desc）；
 * - 一章没匹配到：整本算一章，标题=书名；
 * - 书名=文件名去扩展名；id=随机 UUID。
 *
 * 编码：先试 UTF-8，失败换 GBK（中文 TXT 常见）。
 */
object TxtImporter {

    private val chapterRegex = Regex("^第[一二三四五六七八九十百千万0-9]+[章节回集]")

    data class Imported(val novel: Novel, val chapters: List<Chapter>)

    fun import(file: File): Imported? {
        val text = readTextSmart(file) ?: return null
        if (text.isBlank()) return null
        val title = file.nameWithoutExtension.ifBlank { "未知书名" }
        val novelId = UUID.randomUUID().toString().replace("-", "")

        val chapters = mutableListOf<Chapter>()
        var descBuilder = StringBuilder()
        var curTitle: String? = null
        var curBody = StringBuilder()

        fun flush() {
            if (curTitle != null) {
                chapters += Chapter(
                    novelId = novelId,
                    index = chapters.size,
                    title = curTitle!!.trim(),
                    content = curBody.toString().trim(),
                )
            }
            curBody = StringBuilder()
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            // v0.215.0：首章与后续章同口径——标题行都要求短（≤40 字），
            // 否则正文里一句“第一章……”也会把首章切错位置。
            if (curTitle == null && chapterRegex.containsMatchIn(line.trimStart()) && line.trim().length <= 40) {
                // 第一章标题出现：之前攒的是简介
                curTitle = line.trim()
            } else if (curTitle != null && chapterRegex.containsMatchIn(line.trimStart()) && line.trim().length <= 40) {
                // 新章节（标题行一般很短，避免正文里"第一章"误切）
                flush()
                curTitle = line.trim()
            } else {
                if (curTitle == null) {
                    if (descBuilder.length < 2000) descBuilder.appendLine(line)
                } else {
                    curBody.appendLine(line)
                }
            }
        }
        flush()

        // 一章没切出来：整本一章
        val finalChapters = if (chapters.isEmpty()) {
            listOf(Chapter(novelId = novelId, index = 0, title = title, content = text.trim()))
        } else chapters

        val desc = descBuilder.toString().trim().take(500).ifBlank { null }
        val novel = Novel(
            id = novelId,
            sourceKey = "local",
            title = title,
            author = null,
            cover = null,
            desc = desc,
            totalChapters = finalChapters.size,
            addedAt = System.currentTimeMillis(),
        )
        return Imported(novel, finalChapters)
    }

    private fun readTextSmart(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        // v0.215.0：旧写法 file.readText(UTF_8) 遇到非法字节从不抛异常，
        // 只会塞替换字符硬读，GBK 回退是死代码——GBK 中文 TXT 全读成乱码。
        // 改严格解码：UTF-8 非法才落 GBK。
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val utf8 = runCatching { strict.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        if (!utf8.isNullOrBlank()) return utf8
        return runCatching { String(bytes, charset("GBK")) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
