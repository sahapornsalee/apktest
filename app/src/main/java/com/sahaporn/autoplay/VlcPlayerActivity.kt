package com.sahaporn.autoplay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Backup player built on VLC, for files the main player can't open or shows broken.
 * Plays the series back to back with the same skip times and saved progress.
 */
class VlcPlayerActivity : ThemedActivity() {
    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val CONTROLS_TIMEOUT_MS = 4_000L
        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val RESIZE_NAMES = listOf("พอดีจอ", "เต็มจอ (ตัดขอบ)", "ยืดเต็มจอ")

        fun start(ctx: Context, seriesId: String, index: Int, position: Long) {
            ctx.startActivity(
                Intent(ctx, VlcPlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_ID, seriesId)
                    .putExtra(PlayerActivity.EXTRA_INDEX, index)
                    .putExtra(PlayerActivity.EXTRA_POSITION, position)
            )
        }
    }

    private lateinit var series: Series
    private lateinit var libVlc: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var controls: View
    private lateinit var txtTitle: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var txtPosition: TextView
    private lateinit var txtRemaining: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnRotate: TextView
    private lateinit var buffering: View
    private lateinit var txtSeek: TextView
    private lateinit var btnSkip: Button
    private lateinit var btnUnlock: Button

    private val handler = Handler(Looper.getMainLooper())
    private var fd: ParcelFileDescriptor? = null
    private var index = 0

    /** Where to jump once the clip starts playing; VLC can't seek before that. */
    private var startAt = 0L
    private var ended = false
    private var introHandled = false
    private var outroHandled = false
    private var finished = false
    private var locked = false
    private var dragging = false
    private var failures = 0
    private var lastSavedAt = 0L
    private var skipAction: (() -> Unit)? = null
    private var toast: Toast? = null

    private val ticker = object : Runnable {
        override fun run() {
            checkSkip()
            updateProgressUi()
            if (::mp.isInitialized && mp.isPlaying && System.currentTimeMillis() - lastSavedAt > 5_000) saveProgress()
            handler.postDelayed(this, 250)
        }
    }
    private val hideControls = Runnable { setControlsVisible(false) }
    private val hideSeek = Runnable { txtSeek.visibility = View.GONE }
    private val hideUnlock = Runnable { btnUnlock.visibility = View.GONE }

    private val lockedBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showUnlockHint()
            showToast("หน้าจอล็อกอยู่ · แตะค้างที่ปุ่ม 🔓 เพื่อปลดล็อก")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vlc)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val s = Store.get(this, intent.getStringExtra(PlayerActivity.EXTRA_ID) ?: "")
        if (s == null || s.episodes.isEmpty()) {
            finish()
            return
        }
        series = s

        videoLayout = findViewById(R.id.videoLayout)
        controls = findViewById(R.id.controls)
        txtTitle = findViewById(R.id.txtTitle)
        btnPlay = findViewById(R.id.btnPlay)
        seekBar = findViewById(R.id.seekBar)
        txtPosition = findViewById(R.id.txtPosition)
        txtRemaining = findViewById(R.id.txtRemaining)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnRotate = findViewById(R.id.btnRotate)
        buffering = findViewById(R.id.buffering)
        txtSeek = findViewById(R.id.txtSeek)
        btnSkip = findViewById(R.id.btnSkip)
        btnUnlock = findViewById(R.id.btnUnlock)

        libVlc = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        mp = MediaPlayer(libVlc)
        mp.attachViews(videoLayout, null, true, false)
        mp.setEventListener { event -> handler.post { onVlcEvent(event) } }

        setupControls()
        setupGestures()
        onBackPressedDispatcher.addCallback(this, lockedBack)
        applyScale()
        applyOrientation()

        val start = intent.getIntExtra(PlayerActivity.EXTRA_INDEX, 0).coerceIn(0, s.episodes.size - 1)
        open(start, intent.getLongExtra(PlayerActivity.EXTRA_POSITION, 0L))
        setControlsVisible(true)
        showToast("ตัวเล่นสำรอง VLC" + if (series.vlcHardware) "" else " · ถอดรหัสด้วยซอฟต์แวร์")
    }

    private fun currentEpisode() = series.episodes[index]

    private fun open(i: Int, position: Long) {
        index = i
        ended = false
        introHandled = false
        outroHandled = false
        val ep = series.episodes[i]
        fd?.close()
        fd = try {
            contentResolver.openFileDescriptor(Uri.parse(ep.uri), "r")
        } catch (e: Exception) {
            null
        }
        val file = fd
        if (file == null) {
            showToast("เปิดไฟล์ไม่ได้ (อาจถูกย้ายหรือลบ): ${ep.shortName}")
            skipBrokenClip()
            return
        }
        val media = Media(libVlc, file.fileDescriptor)
        media.setHWDecoderEnabled(series.vlcHardware, false)
        mp.setMedia(media)
        media.release()
        startAt = position.coerceAtLeast(0L)
        mp.setRate(series.speed)
        mp.play()
        series.lastIndex = i
        series.lastWatchedAt = System.currentTimeMillis()
        Store.put(this, series)
        updateTitle()
    }

    private fun skipBrokenClip() {
        failures++
        if (failures < 3 && index + 1 < series.episodes.size) {
            open(index + 1, series.episodes[index + 1].resumePosition)
        } else {
            finish()
        }
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        if (finished || isDestroyed) return
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                failures = 0
                buffering.visibility = View.GONE
                if (startAt > 0) {
                    mp.setTime(startAt)
                    startAt = 0L
                }
                btnPlay.setImageResource(R.drawable.ic_pause)
                scheduleHide()
            }
            MediaPlayer.Event.Paused -> {
                btnPlay.setImageResource(R.drawable.ic_play)
                setControlsVisible(true)
            }
            MediaPlayer.Event.Buffering ->
                buffering.visibility = if (event.buffering < 100f) View.VISIBLE else View.GONE
            MediaPlayer.Event.Vout -> applyOrientation()
            MediaPlayer.Event.EndReached -> {
                ended = true
                btnPlay.setImageResource(R.drawable.ic_play)
                onClipEnded(currentEpisode().duration, currentEpisode().duration, natural = true)
            }
            MediaPlayer.Event.EncounteredError -> {
                showToast("VLC เปิดไฟล์นี้ไม่ได้: ${currentEpisode().shortName}")
                skipBrokenClip()
            }
        }
    }

    /** A clip is over, either at its real end or where its outro starts. */
    private fun onClipEnded(position: Long, duration: Long, natural: Boolean) {
        series.recordProgress(currentEpisode(), position, duration, ended = true)
        series.lastWatchedAt = System.currentTimeMillis()
        Store.put(this, series)
        when {
            !series.autoPlay -> {
                if (!natural) mp.pause()
                setControlsVisible(true)
                showToast(if (index + 1 < series.episodes.size) "จบตอนนี้แล้ว · กด ⏭ เพื่อดูตอนต่อไป" else "จบตอนสุดท้ายแล้ว 🎉")
            }
            index + 1 < series.episodes.size -> {
                if (!natural) showToast("ข้ามเพลงปิด ▶ ตอนต่อไป")
                open(index + 1, series.episodes[index + 1].resumePosition)
            }
            else -> {
                finished = true
                showToast("ดูครบทุกตอนแล้ว 🎉")
                finish()
            }
        }
    }

    private fun goTo(i: Int) {
        if (i !in series.episodes.indices) return
        saveProgress()
        open(i, series.episodes[i].resumePosition)
    }

    private fun nextByUser() {
        if (index + 1 < series.episodes.size) goTo(index + 1) else showToast("นี่คือตอนสุดท้ายแล้ว")
    }

    // ---- Skip times ----

    private fun checkSkip() {
        if (!::mp.isInitialized || finished || ended || startAt > 0 || !mp.isPlaying) return
        val ep = currentEpisode()
        val pos = mp.time
        val dur = mp.length
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
                    showSkipButton("ข้ามเพลงเปิด ⏭") { mp.setTime(introEnd) }
                hasOutro && pos >= outroStart ->
                    showSkipButton(if (index + 1 < series.episodes.size) "ตอนต่อไป ⏭" else "จบเรื่อง ⏹") {
                        onClipEnded(mp.time, mp.length, natural = false)
                    }
                else -> hideSkipButton()
            }
            return
        }
        if (hasIntro && !introHandled) {
            if (pos >= introEnd) {
                introHandled = true
            } else if (pos >= introStart) {
                introHandled = true
                mp.setTime(introEnd)
                showToast("ข้ามเพลงเปิดแล้ว")
            }
        }
        if (hasOutro && !outroHandled && pos >= outroStart) {
            outroHandled = true
            onClipEnded(pos, dur, natural = false)
        }
    }

    private fun showSkipButton(label: String, action: () -> Unit) {
        if (locked) return hideSkipButton()
        skipAction = action
        if (btnSkip.text.toString() != label) btnSkip.text = label
        btnSkip.visibility = View.VISIBLE
    }

    private fun hideSkipButton() {
        skipAction = null
        if (::btnSkip.isInitialized) btnSkip.visibility = View.GONE
    }

    private fun setSkipPoint(kind: Int) {
        val sec = (mp.time / 1000).toInt()
        when (kind) {
            0 -> {
                series.introStart = sec
                if (series.introEnd <= sec) series.introEnd = 0
                showToast("เพลงเปิดเริ่มที่ ${TimeText.format(sec)} (ทุกตอน)")
            }
            1 -> {
                if (sec <= series.introStart) {
                    showToast("จุดจบต้องอยู่หลังจุดเริ่ม (${TimeText.format(series.introStart)})")
                    return
                }
                series.introEnd = sec
                showToast("ข้ามเพลงเปิด ${TimeText.format(series.introStart)}–${TimeText.format(sec)} (ทุกตอน)")
            }
            else -> {
                val remaining = ((mp.length - mp.time) / 1000).toInt()
                if (remaining <= 0) return
                series.outroLength = remaining
                outroHandled = true
                showToast("ข้าม ${TimeText.format(remaining)} สุดท้าย (ทุกตอน)")
            }
        }
        if (kind < 2) {
            series.skipIntro = true
            introHandled = true
        } else {
            series.skipOutro = true
        }
        saveProgress()
    }

    // ---- Controls ----

    private fun setupControls() {
        fun tap(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener {
            action()
            scheduleHide()
        }
        tap(R.id.btnBack) { finish() }
        tap(R.id.btnMore) { showMenu() }
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
        tap(R.id.btnPlay) {
            when {
                ended -> if (index + 1 < series.episodes.size) goTo(index + 1) else open(index, 0L)
                mp.isPlaying -> mp.pause()
                else -> mp.play()
            }
        }
        tap(R.id.btnRew) { seekBy(-SEEK_STEP_MS) }
        tap(R.id.btnFwd) { seekBy(SEEK_STEP_MS) }
        tap(R.id.btnSpeed) { chooseSpeed() }
        tap(R.id.btnLock) { lock() }
        tap(R.id.btnEpisodes) { chooseEpisode() }
        tap(R.id.btnTracks) { chooseTracks() }
        tap(R.id.btnNextEp) { nextByUser() }
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
                mp.setTime(bar.progress * 1000L)
                scheduleHide()
            }
        })
        updateSpeedLabel()
        updateRotateLabel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    locked -> showUnlockHint()
                    controls.visibility == View.VISIBLE -> setControlsVisible(false)
                    else -> setControlsVisible(true)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!locked) seekBy(if (e.x > videoLayout.width / 2f) SEEK_STEP_MS else -SEEK_STEP_MS)
                return true
            }
        })
        videoLayout.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        if (!::controls.isInitialized) return
        handler.removeCallbacks(hideControls)
        if (visible && locked) return
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            updateProgressUi()
            scheduleHide()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (::mp.isInitialized && mp.isPlaying && !dragging) handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
    }

    private fun updateProgressUi() {
        if (!::mp.isInitialized || dragging || controls.visibility != View.VISIBLE) return
        val pos = mp.time.coerceAtLeast(0L)
        val dur = mp.length
        txtPosition.text = TimeText.ms(pos)
        if (dur > 0) {
            seekBar.max = (dur / 1000).toInt()
            seekBar.progress = (pos / 1000).toInt()
            txtRemaining.text = "-${TimeText.ms(dur - pos)}"
        } else {
            txtRemaining.text = ""
        }
    }

    private fun seekBy(delta: Long) {
        val dur = mp.length
        var target = mp.time + delta
        if (dur > 0) target = target.coerceAtMost(dur - 500)
        target = target.coerceAtLeast(0L)
        mp.setTime(target)
        txtSeek.text = "${if (delta > 0) "⏩ +10 วินาที" else "⏪ −10 วินาที"}\n${TimeText.ms(target)}"
        txtSeek.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, 700)
    }

    private fun lock() {
        setControlsVisible(false)
        locked = true
        lockedBack.isEnabled = true
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
        btnUnlock.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3_000)
    }

    private fun updateTitle() {
        txtTitle.text = "ตอนที่ ${index + 1}/${series.episodes.size} · ${currentEpisode().shortName}"
    }

    // ---- Picture, speed, tracks ----

    private fun applyScale() {
        mp.setVideoScale(
            when (series.resizeMode) {
                1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                2 -> MediaPlayer.ScaleType.SURFACE_FILL
                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
        )
    }

    private fun setResize(mode: Int) {
        series.resizeMode = mode
        applyScale()
        saveProgress()
        showToast("ขนาดภาพ: ${RESIZE_NAMES[mode]}")
    }

    private fun applyOrientation() {
        val want = when (series.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> {
                val track = if (::mp.isInitialized) mp.currentVideoTrack else null
                if (track != null && track.height > track.width) {
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
            1 -> "▭ แนวนอน"
            2 -> "▯ แนวตั้ง"
            3 -> "⟳ ตามเครื่อง"
            else -> "⟳ หมุน"
        }
    }

    private fun speedText(x: Float) = if (x == 1f) "1x" else "${x}x".replace(".0x", "x")

    private fun updateSpeedLabel() {
        btnSpeed.text = "⏲\nความเร็ว ${speedText(series.speed)}"
    }

    private fun chooseSpeed() {
        Dialogs.menu(this, "ความเร็วในการเล่น", SPEEDS.map { x ->
            val label = speedText(x) + if (x == 1f) " (ปกติ)" else ""
            (if (x == series.speed) "✓ $label" else label) to {
                series.speed = x
                mp.setRate(x)
                updateSpeedLabel()
                saveProgress()
            }
        })
    }

    private fun chooseTracks() {
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
        if (items.isEmpty()) {
            showToast("ไฟล์นี้ไม่มีเสียงหรือซับไตเติลให้เลือก")
            return
        }
        Dialogs.menu(this, "เสียงและซับไตเติล", items)
    }

    private fun chooseEpisode() {
        Dialogs.menu(this, "เลือกตอน", series.episodes.mapIndexed { i, e ->
            "${if (i == index) "▶ " else ""}${i + 1}. ${e.shortName} · ${TimeText.progress(e)}" to { goTo(i) }
        })
    }

    private fun check(on: Boolean) = if (on) "✅" else "⬜"

    private fun showMenu() {
        val now = TimeText.ms(mp.time)
        Dialogs.menu(
            this, "เมนู · ตัวเล่นสำรอง VLC", listOf(
                "${check(!series.vlcHardware)} ถอดรหัสด้วยซอฟต์แวร์ (แก้ภาพแตก กินแบตขึ้น)" to {
                    series.vlcHardware = !series.vlcHardware
                    val pos = mp.time
                    saveProgress()
                    open(index, pos)
                    showToast(if (series.vlcHardware) "ใช้ฮาร์ดแวร์ถอดรหัส" else "ถอดรหัสด้วยซอฟต์แวร์")
                },
                "${check(series.autoPlay)} เล่นตอนต่อไปอัตโนมัติ" to {
                    series.autoPlay = !series.autoPlay
                    saveProgress()
                },
                "${check(series.skipIntro)} ข้ามเพลงเปิด" to {
                    series.skipIntro = !series.skipIntro
                    saveProgress()
                },
                "${check(series.skipOutro)} ข้ามเพลงปิด" to {
                    series.skipOutro = !series.skipOutro
                    saveProgress()
                },
                "⏱ ตอนนี้ ($now) = จุดเริ่มเพลงเปิด" to { setSkipPoint(0) },
                "⏱ ตอนนี้ ($now) = จุดจบเพลงเปิด" to { setSkipPoint(1) },
                "⏱ ตอนนี้ ($now) = จุดเริ่มเพลงปิด" to { setSkipPoint(2) },
                "↩ กลับไปใช้ตัวเล่นหลัก (ฟีเจอร์ครบ)" to { switchToMain() },
            )
        )
    }

    private fun switchToMain() {
        val pos = mp.time
        saveProgress()
        series.engine = Engines.MAIN
        Store.put(this, series)
        finished = true
        PlayerActivity.start(this, series.id, index, pos)
        finish()
    }

    // ---- Progress and lifecycle ----

    private fun saveProgress() {
        if (!::mp.isInitialized || finished || startAt > 0) return
        series.recordProgress(currentEpisode(), mp.time.coerceAtLeast(0L), mp.length, ended = ended)
        series.lastIndex = index
        series.lastWatchedAt = System.currentTimeMillis()
        lastSavedAt = series.lastWatchedAt
        Store.put(this, series)
    }

    private fun showToast(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
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
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        if (::mp.isInitialized) {
            saveProgress()
            mp.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::mp.isInitialized) {
            mp.setEventListener(null)
            mp.stop()
            mp.detachViews()
            mp.release()
            libVlc.release()
        }
        fd?.close()
    }
}
