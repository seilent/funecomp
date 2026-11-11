package net.seilent.funecomp.ui

import net.seilent.funecomp.proto.gen.Tok
import kotlin.math.max
import kotlin.math.min

internal class VolumeLine(val a: Float, val dx: Float)

internal class VolumeLook(
    val group: Float,
    val number: Float,
    val exit: Float,
    val scrim: Float,
    val k: Float,
    val size: Float,
    val artist: VolumeLine,
    val title: VolumeLine,
    val oldArtist: VolumeLine?,
    val oldTitle: VolumeLine?,
)

private fun ms(token: Float) = token.toLong()

private fun progress(e: Long, dur: Float) = if (dur <= 0f) 1f else (e / dur).coerceIn(0f, 1f)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

internal class VolumeLegs {
    var shown = false
        private set

    var lastUp = 0L
        private set

    private var inAt = 0L
    private var fadeAt = -1L
    private var chgAt = -1L
    private var closeAt = -1L
    private var backAt = -1L
    private var heldAt = -1L
    private var numShift = 0L
    private var crossAt = -1L
    private var backFrom = 1f
    private var scrimFrom = 1f
    private var closeFrom = 1f
    private var closeScrim = 1f
    private var numFrom = 1f
    private var closeK = 1f
    private var closeSize = 1f
    private var atRest = false
    private var held = false
    private var outArtist = VolumeLine(1f, 0f)
    private var outTitle = VolumeLine(1f, 0f)

    private fun endAt() = inAt + ms(Tok.motion_volumeOpenEnd)

    private fun numberAt() = max(endAt(), chgAt) + numShift + ms(Tok.motion_volumeNumberHold)

    private fun restFrom(now: Long) {
        fadeAt = if (held) -1L else max(endAt(), now) + ms(Tok.motion_volumeRestHold)
    }

    private fun holdEnd(now: Long) {
        if (heldAt >= 0) numShift += now - heldAt
        heldAt = -1L
    }

    private fun fading(now: Long) = fadeAt >= 0 && now >= fadeAt

    fun visible(now: Long) = shown && !(closeAt >= 0 && now >= closeAt + CLOSE_MS) && !(fadeAt >= 0 && now >= fadeAt + ms(Tok.motion_volumeRestSlow) + ms(Tok.motion_volumeScrimOut))

    fun closing(now: Long) = closeAt >= 0 || (fadeAt >= 0 && now >= fadeAt + ms(Tok.motion_volumeRestSlow))

    fun crossing(now: Long) = crossAt >= 0 && now - crossAt < CROSS_MS

    fun left(now: Long): Long = when {
        !shown -> 0L
        closeAt >= 0 -> max(0L, closeAt + CLOSE_MS - now)
        fadeAt < 0 -> Long.MAX_VALUE
        else -> max(0L, fadeAt + ms(Tok.motion_volumeRestSlow) + ms(Tok.motion_volumeScrimOut) - now)
    }

    fun open(now: Long) {
        if (!visible(now)) {
            inAt = now
            chgAt = -1L
            closeAt = -1L
            backAt = -1L
            crossAt = -1L
            heldAt = -1L
            numShift = 0L
            numFrom = 1f
            atRest = false
            held = false
            shown = true
        } else if (closing(now) || fading(now)) {
            revive(now)
        }
        restFrom(now)
    }

    fun change(now: Long): Boolean {
        if (!visible(now)) return false
        if (closing(now) || fading(now)) revive(now)
        numFrom = numberK(now)
        chgAt = now
        numShift = 0L
        if (held) heldAt = now
        restFrom(now)
        return true
    }

    fun track(now: Long): Boolean {
        if (!visible(now)) {
            crossAt = -1L
            return false
        }
        if (crossAt >= 0 && now - crossAt < ms(Tok.motion_volumeTextInAt)) return false
        if (crossing(now)) {
            val c = now - crossAt
            outArtist = inLine(c, false)
            outTitle = inLine(c, true)
        } else {
            outArtist = VolumeLine(1f, 0f)
            outTitle = VolumeLine(1f, 0f)
        }
        crossAt = now
        return true
    }

    fun hide(now: Long) {
        if (closeAt < 0 && shown) {
            val l = look(now)
            closeFrom = rest(now)
            closeScrim = scrimK(now)
            closeK = l.k
            closeSize = l.size
            closeAt = now
        }
        holdEnd(now)
        held = false
    }

    fun down(now: Long) {
        if (fading(now)) revive(now)
        held = true
        heldAt = now
        fadeAt = -1L
        atRest = true
    }

    fun up(now: Long) {
        holdEnd(now)
        held = false
        lastUp = now
        restFrom(now)
    }

    private fun revive(now: Long) {
        backFrom = rest(now)
        scrimFrom = scrimK(now)
        backAt = now
        closeAt = -1L
        atRest = true
    }

    fun rest(now: Long): Float {
        if (!shown) return 0f
        if (closeAt >= 0) return closeFrom * (1f - progress(now - closeAt, Tok.motion_volumeTouchClose))
        val back = if (backAt >= 0) min(1f, backFrom + progress(now - backAt, Tok.motion_volumeNumberBack)) else 1f
        if (fadeAt < 0 || now < fadeAt) return back
        val slow = ms(Tok.motion_volumeRestSlow)
        if (now < fadeAt + slow) return lerp(back, Tok.motion_volumeRestLevel, progress(now - fadeAt, Tok.motion_volumeRestSlow))
        return lerp(Tok.motion_volumeRestLevel, 0f, progress(now - fadeAt - slow, Tok.motion_volumeRestFast))
    }

