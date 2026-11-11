package net.seilent.funecomp

import net.seilent.funecomp.proto.gen.Tok

internal val OVERLAY_REST = (Tok.motion_volumeOpenEnd + Tok.motion_volumeRestHold).toLong()

internal val OVERLAY_OUT = OVERLAY_REST + Tok.motion_volumeRestSlow.toLong()

internal val OVERLAY_SPAN = OVERLAY_OUT + Tok.motion_volumeScrimOut.toLong()
