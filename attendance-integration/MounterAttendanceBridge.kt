package com.example.app

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject
import com.example.mounter.attendance.*

/** Installed in APP-TEST. The provider shares card labels and session times, never photos. */
internal object MounterAttendanceBridge {
    const val MOUNTER_PACKAGE = "com.example.mounter"
    const val REQUEST_ACTION = "com.example.app.action.MOUNTER_ATTENDANCE"
    const val RETURN_CALLBACK = "com.example.mounter.extra.ATTENDANCE_RETURN"
    const val REQUEST_ID = "com.example.mounter.extra.ATTENDANCE_REQUEST_ID"
    private const val RETURN_SCHEDULED = "com.example.app.extra.MOUNTER_RETURN_SCHEDULED"
    val statusUri: Uri = Uri.parse("content://com.example.app.mounter.attendance/status")
    private const val PREFS = "mounter_attendance_access"
    private var requestActivity = WeakReference<Activity>(null)

    fun attach(activity: Activity) {
        if(isTrustedRequest(activity)) requestActivity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if(requestActivity.get() === activity) requestActivity.clear()
    }

    private fun activeRequest(context: Context): Activity? =
        (hostActivity(context)?.takeIf { isTrustedRequest(it) } ?: requestActivity.get())
            ?.takeIf { !it.isFinishing && !it.isDestroyed && isTrustedRequest(it) }

    fun isRequest(intent: Intent): Boolean = intent.action == REQUEST_ACTION

    @Suppress("DEPRECATION")
    private fun returnCallback(intent: Intent): PendingIntent? =
        if(Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(RETURN_CALLBACK, PendingIntent::class.java)
        else intent.getParcelableExtra<PendingIntent>(RETURN_CALLBACK)

    fun isTrustedRequest(activity: Activity): Boolean = isRequest(activity.intent) &&
            (activity.callingPackage == MOUNTER_PACKAGE || returnCallback(activity.intent)?.creatorPackage == MOUNTER_PACKAGE)

    fun isTrustedIncoming(intent: Intent): Boolean = isRequest(intent) &&
            returnCallback(intent)?.creatorPackage == MOUNTER_PACKAGE

    private fun hostActivity(context: Context): Activity? {
        var candidate = context
        while(candidate is ContextWrapper && candidate !is Activity) candidate = candidate.baseContext
        return candidate as? Activity
    }

    fun returnToMounter(activity: Activity) {
        if(!isTrustedRequest(activity)) return
        val callback = returnCallback(activity.intent)?.takeIf { it.creatorPackage == MOUNTER_PACKAGE }
        val sent = callback != null && runCatching { callback.send(); true }.getOrDefault(false)
        if(!sent && activity.callingPackage != MOUNTER_PACKAGE) {
            // The immutable callback can have expired after process/task recreation.
            runCatching {
                val intent = Intent().setClassName(MOUNTER_PACKAGE, "$MOUNTER_PACKAGE.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                activity.startActivity(intent)
            }.onFailure {
                activity.intent.removeExtra(RETURN_SCHEDULED)
                android.widget.Toast.makeText(activity, "Не вдалося повернутись до Mounter: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                return
            }
        }
        activity.setResult(Activity.RESULT_OK)
        activity.finish()
    }

    @Suppress("DEPRECATION")
    fun consumeForwardedTag(activity: Activity): Tag? {
        if(!isTrustedRequest(activity)) return null
        val tag = if(Build.VERSION.SDK_INT >= 33) activity.intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else activity.intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        activity.intent.removeExtra(NfcAdapter.EXTRA_TAG)
        return tag
    }

    // Capturing/cancelling a photo does not change confirmed attendance.
    fun beginNfc(context: Context) = Unit

    private fun sessions(context: Context): List<CardWorkSession> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sessions", "[]")
        val rows = JSONArray(raw)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            CardWorkSession(row.getString("card_id"), row.optString("event_id"),
                row.optString("action"), row.optLong("started_at"), row.optLong("ended_at"),
                row.optString("pending_id"), row.optLong("pending_at"),
                row.optJSONArray("seen_ids")?.let { ids -> (0 until ids.length()).map { ids.getString(it) } }.orEmpty())
        }
    }

    private fun encoded(rows: List<CardWorkSession>): String = JSONArray().apply {
        rows.forEach { row -> put(JSONObject().apply {
            put("card_id", row.cardId); put("event_id", row.eventId); put("action", row.lastConfirmedAction)
            put("started_at", row.startedAt); put("ended_at", row.endedAt)
            put("pending_id", row.pendingEventId); put("pending_at", row.pendingAt); put("seen_ids", JSONArray(row.seenEventIds))
        }) }
    }.toString()

    @Synchronized
    fun recordSaved(context: Context, record: AttendanceRecord, cardId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activity = activeRequest(context)
        val requestId = if(activity == null) "" else activity.intent.getStringExtra(REQUEST_ID)
            ?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            activity.intent.putExtra(REQUEST_ID, it)
        }
        val rows = saveCardEvent(sessions(context), cardId, record.externalUuid, System.currentTimeMillis())
        check(prefs.edit().putString("sessions", encoded(rows))
            .putLong("generation", prefs.getLong("generation", 0) + 1)
            .putString("event_id", record.externalUuid).putString("state", "pending")
            .putString("request_id", requestId)
            .putString("device_name", record.deviceName).putString("device_model", record.deviceModel)
            .putString("device_bluetooth", record.bluetoothName.orEmpty())
            .putString("message", "Очікуємо підтвердження стану відмітки від сервера.").commit()) {
            "Не вдалося зберегти ідентифікатор відмітки."
        }
        context.contentResolver.notifyChange(statusUri, null)
    }

