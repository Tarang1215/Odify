import hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('audio_requests',Path(__file__).parents[1]/'audio_requests.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def body(kind='paste_link'):
    return dict(schema=1,request_id='00000000-0000-0000-0000-000000000001',kind=kind,
        song_id='gdrive:abcdefghijklmno',drive_file_id='abcdefghijklmno',expected_md5=hashlib.md5(b'old').hexdigest(),
        title='Song',artist='Artist',album='',duration_ms=180000,youtube_url='https://youtu.be/abcdefghijk',reason='live',created_at='2026-10-25T00:00:00Z')

class FakeDrive:
    def __init__(self):self.audio=b'old';self.updates=0;self.pinned=False;self.corrupt=False
    def metadata(self,target):return dict(id=target,name='song.mp3',mimeType='audio/mpeg',size=len(self.audio),
        md5Checksum=hashlib.md5(self.audio).hexdigest(),headRevisionId='old-revision')
    def download(self,target,path):path.write_bytes(self.audio)
    def pin(self,target,revision):self.pinned=True
    def replace(self,target,path,mime):self.audio=b'bad' if self.corrupt else path.read_bytes();self.updates+=1

class AudioRequestsTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.db=m.connect(self.root/'requests.sqlite3');self.addCleanup(self.db.close);self.drive=FakeDrive()
    def download(self,b,folder,backup,resolve=False):
        self.assertEqual(b'old',backup.read_bytes());target=folder/'new.mp3';target.write_bytes(b'new')
        return target,'https://www.youtube.com/watch?v=abcdefghijk'
    def run_request(self,b=None,**kwargs):
        return m.process(self.db,self.drive,b or body(),self.root,downloader=self.download,**kwargs)
    def test_report_only_records_db_and_does_not_contact_drive(self):
        with patch.object(self.drive,'metadata',side_effect=AssertionError('no Drive audio access')):
            self.assertEqual('REPORTED',self.run_request(body('report'))['status'])
        self.assertEqual('REPORTED',self.db.execute('SELECT status FROM audio_request').fetchone()[0]);self.assertEqual(0,self.drive.updates)
    def test_paste_waits_without_processor_flag(self):
        self.assertEqual('QUEUED',self.run_request()['status']);self.assertEqual(0,self.drive.updates)
    def test_replace_backs_up_and_keeps_id_and_is_idempotent(self):
        self.assertEqual('COMPLETED',self.run_request(process_links=True)['status'])
        self.assertTrue(self.drive.pinned);self.assertEqual(b'old',next(self.root.rglob('previous.mp3')).read_bytes())
        self.assertEqual(b'new',self.drive.audio);self.assertEqual(1,self.drive.updates)
        self.assertEqual('COMPLETED',self.run_request(process_links=True)['status']);self.assertEqual(1,self.drive.updates)
        self.assertEqual(body()['drive_file_id'],self.db.execute('SELECT drive_id FROM audio_source_override').fetchone()[0])
    def test_stale_request_does_not_overwrite(self):
        self.drive.audio=b'someone edited this';self.assertEqual('CONFLICT',self.run_request(process_links=True)['status'])
        self.assertEqual(0,self.drive.updates)
    def test_target_change_during_download_is_preserved(self):
        def download(*args,**kwargs):
            value=self.download(*args,**kwargs);self.drive.audio=b'edited concurrently';return value
        result=m.process(self.db,self.drive,body(),self.root,process_links=True,downloader=download)
        self.assertEqual('CONFLICT',result['status']);self.assertEqual(b'edited concurrently',self.drive.audio)
    def test_download_failure_does_not_replace(self):
        with patch.object(m,'blocked',return_value=False):
            result=m.process(self.db,self.drive,body(),self.root,process_links=True,downloader=lambda *a,**k:(_ for _ in ()).throw(RuntimeError('download failed')))
        self.assertEqual('FAILED',result['status']);self.assertEqual(b'old',self.drive.audio)
    def test_persistent_source_gate_prevents_extraction(self):
        (self.root/'SOURCE_PAUSED').write_text('paused')
        with patch.object(self.drive,'download',side_effect=AssertionError('no download')):
            self.assertEqual('SOURCE_PAUSED',self.run_request(process_links=True)['status'])
    def test_gate_failure_is_persisted(self):
        result=m.process(self.db,self.drive,body(),self.root,process_links=True,
            downloader=lambda *a,**k:(_ for _ in ()).throw(m.SourceAccessPaused()))
        self.assertEqual('SOURCE_PAUSED',result['status']);self.assertTrue((self.root/'SOURCE_PAUSED').exists())
        self.assertEqual(b'old',self.drive.audio)
    def test_changed_request_input_is_rejected(self):
        self.run_request();changed={**body(),'drive_file_id':'newfile123456','song_id':'gdrive:newfile123456'}
        with self.assertRaises(ValueError):self.run_request(changed,process_links=True)
    def test_crash_after_patch_recovers_without_second_download(self):
        real=self.drive.metadata
        def metadata(target):
            if self.drive.updates:raise RuntimeError('connection lost after upload')
            return real(target)
        with patch.object(self.drive,'metadata',side_effect=metadata):
            self.assertEqual('FAILED',self.run_request(process_links=True)['status'])
        self.assertEqual('COMPLETED',self.run_request(process_links=True)['status']);self.assertEqual(1,self.drive.updates)
    def test_verification_failure_keeps_backup_and_no_override(self):
        self.drive.corrupt=True
        self.assertEqual('FAILED',self.run_request(process_links=True)['status'])
        self.assertEqual(b'old',next(self.root.rglob('previous.mp3')).read_bytes())
        self.assertEqual(0,self.db.execute('SELECT COUNT(*) FROM audio_source_override').fetchone()[0])
    def test_url_and_exact_target_validation(self):
        self.assertEqual('https://www.youtube.com/watch?v=abcdefghijk',m.youtube_url('https://youtu.be/abcdefghijk?t=6'))
        for url in ('https://youtube.com.evil/watch?v=abcdefghijk','https://x@youtube.com/watch?v=abcdefghijk',
                    'file:///tmp/file','https://youtube.com/playlist?list=123','https://youtu.be/abcdefghijk/extra'):
            with self.assertRaises(ValueError):m.youtube_url(url)
        with self.assertRaises(ValueError):m.validate({**body(),'song_id':'gdrive:wrongfile12345'})

if __name__=='__main__':unittest.main()
