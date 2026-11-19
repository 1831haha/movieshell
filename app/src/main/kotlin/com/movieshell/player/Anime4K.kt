package com.movieshell.player

import java.io.File

/**
 * v1.3.25 合并 5（DsPlayer 对照第 5 名）：Anime4K 画质增强——档位与着色器链（纯函数部分）。
 *
 * 着色器取自上游 bloc97/Anime4K（MIT，见 assets/anime4k/LICENSE-Anime4K.txt），
 * 链按上游 mpv 模板原样：VL 档 = Template/GLSL_Windows_High-end，
 * M 档 = Template/GLSL_Windows_Low-end，S 档 = 上游 Mode C
 *（Clamp + Restore_S + Upscale_S）。仅 libmpv 引擎生效——Media3
 * 不支持自定义 GLSL，设置页与齿轮页都明示这一点。
 *
 * 链分隔符必须是 ":"——沙箱 mpv 0.37 实测：set glsl-shaders 用 ":"
 * 拆成多项，用 ";" 会被当成一条带分号的路径（上游 mpv.conf 模板里的
 * ";" 是配置文件解析层的写法，property 接口不认）。
 */
object Anime4K {

    /** 画质增强档位：key 存 PlayerSettings，label 做 UI 文案。 */
    enum class Mode(val key: String, val label: String) {
        OFF("off", "关"),
        S("s", "轻量"),
        M("m", "均衡"),
        VL("vl", "画质"),
    }

    fun fromKey(key: String): Mode = Mode.values().firstOrNull { it.key == key } ?: Mode.OFF

    /** 某档用的着色器文件名（按上游模板顺序）。 */
    fun shaderFiles(mode: Mode): List<String> = when (mode) {
        Mode.OFF -> emptyList()
        Mode.S -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Mode.M -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Mode.VL -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_VL.glsl",
            "Anime4K_Upscale_CNN_x2_VL.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
        )
    }

    /** 全部 9 个着色器文件名（首次安装时从 assets 拷到 filesDir 用）。 */
    val ALL_SHADER_FILES: List<String> =
        (shaderFiles(Mode.S) + shaderFiles(Mode.M) + shaderFiles(Mode.VL)).distinct()

    /** 某档的 glsl-shaders 属性值（绝对路径，":" 连接；OFF 为空串）。 */
    fun shaderChain(dir: File, mode: Mode): String =
        shaderFiles(mode).joinToString(":") { File(dir, it).absolutePath }
}
