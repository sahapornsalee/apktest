package com.sahaporn.autoplay

import android.content.Context
import android.content.Intent
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

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }
        updateTitle()
    }

    private fun currentEpisode(): Episode = series.episodes[player.currentMediaItemIndex]

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
        Dialogs.menu(
            this, "เมนู · ตอนที่ ${player.currentMediaItemIndex + 1}", listOf(
                "${if (series.skipIntro) "✅" else "⬜"} ข้ามเพลงเปิด$introNote" to {
                    series.skipIntro = !series.skipIntro
                    saveProgress()
                    showToast(if (series.skipIntro) "เปิดการข้ามเพลงเปิด" else "ปิดการข้ามเพลงเปิด")
                },
                "${if (series.skipOutro) "✅" else "⬜"} ข้ามเพลงปิด$outroNote" to {
                    series.skipOutro = !series.skipOutro
                    saveProgress()
                    showToast(if (series.skipOutro) "เปิดการข้ามเพลงปิด" else "ปิดการข้ามเพลงปิด")
                },
                "⏱ ตอนนี้ ($now) = จุดเริ่มเพลงเปิด" to { chooseScope { only -> setIntroStart(sec, only) } },
                "⏱ ตอนนี้ ($now) = จุดจบเพลงเปิด" to { chooseScope { only -> setIntroEnd(sec, only) } },
                "⏱ ตอนนี้ ($now) = จุดเริ่มเพลงปิด" to { chooseScope { only -> setOutroFrom(pos, only) } },
                "⚙ พิมพ์เวลาข้ามเพลงเอง" to { chooseScope { only -> editSkipTimes(only) } },
                "☰ เลือกตอน" to { chooseEpisode() },
                "⏭ ตอนต่อไป" to {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem() else showToast("นี่คือตอนสุดท้ายแล้ว")
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
            )
            Dialogs.skipSettings(this, "ข้ามเพลง (ทุกตอน)", initial, showFlags = true, onReset = null) { v ->
                series.introStart = v.introStart
                series.introEnd = v.introEnd
                series.outroLength = v.outro
                series.skipIntro = v.skipIntro
                series.skipOutro = v.skipOutro
                saveProgress()
            }
        }
    }

    private fun chooseEpisode() {
        val current = player.currentMediaItemIndex
        Dialogs.menu(
            this, "เลือกตอน", series.episodes.mapIndexed { i, e ->
                "${if (i == current) "▶ " else ""}${i + 1}. ${e.name}" to { player.seekTo(i, 0L) }
            }
        )
    }

    private fun updateTitle() {
        val i = player.currentMediaItemIndex
        val ep = series.episodes.getOrNull(i) ?: return
        txtTitle.text = "ตอนที่ ${i + 1}/${series.episodes.size} · ${ep.name}"
    }

    private fun checkSkip() {
        if (!::player.isInitialized || finished || player.playbackState != Player.STATE_READY) return
        val ep = series.episodes.getOrNull(player.currentMediaItemIndex) ?: return
        val pos = player.currentPosition

        val introStart = series.introStartOf(ep) * 1000L
        val introEnd = series.introEndOf(ep) * 1000L
        if (series.skipIntro && !introHandled && introEnd > introStart) {
            if (pos >= introEnd) {
                introHandled = true
            } else if (pos >= introStart) {
                introHandled = true
                player.seekTo(introEnd)
                showToast("ข้ามเพลงเปิดแล้ว")
            }
        }

        val dur = player.duration
        val outro = series.outroOf(ep) * 1000L
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
