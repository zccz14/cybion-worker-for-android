use anyhow::{Result, bail};

fn unavailable<T>() -> Result<T> {
    bail!("the platform bridge is only available on Android")
}

pub fn gesture(_x1: i32, _y1: i32, _x2: i32, _y2: i32, _duration_ms: i32) -> Result<bool> {
    unavailable()
}

pub fn type_text(_text: &str) -> Result<bool> {
    unavailable()
}

pub fn screenshot() -> Result<String> {
    unavailable()
}

pub fn ui_tree(_max_nodes: i32) -> Result<String> {
    unavailable()
}

pub fn launch_app(_package: &str) -> Result<bool> {
    unavailable()
}

pub fn open_url(_url: &str) -> Result<bool> {
    unavailable()
}

pub fn global_action(_name: &str) -> Result<bool> {
    unavailable()
}

pub fn screen_size() -> Result<(i32, i32)> {
    unavailable()
}

pub fn accessibility_enabled() -> Result<bool> {
    Ok(false)
}

pub fn apk_signer(_path: &std::path::Path) -> Result<String> {
    unavailable()
}

pub fn apk_version(_path: &std::path::Path) -> Result<String> {
    unavailable()
}

pub fn install_apk(_path: &std::path::Path) -> Result<()> {
    unavailable()
}

pub fn install_state() -> Result<String> {
    unavailable()
}
