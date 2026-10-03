use anyhow::{Context, Result, bail, ensure};
use serde_json::{Value, json};

use crate::platform;
use crate::tools::{required_number, required_string, valid_url};

pub async fn run(arguments: &Value) -> Result<Value> {
    let action = required_string(arguments, "action")?;
    match action {
        "click" => {
            let x = required_number(arguments, "x")? as i32;
            let y = required_number(arguments, "y")? as i32;
            gesture(x, y, x, y, 60, "click").await?;
            Ok(json!({"ok": true}))
        }
        "long_press" => {
            let x = required_number(arguments, "x")? as i32;
            let y = required_number(arguments, "y")? as i32;
            gesture(x, y, x, y, 800, "long_press").await?;
            Ok(json!({"ok": true}))
        }
        "move" => {
            bail!("move is not supported on Android Workers; use click, long_press, or swipe")
        }
        "type" => {
            let text = required_string(arguments, "text")?.to_owned();
            let ok = bridge(move || platform::type_text(&text)).await?;
            ensure!(ok, "text input failed: no editable field is focused");
            Ok(json!({"ok": true}))
        }
        "screenshot" => {
            let data = bridge(platform::screenshot).await?;
            ensure!(!data.is_empty(), "screenshot failed");
            Ok(json!({"data": data}))
        }
        "swipe" => swipe(arguments).await,
        "ui_tree" => {
            let tree = bridge(|| platform::ui_tree(400)).await?;
            let value: Value =
                serde_json::from_str(&tree).context("ui_tree returned invalid JSON")?;
            Ok(value)
        }
        "launch" => {
            let package = required_string(arguments, "text")?.to_owned();
            let ok = bridge(move || platform::launch_app(&package)).await?;
            ensure!(ok, "could not launch the requested package");
            Ok(json!({"ok": true}))
        }
        "open" => {
            let url = valid_url(required_string(arguments, "text")?)?;
            let ok = bridge(move || platform::open_url(&url)).await?;
            ensure!(ok, "could not open the requested URL");
            Ok(json!({"ok": true}))
        }
        "key" => {
            let name = required_string(arguments, "text")?.to_owned();
            let value = name.clone();
            let ok = bridge(move || platform::global_action(&value)).await?;
            ensure!(ok, "unknown or failed key: {name}");
            Ok(json!({"ok": true}))
        }
        unknown => bail!("unsupported computer action: {unknown}"),
    }
}

async fn gesture(x1: i32, y1: i32, x2: i32, y2: i32, duration_ms: i32, label: &str) -> Result<()> {
    let ok = bridge(move || platform::gesture(x1, y1, x2, y2, duration_ms)).await?;
    ensure!(
        ok,
        "{label} was not dispatched (is the accessibility service enabled?)"
    );
    Ok(())
}

async fn swipe(arguments: &Value) -> Result<Value> {
    let text = required_string(arguments, "text")?;
    let start = arguments
        .get("x")
        .and_then(Value::as_i64)
        .zip(arguments.get("y").and_then(Value::as_i64))
        .map(|(x, y)| (x as i32, y as i32));
    let (x1, y1, x2, y2, duration) = match text {
        "up" | "down" | "left" | "right" => {
            let (width, height) = bridge(platform::screen_size).await?;
            direction_points(text, width, height, start)
        }
        coordinates => {
            let values: Vec<i32> = coordinates
                .split(',')
                .filter_map(|part| part.trim().parse().ok())
                .collect();
            ensure!(
                values.len() == 4,
                "swipe text must be a direction (up/down/left/right) or four coordinates x1,y1,x2,y2"
            );
            (values[0], values[1], values[2], values[3], 300)
        }
    };
    gesture(x1, y1, x2, y2, duration, "swipe").await?;
    Ok(json!({"ok": true}))
}

fn direction_points(
    direction: &str,
    width: i32,
    height: i32,
    start: Option<(i32, i32)>,
) -> (i32, i32, i32, i32, i32) {
    let (center_x, center_y) = start.unwrap_or((width / 2, height / 2));
    let dx = width / 4;
    let dy = height / 6;
    match direction {
        "up" => (center_x, center_y + dy, center_x, center_y - dy, 300),
        "down" => (center_x, center_y - dy, center_x, center_y + dy, 300),
        "left" => (center_x + dx, center_y, center_x - dx, center_y, 300),
        _ => (center_x - dx, center_y, center_x + dx, center_y, 300),
    }
}

async fn bridge<T: Send + 'static>(task: impl FnOnce() -> Result<T> + Send + 'static) -> Result<T> {
    tokio::task::spawn_blocking(task)
        .await
        .context("platform bridge task panicked")?
}
