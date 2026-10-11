package io.github.xomel45.openschool

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A swipe left (towards the future) or right (towards the past) turns the page. A strip that scrolls sideways itself (the
 * recent marks) takes its own drags first, so it is not affected. `enabledNext` / `enabledPrev` keep the swipe inside the limits.
 * The callbacks are read at the moment of the swipe (they capture the page that is shown then, not the one of the first draw).
 */
fun Modifier.swipePages(enabledPrev: Boolean = true, enabledNext: Boolean = true, onPrev: () -> Unit, onNext: () -> Unit): Modifier = composed {
    val prev by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    val canPrev by rememberUpdatedState(enabledPrev)
    val canNext by rememberUpdatedState(enabledNext)
    pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { total = 0f },
            onDragEnd = {
                val threshold = size.width * 0.25f
                if (total <= -threshold && canNext) next() else if (total >= threshold && canPrev) prev()
                total = 0f
            },
        ) { _, delta -> total += delta }
    }
}
