//! Filesystem helpers shared by the native and extension backends.

use std::fmt;
use std::fs::File;
use std::io;
use std::path::{Component, Path, PathBuf};

/// The prefix `tempfile::NamedTempFile::new_in` uses, for writers that kept it.
pub const DEFAULT_TEMP_PREFIX: &str = ".tmp";

/// A [`write_atomic`] failure. `Persist` keeps tempfile's own message
/// (`failed to persist temporary file: ...`); converting to [`io::Error`]
/// yields the underlying rename error instead, like `PersistError::error`.
#[derive(Debug)]
pub enum WriteAtomicError {
    Io(io::Error),
    Persist(tempfile::PersistError),
}

impl fmt::Display for WriteAtomicError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => error.fmt(formatter),
            Self::Persist(error) => error.fmt(formatter),
        }
    }
}

impl std::error::Error for WriteAtomicError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => error.source(),
            Self::Persist(error) => error.source(),
        }
    }
}

impl From<io::Error> for WriteAtomicError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<WriteAtomicError> for io::Error {
    fn from(error: WriteAtomicError) -> Self {
        match error {
            WriteAtomicError::Io(error) => error,
            WriteAtomicError::Persist(error) => error.error,
        }
    }
}

/// Replaces `path` atomically. In order: creates a `{prefix}XXXXXX` temporary
/// file in `directory`, passes it to `write`, `sync_all`s it, runs `check`,
/// then renames it over `path` (`NamedTempFile::persist`, which replaces an
/// existing file). With `sync_directory`, `directory` is then fsynced on a
/// best-effort basis. Any earlier failure removes the temporary file.
pub fn write_atomic(
    directory: &Path,
    path: &Path,
    prefix: &str,
    write: impl FnOnce(&mut File) -> io::Result<()>,
    check: impl FnOnce() -> io::Result<()>,
    sync_directory: bool,
) -> Result<(), WriteAtomicError> {
    let mut file = tempfile::Builder::new()
        .prefix(prefix)
        .tempfile_in(directory)?;
    write(file.as_file_mut())?;
    file.as_file().sync_all()?;
    check()?;
    file.persist(path).map_err(WriteAtomicError::Persist)?;
    // Directory fsync is unavailable on some filesystems; rename remains atomic.
    if sync_directory && let Ok(directory) = File::open(directory) {
        let _ = directory.sync_all();
    }
    Ok(())
}

/// Lexically cleans a path like Go's `filepath.Clean`: drops `.` components,
/// folds `name/..`, keeps leading `..` on relative paths, drops `..` above the
/// root, and returns `.` for an empty result. The filesystem is not consulted.
pub fn clean(path: &Path) -> PathBuf {
    let mut result = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                if result.file_name().is_some_and(|name| name != "..") {
                    result.pop();
                } else if !result.has_root() {
                    result.push("..");
                }
            }
            _ => result.push(component),
        }
    }
    if result.as_os_str().is_empty() {
        ".".into()
    } else {
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // The copy `isrc::native_files` used before it shared this helper.
    fn previous_isrc_clean(path: &Path) -> PathBuf {
        let mut output = PathBuf::new();
        for component in path.components() {
            match component {
                Component::CurDir => {}
                Component::ParentDir if output.file_name().is_some_and(|name| name != "..") => {
                    output.pop();
                }
                Component::ParentDir if output.has_root() => {}
                other => output.push(other.as_os_str()),
            }
        }
        if output.as_os_str().is_empty() {
            output.push(".");
        }
        output
    }

    #[test]
    fn clean_matches_previous_isrc_copy_and_go_results() {
        let cases = [
            ("", "."),
            (".", "."),
            ("./", "."),
            ("..", ".."),
            ("../..", "../.."),
            ("../../a", "../../a"),
            ("a/..", "."),
            ("a/../..", ".."),
            ("a/b/../../..", ".."),
            ("a/./b/", "a/b"),
            ("a//b///c", "a/b/c"),
            ("a/b/", "a/b"),
            ("/", "/"),
            ("/..", "/"),
            ("/../a", "/a"),
            ("/a/b/../c/./d/", "/a/c/d"),
            ("/a/..", "/"),
            ("//a", "/a"),
            ("a/...", "a/..."),
            ("a/.../..", "a"),
            ("..a/../b", "b"),
            ("\u{00e9}/\u{1f3b5}/../x", "\u{00e9}/x"),
        ];
        for (input, expected) in cases {
            let input = Path::new(input);
            assert_eq!(clean(input), Path::new(expected), "{input:?}");
            assert_eq!(clean(input), previous_isrc_clean(input), "{input:?}");
        }
    }

    fn names(directory: &Path) -> Vec<String> {
        let mut names: Vec<_> = std::fs::read_dir(directory)
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        names
    }

    #[test]
    fn default_temp_prefix_matches_named_temp_file() {
        let directory = tempfile::tempdir().unwrap();
        let file = tempfile::NamedTempFile::new_in(directory.path()).unwrap();
        let name = file.path().file_name().unwrap().to_string_lossy();
        assert!(name.starts_with(DEFAULT_TEMP_PREFIX), "{name}");
        assert_eq!(name.len(), DEFAULT_TEMP_PREFIX.len() + 6);
    }

    #[test]
    fn write_atomic_stages_with_prefix_syncs_checks_then_replaces() {
        use std::io::Write;
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("value.json");
        std::fs::write(&path, b"old").unwrap();
        let order = std::cell::RefCell::new(Vec::new());

        write_atomic(
            directory.path(),
            &path,
            ".mornye-",
            |file| {
                let staged = names(directory.path());
                assert_eq!(staged.len(), 2, "{staged:?}");
                assert!(staged[0].starts_with(".mornye-"), "{staged:?}");
                order.borrow_mut().push("write");
                file.write_all(b"new")
            },
            || {
                assert_eq!(std::fs::read(&path).unwrap(), b"old");
                order.borrow_mut().push("check");
                Ok(())
            },
            true,
        )
        .unwrap();
        assert_eq!(*order.borrow(), ["write", "check"]);
        assert_eq!(std::fs::read(&path).unwrap(), b"new");
        assert_eq!(names(directory.path()), ["value.json"]);

        for failing_check in [false, true] {
            let error = write_atomic(
                directory.path(),
                &path,
                DEFAULT_TEMP_PREFIX,
                |file| {
                    file.write_all(b"partial")?;
                    if failing_check {
                        Ok(())
                    } else {
                        Err(io::Error::other("write failed"))
                    }
                },
                || Err(io::Error::other("cancelled")),
                false,
            )
            .unwrap_err();
            let expected = if failing_check {
                "cancelled"
            } else {
                "write failed"
            };
            assert_eq!(error.to_string(), expected);
            assert_eq!(std::fs::read(&path).unwrap(), b"new");
            assert_eq!(names(directory.path()), ["value.json"]);
        }
    }

    #[test]
    fn write_atomic_persist_errors_keep_tempfile_message_and_io_cause() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("occupied");
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("child"), b"").unwrap();
        let error = write_atomic(
            directory.path(),
            &path,
            ".tmp",
            |_| Ok(()),
            || Ok(()),
            false,
        )
        .unwrap_err();
        let WriteAtomicError::Persist(persist) = &error else {
            panic!("{error:?}");
        };
        let message = persist.to_string();
        assert_eq!(error.to_string(), message);
        assert!(message.starts_with("failed to persist temporary file: "));
        let cause = persist.error.to_string();
        assert_eq!(io::Error::from(error).to_string(), cause);
        assert_eq!(names(directory.path()), ["occupied"]);
    }
}
