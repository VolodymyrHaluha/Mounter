package com.example.app

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun MounterAttendanceContent(activity: Activity, content: @Composable () -> Unit) {
    DisposableEffect(activity, activity.intent) {
        MounterAttendanceBridge.attach(activity)
        onDispose { MounterAttendanceBridge.detach(activity) }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        if(MounterAttendanceBridge.isTrustedRequest(activity)) {
            BackHandler { MounterAttendanceBridge.returnToMounter(activity) }
            TextButton(onClick={ MounterAttendanceBridge.returnToMounter(activity) },
                modifier=Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp)
                    .background(Color(0xEE16261F), RoundedCornerShape(12.dp))) {
                Text("Повернутись до монтажників", color=Color.White)
            }
        }
    }
}
