package com.example.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

internal data class MounterConfirmationRequest(
    val eventId: String, val deviceName: String, val deviceModel: String, val deviceBluetooth: String
)

internal fun confirmedMounterAction(json: JSONObject?, eventId: String): String? {
    if(json == null || !json.optBoolean("ok")) return null
    val matchesEvent = runCatching {
        UUID.fromString(json.optString("external_uuid")) == UUID.fromString(eventId)
    }.getOrDefault(false)
    if(!matchesEvent || json.optLong("employee_id", 0) <= 0 || json.optString("identity_type") != "nfc") return null
    if(json.optString("sync_status") !in setOf("local", "synced")) return null
    return json.optString("server_action").takeIf { it == "check_in" || it == "check_out" }
}

/** LOCAL owns the final action; GLOBAL may still have an unresolved/pending identity. */
internal fun fetchMounterAction(request: MounterConfirmationRequest): String? {
    val id = runCatching { UUID.fromString(request.eventId).toString() }.getOrNull() ?: return null
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    val url = "${ServerKind.LOCAL.baseUrl}/api/attendance/events/$id/mounter-state" +
        "?device_name=${encode(request.deviceName)}&device_model=${encode(request.deviceModel)}" +
        "&device_bluetooth=${encode(request.deviceBluetooth)}"
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout=5000
        readTimeout=7000
        setRequestProperty("Accept", "application/json")
    }
    return try {
        if(connection.responseCode != 200) null
        else confirmedMounterAction(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }), id)
    } finally { connection.disconnect() }
}

internal suspend fun refreshMounterConfirmation(context: Context) {
    val request = MounterAttendanceBridge.confirmationRequest(context) ?: return
    val action = withContext(Dispatchers.IO) { runCatching { fetchMounterAction(request) }.getOrNull() }
    if(action != null) MounterAttendanceBridge.confirm(context, request.eventId, action)
    else MounterAttendanceBridge.notConfirmed(context, request.eventId,
        "Сервер ще не підтвердив «Прихід/Вихід». Перевірте підключення та завершення імпорту відмітки на LOCAL.")
}
