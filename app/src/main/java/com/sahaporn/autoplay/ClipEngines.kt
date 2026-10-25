package com.sahaporn.autoplay

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import android.media.MediaPlayer as SystemMediaPlayer
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

/** Playback events the backup player reacts to. */
interface EngineHost {
    fun onEnginePlaying()
    fun onEnginePaused()
    fun onEngineBuffering(busy: Boolean)
    fun onEngineVideoSize()
    fun onEngineEnded()
    fun onEngineError()
}

/** One way of playing a single clip; the backup player swaps these without changing its controls. */
interface ClipEngine {
    val isPlaying: Boolean
    val time: Long
    val length: Long

    /** Starts the clip at [startAt] ms; false when the file can't even be opened. */
    fun open(uri: Uri, startAt: Long): Boolean
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun setSpeed(x: Float)

    /** 0 = fit, 1 = zoom (crop), 2 = stretch. */
    fun setScale(mode: Int)

    /** Whether the picture is taller than wide; null until the size is known. */
    fun isPortraitVideo(): Boolean?
    fun trackItems(): List<Pair<CharSequence, () -> Unit>>
    fun release()
}

/** Android's own media player: the same decoding path as the phone's built-in video app. */
class SystemEngine(
    private val ctx: Context,
    private val surface: SurfaceView,
    private val host: EngineHost,
) : ClipEngine, SurfaceHolder.Callback {
    private val mp = SystemMediaPlayer()
    private var prepared = false
    private var released = false
    private var startAt = 0L
    private var speed = 1f
    private var scaleMode = 0
    private var videoW = 0
    private var videoH = 0

    init {
        surface.holder.addCallback(this)
        if (surface.holder.surface?.isValid == true) mp.setDisplay(surface.holder)
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        mp.setOnPreparedListener {
            prepared = true
            if (startAt > 0) mp.seekTo(startAt, SystemMediaPlayer.SEEK_CLOSEST)
            startAt = 0L
            mp.start()
            applySpeed()
            host.onEnginePlaying()
        }
        mp.setOnCompletionListener { if (!released) host.onEngineEnded() }
        mp.setOnErrorListener { _, _, _ ->
            if (!released) host.onEngineError()
            true
        }
        mp.setOnVideoSizeChangedListener { _, w, h ->
            videoW = w
            videoH = h
            resize()
            host.onEngineVideoSize()
        }
        mp.setOnInfoListener { _, what, _ ->
            when (what) {
                SystemMediaPlayer.MEDIA_INFO_BUFFERING_START -> host.onEngineBuffering(true)
                SystemMediaPlayer.MEDIA_INFO_BUFFERING_END -> host.onEngineBuffering(false)
            }
            false
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!released) mp.setDisplay(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!released) mp.setDisplay(null)
    }

    override val isPlaying: Boolean get() = prepared && !released && mp.isPlaying
    override val time: Long get() = if (prepared) mp.currentPosition.toLong() else startAt
    override val length: Long get() = if (prepared) mp.duration.toLong().coerceAtLeast(0L) else 0L

    override fun open(uri: Uri, startAt: Long): Boolean {
        return try {
            prepared = false
            mp.reset()
            if (surface.holder.surface?.isValid == true) mp.setDisplay(surface.holder)
            mp.setDataSource(ctx, uri)
            this.startAt = startAt
            mp.prepareAsync()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun play() {
        if (!prepared) return
        mp.start()
        applySpeed()
        host.onEnginePlaying()
    }

    override fun pause() {
        if (prepared && mp.isPlaying) {
            mp.pause()
            host.onEnginePaused()
        }
    }

    override fun seek(ms: Long) {
        if (prepared) mp.seekTo(ms, SystemMediaPlayer.SEEK_CLOSEST) else startAt = ms
    }

    override fun setSpeed(x: Float) {
        speed = x
        if (isPlaying) applySpeed()
    }

    /** Changing playback params on a paused player would start it, so only do it while playing. */
    private fun applySpeed() {
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
        } catch (e: Exception) {
            // Some devices refuse speed changes for certain formats; keep normal speed.
        }
    }

    override fun setScale(mode: Int) {
        scaleMode = mode
        resize()
    }

    /** Sizes the surface inside its parent to fit, crop or stretch the picture. */
    private fun resize() {
        val parent = surface.parent as? View ?: return
        val pw = parent.width
        val ph = parent.height
        if (videoW <= 0 || videoH <= 0 || pw <= 0 || ph <= 0) return
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (scaleMode == 2) {
            lp.width = pw
            lp.height = ph
        } else {
            val sx = pw.toFloat() / videoW
            val sy = ph.toFloat() / videoH
            val s = if (scaleMode == 1) max(sx, sy) else min(sx, sy)
            lp.width = (videoW * s).toInt()
            lp.height = (videoH * s).toInt()
        }
        lp.gravity = Gravity.CENTER
        surface.layoutParams = lp
    }

    override fun isPortraitVideo(): Boolean? = if (videoW > 0 && videoH > 0) videoH > videoW else null

    override fun trackItems(): List<Pair<CharSequence, () -> Unit>> {
        val infos = try {
            mp.trackInfo
        } catch (e: Exception) {
            return emptyList()
        }
        val selected = try {
            mp.getSelectedTrack(SystemMediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)
        } catch (e: Exception) {
            -1
        }
        var n = 0
        return infos.withIndex()
            .filter { it.value.trackType == SystemMediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }
            .map { (i, info) ->
                n++
                val lang = info.language?.takeIf { it.isNotEmpty() && it != "und" }?.let {
                    Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("th")).ifEmpty { it }
                }
                val label: CharSequence = "${if (i == selected) "✓" else "   "} 🔊 ${lang ?: "เสียง $n"}"
                label to { mp.selectTrack(i) }
            }
    }

    override fun release() {
        released = true
        surface.holder.removeCallback(this)
        mp.release()
    }
}

