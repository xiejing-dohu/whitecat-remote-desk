"""Five-minute, authenticated, one-receiver relay for opaque Whitecat migration envelopes."""
import base64
import binascii
import hashlib
import re
import secrets
import time

TTL = 300
MAX_BOX = 4 * 1024 * 1024
MAX_REQUEST = (MAX_BOX + 2) // 3 * 4 + 128
ROUTES = {"/api/migration/create", "/api/migration/claim", "/api/migration/ack"}
INVALID = (410, {"error": "迁移码已失效，请在旧设备重新生成"})


def setup(db):
    db.execute("""CREATE TABLE IF NOT EXISTS migrations (
        id TEXT PRIMARY KEY, sender TEXT NOT NULL, receiver TEXT, expires REAL NOT NULL, box BLOB
    )""")


def prune(db, now):
    db.execute("DELETE FROM migrations WHERE expires <= ?", (now,))


def handle(db, path, body, device, now=None):
    now = time.time() if now is None else now
    if not isinstance(body, dict):
        return 400, {"error": "迁移请求格式有误"}
    with db:
        db.execute("BEGIN IMMEDIATE")
        prune(db, now)
        if path == "/api/migration/create":
            encoded = body.get("box")
            if not isinstance(encoded, str) or len(encoded) > MAX_REQUEST - 100:
                return 413, {"error": "迁移数据超过大小限制"}
            try:
                box = base64.b64decode(encoded, validate=True)
            except (ValueError, binascii.Error):
                return 400, {"error": "迁移数据格式有误"}
            if not 28 <= len(box) <= MAX_BOX:
                return 413, {"error": "迁移数据长度有误"}
            total, count = db.execute("""SELECT coalesce(sum(length(box)), 0), count(*) FROM migrations
                WHERE sender != ? OR receiver IS NOT NULL""", (device,)).fetchone()
            if total + len(box) > 64 * 1024 * 1024 or count >= 512:
                return 429, {"error": "迁移服务繁忙，请稍后重试"}
            # Replacing an unscanned code does not interrupt a different device already importing.
            db.execute("DELETE FROM migrations WHERE sender=? AND receiver IS NULL", (device,))
            token = secrets.token_urlsafe(32)
            key = hashlib.sha256(token.encode("ascii")).hexdigest()
            db.execute("INSERT INTO migrations VALUES(?,?,NULL,?,?)", (key, device, now + TTL, box))
            return 201, {"ticket": token, "ttl": TTL}
        token = body.get("ticket")
        if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
            return INVALID
        key = hashlib.sha256(token.encode("ascii")).hexdigest()
        row = db.execute("SELECT sender,receiver,box FROM migrations WHERE id=?", (key,)).fetchone()
        if not row:
            return INVALID
        sender, receiver, box = row
        if sender == device:
            return 409, {"error": "请在另一台已配对的设备上导入"}
        if receiver and receiver != device:
            return INVALID
        if path == "/api/migration/ack":
            if receiver != device:
                return INVALID
            db.execute("UPDATE migrations SET box=NULL WHERE id=?", (key,))
            return 200, {"done": True}
        if path != "/api/migration/claim":
            return 404, {"error": "路径不存在"}
        if box is None:
            return 200, {"done": True}
        db.execute("UPDATE migrations SET receiver=? WHERE id=?", (device, key))
        return 200, {"box": base64.b64encode(box).decode("ascii")}
