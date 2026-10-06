import os

from flask import Flask
from directory_api import directory_api

app = Flask(__name__)
app.register_blueprint(directory_api)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "5001")))
