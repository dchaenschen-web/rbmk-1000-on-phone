package com.dchaenschen.rbmk

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 读取「正在播放的音乐」标题与歌手。
 *
 * 为什么需要通知使用权
 * --------------------
 * Android 不允许普通应用窥探别的 App 的播放状态。唯一被允许的途径是
 * NotificationListenerService（用户手动在「设置 → 通知使用权」里授权），
 * 拿到授权后用 MediaSessionManager.getActiveSessions() 读 MediaMetadata。
 *
 * 如果没有授权，就只是拿不到歌名，其余功能（跟随音频）不受影响。
 */
class MusicListenerService : NotificationListenerService() {

    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish(metadata)
        }

        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            if (state?.state != android.media.session.PlaybackState.STATE_PLAYING) {
                // 暂停时不清空标题，只标记没有在播；壁纸可选择是否继续显示
                ReactorState.hasMusic = false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 媒体通知变化时顺手刷新一次，比轮询省电
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        try {
            val mgr = getSystemService(MediaSessionManager::class.java) ?: return
            val comp = ComponentName(this, MusicListenerService::class.java)
            val sessions = mgr.getActiveSessions(comp)
            val playing = sessions.firstOrNull {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull()
            if (playing?.packageName == packageName) return

            if (controller?.sessionToken != playing?.sessionToken) {
                controller?.unregisterCallback(callback)
                controller = playing
                controller?.registerCallback(callback)
            }
            publish(playing?.metadata)
        } catch (_: SecurityException) {
            // 没有通知使用权
        } catch (_: Exception) {
        }
    }

    private fun publish(metadata: MediaMetadata?) {
        val t = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val a = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            .ifBlank { metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() }
        ReactorState.title = t
        ReactorState.artist = a
        ReactorState.hasMusic = t.isNotBlank()
    }

    override fun onDestroy() {
        try {
            controller?.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
