package dev.archdock.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import dev.archdock.graph

/** Presentation-window variant of the VNC renderer (Settings → launch mode). */
class VncPresentation(outer: Context, display: Display) : Presentation(outer, display) {
    private lateinit var surface: VncSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val g = context.graph
        surface = VncSurfaceView(context, g.session.vncPort).apply { onFrame = { g.perf.frame() } }
        setContentView(surface)
        g.perf.fpsSource = true
        surface.startClient()
    }

    override fun onStop() {
        surface.stop()
        context.graph.perf.fpsSource = false
        super.onStop()
    }
}
