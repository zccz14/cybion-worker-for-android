//! Controlled APK upgrade. The Controller queues a target version over SSE;
//! the Worker downloads the matching APK from the Controller mirror (GitHub
//! Releases fallback), verifies the SHA-256 checksum and the pinned signing
//! certificate, hands the file to the system installer, and reports
//! `installing`/`failed`. Success is inferred by the Controller from the
//! version reported after the automatic restart.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use anyhow::{Context, Result, bail, ensure};
use futures_util::StreamExt;
use reqwest::Client;
use serde_json::json;
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use tokio::sync::watch;

use crate::config::WorkerConfig;
use crate::delivery;
use crate::platform;
use crate::protocol;

pub const ASSET: &str = "cybion-worker-android-aarch64.apk";

/// APKs are only accepted when signed by the official release certificate;
/// a mismatch aborts the upgrade before the installer is engaged.
pub const SIGNER_SHA256: &str = "a909a4f6d2231c9f94da8c819f0aa72e6688ac18f5a6b3f39c5d6dbfa9e831c9";

const GITHUB_RELEASE_BASE: &str =
    "https://github.com/zccz14/cybion-worker-for-android/releases/download";
const POLL_INTERVAL: Duration = Duration::from_secs(1);
const POLL_DEADLINE: Duration = Duration::from_secs(30 * 60);
const LOG_EVERY: u64 = 4 * 1024 * 1024;

/// One upgrade at a time. The Controller repeats the `upgrade` event every
/// second until the status changes, so repeats and parallel requests must not
/// start a second download or a second installer hand-off.
struct Active {
    id: String,
    handed_off: bool,
}

static ACTIVE: Mutex<Option<Active>> = Mutex::new(None);

fn begin(id: &str) -> bool {
    let mut active = ACTIVE.lock().expect("upgrade lock poisoned");
    if active.is_some() {
        return false;
    }
    *active = Some(Active {
        id: id.to_owned(),
        handed_off: false,
    });
    true
}

fn mark_handed_off(id: &str) {
    let mut active = ACTIVE.lock().expect("upgrade lock poisoned");
    if let Some(active) = active.as_mut()
        && active.id == id
    {
        active.handed_off = true;
    }
}

fn clear(id: &str) {
    let mut active = ACTIVE.lock().expect("upgrade lock poisoned");
    if active.as_ref().is_some_and(|active| active.id == id) {
        *active = None;
    }
}

/// Forgets any in-flight upgrade when the engine stops. The Controller keeps
/// repeating the event, so a fresh session retries safely instead of finding
/// the guard stuck after the async task was dropped.
pub fn reset() {
    *ACTIVE.lock().expect("upgrade lock poisoned") = None;
}

enum Outcome {
    /// Nothing else will happen in this process; the Controller may re-queue.
    Done,
    /// The installer owns the upgrade; keep ignoring repeated events until the
    /// process restarts into the new version.
    HandedOff,
}

pub fn spawn(
    client: Client,
    config: WorkerConfig,
    boot_id: String,
    config_dir: PathBuf,
    upgrade: protocol::Upgrade,
    shutdown: watch::Receiver<bool>,
) {
    if !begin(&upgrade.id) {
        log::info!(
            target: "cybion_worker",
            "upgrade {} is already handled; ignoring a repeated event",
            upgrade.id
        );
        return;
    }
    tokio::spawn(async move {
        match run(&client, &config, &boot_id, &config_dir, &upgrade, shutdown).await {
            Ok(Outcome::Done) => clear(&upgrade.id),
            Ok(Outcome::HandedOff) => {}
            Err(error) => {
                log::warn!(target: "cybion_worker", "upgrade {} failed: {error:#}", upgrade.id);
                if let Err(report_error) = report(
                    &client,
                    &config,
                    &boot_id,
                    &upgrade.id,
                    "failed",
                    Some(&format!("{error:#}")),
                )
                .await
                {
                    log::warn!(target: "cybion_worker", "upgrade failure report rejected: {report_error:#}");
                }
                clear(&upgrade.id);
            }
        }
    });
}

