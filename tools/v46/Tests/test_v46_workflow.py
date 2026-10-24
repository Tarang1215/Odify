import csv
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import odify_library as library
import odify_review as review


class ReviewTests(unittest.TestCase):
    def row(self, **changes):
        return dict(url='https://www.youtube.com/watch?v=ABCDEFGHI_0', video_id='ABCDEFGHI_0',
                    extracted_id='ABCDEFGHI_0', artist='Artist', track='Song', extracted_title='Song',
                    extracted_channel='Artist - Topic', extracted_seconds='127', expected_seconds='127',
                    extractor_status='audio_formats_present', audio_formats='3', **changes)

    def test_extractable_wrong_artist_is_held(self):
        row = self.row(); row['extracted_channel'] = 'Different artist - Topic'
        self.assertEqual(review.evaluate(row)['identity_decision'], 'REVIEW')
        self.assertEqual(review.evaluate(row)['approved_for_playback'], 0)

    def test_altered_versions_rejected_even_when_duration_matches(self):
        for title in ['Song (Instrumental)', 'Song cover', 'Song remix', 'Song 라이브']:
            row = self.row(); row['extracted_title'] = title
            self.assertEqual(review.evaluate(row)['identity_decision'], 'REJECT')

    def test_short_original_and_word_boundary(self):
        row = self.row(); row.update(track='Olive', extracted_title='Olive')
        self.assertEqual(review.evaluate(row)['identity_decision'], 'METADATA_MATCH')

    def test_missing_and_nonfinite_duration_are_held(self):
        for value in ['', 'nan', 'inf', '-1']:
            row = self.row(); row['extracted_seconds'] = value
            self.assertEqual(review.evaluate(row)['identity_decision'], 'REVIEW')


