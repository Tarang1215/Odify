import csv
from io import BytesIO
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch, MagicMock
from zipfile import ZipFile

import drive_source
import music_catalog
import run_master
from source_aliases import approved_alias


def master_row(track_id, artist='Artist', title='Song', verified='검증완료', url='https://www.youtube.com/watch?v=ABCDEFGHI_0'):
    return dict(zip(music_catalog.COLUMNS, [track_id, '2026', '한국', 'Pop', artist, title, 'Album', url,
                                          verified, '대기', '대기', '대기']))


class MasterCollectionTests(unittest.TestCase):
    def test_download_inspection_rejects_wrong_artist_before_audio_write(self):
        info = {'id':'ABCDEFGHI_0','duration':127,'title':'Fast Car','track':'Fast Car',
                'artist':'Taio Cruz','channel':'Taio Cruz - Topic'}
        completed = subprocess.CompletedProcess([],0,stdout=json.dumps(info),stderr='')
        with patch.object(music_catalog.subprocess,'run',return_value=completed):
            with self.assertRaisesRegex(ValueError,'artist'):
                music_catalog.inspect_audio_video('https://youtu.be/ABCDEFGHI_0','Fast Car',artist='安室奈美恵')

    def test_substring_artist_and_omitted_collaborator_do_not_match(self):
        info={'duration':180,'title':'Song','track':'Song','artist':'Artist Other', 'channel':'Artist Other - Topic'}
        self.assertFalse(run_master.candidate_matches(master_row('ODF000001'),info))
        self.assertFalse(run_master.candidate_matches(master_row('ODF000001',artist='Artist & Singer'),
                                                    {**info,'artist':'Artist','channel':'Artist - Topic'}))

    def test_language_version_is_not_the_original_recording(self):
        row=master_row('ODF000201',artist='LE SSERAFIM',title='EASY')
        info={'id':'WaZjvvfCNzw','duration':165,'title':'EASY (English ver.)',
              'track':'EASY (English ver.)','artist':'LE SSERAFIM','channel':'LE SSERAFIM - Topic'}
        self.assertFalse(run_master.candidate_matches(row,info))

    def test_translated_titles_require_exact_reviewed_video_artist_and_title(self):
        info = {'id': 'jCKcEoZrlJQ', 'track': 'My whole world', 'artist': '카더가든'}
        self.assertTrue(approved_alias('그대 작은 나의 세상이 되어', info, '카더가든'))
        self.assertFalse(approved_alias('그대 작은 나의 세상이 되어', {**info, 'id': 'XXXXXXXXXXX'}))
        self.assertFalse(approved_alias('그대 작은 나의 세상이 되어', {**info, 'artist': 'Other'}))
        self.assertFalse(approved_alias('그대 작은 나의 세상이 되어', {**info, 'track': 'Remix'}))
        self.assertFalse(approved_alias('Other song', info))

    def test_plan_has_no_152_track_ceiling_and_checks_actual_drive(self):
        rows = [master_row(f'ODF{number:06d}', title=f'Song {number}') for number in range(1, 510)]
        plan = run_master.build_plan(rows, {'ODF000001': {'Size': 2000}})
        self.assertEqual(len(plan), 509)
        self.assertEqual(sum(row['collection_status'] == 'ready' for row in plan), 508)
        self.assertEqual(plan[0]['collection_status'], 'already_on_drive')

    def test_pending_duplicates_use_verified_representative_and_existing_alias(self):
        pending = master_row('ODF000001', verified='대기', url='')
        verified = master_row('ODF000002')
        plan = run_master.build_plan([pending, verified], {})
        self.assertEqual(plan[0]['canonical_id'], 'ODF000002')
        self.assertEqual(plan[0]['collection_status'], 'duplicate_of_master')
        self.assertEqual(plan[1]['collection_status'], 'ready')
        present = run_master.build_plan([pending, verified], {'ODF000001': {'Size': 2000}})
        self.assertTrue(all(row['collection_status'] == 'already_on_drive' for row in present))

    def test_unverified_links_are_discovered_not_silently_omitted(self):
        plan = run_master.build_plan([master_row('ODF000001', verified='대기', url='')], {})
        self.assertEqual(plan[0]['collection_status'], 'needs_link')

    def test_remote_id_accepts_existing_underscore_names_without_prefix_collision(self):
        self.assertEqual(run_master.file_track_id('ODF000011_Drowning_converted_test.mp3'), 'ODF000011')
        self.assertNotEqual(run_master.file_track_id('ODF0000119.mp3'), 'ODF000011')

    def test_search_requires_matching_artist_and_recording(self):
        row = master_row('ODF000001')
        info = {'id': 'ABCDEFGHI_0', 'title': 'Song', 'artist': 'Artist',
                'track': 'Song', 'channel': 'Artist - Topic', 'duration': 180}
        self.assertTrue(run_master.candidate_matches(row, info))
        self.assertFalse(run_master.candidate_matches(row, {**info, 'artist': 'Other', 'channel': 'Other - Topic'}))
        self.assertFalse(run_master.candidate_matches(row, {**info, 'title': 'Song karaoke'}))
        self.assertFalse(run_master.candidate_matches(row, {**info, 'is_live': True}))
        self.assertFalse(run_master.candidate_matches(row, {**info, 'track': 'Another', 'title': 'Another'}))

    def test_master_export_selects_named_sheet_even_when_it_is_not_first(self):
        data = BytesIO()
        with ZipFile(data, 'w') as archive:
            archive.writestr('xl/workbook.xml', '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Other" r:id="rId1"/><sheet name="MASTER" r:id="rId2"/></sheets></workbook>')
            archive.writestr('xl/_rels/workbook.xml.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>')
            archive.writestr('xl/sharedStrings.xml', '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Track ID</t></si><si><t>연도</t></si></sst>')
            archive.writestr('xl/worksheets/sheet2.xml', '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="D1" t="inlineStr"><is><t>가수</t></is></c></row></sheetData></worksheet>')
        self.assertEqual(drive_source.workbook_rows(data.getvalue()), [['Track ID', '연도', '', '가수']])

    def test_metadata_uses_current_python_module_without_yt_dlp_on_path(self):
        info = {'duration': 180, 'title': 'Song', 'channel': 'Artist - Topic'}
        completed = subprocess.CompletedProcess([], 0, stdout=json.dumps(info), stderr='')
        with patch.object(music_catalog.subprocess, 'run', return_value=completed) as execute:
            music_catalog.inspect_audio_video('https://youtu.be/ABCDEFGHI_0', 'Song')
        self.assertEqual(execute.call_args.args[0][:3], [sys.executable, '-m', 'yt_dlp'])

    def test_upload_is_not_successful_until_remote_hash_matches(self):
        with TemporaryDirectory() as folder:
            audio = Path(folder) / 'ODF000001.mp3'
            audio.write_bytes(b'test audio')
            with patch.object(run_master, 'run', side_effect=['', 'wronghash  file']):
                with self.assertRaisesRegex(RuntimeError, 'checksum'):
                    run_master.upload_track(audio, 'mydrive:Odify_Auto_Upload')

    def test_feature_annotations_do_not_reject_the_correct_studio_recording(self):
        info = {'duration': 180, 'title': 'Song', 'track': 'Song', 'channel': 'Artist - Topic'}
        completed = subprocess.CompletedProcess([], 0, stdout=json.dumps(info), stderr='')
        with patch.object(music_catalog.subprocess, 'run', return_value=completed):
            music_catalog.inspect_audio_video('https://youtu.be/ABCDEFGHI_0', 'Song (feat. Singer)')
        self.assertEqual(music_catalog.recording_title('Song (Live)'), 'Song (Live)')

    def test_removed_verified_video_is_recovered_with_a_validated_alternative(self):
        row = master_row('ODF000001')
        replacement = 'https://www.youtube.com/watch?v=ZYXWVUTSR_0'
        with patch.object(music_catalog, 'download_one', side_effect=[RuntimeError('Video unavailable'), 'ODF000001']) as download:
            with patch.object(run_master, 'resolve_link', return_value=(replacement, '{}')):
                run_master.download_track(MagicMock(), row, Path('.'), row['YouTube 오디오 URL'])
        self.assertEqual(download.call_count, 2)
        self.assertEqual(download.call_args.args[1][-1], replacement)

    def test_access_challenge_is_not_retried_or_bypassed(self):
        row = master_row('ODF000001')
        with patch.object(music_catalog, 'download_one', side_effect=RuntimeError('Sign in to confirm you are not a bot')):
            with patch.object(run_master, 'resolve_link') as search:
                with self.assertRaises(RuntimeError):
                    run_master.download_track(MagicMock(), row, Path('.'), row['YouTube 오디오 URL'])
                search.assert_not_called()


if __name__ == '__main__':
    unittest.main()
