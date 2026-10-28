package net.seilent.funecomp

import android.widget.EditText
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

fun resumedRoot() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().window.decorView

fun secretField(): EditText = checkNotNull(resumedRoot().findViewWithTag("entry"))