    fun scrimK(now: Long): Float {
        if (!shown) return 0f
        if (closeAt >= 0) return closeScrim * (1f - EaseOut.transform(progress(now - closeAt + ms(Tok.motion_volumeTouchScrimLead), Tok.motion_volumeTouchScrim)))
        val came = if (backAt >= 0) min(1f, scrimFrom + progress(now - backAt, Tok.motion_volumeNumberBack)) else progress(now - inAt + ms(Tok.motion_volumeScrimLead), Tok.motion_volumeOpen)
        if (fadeAt < 0) return came
        val out = fadeAt + ms(Tok.motion_volumeRestSlow)
        return if (now < out) came else came * (1f - progress(now - out, Tok.motion_volumeScrimOut))
    }

    fun numberK(now: Long): Float {
        if (!shown) return 0f
        val t = if (held && heldAt >= 0) min(now, heldAt) else now
        val h = numberAt()
        var own = if (t < h) 1f else 1f - EaseIn.transform(progress(t - h, Tok.motion_volumeNumberFade))
        if (chgAt >= 0 && numFrom < 1f) own = min(own, numFrom + progress(now - chgAt, Tok.motion_volumeNumberBack))
        return own
    }

    private fun inLine(c: Long, title: Boolean): VolumeLine {
        val at = ms(Tok.motion_volumeTextInAt)
        val come = if (c < at) 0f else progress(c - at, Tok.motion_volumeTextIn)
        val a = if (c < at) 0f else progress(c - at, Tok.motion_volumeTextInFade)
        val left = Tok.motion_volumeTextInShift * (1f - come)
        return VolumeLine(a, if (title) left * Tok.motion_volumeTextTitleIn else left)
    }

    fun look(now: Long): VolumeLook {
        val none = VolumeLine(0f, 0f)
        if (!shown) return VolumeLook(0f, 0f, 0f, 0f, 1f, 1f, none, none, null, null)
        val r = rest(now)
        val e = now - inAt
        val lead = ms(Tok.motion_volumeGroupLead)
        var k = if (atRest) 1f else PowOut17.transform(progress(e + lead, Tok.motion_volumeGroup))
        var size = Tok.motion_volumeGroupScale + (1f - Tok.motion_volumeGroupScale) * k
        val slow = ms(Tok.motion_volumeRestSlow)
        if (closeAt >= 0) {
            val p = progress(now - closeAt, Tok.motion_volumeTouchClose)
            k = lerp(closeK, Tok.motion_volumeCloseK, p)
            size = lerp(closeSize, Tok.motion_volumeCloseSize, p)
        } else if (fadeAt >= 0 && now >= fadeAt && now < fadeAt + slow) {
            k = lerp(1f, Tok.motion_volumeCloseSlowK, progress(now - fadeAt, Tok.motion_volumeRestSlow))
            size = 1f
        } else if (fadeAt >= 0 && now >= fadeAt + slow) {
            val p = progress(now - fadeAt - slow, Tok.motion_volumeRestFast)
            k = lerp(Tok.motion_volumeCloseSlowK, Tok.motion_volumeCloseK, p)
            size = lerp(1f, Tok.motion_volumeCloseSize, p)
        }
        val group = CubicOut.transform(progress(e + lead, Tok.motion_volumeGroupFade)) * r
        val number = PowOut17.transform(progress(e - ms(Tok.motion_volumeNumberAt), Tok.motion_volumeNumberIn)) * numberK(now) * r
        val exit = PowOut17.transform(progress(e - ms(Tok.motion_volumeExitAt), Tok.motion_volumeExitIn)) * r
        val text = EaseOut.transform(progress(e - ms(Tok.motion_volumeTextFadeAt), Tok.motion_volumeTextFadeIn)) * r
        val slid = EaseOut.transform(progress(e - ms(Tok.motion_volumeTextAt), Tok.motion_volumeTextSlide))
        val dx = Tok.motion_volumeTextShift * (1f - slid)
        val scrim = scrimK(now)
        if (!crossing(now)) return VolumeLook(group, number, exit, scrim, k, size, VolumeLine(text, dx), VolumeLine(text, dx), null, null)
        val c = now - crossAt
        val gone = progress(c, Tok.motion_volumeTextOut)
        val shift = Tok.motion_volumeTextOutShift * gone
        val oldArtist = VolumeLine(outArtist.a * (1f - progress(c, Tok.motion_volumeTextOutFade)) * text, dx + outArtist.dx - shift)
        val oldTitle = VolumeLine(outTitle.a * (1f - EaseOut.transform(gone)) * text, dx + outTitle.dx - shift * Tok.motion_volumeTextTitleOut)
        val a = inLine(c, false)
        val t = inLine(c, true)
        return VolumeLook(group, number, exit, scrim, k, size, VolumeLine(a.a * text, dx + a.dx), VolumeLine(t.a * text, dx + t.dx), oldArtist, oldTitle)
    }

    companion object {
        val CLOSE_MS = max(ms(Tok.motion_volumeTouchClose), ms(Tok.motion_volumeTouchScrim) - ms(Tok.motion_volumeTouchScrimLead))
        val CROSS_MS = max(max(ms(Tok.motion_volumeTextOutFade), ms(Tok.motion_volumeTextOut)), ms(Tok.motion_volumeTextInAt) + max(ms(Tok.motion_volumeTextIn), ms(Tok.motion_volumeTextInFade)))
        val SPAN = ms(Tok.motion_volumeOpenEnd) + ms(Tok.motion_volumeRestHold) + ms(Tok.motion_volumeRestSlow) + ms(Tok.motion_volumeScrimOut)
    }
}
