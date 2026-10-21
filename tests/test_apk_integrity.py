import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "verify_android_apk", ROOT / "scripts" / "verify_android_apk.py"
)
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)

BASE = {
    "AndroidManifest.xml": b"manifest",
    "classes.dex": b"dex\\n035\\x00".decode("unicode_escape").encode("latin1"),
    "lib/arm64-v8a/libflutter.so": b"\\x7fELFflutter".decode("unicode_escape").encode("latin1"),
    "lib/arm64-v8a/libmornye_audio.so": b"\\x7fELFaudio".decode("unicode_escape").encode("latin1"),
}


class ApkIntegrityTests(unittest.TestCase):
    def _write(self, directory, *, mode, missing=None):
        filename = Path(directory) / ("test-debug.apk" if mode == "debug" else "test-release.apk")
        entries = dict(BASE)
        entries["assets/flutter_assets/kernel_blob.bin" if mode == "debug"
                else "lib/arm64-v8a/libapp.so"] = (
                    b"JIT-kernel" if mode == "debug" else b"\\x7fELFapp".decode("unicode_escape").encode("latin1")
                )
        if missing:
            entries.pop(missing)
        with ZipFile(filename, "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        return filename

    def test_release_requires_compiled_aot_library(self):
        with TemporaryDirectory() as tmp:
            verify.check(self._write(tmp, mode="release"), mode="release")
            with self.assertRaisesRegex(RuntimeError, "libapp.so"):
                verify.check(self._write(tmp, mode="release", missing="lib/arm64-v8a/libapp.so"))

    def test_debug_requires_kernel_not_aot(self):
        with TemporaryDirectory() as tmp:
            verify.check(self._write(tmp, mode="debug"), mode="debug")
            with self.assertRaisesRegex(RuntimeError, "kernel_blob.bin"):
                verify.check(self._write(tmp, mode="debug", missing="assets/flutter_assets/kernel_blob.bin"), mode="debug")

    def test_debug_cannot_masquerade_as_release(self):
        with TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "libapp.so"):
                verify.check(self._write(tmp, mode="debug"), mode="release")


if __name__ == "__main__":
    unittest.main()
