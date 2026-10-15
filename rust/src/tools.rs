use std::time::Duration;

use anyhow::{Context, Result, bail, ensure};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde_json::{Value, json};
use tokio::sync::watch;

use crate::bash;
use crate::computer;
use crate::platform;
use crate::protocol::ToolCall;

pub const MAX_TOOL_OUTPUT_BYTES: usize = 2 * 1024 * 1024;
const MAX_VIEW_IMAGE_BYTES: usize = 4 * 1024 * 1024;

pub async fn execute_call(call: &ToolCall, cancel: watch::Receiver<bool>) -> Result<Value> {
    match call.name.as_str() {
        "diagnostics" => diagnostics().await,
        "bash" => bash::run(&call.arguments, cancel).await,
        "computer_use" => computer::run(&call.arguments).await,
        "view_image" => view_image(&call.arguments).await,
        "browser_control" => bail!("browser_control is not supported on Android Workers"),
        unknown => bail!("unsupported Worker tool: {unknown}"),
    }
}

pub async fn diagnostics() -> Result<Value> {
    let (_cancel_sender, cancel_receiver) = watch::channel(false);
    let shell = match tokio::time::timeout(
        Duration::from_secs(5),
        bash::run(
            &json!({"command": "echo cybion-worker-check"}),
            cancel_receiver,
        ),
    )
    .await
    {
        Ok(Ok(result))
            if result["exit_code"] == 0
                && result["stdout"]
                    .as_str()
                    .is_some_and(|value| value.trim() == "cybion-worker-check") =>
        {
            json!({"status": "ready", "detail": "shell_ok"})
        }
        _ => json!({"status": "failed", "detail": "shell_failed"}),
    };
    let accessibility = platform::accessibility_enabled().unwrap_or(false);
    let desktop = json!({
        "status": "not_checked",
        "detail": if accessibility { "accessibility_enabled" } else { "accessibility_disabled" },
    });
    let browser = json!({"status": "missing_dependency", "detail": "no_browser"});
    Ok(json!({
        "shell": shell,
        "browser": browser,
        "desktop": desktop,
        "platform": "android",
        "arch": std::env::consts::ARCH,
        "version": crate::engine::version(),
    }))
}

/// Reads one image file into the conversation. The Controller stores the bytes
/// as an object and replays them as an `input_image`; the result carries an
/// explicit `media_type`, so neither side classifies images by content shape.
async fn view_image(arguments: &Value) -> Result<Value> {
    let path = required_string(arguments, "path")?;
    let bytes = tokio::fs::read(path)
        .await
        .with_context(|| format!("view_image cannot read {path}"))?;
    ensure!(
        bytes.len() <= MAX_VIEW_IMAGE_BYTES,
        "view_image supports images up to 4 MiB, but {path} is {} bytes; resize it first",
        bytes.len()
    );
    let media_type = image_media_type(&bytes)
        .with_context(|| format!("{path} is not a PNG, JPEG, WebP, or GIF image"))?;
    Ok(json!({"data": STANDARD.encode(&bytes), "media_type": media_type}))
}

/// The whitelisted image formats, recognized by magic number only: PNG, JPEG,
/// GIF, WebP.
fn image_media_type(bytes: &[u8]) -> Option<&'static str> {
    const PNG: &[u8] = b"\x89PNG\r\n\x1a\n";
    if bytes.starts_with(PNG) {
        return Some("image/png");
    }
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some("image/jpeg");
    }
    if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        return Some("image/gif");
    }
    if bytes.starts_with(b"RIFF") && bytes.len() >= 12 && &bytes[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    None
}

pub fn required_string<'a>(arguments: &'a Value, key: &str) -> Result<&'a str> {
    arguments
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .with_context(|| format!("{key} is required"))
}

pub fn required_number(arguments: &Value, key: &str) -> Result<i64> {
    arguments
        .get(key)
        .and_then(Value::as_i64)
        .with_context(|| format!("{key} is required"))
}

pub fn valid_url(value: &str) -> Result<String> {
    let url = url::Url::parse(value).context("url is invalid")?;
    ensure!(
        matches!(url.scheme(), "http" | "https"),
        "url must use HTTP or HTTPS"
    );
    Ok(url.into())
}

pub fn command_output(code: Option<i32>, stdout: &[u8], stderr: &[u8]) -> Value {
    json!({
        "exit_code": code,
        "stdout": limited_output(stdout),
        "stderr": limited_output(stderr),
    })
}

fn limited_output(value: &[u8]) -> String {
    let value = &value[..value.len().min(MAX_TOOL_OUTPUT_BYTES)];
    String::from_utf8_lossy(value).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn view_image_sniffs_only_whitelisted_formats() {
        assert_eq!(
            image_media_type(b"\x89PNG\r\n\x1a\nrest"),
            Some("image/png")
        );
        assert_eq!(
            image_media_type(&[0xFF, 0xD8, 0xFF, 0xE0]),
            Some("image/jpeg")
        );
        assert_eq!(image_media_type(b"GIF87a1234"), Some("image/gif"));
        assert_eq!(image_media_type(b"GIF89a1234"), Some("image/gif"));
        assert_eq!(
            image_media_type(b"RIFF\x00\x00\x00\x00WEBPVP8 "),
            Some("image/webp")
        );
        assert_eq!(image_media_type(b"RIFF\x00\x00\x00\x00WAVEfmt "), None);
        assert_eq!(image_media_type(b"plain text"), None);
        assert_eq!(image_media_type(b""), None);
    }

    #[tokio::test]
    async fn view_image_reads_files_and_rejects_other_input() {
        let dir = tempfile::tempdir().unwrap();
        let png = dir.path().join("capture.png");
        tokio::fs::write(&png, b"\x89PNG\r\n\x1a\npayload")
            .await
            .unwrap();
        let result = view_image(&json!({"path": png})).await.unwrap();
        assert_eq!(result["media_type"], "image/png");
        assert_eq!(result["data"], STANDARD.encode(b"\x89PNG\r\n\x1a\npayload"));

        let text = dir.path().join("notes.txt");
        tokio::fs::write(&text, b"not an image").await.unwrap();
        let error = view_image(&json!({"path": text})).await.unwrap_err();
        assert!(
            error
                .to_string()
                .contains("is not a PNG, JPEG, WebP, or GIF image"),
            "{error}"
        );

        let large = dir.path().join("large.png");
        let mut bytes = b"\x89PNG\r\n\x1a\n".to_vec();
        bytes.resize(MAX_VIEW_IMAGE_BYTES + 1, 0);
        tokio::fs::write(&large, &bytes).await.unwrap();
        let error = view_image(&json!({"path": large})).await.unwrap_err();
        assert!(error.to_string().contains("up to 4 MiB"), "{error}");
    }
}
