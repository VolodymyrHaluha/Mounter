package com.example.mounter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

private class EditingField(
    val coordinates: () -> LayoutCoordinates?,
    val finish: () -> Unit
)

private class InputEditingState {
    var field: EditingField? = null
}

private val LocalInputEditing = compositionLocalOf<InputEditingState> {
    error("InputEditingHost is required")
}

@Composable
internal fun InputEditingHost(content: @Composable () -> Unit) {
    val state = remember { InputEditingState() }
    CompositionLocalProvider(LocalInputEditing provides state) {
        Box(Modifier.dismissInputOnOutsideTouch()) { content() }
    }
}

// Add this to separate windows (dialogs), whose pointer events do not reach the host.
internal fun Modifier.dismissInputOnOutsideTouch(): Modifier = composed {
    val state = LocalInputEditing.current
    // Keep coordinates in a stable holder so pointer input sees layout updates.
    val origin = remember { arrayOfNulls<LayoutCoordinates>(1) }
    onGloballyPositioned { origin[0] = it }.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                    ?: continue
                val root = origin[0]?.takeIf { it.isAttached } ?: continue
                val field = state.field ?: continue
                val bounds = field.coordinates()?.takeIf { it.isAttached }?.boundsInWindow()
                val position = root.localToWindow(down.position)
                if (bounds == null || !bounds.contains(position)) field.finish()
                // Do not consume: buttons and another field still receive this touch.
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
internal fun Modifier.finishEditingOnOutsideTouch(): Modifier = composed {
    val state = LocalInputEditing.current
    val focusManager = LocalFocusManager.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val field = remember(state, focusManager) {
        EditingField({ coordinates[0] }) {
            // Ending the text input session also dismisses its keyboard.
            focusManager.clearFocus(force = true)
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    val previousVisibility = remember { booleanArrayOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        if (previousVisibility[0] && !imeVisible && state.field === field) {
            field.finish()
        }
        previousVisibility[0] = imeVisible
    }
    DisposableEffect(field) {
        onDispose { if (state.field === field) state.field = null }
    }
    onGloballyPositioned { coordinates[0] = it }.onFocusChanged {
        if (it.isFocused) state.field = field
        else if (state.field === field) state.field = null
    }
}