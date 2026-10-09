package com.example.app

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

data class ConfirmedAttendanceResponse(val eventId: String, val state: CardAttendanceState)

internal fun serverTime(value: Any?): Long? = when(value) {
    is Number -> value.toLong().takeIf { it > 0 }
    is String -> {
        if(!Regex(".*(Z|[+-][0-9]{2}:[0-9]{2})$").matches(value)) null
        else listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient=false }.parse(value)?.time }.getOrNull()
        }
    }
    else -> null
}

/** GLOBAL can only relay a versioned LOCAL state. A 200/duplicate/client_action is not confirmation. */
internal fun parseAttendanceConfirmation(json: JSONObject?, expectedId: String, label: String, server: ServerKind): ConfirmedAttendanceResponse? {
    if(json == null || !json.optBoolean("ok") || json.optInt("contract_version") != ATTENDANCE_CONTRACT_VERSION) return null
    val matches = runCatching { UUID.fromString(json.optString("external_uuid")) == UUID.fromString(expectedId) }.getOrDefault(false)
    if(!matches || json.optString("identity_type") != "nfc" || json.optString("card_key") != cardKey(label) ||
        json.optString("confirmation_source") != "LOCAL" || json.optString("server_action") !in setOf("check_in", "check_out") ||
        json.optString("sync_status") !in setOf("local", "synced")) return null
    if(server == ServerKind.GLOBAL && json.optString("sync_status") != "synced") return null
    val state = json.optJSONObject("state") ?: return null
    if(state.optString("card_key") != cardKey(label) || state.optString("card_label") != label.trim() ||
        state.optString("confirmation_source") != "LOCAL" || state.optLong("employee_id") <= 0 || state.optLong("state_revision") <= 0) return null
    val action = state.optString("last_confirmed_action")
    val event = state.optString("last_confirmed_event_id")
    if(action !in setOf("check_in", "check_out") || runCatching { UUID.fromString(event) }.isFailure) return null
    val started = serverTime(state.opt("work_started_at")) ?: return null
    val ended = if(state.isNull("work_ended_at")) 0 else serverTime(state.opt("work_ended_at")) ?: return null
    val confirmedAt = serverTime(state.opt("last_confirmed_at")) ?: return null
    if((action == "check_in" && ended != 0L) || (action == "check_out" && ended < started)) return null
    return ConfirmedAttendanceResponse(expectedId, CardAttendanceState(cardKey(label), label.trim(), state.getLong("employee_id"),
        event, action, started, ended, confirmedAt, state.getLong("state_revision"), "LOCAL", "confirmed"))
}

private fun requestJson(server: ServerKind, path: String, body: JSONObject? = null): JSONObject? {
    val connection = (URL("${server.baseUrl}$path").openConnection() as HttpURLConnection).apply {
        connectTimeout=5000; readTimeout=7000; setRequestProperty("Accept", "application/json"); applyCloudflareAccess(server)
        if(body != null) { requestMethod="POST"; doOutput=true; setRequestProperty("Content-Type", "application/json") }
    }
    return try {
        body?.let { connection.outputStream.use { stream -> stream.write(it.toString().toByteArray(Charsets.UTF_8)) } }
        if(connection.responseCode != 200) JSONObject().put("ok",false).put("error","API підтвердження ${server.name}: HTTP ${connection.responseCode}")
        else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally { connection.disconnect() }
}

internal fun fetchAttendanceConfirmation(event: PendingAttendanceEvent, server: ServerKind): ConfirmedAttendanceResponse? {
    val id = runCatching { UUID.fromString(event.eventId).toString() }.getOrNull() ?: return null
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    val path = "/api/attendance/events/$id/mounter-state?device_name=${encode(event.deviceName)}" +
        "&device_model=${encode(event.deviceModel)}&device_bluetooth=${encode(event.deviceBluetooth)}"
    return runCatching {
        val json = requestJson(server, path)
        val label = event.cardLabel.ifBlank { json?.optJSONObject("state")?.optString("card_label").orEmpty() }
        if(cardKey(label) != event.cardKey) null else parseAttendanceConfirmation(json, id, label, server)
    }.getOrNull()
}

/** Uses stored event ownership anchors, so raw NFC identities never occur in URLs. */
internal fun refreshAttendanceConfirmations(context: Context, server: ServerKind) {
    val snapshot = CardAttendanceStore.read(context)
    // Include settled events to refresh employee changes made on another device.
    val anchors = (snapshot.events.filter { it.unresolved } + snapshot.events.groupBy { it.cardKey }.values.map { it.last() })
        .distinctBy { it.eventId }
    for(group in anchors.groupBy { Triple(it.deviceName, it.deviceModel, it.deviceBluetooth) }.values) {
        for(chunk in group.chunked(100)) {
            val first = chunk.first()
            val payload = JSONObject().put("event_uuids", JSONArray(chunk.map { it.eventId }))
                .put("device_name", first.deviceName).put("device_model", first.deviceModel).put("device_bluetooth", first.deviceBluetooth)
            val batch = runCatching { requestJson(server, "/api/attendance/cards/state", payload) }.getOrNull()
            val rows = batch?.takeIf { it.optBoolean("ok") && it.optInt("contract_version") == ATTENDANCE_CONTRACT_VERSION }?.optJSONArray("results")
            if(rows != null) {
                for(index in 0 until rows.length()) {
                    val json = rows.optJSONObject(index) ?: continue
                    val event = chunk.firstOrNull { it.eventId == json.optString("external_uuid") } ?: continue
                    val label = event.cardLabel.ifBlank { json.optJSONObject("state")?.optString("card_label").orEmpty() }
                    if(cardKey(label) != event.cardKey) {
                        if(event.unresolved) CardAttendanceStore.eventStatus(context,event.eventId,event.status,server.name,
                            json.optString("error").ifBlank { "Сервер ще не повернув підтверджену картку v2." })
                        continue
                    }
                    val confirmation = parseAttendanceConfirmation(json, event.eventId, label, server)
                    if(confirmation == null && event.unresolved) CardAttendanceStore.eventStatus(context,event.eventId,event.status,server.name,
                        json.optString("error").ifBlank { "API ${server.name} ще не повернув повне підтвердження LOCAL v2." })
                    confirmation?.let { response ->
                        CardAttendanceStore.update(context) { applyConfirmedAttendance(it, response.eventId, response.state) }
                        MounterAttendanceBridge.confirmed(context, response.eventId)
                    }
                }
            } else {
                for(event in chunk.filter { it.unresolved }) {
                    val confirmation = fetchAttendanceConfirmation(event,server)
                    if(confirmation == null) CardAttendanceStore.eventStatus(context,event.eventId,event.status,server.name,
                        batch?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: "Очікуємо повного підтвердження LOCAL v2 через ${server.name}. Перевірте API та синхронізацію серверів.")
                    else {
                        CardAttendanceStore.update(context) { applyConfirmedAttendance(it, confirmation.eventId, confirmation.state) }
                        MounterAttendanceBridge.confirmed(context,confirmation.eventId)
                    }
                }
            }
        }
    }
}
