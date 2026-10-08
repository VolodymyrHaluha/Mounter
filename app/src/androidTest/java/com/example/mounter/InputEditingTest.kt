package com.example.mounter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InputEditingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun outsideTouchFinishesEditingAndPreservesText() {
        val value = mutableStateOf("")
        compose.setContent {
            MounterTheme {
                Column {
                    OutlinedTextField(value.value, { value.value = it },
                        modifier = Modifier.finishEditingOnOutsideTouch().testTag("field"))
                    Spacer(Modifier.size(100.dp).testTag("outside"))
                }
            }
        }
        compose.onNodeWithTag("field").performTouchInput { click() }
        compose.onNodeWithTag("field").performTextInput("23232323")
        compose.onNodeWithTag("outside").performTouchInput { click() }
        compose.onNodeWithTag("field").assertIsNotFocused().assertTextEquals("23232323")
    }

    @Test fun touchesInsideKeepFocusAndAnotherFieldReceivesFocus() {
        compose.setContent {
            MounterTheme {
                Column {
                    OutlinedTextField("", {}, modifier = Modifier.finishEditingOnOutsideTouch().testTag("first"))
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField("", {}, modifier = Modifier.finishEditingOnOutsideTouch().testTag("second"))
                }
            }
        }
        compose.onNodeWithTag("first").performTouchInput { click() }
        compose.onNodeWithTag("first").performTouchInput { click() }
        compose.onNodeWithTag("first").assertIsFocused()
        compose.onNodeWithTag("second").performTouchInput { click() }
        compose.onNodeWithTag("first").assertIsNotFocused()
        compose.onNodeWithTag("second").assertIsFocused()
    }

    @Test fun dialogButtonFinishesEditingAndActsOnFirstTouch() {
        var clicks = 0
        compose.setContent {
            MounterTheme {
                AlertDialog(onDismissRequest = {}, modifier = Modifier.dismissInputOnOutsideTouch(),
                    text = {
                        OutlinedTextField("", {},
                            modifier = Modifier.finishEditingOnOutsideTouch().testTag("field"))
                    },
                    confirmButton = {
                        Button(onClick = { clicks++ }, modifier = Modifier.testTag("button")) { Text("OK") }
                    })
            }
        }
        compose.onNodeWithTag("field").performTouchInput { click() }
        compose.onNodeWithTag("button").performTouchInput { click() }
        compose.onNodeWithTag("field").assertIsNotFocused()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
