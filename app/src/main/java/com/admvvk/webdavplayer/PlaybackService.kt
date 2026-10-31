package com.admvvk.webdavplayer

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps playback alive while the app is in the background.
 *
 * Previously the ExoPlayer instance lived purely inside AppViewModel, tied
 * to MainActivity's lifecycle. As soon as the user backgrounded the app
 * (home button / app switcher), the process lost foreground priority and
 * Android started throttling/killing its background network access --
 * observed in the field as playback reliably dying ~10 minutes after being
 * backgrounded, regardless of signal quality (an OS background-execution
 * policy, not an actual network problem; foreground playback with the
 * screen off was never affected).
 *
 * A MediaSessionService fixes this the standard Android way: as long as a
 * registered MediaSession's player is playing, the service promotes
 * itself to a foreground service with a system media notification, which
 * exempts it from the background network/execution restrictions that were
 * killing playback.
 *
 * AppViewModel still owns ExoPlayer's construction (it needs the WebDAV
 * auth header from the live connection, assembled lazily after connecting
 * to the server), it just hands the *same* player instance to this
 * service via [setPlayer] once created, so the system sees one real
 * playback session regardless of whether the Activity is visible.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** Called by AppViewModel once its ExoPlayer instance exists. */
    fun setPlayer(player: Player) {
        if (mediaSession?.player === player) return
        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    /** Called when playback is fully torn down (e.g. entering Settings, or app exit). */
    fun clearPlayer() {
        mediaSession?.release()
        mediaSession = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val localBinder = LocalBinder()

    /**
     * Media3 controllers (incl. the system notification) bind with the
     * action [MediaSessionService.SERVICE_INTERFACE] and must go through
     * the superclass's own binder; AppViewModel instead binds with a plain
     * Intent to get a [LocalBinder] and talk to [setPlayer]/[clearPlayer]
     * directly.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            localBinder
        }
    }
}
