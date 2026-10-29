"""Execute the production Java history query builder against a real SQLite database."""
import base64
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HistorySqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        build = ROOT / "build" / "ci-history"
        build.mkdir(parents=True, exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix="classes-", dir=build)
        sources = ROOT / "blocklist" / "src" / "main" / "java" / "tracewall" / "data"
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-d", cls.temp.name,
                        str(sources / "HistoryFilter.java"), str(sources / "HistoryQuery.java"),
                        str(Path(__file__).with_name("HistorySqlFixture.java"))], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("""CREATE TABLE queries (
            id INTEGER PRIMARY KEY AUTOINCREMENT, timeMs INTEGER NOT NULL, host TEXT NOT NULL,
            type INTEGER NOT NULL, blocked INTEGER NOT NULL, uid INTEGER NOT NULL,
            policy TEXT NOT NULL, latencyMs INTEGER NOT NULL, resolver TEXT NOT NULL)""")

    def add(self, host="example.com", time=100, uid=10001, blocked=0, latency=10):
        return self.db.execute("INSERT INTO queries(timeMs,host,type,blocked,uid,policy,latencyMs,resolver) "
                               "VALUES(?,?,1,?,?,\"default\",?,\"test\")", (time, host, blocked, uid, latency)).lastrowid

    def query(self, mode="page", domain="", verdict="ALL", start=0, end=10000, uids="*",
              snapshot=9999, anchor=None, direction="OLDER", limit=100):
        time, row_id = anchor or (0, 0)
        result = subprocess.run(["java", "-cp", self.temp.name, "HistorySqlFixture", mode, domain, verdict,
                                 str(start), str(end), uids, str(snapshot), str(time), str(row_id), direction,
                                 str(limit)], check=True, capture_output=True, text=True)
        lines = result.stdout.splitlines()
        sql = base64.b64decode(lines[0]).decode("utf-8")
        args = [int(line[2:]) if line.startswith("N:") else base64.b64decode(line[2:]).decode("utf-8")
                for line in lines[1:]]
        return self.db.execute(sql, args).fetchall()

    def ids(self, **options):
        return [row[0] for row in self.query(**options)]

    def test_literal_domain_matching_binds_sql_and_escapes_like(self):
        for host in ["sale%now.example", "saleXnow.example", "underscore_.example", "underscoreZ.example",
                     "path\\name.example", "pathname.example", "' OR 1=1 --.example"]:
            self.add(host)
        self.assertEqual([1], self.ids(domain="%"))
        self.assertEqual([3], self.ids(domain="_"))
        self.assertEqual([5], self.ids(domain="\\"))
        self.assertEqual([7], self.ids(domain="' OR 1=1 --"))

    def test_tied_timestamps_and_backdated_inserts_do_not_shift_frozen_pages(self):
        for _ in range(5):
            self.add(time=100)
        self.assertEqual([5, 4], self.ids(snapshot=5, limit=2))
        self.add(time=50)
        self.add(time=100)
        self.assertEqual([3, 2], self.ids(snapshot=5, anchor=(100, 4), limit=2))
        self.assertEqual([1], self.ids(snapshot=5, anchor=(100, 2), limit=2))

    def test_newer_keysets_return_the_nearest_page_and_boundary_existence(self):
        for _ in range(6):
            self.add(time=100)
        self.assertEqual([3, 4], self.ids(snapshot=6, anchor=(100, 2), direction="NEWER", limit=2))
        self.assertEqual(1, len(self.query(mode="exists", snapshot=6, anchor=(100, 4), direction="NEWER")))
        self.assertEqual([], self.query(mode="exists", snapshot=6, anchor=(100, 6), direction="NEWER"))

    def test_statistics_cover_the_entire_selection_and_date_end_is_exclusive(self):
        self.add(time=100, blocked=1, latency=10)
        self.add(time=200, blocked=0, latency=20)
        self.add(time=300, blocked=1, latency=300)
        self.assertEqual([(2, 1, 15.0)], self.query(mode="stats", start=100, end=300))
        self.assertEqual([2], self.ids(start=100, end=300, limit=1))
        self.assertEqual([1], self.ids(start=100, end=300, verdict="BLOCKED"))

    def test_empty_unknown_and_large_app_selections(self):
        self.add(uid=10000)
        self.add(uid=-1)
        self.assertEqual([], self.ids(uids="none"))
        self.assertEqual([2], self.ids(uids="-1"))
        self.assertEqual([2, 1], self.ids())
        self.assertEqual([1], self.ids(uids=",".join(str(uid) for uid in range(10000, 10800))))

    def test_retention_deletion_does_not_skip_surviving_keyset_rows(self):
        for _ in range(5):
            self.add(time=100)
        self.db.execute("DELETE FROM queries WHERE id IN (4,5)")
        self.assertEqual([3, 2], self.ids(snapshot=5, anchor=(100, 4), limit=2))
        self.assertEqual([(3, 0, 10.0)], self.query(mode="stats", snapshot=5))


if __name__ == "__main__":
    unittest.main()
