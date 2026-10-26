package com.sahaporn.autoplay

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import android.media.MediaPlayer as SystemMediaPlayer
import androidx.media3.common.AudioAttributes as ExoAudioAttributes
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

    /** Width / height of the picture, for the floating window; null until known. */
    fun videoAspect(): Float? = null
    fun trackItems(): List<Pair<CharSequence, () -> Unit>>

    /** The screen went off but sound keeps going. */
    fun onBackground() = Unit

    /** The screen is back. */
    fun onForeground() = Unit
    fun release()
}

/** The main engine (ExoPlayer): fast, embedded subtitles, FFmpeg audio for AC3/DTS and the like. */
@OptIn(UnstableApi::class)
class ExoEngine(
    ctx: Context,
    private val view: PlayerView,
    private val series: Series,
    private val host: EngineHost,
    private val onPrefsChanged: () -> Unit,
) : ClipEngine {
    private val player: ExoPlayer
    private var released = false

    init {
        // FFmpeg takes over audio the phone can't decode; a failing video decoder falls back to another.
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        // Seek in files without an index, and play TS recordings that lack IDR frames.
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        val audio = ExoAudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player = ExoPlayer.Builder(ctx, renderers, DefaultMediaSourceFactory(ctx, extractors))
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(series.audioLang)
            .setPreferredTextLanguage(series.textLang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, series.textOff)
            .build()
        view.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!released && isPlaying) host.onEnginePlaying()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!released && !playWhenReady) host.onEnginePaused()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (released) return
                host.onEngineBuffering(playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED) host.onEngineEnded()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!released) host.onEngineVideoSize()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!released) host.onEngineError()
            }
        })
    }

    override val isPlaying: Boolean get() = !released && player.isPlaying
    override val time: Long get() = player.currentPosition.coerceAtLeast(0L)
    override val length: Long get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    override fun open(uri: Uri, startAt: Long): Boolean {
        player.setMediaItem(MediaItem.fromUri(uri), startAt)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    override fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    override fun pause() = player.pause()
    override fun seek(ms: Long) = player.seekTo(ms)
    override fun setSpeed(x: Float) = player.setPlaybackSpeed(x)

    override fun setScale(mode: Int) {
        view.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun isPortraitVideo(): Boolean? {
        val v = player.videoSize
        if (v.width <= 0 || v.height <= 0) return null
        return v.height > v.width * v.pixelWidthHeightRatio
    }

    override fun videoAspect(): Float? {
        val v = player.videoSize
        if (v.width <= 0 || v.height <= 0) return null
        return v.width * v.pixelWidthHeightRatio / v.height
    }

    private fun trackName(f: Format, fallback: String): String {
        val lang = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("th")).ifEmpty { it }
        }
        return listOfNotNull(f.label, lang).distinct().joinToString(" · ").ifEmpty { fallback }
    }

    override fun trackItems(): List<Pair<CharSequence, () -> Unit>> {
        val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
        val groups = player.currentTracks.groups
        var n = 0
        groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val name = trackName(g.getTrackFormat(i), "เสียง $n")
                items.add("${if (g.isTrackSelected(i)) "✓" else "   "} 🔊 $name" to { selectTrack(g, i) })
            }
        }
        val texts = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (texts.isNotEmpty()) {
            val off = texts.none { it.isSelected }
            items.add("${if (off) "✓" else "   "} 💬 ปิดซับไตเติล" to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                series.textOff = true
                onPrefsChanged()
            })
            n = 0
            texts.forEach { g ->
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    n++
                    val name = trackName(g.getTrackFormat(i), "ซับไตเติล $n")
                    items.add("${if (g.isTrackSelected(i)) "✓" else "   "} 💬 $name" to { selectTrack(g, i) })
                }
            }
        }
        return items
    }

    /** Picks a track and remembers its language, so later episodes use the same one. */
    private fun selectTrack(g: Tracks.Group, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(g.type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, index))
            .build()
        val lang = g.getTrackFormat(index).language
        if (g.type == C.TRACK_TYPE_AUDIO) {
            series.audioLang = lang
        } else {
            series.textLang = lang
            series.textOff = false
        }
        onPrefsChanged()
    }

    override fun release() {
        released = true
        view.player = null
        player.release()
    }
}

/** Android's own media player: the same decoding path as the phone's built-in video app. */
@OptIn(UnstableApi::class)
class SystemEngine(
    private val ctx: Context,
    private val surface: SurfaceView,
    private val frame: AspectRatioFrameLayout,
    private val host: EngineHost,
) : ClipEngine, SurfaceHolder.Callback {
    private val mp = SystemMediaPlayer()
    private var prepared = false
    private var released = false
    private var startAt = 0L
    private var speed = 1f
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
            if (w > 0 && h > 0) frame.setAspectRatio(w.toFloat() / h)
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
        frame.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun isPortraitVideo(): Boolean? = if (videoW > 0 && videoH > 0) videoH > videoW else null

    override fun videoAspect(): Float? = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else null

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

    override fun videoAspect(): Float? {
        val track = mp.currentVideoTrack ?: return null
        if (track.width <= 0 || track.height <= 0) return null
        return track.width.toFloat() / track.height
    }

    private var viewsAttached = true

    /** Sound only while the screen is off; VLC stalls if it keeps drawing to a gone surface. */
    override fun onBackground() {
        if (!viewsAttached || released) return
        mp.setVideoTrackEnabled(false)
        mp.detachViews()
        viewsAttached = false
    }

    override fun onForeground() {
        if (viewsAttached || released) return
        mp.attachViews(view, null, true, false)
        mp.setVideoTrackEnabled(true)
        viewsAttached = true
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
        if (viewsAttached) mp.detachViews()
        mp.release()
        libVlc.release()
        fd?.close()
    }
}
