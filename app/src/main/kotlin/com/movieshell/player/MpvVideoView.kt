package com.movieshell.player

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * libmpv 渲染载体（v0.20.0）。
 *
 * 必须是 SurfaceView（vo=gpu 需要 Surface，不支持 TextureView）；
 * surface 生命周期直接转交给 [MpvPlayerManager] 做 attach/detach。
 */
class MpvVideoView(context: Context) : SurfaceView(context) {

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.surface?.let { MpvPlayerManager.onSurfaceReady(it) }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                MpvPlayerManager.onSurfaceSize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                MpvPlayerManager.onSurfaceLost()
            }
        })
    }
}
