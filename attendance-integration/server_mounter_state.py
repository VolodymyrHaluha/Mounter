"""Versioned LOCAL snapshots. Actions remain the result of LOCAL's chronological calculation."""
import hashlib
import json
import os
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo
from flask import request, jsonify
from psycopg2.extras import Json

CONTRACT_VERSION = 2


def card_key(label):
    return hashlib.sha256(label.strip().encode('utf-8')).hexdigest()


def timestamp_ms(value):
    if not value:
        return None
    if isinstance(value, str):
        value = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if value.tzinfo is None:
        value = value.replace(tzinfo=ZoneInfo(os.environ.get('ATTENDANCE_TIMEZONE', 'Europe/Kyiv')))
    return int(value.timestamp() * 1000)


def lock_employee(conn, employee_id):
    with conn.cursor() as cur:
        cur.execute('SELECT pg_advisory_xact_lock(%s)', (employee_id,))


def current_employee_state(conn, employee_id):
    lock_employee(conn, employee_id)
    with conn.cursor() as cur:
        cur.execute('SELECT revision FROM mounter_employee_state WHERE employee_id = %s', (employee_id,))
        if cur.fetchone() is None:
            # One-time restoration of LOCAL chronology for histories created by legacy client hints.
            cur.execute('SELECT id FROM attendance_events WHERE employee_id = %s ORDER BY received_at ASC, id ASC', (employee_id,))
            for index, row in enumerate(cur.fetchall()):
                cur.execute('UPDATE attendance_events SET server_action = %s WHERE id = %s',
                            ('check_in' if index % 2 == 0 else 'check_out', row['id']))
        cur.execute('''SELECT external_uuid, server_action, received_at FROM attendance_events
                       WHERE employee_id = %s AND sync_status IN ('local', 'synced')
                       ORDER BY received_at DESC, id DESC LIMIT 1''', (employee_id,))
        latest = cur.fetchone()
        if not latest or latest['server_action'] not in ('check_in', 'check_out'):
            raise ValueError('Немає підтвердженої робочої зміни')
        cur.execute('''SELECT received_at FROM attendance_events WHERE employee_id = %s
                       AND server_action = 'check_in' AND received_at <= %s
                       AND sync_status IN ('local', 'synced') ORDER BY received_at DESC, id DESC LIMIT 1''',
                    (employee_id, latest['received_at']))
        arrival = cur.fetchone()
        if not arrival:
            raise ValueError('Не знайдено початок зміни')
        state = dict(employee_id=employee_id, last_confirmed_event_id=str(latest['external_uuid']),
                     last_confirmed_action=latest['server_action'], work_started_at=timestamp_ms(arrival['received_at']),
                     work_ended_at=timestamp_ms(latest['received_at']) if latest['server_action'] == 'check_out' else None,
                     last_confirmed_at=timestamp_ms(latest['received_at']), confirmation_source='LOCAL')
        fingerprint = hashlib.sha256(json.dumps(state, sort_keys=True).encode()).hexdigest()
        cur.execute('''INSERT INTO mounter_employee_state (employee_id, fingerprint) VALUES (%s, %s)
                       ON CONFLICT (employee_id) DO UPDATE SET fingerprint = EXCLUDED.fingerprint,
                       revision = CASE WHEN mounter_employee_state.fingerprint <> EXCLUDED.fingerprint
                           THEN nextval('mounter_state_revision_seq') ELSE mounter_employee_state.revision END
                       RETURNING revision''', (employee_id, fingerprint))
        state['state_revision'] = cur.fetchone()['revision']
        return state


def event_confirmation(conn, event_uuid, expected_device=None, state=None):
    event_uuid = str(uuid.UUID(str(event_uuid)))
    with conn.cursor() as cur:
        cur.execute('''SELECT ev.*, e.dismissed, i.is_active AS identity_active
                       FROM attendance_events ev LEFT JOIN employees e ON e.id = ev.employee_id
                       LEFT JOIN attendance_identities i ON i.employee_id = ev.employee_id
                           AND i.identity_type = ev.identity_type AND i.identity_data = ev.identity_data
                       WHERE ev.external_uuid = %s LIMIT 1''', (event_uuid,))
        event = cur.fetchone()
    if not event:
        raise LookupError('Подію ще не знайдено на LOCAL')
    device = event.get('devices') or {}
    if expected_device is not None and device.get('device_name') != expected_device['device_name']:
        raise PermissionError('Подія належить іншому пристрою')
    if (not event['employee_id'] or event['dismissed'] is not False or event['identity_active'] is not True
            or event['identity_type'] != 'nfc' or event['sync_status'] not in ('local', 'synced')
            or event['server_action'] not in ('check_in', 'check_out')):
        raise ValueError('NFC-відмітка не підтверджена LOCAL')
    state = dict(state or current_employee_state(conn, event['employee_id']))
    state.update(card_key=card_key(event['identity_data']), card_label=event['identity_data'].strip())
    with conn.cursor() as cur:
        cur.execute('SELECT server_action FROM attendance_events WHERE external_uuid = %s', (event_uuid,))
        event['server_action'] = cur.fetchone()['server_action']
    return dict(ok=True, contract_version=CONTRACT_VERSION, external_uuid=event_uuid,
                employee_id=event['employee_id'], card_key=state['card_key'], identity_type='nfc',
                server_action=event['server_action'], confirmation_source='LOCAL', sync_status=event['sync_status'],
                device_name=device.get('device_name', ''), device_model=device.get('model') or device.get('module') or '',
                device_bluetooth=device.get('device_bluetooth') or '', state=state)


