package com.movieshell.util

/** v0.294.40：列表可能为空时的下标收敛（coerceIn(list.indices) 在空列表上会抛 0..-1 异常） */
fun Int.safeIndex(size: Int): Int = if (size <= 0) 0 else coerceIn(0, size - 1)
