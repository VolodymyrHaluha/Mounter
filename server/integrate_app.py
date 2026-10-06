"""Insert the Mounter routes into an existing app.py without starting the server."""
import argparse
from pathlib import Path
import re
import shutil

MOUNTER_ROUTES = r"""
# --- Mounter Android API: uses this application's existing database connection ---
def _mounter_read(query, parameters=()):
    connection = None
    try:
        connection = get_db_connection()
        if connection is None:
            return None, (jsonify(detail='Не вдалося підключитися до PostgreSQL.'), 503)
        with connection.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute(query, parameters)
            return [dict(row) for row in cursor.fetchall()], None
    except psycopg2.Error as error:
        app.logger.warning('Mounter database request failed: %s', type(error).__name__)
        if isinstance(error, (psycopg2.OperationalError, psycopg2.InterfaceError)):
            message = 'Немає підключення до PostgreSQL. Перевірте налаштування сервера БД.'
        else:
            message = 'Помилка запиту до БД. Перевірте таблиці employees, clients та їхні стовпці.'
        return None, (jsonify(detail=message), 503)
    except Exception as error:
        app.logger.warning('Mounter API request failed: %s', type(error).__name__)
        return None, (jsonify(detail='Помилка API під час звернення до БД.'), 500)
    finally:
        if connection is not None:
            connection.close()


def _mounter_search_parameters():
    value = request.args.get('q', '').strip()
    try:
        limit = int(request.args.get('limit', '30'))
    except ValueError:
        return None, None, (jsonify(detail='Некоректний ліміт пошуку.'), 400)
    if len(value) > 200 or not 1 <= limit <= 100:
        return None, None, (jsonify(detail='Некоректні параметри пошуку.'), 400)
    return value, limit, None


def _mounter_search_pattern(value):
    return '%' + value.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'


@app.route('/api/mounter/health', methods=['GET'])
def mounter_health():
    _, failure = _mounter_read('SELECT 1 AS ok')
    if failure is not None:
        return failure
    return jsonify(connected=True)


@app.route('/api/mounter/employees', methods=['GET'])
def mounter_employees():
    value, limit, failure = _mounter_search_parameters()
    if failure is not None:
        return failure
    if not value:
        return jsonify([])
    rows, failure = _mounter_read('''
        SELECT id::text AS id, full_name,
               COALESCE(job_title, '') AS job_title,
               COALESCE(image_url, '') AS image_url
        FROM employees
        WHERE full_name ILIKE %s
        ORDER BY full_name, id
        LIMIT %s
    ''', (_mounter_search_pattern(value), limit))
    return failure if failure is not None else jsonify(rows)


@app.route('/api/mounter/employees/selected', methods=['GET'])
def mounter_selected_employees():
    raw_ids = list(dict.fromkeys(request.args.getlist('ids')))
    if len(raw_ids) > 100 or any(re.fullmatch(r'[1-9][0-9]{0,9}', value) is None for value in raw_ids):
        return jsonify(detail='Некоректні ідентифікатори співробітників.'), 400
    ids = [int(value) for value in raw_ids]
    if any(value > 2147483647 for value in ids):
        return jsonify(detail='Некоректні ідентифікатори співробітників.'), 400
    if not ids:
        return jsonify([])
    rows, failure = _mounter_read('''
        SELECT id::text AS id, full_name,
               COALESCE(job_title, '') AS job_title,
               COALESCE(image_url, '') AS image_url
        FROM employees
        WHERE id = ANY(%s)
        ORDER BY full_name, id
    ''', (ids,))
    return failure if failure is not None else jsonify(rows)


@app.route('/api/mounter/clients', methods=['GET'])
def mounter_clients():
    value, limit, failure = _mounter_search_parameters()
    if failure is not None:
        return failure
    rows, failure = _mounter_read('''
        SELECT id::text AS id, name
        FROM clients
        WHERE name ILIKE %s
        ORDER BY name, id
        LIMIT %s
    ''', (_mounter_search_pattern(value), limit))
    return failure if failure is not None else jsonify(rows)
# --- End of Mounter Android API ---


"""

def integrate(path):
    original = path.read_bytes()
    routes = (
        b"/api/mounter/health", b"/api/mounter/employees",
        b"/api/mounter/employees/selected", b"/api/mounter/clients",
    )
    if any(route in original for route in routes):
        raise SystemExit("app.py already contains Mounter routes; review them before applying this integration.")
    for required in (b"get_db_connection", b"RealDictCursor", b"import psycopg2", b"import re"):
        if required not in original:
            raise SystemExit("Missing required existing import or database helper: " + required.decode())
    marker = re.search(rb"(?m)^if __name__\s*==\s*['\"]__main__['\"]\s*:", original)
    if marker is None:
        raise SystemExit("Could not find the server startup block in app.py.")
    newline = "\r\n" if b"\r\n" in original else "\n"
    inserted = MOUNTER_ROUTES.replace("\n", newline).encode("utf-8")
    updated = original[:marker.start()] + inserted + original[marker.start():]
    backup = path.with_name(path.name + ".before-mounter.bak")
    if backup.exists():
        raise SystemExit("Backup already exists; keep or rename it before applying the integration.")
    shutil.copy2(path, backup)
    temporary = path.with_name(path.name + ".mounter.tmp")
    try:
        temporary.write_bytes(updated)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    print("Updated:", path)
    print("Backup:", backup)
    print("Restart the Flask server to load /api/mounter routes.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="Path to the existing Flask app.py")
    integrate(parser.parse_args().app)
