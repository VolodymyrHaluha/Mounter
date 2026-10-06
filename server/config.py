import os

from dotenv import load_dotenv

load_dotenv()

DB_CONFIG = {
    "dbname": os.getenv("DB_NAME", "frop_testing"),
    "user": os.getenv("DB_USER", "Administrator"),
    "password": os.getenv("DB_PASSWORD", ""),
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", "5432")),
    "connect_timeout": 5,
    "options": "-c statement_timeout=5000 -c default_transaction_read_only=on",
}
DB_SCHEMA = os.getenv("DB_SCHEMA", "public")
EMPLOYEE_ID_COLUMN = os.getenv("EMPLOYEE_ID_COLUMN", "id")
CLIENT_ID_COLUMN = os.getenv("CLIENT_ID_COLUMN", "id")