    /** Caller supplies the server's confirmed action for this exact uploaded event. */
    @Synchronized
    fun confirm(context: Context, eventId: String, action: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val before = sessions(context)
        val rows = confirmCardEvent(before, eventId, action)
        if(rows == before) return false
        val state = when(action) {
            "check_in" -> "arrival"
            "check_out" -> "departure"
            else -> return false
        }
        val message = if(state == "arrival") "Сервер підтвердив «Прихід»." else "Сервер підтвердив «Вихід». Відмітьте новий «Прихід», щоб відкрити меню."
        check(prefs.edit().putString("sessions", encoded(rows))
            .putLong("generation", prefs.getLong("generation", 0) + 1)
            .putString("state", state).putString("message", message).commit()) {
            "Не вдалося зберегти підтвердження сервера."
        }
        context.contentResolver.notifyChange(statusUri, null)
        val activity = activeRequest(context)
        val requestId = activity?.intent?.getStringExtra(REQUEST_ID).orEmpty()
        if(activity != null && isTrustedRequest(activity) && requestId.isNotBlank() &&
            prefs.getString("request_id", "") == requestId && !activity.intent.getBooleanExtra(RETURN_SCHEDULED, false)) {
            activity.intent.putExtra(RETURN_SCHEDULED, true)
            Handler(Looper.getMainLooper()).postDelayed({
                if(!activity.isFinishing && !activity.isDestroyed &&
                    prefs.getString("event_id", "") == eventId && prefs.getString("state", "") == state) {
                    returnToMounter(activity)
                } else {
                    activity.intent.removeExtra(RETURN_SCHEDULED)
                }
            }, 1500)
        }
        return true
    }

    @Synchronized
    fun notConfirmed(context: Context, eventId: String, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if(prefs.getString("event_id", "") != eventId) return
        // A connection failure alone must not revoke a previously confirmed arrival.
        if(prefs.getString("state", "") != "pending") return
        if(prefs.getString("message", "") == message) return
        check(prefs.edit().putString("state", "pending").putString("message", message).commit()) {
            "Не вдалося зберегти стан відмітки."
        }
        context.contentResolver.notifyChange(statusUri, null)
    }

    @Synchronized
    fun status(context: Context): Cursor {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = sessions(context)
        val allowed = hasActiveAttendance(rows)
        return MatrixCursor(arrayOf("state", "generation", "event_id", "message", "request_id", "sessions")).apply {
            addRow(arrayOf<Any?>(
                if(allowed) "arrival" else "locked", prefs.getLong("generation", 0),
                rows.firstOrNull { it.active }?.eventId.orEmpty(),
                if(allowed) "Доступ підтверджено LOCAL." else prefs.getString("message", "Відмітьте «Прихід», щоб відкрити меню."),
                prefs.getString("request_id", ""), encoded(rows)
            ))
        }
    }

    @Synchronized
    fun confirmationRequests(context: Context): List<MounterConfirmationRequest> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sessions(context).filter { it.pendingEventId.isNotBlank() }.map {
            MounterConfirmationRequest(it.pendingEventId, prefs.getString("device_name", "").orEmpty(),
                prefs.getString("device_model", "").orEmpty(), prefs.getString("device_bluetooth", "").orEmpty())
        }
    }


}

class MounterAttendanceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val appContext = checkNotNull(context)
        val packages = appContext.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        check(MounterAttendanceBridge.MOUNTER_PACKAGE in packages) { "Цей стан доступний лише Mounter." }
        require(uri == MounterAttendanceBridge.statusUri) { "Невідомий маршрут." }
        return MounterAttendanceBridge.status(appContext)
    }
    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.frop.mounter-attendance"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}