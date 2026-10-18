package com.movieshell.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.movieshell.data.download.Notify

/**
 * v0.85.0：音乐后台播放。
 *
 * ExoPlayer 实例归 service 持有并建 MediaSession；通知栏/锁屏/耳机线控走
 * media3 标准媒体通知（DefaultMediaNotificationProvider，封面/歌名/播放暂停/上下首）。
 * 前台服务状态由 MediaSessionService 在播放起停时自动管理。
 *
 * 直链按首实时解析（时效签名不缓存），service 的 player 每次只持有一首；
 * 通知栏的上下首经 QueueForwardingPlayer 转给 MusicPlayer 的逻辑队列。
 */
class MusicService : MediaSessionService() {

    private var session: MediaSession? = null

    private inner class QueueForwardingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands {
            val builder = super.getAvailableCommands().buildUpon()
            if (MusicPlayer.hasPrev()) builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            if (MusicPlayer.hasNext()) builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            return builder.build()
        }

        override fun seekToPrevious() {
            MusicPlayer.prev(this@MusicService)
        }

        override fun seekToNext() {
            MusicPlayer.next(this@MusicService)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 耳机拔出暂停等默认由 ExoPlayer 处理（becoming noisy）
        val exo = ExoPlayer.Builder(this).build()
        val sessionActivity = Notify.routeIntent(this, "music_player", id = 9001)
        session = MediaSession.Builder(this, QueueForwardingPlayer(exo))
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}
