package com.movieshell.data.ai

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.data.source_mgr.PhpSourceManager
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.data.source_mgr.WebParseSourceManager
import com.movieshell.data.store.SourceStateStore
import com.movieshell.player.PlaySession
import com.movieshell.player.PlayerCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
* v1.3.25 合并 3（AI Agent 工具化操控）：App 动作总线。
*
* 侠影已有 LlmClient（五家服务商）与 AI 写源，缺的是“让 AI 动手操作
* App”的一层：动作注册表 + 分发 + 给模型看的工具说明。
* LlmClient 没有原生 function-calling（三种 provider 形态各异），
* 这里用跨 provider 都通的 JSON 代码块协议：模型回一个 ```action
* 代码块，AiAgent 解析后调 dispatch，执行结果再发回模型（见 AiAgent）。
*
* 白名单（v1，只读 + 轻量操作，全可逆、无删除、无改配置）：
* 播放（暂停/继续）、切集（上/下一集）、seek、倍速、搜片、换源。
* 需要 UI 上下文的走命令流解耦（PlayerCommands / NavCommands /
* SearchQueryBus），UI 层订阅执行；换源直接调管理器（与源管理页
* activateDrpy / activatePy 同一套序列：清其他系 → 激活 → 落盘）。
* 每步执行结果写 LogBus（tag AiAgent），对话里也向用户明示。
*/
object AppActionBus {

data class ParamDef(val name: String, val type: String, val required: Boolean, val description: String)

data class ActionDef(val id: String, val description: String, val params: List<ParamDef> = emptyList())

data class ActionCall(val id: String, val args: Map<String, String>)

sealed interface ActionResult {
val summary: String
data class Ok(override val summary: String): ActionResult
data class Fail(override val summary: String): ActionResult
}

fun definitions(): List<ActionDef> = listOf(
ActionDef("pause", "暂停当前播放"),
ActionDef("resume", "继续播放"),
ActionDef("next_episode", "播放下一集"),
ActionDef("prev_episode", "播放上一集"),
ActionDef("seek", "跳到指定位置", listOf(ParamDef("seconds", "数字", true, "秒数，如 90"))),
ActionDef("set_speed", "改倍速", listOf(ParamDef("speed", "数字", true, "0.25 到 4，如 1.5"))),
ActionDef("search_video", "搜片并打开搜索页", listOf(ParamDef("query", "文字", true, "片名，如 谷雨"))),
ActionDef("switch_source", "切换视频源", listOf(ParamDef("name", "文字", true, "源名或源显示名"))),
)

/** 给模型看的操作说明（以 user 角色消息发，Anthropic 的 messages 里不接受 system 角色）。 */
fun toolsPrompt(): String = buildString {
    appendLine("你可以直接操作这个 App（播放器与搜索）。想操作时，只回一个 ```action 代码块，里面是 JSON：")
    appendLine("```action")
    appendLine("{\"action\": \"pause\", \"args\": {}}")
    appendLine("```")
    appendLine("可用动作（一次只做一个）：")
    for (def in definitions()) {
        val params = if (def.params.isEmpty()) "" else "，参数：" + def.params.joinToString("、") { p ->
            p.name + "（" + p.type + "，" + (if (p.required) "必填" else "可选") + "，" + p.description + "）"
        }
        appendLine("- " + def.id + "：" + def.description + params)
    }
    appendLine("不需要操作时直接回文字，不用发代码块。")
}

/**
* 从模型回复里抠第一个 ```action 代码块；没有、JSON 不合法、
* 缺 action 字段时返回 null（调用方按普通文字回复处理）。
*/
fun parseActionCall(text: String): ActionCall? {
val start = text.indexOf("```action")
if (start < 0) return null
val bodyStart = text.indexOf('\n', start)
if (bodyStart < 0) return null
val end = text.indexOf("```", bodyStart + 1)
if (end < 0) return null
val json = text.substring(bodyStart + 1, end).trim()
if (json.isEmpty()) return null
return runCatching {
val obj = JSONObject(json)
val id = obj.optString("action").trim()
if (id.isEmpty()) return@runCatching null
val argsObj = obj.optJSONObject("args")
val args = mutableMapOf<String, String>()
if (argsObj!= null) {
val keys = argsObj.keys()
while (keys.hasNext()) {
val key = keys.next()
args[key] = argsObj.opt(key)?.toString().orEmpty()
}
}
ActionCall(id, args)
}.getOrNull()
}

