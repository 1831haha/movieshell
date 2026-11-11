package com.movieshell.data.source_mgr

/**
 * v1.1.25：网盘中心页六家（扫码登录＋清除 Cookie 一一对应）。
 *
 * 登录态存放分两类：夸克/UC/百度存 Cookie（filesDir/<dir>/cookie.txt），
 * 阿里/光鸭/迅雷存 token（filesDir/<dir>/token.json）；中心页据此显示
 * 登录状态。badgeChar 是宫格徽标里的单字，颜色在 UI 层按品牌定。
 */
enum class PanProvider(
    val dirName: String,
    val displayName: String,
    val appName: String,
    val badgeChar: String,
) {
    QUARK("quark", "夸克", "夸克", "夸"),
    UC("uc", "UC", "UC", "U"),
    BAIDU("baidu", "百度", "百度", "百"),
    ALIYUN("aliyun", "阿里云盘", "阿里云盘", "阿"),
    GUANGYA("guangya", "光鸭", "光鸭", "光"),
    XUNLEI("xunlei", "迅雷", "迅雷", "迅"),
}
