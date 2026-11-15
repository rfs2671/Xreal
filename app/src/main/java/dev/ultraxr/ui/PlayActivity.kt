package dev.ultraxr.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.game.Game
import dev.ultraxr.render.GameScene
import dev.ultraxr.render.GlassesStage
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.Ui.add

/**
 * Runs the built-in game. The glasses show the game; the phone becomes a dark, simple
 * remote (recenter / exit) so it can be used without looking. With no glasses attached
 * the game is previewed on the phone screen.
 */
class PlayActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val game = Game()
    private val scene = GameScene(game)
    private lateinit var stage: GlassesStage
    private lateinit var status: TextView
    private lateinit var score: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Tracking.acquire(this)
        game.best = Tracking.settings.bestScore

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val preview = FrameLayout(this)
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val panel = Ui.column(this, 24).apply { gravity = Gravity.BOTTOM }
        score = Ui.text(this, "", 44f, bold = true).apply { gravity = Gravity.CENTER }
        status = Ui.text(this, "", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        panel.add(score); panel.add(status, topDp = 10)
        panel.add(Ui.button(this, "Recenter", primary = true) { Tracking.recenter(); game.resetOrigin() }.apply { minHeight = Ui.dp(this@PlayActivity, 96); textSize = 20f }, topDp = 28)
        panel.add(Ui.button(this, "Exit") { finish() }, topDp = 12)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = Ui.dp(this@PlayActivity, 24) })
        setContentView(root)
        stage = GlassesStage(this, preview, scene)
    }

    override fun onResume() { super.onResume(); stage.start(); scene.paused = false; ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); scene.paused = true; stage.stop(); saveBest(); super.onPause() }
    override fun onDestroy() { Tracking.release(); super.onDestroy() }

    /** For instrumented tests. */
    fun stageForTest(): GlassesStage = stage

    private fun saveBest() { if (game.best > Tracking.settings.bestScore) Tracking.settings.bestScore = game.best }

    private val tick = object : Runnable {
        override fun run() {
            val s = Tracking.snapshot()
            score.text = when (game.phase) {
                Game.Phase.PLAYING -> "${game.score}"
                Game.Phase.FINISHED -> "${game.score}"
                Game.Phase.READY -> "Lumen"
            }
            val where = if (stage.onGlasses) "" else "Preview on the phone: plug in the glasses to play in 3D.\n"
            val track = when (s.head) {
                HeadTracker.Mode.SIX_DOF -> if (s.handsVisible > 0) "Full tracking, hands in view" else if (s.handsActive) "Full tracking. Raise a hand to touch orbs, or look at one." else "Full tracking. Look at an orb to burst it."
                HeadTracker.Mode.ORIENTATION_ONLY -> "Rotation only: orbs stay within reach."
                HeadTracker.Mode.NONE -> "Waiting for the glasses' sensors."
            }
            val phase = when (game.phase) {
                Game.Phase.READY -> "Touch the glowing orb in front of you to start.\n"
                Game.Phase.FINISHED -> "Round over. Best ${game.best}. Touch the orb to play again.\n"
                Game.Phase.PLAYING -> ""
            }
            status.text = where + phase + track
            saveBest()
            ui.postDelayed(this, 500)
        }
    }
}
