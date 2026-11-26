"""Exercise a disposable paired gateway, including racing receivers and lost acknowledgements."""
import base64
import http.client
import json
import os
import sqlite3
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import migration_relay as relay
import mobile_gateway as gateway


class MigrationChecks(unittest.TestCase):
    def setUp(self):
        folder = TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        state = patch.object(gateway, "STATE", Path(folder.name))
        state.start()
        self.addCleanup(state.stop)
        self.gateway = gateway.MobileServer(("127.0.0.1", 0), gateway.Mobile)
        self.gateway.upstream = 1
        thread = threading.Thread(target=self.gateway.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.gateway.server_close)
        self.addCleanup(self.gateway.shutdown)
        self.sender, self.first, self.second = [self.pair() for _ in range(3)]
        self.box = base64.b64encode(os.urandom(100)).decode("ascii")

    def post(self, endpoint, data, device=""):
        with closing(http.client.HTTPConnection(*self.gateway.server_address, timeout=4)) as conn:
            headers = {"Content-Type": "application/json"}
            if device:
                headers["Authorization"] = "Bearer " + device
            conn.request("POST", endpoint, json.dumps(data), headers)
            result = conn.getresponse()
            return result.status, json.load(result)

    def pair(self):
        status, data = self.post("/pair", {"code":gateway.pairing_code()})
        self.assertEqual(status, 200)
        return data["token"]

    def create(self):
        status, result = self.post("/api/migration/create", {"box":self.box}, self.sender)
        self.assertEqual(status, 201)
        self.assertEqual(result["ttl"], 300)
        return {"ticket":result["ticket"]}

    def claim(self, ticket, device):
        return self.post("/api/migration/claim", ticket, device)

    def ack(self, ticket, device):
        return self.post("/api/migration/ack", ticket, device)

    def test_retry_and_single_receiver(self):
        ticket = self.create()
        self.assertEqual(self.claim(ticket, self.sender)[0], 409)
        self.assertEqual(self.ack(ticket, self.second)[0], 410)
        with ThreadPoolExecutor(max_workers=2) as pool:
            answers = list(pool.map(lambda dev:self.claim(ticket,dev), (self.first,self.second)))
        self.assertEqual(sorted(a[0] for a in answers), [200,410])
        winner, other = (self.first,self.second) if answers[0][0] == 200 else (self.second,self.first)
        self.assertEqual(self.claim(ticket,winner), (200,{"box":self.box}))
        self.assertEqual(self.ack(ticket,other)[0], 410)
        self.assertEqual(self.ack(ticket,winner), (200,{"done":True}))
        self.assertEqual(self.ack(ticket,winner), (200,{"done":True}))
        self.assertEqual(self.claim(ticket,winner), (200,{"done":True}))
        self.assertEqual(self.claim(ticket,other)[0], 410)
        with closing(gateway.connect()) as db:
            self.assertIsNone(db.execute("SELECT box FROM migrations").fetchone()[0])

    def test_pairing_bounds_and_expiry(self):
        for path in relay.ROUTES:
            self.assertEqual(self.post(path,{})[0],401)
            self.assertEqual(self.post(path,{},"unpaired")[0],401)
        self.assertEqual(self.post("/api/migration/create",{"box":"A"*16},self.sender)[0],413)
        self.assertEqual(self.post("/api/migration/create",{"box":"非法密文"},self.sender)[0],400)
        with closing(sqlite3.connect(":memory:")) as db:
            relay.setup(db)
            data = base64.b64encode(bytes(relay.MAX_BOX+1)).decode()
            self.assertEqual(relay.handle(db,"/api/migration/create",{"box":data},"source")[0],413)
        expired = self.create()
        with closing(gateway.connect()) as db, db:
            db.execute("UPDATE migrations SET expires=0")
        self.assertEqual(self.claim(expired,self.first)[0],410)
        with closing(gateway.connect()) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM migrations").fetchone()[0],0)

    def test_replacement_and_workstation_scope(self):
        old = self.create()
        current = self.create()
        self.assertEqual(self.claim(old,self.first)[0],410)
        self.assertEqual(self.claim(current,self.first)[0],200)
        self.create()
        self.assertEqual(self.claim(current,self.first)[0],200)
        with closing(sqlite3.connect(":memory:")) as other:
            relay.setup(other)
            self.assertEqual(relay.handle(other,"/api/migration/claim",current,self.first)[0],410)


if __name__ == "__main__":
    unittest.main(verbosity=2)
