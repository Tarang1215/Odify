from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import run_2026


class PackagedWorkerTests(unittest.TestCase):
    def test_real_packaged_worker_ingests_snapshot_and_preserves_resume_state(self):
        worker = run_2026.get_worker()
        with TemporaryDirectory() as folder:
            database = worker.connection(Path(folder) / 'catalog.sqlite3')
            try:
                worker.ingest(database, run_2026.DATA, default_year='2026')
                self.assertEqual(database.execute('SELECT COUNT(*) FROM tracks').fetchone()[0], 152)
                database.execute("UPDATE tracks SET status='downloaded' WHERE id='ODF000001'")
                database.commit()
                worker.ingest(database, run_2026.DATA, default_year='2026')
                self.assertEqual(database.execute(
                    "SELECT status FROM tracks WHERE id='ODF000001'").fetchone()[0], 'downloaded')
            finally:
                database.close()


if __name__ == '__main__':
    unittest.main()
