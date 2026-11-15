package dev.xrealdiag.display

import android.app.Presentation
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

/** Full-screen GL window on the glasses' display. */
class StereoPresentation(context: Context, display: Display, private val modeId: Int, val renderer: StereoRenderer) :
    Presentation(context, display) {
    private var view: GLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.let { w ->
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (modeId != 0) w.attributes = w.attributes.apply { preferredDisplayModeId = modeId }
        }
        view = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(view!!)
    }

    override fun onStop() { view?.onPause(); super.onStop() }
}
