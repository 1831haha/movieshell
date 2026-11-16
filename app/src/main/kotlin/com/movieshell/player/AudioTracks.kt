package com.movieshell.player

import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.movieshell.player.TrackLanguage.AudioTrackInfo

/**
 * v1.2.14 B片（3）：Media3 Tracks → 音轨信息列表（薄映射层）。
 *
 * 只取音频组；id 为 "组下标:轨下标"，供 PlayerManager.selectAudioTrack 认领。
 * 字幕轨（TEXT）不走这里——外挂字幕仍走 SubtitleKit 单入口，内嵌字幕轨
 * 本版不做切换（多数字幕轨是外挂字幕文件，容器内嵌字幕轨极少）。
 */
object AudioTracks {

    fun fromTracks(tracks: Tracks): List<AudioTrackInfo> {
        val out = ArrayList<AudioTrackInfo>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                out.add(
                    AudioTrackInfo(
                        id = "$groupIndex:$trackIndex",
                        language = format.language.orEmpty(),
                        label = format.label.orEmpty(),
                        mimeType = format.sampleMimeType.orEmpty(),
                        selected = group.isTrackSelected(trackIndex),
                    ),
                )
            }
        }
        return out
    }
}
