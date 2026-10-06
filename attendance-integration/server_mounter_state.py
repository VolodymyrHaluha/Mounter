"""Additive module for the user's LOCAL attendance Flask server."""

import uuid
from flask import request, jsonify


def register_mounter_state(app, get_db, verify_attendance_device):
    @app.route("/api/attendance/events/<event_uuid>/mounter-state", methods=["GET"])
    def mounter_state(event_uuid):
        try:
            event_uuid = str(uuid.UUID(event_uuid))
        except ValueError:
            return jsonify(ok=False, error="Некоректний external_uuid"), 400
        conn = None
        try:
            conn = get_db()
            device = verify_attendance_device(
                conn,
                request.args.get("device_name", "").strip(),
                request.args.get("device_model", "").strip(),
                request.args.get("device_bluetooth", "").strip(),
            )
            with conn.cursor() as cur:
                cur.execute("""
                    SELECT ev.external_uuid, ev.employee_id, ev.identity_type,
                           ev.server_action, ev.sync_status,
                           ev.devices ->> 'device_name' AS device_name,
                           e.dismissed, i.is_active AS identity_active
                    FROM attendance_events ev
                    LEFT JOIN employees e ON e.id = ev.employee_id
                    LEFT JOIN attendance_identities i
                      ON i.employee_id = ev.employee_id
                     AND i.identity_type = ev.identity_type
                     AND i.identity_data = ev.identity_data
                    WHERE ev.external_uuid = %s
                    LIMIT 1
                """, (event_uuid,))
                event = cur.fetchone()
            if not event:
                return jsonify(ok=False, error="Відмітку ще не знайдено на LOCAL"), 404
            if event["device_name"] != device["device_name"]:
                return jsonify(ok=False, error="Відмітка належить іншому пристрою"), 403
            if (not event["employee_id"] or event["dismissed"] is not False
                    or event["identity_active"] is not True
                    or event["identity_type"] != "nfc"
                    or event["sync_status"] not in {"local", "synced"}
                    or event["server_action"] not in {"check_in", "check_out"}):
                return jsonify(ok=False, error="Відмітка не підтверджена"), 409
            conn.commit()
            return jsonify(
                ok=True, external_uuid=event_uuid, employee_id=event["employee_id"],
                identity_type=event["identity_type"], server_action=event["server_action"],
                sync_status=event["sync_status"],
            )
        except PermissionError as error:
            return jsonify(ok=False, error=str(error)), 403
        except ValueError as error:
            return jsonify(ok=False, error=str(error)), 400
        except Exception:
            return jsonify(ok=False, error="Не вдалося перевірити відмітку на LOCAL"), 503
        finally:
            if conn is not None:
                conn.close()
