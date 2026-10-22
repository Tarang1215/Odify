"""Pure-stdlib checks for conservative Odify MASTER tag matching."""
import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "odify_retag_library.py"
spec = importlib.util.spec_from_file_location("odify_retag_library", MODULE)
retag = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retag)


class OdifyRetagTests(unittest.TestCase):
    def test_track_id_must_be_bounded(self):
        self.assertEqual(retag.track_code("ODF000001.mp3"), "ODF000001")
        self.assertEqual(retag.track_code("odf000001 - Artist - Title.m4a"), "ODF000001")
        self.assertIsNone(retag.track_code("ODF0000010.mp3"))
        self.assertIsNone(retag.track_code("wrong_ODF000001.mp3"))
        self.assertIsNone(retag.track_code("Artist - Title.mp3"))

    def test_conflicting_master_rows_are_excluded(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "master.csv"
            with source.open("w", encoding="utf-8-sig", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=["Track ID", "곡명", "아티스트", "앨범"])
                writer.writeheader()
                writer.writerow({"Track ID": "ODF000001", "곡명": "怪獣の花唄",
                                 "아티스트": "Vaundy", "앨범": ""})
                writer.writerow({"Track ID": "ODF000002", "곡명": "A",
                                 "아티스트": "Artist", "앨범": "One"})
                writer.writerow({"Track ID": "ODF000002", "곡명": "B",
                                 "아티스트": "Artist", "앨범": "One"})
            matched, conflicts = retag.canonical_rows(source)
            self.assertEqual(matched["ODF000001"], ("怪獣の花唄", "Vaundy", ""))
            self.assertNotIn("ODF000002", matched)
            self.assertEqual(conflicts, {"ODF000002"})

    def test_dry_run_leaves_audio_unchanged(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            music = root / "music"
            music.mkdir()
            fake = music / "ODF000003.mp3"
            fake.write_bytes(b"unchanged")
            master = root / "master.csv"
            master.write_text("Track ID,곡명,아티스트,앨범\nODF000003,REDRED,CORTIS,\n",
                              encoding="utf-8")
            with patch.object(retag, "read_tags", return_value=("Red Red", "WRONG", "")):
                self.assertEqual(retag.run(master, music, False, None), 0)
            self.assertEqual(fake.read_bytes(), b"unchanged")


if __name__ == "__main__":
    unittest.main()
