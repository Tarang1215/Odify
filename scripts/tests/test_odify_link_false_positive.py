import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from odify_link_consensus import eligible

class LinkFalsePositiveTests(unittest.TestCase):
    def candidate(self,title,channel,seconds=180):
        return {"title":title,"channel":channel,"seconds":seconds,"url":"https://www.youtube.com/watch?v=ABCDEFGHIJK","views":100,"description":""}

    def test_original_song_is_not_karaoke_channel(self):
        song={"아티스트":"Agust D","곡명":"해금","음원 버전":""}
        self.assertFalse(eligible(song,self.candidate("Haegeum (해금)","ZZang KARAOKE"))[0])

    def test_band_version_is_not_original(self):
        song={"아티스트":"Jung Kook","곡명":"Standing Next to You","음원 버전":""}
        self.assertFalse(eligible(song,self.candidate("Standing Next to You - Band Ver.","Jung Kook"))[0])

    def test_run_not_misidentified_as_run_bts(self):
        song={"아티스트":"BTS","곡명":"RUN","음원 버전":""}
        self.assertFalse(eligible(song,self.candidate("Run BTS","BTS"))[0])
        self.assertTrue(eligible(song,self.candidate("BTS - RUN","BTS"))[0])

    def test_explicit_cover_still_allowed_when_requested(self):
        song={"아티스트":"Jung Kook","곡명":"Standing Next to You - Band Ver.","음원 버전":""}
        self.assertTrue(eligible(song,self.candidate("Standing Next to You - Band Ver.","Jung Kook"))[0])

if __name__=="__main__":unittest.main()
