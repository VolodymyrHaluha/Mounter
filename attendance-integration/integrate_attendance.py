"""Apply the Mounter bridge to the supplied attendance Android sources."""
import argparse
from pathlib import Path


def replace_once(text, old, new, label):
    if text.count(old) != 1:
        raise SystemExit(f"Cannot locate exactly one {label}; no files changed.")
    return text.replace(old, new, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path, help="Attendance project root (or extracted src's parent)")
    args = parser.parse_args()
    root = args.project.resolve()
    src = root / "app/src/main" if (root / "app/src/main").is_dir() else root / "src/main"
    java = src / "java/com/example/app"
    main_file, api_file = java / "MainActivity.kt", java / "GlobalAttendanceApi.kt"
    kiosk_file, manifest_file = java / "KioskManager.kt", src / "AndroidManifest.xml"
    paths = [main_file, api_file, kiosk_file, manifest_file]
    originals = {path: path.read_bytes() for path in paths}
    texts = {path: data.decode("utf-8").replace("\r\n", "\n") for path, data in originals.items()}
    if any("MounterAttendanceBridge" in text for text in texts.values()):
        raise SystemExit("Bridge already installed; no files changed.")

    text = texts[main_file]
    text = replace_once(text,
        "        enableEdgeToEdge()\n        setContent { AppTheme { NetworkStatusScreen { onNfcTag = it } } }",
        '''        if (MounterAttendanceBridge.isRequest(intent) && callingPackage != MounterAttendanceBridge.MOUNTER_PACKAGE) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent { AppTheme { NetworkStatusScreen { callback ->
            onNfcTag = callback
            MounterAttendanceBridge.consumeForwardedTag(this)?.let { tag ->
                window.decorView.post { onTagDiscovered(tag) }
            }
        } } }''', "attendance launch")
    text = replace_once(text,
        '        if (pendingCapture == null) {\n            pendingCapture = PendingCapture(action, identification)',
        '''        if (pendingCapture == null) {
            if (action == "NFC" && runCatching { MounterAttendanceBridge.beginNfc(context) }.isFailure) {
                saveVisualState = SaveVisualState.FAILURE
                return
            }
            pendingCapture = PendingCapture(action, identification)''', "NFC capture")
    anchor = "                    val record = photoPath?.let { addRecord(pending.action, pending.identification, it) }"
    text = replace_once(text, anchor, anchor + '''
                    if (record != null && pending.action == "NFC") {
                        runCatching { MounterAttendanceBridge.recordSaved(context, record) }
                    }''', "persisted NFC record")
    text = replace_once(text, "                if (results.any { it.success }) {", '''                if (attemptedRecord.action == "NFC" && !attemptedRecord.syncErrorPhase) {
                    val action = results.firstNotNullOfOrNull { it.confirmedAction }
                    if (action != null) MounterAttendanceBridge.confirm(context, attemptedRecord.externalUuid, action)
                    else MounterAttendanceBridge.notConfirmed(context, attemptedRecord.externalUuid,
                        "Відмітку ще не підтверджено LOCAL. Перевірте підключення та імпорт відмітки.")
                }
                if (results.any { it.success }) {''', "server result")
    anchor = "    // Failed records remain only on the phone during the ten-attempt window."
    text = replace_once(text, anchor, '''    // GLOBAL acceptance is provisional; wait for LOCAL's final event action.
    LaunchedEffect(connection.activeServer) {
        if (connection.activeServer != ServerKind.LOCAL) return@LaunchedEffect
        while (true) {
            refreshMounterConfirmation(context)
            delay(5_000L)
        }
    }

''' + anchor, "confirmation polling")
    texts[main_file] = text

    text = replace_once(texts[api_file], "    val connectionFailed: Boolean = false,",
        "    val connectionFailed: Boolean = false,\n    val confirmedAction: String? = null,", "sync result model")
    for old, message in [
        ('GlobalSyncResult(true, "${server.name} • збережено")', '${server.name} • збережено'),
        ('GlobalSyncResult(true, "${server.name} • вже збережено")', '${server.name} • вже збережено'),
    ]:
        new = '''GlobalSyncResult(true, "MESSAGE", confirmedAction =
                if (server == ServerKind.LOCAL && record.action == "NFC") runCatching {
                    fetchMounterAction(MounterConfirmationRequest(record.externalUuid,
                        record.deviceName, record.deviceModel, record.bluetoothName.orEmpty()))
                }.getOrNull() else null)'''.replace("MESSAGE", message)
        text = replace_once(text, old, new, "event confirmation result")
    texts[api_file] = text
    texts[kiosk_file] = replace_once(texts[kiosk_file],
        "dpm.setLockTaskPackages(admin, arrayOf(appContext.packageName))",
        'dpm.setLockTaskPackages(admin, arrayOf(appContext.packageName, "com.example.mounter"))', "kiosk allowlist")
    texts[manifest_file] = replace_once(texts[manifest_file], "    </application>", '''        <provider
            android:name=".MounterAttendanceProvider"
            android:authorities="com.example.app.mounter.attendance"
            android:exported="true"
            android:grantUriPermissions="false" />
    </application>''', "provider declaration")

    additions = {java / name: Path(__file__).with_name(name).read_bytes()
                 for name in ["MounterAttendanceBridge.kt", "MounterServerConfirmation.kt"]}
    backups = {path: path.with_name(path.name + ".before-mounter") for path in paths}
    if any(path.exists() for path in [*backups.values(), *additions]):
        raise SystemExit("A backup/addition already exists; no files changed.")
    updates = {path: text.replace("\n", "\r\n").encode("utf-8") if b"\r\n" in originals[path]
               else text.encode("utf-8") for path, text in texts.items()}
    try:
        for path, backup in backups.items():
            backup.write_bytes(originals[path])
        for path, data in {**updates, **additions}.items():
            path.write_bytes(data)
    except Exception:
        for path, data in originals.items():
            path.write_bytes(data)
        for path in additions:
            path.unlink(missing_ok=True)
        raise
    print("Attendance sources updated. Original files: *.before-mounter. Rebuild and install the attendance app.")


if __name__ == "__main__":
    main()
