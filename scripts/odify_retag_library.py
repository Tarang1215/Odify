#!/usr/bin/env python3
"""Conservatively correct existing Odify audio tags from a user-reviewed MASTER CSV.

This edits ONLY local files with an exact ODF###### prefix and an unambiguous
MASTER Track ID. It never guesses Korean/Japanese transliterations or changes
YouTube/Drive data. Default is dry-run; --apply requires an automatic backup.

Install: python -m pip install mutagen
Usage:
  python scripts/odify_retag_library.py --master-csv Odify_MASTER.csv --library ./Music
  python scripts/odify_retag_library.py --master-csv Odify_MASTER.csv --library ./Music --apply --backup-dir ./Odify_tag_backup

Upload corrected files to Google Drive only after reviewing the report. Never
bulk-overwrite Drive originals without independently reviewing cloud backups.
"""
from __future__ import annotations

import argparse
import csv
import re
import shutil
from pathlib import Path

SUPPORTED = {".mp3", ".m4a", ".mp4", ".flac", ".ogg", ".opus"}
CODE = re.compile(r"^(ODF[0-9]{6})(?=[. _-])", re.IGNORECASE)


def track_code(filename: str) -> str | None:
    match = CODE.match(filename)
    return match.group(1).upper() if match else None


def canonical_rows(path: Path) -> tuple[dict[str, tuple[str, str, str]], set[str]]:
    candidates: dict[str, set[tuple[str, str, str]]] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required = {"Track ID", "곡명", "아티스트"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError("MASTER CSV에는 Track ID, 곡명, 아티스트 열이 필요합니다.")
        for row in reader:
            code = str(row.get("Track ID", "")).strip().upper()
            if not re.fullmatch(r"ODF[0-9]{6}", code):
                continue
            title = str(row.get("곡명") or "").strip()
            artist = str(row.get("아티스트") or "").strip()
            album = str(row.get("앨범") or "").strip()
            if title and artist:
                candidates.setdefault(code, set()).add((title, artist, album))
    conflicts = {code for code, variants in candidates.items() if len(variants) != 1}
    verified = {code: next(iter(variants))
                for code, variants in candidates.items() if code not in conflicts}
    return verified, conflicts


def read_tags(path: Path) -> tuple[str, str, str]:
    from mutagen import File
    media = File(str(path), easy=True)
    if media is None:
        raise ValueError("지원되지 않는 오디오 태그 형식")
    def first(name: str) -> str:
        values = media.get(name, [])
        return str(values[0]) if values else ""
    return first("title"), first("artist"), first("album")


def set_tags(path: Path, fields: tuple[str, str, str]) -> None:
    from mutagen import File
    media = File(str(path), easy=True)
    if media is None:
        raise ValueError("오디오 태그를 쓸 수 없습니다.")
    if media.tags is None:
        media.add_tags()
    for key, value in zip(("title", "artist", "album"), fields):
        if value:  # A blank MASTER album must not erase a real album tag.
            media[key] = [value]
    media.save()


def run(master: Path, library: Path, apply: bool, backups: Path | None) -> int:
    if not library.is_dir():
        raise ValueError(f"음악 폴더가 없습니다: {library}")
    library = library.resolve()
    if apply:
        if backups is None:
            raise ValueError("--apply 시 --backup-dir를 명시해야 합니다.")
        backups = backups.resolve()
        if backups == library or library in backups.parents:
            raise ValueError("백업 폴더는 음악 폴더의 외부에 지정해야 합니다.")
    rows, conflicts = canonical_rows(master)
    changed = skipped = errors = 0
    print(f"MASTER {len(rows)}개, 충돌 {len(conflicts)}개 / 모드: {'실제 수정' if apply else '미리보기'}")
    for path in sorted(library.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in SUPPORTED:
            continue
        code = track_code(path.name)
        if code is None or code in conflicts or code not in rows:
            skipped += 1
            continue
        target = rows[code]
        try:
            before = read_tags(path)
            after = tuple(target[i] or before[i] for i in range(3))
            if before == after:
                skipped += 1
                continue
            print(f"{code} | {path.relative_to(library)}")
            print(f"  이전: {before!r}")
            print(f"  수정: {after!r}")
            if apply:
                assert backups is not None
                backup = backups / path.relative_to(library)
                if backup.exists():
                    raise FileExistsError(f"백업이 이미 있어 덮어쓰지 않습니다: {backup}")
                backup.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, backup)
                try:
                    set_tags(path, after)
                    if read_tags(path) != after:
                        raise RuntimeError("태그 검증 실패")
                except Exception:
                    shutil.copy2(backup, path)
                    raise
            changed += 1
        except Exception as exc:
            errors += 1
            print(f"  ! 오류: {path.name}: {exc}")
    print(f"수정 대상 {changed} / 건너뜀 {skipped} / 오류 {errors}")
    return 1 if errors else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--master-csv", required=True, type=Path)
    parser.add_argument("--library", required=True, type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup-dir", type=Path)
    opts = parser.parse_args()
    return run(opts.master_csv, opts.library, opts.apply, opts.backup_dir)


if __name__ == "__main__":
    raise SystemExit(main())
