"""Update the owned bridge files in an attendance project with the multi-card LOCAL integration."""
import argparse
from pathlib import Path


def main():
    raise SystemExit("Legacy installer disabled: install the paired APP-TEST/Mounter v2 sources and server migrations described in attendance-integration/README.md. No files changed.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    root = parser.parse_args().project.resolve()
    src = root / "app/src/main" if (root / "app/src/main").is_dir() else root / "src/main"
    java = src / "java/com/example/app"
    main_text = (java / "MainActivity.kt").read_text(encoding="utf-8")
    if "MounterAttendanceContent" not in main_text or "isTrustedIncoming" not in main_text:
        raise SystemExit("Apply the initial integration or v2 upgrade first; no files changed.")
    names = ["MounterAttendanceBridge.kt", "MounterAttendanceContent.kt", "MounterServerConfirmation.kt"]
    main_file = java / "MainActivity.kt"
    old_hook = "MounterAttendanceBridge.recordSaved(context, record)"
    new_hook = "MounterAttendanceBridge.recordSaved(context, record, pending.identification)"
    if old_hook not in main_text and new_hook not in main_text:
        raise SystemExit("Cannot locate the card identity hook; no files changed.")
    originals = {java / name: (java / name).read_bytes() for name in names}
    originals[main_file] = main_file.read_bytes()
    updates = {java / name: Path(__file__).with_name(name).read_bytes() for name in names}
    updates[main_file] = originals[main_file].replace(old_hook.encode(), new_hook.encode())
    model_file = java / "CardSessions.kt"
    model_data = (Path(__file__).parent.parent / "app/src/main/java/com/example/mounter/attendance/CardSessions.kt").read_bytes()
    if model_file.exists():
        originals[model_file] = model_file.read_bytes()
    updates[model_file] = model_data
    if originals == updates:
        print("Attendance bridge is already current.")
        return
    backups = {path: path.with_name(path.name + ".before-mounter-multi-card") for path in originals}
    if any(path.exists() for path in backups.values()):
        raise SystemExit("A backup already exists; review previous changes before updating. No files changed.")
    try:
        for path, backup in backups.items():
            backup.write_bytes(originals[path])
        for path, data in updates.items():
            path.write_bytes(data)
    except Exception:
        for path, data in originals.items():
            path.write_bytes(data)
        if model_file not in originals:
            model_file.unlink(missing_ok=True)
        raise
    print("Bridge updated. Rebuild and install the attendance app. Originals: *.before-mounter-multi-card.")


if __name__ == "__main__":
    main()