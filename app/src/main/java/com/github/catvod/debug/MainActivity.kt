package com.github.catvod.debug

/**
 * v0.8.0：tvkj.jar 里 spider 调试入口引用的宿主类（dex 实测有 23 处引用，
 * 方法名已混淆，均来自 spider 的调试代码，运行时不会走到）。
 * 放一个空壳，避免类校验时缺类，仅此而已——永远不会被实例化或启动。
 */
class MainActivity