/** VLC: decodes nearly any format, in software when [hardware] is off. */
class VlcEngine(
    private val ctx: Context,
    private val view: VLCVideoLayout,
    private val hardware: Boolean,
    private val host: EngineHost,
    private val handler: Handler,
) : ClipEngine {
    private val libVlc = LibVLC(ctx, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
    private val mp = VlcMediaPlayer(libVlc)
    private var fd: ParcelFileDescriptor? = null
    private var released = false

    /** Where to jump once playback starts; VLC can't seek before that. */
    private var startAt = 0L

    init {
        mp.attachViews(view, null, true, false)
        mp.setEventListener { event -> handler.post { onEvent(event) } }
    }

    private fun onEvent(event: VlcMediaPlayer.Event) {
        if (released) return
        when (event.type) {
            VlcMediaPlayer.Event.Playing -> {
                if (startAt > 0) {
                    mp.setTime(startAt)
                    startAt = 0L
                }
                host.onEnginePlaying()
            }
            VlcMediaPlayer.Event.Paused -> host.onEnginePaused()
            VlcMediaPlayer.Event.Buffering -> host.onEngineBuffering(event.buffering < 100f)
            VlcMediaPlayer.Event.Vout -> host.onEngineVideoSize()
            VlcMediaPlayer.Event.EndReached -> host.onEngineEnded()
            VlcMediaPlayer.Event.EncounteredError -> host.onEngineError()
        }
    }

    override val isPlaying: Boolean get() = !released && mp.isPlaying
    override val time: Long get() = if (startAt > 0) startAt else mp.time.coerceAtLeast(0L)
    override val length: Long get() = mp.length

    override fun open(uri: Uri, startAt: Long): Boolean {
        fd?.close()
        fd = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        val file = fd ?: return false
        val media = Media(libVlc, file.fileDescriptor)
        media.setHWDecoderEnabled(hardware, false)
        mp.setMedia(media)
        media.release()
        this.startAt = startAt
        mp.play()
        return true
    }

    override fun play() = mp.play()
    override fun pause() = mp.pause()

    override fun seek(ms: Long) {
        if (startAt > 0) startAt = ms else mp.setTime(ms)
    }

    override fun setSpeed(x: Float) = mp.setRate(x)

    override fun setScale(mode: Int) {
        mp.setVideoScale(
            when (mode) {
                1 -> VlcMediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                2 -> VlcMediaPlayer.ScaleType.SURFACE_FILL
                else -> VlcMediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
        )
    }

    override fun isPortraitVideo(): Boolean? {
        val track = mp.currentVideoTrack ?: return null
        return track.height > track.width
    }

    override fun trackItems(): List<Pair<CharSequence, () -> Unit>> {
        val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
        val audio = mp.audioTrack
        mp.audioTracks?.filter { it.id >= 0 }?.forEach { t ->
            items.add("${if (t.id == audio) "✓" else "   "} 🔊 ${t.name}" to { mp.setAudioTrack(t.id) })
        }
        val spu = mp.spuTrack
        mp.spuTracks?.forEach { t ->
            val name = if (t.id < 0) "ปิดซับไตเติล" else t.name
            items.add("${if (t.id == spu) "✓" else "   "} 💬 $name" to { mp.setSpuTrack(t.id) })
        }
        return items
    }

    override fun release() {
        released = true
        mp.setEventListener(null)
        mp.stop()
        mp.detachViews()
        mp.release()
        libVlc.release()
        fd?.close()
    }
}
