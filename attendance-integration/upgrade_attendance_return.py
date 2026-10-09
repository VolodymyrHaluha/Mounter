"""Upgrade attendance sources previously patched by PR #15 without replacing user code."""
import argparse
from pathlib import Path
from integrate_attendance import replace_once, add_reused_requests, MOUNTER_CONTENT


def main():
    raise SystemExit("Legacy installer disabled: install the paired APP-TEST/Mounter v2 sources and server migrations described in attendance-integration/README.md. No files changed.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    root = parser.parse_args().project.resolve()
    src = root / "app/src/main" if (root / "app/src/main").is_dir() else root / "src/main"
    java = src / "java/com/example/app"
    main_file, api_file = java / "MainActivity.kt", java / "GlobalAttendanceApi.kt"
    bridge_file, confirmation_file = java / "MounterAttendanceBridge.kt", java / "MounterServerConfirmation.kt"
    originals = {path: path.read_bytes() for path in [main_file, api_file, bridge_file, confirmation_file]}
    main_text = originals[main_file].decode("utf-8").replace("\r\n", "\n")
    main_text = replace_once(main_text,
        "MounterAttendanceBridge.isRequest(intent) && callingPackage != MounterAttendanceBridge.MOUNTER_PACKAGE",
        "MounterAttendanceBridge.isRequest(intent) && !MounterAttendanceBridge.isTrustedRequest(this)", "request validation")
    main_text = main_text.replace("MounterAttendanceBridge.recordSaved(context, record)",
        "MounterAttendanceBridge.recordSaved(context, record, pending.identification)")
    old_content = '''        setContent { AppTheme { NetworkStatusScreen { callback ->
            onNfcTag = callback
            MounterAttendanceBridge.consumeForwardedTag(this)?.let { tag ->
                window.decorView.post { onTagDiscovered(tag) }
            }
        } } }'''
    main_text = replace_once(main_text, old_content, MOUNTER_CONTENT, "attendance content")
    main_text = add_reused_requests(main_text)
    api_text = originals[api_file].decode("utf-8").replace("\r\n", "\n")
    old_lookup = '''                if (server == ServerKind.LOCAL && record.action == "NFC") runCatching {
                    fetchMounterAction(MounterConfirmationRequest(record.externalUuid,
                        record.deviceName, record.deviceModel, record.bluetoothName.orEmpty()))
                }.getOrNull() else null'''
    if api_text.count(old_lookup) != 2:
        raise SystemExit("Cannot locate the two previous event-confirmation hooks; no files changed.")
    api_text = api_text.replace(old_lookup, "                mounterUploadAction(record, server, json)")

    updates = {main_file: main_text.encode("utf-8"), api_file: api_text.encode("utf-8")}
    for path in [main_file, api_file]:
        if b"\r\n" in originals[path]:
            updates[path] = updates[path].replace(b"\n", b"\r\n")
    for path in [bridge_file, confirmation_file]:
        updates[path] = Path(__file__).with_name(path.name).read_bytes()
    content_file = java / "MounterAttendanceContent.kt"
    model_file = java / "CardSessions.kt"
    backup_paths = {path: path.with_name(path.name + ".before-mounter-return-v2") for path in originals}
    if content_file.exists() or model_file.exists() or any(path.exists() for path in backup_paths.values()):
        raise SystemExit("A v2 backup/content file already exists; no files changed.")
    try:
        for path, backup in backup_paths.items():
            backup.write_bytes(originals[path])
        for path, data in updates.items():
            path.write_bytes(data)
        model_file.write_bytes((Path(__file__).parent.parent / "app/src/main/java/com/example/mounter/attendance/CardSessions.kt").read_bytes())
        content_file.write_bytes(Path(__file__).with_name(content_file.name).read_bytes())
    except Exception:
        for path, data in originals.items():
            path.write_bytes(data)
        content_file.unlink(missing_ok=True)
        model_file.unlink(missing_ok=True)
        raise
    print("Attendance return upgraded. Rebuild and install the attendance app; originals: *.before-mounter-return-v2.")


if __name__ == "__main__":
    main()
