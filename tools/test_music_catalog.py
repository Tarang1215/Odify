"""Offline unit tests: no Google account, YouTube, yt-dlp or network access."""
from __future__ import annotations
import csv
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from music_catalog import connection, ingest, validate_video_url, export_status


class MusicCatalogTests(unittest.TestCase):
    def test_verified_youtube_urls_only(self):
        self.assertTrue(validate_video_url("https://music.youtube.com/watch?v=ABCDEFGHI_0"))
        self.assertTrue(validate_video_url("https://youtu.be/ABCDEFGHI_0"))
        self.assertFalse(validate_video_url("https://youtube.com.evil.example/watch?v=x"))
        self.assertFalse(validate_video_url("https://www.youtube.com/results?search_query=beatles"))
        self.assertFalse(validate_video_url("https://www.youtube.com/playlist?list=PL123"))
        self.assertFalse(validate_video_url("https://youtube.com/watch?v=too-short"))
        self.assertFalse(validate_video_url("file:///etc/passwd"))
        self.assertFalse(validate_video_url("javascript:alert(1)"))

    def test_ingest_is_idempotent_and_records_exact_duplicate_candidates(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / "tracks.csv"
            headers = ["Track ID", "연도", "국가", "장르", "아티스트",
                       "곡명", "앨범", "YouTube 오디오 URL", "링크검증",
                       "다운로드", "커버", "가사"]
            with path.open("w", encoding="utf-8-sig", newline="") as fp:
                writer = csv.writer(fp)
                writer.writerow(headers)
                writer.writerow(["ODF000001", 2026, "한국", "인디", "한로로",
                                 "사랑하게 될 거야", "", "", "대기",
                                 "대기", "대기", "대기"])
                writer.writerow(["ODF000002", 2025, "한국", "인디", "한로로",
                                 "사랑하게 될 거야", "", "", "대기",
                                 "대기", "대기", "대기"])
                writer.writerow(["ODF000003", 2026, "한국", "인디", "다른 가수",
                                 "사랑하게 될 거야", "", "", "대기",
                                 "대기", "대기", "대기"])
            db = connection(root / "state.sqlite3")
            self.assertEqual(ingest(db, path), (3, 1))
            self.assertEqual(ingest(db, path), (3, 1))
            self.assertEqual(db.execute("SELECT COUNT(*) FROM tracks").fetchone()[0], 3)
            self.assertEqual(db.execute(
                "SELECT canonical_track_id FROM duplicate_candidates "
                "WHERE track_id='ODF000002'").fetchone()[0], "ODF000001")
            export_status(db, root / "status.csv")
            self.assertIn("ODF000003", (root / "status.csv").read_text(encoding="utf-8-sig"))
            db.close()


if __name__ == "__main__":
    unittest.main()
