package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.WarehouseEntry

/**
 * v0.122.0：多仓子仓订阅记录同步（单一入口，站源页刷新与自动刷新 Worker 共用）。
 *
 * 背景：v0.43.0 起订阅解析能识别多仓（顶层 urls 数组），但子仓只存进父仓的
 * SubCache.warehouses，靠站源页里手动展开——不是独立订阅记录，没有自己的
 * 开关/刷新/分组，自动刷新也不会带上。本对象把子仓落成正式的 ApiSource 记录：
 *
 * - 子仓记录：name="父名 · 子名"、group=父名（分组 chips 归父仓下）、
 *   parentUrl=父仓 URL（归属标记，老数据缺字段读作空串=顶层订阅）。
 * - 同步（父仓刷新成功后）：新子仓无同址记录才新增（去重，用户自加的同址
 *   订阅不动）；已消失的子仓（parentUrl 命中且 URL 不在新列表）删记录+清缓存。
 * - 只展开一层：父仓本身是子仓（parentUrl 非空）时不同步，防多仓套多仓递归。
 * - 删除：删父仓连带删其全部自动子仓（随父删，含各自站点缓存）；
 *   删子仓本身只删自己（走普通订阅删除路径，不在此处理）。
 */
object WarehouseSync {

    /**
     * 父仓刷新后同步子仓记录。返回新增子仓的 URL 列表（调用方决定是否立即解析展开）。
     * 文件读写是小 JSON，沿用站源页现有口径直接同步调用。
     */
    fun syncChildren(
        context: Context,
        parent: ApiSource,
        warehouses: List<WarehouseEntry>,
    ): List<String> {
        if (parent.parentUrl.isNotBlank()) return emptyList()
        val all = ApiSourceStore.load(context).toMutableList()
        var changed = false
        val newUrls = warehouses.map { it.url }.toSet()
        // 已消失的子仓：先只删记录，缓存等写盘成功后再清——原来先清
        // 缓存后写盘且不看结果，写盘一失败记录还在、缓存已经没了，
        // 下次打开该子仓要重新下载展开，同步还以为自己成功了。
        val removedUrls = mutableListOf<String>()
        val iter = all.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.parentUrl == parent.url && e.url !in newUrls) {
                iter.remove()
                changed = true
                removedUrls += e.url
                LogBus.i("WarehouseSync", "子仓已消失，随父仓同步删除：${e.name}")
            }
        }
        // 新子仓：同址已有记录（自加/已同步）则跳过，不重复添加
        val added = mutableListOf<String>()
        for (wh in warehouses) {
            if (all.any { it.url == wh.url }) continue
            all.add(
                ApiSource(
                    name = "${parent.name} · ${wh.name}",
                    url = wh.url,
                    group = parent.name,
                    parentUrl = parent.url,
                ),
            )
            added += wh.url
            changed = true
            LogBus.i("WarehouseSync", "多仓展开新增子仓订阅：${parent.name} · ${wh.name}")
        }
        if (changed) {
            // 写盘成功才清已消失子仓的缓存；写盘失败时缓存不动、新增
            // 也不报给调用方去解析展开，下次同步重跑。
            if (ApiSourceStore.save(context, all)) {
                removedUrls.forEach { SubSiteStore.removeCache(context, it) }
            } else {
                LogBus.w("WarehouseSync", "多仓同步写盘失败，缓存未清、新增未上报，下次同步重跑")
                return emptyList()
            }
        }
        return added
    }

    /** 删父仓时连带删除其自动子仓记录与站点缓存（随父删）。 */
    fun removeChildren(context: Context, parentUrl: String) {
        val all = ApiSourceStore.load(context)
        val children = all.filter { it.parentUrl == parentUrl }
        if (children.isEmpty()) return
        // 与 syncChildren 同款：先写盘、成功后再清缓存，写盘失败缓存不动。
        if (!ApiSourceStore.save(context, all.filter { it.parentUrl != parentUrl })) {
            LogBus.w("WarehouseSync", "删父仓连带子仓写盘失败，缓存未清")
            return
        }
        children.forEach { SubSiteStore.removeCache(context, it.url) }
        LogBus.i("WarehouseSync", "删父仓连带删除 ${children.size} 个子仓订阅")
    }
}