def queue_employee_confirmations(conn, employee_id):
    state = current_employee_state(conn, employee_id)
    with conn.cursor() as cur:
        cur.execute("SELECT external_uuid FROM attendance_events WHERE employee_id = %s AND identity_type = 'nfc'", (employee_id,))
        events = cur.fetchall()
    for event in events:
        try:
            payload = event_confirmation(conn, event['external_uuid'], state=state)
        except (ValueError, LookupError):
            continue
        with conn.cursor() as cur:
            cur.execute('''INSERT INTO mounter_confirmation_outbox (event_uuid, revision, payload) VALUES (%s, %s, %s)
                           ON CONFLICT (event_uuid) DO UPDATE SET revision = EXCLUDED.revision, payload = EXCLUDED.payload''',
                        (payload['external_uuid'], state['state_revision'], Json(payload)))


def seed_existing_confirmations(get_db):
    """Backfill a bounded batch of legacy employee histories; no events are deleted."""
    conn = get_db()
    try:
        with conn.cursor() as cur:
            cur.execute("""SELECT DISTINCT ev.employee_id FROM attendance_events ev
                WHERE ev.identity_type = 'nfc' AND ev.employee_id IS NOT NULL AND NOT EXISTS
                (SELECT 1 FROM mounter_employee_state s WHERE s.employee_id = ev.employee_id)
                ORDER BY ev.employee_id LIMIT 100""")
            employees = cur.fetchall()
        for employee in employees:
            try:
                queue_employee_confirmations(conn, employee['employee_id'])
                conn.commit()
            except ValueError:
                conn.rollback()
        return len(employees)
    finally:
        conn.close()


def publish_confirmations(get_db, global_url, headers, post):
    if not global_url:
        return 0
    conn = get_db()
    try:
        with conn.cursor() as cur:
            cur.execute('SELECT event_uuid, revision, payload FROM mounter_confirmation_outbox ORDER BY revision LIMIT 100')
            rows = cur.fetchall()
        if not rows:
            return 0
        # Only the authenticated LOCAL service may write this relay cache.
        response = post(f'{global_url}/api/sync/mounter-confirmations', headers=headers,
                        json={'contract_version': CONTRACT_VERSION, 'confirmations': [row['payload'] for row in rows]}, timeout=20)
        response.raise_for_status()
        result = response.json()
        if result.get('ok') is not True:
            raise ValueError('GLOBAL не підтвердив збереження результатів LOCAL')
        accepted = set(result.get('accepted') or [])
        with conn.cursor() as cur:
            for row in rows:
                if str(row['event_uuid']) in accepted:
                    cur.execute('DELETE FROM mounter_confirmation_outbox WHERE event_uuid = %s AND revision = %s',
                                (row['event_uuid'], row['revision']))
        conn.commit()
        return len(accepted)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def register_mounter_state(app, get_db, verify_attendance_device):
    def lookup(ids, device_fields):
        conn = get_db()
        try:
            device = verify_attendance_device(conn, device_fields.get('device_name', '').strip(),
                                             device_fields.get('device_model', '').strip(), device_fields.get('device_bluetooth', '').strip())
            results = []
            # Stable lock ordering prevents cross-employee batch deadlocks.
            for event_id in ids:
                try:
                    results.append(event_confirmation(conn, event_id, device))
                    conn.commit()
                except (ValueError, LookupError) as error:
                    conn.rollback()
                    results.append(dict(ok=False, external_uuid=event_id, error=str(error)))
            return results
        finally:
            conn.close()

    @app.route('/api/attendance/events/<event_uuid>/mounter-state', methods=['GET'])
    def mounter_state(event_uuid):
        try:
            result = lookup([str(uuid.UUID(event_uuid))], request.args)[0]
            return jsonify(result), 200 if result['ok'] else 409
        except PermissionError as error:
            return jsonify(ok=False, error=str(error)), 403
        except ValueError as error:
            return jsonify(ok=False, error=str(error)), 400

    @app.route('/api/attendance/cards/state', methods=['POST'])
    def mounter_cards_state():
        data = request.get_json(silent=True) or {}
        ids = data.get('event_uuids')
        if not isinstance(ids, list) or not 1 <= len(ids) <= 100:
            return jsonify(ok=False, error='Потрібно від 1 до 100 UUID'), 400
        try:
            ids = [str(uuid.UUID(str(value))) for value in ids]
            return jsonify(ok=True, contract_version=CONTRACT_VERSION, results=lookup(ids, data))
        except PermissionError as error:
            return jsonify(ok=False, error=str(error)), 403
        except ValueError as error:
            return jsonify(ok=False, error=str(error)), 400
