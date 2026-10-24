import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

file = Path(__file__).resolve().parents[1] / "odify_catalog_pipeline.py"
spec = importlib.util.spec_from_file_location("odify_catalog_pipeline", file)
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class ChartCatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.con = catalog.connect(Path(self.temp.name) / "catalog.sqlite3")

    def tearDown(self):
        self.con.close()
        self.temp.cleanup()

    def test_coverage_japan_starts_2003_with_archive_opt_in_not_override(self):
        self.assertFalse(catalog.eligible_history("jp", "2002-12-30"))
        self.assertFalse(catalog.eligible_history("jp", "2002-12-30", True))
        self.assertTrue(catalog.eligible_history("jp", "2003-01-01"))
        self.assertTrue(catalog.eligible_history("jp", "2026-10-01"))
        self.assertFalse(catalog.eligible_history("us", "1999-12-31"))
        self.assertTrue(catalog.eligible_history("us", "1999-12-31", True))
        self.assertFalse(catalog.eligible_history("kr", "1988-01-01"))
        self.assertTrue(catalog.eligible_history("kr", "1988-01-01", True))
        with self.assertRaises(ValueError):
            catalog.country_code("cn")

    def test_monthly_is_derived_separate_from_weekly_and_country_dedupe(self):
        catalog.snapshot(self.con, "billboard-hot-100-community-archive", "us",
                         "2003-06-07", [
                            {"rank": 1, "title": "A song", "artist": "Singer"},
                            {"rank": 2, "title": "B song", "artist": "Singer"}])
        catalog.snapshot(self.con, "billboard-hot-100-community-archive", "us",
                         "2003-06-14", [
                            {"rank": 1, "title": "A song", "artist": "Singer"},
                            {"rank": 3, "title": "B song", "artist": "Singer"}])
        catalog.snapshot(self.con, "test-japan", "jp", "2003-06-07",
                         [{"rank": 1, "title": "A song", "artist": "Singer"}])
        monthly = catalog.rebuild_monthly(self.con,
                                           "billboard-hot-100-community-archive",
                                           "us", "2003-06")
        self.assertEqual(2, monthly)
        self.assertEqual(2, self.con.execute("SELECT count(*) FROM recording").fetchone()[0])
        monthly_chart = self.con.execute(
            "SELECT category,provider FROM chart_edition WHERE category='monthly-derived'").fetchone()
        self.assertEqual("monthly-derived", monthly_chart["category"])
        positions = self.con.execute("""
          SELECT r.title,c.rank FROM chart_entry c
          JOIN chart_edition e ON e.id=c.edition_id
          JOIN recording r ON r.id=c.recording_id
          WHERE e.category='monthly-derived' ORDER BY c.rank
        """).fetchall()
        self.assertEqual(["A song", "B song"], [x["title"] for x in positions])
        self.assertEqual(2, catalog.rebuild_monthly(self.con,
                             "billboard-hot-100-community-archive", "us", "2003-06"))
        self.assertEqual(2,self.con.execute(
            "SELECT count(*) FROM chart_entry c JOIN chart_edition e"
            " ON e.id=c.edition_id WHERE e.category='monthly-derived'").fetchone()[0])

    def test_japan_2002_csv_rows_skipped_even_with_archive(self):
        file = Path(self.temp.name) / "history.csv"
        with file.open("w",newline="",encoding="utf-8") as fp:
            writer=csv.DictWriter(fp,fieldnames=[
                "source","country","chart_date","rank","artist","title","genre","category"])
            writer.writeheader()
            writer.writerows([
                {"source":"history","country":"jp","chart_date":"2002-06-01",
                 "rank":"1","artist":"Artist","title":"Old"},
                {"source":"history","country":"jp","chart_date":"2003-06-01",
                 "rank":"1","artist":"Artist","title":"Modern",
                 "category":"indie","genre":"J-rock"},
                {"source":"history","country":"us","chart_date":"1997-06-01",
                 "rank":"1","artist":"Artist","title":"US 1997"}
            ])
        self.assertEqual((1,3), catalog.import_csv(self.con,file,False))
        self.assertEqual((2,3), catalog.import_csv(self.con,file,True))
        countries = self.con.execute("SELECT country,as_of FROM chart_edition"
                                     " ORDER BY as_of").fetchall()
        self.assertEqual([("us","1997-06-01"),("jp","2003-06-01")],
                         [(r["country"],r["as_of"]) for r in countries])
        genre = self.con.execute("SELECT genre FROM recording_genre").fetchone()
        self.assertEqual("J-rock", genre["genre"])

    def test_incremental_us_archive_resumes_without_overwrite(self):
        def mock(url,timeout=20):
            if "valid_dates.json" in url:
                return ["1999-12-25","2000-01-01","2000-01-08","2000-01-15"]
            date=url.split("/")[-1].removesuffix(".json")
            return {"date":date,"data":[
                {"song":"Shared track","artist":"Singer","this_week":1},
                {"song":"Other track","artist":"Singer","this_week":2}]}
        with patch.object(catalog,"http_json",side_effect=mock):
            first=catalog.backfill_us(self.con,limit=2)
            second=catalog.backfill_us(self.con,limit=2)
            third=catalog.backfill_us(self.con,limit=2)
        self.assertEqual(2,first["weeks_imported"])
        self.assertEqual(1,second["weeks_imported"])
        self.assertEqual(0,third["weeks_imported"])
        self.assertEqual(2,self.con.execute("SELECT count(*) FROM recording").fetchone()[0])
        self.assertEqual(3,self.con.execute("SELECT count(*) FROM chart_edition"
                                          " WHERE category='songs'").fetchone()[0])
        self.assertGreaterEqual(self.con.execute("SELECT count(*) FROM chart_edition"
                                          " WHERE category='monthly-derived'").fetchone()[0],1)

    def test_historic_album_queue_uses_itunes_search_and_tracklist(self):
        catalog.snapshot(self.con,"historic","us","2003-01-01",
                         [{"rank":1,"artist":"Artist","title":"Song A"}])
        def mock(url,timeout=20):
            if "/search?" in url:
                return {"results":[{"wrapperType":"track","trackId":1234,
                      "trackName":"Song A","artistName":"Artist",
                      "collectionId":999,"collectionName":"Album"}]}
            if "id=999" in url:
                return {"results":[
                  {"wrapperType":"collection","collectionId":999,
                    "collectionName":"Album","artistName":"Artist",
                    "releaseDate":"2003-01-01T00:00:00Z"},
                  {"wrapperType":"track","kind":"song","trackId":1234,
                    "trackName":"Song A","artistName":"Artist","trackNumber":1,
                    "discNumber":1,"collectionName":"Album"},
                  {"wrapperType":"track","kind":"song","trackId":1235,
                    "trackName":"Song B","artistName":"Artist","trackNumber":2,
                    "discNumber":1,"collectionName":"Album"}]}
            raise AssertionError("unexpected API "+url)
        with patch.object(catalog,"http_json",side_effect=mock):
            albums,calls=catalog.expand_apple(self.con,limit=2,delay=0)
        self.assertEqual((1,2),(albums,calls))
        self.assertEqual(1,self.con.execute("SELECT count(*) FROM album").fetchone()[0])
        self.assertEqual(2,self.con.execute("SELECT count(*) FROM recording").fetchone()[0])
        self.assertEqual(2,self.con.execute("SELECT count(*) FROM album_track").fetchone()[0])
        self.assertEqual("done",self.con.execute(
            "SELECT status FROM release_catalog_queue").fetchone()[0])


if __name__=="__main__":
    unittest.main()
