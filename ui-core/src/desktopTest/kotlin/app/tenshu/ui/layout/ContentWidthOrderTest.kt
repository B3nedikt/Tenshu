package app.tenshu.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠⚠ **`Modifier.fillMaxSize().appContentWidth()` does not cap anything** — the order is
 * load-bearing and the broken one is the order [appContentWidth]'s own KDoc used to show.
 *
 * `fillMaxSize` measures its child with a **fixed** width, so the `widthIn(max = …)` inside it
 * receives `minWidth == maxWidth == parentWidth` and `Constraints.constrain` coerces the 840 dp
 * ceiling straight back up to the parent's width. Nothing errors; the content simply spans the
 * whole window, which is indistinguishable from "we never applied a cap".
 *
 * These two tests exist so the difference is a fact rather than a claim, and so a future
 * refactor that swaps the order back fails here instead of silently un-capping every screen.
 */
@OptIn(ExperimentalTestApi::class)
class ContentWidthOrderTest {

    private fun measuredWidth(content: @Composable (onWidth: (Int) -> Unit) -> Unit): Int {
        var width = -1
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent {
                TenshuTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        content { width = it }
                    }
                }
            }
            waitForIdle()
        }
        return width
    }

    @Test
    fun capAfterFillMaxSize_isANoOp() {
        val width = measuredWidth { onWidth ->
            Box(Modifier.fillMaxSize().appContentWidth().onSizeChanged { onWidth(it.width) })
        }
        assertEquals(1440, width, "fillMaxSize() before appContentWidth() must not be able to cap")
    }

    @Test
    fun capBeforeFillMaxSize_caps() {
        val width = measuredWidth { onWidth ->
            Box(Modifier.appContentWidth().fillMaxSize().onSizeChanged { onWidth(it.width) })
        }
        assertEquals(AppContentMaxWidth.value.toInt(), width, "appContentWidth() first must cap at 840dp")
    }
}
