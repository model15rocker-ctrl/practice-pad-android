package com.trguitar.practicepad

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Test build: a big practice pad that remote-controls the YouTube app through
 * Android's media session (the same channel the notification player uses).
 * A-B looping is done here by watching the position and calling seekTo(A).
 */
class MainActivity : Activity() {

    private val bg = Color.parseColor("#0f1114")
    private val card = Color.parseColor("#232830")
    private val textColor = Color.parseColor("#eceff2")
    private val dim = Color.parseColor("#8d95a1")
    private val acc = Color.parseColor("#f5a524")

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null

    private var pointA = -1L
    private var pointB = -1L
    private var loopOn = true
    private var loops = 0
    private var lastSeekAt = 0L
    private var tickCount = 0

    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var speedLabel: TextView
    private lateinit var btnAccess: Button
    private lateinit var btnPlay: Button
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var btnLoop: Button

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "연습 패드 · 유튜브 리모컨 (시험판)"
            setTextColor(acc)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        status = label(14f, textColor)
        root.addView(status, fixed(LinearLayout.LayoutParams.WRAP_CONTENT, 6))

        btnAccess = makeButton("① 알림 접근 권한 허용하기 (처음 한 번)", Color.parseColor("#7a2e26"), textColor, 15f)
        btnAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                toast("설정 화면을 열 수 없어요")
            }
        }
        root.addView(btnAccess, fixed(dp(52), 8))

        val btnYt = makeButton("유튜브 열기", Color.parseColor("#b3261e"), Color.WHITE, 15f)
        btnYt.setOnClickListener { openYouTube() }
        root.addView(btnYt, fixed(dp(48), 8))

        info = label(15f, textColor).apply { typeface = Typeface.MONOSPACE }
        root.addView(info, fixed(LinearLayout.LayoutParams.WRAP_CONTENT, 8))

        val slower = makeButton("속도 −5%", card)
        slower.setOnClickListener { changeSpeed(-0.05f) }
        val faster = makeButton("속도 +5%", card)
        faster.setOnClickListener { changeSpeed(0.05f) }
        speedLabel = label(16f, acc).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(row(listOf(slower, speedLabel, faster)), fixed(dp(52), 8))

        btnLoop = makeButton("반복 ON", Color.parseColor("#3a2c0c"), acc, 15f)
        btnLoop.setOnClickListener { toggleLoop() }
        val btnClear = makeButton("구간 지우기", card, textColor, 15f)
        btnClear.setOnClickListener {
            pointA = -1; pointB = -1; loops = 0
            toast("구간을 지웠어요")
        }
        root.addView(row(listOf(btnLoop, btnClear)), fixed(dp(48), 8))

        val btnBack = makeButton("⟲\n구간 처음", card, textColor, 22f)
        btnBack.setOnClickListener { goA() }
        btnPlay = makeButton("▶\n재생", Color.parseColor("#123b2a"), Color.parseColor("#2fcf8a"), 22f)
        btnPlay.setOnClickListener { togglePlay() }
        btnA = makeButton("A\n시작점", Color.parseColor("#2d2410"), acc, 26f)
        btnA.setOnClickListener { setA() }
        btnB = makeButton("B\n끝점", Color.parseColor("#2d2410"), acc, 26f)
        btnB.setOnClickListener { setB() }

        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pad.addView(row(listOf(btnBack, btnPlay)), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        pad.addView(row(listOf(btnA, btnB)), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        setContentView(root)
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        connect()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ---------- media session ----------

    private fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun connect() {
        if (!hasAccess()) {
            btnAccess.visibility = View.VISIBLE
            controller = null
            status.text = "먼저 ① 버튼을 눌러 '연습 패드'의 알림 접근을 허용해 주세요. 유튜브를 조종하는 데 필요해요."
            return
        }
        btnAccess.visibility = View.GONE
        val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm = m
        val comp = ComponentName(this, MediaListener::class.java)
        try {
            m.removeOnActiveSessionsChangedListener(sessionsListener)
            m.addOnActiveSessionsChangedListener(sessionsListener, comp)
            pickController(m.getActiveSessions(comp))
        } catch (e: SecurityException) {
            status.text = "권한 오류: 알림 접근을 껐다가 다시 켜 보세요."
        }
    }

    private fun pickController(list: List<MediaController>?) {
        val l = list ?: emptyList()
        controller = l.firstOrNull { it.packageName == YT }
            ?: l.firstOrNull { it.packageName.contains("youtube") }
            ?: l.firstOrNull()
        updateStatus()
    }

    private fun updateStatus() {
        val c = controller
        if (!hasAccess()) return
        status.text = if (c == null) {
            "유튜브에서 영상을 재생하면 여기에 연결돼요. 유튜브와 이 앱을 화면 분할로 같이 띄우면 편해요."
        } else {
            val t = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "(제목 없음)"
            val app = if (c.packageName == YT) "유튜브" else c.packageName
            "연결됨: " + app + " · " + t
        }
    }

    private fun state(): PlaybackState? = controller?.playbackState

    private fun isPlaying(): Boolean = state()?.state == PlaybackState.STATE_PLAYING

    /** Current position in ms, extrapolated from the last report while playing. */
    private fun position(): Long {
        val st = state() ?: return 0
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            val dt = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
            val sp = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
            pos += (dt * sp).toLong()
        }
        return pos
    }

    private fun seek(ms: Long) {
        controller?.transportControls?.seekTo(ms.coerceAtLeast(0))
        lastSeekAt = SystemClock.elapsedRealtime()
    }

    private fun tick() {
        tickCount++
        if (tickCount % 25 == 0) updateStatus()
        val c = controller
        if (c == null) {
            info.text = "연결된 영상 없음"
            speedLabel.text = "-"
            btnPlay.text = "▶\n재생"
            return
        }
        val p = position()
        val playing = isPlaying()
        if (loopOn && pointA >= 0 && pointB > pointA && playing &&
            SystemClock.elapsedRealtime() - lastSeekAt > 700 && p >= pointB
        ) {
            loops++
            seek(pointA)
        }
        val st = state()
        val stateName = when (st?.state) {
            PlaybackState.STATE_PLAYING -> "재생 중"
            PlaybackState.STATE_PAUSED -> "일시정지"
            PlaybackState.STATE_BUFFERING -> "버퍼링"
            PlaybackState.STATE_STOPPED -> "정지"
            else -> "대기"
        }
        info.text = "위치 " + fmt(p) + "   " + stateName + "\nA " + fmt(pointA) + "   B " + fmt(pointB) + "   반복 " + loops + "회"
        val sp = st?.playbackSpeed ?: 0f
        speedLabel.text = if (sp > 0f) (sp * 100).roundToInt().toString() + "%" else "-"
        btnPlay.text = if (playing) "❚❚\n정지" else "▶\n재생"
        btnLoop.text = if (loopOn) "반복 ON" else "반복 OFF"
        btnA.text = "A\n" + (if (pointA >= 0) fmt(pointA) else "시작점")
        btnB.text = "B\n" + (if (pointB >= 0) fmt(pointB) else "끝점")
    }

    // ---------- actions ----------

    private fun noSession(): Boolean {
        if (controller == null) {
            toast(if (hasAccess()) "유튜브에서 영상을 먼저 재생해 주세요" else "알림 접근 권한을 먼저 허용해 주세요")
            return true
        }
        return false
    }

    private fun togglePlay() {
        if (noSession()) return
        val tc = controller!!.transportControls
        if (isPlaying()) tc.pause() else tc.play()
    }

    private fun goA() {
        if (noSession()) return
        seek(if (pointA >= 0) pointA else 0)
        controller!!.transportControls.play()
    }

    private fun setA() {
        if (noSession()) return
        val p = position()
        pointA = p
        if (pointB in 0..(p + 50)) pointB = -1
        loops = 0
        toast("A 지점 " + fmt(p))
    }

    private fun setB() {
        if (noSession()) return
        val p = position()
        if (pointA < 0) pointA = 0
        if (p <= pointA + 50) {
            toast("B는 A보다 뒤에 찍어 주세요")
            return
        }
        pointB = p
        loops = 0
        if (loopOn) {
            toast("이 구간 반복 시작")
            if (isPlaying()) seek(pointA)
        } else {
            toast("B 지점 " + fmt(p))
        }
    }

    private fun toggleLoop() {
        loopOn = !loopOn
        toast(if (loopOn) "구간 반복 켜짐" else "구간 반복 꺼짐")
    }

    /** Asks YouTube to change speed, then reports whether it actually did. */
    private fun changeSpeed(delta: Float) {
        if (noSession()) return
        if (Build.VERSION.SDK_INT < 29) {
            toast("이 폰의 안드로이드 버전은 외부 속도 변경을 지원하지 않아요")
            return
        }
        val cur = state()?.playbackSpeed ?: 0f
        val base = if (cur > 0f) cur else 1f
        val target = (((base + delta) * 100).roundToInt() / 100f).coerceIn(0.25f, 2f)
        controller!!.transportControls.setPlaybackSpeed(target)
        handler.postDelayed({
            val st = state()
            val now = st?.playbackSpeed ?: 0f
            when {
                st?.state != PlaybackState.STATE_PLAYING ->
                    toast("재생 중에 눌러야 적용 여부를 확인할 수 있어요")
                abs(now - target) < 0.01f ->
                    toast("속도 " + (now * 100).roundToInt() + "% 적용됨")
                else ->
                    toast("유튜브가 속도 변경을 받지 않았어요 (지금 " + (now * 100).roundToInt() + "%)")
            }
        }, 900)
    }

    private fun openYouTube() {
        val i = packageManager.getLaunchIntentForPackage(YT)
        if (i != null) startActivity(i) else toast("유튜브 앱을 찾을 수 없어요")
    }

    // ---------- pedal / keyboard ----------

    private val ourKeys = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_LEFT_BRACKET,
        KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_RIGHT_BRACKET,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_L
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in ourKeys) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val arrowsUpDown = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.repeatCount > 0 && !arrowsUpDown) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> goA()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> togglePlay()
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_LEFT_BRACKET -> setA()
            KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_RIGHT_BRACKET -> setB()
            KeyEvent.KEYCODE_DPAD_UP -> changeSpeed(0.05f)
            KeyEvent.KEYCODE_DPAD_DOWN -> changeSpeed(-0.05f)
            KeyEvent.KEYCODE_L -> toggleLoop()
        }
        return true
    }

    // ---------- small helpers ----------

    private fun fmt(ms: Long): String {
        if (ms < 0) return "—"
        val tenths = ms / 100
        val s = tenths / 10
        return String.format("%d:%02d.%d", s / 60, s % 60, tenths % 10)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun label(size: Float, color: Int): TextView = TextView(this).apply {
        textSize = size
        setTextColor(color)
    }

    private fun makeButton(t: String, fill: Int, fg: Int = textColor, size: Float = 16f): Button =
        Button(this).apply {
            text = t
            isAllCaps = false
            textSize = size
            setTextColor(fg)
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(fill)
            }
        }

    private fun row(views: List<View>): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v ->
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (i > 0) p.leftMargin = dp(8)
            r.addView(v, p)
        }
        return r
    }

    private fun fixed(height: Int, topDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(topDp) }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        private const val YT = "com.google.android.youtube"
    }
}
