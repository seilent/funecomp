package net.seilent.funecomp.ui

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag

@Composable
fun LoginPage(
    name: String,
    host: String,
    onBack: () -> Unit,
    insets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    ime: WindowInsets = WindowInsets.ime,
    web: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val bleed = Bleed(insets.getLeft(density, dir), insets.getTop(density), insets.getRight(density, dir), insets.getBottom(density))
    BoxWithConstraints(Modifier.fillMaxSize().background(Black).windowInsetsPadding(insets)) {
        val ui = rememberUi(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        CompositionLocalProvider(LocalUi provides ui, LocalBleed provides bleed, LocalOverscrollFactory provides null) {
            HubPage(name, onBack, count = host, keepEnd = true) {
                Box(Modifier.fillMaxSize().padding(top = ui.px(ui.contentTop).dp()).windowInsetsPadding(remember(ime, insets) { ime.exclude(insets) }).testTag("login.web")) {
                    web?.invoke()
                }
            }
        }
    }
}
