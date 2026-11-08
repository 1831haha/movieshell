package com.movieshell.domain.model

/**
 * v0.294.12：正在播放页布局 / 动态效果。
 * 经典=当前默认；唱片=圆形黑胶旋转；沉浸=大模糊封面氛围；
 * 频谱=封面下方律动条；卡片=紧凑小封面。
 */
enum class MusicPlayerLayout(
    val label: String,
    val description: String,
) {
    CLASSIC("经典", "封面边缘炫彩柔光脉冲"),
    VINYL("唱片", "黑胶旋转 + 炫彩反光扫光"),
    IMMERSIVE("沉浸", "流动炫彩极光氛围"),
    SPECTRUM("频谱", "炫彩多色律动频谱条"),
    CARD("炫彩", "旋转封面 + 轨道粒子光环"),
}
