package net.seilent.funecomp

import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.gen.Feature

internal val NO_SIG = "features=${Groups.all and (1L shl Feature.updSig).inv()}"
