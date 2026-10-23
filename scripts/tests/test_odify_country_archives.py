import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

p=Path(__file__).resolve().parents[1]/"odify_country_archives.py"
spec=importlib.util.spec_from_file_location("odify_country_archives",p)
c=importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)

class ArchiveCountryTests(unittest.TestCase):
    def test_kr_metadata_ignores_lyrics_and_dedupes_months(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            src=root/"melon"/"monthly-chart"/"melon-2003"/"melon-2003-01"
            src.mkdir(parents=True)
            body={"info":[{"year":2003,"month":1,"rank":1},""],
                "song_id":"song123","song_name":"안녕",
                "artist":"가수","album":"음반","genre":"인디",
                "lyrics":{"lines":["Do not save me","private lyrics"]}}
            (src/"song.json").write_text(json.dumps(body,ensure_ascii=False),encoding="utf-8")
            con=c.catalog.connect(root/"out.sqlite3")
            outcome=c.korean_monthly(con,root)
            self.assertEqual(1,outcome["monthly_charts"])
            self.assertEqual(1,con.execute("SELECT COUNT(*) FROM recording").fetchone()[0])
            self.assertEqual("인디",con.execute("SELECT genre FROM recording_genre").fetchone()[0])
            self.assertEqual("monthly-official-source",con.execute(
                "SELECT category FROM chart_edition").fetchone()[0])
            self.assertNotIn("Do not save me",str(con.iterdump()))
            con.close()

    def test_japan_only_2008_plus_weekly_and_monthly(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            for y in [2007,2008,2009]:
                target=root/"billboard_charts"/str(y)
                target.mkdir(parents=True)
                (target/f"{y}_01_12.csv").write_text(
                    "rank,song_title,artist_name,score\n"
                    "1,そばにいるね,青山 テルマ,100\n"
                    "2,さくら,Artist,99\n",encoding="utf-8-sig")
            con=c.catalog.connect(root/"out.sqlite3")
            result=c.billboard_japan(con,root)
            self.assertEqual(2,result["weekly_charts"])
            self.assertEqual(2,result["monthly_derived"])
            self.assertEqual(2,con.execute("SELECT COUNT(*) FROM recording").fetchone()[0])
            self.assertEqual(4,con.execute("SELECT COUNT(*) FROM chart_edition").fetchone()[0])
            con.close()

if __name__=="__main__":
    unittest.main()
