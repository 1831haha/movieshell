package com.movieshell.data.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.2.5：主题收藏夹。
 *
 * 主题 = 种子色 + 品牌渐变 + 底图 + 深浅模式（深浅仍走 ThemeStore.mode 全局值，
 * 套用主题时不强制改模式；快照里记下当时的模式，点“应用”时才一并还原）。
 * 底图只在首页生效（与原“背景底图”一致），上面压半透明罩保可读（晚霞罩子更重）。
 * 皮肤工作室（编辑/复制/导出皮肤）这次不做。
 */
object ThemeCollectionStore {
    const val BG_NONE = "none"
    const val BG_CUSTOM = "custom"

    private const val PREFS = "movieshell_prefs"
    private const val KEY_BG = "theme_bg"
    private const val KEY_SAVED = "theme_collection_saved"
    private const val MAX_SAVED = 50

    /** 内置底图：id / 名 / assets/theme_bg 下的文件 / 配哪套主题 / 罩子加深量（晚霞更重）。 */
    data class ThemeBackground(
        val id: String,
        val name: String,
        val asset: String,
        val forTheme: String,
        val scrimBoost: Float = 0f,
    )

    val BACKGROUNDS = listOf(
        ThemeBackground("forest", "雾林", "bg-forest-misty.jpg", "空山新雨"),
        ThemeBackground("sunset", "晚霞", "bg-sunset-amber.jpg", "长河落日", scrimBoost = 0.10f),
        ThemeBackground("blossom", "花影", "bg-peach-blossom.jpg", "夭桃"),
        ThemeBackground("sky", "云海", "bg-sky-cloud.jpg", "天青"),
        ThemeBackground("aurora", "极光", "bg-aurora.jpg", "通用"),
    )

    /** 内置主题：id / 名 / 种子色 / 品牌渐变色带 / 底图 / 一行说明。 */
    data class BuiltinTheme(
        val id: String,
        val name: String,
        val seed: Int,
        val gradient: List<Int>,
        val backgroundId: String,
        val desc: String,
    )

    val BUILTIN_THEMES = listOf(
        BuiltinTheme(
            "kongshan", "空山新雨", 0xFF2E7D5B.toInt(),
            listOf(0xFFDFEEE6.toInt(), 0xFF9DC3AE.toInt(), 0xFF2E7D5B.toInt()),
            "bg:forest", "晨雾森林绿 · 底图「雾林」",
        ),
        BuiltinTheme(
            "changhe", "长河落日", 0xFFD9822B.toInt(),
            listOf(0xFFFDF0D5.toInt(), 0xFFF2C879.toInt(), 0xFFD9822B.toInt()),
            "bg:sunset", "暖阳琥珀 · 底图「晚霞」",
        ),
        BuiltinTheme(
            "yaotao", "夭桃", 0xFFE07878.toInt(),
            listOf(0xFFFDEEF0.toInt(), 0xFFF9C2C2.toInt(), 0xFFE07878.toInt()),
            "bg:blossom", "蜜桃粉 · 底图「花影」",
        ),
        BuiltinTheme(
            "tianqing", "天青", 0xFF5B9BD5.toInt(),
            listOf(0xFFE8F3FD.toInt(), 0xFFA9CDF0.toInt(), 0xFF5B9BD5.toInt()),
            "bg:sky", "极简亮蓝 · 底图「云海」",
        ),
    )

    /** 收藏/导入的主题快照：gradient 为 null = 单色模式。 */
    data class SavedTheme(
        val name: String,
        val seed: Int,
        val gradient: List<Int>?,
        val backgroundId: String,
        val mode: String,
    )

    fun bgId(assetId: String) = "bg:$assetId"

    fun backgroundDef(id: String): ThemeBackground? {
        if (!id.startsWith("bg:")) return null
        val want = id.removePrefix("bg:")
        return BACKGROUNDS.firstOrNull { it.id == want }
    }

    /** 底图名（设置页“背景底图”副标题用）：none=默认，custom=已自定义，bg:x=底图名。 */
    fun backgroundLabel(id: String): String = when (id) {
        BG_NONE -> "默认"
        BG_CUSTOM -> "已自定义"
        else -> backgroundDef(id)?.name ?: "默认"
    }

    fun scrimBoost(id: String): Float = backgroundDef(id)?.scrimBoost ?: 0f

    fun modeLabel(mode: String): String = when (mode) {
        ThemeStore.MODE_LIGHT -> "浅色"
        ThemeStore.MODE_DARK -> "深色"
        else -> "跟随系统"
    }

    private val _background = MutableStateFlow(BG_NONE)
    val background: StateFlow<String> = _background