class LibraryTests(unittest.TestCase):
    def test_traversal_absolute_and_ads_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            for path in ['../bad.mp3', 'C:/bad.mp3', '/bad.mp3', 'song.mp3:stream']:
                with self.assertRaises(ValueError): library.child(tmp, path)

    def test_copy_conflict_and_idempotent_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp); src=base/'a.mp3'; dst=base/'b.mp3'; src.write_bytes(b'first')
            self.assertEqual(library.copy_verified(src,dst)[0], 'WOULD_COPY')
            self.assertFalse(dst.exists())
            self.assertEqual(library.copy_verified(src,dst,True)[0], 'COPIED_VERIFIED')
            self.assertEqual(library.copy_verified(src,dst,True)[0], 'EXISTS_SAME_HASH')
            src.write_bytes(b'changed')
            self.assertEqual(library.copy_verified(src,dst,True)[0], 'CONFLICT_SKIP')
            self.assertEqual(dst.read_bytes(),b'first')

    def test_selection_resets_when_audio_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'Music').mkdir(); audio=root/'Music'/'a.mp3'; audio.write_bytes(b'a')
            rows=library.selection(root); self.assertEqual(rows[0]['selected'],'NO')
            rows[0]['selected']='YES'; manifest=root/'Logs'/'drive_selection.csv'; manifest.unlink()
            library.write_csv(manifest, rows)
            self.assertEqual(library.selection(root)[0]['selected'],'YES')
            audio.write_bytes(b'b')
            self.assertEqual(library.selection(root)[0]['selected'],'NO')

    def test_remote_dedupe_hash_conflict_and_modified_selection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'Music').mkdir(); audio=root/'Music'/'a.mp3'; audio.write_bytes(b'a')
            rows=library.selection(root); rows[0]['selected']='YES'
            manifest=root/'Logs'/'drive_selection.csv'; manifest.unlink(); library.write_csv(manifest, rows)
            item={'Path':'other.mp3','Size':1,'Hashes':{'md5':library.digest(audio,'md5')},'ID':'existing-id'}
            with patch.object(library,'command') as execute:
                result=library.drive_sync(root,'private.conf','exact-folder-id',True,[item])
                self.assertEqual(result[0]['status'],'REMOTE_VERIFIED'); execute.assert_not_called()
            conflict={**item,'Path':'a.mp3','Hashes':{'md5':'wrong'}}
            with patch.object(library,'command') as execute:
                self.assertEqual(library.drive_sync(root,'private.conf','exact-folder-id',True,[conflict])[0]['status'],'CONFLICT_SKIP')
                execute.assert_not_called()
            audio.write_bytes(b'changed')
            with self.assertRaises(ValueError):library.drive_sync(root,'private.conf','exact-folder-id',True,[])

    def test_blocked_recording_cannot_be_uploaded_by_selection_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'Music').mkdir(); (root/'Music'/'a.mp3').write_bytes(b'a')
            rows=library.selection(root); rows[0].update(selected='YES',drive_upload_status='BLOCKED_VERSION_MISMATCH')
            manifest=root/'Logs'/'drive_selection.csv'; manifest.unlink(); library.write_csv(manifest,rows)
            with patch.object(library,'command') as execute:
                with self.assertRaisesRegex(ValueError,'Recording review'):
                    library.drive_sync(root,'private.conf','exact-folder-id',True,[])
                execute.assert_not_called()

    def test_upload_requires_remote_checksum_before_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'Music').mkdir(); audio=root/'Music'/'a.mp3'; audio.write_bytes(b'a')
            rows=library.selection(root); rows[0]['selected']='YES'
            manifest=root/'Logs'/'drive_selection.csv'; manifest.unlink(); library.write_csv(manifest, rows)
            with patch.object(library,'command', side_effect=['','{"Size":1,"Hashes":{"md5":"wrong"}}']):
                with self.assertRaisesRegex(ValueError, 'Remote verification failed'):
                    library.drive_sync(root,'private.conf','exact-folder-id',True,[])

    def test_reconciliation_requires_hash_and_size_not_title(self):
        import json
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'Music').mkdir(); audio=root/'Music'/'same-title.mp3'; audio.write_bytes(b'a')
            library.selection(root)
            inventory=root/'remote.json'
            inventory.write_text(json.dumps([{'Path':'same-title.mp3','Size':1,
                'Hashes':{'sha256':'wrong'},'ID':'wrong-artist'}]),encoding='utf-8')
            self.assertEqual(library.reconcile(root,[inventory])[0]['status'],'LOCAL_ONLY')
            inventory.write_text(json.dumps([{'Path':'renamed.mp3','Size':1,
                'Hashes':{'sha256':library.digest(audio)},'ID':'owned-id'}]),encoding='utf-8')
            self.assertEqual(library.reconcile(root,[inventory])[0]['drive_ids'],'owned-id')

    def test_batch_upload_verifies_observed_remote_hashes_and_deduplicates(self):
        import json
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'Music').mkdir(); (root/'Metadata').mkdir()
            audio=root/'Music'/'a.mp3'; audio.write_bytes(b'a')
            (root/'Music'/'duplicate.mp3').write_bytes(b'a')
            rows=library.selection(root)
            for r in rows:r['selected']='YES'
            manifest=root/'Logs'/'drive_selection.csv';manifest.unlink();library.write_csv(manifest,rows)
            item={'Path':'a.mp3','ID':'new-remote-id','Size':1,'Hashes':{'sha256':library.digest(audio)}}
            with patch.object(library,'command',side_effect=['[]','',json.dumps([item])]) as execute:
                receipts=library.batch_sync(root,'private.conf','exact-folder-id',[],True)
                self.assertEqual(len(receipts),2)
                self.assertTrue(all(r['drive_id']=='new-remote-id' for r in receipts))
                self.assertIn('--immutable',execute.call_args_list[1].args[0])
                self.assertEqual(len(next((root/'Logs').glob('drive_batch_*.txt')).read_text().splitlines()),1)

    def test_batch_partial_remote_result_cannot_claim_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'Music').mkdir();(root/'Music'/'a.mp3').write_bytes(b'a')
            rows=library.selection(root);rows[0]['selected']='YES'
            manifest=root/'Logs'/'drive_selection.csv';manifest.unlink();library.write_csv(manifest,rows)
            with patch.object(library,'command',side_effect=['[]','','[]']):
                with self.assertRaisesRegex(ValueError,'Remote checksum'):
                    library.batch_sync(root,'private.conf','exact-folder-id',[],True)


if __name__=='__main__': unittest.main()
