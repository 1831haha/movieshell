package com.movieshell.core.util

import com.movieshell.data.source.PluginMarket
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.294.48 插件签名回归：公钥一旦填错一个字符，官方插件安装会全军
 * 覆没（验签异常一律按失败算），本地又编不了，所以用云构建的单测
 * 把“公钥 + 验签逻辑 + 脚本规范串”三者一次性钉住。
 *
 * 两个签名都是用官方私钥签出来的固定值（ECDSA 签名本身是公开信息，
 * 不是密钥，贴进单测无妨）：
 * - 包签名：对 "movieshell plugin signature test" 这串字节签的。
 * - 索引签名：对下面 dummy 插件按 PluginSignature.canonicalMarketText
 *   拼出的规范串签的——如果 sign_market.py 的拼串顺序与 App 不一致，
 *   这个用脚本口径签出来的签名在 App 口径下就会验不过，单测立刻红。
 * 注意：官方密钥轮换时这两个签名必须随新私钥重签，否则单测会红，
 * 那是预期的，不代表 App 坏了。
 */
class PluginSignatureTest {

    private val packageData = "movieshell plugin signature test"
    private val packageSignature =
        "MEQCIAWq4bgf4NsgXVnEhDfRB3V+Ub/31eXtRlFOa5wfIpPnAiBKLNG2io2abDWdhsk1ZyTs33Cqk3ayD6xDppxmeCYbvA=="
    private val marketSignature =
        "MEUCIG1rjkuLuO5zg6vtirglWeTvheQik011fNqahpPqCKGTAiEAnXWCmP/ZwCEM+lq6NGAmC1J1LR0EUczNe2kCifGpktY="

    private fun dummyPlugin() = PluginMarket.MarketPlugin(
        id = "test-plugin",
        name = "Test Plugin",
        version = "1.0.0",
        type = "source",
        engine = "py",
        md5 = "d41d8cd98f00b204e9800998ecf8427e",
        sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        signature = "test-package-signature",
        url = "https://example.com/packages/test-plugin-1.0.0.zip",
        desc = "",
        author = "",
        size = 0,
        iconUrl = "",
        tags = emptyList(),
        changelog = "",
        platforms = listOf("android"),
        category = "app",
        minApp = "",
        abis = listOf("arm64-v8a"),
    )

    private fun tempFileWith(text: String): File {
        val f = File.createTempFile("sig_test_", ".bin")
        f.writeText(text)
        return f
    }

    @Test fun publicKeyIsConfigured() {
        assertTrue(PluginSignature.isConfigured())
    }

    @Test fun packageSignatureVerifies() {
        val f = tempFileWith(packageData)
        try {
            assertEquals(
                PluginSignature.SignatureCheck.VERIFIED,
                PluginSignature.checkPackageSignature(f, packageSignature),
            )
        } finally {
            f.delete()
        }
    }

    @Test fun tamperedPackageFails() {
        val f = tempFileWith(packageData + "tampered")
        try {
            assertEquals(
                PluginSignature.SignatureCheck.INVALID,
                PluginSignature.checkPackageSignature(f, packageSignature),
            )
        } finally {
            f.delete()
        }
    }

    @Test fun marketSignatureVerifiesWithScriptCanonicalText() {
        val plugins = listOf(dummyPlugin())
        assertEquals(
            PluginSignature.SignatureCheck.VERIFIED,
            PluginSignature.checkMarketSignature(plugins, marketSignature),
        )
        // 规范串本身也要和脚本口径一致：逐行核对，不只看验签结果。
        val expected = "test-plugin\n" +
            "1.0.0\n" +
            "source\n" +
            "https://example.com/packages/test-plugin-1.0.0.zip\n" +
            "d41d8cd98f00b204e9800998ecf8427e\n" +
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n" +
            "test-package-signature\n"
        assertEquals(expected, PluginSignature.canonicalMarketText(plugins))
    }

    @Test fun wrongMarketSignatureFails() {
        val plugins = listOf(dummyPlugin())
        assertEquals(
            PluginSignature.SignatureCheck.INVALID,
            PluginSignature.checkMarketSignature(plugins, packageSignature),
        )
    }

    @Test fun blankSignatureIsUnsignedNotInvalid() {
        val plugins = listOf(dummyPlugin())
        assertEquals(
            PluginSignature.SignatureCheck.UNSIGNED,
            PluginSignature.checkMarketSignature(plugins, ""),
        )
        val f = tempFileWith(packageData)
        try {
            assertEquals(
                PluginSignature.SignatureCheck.UNSIGNED,
                PluginSignature.checkPackageSignature(f, ""),
            )
        } finally {
            f.delete()
        }
    }
}