async fn run(
    client: &Client,
    config: &WorkerConfig,
    boot_id: &str,
    config_dir: &Path,
    upgrade: &protocol::Upgrade,
    shutdown: watch::Receiver<bool>,
) -> Result<Outcome> {
    if upgrade.boot_id != boot_id {
        bail!("upgrade is addressed to another Worker process");
    }
    let target = upgrade.version.trim_start_matches('v').to_owned();
    let current = crate::engine::version();
    if !newer_version(&target, &current) {
        log::info!(
            target: "cybion_worker",
            "upgrade target {target} is not newer than {current}; ignoring"
        );
        return Ok(Outcome::Done);
    }
    let apk = config_dir.join("upgrade").join(ASSET);
    let mut last_error = "no release source available".to_owned();
    let mut verified = false;
    for source in asset_sources(&config.controller_url, &upgrade.version) {
        if *shutdown.borrow() {
            return Ok(Outcome::Done);
        }
        match fetch_and_verify(client, &source, &apk).await {
            Ok(()) => {
                log::info!(target: "cybion_worker", "downloaded and verified {source}");
                verified = true;
                break;
            }
            Err(error) => {
                log::warn!(target: "cybion_worker", "release source {source} failed: {error:#}");
                last_error = format!("{error:#}");
            }
        }
    }
    if !verified {
        bail!("could not download a verified APK: {last_error}");
    }
    let signer = platform::apk_signer(&apk).context("cannot read the APK signing certificate")?;
    ensure!(
        signer.eq_ignore_ascii_case(SIGNER_SHA256),
        "APK is signed by an unexpected certificate ({signer})"
    );
    let target_parts = parse_version(&target).context("the upgrade target version is malformed")?;
    let version = platform::apk_version(&apk).context("cannot read the APK version")?;
    let apk_parts = parse_version(&version).context("the downloaded APK version is unknown")?;
    ensure!(
        apk_parts == target_parts,
        "downloaded APK version {version} does not match {target}"
    );
    if *shutdown.borrow() {
        return Ok(Outcome::Done);
    }
    log::info!(target: "cybion_worker", "handing {ASSET} to the system installer");
    platform::install_apk(&apk).context("the package installer rejected the APK")?;
    mark_handed_off(&upgrade.id);
    if let Err(error) = report(client, config, boot_id, &upgrade.id, "installing", None).await {
        log::warn!(target: "cybion_worker", "installing report rejected: {error:#}");
    }
    // The system installer owns the flow from here. Success is only visible
    // as a restarted process reporting the target version, from which the
    // Controller infers completion. Failures that can still surface come
    // through UpgradeInstall; if the confirmation never arrives, report the
    // upgrade as failed so the console can request it again.
    let deadline = tokio::time::Instant::now() + POLL_DEADLINE;
    loop {
        if *shutdown.borrow() {
            return Ok(Outcome::HandedOff);
        }
        let state = platform::install_state().unwrap_or_default();
        if let Some(message) = state.strip_prefix("failed:") {
            bail!("{message}");
        }
        if tokio::time::Instant::now() >= deadline {
            bail!("the system installer is still waiting for confirmation on the device");
        }
        tokio::time::sleep(POLL_INTERVAL).await;
    }
}

/// The Controller mirror first, the official GitHub release second. Mirror
/// failures on restricted networks fall back without user interaction.
pub fn asset_sources(controller_url: &str, version: &str) -> Vec<String> {
    let base = controller_url.trim_end_matches('/');
    vec![
        format!("{base}/worker-release/{version}/{ASSET}"),
        format!("{GITHUB_RELEASE_BASE}/{version}/{ASSET}"),
    ]
}

async fn fetch_and_verify(client: &Client, source: &str, path: &Path) -> Result<()> {
    let checksum = client
        .get(format!("{source}.sha256"))
        .timeout(Duration::from_secs(30))
        .send()
        .await?
        .error_for_status()
        .context("checksum download failed")?
        .text()
        .await?;
    let expected = parse_checksum(&checksum)?;
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let response = client
        .get(source)
        .timeout(Duration::from_secs(600))
        .send()
        .await?
        .error_for_status()
        .context("APK download failed")?;
    let mut file = tokio::fs::File::create(path).await?;
    let mut stream = response.bytes_stream();
    let mut written: u64 = 0;
    let mut logged: u64 = 0;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        file.write_all(&chunk).await?;
        written += chunk.len() as u64;
        if written - logged >= LOG_EVERY {
            logged = written;
            log::info!(target: "cybion_worker", "downloaded {written} bytes from {source}");
        }
    }
    file.sync_all().await?;
    ensure!(written > 0, "downloaded an empty APK");
    verify_file(path, &expected)
}

