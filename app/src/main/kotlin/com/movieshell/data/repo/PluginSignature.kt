package com.movieshell.data.repo

import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 插件签名校验（S1 修复的单一入口）。
 *
 * 信任锚：ECDSA P-256 公钥，只信签名不信 md5。md5 与 sha256
 * 仍可保留作辅助校验，但永远不能单独放行一个包。
 *
 * 两种签名，签法都在下面写明，市场发布方按此签：
 * - 插件包签名：对下载到的插件包文件本身的原始字节做
 *   SHA256withECDSA 签名，Base64 结果放 market.json 里该插件的
 *   signature 字段；sha256 字段如有，同步校验。
 * - 市场索引签名：对全部插件按市场顺序拼成的规范字符串做
 *   SHA256withECDSA 签名，Base64 结果放 market.json 顶层的
 *   signature 字段。规范字符串每插件 7 行，顺序固定为
 *   id、version、type、url、md5、sha256、signature，每行后换行。
 *
 * 镜像只作传输：不管包是从 raw、jsdelivr 还是第三方代理拉到的，
 * 验签都对包字节本身做，镜像改了包或改了索引里的签名都会验不过。
 *
 * 旧市场兼容处理（过渡态，用户 2026-10-29 定）：未签名、公钥未配置
 * 只警告不拦截——市场列表照常展示、插件照常安装，但调用方要把
 * 警告记日志并在安装时返回给用户；签名已提供但验签失败的，
 * 说明内容可能被篡改，仍一律拒绝。等市场发布方按上面的签法
 * 重新签名、公钥填好后，再收紧为未签名也拒绝。
 *
 * 公钥配置：PUBLIC_KEY_BASE64 现在是空占位，表示尚未配置。
 * 市场所有者离线生成一对 P-256 密钥，把公钥的 DER 编码做
 * Base64 后填到这里，私钥离线保管，绝不进仓库、不进聊天。
 * 填好公钥前，未签名内容只警告不拦截（见上），这是过渡态，
 * 不是 fail-closed，也不是放行已签名但验不过的内容。
 */
object PluginSignature {
    private const val TAG = "PluginSignature"

    /** 签名检查结果：已验过 / 未提供签名 / 公钥未配置 / 签名无效。 */
    enum class SignatureCheck { VERIFIED, UNSIGNED, UNCONFIGURED, INVALID }

    /** 尚未配置的公钥占位：填入市场所有者公钥的 Base64 DER 编码后才生效。 */
    private const val PUBLIC_KEY_BASE64 = ""

    fun isConfigured(): Boolean = PUBLIC_KEY_BASE64.isNotBlank()

    /** 规范字符串：市场索引签名要签的文本，发布方与 App 必须逐字一致。 */
    fun canonicalMarketText(
        plugins: List<PluginMarket.MarketPlugin>,
    ): String {
        val out = StringBuilder()
        for (p in plugins) {
            out.append(p.id).append('\n')
            out.append(p.version).append('\n')
            out.append(p.type).append('\n')
            out.append(p.url).append('\n')
            out.append(p.md5).append('\n')
            out.append(p.sha256).append('\n')
            out.append(p.signature).append('\n')
        }
        return out.toString()
    }

    /** 验市场索引签名：签名空白或公钥未配置时返回 false，调用方拒绝并提示。 */
    fun verifyMarketSignature(
        plugins: List<PluginMarket.MarketPlugin>,
        signatureBase64: String,
    ): Boolean {
        if (signatureBase64.isBlank()) return false
        return verify(
            canonicalMarketText(plugins).toByteArray(Charsets.UTF_8),
            signatureBase64,
        )
    }

    /** 市场索引签名检查：未签名与公钥未配置是警告态，签名无效才是拦截态。 */
    fun checkMarketSignature(
        plugins: List<PluginMarket.MarketPlugin>,
        signatureBase64: String,
    ): SignatureCheck {
        if (signatureBase64.isBlank()) return SignatureCheck.UNSIGNED
        if (!isConfigured()) return SignatureCheck.UNCONFIGURED
        return if (verifyMarketSignature(plugins, signatureBase64)) {
            SignatureCheck.VERIFIED
        } else {
            SignatureCheck.INVALID
        }
    }

    /** 插件包签名检查：与市场索引同口径，未签名与公钥未配置只警告。 */
    fun checkPackageSignature(file: File, signatureBase64: String): SignatureCheck {
        if (signatureBase64.isBlank()) return SignatureCheck.UNSIGNED
        if (!isConfigured()) return SignatureCheck.UNCONFIGURED
        return if (verifyPackageSignature(file, signatureBase64)) {
            SignatureCheck.VERIFIED
        } else {
            SignatureCheck.INVALID
        }
    }

    /** 验插件包签名：签名空白或公钥未配置时返回 false，调用方拒绝并提示。 */
    fun verifyPackageSignature(file: File, signatureBase64: String): Boolean {
        if (signatureBase64.isBlank()) return false
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        return verify(bytes, signatureBase64)
    }

    private fun verify(data: ByteArray, signatureBase64: String): Boolean {
        if (!isConfigured()) {
            LogBus.w(TAG, "签名公钥未配置，拒绝验签")
            return false
        }
        return runCatching {
            val keyBytes = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT)
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(key)
            sig.update(data)
            sig.verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        }.onFailure { LogBus.e(TAG, "签名校验异常：${it.message}") }
            .getOrDefault(false)
    }
}
