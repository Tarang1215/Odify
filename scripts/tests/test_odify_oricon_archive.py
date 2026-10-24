import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

path=Path(__file__).resolve().parents[1]/"odify_oricon_archive.py"
spec=importlib.util.spec_from_file_location("oricon",path)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class JapanOriconTests(unittest.TestCase):
    def test_2003_cutoff_and_deduped_weekly_monthly(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"oricon.csv"
            with p.open("w",newline="",encoding="utf-8") as fp:
                w=csv.DictWriter(fp,fieldnames=["chart_date","chart_year","rank","title","artist","movement","weekly_sales","total_sales","source_year"])
                w.writeheader()
                for date,rank,name in [
                    ("2002-12-30",1,"Not allowed"),
                    ("2003-01-04",1,"First single"),
                    ("2003-01-11",1,"First single"),
                    ("2003-01-11",2,"Second single"),
                    ("2003-01-11",2,"Second single"),
                    ("2003-01-18",4,"!!!"), # invalid normalized title
                ]:
                    w.writerow({"chart_date":date,"chart_year":date[:4],
                        "rank":rank,"title":name,"artist":"Singer"})
            con=m.catalog.connect(Path(d)/"odify.db")
            a=m.ingest(con,p)
            self.assertEqual(2,a["weeks_new"])
            self.assertEqual(1,a["months_recomputed"])
            self.assertEqual(2,con.execute("SELECT COUNT(*) FROM recording").fetchone()[0])
            self.assertEqual(3,con.execute("SELECT COUNT(*) FROM chart_edition").fetchone()[0])
            self.assertEqual(0,con.execute("SELECT COUNT(*) FROM chart_edition WHERE as_of<'2003-01-01'").fetchone()[0])
            self.assertEqual("ok",con.execute("PRAGMA integrity_check").fetchone()[0])
            b=m.ingest(con,p)
            self.assertEqual(0,b["weeks_new"])
            con.close()

if __name__=="__main__":
    unittest.main()
