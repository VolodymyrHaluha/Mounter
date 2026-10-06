import logging
from contextlib import contextmanager

import psycopg
from flask import Blueprint, jsonify, request
from psycopg import sql
from psycopg.rows import dict_row

from config import CLIENT_ID_COLUMN, DB_CONFIG, DB_SCHEMA, EMPLOYEE_ID_COLUMN

# Register this Blueprint in the existing Flask application running on port 5001.
directory_api = Blueprint("mounter_directory", __name__, url_prefix="/api/mounter")
logger = logging.getLogger("mounter")


class DirectoryError(Exception):
    def __init__(self, message, status=503):
        self.message = message
        self.status = status


@directory_api.errorhandler(DirectoryError)
def directory_error(error):
    return jsonify(detail=error.message), error.status


@contextmanager
def database():
    try:
        with psycopg.connect(**DB_CONFIG, row_factory=dict_row) as connection:
            yield connection
    except psycopg.Error as error:
        logger.warning("PostgreSQL request failed: %s", type(error).__name__)
        if isinstance(error, (psycopg.OperationalError, psycopg.InterfaceError)):
            message = "Немає підключення до PostgreSQL. Перевірте сервер і DB_CONFIG."
        else:
            message = "Помилка схеми PostgreSQL. Перевірте таблиці та назви стовпців у конфігурації API."
        raise DirectoryError(message) from error


def search_parameters():
    value = request.args.get("q", "").strip()
    try:
        limit = int(request.args.get("limit", "30"))
    except ValueError as error:
        raise DirectoryError("Некоректний ліміт пошуку.", 400) from error
    if len(value) > 200 or not 1 <= limit <= 100:
        raise DirectoryError("Некоректні параметри пошуку.", 400)
    pattern = "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    return value, pattern, limit


def employees_query():
    return sql.SQL("SELECT {id}::text AS id, full_name, job_title, image_url FROM {table}").format(
        id=sql.Identifier(EMPLOYEE_ID_COLUMN), table=sql.Identifier(DB_SCHEMA, "employees")
    )


@directory_api.get("/health")
def health():
    with database() as connection:
        connection.execute("SELECT 1")
    return jsonify(connected=True)


@directory_api.get("/employees")
def employees():
    value, pattern, limit = search_parameters()
    if not value:
        return jsonify([])
    query = employees_query() + sql.SQL(" WHERE full_name ILIKE %s ORDER BY full_name, id LIMIT %s")
    with database() as connection:
        return jsonify(connection.execute(query, (pattern, limit)).fetchall())


@directory_api.get("/employees/selected")
def selected_employees():
    ids = list(dict.fromkeys(request.args.getlist("ids")))
    if len(ids) > 100 or any(not value.isdecimal() or int(value) < 1 for value in ids):
        raise DirectoryError("Некоректні ідентифікатори співробітників.", 400)
    if not ids:
        return jsonify([])
    query = employees_query() + sql.SQL(" WHERE {id}::text = ANY(%s) ORDER BY full_name, id").format(
        id=sql.Identifier(EMPLOYEE_ID_COLUMN)
    )
    with database() as connection:
        return jsonify(connection.execute(query, (ids,)).fetchall())


@directory_api.get("/clients")
def clients():
    _, pattern, limit = search_parameters()
    query = sql.SQL("SELECT {id}::text AS id, name FROM {table} WHERE name ILIKE %s ORDER BY name, id LIMIT %s").format(
        id=sql.Identifier(CLIENT_ID_COLUMN), table=sql.Identifier(DB_SCHEMA, "clients")
    )
    with database() as connection:
        return jsonify(connection.execute(query, (pattern, limit)).fetchall())
