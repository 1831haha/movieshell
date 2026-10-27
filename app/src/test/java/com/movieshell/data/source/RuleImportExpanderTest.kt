package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RuleImportExpander 单元测试（纯函数，不碰文件系统/WebView）。
 * v0.247.0（F33）：注释/字符串里的 import/export 不得误展开，且注释里的
 * import 不得占掉 visited 名额、导致后面真正的同文件 import 被跳过。
 */
class RuleImportExpanderTest {

    private val lib = "function b() {}\nconst LIB = 1;"
    private val resolve: (String) -> String? = { spec -> if (spec == "a.js") lib else null }

    @Test
    fun `line comment import is not expanded`() {
        val js = "// import './a.js';\nconst x = 1;"
        val r = RuleImportExpander.expand(js, resolve)
        assertTrue(r.js.contains("// import './a.js';"))
        assertFalse(r.js.contains("LIB"))
        assertTrue(r.inlined.isEmpty())
    }

    @Test
    fun `block comment import is not expanded and does not poison visited`() {
        val js = "/*\nimport './a.js';\n*/\nimport './a.js';\nconst x = 1;"
        val r = RuleImportExpander.expand(js, resolve)
        assertTrue(r.js.contains("/*\nimport './a.js';\n*/"))
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `template literal import is not expanded`() {
        val js = "const s = `hello\nimport './a.js';\nworld`;\nconst x = 1;"
        val r = RuleImportExpander.expand(js, resolve)
        assertTrue(r.js.contains("import './a.js';\nworld"))
        assertFalse(r.js.contains("LIB"))
        assertTrue(r.inlined.isEmpty())
    }

    @Test
    fun `interpolation state recovers for later real import`() {
        val js = "const s = `v=\${\"import './a.js';\"}`;\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `nested template tail stays non-code`() {
        val js = "const s = `a\${`b\${x}c`}d\nimport './a.js';\ne`;\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertTrue(r.js.contains("d\nimport './a.js';\ne"))
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `real import still expands with rename binding`() {
        val js = "import { b as c } from './a.js';\nconst x = c();"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("const c = b;"))
        assertFalse(r.js.contains("import {"))
    }

    @Test
    fun `regex char class with slash star does not swallow later import`() {
        val js = "const r = /[/*]/;\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `regex with escaped slashes and division do not break later import`() {
        val js = "const r = /https?:\\/\\//;\nconst x = a / b;\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `regex after if condition does not swallow later import`() {
        val js = "if (x) /[/*]/.test(s);\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `division after call does not break later import`() {
        val js = "const y = f(x) / 2;\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `throw regex does not swallow later import`() {
        val js = "throw /[/*]/.test(s);\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `regex statement after call on next line does not swallow later import`() {
        val js = "const y = f(x)\n/[/*]/.test(s);\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `export default regex survives stripping and later import expands`() {
        val js = "export default /[/*]/;\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("__exp_default_0"))
    }

    @Test
    fun `break newline regex does not swallow later import`() {
        val js = "while (true) { break\n/[/*]/.test(s); }\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `continue newline regex does not swallow later import`() {
        val js = "while (true) { continue\n/[/*]/.test(s); }\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `debugger newline regex does not swallow later import`() {
        val js = "debugger\n/[/*]/.test(s);\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `labeled break newline regex does not swallow later import`() {
        val js = "outer: while (true) { break outer\n/[/*]/.test(s); }\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `labeled continue newline regex does not swallow later import`() {
        val js = "outer: while (true) { continue outer\n/[/*]/.test(s); }\nimport './a.js';"
        val r = RuleImportExpander.expand(js, resolve)
        assertEquals(listOf("a.js"), r.inlined)
        assertTrue(r.js.contains("LIB"))
    }

    @Test
    fun `export inside block comment is not stripped`() {
        val js = "/*\nexport default function foo() {}\n*/\nconst x = 1;"
        val r = RuleImportExpander.expand(js, resolve)
        assertTrue(r.js.contains("/*\nexport default function foo() {}\n*/"))
    }
}
