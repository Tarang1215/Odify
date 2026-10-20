use super::{ExtensionManager, RuntimeLimits};
use crate::storage::{StorageMasterKey, encrypt};
use serde_json::{Value, json};
use std::fs::File;
use std::sync::Arc;

#[test]
fn unreadable_settings_keep_installed_packages_visible_and_preserve_data() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("sources");
    let data = directory.path().join("data");
    let original_key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    let other_key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    let key = StorageMasterKey::from_base64(original_key).unwrap();
    let ids = ["example.first", "example.second"];
    let mut snapshots = Vec::new();
    for id in ids {
        let source = sources.join(id);
        let private = data.join(id);
        std::fs::create_dir_all(&source).unwrap();
        std::fs::create_dir_all(&private).unwrap();
        std::fs::write(
            source.join("manifest.json"),
            json!({"name":id,"displayName":"Example","version":"1","description":"Storage recovery fixture","type":["metadata_provider"]}).to_string(),
        ).unwrap();
        // Even validation must not execute while private storage is broken.
        std::fs::write(source.join("index.js"), "throw Error('must not run');").unwrap();
        let settings = encrypt(
            br#"{"_enabled":true,"region":"example"}"#,
            &key.derive(id, "settings"),
        )
        .unwrap();
        let credentials = encrypt(
            br#"{"token":"example-secret"}"#,
            &key.derive(id, "credentials"),
        )
        .unwrap();
        std::fs::write(private.join("settings.enc"), &settings).unwrap();
        std::fs::write(private.join(".credentials.enc"), &credentials).unwrap();
        snapshots.push((private, settings, credentials));
    }
    let manager =
        ExtensionManager::new(&sources, &data, other_key, "1", RuntimeLimits::default()).unwrap();
    let loaded: Value = serde_json::from_str(&manager.load_all().unwrap()).unwrap();
    assert_eq!(loaded["loaded"], json!(ids));
    let installed: Value = serde_json::from_str(&manager.installed().unwrap()).unwrap();
    assert_eq!(installed.as_array().unwrap().len(), 2);
    for entry in installed.as_array().unwrap() {
        assert_eq!(entry["enabled"], false);
        assert_eq!(entry["status"], "error");
        assert_eq!(
            entry["error_message"],
            "cipher: message authentication failed"
        );
        assert!(
            manager
                .get(entry["id"].as_str().unwrap())
                .unwrap()
                .engine
                .lock()
                .unwrap()
                .runtime
                .is_none()
        );
    }
    for (private, settings, credentials) in &snapshots {
        assert_eq!(
            std::fs::read(private.join("settings.enc")).unwrap(),
            *settings
        );
        assert_eq!(
            std::fs::read(private.join(".credentials.enc")).unwrap(),
            *credentials
        );
        assert!(!private.join(".cred_salt").exists());
    }
    manager.shutdown();

    // Restoring the correct key makes the existing settings readable again.
    for id in ids {
        std::fs::write(sources.join(id).join("index.js"), "registerExtension({});").unwrap();
    }
    let recovered =
        ExtensionManager::new(&sources, &data, original_key, "1", RuntimeLimits::default())
            .unwrap();
    recovered.load_all().unwrap();
    let installed: Value = serde_json::from_str(&recovered.installed().unwrap()).unwrap();
    for entry in installed.as_array().unwrap() {
        assert_eq!(entry["enabled"], true);
        assert_eq!(entry["status"], "loaded");
        assert_eq!(
            recovered
                .environment
                .settings(entry["id"].as_str().unwrap())
                .unwrap()["region"],
            "example"
        );
    }
}

#[test]
fn manager_and_isolated_vm_share_one_retained_source_allocation() {
    let directory = tempfile::tempdir().unwrap();
    let sources = directory.path().join("sources");
    let source_dir = sources.join("example.memory");
    std::fs::create_dir_all(&source_dir).unwrap();
    let manifest = r#"{"name":"example.memory","displayName":"Example Memory","version":"1","description":"Generic memory fixture","type":["metadata_provider"]}"#;
    std::fs::write(source_dir.join("manifest.json"), manifest).unwrap();
    let source = format!(
        "/*{}*/let calls=0;registerExtension({{next(){{return ++calls;}}}});",
        "x".repeat(4 * 1024 * 1024)
    );
    let source_bytes = source.len();
    std::fs::write(source_dir.join("index.js"), &source).unwrap();
    drop(source);
    let manager = ExtensionManager::new(
        &sources,
        &directory.path().join("data"),
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "1",
        RuntimeLimits::default(),
    )
    .unwrap();
    manager.load_all().unwrap();
    manager.set_enabled("example.memory", true).unwrap();
    let entry = manager.get("example.memory").unwrap();
    let mut engine = entry.engine.lock().unwrap();
    let primary = manager.ready(&entry, &mut engine, true).unwrap();
    let retained = engine.source.as_ref().unwrap();
    assert!(Arc::ptr_eq(retained, &primary.shared_source()));
    let isolated = manager
        .environment
        .load_isolated(manifest, retained, RuntimeLimits::default(), &primary)
        .unwrap();
    assert!(Arc::ptr_eq(retained, &isolated.shared_source()));
    assert_eq!(retained.len(), source_bytes);
    drop(engine);
    assert_eq!(primary.call("next", "[]", None, 0).unwrap(), "1");
    assert_eq!(isolated.call("next", "[]", None, 0).unwrap(), "1");
    isolated.shutdown();
    assert_eq!(primary.call("next", "[]", None, 0).unwrap(), "2");
    eprintln!(
        "retained source text: baseline_bytes={} shared_bytes={} saved_bytes={source_bytes}; primary/isolated JS state remains separate",
        source_bytes * 2,
        source_bytes
    );
    manager.shutdown();
}

#[test]
fn shutdown_unlocks_inherited_descriptors_without_unlocking_reopened_owner() {
    let directory = tempfile::tempdir().unwrap();
    let open = || {
        ExtensionManager::new(
            &directory.path().join("sources"),
            &directory.path().join("data"),
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "1",
            RuntimeLimits::default(),
        )
    };
    let manager = open().unwrap();
    // A fork before exec can retain the same open file description even
    // though the manager closes its own descriptor during shutdown.
    let inherited = manager
        .directory_locks
        .lock()
        .unwrap()
        .as_ref()
        .unwrap()
        .iter()
        .map(File::try_clone)
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    manager.shutdown();
    let reopened = open().unwrap();
    drop(inherited);
    drop(manager);
    assert!(
        open()
            .err()
            .unwrap()
            .to_string()
            .contains("already managed")
    );
    reopened.shutdown();
    assert!(open().is_ok());
}
