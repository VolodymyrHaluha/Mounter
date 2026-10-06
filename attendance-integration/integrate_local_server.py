"""Install the status lookup and fix the supplied LOCAL server's stale action response."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="Path to LOCAL app.py")
    path = parser.parse_args().app.resolve()
    original = path.read_bytes()
    text = original.decode("utf-8").replace("\r\n", "\n")
    old = '''        if employee_id:
            recalculate_employee_actions(conn, employee_id)

        conn.commit()

        return jsonify({'''
    new = '''        if employee_id:
            recalculate_employee_actions(conn, employee_id)

        # Return the action actually stored after chronological recalculation.
        with conn.cursor() as cur:
            cur.execute("SELECT server_action FROM attendance_events WHERE id = %s", (event_id,))
            server_action = cur.fetchone()["server_action"]

        conn.commit()

        return jsonify({'''
    registration = '''
from server_mounter_state import register_mounter_state
register_mounter_state(app, get_db, verify_attendance_device)


'''
    entry = 'if __name__ == "__main__":'
    if text.count(old) != 1 or text.count(entry) != 1 or "register_mounter_state" in text:
        raise SystemExit("LOCAL source does not match or integration is already installed; no files changed.")
    addon = path.with_name("server_mounter_state.py")
    backup = path.with_name(path.name + ".before-mounter")
    if addon.exists() or backup.exists():
        raise SystemExit("Backup/addon already exists; no files changed.")
    text = text.replace(old, new, 1).replace(entry, registration + entry, 1)
    if b"\r\n" in original:
        text = text.replace("\n", "\r\n")
    try:
        backup.write_bytes(original)
        addon.write_bytes(Path(__file__).with_name(addon.name).read_bytes())
        path.write_bytes(text.encode("utf-8"))
    except Exception:
        path.write_bytes(original)
        addon.unlink(missing_ok=True)
        raise
    print("LOCAL updated; original app.py is saved as app.py.before-mounter. Restart the LOCAL service.")


if __name__ == "__main__":
    main()