    private val _saved = MutableStateFlow<List<SavedTheme>>(emptyList())
    val saved: StateFlow<List<SavedTheme>> = _saved

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _background.value = prefs.getString(KEY_BG, BG_NONE)?.takeIf { it == BG_NONE || it == BG_CUSTOM || backgroundDef(it) != null } ?: BG_NONE
        _saved.value = parseSaved(prefs.getString(KEY_SAVED, null))
    }

    fun setBackground(context: Context, id: String) {
        val safe = if (id == BG_NONE || id == BG_CUSTOM || backgroundDef(id) != null) id else BG_NONE
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BG, safe).apply()
        _background.value = safe
    }

    /** 套用内置主题：种子色 + 品牌渐变 + 底图；深浅模式不动。 */
    fun applyBuiltin(context: Context, theme: BuiltinTheme) {
        ThemeStore.setCustomGradient(context, theme.gradient, theme.seed)
        setBackground(context, theme.backgroundId)
    }

    /** 套用收藏/导入的主题：种子色 + 渐变（或单色）+ 底图 + 快照里的深浅模式。 */
    fun applySaved(context: Context, theme: SavedTheme) {
        if (theme.gradient.isNullOrEmpty()) ThemeStore.setColor(context, theme.seed)
        else ThemeStore.setCustomGradient(context, theme.gradient, theme.seed)
        setBackground(context, theme.backgroundId)
        ThemeStore.setMode(context, theme.mode)
    }

    /** 当前观感快照（名字自动编“我的主题 N”，不重名）。 */
    fun currentSnapshot(): SavedTheme {
        val n = generateSequence(1) { it + 1 }.first { i -> _saved.value.none { it.name == "我的主题 $i" } }
        return SavedTheme(
            name = "我的主题 $n",
            seed = ThemeStore.color.value,
            gradient = if (ThemeStore.gradientId.value == null) null else ThemeStore.brandGradient.value,
            backgroundId = _background.value,
            mode = ThemeStore.mode.value,
        )
    }

    /** 保存当前观感：满 50 个返回 false（调用方提示）。 */
    fun saveCurrent(context: Context): Boolean {
        if (_saved.value.size >= MAX_SAVED) return false
        val next = _saved.value + currentSnapshot()
        persistSaved(context, next)
        return true
    }

    fun deleteSaved(context: Context, theme: SavedTheme) {
        persistSaved(context, _saved.value - theme)
    }

    private fun persistSaved(context: Context, list: List<SavedTheme>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SAVED, arr.toString()).apply()
        _saved.value = list
    }

    fun toJson(theme: SavedTheme): JSONObject = JSONObject().apply {
        put("name", theme.name)
        put("seed", theme.seed)
        put("gradient", theme.gradient?.let { g -> JSONArray().apply { g.forEach { put(it) } } } ?: JSONObject.NULL)
        put("background", theme.backgroundId)
        put("mode", theme.mode)
    }

    /** 导入：单条对象、对象数组、或 {"themes":[...]}；成功返回条数，失败返回原因。 */
    fun importJson(context: Context, text: String): Result<Int> {
        val parsed = runCatching {
            val t = text.trim()
            when {
                t.startsWith("[") -> parseSaved(t)
                t.startsWith("{") -> {
                    val obj = JSONObject(t)
                    if (obj.has("themes")) parseSaved(obj.getJSONArray("themes").toString())
                    else listOf(parseOne(obj))
                }
                else -> throw IllegalArgumentException("不是主题 JSON")
            }
        }
        val list = parsed.getOrElse { return Result.failure(it) }
        if (list.isEmpty()) return Result.failure(IllegalArgumentException("里面没有可用的主题"))
        if (_saved.value.size + list.size > MAX_SAVED) return Result.failure(IllegalArgumentException("收藏已满 $MAX_SAVED 个，先删一些"))
        persistSaved(context, _saved.value + list)
        return Result.success(list.size)
    }

    private fun parseSaved(raw: String?): List<SavedTheme> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { runCatching { parseOne(arr.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun parseOne(obj: JSONObject): SavedTheme {
        val name = obj.optString("name", "").trim().ifEmpty { throw IllegalArgumentException("缺名字") }
        val seed = obj.getInt("seed")
        val gradient = if (obj.isNull("gradient")) null else {
            val arr = obj.getJSONArray("gradient")
            (0 until arr.length()).map { arr.getInt(it) }.takeIf { it.isNotEmpty() }
        }
        val background = obj.optString("background", BG_NONE).takeIf { it == BG_NONE || it == BG_CUSTOM || backgroundDef(it) != null } ?: BG_NONE
        val mode = obj.optString("mode", ThemeStore.MODE_SYSTEM).takeIf { it == ThemeStore.MODE_SYSTEM || it == ThemeStore.MODE_DARK || it == ThemeStore.MODE_LIGHT } ?: ThemeStore.MODE_SYSTEM
        return SavedTheme(name, seed, gradient, background, mode)
    }
}
