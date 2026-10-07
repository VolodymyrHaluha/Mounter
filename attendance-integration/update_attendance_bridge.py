"""Update the owned bridge files in an attendance project with the v2 integration."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    root = parser.parse_args().project.resolve()
    src = root / "app/src/main" if (root / "app/src/main").is_dir() else root / "src/main"
    java = src / "java/com/example/app"
    main_text = (java / "MainActivity.kt").read_text(encoding="utf-8")
    if "MounterAttendanceContent" not in main_text or "isTrustedIncoming" not in main_text:
        raise SystemExit("Apply the initial integration or v2 upgrade first; no files changed.")
    names = ["MounterAttendanceBridge.kt", "MounterAttendanceContent.kt"]
    originals = {java / name: (java / name).read_bytes() for name in names}
    updates = {java / name: Path(__file__).with_name(name).read_bytes() for name in names}
    if originals == updates:
        print("Attendance bridge is already current.")
        return
    backups = {path: path.with_name(path.name + ".before-mounter-app-selection") for path in originals}
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
        raise
    print("Bridge updated. Rebuild and install the attendance app. Originals: *.before-mounter-app-selection.")


if __name__ == "__main__":
    main()
