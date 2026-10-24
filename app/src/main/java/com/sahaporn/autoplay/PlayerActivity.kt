package com.sahaporn.autoplay

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "series_id"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION = "position"
    }

    private lateinit var series: Series
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var txtTitle: TextView
    private lateinit var btnToggleIntro: Button
    private lateinit var btnToggleOutro: Button

    private val handler = Handler(Looper.getMainLooper())
    private var introHandled = false
    private var outroHandled = false
    private var finished = false
    private var toast: Toast? = null

    private val ticker = object : Runnable {
        override fun run() {
            checkSkip()
            handler.postDelayed(this, 250)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            introHandled = false
            outroHandled = false
            updateTitle()
            saveProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onSeriesFinished()
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
        btnToggleIntro = findViewById(R.id.btnToggleIntro)
        btnToggleOutro = findViewById(R.id.btnToggleOutro)

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        playerView.player = player
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> topBar.visibility = visibility }
        )

        val items = s.episodes.map {
            MediaItem.Builder()
                .setUri(Uri.parse(it.uri))
                .setMediaId(it.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).build())
                .build()
        }
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1)
        val startPos = intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L)
        player.addListener(listener)
        player.setMediaItems(items, startIndex, startPos)
        player.prepare()
        player.playWhenReady = true

        setupButtons()
        updateTitle()
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        btnToggleIntro.setOnClickListener {
            series.skipIntro = !series.skipIntro
            saveProgress()
            updateToggleLabels()
        }
        btnToggleOutro.setOnClickListener {
            series.skipOutro = !series.skipOutro
            saveProgress()
            updateToggleLabels()
        }
        findViewById<Button>(R.id.btnIntroStart).setOnClickListener {
            val sec = (player.currentPosition / 1000).toInt()
            series.introStart = sec
            if (series.introEnd <= sec) series.introEnd = 0
            series.skipIntro = true
            introHandled = true
            saveProgress()
            updateToggleLabels()
            showToast("เพลงเปิดเริ่มที่ ${TimeText.format(sec.toLong())} — ต่อไปกด “จุดจบเพลงเปิด” ตอนเพลงจบ")
        }
        findViewById<Button>(R.id.btnIntroEnd).setOnClickListener {
            val sec = (player.currentPosition / 1000).toInt()
            if (sec <= series.introStart) {
                showToast("จุดจบต้องอยู่หลังจุดเริ่ม (${TimeText.format(series.introStart.toLong())})")
                return@setOnClickListener
            }
            series.introEnd = sec
            series.skipIntro = true
            introHandled = true
            saveProgress()
            updateToggleLabels()
            showToast(
                "ตั้งแล้ว: ข้ามเพลงเปิด ${TimeText.format(series.introStart.toLong())}–${TimeText.format(sec.toLong())} ทุกตอน"
            )
        }
        findViewById<Button>(R.id.btnOutroStart).setOnClickListener {
            val dur = player.duration
            if (dur == C.TIME_UNSET) return@setOnClickListener
            val remaining = ((dur - player.currentPosition) / 1000).toInt()
            if (remaining <= 0) return@setOnClickListener
            series.outroLength = remaining
            series.skipOutro = true
            outroHandled = true
            saveProgress()
            updateToggleLabels()
            showToast("ตั้งแล้ว: ข้าม ${TimeText.format(remaining.toLong())} สุดท้ายของทุกตอน")
        }
        findViewById<Button>(R.id.btnNext).setOnClickListener {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else showToast("นี่คือตอนสุดท้ายแล้ว")
        }
        updateToggleLabels()
    }

    private fun updateToggleLabels() {
        btnToggleIntro.text = if (series.skipIntro) "ข้ามเพลงเปิด: เปิด" else "ข้ามเพลงเปิด: ปิด"
        btnToggleOutro.text = if (series.skipOutro) "ข้ามเพลงปิด: เปิด" else "ข้ามเพลงปิด: ปิด"
    }

    private fun updateTitle() {
        val i = player.currentMediaItemIndex
        val ep = series.episodes.getOrNull(i) ?: return
        txtTitle.text = "${series.name} · ตอนที่ ${i + 1}/${series.episodes.size} · ${ep.name}"
    }

    private fun checkSkip() {
        if (!::player.isInitialized || finished || player.playbackState != Player.STATE_READY) return
        val pos = player.currentPosition

        if (series.skipIntro && !introHandled && series.introEnd > series.introStart) {
            val start = series.introStart * 1000L
            val end = series.introEnd * 1000L
            if (pos >= end) {
                introHandled = true
            } else if (pos >= start) {
                introHandled = true
                player.seekTo(end)
                showToast("ข้ามเพลงเปิดแล้ว")
            }
        }

        val dur = player.duration
        val outro = series.outroLength * 1000L
        if (series.skipOutro && !outroHandled && outro > 0 && dur != C.TIME_UNSET && dur > outro) {
            if (pos >= dur - outro) {
                outroHandled = true
                if (player.hasNextMediaItem()) {
                    showToast("ข้ามเพลงปิด ▶ ตอนต่อไป")
                    player.seekToNextMediaItem()
                } else {
                    player.pause()
                    onSeriesFinished()
                }
            }
        }
    }

    private fun onSeriesFinished() {
        if (finished) return
        finished = true
        // Start over from episode 1 next time, for a rewatch.
        series.lastIndex = 0
        series.lastPosition = 0
        Store.put(this, series)
        showToast("ดูครบทุกตอนแล้ว 🎉")
        finish()
    }

    private fun saveProgress() {
        if (!::player.isInitialized || finished) return
        series.lastIndex = player.currentMediaItemIndex
        series.lastPosition = player.currentPosition
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
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        if (::player.isInitialized) {
            saveProgress()
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }
}
