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

/** Read-only card state projection for Mounter; photographs are never shared. */
internal object MounterAttendanceBridge {
    const val MOUNTER_PACKAGE = "com.example.mounter"
    const val REQUEST_ACTION = "com.example.app.action.MOUNTER_ATTENDANCE"
    const val RETURN_CALLBACK = "com.example.mounter.extra.ATTENDANCE_RETURN"
    const val REQUEST_ID = "com.example.mounter.extra.ATTENDANCE_REQUEST_ID"
    private const val RETURN_SCHEDULED = "com.example.app.extra.MOUNTER_RETURN_SCHEDULED"
    val statusUri: Uri = Uri.parse("content://com.example.app.mounter.attendance/status")
    private const val PREFS = "attendance"
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

    // A cancelled capture is not an attendance event.
    fun beginNfc(context: Context) { CardAttendanceStore.read(context) }

    @Synchronized
    fun recordSaved(context: Context, record: AttendanceRecord) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activity = activeRequest(context)
        val requestId = if(activity == null) "" else activity.intent.getStringExtra(REQUEST_ID)
            ?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            activity.intent.putExtra(REQUEST_ID, it)
        }
        check(CardAttendanceStore.read(context).events.any { it.eventId == record.externalUuid }) {
            "Відмітку ще не збережено на пристрої."
        }
        check(prefs.edit().putString("request_id", requestId).commit()) {
            "Не вдалося зберегти запит повернення."
        }
        context.contentResolver.notifyChange(statusUri, null)
        // Returning does not grant access; the provider remains authoritative.
        scheduleReturn(context, record.externalUuid)
    }

    private fun scheduleReturn(context: Context, eventId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activity = activeRequest(context)
        val requestId = activity?.intent?.getStringExtra(REQUEST_ID).orEmpty()
        if(activity != null && requestId.isNotBlank() &&
            prefs.getString("request_id", "") == requestId && !activity.intent.getBooleanExtra(RETURN_SCHEDULED, false)) {
            activity.intent.putExtra(RETURN_SCHEDULED, true)
            Handler(Looper.getMainLooper()).postDelayed({
                if(!activity.isFinishing && !activity.isDestroyed &&
                    CardAttendanceStore.read(context).events.any { it.eventId == eventId }) {
                    returnToMounter(activity)
                } else activity.intent.removeExtra(RETURN_SCHEDULED)
            }, 1500)
        }
    }

    fun confirmed(context: Context, eventId: String) { scheduleReturn(context, eventId) }

    fun cards(context: Context, history: Boolean = false): Cursor {
        val snapshot = CardAttendanceStore.read(context)
        return MatrixCursor(arrayOf("contract_version", "generation", "card_key", "card_label", "employee_id",
            "last_confirmed_event_id", "last_confirmed_action", "work_started_at", "work_ended_at",
            "confirmation_status", "confirmation_source", "server_revision", "last_confirmed_at", "sync_status", "pending")).apply {
            (if(history) snapshot.history else snapshot.cards).forEach { card ->
                addRow(arrayOf<Any?>(ATTENDANCE_CONTRACT_VERSION, snapshot.generation, card.cardKey, card.cardLabel, card.employeeId,
                    card.lastConfirmedEventId, card.lastConfirmedAction, card.workStartedAt, card.workEndedAt,
                    if(card.confirmed) "confirmed" else "unconfirmed", card.confirmationSource, card.serverRevision,
                    card.lastConfirmedAt, if(snapshot.pending(card)) "pending_confirmation" else card.syncStatus, if(!history && snapshot.pending(card)) 1 else 0))
            }
        }
    }
    fun status(context: Context): Cursor {
        val snapshot = CardAttendanceStore.read(context)
        val active = snapshot.cards.firstOrNull { snapshot.allowed(it) }
        val sessions = JSONArray().apply { snapshot.cards.forEach { card -> put(JSONObject().apply {
            put("card_id", card.cardLabel); put("event_id", card.lastConfirmedEventId); put("action", if(card.confirmed) card.lastConfirmedAction else "")
            put("started_at", card.workStartedAt); put("ended_at", card.workEndedAt)
            put("pending_id", if(snapshot.pending(card)) "pending" else ""); put("pending_at", 0)
        }) } }
        return MatrixCursor(arrayOf("contract_version", "state", "generation", "event_id", "message", "request_id", "sessions", "history", "cards")).apply {
            addRow(arrayOf<Any?>(ATTENDANCE_CONTRACT_VERSION, if(snapshot.allowed) "arrival" else "locked", snapshot.generation,
                active?.lastConfirmedEventId.orEmpty(), if(snapshot.allowed) "Доступ підтверджено LOCAL."
                else if(snapshot.events.any { it.unresolved }) snapshot.events.lastOrNull { it.unresolved && it.message.isNotBlank() }?.message
                    ?: "Очікуємо остаточного підтвердження LOCAL."
                else "Відмітьте «Прихід», щоб відкрити Mounter.", "", sessions.toString(), CardAttendanceStore.rowsJson(snapshot.history),
                CardAttendanceStore.rowsJson(snapshot.cards.map { card -> if(snapshot.pending(card)) card.copy(syncStatus="pending_confirmation") else card })))
        }
    }


}

class MounterAttendanceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val appContext = checkNotNull(context)
        val packages = appContext.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        check(MounterAttendanceBridge.MOUNTER_PACKAGE in packages) { "Цей стан доступний лише Mounter." }
        require(uri.authority == MounterAttendanceBridge.statusUri.authority) { "Невідомий provider." }
        return when(uri.path) {
            "/status" -> MounterAttendanceBridge.status(appContext)
            "/cards" -> MounterAttendanceBridge.cards(appContext)
            "/history" -> MounterAttendanceBridge.cards(appContext, history=true)
            else -> error("Невідомий маршрут.")
        }.apply { setNotificationUri(appContext.contentResolver, uri) }
    }
    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.frop.mounter-attendance"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}