fn parse_checksum(text: &str) -> Result<String> {
    let digest = text
        .split_whitespace()
        .next()
        .context("checksum file is empty")?;
    ensure!(
        digest.len() == 64 && digest.bytes().all(|byte| byte.is_ascii_hexdigit()),
        "checksum file does not contain a SHA-256 digest"
    );
    Ok(digest.to_ascii_lowercase())
}

fn verify_file(path: &Path, expected: &str) -> Result<()> {
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    let digest = hex::encode(hasher.finalize());
    ensure!(
        digest == expected,
        "checksum mismatch: expected {expected}, got {digest}"
    );
    Ok(())
}

fn parse_version(version: &str) -> Option<(u64, u64, u64)> {
    let parts = version
        .trim_start_matches('v')
        .split('.')
        .map(str::parse::<u64>)
        .collect::<Result<Vec<_>, _>>()
        .ok()?;
    if parts.len() != 3 {
        return None;
    }
    Some((parts[0], parts[1], parts[2]))
}

fn newer_version(target: &str, current: &str) -> bool {
    match (parse_version(target), parse_version(current)) {
        (Some(target), Some(current)) => target > current,
        _ => false,
    }
}

async fn report(
    client: &Client,
    config: &WorkerConfig,
    boot_id: &str,
    id: &str,
    status: &str,
    error: Option<&str>,
) -> Result<()> {
    let url = format!("{}/upgrade", protocol::worker_url(config));
    let payload = json!({"id": id, "status": status, "error": error});
    delivery::post_until_confirmed(client, &config.access_token, boot_id, &url, &payload).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn asset_sources_prefer_the_controller_mirror() {
        let sources = asset_sources("https://cybion.ntnl.io/", "v0.1.8");
        assert_eq!(
            sources,
            vec![
                "https://cybion.ntnl.io/worker-release/v0.1.8/cybion-worker-android-aarch64.apk",
                "https://github.com/zccz14/cybion-worker-for-android/releases/download/v0.1.8/cybion-worker-android-aarch64.apk",
            ]
        );
    }

    #[test]
    fn checksum_parsing_accepts_sha256sum_output_only() {
        let digest = "a".repeat(64);
        assert_eq!(
            parse_checksum(&format!("{digest}  cybion-worker-android-aarch64.apk\n")).unwrap(),
            digest
        );
        assert_eq!(parse_checksum(&"A".repeat(64)).unwrap(), digest);
        assert!(parse_checksum("").is_err());
        assert!(parse_checksum("deadbeef").is_err());
        assert!(parse_checksum(&"g".repeat(64)).is_err());
    }

    #[test]
    fn file_verification_detects_mismatches() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("asset");
        std::fs::write(&path, b"apk-bytes").unwrap();
        let digest = hex::encode(Sha256::digest(b"apk-bytes"));
        verify_file(&path, &digest).unwrap();
        assert!(verify_file(&path, &"0".repeat(64)).is_err());
    }

    #[test]
    fn only_newer_versions_are_considered() {
        assert!(newer_version("0.1.8", "0.1.7"));
        assert!(newer_version("v0.2.0", "0.1.9"));
        assert!(!newer_version("0.1.7", "0.1.7"));
        assert!(!newer_version("0.1.6", "0.1.7"));
        assert!(!newer_version("garbage", "0.1.7"));
        assert!(!newer_version("0.1.8", "garbage"));
    }

    #[test]
    fn the_upgrade_guard_blocks_repeats_until_it_is_cleared() {
        reset();
        assert!(begin("one"));
        assert!(!begin("one"));
        assert!(!begin("two"));
        mark_handed_off("one");
        assert!(!begin("one"));
        clear("one");
        assert!(begin("two"));
        reset();
    }
}
