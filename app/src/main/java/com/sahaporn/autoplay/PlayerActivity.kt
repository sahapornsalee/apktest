package com.sahaporn.autoplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "series_id"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION = "position"

        private const val COUNTDOWN_SECONDS = 5
        private const val SEEK_STEP_MS = 10_000L
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

    private lateinit var series: Series
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var txtTitle: TextView
    private lateinit var txtSleep: TextView
    private lateinit var txtSeek: TextView
    private lateinit var btnSkip: Button
    private lateinit var countdownBox: View
    private lateinit var txtCountdown: TextView
    private lateinit var btnUnlock: Button

    private val handler = Handler(Looper.getMainLooper())
    private var introHandled = false
    private var outroHandled = false
    private var finished = false
    private var toast: Toast? = null

    // Last known spot of the clip being played, so it can be saved after the player has moved on.
    private var trackedIndex = -1
    private var trackedPosition = 0L
    private var trackedDuration = C.TIME_UNSET
    private var lastSavedAt = 0L

    private var locked = false
    private var inPip = false

    /** Wall-clock time when the sleep timer pauses playback; 0 = off. */
    private var sleepAt = 0L

    /** Stop when this clip ends ("stop after this one" / "one more episode"); -1 = off. */
    private var stopAfterIndex = -1
    private var countdownLeft = 0
    private var skipAction: (() -> Unit)? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val ticker = object : Runnable {
        override fun run() {
            track()
            checkSkip()
            checkSleep()
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

    private val hideSeek = Runnable { txtSeek.visibility = View.GONE }
    private val hideUnlock = Runnable { btnUnlock.visibility = View.GONE }

    private val lockedBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showUnlockHint()
            showToast("หน้าจอล็อกอยู่ · แตะค้างที่ปุ่ม 🔓 เพื่อปลดล็อก")
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = player.currentMediaItemIndex
            if (trackedIndex in series.episodes.indices && trackedIndex != index) {
                record(
                    series.episodes[trackedIndex], trackedPosition, trackedDuration,
                    ended = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                )
            }
            trackedIndex = index
            trackedPosition = player.currentPosition
            trackedDuration = C.TIME_UNSET
            introHandled = false
            outroHandled = false
            cancelCountdown()
            hideSkipButton()
            // Jumping past the clip a stop was set for cancels that stop.
            if (stopAfterIndex in 0 until index) stopAfterIndex = -1
            // Moving to another clip (auto-next, next button, chosen from the list) resumes where it was left.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                val resume = series.episodes[index].resumePosition
                if (resume > 0 && abs(player.currentPosition - resume) > 2_000) player.seekTo(resume)
            }
            updatePauseAtEnd()
            updateSleepLabel()
            updateTitle()
            saveProgress()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // The player stops itself at the end of a clip when we asked it to (auto-play off, countdown, sleep).
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                onClipEnded(player.currentPosition, player.duration)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                record(currentEpisode(), player.currentPosition, player.duration, ended = true)
                onSeriesFinished()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyOrientation()
        }

        override fun onPlayerError(error: PlaybackException) {
            showToast("เล่นไฟล์นี้ไม่ได้ (${error.errorCodeName})")
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
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

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        txtTitle = findViewById(R.id.txtTitle)
        txtSleep = findViewById(R.id.txtSleep)
        txtSeek = findViewById(R.id.txtSeek)
        btnSkip = findViewById(R.id.btnSkip)
        countdownBox = findViewById(R.id.countdownBox)
        txtCountdown = findViewById(R.id.txtCountdown)
        btnUnlock = findViewById(R.id.btnUnlock)

        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        playerView.player = player
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = if (locked || inPip) View.GONE else visibility
            }
        )
        setupGestures()

        val items = s.episodes.map {
            MediaItem.Builder()
                .setUri(Uri.parse(it.uri))
                .setMediaId(it.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).setArtist(s.name).build())
                .build()
        }
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1)
        val startPos = intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L)
        player.addListener(listener)
        player.setMediaItems(items, startIndex, startPos)
        player.prepare()
        player.playWhenReady = true

        applyResize()
        applyOrientation()
        updatePauseAtEnd()
        onBackPressedDispatcher.addCallback(this, lockedBack)

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.btnLock).setOnClickListener { lock() }
        findViewById<Button>(R.id.btnResize).setOnClickListener { cycleResize() }
        findViewById<Button>(R.id.btnPlayNow).setOnClickListener { playNextNow() }
        findViewById<Button>(R.id.btnCancelNext).setOnClickListener { cancelNext() }
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

        if (Settings.backgroundAudio(this)) connectSession()
        updateTitle()
    }

    private fun currentEpisode(): Episode = series.episodes[player.currentMediaItemIndex]

    // ---- Gestures and lock ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    locked -> showUnlockHint()
                    inPip -> Unit
                    playerView.isControllerFullyVisible -> playerView.hideController()
                    else -> playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked || inPip) return true
                seekBy(if (e.x > playerView.width / 2f) SEEK_STEP_MS else -SEEK_STEP_MS)
                return true
            }
        })
        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun seekBy(delta: Long) {
        val dur = player.duration
        var target = player.currentPosition + delta
        if (dur != C.TIME_UNSET) target = target.coerceAtMost(dur - 500)
        target = target.coerceAtLeast(0L)
        player.seekTo(target)
        val arrow = if (delta > 0) "⏩ +10 วินาที" else "⏪ −10 วินาที"
        txtSeek.text = "$arrow\n${TimeText.ms(target)}"
        txtSeek.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, 700)
    }

    private fun lock() {
        locked = true
        lockedBack.isEnabled = true
        playerView.hideController()
        playerView.useController = false
        topBar.visibility = View.GONE
        hideSkipButton()
        showUnlockHint()
        showToast("ล็อกหน้าจอแล้ว · แตะค้างที่ 🔓 เพื่อปลดล็อก")
    }

    private fun unlock() {
        locked = false
        lockedBack.isEnabled = false
        handler.removeCallbacks(hideUnlock)
        btnUnlock.visibility = View.GONE
        playerView.useController = true
        playerView.showController()
        showToast("ปลดล็อกแล้ว")
    }

    private fun showUnlockHint() {
        if (inPip) return
        btnUnlock.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3_000)
    }

    // ---- Picture size and rotation ----

    private fun applyResize() {
        playerView.resizeMode = when (series.resizeMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun setResize(mode: Int) {
        series.resizeMode = mode
        applyResize()
        saveProgress()
        showToast("ขนาดภาพ: ${RESIZE_NAMES[mode]}")
    }

    private fun cycleResize() = setResize((series.resizeMode + 1) % RESIZE_NAMES.size)

    private fun applyOrientation() {
        if (!::player.isInitialized || inPip) return
        val want = when (series.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> {
                val v = player.videoSize
                if (v.width > 0 && v.height > v.width * v.pixelWidthHeightRatio) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
        if (requestedOrientation != want) requestedOrientation = want
    }

    // ---- Picture-in-picture and background audio ----

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            showToast("เครื่องนี้ไม่รองรับจอลอย")
            return
        }
        val v = player.videoSize
        val ratio = if (v.width > 0 && v.height > 0) {
            (v.width * v.pixelWidthHeightRatio / v.height).coerceIn(0.42f, 2.38f)
        } else {
            16f / 9f
        }
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
        if (::player.isInitialized && Settings.autoPip(this) && player.isPlaying && !locked) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            playerView.hideController()
            playerView.useController = false
            topBar.visibility = View.GONE
            hideSkipButton()
            countdownBox.visibility = View.GONE
            btnUnlock.visibility = View.GONE
        } else {
            // Closing the floating window leaves the activity stopped; treat that as closing the player.
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                finish()
                return
            }
            playerView.useController = !locked
            if (countdownLeft > 0) countdownBox.visibility = View.VISIBLE
            applyOrientation()
        }
    }

    private fun connectSession() {
        if (controllerFuture != null) return
        PlayerHolder.player = player
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    private fun toggleBackgroundAudio() {
        val on = !Settings.backgroundAudio(this)
        Settings.setBackgroundAudio(this, on)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            connectSession()
            showToast("ปิดจอแล้วเสียงจะเล่นต่อ · ควบคุมได้จากแถบแจ้งเตือน")
        } else {
            showToast("ปิดจอแล้วจะหยุดเล่น")
        }
    }

    // ---- Menus ----

    private fun check(on: Boolean) = if (on) "✅" else "⬜"

    private fun showMenu() {
        val pos = player.currentPosition
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
            this, "เมนู · ตอนที่ ${player.currentMediaItemIndex + 1}", listOf(
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
                "⏱ ตอนนี้ ($now) = จุดเริ่มเพลงเปิด" to { chooseScope { only -> setIntroStart(sec, only) } },
                "⏱ ตอนนี้ ($now) = จุดจบเพลงเปิด" to { chooseScope { only -> setIntroEnd(sec, only) } },
                "⏱ ตอนนี้ ($now) = จุดเริ่มเพลงปิด" to { chooseScope { only -> setOutroFrom(pos, only) } },
                "⚙ พิมพ์เวลาข้ามเพลงเอง" to { chooseScope { only -> editSkipTimes(only) } },
                "🎛 ตั้งค่าการเล่น" to { showPlaybackSettings() },
                "☰ เลือกตอน" to { chooseEpisode() },
                "⏭ ตอนต่อไป" to {
                    if (player.hasNextMediaItem()) playNextNow() else showToast("นี่คือตอนสุดท้ายแล้ว")
                },
            )
        )
    }

    private fun showPlaybackSettings() {
        val orientation = ORIENTATION_NAMES[series.orientation.coerceIn(0, ORIENTATION_NAMES.size - 1)]
        val resize = RESIZE_NAMES[series.resizeMode.coerceIn(0, RESIZE_NAMES.size - 1)]
        Dialogs.menu(
            this, "ตั้งค่าการเล่น (${series.name})", listOf(
                "${check(series.autoPlay)} เล่นตอนต่อไปอัตโนมัติ" to {
                    series.autoPlay = !series.autoPlay
                    updatePauseAtEnd()
                    saveProgress()
                    showToast(if (series.autoPlay) "จบตอนแล้วไปตอนต่อไปเลย" else "จบตอนแล้วจะหยุดรอ")
                },
                "${check(series.countdown)} นับถอยหลัง $COUNTDOWN_SECONDS วินาทีก่อนไปตอนต่อไป" to {
                    series.countdown = !series.countdown
                    updatePauseAtEnd()
                    saveProgress()
                    showToast(if (series.countdown) "เปิดการนับถอยหลัง" else "ปิดการนับถอยหลัง")
                },
                "${check(series.skipManual)} ข้ามเพลงแบบกดปุ่มเอง (แบบ Netflix)" to {
                    series.skipManual = !series.skipManual
                    hideSkipButton()
                    // Don't jump right away if the switch to auto happens mid-intro or mid-outro.
                    introHandled = true
                    outroHandled = true
                    saveProgress()
                    showToast(if (series.skipManual) "จะแสดงปุ่มให้กดข้ามเอง" else "จะข้ามให้อัตโนมัติ")
                },
                "📱 การหมุนจอ: $orientation" to {
                    Dialogs.menu(this, "การหมุนจอ", ORIENTATION_NAMES.mapIndexed { i, name ->
                        "${if (i == series.orientation) "✓ " else ""}$name" to {
                            series.orientation = i
                            applyOrientation()
                            saveProgress()
                        }
                    })
                },
                "🔲 ขนาดภาพ: $resize" to {
                    Dialogs.menu(this, "ขนาดภาพ", RESIZE_NAMES.mapIndexed { i, name ->
                        "${if (i == series.resizeMode) "✓ " else ""}$name" to { setResize(i) }
                    })
                },
                "${check(Settings.backgroundAudio(this))} เล่นเสียงต่อเมื่อปิดจอ (ทุกเรื่อง)" to { toggleBackgroundAudio() },
                "${check(Settings.autoPip(this))} เข้าจอลอยอัตโนมัติเมื่อกดออก (ทุกเรื่อง)" to {
                    val on = !Settings.autoPip(this)
                    Settings.setAutoPip(this, on)
                    showToast(if (on) "กดปุ่มโฮมระหว่างดูจะเป็นจอลอย" else "ปิดจอลอยอัตโนมัติ")
                },
            )
        )
    }

    private fun chooseScope(onChosen: (Boolean) -> Unit) {
        Dialogs.menu(
            this, "ใช้กับ", listOf(
                "ทุกตอนในเรื่องนี้" to { onChosen(false) },
                "เฉพาะตอนนี้" to { onChosen(true) },
            )
        )
    }

    private fun chooseEpisode() {
        val current = player.currentMediaItemIndex
        Dialogs.menu(
            this, "เลือกตอน", series.episodes.mapIndexed { i, e ->
                "${if (i == current) "▶ " else ""}${i + 1}. ${e.name} · ${TimeText.progress(e)}" to {
                    player.seekTo(i, e.resumePosition)
                }
            }
        )
    }

    // ---- Skip times ----

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
        val dur = player.duration
        if (dur == C.TIME_UNSET) return
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

    private fun checkSkip() {
        if (!::player.isInitialized || finished || player.playbackState != Player.STATE_READY) return
        val ep = series.episodes.getOrNull(player.currentMediaItemIndex) ?: return
        val pos = player.currentPosition
        val dur = player.duration

        val introStart = series.introStartOf(ep) * 1000L
        val introEnd = series.introEndOf(ep) * 1000L
        val outro = series.outroOf(ep) * 1000L
        val hasIntro = series.skipIntro && introEnd > introStart
        val hasOutro = series.skipOutro && outro > 0 && dur != C.TIME_UNSET && dur > outro
        val outroStart = if (hasOutro) dur - outro else Long.MAX_VALUE

        if (series.skipManual) {
            when {
                hasIntro && pos >= introStart && pos < introEnd - 1_000 ->
                    showSkipButton("ข้ามเพลงเปิด ⏭") { player.seekTo(introEnd) }
                hasOutro && pos >= outroStart && countdownLeft == 0 ->
                    showSkipButton(if (player.hasNextMediaItem()) "ตอนต่อไป ⏭" else "จบเรื่อง ⏹") { skipToNextByUser() }
                else -> hideSkipButton()
            }
            return
        }

        if (hasIntro && !introHandled) {
            if (pos >= introEnd) {
                introHandled = true
            } else if (pos >= introStart) {
                introHandled = true
                player.seekTo(introEnd)
                showToast("ข้ามเพลงเปิดแล้ว")
            }
        }
        if (hasOutro && !outroHandled && pos >= outroStart) {
            outroHandled = true
            onClipEnded(pos, dur)
        }
    }

    private fun showSkipButton(label: String, action: () -> Unit) {
        if (locked || inPip || countdownLeft > 0) {
            hideSkipButton()
            return
        }
        skipAction = action
        if (btnSkip.text.toString() != label) btnSkip.text = label
        btnSkip.visibility = View.VISIBLE
    }

    private fun hideSkipButton() {
        skipAction = null
        if (::btnSkip.isInitialized) btnSkip.visibility = View.GONE
    }

    private fun skipToNextByUser() {
        if (player.hasNextMediaItem()) {
            playNextNow()
        } else {
            player.pause()
            record(currentEpisode(), player.currentPosition, player.duration, ended = true)
            onSeriesFinished()
        }
    }

    // ---- End of a clip: auto-play, countdown, stop ----

    private fun stopsAfter(index: Int) = if (stopAfterIndex >= 0) index == stopAfterIndex else !series.autoPlay

    /** Let the player pause by itself at the end of the clip whenever we may not move straight on. */
    private fun updatePauseAtEnd() {
        if (!::player.isInitialized) return
        player.pauseAtEndOfMediaItems = series.countdown || stopsAfter(player.currentMediaItemIndex)
    }

    /** The clip is over: either it reached its end, or its outro started. */
    private fun onClipEnded(position: Long, duration: Long) {
        val index = player.currentMediaItemIndex
        when {
            stopsAfter(index) -> {
                val planned = stopAfterIndex >= 0
                if (planned) stopAfterIndex = -1
                stopAtEndOfClip(position, duration, if (planned) "ดูจบตามที่ตั้งไว้แล้ว ราตรีสวัสดิ์ 💤" else null)
                updatePauseAtEnd()
                updateSleepLabel()
            }
            !player.hasNextMediaItem() -> {
                player.pause()
                record(currentEpisode(), position, duration, ended = true)
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
        val next = series.episodes.getOrNull(player.currentMediaItemIndex + 1)?.name ?: ""
        txtCountdown.text = "ตอนต่อไปใน $countdownLeft วินาที\n$next"
    }

    private fun cancelCountdown() {
        countdownLeft = 0
        handler.removeCallbacks(countdownTick)
        if (::countdownBox.isInitialized) countdownBox.visibility = View.GONE
    }

    private fun playNextNow() {
        cancelCountdown()
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        }
    }

    /** "Cancel" on the countdown: stay on this clip and stop when it ends. */
    private fun cancelNext() {
        cancelCountdown()
        stopAfterIndex = player.currentMediaItemIndex
        updatePauseAtEnd()
        updateSleepLabel()
        showToast("ยกเลิกแล้ว · จะหยุดเมื่อจบตอนนี้")
    }

    /** Pause, count the clip as watched and wait for the user. */
    private fun stopAtEndOfClip(position: Long, duration: Long, message: String?) {
        player.pause()
        record(currentEpisode(), position, duration, ended = true)
        series.lastWatchedAt = System.currentTimeMillis()
        Store.put(this, series)
        if (!locked && !inPip) playerView.showController()
        showToast(
            message ?: if (player.hasNextMediaItem()) "จบตอนนี้แล้ว · กด ⏭ เพื่อดูตอนต่อไป" else "จบตอนสุดท้ายแล้ว 🎉"
        )
    }

    // ---- Sleep timer ----

    private fun showSleepMenu() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
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
            setSleep(0L, player.currentMediaItemIndex)
            showToast("จะหยุดเมื่อจบตอนนี้")
        })
        items.add("ดูอีกตอนเดียวแล้วหยุด" to { oneMoreEpisode() })
        Dialogs.menu(this, "⏾ ตั้งเวลาปิด", items)
    }

    private fun setSleep(at: Long, stopAfter: Int) {
        sleepAt = at
        stopAfterIndex = stopAfter
        updatePauseAtEnd()
        updateSleepLabel()
    }

    private fun oneMoreEpisode() {
        val i = player.currentMediaItemIndex
        val target = if (player.hasNextMediaItem()) i + 1 else i
        setSleep(0L, target)
        showToast("ดูต่ออีกตอนเดียว · จะหยุดเมื่อจบตอนที่ ${target + 1}")
    }

    private fun checkSleep() {
        if (sleepAt == 0L || !::player.isInitialized) return
        if (System.currentTimeMillis() >= sleepAt) {
            sleepAt = 0L
            player.pause()
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

    // ---- Progress ----

    private fun updateTitle() {
        val i = player.currentMediaItemIndex
        val ep = series.episodes.getOrNull(i) ?: return
        txtTitle.text = "ตอนที่ ${i + 1}/${series.episodes.size} · ${ep.name}"
    }

    private fun track() {
        if (!::player.isInitialized || finished || player.currentMediaItemIndex != trackedIndex) return
        trackedPosition = player.currentPosition
        if (player.duration != C.TIME_UNSET) trackedDuration = player.duration
        val now = System.currentTimeMillis()
        if (player.isPlaying && now - lastSavedAt > 5_000) saveProgress()
    }

    /** Saves a clip's spot; near the end (or past the outro) it counts as watched. */
    private fun record(ep: Episode, position: Long, duration: Long, ended: Boolean) {
        if (duration != C.TIME_UNSET && duration > 0) ep.duration = duration
        val d = ep.duration
        val outro = series.outroOf(ep) * 1000L
        val nearEnd = ended || (d > 0 && (position >= d * 95 / 100 ||
            (series.skipOutro && outro > 0 && position >= d - outro - 1_500)))
        if (nearEnd) {
            ep.watched = true
            ep.position = 0L
        } else {
            ep.position = position
            if (position >= 5_000) ep.watched = false
        }
    }

    private fun onSeriesFinished() {
        if (finished) return
        finished = true
        series.lastWatchedAt = System.currentTimeMillis()
        Store.put(this, series)
        showToast("ดูครบทุกตอนแล้ว 🎉")
        finish()
    }

    private fun saveProgress() {
        if (!::player.isInitialized || finished) return
        val index = player.currentMediaItemIndex
        val ep = series.episodes.getOrNull(index) ?: return
        record(ep, player.currentPosition, player.duration, ended = false)
        series.lastIndex = index
        series.lastWatchedAt = System.currentTimeMillis()
        lastSavedAt = series.lastWatchedAt
        Store.put(this, series)
    }

    private fun showToast(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    // ---- Window and lifecycle ----

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
    }

    override fun onStop() {
        super.onStop()
        if (!::player.isInitialized) return
        saveProgress()
        val keepPlaying = Settings.backgroundAudio(this) && player.isPlaying && !isFinishing
        if (!keepPlaying) {
            handler.removeCallbacks(ticker)
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) {
            controllerFuture?.let { MediaController.releaseFuture(it) }
            PlaybackService.shutdown()
            PlayerHolder.player = null
            player.release()
        }
    }
}
