package com.movieshell.data.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 主题模式持久化：system（跟随系统）/ dark（深色）/ light（浅色），默认 system（v1.1.12 起跟随系统）。
 *
 * - mode：UI 层 collectAsState() 订阅，切换后全 App 即时生效；
 * - setMode()：写 SharedPreferences 并推送 flow。
 *
 * v0.12.0：加主题色（theme_color，存 ARGB Int），设置页色圈选择，全 App 主色跟随。
 */
object ThemeStore {
    const val MODE_SYSTEM = "system"
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"

    private const val PREFS = "movieshell_prefs"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_COLOR = "theme_color"
    /** v0.87.1：炫彩渐变主题 id；有值=渐变模式，无值=单色模式 */
    private const val KEY_GRADIENT = "theme_gradient"
    /** v1.2.5：自定义渐变色带（逗号分隔 ARGB），KEY_GRADIENT="custom" 时有效。 */
    private const val KEY_GRADIENT_COLORS = "theme_gradient_colors"
    const val GRADIENT_CUSTOM = "custom"

    /**
     * 默认主题色：v0.279.0 旧版 改为 经典蓝 #3D9BFF（原为青 #4DD0E1）；
     * v0.292.0 V5 Material You：改为设计稿种子色 #D9822B（暖琥珀），整套 M3 色板由它按 TonalSpot 生成。
     * 显式选过颜色的用户不受影响（存的是具体色值，按该色作种子生成色板）。
     */
    const val DEFAULT_COLOR = 0xFFD9822B.toInt()

    /** v0.279.0 的 经典蓝，V5 起作为普通预设保留。 */
    const val CLASSIC_BLUE = 0xFF3D9BFF.toInt()

    /** v0.292.0：色板来源——seed（默认，预设色/炫彩锚点作种子）/ poster（海报取色）/ dynamic（Android 12+ 系统壁纸取色）。 */
    const val SOURCE_SEED = "seed"
    const val SOURCE_POSTER = "poster"
    const val SOURCE_DYNAMIC = "dynamic"
    private const val KEY_SOURCE = "theme_color_source"

    /** DsPlayer 式候选色 */
    // v0.80.0：12 种主题色（对标 DsPlayer 我的页主题卡）
    val COLOR_OPTIONS = listOf(
        DEFAULT_COLOR, // v0.292.0：V5 琥珀种子（默认）
        CLASSIC_BLUE, // v0.279.0：经典蓝
        0xFF2B7DE9.toInt(), // 蓝
        0xFF3D5AFE.toInt(), // 靛蓝
        0xFF03A9F4.toInt(), // 天蓝
        0xFF00BFA5.toInt(), // 青
        0xFF00C853.toInt(), // 绿
        0xFFFFD600.toInt(), // 黄
        0xFFFF6D00.toInt(), // 橙
        0xFFFF3D00.toInt(), // 红
        0xFFFF4081.toInt(), // 粉
        0xFFAA00FF.toInt(), // 紫
        0xFF8D6E63.toInt(), // 棕
        0xFF9E9E9E.toInt(), // 灰
        // v0.87.1：炫彩组（霓虹高饱和）
        0xFFFF2E9F.toInt(), // 炫彩玫红
        0xFF00E5FF.toInt(), // 电光青
        0xFF76FF03.toInt(), // 荧光绿
        0xFFD500F9.toInt(), // 霓虹紫
        0xFFFF9100.toInt(), // 炫彩橙
        0xFFFF6E40.toInt(), // 珊瑚橙红
    )

    /** v0.294.11：预设色中文名（设置「主题」行副标题用）；未知返回 null。 */
    fun colorName(argb: Int): String? = when (argb) {
        DEFAULT_COLOR -> "琥珀"
        CLASSIC_BLUE -> "经典蓝"
        0xFF2B7DE9.toInt() -> "蓝"
        0xFF3D5AFE.toInt() -> "靛蓝"
        0xFF03A9F4.toInt() -> "天蓝"
        0xFF00BFA5.toInt() -> "青"
        0xFF00C853.toInt() -> "绿"
        0xFFFFD600.toInt() -> "黄"
        0xFFFF6D00.toInt() -> "橙"
        0xFFFF3D00.toInt() -> "红"
        0xFFFF4081.toInt() -> "粉"
        0xFFAA00FF.toInt() -> "紫"
        0xFF8D6E63.toInt() -> "棕"
        0xFF9E9E9E.toInt() -> "灰"
        0xFFFF2E9F.toInt() -> "炫彩玫红"
        0xFF00E5FF.toInt() -> "电光青"
        0xFF76FF03.toInt() -> "荧光绿"
        0xFFD500F9.toInt() -> "霓虹紫"
        0xFFFF9100.toInt() -> "炫彩橙"
        0xFFFF6E40.toInt() -> "珊瑚橙红"
        else -> null
    }

