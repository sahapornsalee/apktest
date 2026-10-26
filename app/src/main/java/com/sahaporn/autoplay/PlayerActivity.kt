package com.sahaporn.autoplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The video player. One screen and one set of controls; the picture comes from one of three
 * engines (see [Engines]). Each clip starts on its preferred engine and moves on to the next one
 * by itself when it can't be played, so a file the phone can open always gets a chance.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ThemedActivity(), EngineHost {
    companion object {
        const val EXTRA_ID = "series_id"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION = "position"

        private const val COUNTDOWN_SECONDS = 5
        private const val CONTROLS_TIMEOUT_MS = 4_000L
        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        private val RESIZE_NAMES = listOf("พอดีจอ", "เต็มจอ (ตัดขอบ)", "ยืดเต็มจอ")
        private val ORIENTATION_NAMES = listOf("อัตโนมัติตามคลิป", "แนวนอน", "แนวตั้ง", "หมุนตามเครื่อง")

        fun start(ctx: Context, seriesId: String, index: Int, position: Long) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_ID, seriesId)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_POSITION, position)
            )
        }
    }

    private enum class Drag { NONE, IGNORE, SEEK, BRIGHTNESS, VOLUME }

    private lateinit var series: Series
    private lateinit var engine: ClipEngine
    private var engineKind = Engines.MAIN

    /** In automatic mode, the engine that rescued a clip; the clips after it try that one first. */
    private var sessionEngine: Int? = null
    private val triedEngines = mutableSetOf<Int>()
    private var index = 0

    /** Short episode names ("ตอนที่ 3"), see [Series.episodeLabels]. */
    private var labels: List<String> = emptyList()

    // ExoPlayer handles audio focus itself; the other two engines get it from here.
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private lateinit var videoFrame: View
    private lateinit var exoView: PlayerView
    private lateinit var vlcLayout: VLCVideoLayout
    private lateinit var systemFrame: AspectRatioFrameLayout
    private lateinit var systemSurface: SurfaceView
    private lateinit var controls: View
    private lateinit var txtSeriesName: TextView
    private lateinit var txtTitle: TextView
    private lateinit var txtSleep: TextView
    private lateinit var btnEngine: TextView
    private lateinit var btnRotate: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnRew: TextView
    private lateinit var btnFwd: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var txtPosition: TextView
    private lateinit var txtRemaining: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var buffering: View
    private lateinit var txtGesture: TextView
    private lateinit var episodesPanel: View
    private lateinit var btnPanelSeason: Button
    private lateinit var panelList: RecyclerView
    private lateinit var panelAdapter: SimpleAdapter<Int>
    private lateinit var btnSkip: Button
    private lateinit var countdownBox: View
    private lateinit var txtCountdown: TextView
    private lateinit var btnUnlock: Button

    private val handler = Handler(Looper.getMainLooper())
    private var toast: Toast? = null
    private var ended = false
    private var introHandled = false
    private var outroHandled = false
    private var finished = false
    private var locked = false
    private var inPip = false
    private var dragging = false
    private var failures = 0
    private var lastSavedAt = 0L

    /** Wall-clock time when the sleep timer pauses playback; 0 = off. */
    private var sleepAt = 0L

    /** Stop when this clip ends ("stop after this one" / "one more episode"); -1 = off. */
    private var stopAfterIndex = -1
    private var countdownLeft = 0
    private var skipAction: (() -> Unit)? = null
    private var panelSeason = ""

    private lateinit var session: MediaSession
    private var notificationShown = false
    private var receiverRegistered = false

    private var drag = Drag.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartValue = 0f
    private var dragStartPos = 0L
    private var dragSeekTarget = 0L
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val seekStepMs: Long get() = Settings.seekStepSec(this) * 1000L

    private val ticker = object : Runnable {
        override fun run() {
            if (::engine.isInitialized && !finished) {
                checkSkip()
                checkSleep()
                updateProgressUi()
                if (engine.isPlaying && System.currentTimeMillis() - lastSavedAt > 5_000) {
                    saveProgress()
                    updateSession()
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            countdownLeft--
            if (countdownLeft <= 0) {
                playNextNow()
            } else {
                updateCountdownText()
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private val hideControls = Runnable { setControlsVisible(false) }
    private val hideGesture = Runnable { txtGesture.visibility = View.GONE }
    private val hideUnlock = Runnable { btnUnlock.visibility = View.GONE }

    private val lockedBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showUnlockHint()
            showToast("หน้าจอล็อกอยู่ · แตะค้างที่ปุ่ม 🔓 เพื่อปลดล็อก")
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Headphones unplugged: pause, as every player should (ExoPlayer already does this itself). */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::engine.isInitialized && engineKind != Engines.MAIN && engine.isPlaying) engine.pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (!::engine.isInitialized || engineKind == Engines.MAIN) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (engine.isPlaying) {
                    resumeOnFocusGain = true
                    engine.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    engine.play()
                }
            }
        }
    }

    /** Pause for calls and other apps' sound when the system player or VLC is playing. */
    private fun requestAudioFocus() {
        if (engineKind == Engines.MAIN) return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
            .also { focusRequest = it }
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        resumeOnFocusGain = false
    }

    /** Buttons on the notification and lock screen. */
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::engine.isInitialized || finished) return
            when (intent.action) {
                PlaybackNotification.ACTION_PLAY_PAUSE -> togglePlay()
                PlaybackNotification.ACTION_NEXT -> nextByUser()
                PlaybackNotification.ACTION_PREV -> previousByUser()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val s = Store.get(this, intent.getStringExtra(EXTRA_ID) ?: "")
        if (s == null || s.episodes.isEmpty()) {
            finish()
            return
        }
        series = s
        labels = s.episodeLabels()

        bindViews()
        keepControlsClearOfScreenEdges()
        setupControls()
        setupGestures()
        setupSession()
        onBackPressedDispatcher.addCallback(this, lockedBack)
        val filter = IntentFilter().apply {
            addAction(PlaybackNotification.ACTION_PLAY_PAUSE)
            addAction(PlaybackNotification.ACTION_NEXT)
            addAction(PlaybackNotification.ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true

        applyOrientation()
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, s.episodes.size - 1)
        openClip(start, intent.getLongExtra(EXTRA_POSITION, 0L))
        setControlsVisible(true)
        showFirstTimeHint()
    }

    private fun bindViews() {
        videoFrame = findViewById(R.id.videoFrame)
        exoView = findViewById(R.id.exoView)
        vlcLayout = findViewById(R.id.vlcLayout)
        systemFrame = findViewById(R.id.systemFrame)
        systemSurface = findViewById(R.id.systemSurface)
        controls = findViewById(R.id.controls)
        txtSeriesName = findViewById(R.id.txtSeriesName)
        txtTitle = findViewById(R.id.txtTitle)
        txtSleep = findViewById(R.id.txtSleep)
        btnEngine = findViewById(R.id.btnEngine)
        btnRotate = findViewById(R.id.btnRotate)
        btnPlay = findViewById(R.id.btnPlay)
        btnRew = findViewById(R.id.btnRew)
        btnFwd = findViewById(R.id.btnFwd)
        seekBar = findViewById(R.id.seekBar)
        txtPosition = findViewById(R.id.txtPosition)
        txtRemaining = findViewById(R.id.txtRemaining)
        btnSpeed = findViewById(R.id.btnSpeed)
        buffering = findViewById(R.id.buffering)
        txtGesture = findViewById(R.id.txtGesture)
        episodesPanel = findViewById(R.id.episodesPanel)
        btnPanelSeason = findViewById(R.id.btnPanelSeason)
        panelList = findViewById(R.id.rvPanel)
        btnSkip = findViewById(R.id.btnSkip)
        countdownBox = findViewById(R.id.countdownBox)
        txtCountdown = findViewById(R.id.txtCountdown)
        btnUnlock = findViewById(R.id.btnUnlock)
    }

    /**
     * Pads the controls away from the camera cutout, status bar and gesture bar. Uses the bars'
     * size even while they're hidden, so nothing jumps when they slide in.
     */
    private fun keepControlsClearOfScreenEdges() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val topBar = findViewById<View>(R.id.topBar)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.playerRoot)) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val left = max(bars.left, cutout.left)
            val top = max(bars.top, cutout.top)
            val right = max(bars.right, cutout.right)
            val bottom = max(bars.bottom, cutout.bottom)
            topBar.setPadding(left + px(12), top + px(8), right + px(12), px(28))
            bottomBar.setPadding(left + px(16), px(40), right + px(16), bottom + px(6))
            episodesPanel.setPadding(left, px(8), right, bottom + px(16))
            (btnUnlock.layoutParams as ViewGroup.MarginLayoutParams).topMargin = top + px(24)
            btnUnlock.requestLayout()
            for (corner in listOf(btnSkip, countdownBox)) {
                val lp = corner.layoutParams as ViewGroup.MarginLayoutParams
                lp.marginEnd = right + px(32)
                lp.bottomMargin = bottom + px(132)
                corner.requestLayout()
            }
            insets
        }
    }

    private fun currentEpisode(): Episode = series.episodes[index]

    private fun hasNext() = index + 1 < series.episodes.size

    // ---- Engines ----

    private fun preferredEngine(i: Int): Int {
        if (series.engine == Engines.AUTO) sessionEngine?.let { return it }
        return Engines.preferred(this, series, series.episodes[i])
    }

    /** Swaps the engine and shows its picture view; the caller opens the clip afterwards. */
    private fun useEngine(kind: Int) {
        if (::engine.isInitialized) engine.release()
        // ExoPlayer asks for audio focus on its own; don't hold a second request against it.
        if (kind == Engines.MAIN) abandonAudioFocus()
        engineKind = kind
        exoView.visibility = if (kind == Engines.MAIN) View.VISIBLE else View.GONE
        vlcLayout.visibility = if (kind == Engines.VLC) View.VISIBLE else View.GONE
        systemFrame.visibility = if (kind == Engines.SYSTEM) View.VISIBLE else View.GONE
        engine = when (kind) {
            Engines.SYSTEM -> SystemEngine(this, systemSurface, systemFrame, this)
            Engines.VLC -> VlcEngine(this, vlcLayout, series.vlcHardware, this, handler)
            else -> ExoEngine(this, exoView, series, this) { Store.put(this, series) }
        }
        engine.setScale(series.resizeMode)
        btnEngine.text = "🎬 ${Engines.badge(kind)}"
    }

    /** Starts clip [i] at [position] on its preferred engine. */
    private fun openClip(i: Int, position: Long) {
        index = i
        triedEngines.clear()
        if (stopAfterIndex in 0 until i) stopAfterIndex = -1
        startOn(preferredEngine(i), position)
    }

    /** Plays the current clip on engine [kind]; [fresh] rebuilds it even if it's already in use. */
    private fun startOn(kind: Int, position: Long, fresh: Boolean = false) {
        triedEngines.add(kind)
        if (fresh || !::engine.isInitialized || engineKind != kind) useEngine(kind)
        ended = false
        introHandled = false
        outroHandled = false
        cancelCountdown()
        hideSkipButton()
        series.lastIndex = index
        series.lastWatchedAt = System.currentTimeMillis()
        Store.put(this, series)
        updateTitle()
        if (episodesPanel.visibility == View.VISIBLE) renderPanel()
        if (!engine.open(Uri.parse(currentEpisode().uri), position.coerceAtLeast(0L))) {
            recoverFromError()
            return
        }
        engine.setSpeed(series.speed)
        updateSession()
    }

    /** The current engine gave up on this clip: try the next engine, then the next clip. */
    private fun recoverFromError() {
        val pos = engine.time
        val next = Engines.ORDER.firstOrNull { it !in triedEngines }
        if (next != null) {
            showToast("${Engines.shortName(engineKind)}เปิดไฟล์นี้ไม่ได้ · กำลังลองด้วย${Engines.shortName(next)}")
            startOn(next, pos)
        } else {
            showToast("เปิดไฟล์นี้ไม่ได้ทุกตัวเล่น (ไฟล์อาจเสีย): ${currentEpisode().shortName}")
            failures++
            if (failures < 3 && hasNext()) openClip(index + 1, series.episodes[index + 1].resumePosition) else finish()
        }
    }

    /** Lets the user pick the engine for this series, e.g. when the picture breaks up. */
    private fun chooseEngine() {
        fun pick(kind: Int) {
            val pos = engine.time
            saveProgress()
            series.engine = kind
            if (kind == Engines.VLC) series.vlcHardware = false
            sessionEngine = null
            Store.put(this, series)
            triedEngines.clear()
            val target = if (kind == Engines.AUTO) Engines.preferred(this, series, currentEpisode()) else kind
            startOn(target, pos, fresh = true)
            showToast("เปลี่ยนเป็น${Engines.shortName(target)}แล้ว · จำไว้สำหรับเรื่องนี้")
        }
        fun mark(kind: Int) = if (series.engine == kind) "✓  " else ""
        Dialogs.menu(
            this, "ภาพแตกหรือเล่นไม่ได้? ลองเปลี่ยนตัวเล่น", listOf(
                "${mark(Engines.SYSTEM)}📱 ตัวเล่นของระบบ · แบบเดียวกับแอปวิดีโอในเครื่อง" to { pick(Engines.SYSTEM) },
                "${mark(Engines.VLC)}🧰 VLC · ถอดรหัสด้วยซอฟต์แวร์ เปิดได้แทบทุกไฟล์" to { pick(Engines.VLC) },
                "${mark(Engines.MAIN)}⭐ ตัวเล่นหลัก · เร็ว ซับไตเติลครบ" to { pick(Engines.MAIN) },
                "${mark(Engines.AUTO)}✨ อัตโนมัติ · ตามค่าในหน้าตั้งค่า" to { pick(Engines.AUTO) },
            )
        )
    }

    // ---- Engine events ----

    override fun onEnginePlaying() {
        failures = 0
        requestAudioFocus()
        if (series.engine == Engines.AUTO && triedEngines.size > 1) sessionEngine = engineKind
        buffering.visibility = View.GONE
        btnPlay.setImageResource(R.drawable.ic_pause)
        scheduleHide()
        startBackgroundNotification()
        updateSession()
    }

    override fun onEnginePaused() {
        btnPlay.setImageResource(R.drawable.ic_play)
        setControlsVisible(true)
        updateSession()
    }

    override fun onEngineBuffering(busy: Boolean) {
        buffering.visibility = if (busy) View.VISIBLE else View.GONE
    }

    override fun onEngineVideoSize() {
        applyOrientation()
        engine.setScale(series.resizeMode)
    }

    override fun onEngineEnded() {
        // Engines report from inside their own callbacks; act once they've returned.
        handler.post {
            if (finished || ended) return@post
            ended = true
            btnPlay.setImageResource(R.drawable.ic_play)
            val d = currentEpisode().duration.takeIf { it > 0 } ?: engine.length
            handleClipEnd(d, d, natural = true)
            updateSession()
        }
    }

    override fun onEngineError() {
        handler.post { if (!finished) recoverFromError() }
    }

    // ---- End of a clip: auto-play, countdown, stop ----

    private fun stopsAfter(i: Int) = if (stopAfterIndex >= 0) i == stopAfterIndex else !series.autoPlay

    /** The clip is over: it reached its end ([natural]) or its outro started. */
    private fun handleClipEnd(position: Long, duration: Long, natural: Boolean) {
        series.recordProgress(currentEpisode(), position, duration, ended = true)
        series.lastWatchedAt = System.currentTimeMillis()
        Store.put(this, series)
        when {
            stopsAfter(index) -> {
                val planned = stopAfterIndex >= 0
                if (planned) stopAfterIndex = -1
                if (!natural) engine.pause()
                setControlsVisible(true)
                updateSleepLabel()
                showToast(
                    when {
                        planned -> "ดูจบตามที่ตั้งไว้แล้ว ราตรีสวัสดิ์ 💤"
                        hasNext() -> "จบตอนนี้แล้ว · กด ⏭ เพื่อดูตอนต่อไป"
                        else -> "จบตอนสุดท้ายแล้ว 🎉"
                    }
                )
            }
            !hasNext() -> {
                if (!natural) engine.pause()
                onSeriesFinished()
            }
            series.countdown -> startCountdown()
            else -> playNextNow()
        }
    }

    private fun startCountdown() {
        if (countdownLeft > 0) return
        hideSkipButton()
        countdownLeft = COUNTDOWN_SECONDS
        updateCountdownText()
        countdownBox.visibility = if (inPip) View.GONE else View.VISIBLE
        handler.removeCallbacks(countdownTick)
        handler.postDelayed(countdownTick, 1_000)
    }

    private fun updateCountdownText() {
        val next = if (hasNext()) "${series.seasonPrefix(index + 1)}${labels.getOrElse(index + 1) { "" }}" else ""
        txtCountdown.text = "ตอนต่อไปใน $countdownLeft วินาที\n$next"
    }

    private fun cancelCountdown() {
        countdownLeft = 0
        handler.removeCallbacks(countdownTick)
        if (::countdownBox.isInitialized) countdownBox.visibility = View.GONE
    }

    private fun playNextNow() {
        cancelCountdown()
        if (hasNext()) openClip(index + 1, series.episodes[index + 1].resumePosition)
    }

    /** "Cancel" on the countdown: stay on this clip and stop when it ends. */
    private fun cancelNext() {
        cancelCountdown()
        stopAfterIndex = index
        updateSleepLabel()
        showToast("ยกเลิกแล้ว · จะหยุดเมื่อจบตอนนี้")
    }

    private fun togglePlay() {
        when {
            ended -> if (hasNext()) playNextNow() else openClip(index, 0L)
            engine.isPlaying -> engine.pause()
            else -> engine.play()
        }
    }

    private fun nextByUser() {
        if (!hasNext()) {
            showToast("นี่คือตอนสุดท้ายแล้ว")
            return
        }
        saveProgress()
        openClip(index + 1, series.episodes[index + 1].resumePosition)
    }

    private fun previousByUser() {
        if (engine.time > 5_000 || index == 0) {
            engine.seek(0L)
            return
        }
        saveProgress()
        openClip(index - 1, series.episodes[index - 1].resumePosition)
    }

    private fun onSeriesFinished() {
        if (finished) return
        finished = true
        series.lastWatchedAt = System.currentTimeMillis()
        Store.put(this, series)
        showToast("ดูครบทุกตอนแล้ว 🎉")
        finish()
    }

    // ---- Skip times ----

    private fun checkSkip() {
        if (ended || !engine.isPlaying) return
        val ep = currentEpisode()
        val pos = engine.time
        val dur = engine.length
        if (dur > 0) ep.duration = dur
        val introStart = series.introStartOf(ep) * 1000L
        val introEnd = series.introEndOf(ep) * 1000L
        val outro = series.outroOf(ep) * 1000L
        val hasIntro = series.skipIntro && introEnd > introStart
        val hasOutro = series.skipOutro && outro > 0 && dur > outro
        val outroStart = if (hasOutro) dur - outro else Long.MAX_VALUE

        if (series.skipManual) {
            when {
                hasIntro && pos >= introStart && pos < introEnd - 1_000 ->
                    showSkipButton("ข้ามเพลงเปิด ⏭") { engine.seek(introEnd) }
                hasOutro && pos >= outroStart && countdownLeft == 0 ->
                    showSkipButton(if (hasNext()) "ตอนต่อไป ⏭" else "จบเรื่อง ⏹") { skipToNextByUser() }
                else -> hideSkipButton()
            }
            return
        }
        if (hasIntro && !introHandled) {
            if (pos >= introEnd) {
                introHandled = true
            } else if (pos >= introStart) {
                introHandled = true
                engine.seek(introEnd)
                showToast("ข้ามเพลงเปิดแล้ว")
            }
        }
        if (hasOutro && !outroHandled && pos >= outroStart) {
            outroHandled = true
            handleClipEnd(pos, dur, natural = false)
        }
    }

    private fun skipToNextByUser() {
        series.recordProgress(currentEpisode(), engine.time, engine.length, ended = true)
        if (hasNext()) {
            playNextNow()
        } else {
            engine.pause()
            onSeriesFinished()
        }
    }

    private fun showSkipButton(label: String, action: () -> Unit) {
        if (locked || inPip || countdownLeft > 0) return hideSkipButton()
        skipAction = action
        if (btnSkip.text.toString() != label) btnSkip.text = label
        btnSkip.visibility = View.VISIBLE
    }

    private fun hideSkipButton() {
        skipAction = null
        if (::btnSkip.isInitialized) btnSkip.visibility = View.GONE
    }

    private fun chooseScope(onChosen: (Boolean) -> Unit) {
        Dialogs.menu(
            this, "ใช้กับ", listOf(
                "ทุกตอนในเรื่องนี้" to { onChosen(false) },
                "เฉพาะตอนนี้" to { onChosen(true) },
            )
        )
    }

    private fun setIntroStart(sec: Int, thisOnly: Boolean) {
        val ep = currentEpisode()
        if (thisOnly) {
            ep.introStart = sec
            if (series.introEndOf(ep) <= sec) ep.introEnd = 0
        } else {
            series.introStart = sec
            if (series.introEnd <= sec) series.introEnd = 0
        }
        series.skipIntro = true
        introHandled = true
        saveProgress()
        showToast("เพลงเปิดเริ่มที่ ${TimeText.format(sec)} — ต่อไปตั้ง “จุดจบเพลงเปิด” ตอนเพลงจบ")
    }

    private fun setIntroEnd(sec: Int, thisOnly: Boolean) {
        val ep = currentEpisode()
        val start = if (thisOnly) series.introStartOf(ep) else series.introStart
        if (sec <= start) {
            showToast("จุดจบต้องอยู่หลังจุดเริ่ม (${TimeText.format(start)})")
            return
        }
        if (thisOnly) {
            ep.introStart = start
            ep.introEnd = sec
        } else {
            series.introEnd = sec
        }
        series.skipIntro = true
        introHandled = true
        saveProgress()
        val scope = if (thisOnly) "เฉพาะตอนนี้" else "ทุกตอน"
        showToast("ตั้งแล้ว: ข้ามเพลงเปิด ${TimeText.format(start)}–${TimeText.format(sec)} ($scope)")
    }

    private fun setOutroFrom(pos: Long, thisOnly: Boolean) {
        val dur = engine.length
        if (dur <= 0) return
        val remaining = ((dur - pos) / 1000).toInt()
        if (remaining <= 0) return
        if (thisOnly) currentEpisode().outroLength = remaining else series.outroLength = remaining
        series.skipOutro = true
        outroHandled = true
        saveProgress()
        val scope = if (thisOnly) "เฉพาะตอนนี้" else "ทุกตอน"
        showToast("ตั้งแล้ว: ข้าม ${TimeText.format(remaining)} สุดท้าย ($scope)")
    }

    private fun editSkipTimes(thisOnly: Boolean) {
        val ep = currentEpisode()
        if (thisOnly) {
            val initial = SkipValues(
                series.introStartOf(ep), series.introEndOf(ep), series.outroOf(ep), series.skipIntro, series.skipOutro,
            )
            Dialogs.skipSettings(
                this, "ข้ามเพลงเฉพาะตอนนี้", initial, showFlags = false,
                onReset = {
                    ep.clearOverride()
                    saveProgress()
                },
            ) { v ->
                ep.introStart = v.introStart
                ep.introEnd = v.introEnd
                ep.outroLength = v.outro
                saveProgress()
            }
        } else {
            val initial = SkipValues(
                series.introStart, series.introEnd, series.outroLength, series.skipIntro, series.skipOutro,
                series.skipManual,
            )
            Dialogs.skipSettings(this, "ข้ามเพลง (ทุกตอน)", initial, showFlags = true, onReset = null) { v ->
                series.introStart = v.introStart
                series.introEnd = v.introEnd
                series.outroLength = v.outro
                series.skipIntro = v.skipIntro
                series.skipOutro = v.skipOutro
                series.skipManual = v.skipManual
                hideSkipButton()
                saveProgress()
            }
        }
    }

    // ---- On-screen controls ----

    private fun setupControls() {
        fun tap(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener {
            action()
            scheduleHide()
        }
        tap(R.id.btnBack) { finish() }
        tap(R.id.btnMore) { showMenu() }
        tap(R.id.btnEngine) { chooseEngine() }
        tap(R.id.btnResize) { setResize((series.resizeMode + 1) % RESIZE_NAMES.size) }
        tap(R.id.btnRotate) { toggleRotation() }
        btnRotate.setOnLongClickListener {
            series.orientation = 0
            applyOrientation()
            updateRotateLabel()
            saveProgress()
            showToast("หมุนจออัตโนมัติตามคลิป")
            true
        }
        tap(R.id.btnPlay) { togglePlay() }
        tap(R.id.btnRew) { seekBy(-seekStepMs) }
        tap(R.id.btnFwd) { seekBy(seekStepMs) }
        tap(R.id.btnSpeed) { chooseSpeed() }
        tap(R.id.btnLock) { lock() }
        tap(R.id.btnEpisodes) { showEpisodesPanel() }
        tap(R.id.btnTracks) { chooseTracks() }
        tap(R.id.btnNextEp) { nextByUser() }
        findViewById<View>(R.id.btnPanelClose).setOnClickListener { hideEpisodesPanel() }
        btnPanelSeason.setOnClickListener { choosePanelSeason() }
        findViewById<View>(R.id.btnPlayNow).setOnClickListener { playNextNow() }
        findViewById<View>(R.id.btnCancelNext).setOnClickListener { cancelNext() }
        btnSkip.setOnClickListener {
            val action = skipAction
            hideSkipButton()
            action?.invoke()
        }
        btnUnlock.setOnClickListener {
            showUnlockHint()
            showToast("แตะค้างเพื่อปลดล็อก")
        }
        btnUnlock.setOnLongClickListener {
            unlock()
            true
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) txtPosition.text = TimeText.format(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                engine.seek(bar.progress * 1000L)
                scheduleHide()
            }
        })
        panelAdapter = SimpleAdapter(R.layout.item_episode_card) { v, i -> bindPanelCard(v, i) }
        panelList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        panelList.adapter = panelAdapter
        updateSpeedLabel()
        updateRotateLabel()
        updateSeekLabels()
    }

    private fun updateSeekLabels() {
        val step = Settings.seekStepSec(this)
        btnRew.text = "↺\n$step"
        btnFwd.text = "↻\n$step"
    }

    private fun setControlsVisible(visible: Boolean) {
        if (!::controls.isInitialized) return
        handler.removeCallbacks(hideControls)
        if (visible && (locked || inPip || episodesPanel.visibility == View.VISIBLE)) return
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            updateProgressUi()
            scheduleHide()
        }
    }

    /** Fade the controls after a few seconds, but only while the video is playing. */
    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (::engine.isInitialized && engine.isPlaying && !dragging) handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
    }

    private fun updateProgressUi() {
        if (!::engine.isInitialized || dragging || controls.visibility != View.VISIBLE) return
        val pos = engine.time
        val dur = engine.length
        txtPosition.text = TimeText.ms(pos)
        if (dur > 0) {
            seekBar.max = (dur / 1000).toInt()
            seekBar.progress = (pos / 1000).toInt()
            txtRemaining.text = "-${TimeText.ms(dur - pos)}"
        } else {
            txtRemaining.text = ""
        }
    }

    private fun updateTitle() {
        txtSeriesName.text = series.name
        txtTitle.text = "${series.seasonPrefix(index)}${labels.getOrElse(index) { currentEpisode().shortName }}"
    }

    private fun seekBy(delta: Long) {
        val dur = engine.length
        var target = engine.time + delta
        if (dur > 0) target = target.coerceAtMost(dur - 500)
        target = target.coerceAtLeast(0L)
        engine.seek(target)
        val sec = abs(delta) / 1000
        showGesture("${if (delta > 0) "⏩ +$sec" else "⏪ −$sec"} วินาที\n${TimeText.ms(target)}")
    }

    private fun showGesture(text: String, hideAfter: Long = 700) {
        txtGesture.text = text
        txtGesture.visibility = View.VISIBLE
        handler.removeCallbacks(hideGesture)
        if (hideAfter > 0) handler.postDelayed(hideGesture, hideAfter)
    }

    // ---- Gestures: tap, double tap, swipe ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    locked -> showUnlockHint()
                    inPip -> Unit
                    episodesPanel.visibility == View.VISIBLE -> hideEpisodesPanel()
                    controls.visibility == View.VISIBLE -> setControlsVisible(false)
                    else -> setControlsVisible(true)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!locked && !inPip) seekBy(if (e.x > videoFrame.width / 2f) seekStepMs else -seekStepMs)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || locked || inPip || !Settings.gestures(this@PlayerActivity)) return false
                if (drag == Drag.NONE) beginDrag(e1, e2)
                updateDrag(e2)
                return true
            }
        })
        videoFrame.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) endDrag()
            true
        }
    }

    private fun beginDrag(start: MotionEvent, now: MotionEvent) {
        val h = videoFrame.height.toFloat()
        val w = videoFrame.width.toFloat()
        dragStartX = start.x
        dragStartY = start.y
        // Leave the screen edges to the system's own swipe gestures.
        if (start.y < h * 0.08f || start.y > h * 0.92f) {
            drag = Drag.IGNORE
            return
        }
        drag = when {
            abs(now.x - start.x) > abs(now.y - start.y) -> Drag.SEEK
            start.x < w / 2 -> Drag.BRIGHTNESS
            else -> Drag.VOLUME
        }
        when (drag) {
            Drag.SEEK -> {
                dragStartPos = engine.time
                dragSeekTarget = dragStartPos
            }
            Drag.BRIGHTNESS -> {
                val current = window.attributes.screenBrightness
                dragStartValue = if (current >= 0) current else systemBrightness()
            }
            Drag.VOLUME -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                dragStartValue = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            }
            else -> Unit
        }
    }

    private fun systemBrightness(): Float = try {
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Exception) {
        0.5f
    }

    private fun updateDrag(now: MotionEvent) {
        val h = videoFrame.height.coerceAtLeast(1).toFloat()
        val w = videoFrame.width.coerceAtLeast(1).toFloat()
        when (drag) {
            Drag.SEEK -> {
                val len = engine.length
                // The full width of the screen is two minutes.
                val delta = ((now.x - dragStartX) / w * 120_000).toLong()
                dragSeekTarget = (dragStartPos + delta).coerceIn(0L, if (len > 0) len - 500 else Long.MAX_VALUE)
                val shift = dragSeekTarget - dragStartPos
                val sign = if (shift >= 0) "+" else "−"
                val total = if (len > 0) " / ${TimeText.ms(len)}" else ""
                showGesture("${if (shift >= 0) "⏩" else "⏪"} $sign${TimeText.ms(abs(shift))}\n${TimeText.ms(dragSeekTarget)}$total", 0)
            }
            Drag.BRIGHTNESS -> {
                val v = (dragStartValue + (dragStartY - now.y) / h * 1.2f).coerceIn(0.01f, 1f)
                window.attributes = window.attributes.apply { screenBrightness = v }
                showGesture("☀ ความสว่าง ${(v * 100).roundToInt()}%", 0)
            }
            Drag.VOLUME -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val v = (dragStartValue + (dragStartY - now.y) / h * 1.2f).coerceIn(0f, 1f)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
                showGesture("🔊 เสียง ${(v * 100).roundToInt()}%", 0)
            }
            else -> Unit
        }
    }

    private fun endDrag() {
        if (drag == Drag.SEEK) engine.seek(dragSeekTarget)
        if (drag != Drag.NONE && drag != Drag.IGNORE) {
            handler.removeCallbacks(hideGesture)
            handler.postDelayed(hideGesture, 500)
        }
        drag = Drag.NONE
    }

    // ---- Lock ----

    private fun lock() {
        setControlsVisible(false)
        locked = true
        lockedBack.isEnabled = true
        episodesPanel.visibility = View.GONE
        hideSkipButton()
        showUnlockHint()
        showToast("ล็อกหน้าจอแล้ว · แตะค้างที่ 🔓 เพื่อปลดล็อก")
    }

    private fun unlock() {
        locked = false
        lockedBack.isEnabled = false
        handler.removeCallbacks(hideUnlock)
        btnUnlock.visibility = View.GONE
        setControlsVisible(true)
        showToast("ปลดล็อกแล้ว")
    }

    private fun showUnlockHint() {
        if (inPip) return
        btnUnlock.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3_000)
    }

    // ---- Picture size, rotation, speed, tracks ----

    private fun setResize(mode: Int) {
        series.resizeMode = mode
        engine.setScale(mode)
        saveProgress()
        showToast("ขนาดภาพ: ${RESIZE_NAMES[mode]}")
    }

    private fun applyOrientation() {
        if (inPip) return
        val want = when (series.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> {
                val portrait = if (::engine.isInitialized) engine.isPortraitVideo() else null
                if (portrait == true) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
        if (requestedOrientation != want) requestedOrientation = want
    }

    private fun toggleRotation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        series.orientation = if (landscape) 2 else 1
        applyOrientation()
        updateRotateLabel()
        saveProgress()
        showToast(if (landscape) "ล็อกแนวตั้ง · แตะค้างเพื่อหมุนอัตโนมัติ" else "ล็อกแนวนอน · แตะค้างเพื่อหมุนอัตโนมัติ")
    }

    private fun updateRotateLabel() {
        btnRotate.text = when (series.orientation) {
            1 -> "แนวนอน"
            2 -> "แนวตั้ง"
            3 -> "ตามเครื่อง"
            else -> "หมุนจอ"
        }
    }

    private fun speedText(x: Float) = if (x == 1f) "1x" else "${x}x".replace(".0x", "x")

    private fun updateSpeedLabel() {
        btnSpeed.text = "ความเร็ว ${speedText(series.speed)}"
    }

    private fun chooseSpeed() {
        Dialogs.choose(
            this, "ความเร็วในการเล่น",
            SPEEDS.map { speedText(it) + if (it == 1f) " (ปกติ)" else "" },
            SPEEDS.indexOf(series.speed),
        ) { i ->
            series.speed = SPEEDS[i]
            engine.setSpeed(series.speed)
            updateSpeedLabel()
            saveProgress()
        }
    }

    private fun chooseTracks() {
        val items = engine.trackItems()
        if (items.isEmpty()) {
            showToast("ไฟล์นี้ไม่มีเสียงหรือซับไตเติลให้เลือก")
            return
        }
        Dialogs.menu(this, "เสียงและซับไตเติล", items)
    }

    // ---- Menus ----

    private fun check(on: Boolean) = if (on) "✅" else "⬜"

    private fun showMenu() {
        val pos = engine.time
        val sec = (pos / 1000).toInt()
        val now = TimeText.format(sec)
        val ep = currentEpisode()
        val introNote = if (series.introEndOf(ep) > series.introStartOf(ep)) {
            " (${TimeText.format(series.introStartOf(ep))}–${TimeText.format(series.introEndOf(ep))})"
        } else {
            " (ยังไม่ได้ตั้ง)"
        }
        val outroNote = if (series.outroOf(ep) > 0) " (${TimeText.format(series.outroOf(ep))} สุดท้าย)" else " (ยังไม่ได้ตั้ง)"
        val sleepNote = when {
            sleepAt > 0 -> " (เหลือ ${TimeText.ms(sleepAt - System.currentTimeMillis())})"
            stopAfterIndex >= 0 -> " (หยุดหลังตอนที่ ${stopAfterIndex + 1})"
            else -> ""
        }
        Dialogs.menu(
            this, "${series.seasonPrefix(index)}${labels.getOrElse(index) { ep.shortName }}", listOf(
                "🎬 ภาพแตก / เล่นไม่ได้? เปลี่ยนตัวเล่น (ตอนนี้: ${Engines.shortName(engineKind)})" to { chooseEngine() },
                "⏾ ตั้งเวลาปิด$sleepNote" to { showSleepMenu() },
                "1️⃣ ดูอีกตอนเดียวแล้วหยุด" to { oneMoreEpisode() },
                "🗗 ดูแบบจอลอย" to { enterPip() },
                "${check(series.skipIntro)} ข้ามเพลงเปิด$introNote" to {
                    series.skipIntro = !series.skipIntro
                    saveProgress()
                    showToast(if (series.skipIntro) "เปิดการข้ามเพลงเปิด" else "ปิดการข้ามเพลงเปิด")
                },
                "${check(series.skipOutro)} ข้ามเพลงปิด$outroNote" to {
                    series.skipOutro = !series.skipOutro
                    saveProgress()
                    showToast(if (series.skipOutro) "เปิดการข้ามเพลงปิด" else "ปิดการข้ามเพลงปิด")
                },
                "⏱ ตอนนี้ ($now) = จุดเริ่มเพลงเปิด" to { chooseScope { setIntroStart(sec, it) } },
                "⏱ ตอนนี้ ($now) = จุดจบเพลงเปิด" to { chooseScope { setIntroEnd(sec, it) } },
                "⏱ ตอนนี้ ($now) = จุดเริ่มเพลงปิด" to { chooseScope { setOutroFrom(pos, it) } },
                "⚙ พิมพ์เวลาข้ามเพลงเอง" to { chooseScope { editSkipTimes(it) } },
                "🎛 ตั้งค่าการเล่นอื่นๆ" to { showPlaybackSettings() },
            )
        )
    }

    private fun showPlaybackSettings() {
        val orientation = ORIENTATION_NAMES[series.orientation.coerceIn(0, ORIENTATION_NAMES.size - 1)]
        val resize = RESIZE_NAMES[series.resizeMode.coerceIn(0, RESIZE_NAMES.size - 1)]
        val items = mutableListOf<Pair<CharSequence, () -> Unit>>(
            "${check(series.autoPlay)} เล่นตอนต่อไปอัตโนมัติ" to {
                series.autoPlay = !series.autoPlay
                saveProgress()
                showToast(if (series.autoPlay) "จบตอนแล้วไปตอนต่อไปเลย" else "จบตอนแล้วจะหยุดรอ")
            },
            "${check(series.countdown)} นับถอยหลัง $COUNTDOWN_SECONDS วินาทีก่อนไปตอนต่อไป" to {
                series.countdown = !series.countdown
                saveProgress()
            },
            "${check(series.skipManual)} ข้ามเพลงแบบกดปุ่มเอง (แบบ Netflix)" to {
                series.skipManual = !series.skipManual
                hideSkipButton()
                // Don't jump right away if the switch to auto happens mid-intro or mid-outro.
                introHandled = true
                outroHandled = true
                saveProgress()
            },
            "📱 การหมุนจอ: $orientation" to {
                Dialogs.choose(this, "การหมุนจอ", ORIENTATION_NAMES, series.orientation) { i ->
                    series.orientation = i
                    applyOrientation()
                    updateRotateLabel()
                    saveProgress()
                }
            },
            "🔲 ขนาดภาพ: $resize" to {
                Dialogs.choose(this, "ขนาดภาพ", RESIZE_NAMES, series.resizeMode) { setResize(it) }
            },
            "⏲ ความเร็ว: ${speedText(series.speed)}" to { chooseSpeed() },
            "💬 เสียงและซับไตเติล" to { chooseTracks() },
        )
        if (engineKind == Engines.VLC) {
            items.add("${check(!series.vlcHardware)} VLC ถอดรหัสด้วยซอฟต์แวร์ (แก้ภาพแตก กินแบตขึ้น)" to {
                series.vlcHardware = !series.vlcHardware
                val pos = engine.time
                saveProgress()
                startOn(Engines.VLC, pos, fresh = true)
            })
        }
        items.add("${check(Settings.backgroundAudio(this))} เล่นเสียงต่อเมื่อปิดจอ (ทุกเรื่อง)" to {
            val on = !Settings.backgroundAudio(this)
            Settings.setBackgroundAudio(this, on)
            if (on) startBackgroundNotification() else stopBackgroundNotification()
            showToast(if (on) "ปิดจอแล้วเสียงจะเล่นต่อ" else "ปิดจอแล้วจะหยุดเล่น")
        })
        items.add("${check(Settings.autoPip(this))} เข้าจอลอยอัตโนมัติเมื่อกดออก (ทุกเรื่อง)" to {
            Settings.setAutoPip(this, !Settings.autoPip(this))
        })
        items.add("${check(Settings.gestures(this))} ปัดจอเพื่อกรอ / ปรับแสง / ปรับเสียง (ทุกเรื่อง)" to {
            Settings.setGestures(this, !Settings.gestures(this))
        })
        Dialogs.menu(this, "ตั้งค่าการเล่น · ${series.name}", items)
    }

    // ---- Sleep timer ----

    private fun showSleepMenu() {
        val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
        if (sleepAt > 0 || stopAfterIndex >= 0) {
            items.add("⏹ ยกเลิกการตั้งเวลา" to {
                setSleep(0L, -1)
                showToast("ยกเลิกการตั้งเวลาแล้ว")
            })
        }
        listOf(15, 30, 45, 60, 90).forEach { m ->
            items.add("หยุดหลัง $m นาที" to {
                setSleep(System.currentTimeMillis() + m * 60_000L, -1)
                showToast("จะหยุดเล่นใน $m นาที")
            })
        }
        items.add("หยุดเมื่อจบตอนนี้" to {
            setSleep(0L, index)
            showToast("จะหยุดเมื่อจบตอนนี้")
        })
        items.add("ดูอีกตอนเดียวแล้วหยุด" to { oneMoreEpisode() })
        Dialogs.menu(this, "⏾ ตั้งเวลาปิด", items)
    }

    private fun setSleep(at: Long, stopAfter: Int) {
        sleepAt = at
        stopAfterIndex = stopAfter
        updateSleepLabel()
    }

    private fun oneMoreEpisode() {
        val target = if (hasNext()) index + 1 else index
        setSleep(0L, target)
        showToast("ดูต่ออีกตอนเดียว · จะหยุดเมื่อจบตอนที่ ${target + 1}")
    }

    private fun checkSleep() {
        if (sleepAt == 0L) return
        if (System.currentTimeMillis() >= sleepAt) {
            sleepAt = 0L
            engine.pause()
            saveProgress()
            showToast("หมดเวลาที่ตั้งไว้ หยุดเล่นแล้ว 💤")
        }
        updateSleepLabel()
    }

    private fun updateSleepLabel() {
        if (!::txtSleep.isInitialized) return
        val text = when {
            sleepAt > 0 -> "⏾ ${TimeText.ms(sleepAt - System.currentTimeMillis())}"
            stopAfterIndex >= 0 -> "⏾ หลังตอนที่ ${stopAfterIndex + 1}"
            else -> null
        }
        txtSleep.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) txtSleep.text = text
    }

    // ---- Episode picker ----

    private fun showEpisodesPanel() {
        setControlsVisible(false)
        panelSeason = currentEpisode().season
        renderPanel()
        episodesPanel.visibility = View.VISIBLE
    }

    private fun hideEpisodesPanel() {
        episodesPanel.visibility = View.GONE
        setControlsVisible(true)
    }

    private fun renderPanel() {
        val seasons = series.seasons()
        if (seasons.size > 1) {
            btnPanelSeason.visibility = View.VISIBLE
            btnPanelSeason.text = "${TimeText.seasonLabel(panelSeason)} ▾"
        } else {
            btnPanelSeason.visibility = View.GONE
        }
        val shown = series.episodes.indices.filter { seasons.size <= 1 || series.episodes[it].season == panelSeason }
        panelAdapter.items = shown
        val at = shown.indexOf(index)
        if (at >= 0) (panelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(at, 48)
    }

    private fun choosePanelSeason() {
        val seasons = series.seasons()
        Dialogs.choose(this, "เลือกภาค", seasons.map { TimeText.seasonLabel(it) }, seasons.indexOf(panelSeason)) { i ->
            panelSeason = seasons[i]
            renderPanel()
        }
    }

    private fun bindPanelCard(v: View, i: Int) {
        val ep = series.episodes[i]
        val current = i == index
        Thumbs.load(this, ep.uri, v.findViewById<ImageView>(R.id.imgThumb))
        v.findViewById<MaterialCardView>(R.id.card).strokeWidth =
            if (current) (3 * resources.displayMetrics.density).toInt() else 0
        v.findViewById<TextView>(R.id.txtTitle).text = labels.getOrElse(i) { ep.shortName }
        v.findViewById<TextView>(R.id.txtSubtitle).text = if (current) "▶ กำลังเล่น" else TimeText.progress(ep)
        v.findViewById<ProgressBar>(R.id.progress).showPercent(TimeText.percent(ep))
        v.setOnClickListener {
            episodesPanel.visibility = View.GONE
            if (current) {
                engine.play()
            } else {
                saveProgress()
                openClip(i, ep.resumePosition)
            }
        }
    }

    // ---- Picture-in-picture ----

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            showToast("เครื่องนี้ไม่รองรับจอลอย")
            return
        }
        val ratio = (engine.videoAspect() ?: (16f / 9f)).coerceIn(0.42f, 2.38f)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .build()
        try {
            enterPictureInPictureMode(params)
        } catch (e: IllegalStateException) {
            showToast("เปิดจอลอยไม่ได้")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::engine.isInitialized && Settings.autoPip(this) && engine.isPlaying && !locked) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            controls.visibility = View.GONE
            episodesPanel.visibility = View.GONE
            countdownBox.visibility = View.GONE
            btnUnlock.visibility = View.GONE
            txtGesture.visibility = View.GONE
            hideSkipButton()
        } else {
            // Closing the floating window leaves the activity stopped; treat that as closing the player.
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                finish()
                return
            }
            if (countdownLeft > 0) countdownBox.visibility = View.VISIBLE
            applyOrientation()
        }
    }

    // ---- Media session and notification ----

    private fun setupSession() {
        session = MediaSession(this, "AutoPlayVideo")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (::engine.isInitialized && !engine.isPlaying) togglePlay()
            }

            override fun onPause() {
                if (::engine.isInitialized) engine.pause()
            }

            override fun onSkipToNext() = nextByUser()

            override fun onSkipToPrevious() = previousByUser()

            override fun onSeekTo(pos: Long) {
                if (::engine.isInitialized) engine.seek(pos)
            }

            override fun onStop() {
                if (::engine.isInitialized) engine.pause()
            }
        })
        session.isActive = true
    }

    private fun updateSession() {
        if (!::session.isInitialized || !::engine.isInitialized || finished) return
        val ep = currentEpisode()
        val playing = engine.isPlaying
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, labels.getOrElse(index) { ep.shortName })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, series.name)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, engine.length)
        Thumbs.cached(ep.uri)?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        session.setMetadata(meta.build())
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    engine.time,
                    if (playing) series.speed else 0f,
                )
                .build()
        )
        if (notificationShown) {
            val n = PlaybackNotification.build(this, notificationTitle(), series.name, ep.uri, playing, session.sessionToken)
            PlaybackNotification.current = n
            getSystemService(NotificationManager::class.java).notify(PlaybackNotification.ID, n)
        }
    }

    /** Shows the "now playing" notification, which keeps sound going while the screen is off. */
    private fun startBackgroundNotification() {
        if (notificationShown || finished || isFinishing || !Settings.backgroundAudio(this)) return
        if (Build.VERSION.SDK_INT >= 33 && !Settings.notificationsAsked(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Settings.setNotificationsAsked(this)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PlaybackNotification.current = PlaybackNotification.build(
            this, notificationTitle(), series.name, currentEpisode().uri, true, session.sessionToken
        )
        notificationShown = KeepAliveService.start(this)
    }

    private fun notificationTitle() = "${series.seasonPrefix(index)}${labels.getOrElse(index) { currentEpisode().shortName }}"

    private fun stopBackgroundNotification() {
        if (!notificationShown) return
        notificationShown = false
        KeepAliveService.stop(this)
    }

    private fun showFirstTimeHint() {
        if (Settings.hintCount(this) >= 3) return
        Settings.bumpHint(this)
        handler.postDelayed({
            if (!finished) showToast("ภาพแตกหรือเล่นไม่ได้? แตะปุ่ม 🎬 ด้านบนเพื่อเปลี่ยนตัวเล่น", long = true)
        }, 3_000)
    }

    // ---- Progress and lifecycle ----

    private fun saveProgress() {
        if (!::engine.isInitialized || finished) return
        series.recordProgress(currentEpisode(), engine.time, engine.length, ended = ended)
        series.lastIndex = index
        series.lastWatchedAt = System.currentTimeMillis()
        lastSavedAt = series.lastWatchedAt
        Store.put(this, series)
    }

    private fun showToast(msg: String, long: Boolean = false) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (::engine.isInitialized) engine.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!::engine.isInitialized) return
        saveProgress()
        if (keepPlayingInBackground(engine.isPlaying)) {
            engine.onBackground()
        } else {
            handler.removeCallbacks(ticker)
            engine.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            unregisterReceiver(mediaReceiver)
            unregisterReceiver(noisyReceiver)
        }
        abandonAudioFocus()
        stopBackgroundNotification()
        if (::session.isInitialized) session.release()
        if (::engine.isInitialized) engine.release()
    }
}
