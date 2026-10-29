package com.movieshell.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
        // v0.99.0 P0-6：显式音频属性 + 接管音频焦点，来电/导航/其他 App 抢音频时自动暂停或 duck
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val sessionActivity = Notify.routeIntent(this, "music_player", id = 9001)
        session = MediaSession.Builder(this, QueueForwardingPlayer(exo))
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // v0.275.0（T17）：先通知 MusicPlayer 丢掉已失效的控制器引用，
        // 再释放 session/player，否则播放器侧仍握着旧控制器、toggle 调它无反应。
        MusicPlayer.onServiceDestroyed()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}