    private val _mode = MutableStateFlow(MODE_SYSTEM)
    val mode: StateFlow<String> = _mode

    private val _color = MutableStateFlow(DEFAULT_COLOR)
    val color: StateFlow<Int> = _color

    /**
     * v0.87.1：炫彩渐变预设。
     * - colors：渐变色带（ARGB），用于顶栏品牌线/弹窗描边/海报跑马灯等招牌渐变面；
     * - anchor：Material 主色锚点（单色），按钮/chip/开关等跟随它。
     */
    data class GradientPreset(
        val id: String,
        val name: String,
        val colors: List<Int>,
        val anchor: Int,
    )

    val GRADIENT_OPTIONS = listOf(
        GradientPreset(
            "aurora", "极光炫彩",
            listOf(0xFF22D3EE.toInt(), 0xFF818CF8.toInt(), 0xFFE879F9.toInt(), 0xFFFBBF24.toInt()),
            0xFF818CF8.toInt(),
        ),
        GradientPreset(
            "neon", "霓虹电光",
            listOf(0xFF00E5FF.toInt(), 0xFFD500F9.toInt(), 0xFFFF2E9F.toInt()),
            0xFFD500F9.toInt(),
        ),
        GradientPreset(
            "sunset", "日落鎏金",
            listOf(0xFFFF9100.toInt(), 0xFFFF2E9F.toInt(), 0xFF7C4DFF.toInt()),
            0xFFFF2E9F.toInt(),
        ),
        GradientPreset(
            "mint", "薄荷冰晶",
            listOf(0xFF76FF03.toInt(), 0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt()),
            0xFF00E5FF.toInt(),
        ),
    )

    private val _source = MutableStateFlow(SOURCE_SEED)
    /** v0.292.0：色板来源（见 SOURCE_*）。 */
    val source: StateFlow<String> = _source

    /**
     * v0.292.0：海报取色的种子（首页 Hero 当前海报算出来的主色，进程内有效，不落盘）；
     * null = 还没取到，主题按 [color] 兜底。
     */
    private val _posterSeed = MutableStateFlow<Int?>(null)
    val posterSeed: StateFlow<Int?> = _posterSeed

    fun setPosterSeed(argb: Int?) {
        _posterSeed.value = argb
    }

    private val _gradientId = MutableStateFlow<String?>(null)
    val gradientId: StateFlow<String?> = _gradientId

    /** 当前渐变色带（ARGB）；单色模式下为 null，UI 用默认品牌渐变 */
    private val _brandGradient = MutableStateFlow<List<Int>?>(null)
    val brandGradient: StateFlow<List<Int>?> = _brandGradient

    /** 选择器统一选中态："g:<id>" 渐变 / "c:<argb>" 单色 */
    private val _themeKey = MutableStateFlow("c:$DEFAULT_COLOR")
    val themeKey: StateFlow<String> = _themeKey

    private fun presetOf(id: String?): GradientPreset? =
        id?.let { want -> GRADIENT_OPTIONS.firstOrNull { it.id == want } }

