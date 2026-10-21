package net.seilent.funecomp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import net.seilent.funecomp.ui.LocalUptime

fun ComposeContentTestRule.setUptimeContent(content: @Composable () -> Unit) = setContent {
    CompositionLocalProvider(LocalUptime provides { mainClock.currentTime }, content = content)
}
