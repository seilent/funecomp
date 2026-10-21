package net.seilent.funecomp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Window
import net.seilent.funecomp.R

class RefreshPin(private val window: Window?, private val read: (Display) -> Pair<List<ModeInfo>, ModeInfo> = { modes(it) }) {
    data class ModeInfo(val id: Int, val w: Int, val h: Int, val hz: Float)

    inner class Handle {
        private var live = true

        fun release() {
            if (!live) return
            live = false
            if (--count == 0) stop()
        }
    }

    private var count = 0
    private var written = 0
    private val handler = Handler(Looper.getMainLooper())
    private val displays get() = window?.context?.getSystemService(DisplayManager::class.java)

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}

        override fun onDisplayRemoved(id: Int) {}

        override fun onDisplayChanged(id: Int) {
            if (id == window?.decorView?.display?.displayId) repick()
        }
    }

    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}

        override fun onViewDetachedFromWindow(v: View) {
            displays?.unregisterDisplayListener(listener)
        }
    }

    fun hold(): Handle {
        if (count++ == 0) {
            displays?.registerDisplayListener(listener, handler)
            window?.decorView?.addOnAttachStateChangeListener(detach)
            repick()
        }
        return Handle()
    }

    fun repick() {
        if (count == 0) return
        val display = window?.decorView?.display ?: return
        val (all, current) = read(display)
        write(top(all, current))
    }

    private fun stop() {
        displays?.unregisterDisplayListener(listener)
        window?.decorView?.removeOnAttachStateChangeListener(detach)
        write(0)
    }

    private fun write(id: Int) {
        val w = window ?: return
        if (id == written) return
        written = id
        w.attributes = w.attributes.also { it.preferredDisplayModeId = id }
    }

    companion object {
        fun top(modes: List<ModeInfo>, current: ModeInfo): Int {
            val same = modes.filter { it.w == current.w && it.h == current.h }
            if (same.map { it.hz }.distinct().size < 2) return 0
            val best = same.maxOf { it.hz }
            return if (current.hz == best && same.any { it.id == current.id }) current.id else same.first { it.hz == best }.id
        }

        fun of(view: View): RefreshPin {
            val root = view.rootView
            (root.getTag(R.id.refresh_pin) as? RefreshPin)?.let { return it }
            return RefreshPin(activity(view.context)?.window).also { root.setTag(R.id.refresh_pin, it) }
        }

        private tailrec fun activity(c: Context?): Activity? = when (c) {
            is Activity -> c
            is ContextWrapper -> activity(c.baseContext)
            else -> null
        }

        private fun info(m: Display.Mode) = ModeInfo(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate)

        private fun modes(d: Display): Pair<List<ModeInfo>, ModeInfo> = d.supportedModes.map(::info) to info(d.mode)
    }
}