/**
* 执行一个动作。context 只供 switch_source 用（换源要读写
* SharedPreferences 与规则文件），其他动作传 null 即可。
*/
suspend fun dispatch(context: Context?, id: String, args: Map<String, String>): ActionResult {
val result = when (id) {
"pause" -> postOrFail(PlayerCommands.Command.Pause, "已暂停")
"resume" -> postOrFail(PlayerCommands.Command.Resume, "已继续播放")
"next_episode" -> nextEpisode()
"prev_episode" -> prevEpisode()
"seek" -> {
val seconds = args["seconds"]?.toLongOrNull()
when {
seconds == null -> ActionResult.Fail("没给秒数，请说跳到第几秒")
seconds < 0 -> ActionResult.Fail("秒数不能是负数")
else -> postOrFail(PlayerCommands.Command.SeekTo(seconds * 1000), "已跳到 ${seconds} 秒处")
}
}
"set_speed" -> {
val speed = args["speed"]?.toFloatOrNull()
when {
speed == null -> ActionResult.Fail("没给倍速，请说几倍速")
speed < 0.25f || speed > 4f -> ActionResult.Fail("倍速只支持 0.25 到 4 倍")
else -> postOrFail(PlayerCommands.Command.SetSpeed(speed), "倍速已设为 ${speed}X")
}
}
"search_video" -> {
val query = args["query"]?.trim().orEmpty()
if (query.isEmpty()) {
ActionResult.Fail("没给片名，请说搜什么")
} else if (NavCommands.post(NavCommands.Command.Search(query))) {
ActionResult.Ok("正在搜「$query」")
} else {
ActionResult.Fail("搜片命令没送达，请打开搜索页再试")
}
}
"switch_source" -> switchSource(context, args["name"]?.trim().orEmpty())
else -> ActionResult.Fail("不支持的动作：$id")
}
LogBus.i("AiAgent", "动作 $id 参数 $args → ${result.summary}")
return result
}

private fun postOrFail(command: PlayerCommands.Command, okSummary: String): ActionResult =
if (PlayerCommands.post(command)) ActionResult.Ok(okSummary)
else ActionResult.Fail("播放器不在前台，命令没送达，请打开播放器再试")

private fun nextEpisode(): ActionResult {
val line = PlaySession.currentLine()?: return ActionResult.Fail("当前没有在播的剧集")
val next = PlaySession.episodeIndex + 1
if (line.episodes.getOrNull(next) == null) return ActionResult.Fail("已经是最后一集了")
return postOrFail(PlayerCommands.Command.NextEpisode, "正在播放下一集")
}

private fun prevEpisode(): ActionResult {
if (PlaySession.lines.isEmpty()) return ActionResult.Fail("当前没有在播的剧集")
if (PlaySession.episodeIndex <= 0) return ActionResult.Fail("已经是第一集了")
return postOrFail(PlayerCommands.Command.PrevEpisode, "正在播放上一集")
}

private data class SourceCandidate(val family: String, val fileName: String, val title: String)

/**
* 换源：与源管理页 activateDrpy / activatePy 同一套序列
* （清其他系 → 激活 → 落盘 SourceStateStore），失败时按原样回滚。
* 读规则目录与建引擎放 IO 线程；匹配先精确（文件名/显示名/去后缀名）
* 再包含，多个命中时让用户说全名，不猜。
*/
private suspend fun switchSource(context: Context?, name: String): ActionResult {
if (context == null) return ActionResult.Fail("换源需要 App 上下文")
if (name.isBlank()) return ActionResult.Fail("没给源名，请说换成哪个源")
return withContext(Dispatchers.IO) {
val candidates = ArrayList<SourceCandidate>()
runCatching { DrpySourceManager.listRules()}.getOrNull().orEmpty().forEach {
candidates += SourceCandidate("js", it.name, it.title)
}
runCatching { PySourceManager.listRules()}.getOrNull().orEmpty().forEach {
candidates += SourceCandidate("py", it.name, it.title)
}
if (candidates.isEmpty()) return@withContext ActionResult.Fail("一个可用源都没找到")
val exact = candidates.filter {
it.fileName.equals(name, ignoreCase = true) ||
it.title.equals(name, ignoreCase = true) ||
it.fileName.substringBeforeLast('.').equals(name, ignoreCase = true)
}
val matched = if (exact.isNotEmpty()) exact else candidates.filter {
it.title.contains(name, ignoreCase = true) ||
it.fileName.contains(name, ignoreCase = true)
}
when {
matched.isEmpty() -> ActionResult.Fail("没找到叫「$name」的源")
matched.size > 1 -> ActionResult.Fail(
"找到多个匹配的源：${matched.take(3).joinToString("、") { it.title}}，请说全名",
)
else -> activateSource(context, matched[0])
}
}
}

private suspend fun activateSource(context: Context, target: SourceCandidate): ActionResult {
val prevDrpy = DrpySourceManager.activeRuleName()
val prevPy = PySourceManager.activeRuleName()
val prevPhp = PhpSourceManager.activeRuleName()
val prevWebParse = WebParseSourceManager.activeSite()?.fileName
SubSiteStore.clearCurrent(context)
DrpySourceManager.setActiveRule(null)
PySourceManager.setActiveRule(null)
PhpSourceManager.setActiveRule(null)
WebParseSourceManager.setActiveSite(null)
val ok = if (target.family == "js") {
DrpySourceManager.setActiveRuleAsync(target.fileName)
} else {
PySourceManager.setActiveRule(target.fileName)
}
if (!ok) {
if (prevDrpy!= null) DrpySourceManager.setActiveRuleAsync(prevDrpy)
else if (prevPy!= null) PySourceManager.setActiveRule(prevPy)
else if (prevPhp!= null) PhpSourceManager.setActiveRule(prevPhp)
else if (prevWebParse!= null) WebParseSourceManager.setActiveByFileName(prevWebParse)
return ActionResult.Fail("切换失败：规则文件不存在或加载失败")
}
SourceStateStore.setRuleEnabled(context, target.family, target.fileName, true)
SourceStateStore.saveActiveRule(context, target.family, target.fileName)
return ActionResult.Ok("已切换到「${target.title}」")
}
}
