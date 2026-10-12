package com.movieshell.ui.data

/**
 * 演示用影片数据（占位，不联网）。
 * 接入真实站源后，用 Repository 返回的数据替换这里即可，
 * 界面只依赖 Vod 这个数据结构。
 */
data class Vod(
    val id: String,
    val title: String,
    val category: String,
    val year: String,
    val rating: String,
    val area: String,
    val actors: String,
    val desc: String,
    val episodes: Int,
    val poster: String = "",
)

object DemoData {

    val categories = listOf("电影", "剧集", "综艺", "动漫", "短剧")

    val vods = listOf(
        Vod(
            id = "v001", title = "星海追光者", category = "电影",
            year = "2026", rating = "8.6", area = "中国大陆", actors = "陈默 / 林晚",
            desc = "一支深空探测小队在返航途中收到神秘信号，循着星光一路追寻，" +
                "却发现信号的源头竟与他们出发的原因息息相关。",
            episodes = 1,
        ),
        Vod(
            id = "v002", title = "长安夜未央", category = "剧集",
            year = "2025", rating = "8.9", area = "中国大陆", actors = "沈昭 / 顾清辞",
            desc = "盛唐长安，一桩离奇命案牵出朝堂暗流，少年仵作与女官联手，" +
                "在灯火阑珊处抽丝剥茧，还长安一个清平夜。",
            episodes = 36,
        ),
        Vod(
            id = "v003", title = "荒野求生季", category = "综艺",
            year = "2026", rating = "8.2", area = "中国大陆", actors = "常驻嘉宾六人组",
            desc = "六位嘉宾深入无人之境，21 天极限生存挑战，" +
                "真实记录人类在荒野中的智慧与勇气。",
            episodes = 12,
        ),
        Vod(
            id = "v004", title = "云端之上", category = "动漫",
            year = "2026", rating = "9.1", area = "日本", actors = "CV：星野遥 / 苍井翼",
            desc = "少年在云层之上发现了漂浮的岛屿文明，" +
                "与天空少女一起，展开守护云海的冒险之旅。",
            episodes = 24,
        ),
        Vod(
            id = "v005", title = "重生之我在豪门当管家", category = "短剧",
            year = "2026", rating = "7.8", area = "中国大陆", actors = "陆言 / 苏棠",
            desc = "一朝重生回到入职第一天，看小管家如何凭先知先觉，" +
                "在豪门风云中逆袭翻盘。",
            episodes = 80,
        ),
        Vod(
            id = "v006", title = "深海回声", category = "电影",
            year = "2025", rating = "8.4", area = "美国", actors = "Jack M. / Elena R.",
            desc = "深海探测器捕捉到一段来自海底深渊的神秘回声，" +
                "科考队下潜探寻，却揭开了一段尘封百年的秘密。",
            episodes = 1,
        ),
        Vod(
            id = "v007", title = "人间烟火处", category = "剧集",
            year = "2026", rating = "8.7", area = "中国大陆", actors = "何其 / 温小满",
            desc = "老街拆迁前夕，几户人家的悲欢离合交织，" +
                "一碗人间烟火，抚慰平凡人生。",
            episodes = 28,
        ),
        Vod(
            id = "v008", title = "极速挑战", category = "综艺",
            year = "2025", rating = "8.0", area = "韩国", actors = "MC：金大成",
            desc = "明星车手与素人搭档，在专业赛道上展开极速对决，" +
                "肾上腺素拉满的竞技真人秀。",
            episodes = 10,
        ),
        Vod(
            id = "v009", title = "机关城传说", category = "动漫",
            year = "2025", rating = "8.8", area = "中国", actors = "CV：墨白 / 沈机",
            desc = "少年机关师误入千年机关城，解开层层机关谜题，" +
                "寻找传说中的永动之心。",
            episodes = 16,
        ),
        Vod(
            id = "v010", title = "替身新娘", category = "短剧",
            year = "2026", rating = "7.5", area = "中国大陆", actors = "江离 / 慕晚晴",
            desc = "为替姐姐还债嫁入豪门，本以为是交易婚姻，" +
                "却在朝夕相处中动了真心。",
            episodes = 60,
        ),
        Vod(
            id = "v011", title = "雾都孤影", category = "电影",
            year = "2024", rating = "8.3", area = "英国", actors = "Arthur B. / Lily S.",
            desc = "浓雾笼罩的伦敦，退休侦探接下最后一桩委托，" +
                "在迷雾中寻找真相，也寻找救赎。",
            episodes = 1,
        ),
        Vod(
            id = "v012", title = "少年歌行外传", category = "动漫",
            year = "2026", rating = "9.0", area = "中国", actors = "CV：原班人马",
            desc = "江湖再起风云，少年们仗剑天涯，" +
                "续写属于他们的热血篇章。",
            episodes = 26,
        ),
    )

    val hotSearches = listOf(
        "长安夜未央", "星海追光者", "重生之我在豪门当管家",
        "云端之上", "荒野求生季", "深海回声", "替身新娘", "雾都孤影",
    )

    fun find(id: String): Vod = vods.find { it.id == id } ?: vods.first()
}
