import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

module_path = Path(__file__).resolve().parents[1] / "odify_link_candidates.py"
spec = importlib.util.spec_from_file_location("odify_link_candidates", module_path)
links = importlib.util.module_from_spec(spec)
spec.loader.exec_module(links)


class LinkBatchTests(unittest.TestCase):
    def test_filter_keeps_unverified_only(self):
        rows = [
            {"Track ID": "ODF000001", "연도": "2026", "아티스트": "가수",
             "곡명": "제목", "링크검증": "검증완료",
             "YouTube 오디오 URL": "https://youtube.com/watch?v=abcdefghijk"},
            {"Track ID": "ODF000002", "연도": "2026", "아티스트": "米津玄師",
             "곡명": "Lemon", "링크검증": "미검증", "YouTube 오디오 URL": ""},
            {"Track ID": "ODF000003", "연도": "2025", "아티스트": "아이유",
             "곡명": "밤편지", "링크검증": "대기", "YouTube 오디오 URL": ""}
        ]
        self.assertEqual(["ODF000002"], [x["Track ID"] for x in links.pending(rows, "2026")])
        self.assertEqual(2, len(links.pending(rows)))

    def test_existing_video_id_can_be_verified_without_search(self):
        self.assertEqual("abcdefghijk", links.existing_video_id(
            "https://www.youtube.com/watch?v=abcdefghijk"))
        self.assertEqual("abcdefghijk", links.existing_video_id(
            "https://youtu.be/abcdefghijk"))
        self.assertIsNone(links.existing_video_id(
            "https://untrusted.example/watch?v=abcdefghijk"))
        self.assertIsNone(links.existing_video_id(
            "http://www.youtube.com/watch?v=abcdefghijk"))

    def test_artist_and_title_must_match_before_priority(self):
        target = {"아티스트": "아이유", "곡명": "밤편지"}
        valid = {"title": "아이유 - 밤편지 (Official Audio)", "channelTitle": "IU - Topic"}
        other = {"title": "아이유 - 좋은 날 (Official Audio)", "channelTitle": "IU - Topic"}
        details = {"snippet": {"description": "Provided to YouTube by Distribution"}}
        self.assertEqual("우선검토", links.evidence_rank(target, valid, details)[0])
        self.assertEqual("불일치가능", links.evidence_rank(target, other, details)[0])
        self.assertEqual("아니오", "아니오")  # even strong candidates are NOT auto-verified

    def test_dry_run_never_requires_an_api_key(self):
        with tempfile.TemporaryDirectory() as td:
            master = Path(td) / "master.csv"
            with master.open("w", encoding="utf-8-sig", newline="") as fp:
                wr = csv.DictWriter(fp, fieldnames=[
                    "Track ID", "연도", "아티스트", "곡명",
                    "YouTube 오디오 URL", "링크검증"])
                wr.writeheader()
                wr.writerow({"Track ID": "ODF000001", "연도": "2026",
                             "아티스트": "back number", "곡명": "花束",
                             "YouTube 오디오 URL": "", "링크검증": "대기"})
            result = links.run(master, Path(td)/"candidates.csv", Path(td)/"cache.json",
                               "2026", 25, 4, "", True)
            self.assertEqual(1, result["new_searches"])
            self.assertEqual(1, result["planned_search_calls"])
            self.assertFalse((Path(td)/"candidates.csv").exists())


if __name__ == "__main__":
    unittest.main()
