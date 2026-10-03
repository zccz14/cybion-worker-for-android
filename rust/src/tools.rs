use std::time::Duration;

use anyhow::{Context, Result, bail, ensure};
use serde_json::{Value, json};
use tokio::sync::watch;

use crate::bash;
use crate::computer;
use crate::platform;
use crate::protocol::ToolCall;

pub const MAX_TOOL_OUTPUT_BYTES: usize = 2 * 1024 * 1024;

pub async fn execute_call(call: &ToolCall, cancel: watch::Receiver<bool>) -> Result<Value> {
    match call.name.as_str() {
        "diagnostics" => diagnostics().await,
        "bash" => bash::run(&call.arguments, cancel).await,
        "computer_use" => computer::run(&call.arguments).await,
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