    /** 启动时读回上次保存的模式和主题；未知值回退默认。 */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // v0.237.0：默认主题改浅色（跟参考图走）。
        // v0.279.0 旧版：默认改回 经典暗色——没显式存过模式的（新装、一直用默认的）
        // 拿到 经典暗色；显式选过亮色/跟随系统的（存了值）原样保留，不强制迁移。
        // v1.1.12：默认改回 跟随系统——没显式存过模式的（新装、一直用默认的）
        // 拿到 跟随系统；显式选过深色/浅色/跟随系统的（存了值）原样保留，不强制迁移。
        val saved = prefs.getString(KEY_MODE, MODE_SYSTEM)
            .takeIf { it == MODE_DARK || it == MODE_LIGHT || it == MODE_SYSTEM }
            ?: MODE_SYSTEM
        _mode.value = saved
        val savedGradient = prefs.getString(KEY_GRADIENT, null)
        val preset = presetOf(savedGradient)
        when {
            preset != null -> applyGradient(preset)
            // v1.2.5：自定义渐变（主题收藏夹内置主题/收藏快照用），色带另存 KEY_GRADIENT_COLORS。
            savedGradient == GRADIENT_CUSTOM -> {
                val colors = prefs.getString(KEY_GRADIENT_COLORS, null)
                    ?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() }
                if (colors != null) {
                    _gradientId.value = GRADIENT_CUSTOM
                    _brandGradient.value = colors
                    _color.value = prefs.getInt(KEY_COLOR, DEFAULT_COLOR)
                    _themeKey.value = "g:$GRADIENT_CUSTOM"
                } else {
                    applyColor(prefs.getInt(KEY_COLOR, DEFAULT_COLOR))
                }
            }
            else -> applyColor(prefs.getInt(KEY_COLOR, DEFAULT_COLOR))
        }
        // v0.292.0：海报取色 / 系统取色是叠在种子色上的来源开关（种子色仍保留作兜底）
        when (prefs.getString(KEY_SOURCE, SOURCE_SEED)) {
            SOURCE_POSTER -> applySource(SOURCE_POSTER)
            SOURCE_DYNAMIC -> if (android.os.Build.VERSION.SDK_INT >= 31) applySource(SOURCE_DYNAMIC)
        }
    }

    /** v0.292.0：选「海报取色」或「系统取色」（Android 12+）。选回任一预设色即回到种子模式。 */
    fun setSource(context: Context, source: String) {
        val s = when (source) {
            SOURCE_POSTER -> SOURCE_POSTER
            SOURCE_DYNAMIC -> if (android.os.Build.VERSION.SDK_INT >= 31) SOURCE_DYNAMIC else SOURCE_SEED
            else -> SOURCE_SEED
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE, s)
            .apply()
        if (s == SOURCE_SEED) {
            _source.value = SOURCE_SEED
            val g = _gradientId.value
            _themeKey.value = if (g != null) "g:$g" else "c:${_color.value}"
        } else {
            applySource(s)
        }
    }

    private fun applySource(s: String) {
        _source.value = s
        _themeKey.value = if (s == SOURCE_POSTER) "p:poster" else "d:dynamic"
    }

    private fun clearSource(context: Context) {
        if (_source.value != SOURCE_SEED) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SOURCE)
                .apply()
            _source.value = SOURCE_SEED
        }
    }

    fun setMode(context: Context, mode: String) {
        val m = when (mode) {
            MODE_DARK, MODE_LIGHT -> mode
            else -> MODE_SYSTEM
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, m)
            .apply()
        _mode.value = m
    }

    fun setColor(context: Context, argb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COLOR, argb)
            .remove(KEY_GRADIENT)
            .remove(KEY_GRADIENT_COLORS)
            .apply()
        clearSource(context)
        applyColor(argb)
    }

    fun setGradient(context: Context, id: String) {
        val preset = presetOf(id) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GRADIENT, id)
            .remove(KEY_GRADIENT_COLORS)
            .apply()
        clearSource(context)
        applyGradient(preset)
    }

    /**
     * v1.2.5：自定义渐变（主题收藏夹用）：色带 + 锚点色（种子色）一起存，
     * 恢复走 init 的 custom 分支；themeKey 记 "g:custom"。
     */
    fun setCustomGradient(context: Context, colors: List<Int>, anchor: Int) {
        val list = colors.take(4)
        if (list.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COLOR, anchor)
            .putString(KEY_GRADIENT, GRADIENT_CUSTOM)
            .putString(KEY_GRADIENT_COLORS, list.joinToString(","))
            .apply()
        clearSource(context)
        _gradientId.value = GRADIENT_CUSTOM
        _brandGradient.value = list
        _color.value = anchor
        _themeKey.value = "g:$GRADIENT_CUSTOM"
    }

    private fun applyColor(argb: Int) {
        _gradientId.value = null
        _brandGradient.value = null
        _color.value = argb
        _themeKey.value = "c:$argb"
    }

    private fun applyGradient(preset: GradientPreset) {
        _gradientId.value = preset.id
        _brandGradient.value = preset.colors
        _color.value = preset.anchor
        _themeKey.value = "g:${preset.id}"
    }
}
