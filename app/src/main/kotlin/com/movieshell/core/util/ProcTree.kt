package com.movieshell.core.util

/**
 * v1.3.28：按 /proc 枚举进程树的纯函数（不碰 Android，可单测）。
 *
 * 背景：Android 的 java.lang.Process 没有 pid()/toHandle()，
 * java.lang.ProcessHandle 类也不在 android.jar 里——杀进程树只能
 * 走 /proc：读 /proc/<pid>/task/<pid>/children 递归收集后代，
 * 再用 android.os.Process.killProcess(pid) 逐个杀（同 UID 可杀）。
 * 扫描与 kill 的实现在 ServicePluginManager.stopLocked 里，
 * 这里只放可纯测的解析与收集。
 */

/** 解析 /proc/<pid>/task/<pid>/children 的内容（空格分隔的 pid 列表，可带换行/尾空格）。 */
internal fun parseProcChildren(text: String): List<Int> =
    text.split(' ', '\n', '\t', '\r').mapNotNull { it.toIntOrNull() }

/**
 * 递归收集 root 的全部后代（前序；visited 防环）。
 * childrenOf 由调用方提供（生产走 /proc 读文件，测试喂假表）。
 */
internal fun collectProcDescendants(
    root: Int,
    childrenOf: (Int) -> List<Int>,
): List<Int> {
    val out = mutableListOf<Int>()
    val seen = mutableSetOf<Int>()
    fun walk(pid: Int) {
        for (child in childrenOf(pid)) {
            if (seen.add(child)) {
                out.add(child)
                walk(child)
            }
        }
    }
    walk(root)
    return out
}
