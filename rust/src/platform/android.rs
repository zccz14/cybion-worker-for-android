use std::path::Path;
use std::sync::OnceLock;

use anyhow::{Context, Result, bail};
use jni::objects::{GlobalRef, JClass, JString, JValue, JValueGen};
use jni::{JNIEnv, JavaVM};

static VM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGE: OnceLock<GlobalRef> = OnceLock::new();

/// Called from `WorkerCore.nativeStart` while a Java frame is active, so
/// `find_class` resolves through the application class loader.
pub fn init(env: &mut JNIEnv) -> Result<()> {
    let vm = env.get_java_vm()?;
    let _ = VM.set(vm);
    let class = env.find_class("io/ntnl/cybion/worker/PlatformBridge")?;
    let _ = BRIDGE.set(env.new_global_ref(class)?);
    Ok(())
}

fn with_env<R>(task: impl FnOnce(&mut JNIEnv) -> Result<R>) -> Result<R> {
    let vm = VM.get().context("the platform bridge is not initialized")?;
    let mut env = vm.attach_current_thread()?;
    task(&mut env)
}

fn bridge_class() -> Result<JClass<'static>> {
    let bridge = BRIDGE
        .get()
        .context("the platform bridge is not initialized")?;
    // SAFETY: `BRIDGE` holds a JVM global reference, which stays valid for the
    // life of the process; wrapping it for a call does not take ownership.
    Ok(unsafe { JClass::from_raw(bridge.as_obj().as_raw() as jni::sys::jclass) })
}

fn call_bool(name: &str, signature: &str, args: &[JValue]) -> Result<bool> {
    with_env(|env| {
        let result = env.call_static_method(bridge_class()?, name, signature, args)?;
        match result {
            JValueGen::Bool(value) => Ok(value != 0),
            _ => bail!("{name} returned an unexpected value"),
        }
    })
}

fn call_string(name: &str, signature: &str, args: &[JValue]) -> Result<String> {
    with_env(|env| {
        let result = env.call_static_method(bridge_class()?, name, signature, args)?;
        let JValueGen::Object(object) = result else {
            bail!("{name} returned an unexpected value");
        };
        let text = JString::from(object);
        Ok(env.get_string(&text)?.into())
    })
}

fn call_string_with_string(name: &str, value: &str) -> Result<String> {
    with_env(|env| {
        let argument = env.new_string(value)?;
        let result = env.call_static_method(
            bridge_class()?,
            name,
            "(Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(argument.as_ref())],
        )?;
        let JValueGen::Object(object) = result else {
            bail!("{name} returned an unexpected value");
        };
        let text = JString::from(object);
        Ok(env.get_string(&text)?.into())
    })
}

fn call_bool_with_string(name: &str, value: &str) -> Result<bool> {
    with_env(|env| {
        let argument = env.new_string(value)?;
        let result = env.call_static_method(
            bridge_class()?,
            name,
            "(Ljava/lang/String;)Z",
            &[JValue::Object(argument.as_ref())],
        )?;
        match result {
            JValueGen::Bool(flag) => Ok(flag != 0),
            _ => bail!("{name} returned an unexpected value"),
        }
    })
}

pub fn gesture(x1: i32, y1: i32, x2: i32, y2: i32, duration_ms: i32) -> Result<bool> {
    call_bool(
        "gesture",
        "(IIIII)Z",
        &[
            JValue::Int(x1),
            JValue::Int(y1),
            JValue::Int(x2),
            JValue::Int(y2),
            JValue::Int(duration_ms),
        ],
    )
}

pub fn type_text(text: &str) -> Result<bool> {
    call_bool_with_string("typeText", text)
}

pub fn screenshot() -> Result<String> {
    call_string("screenshot", "()Ljava/lang/String;", &[])
}

pub fn ui_tree(max_nodes: i32) -> Result<String> {
    call_string("uiTree", "(I)Ljava/lang/String;", &[JValue::Int(max_nodes)])
}

pub fn launch_app(package: &str) -> Result<bool> {
    call_bool_with_string("launchApp", package)
}

pub fn open_url(url: &str) -> Result<bool> {
    call_bool_with_string("openUrl", url)
}

pub fn global_action(name: &str) -> Result<bool> {
    call_bool_with_string("globalAction", name)
}

pub fn screen_size() -> Result<(i32, i32)> {
    let text = call_string("screenSize", "()Ljava/lang/String;", &[])?;
    let (width, height) = text.split_once(',').context("screen size is malformed")?;
    Ok((width.trim().parse()?, height.trim().parse()?))
}

pub fn accessibility_enabled() -> Result<bool> {
    call_bool("accessibilityEnabled", "()Z", &[])
}

/// SHA-256 of the APK signing certificate (lowercase hex), or an empty
/// string when the archive cannot be inspected.
pub fn apk_signer(path: &Path) -> Result<String> {
    call_string_with_string("apkSigner", &path.to_string_lossy())
}

pub fn apk_version(path: &Path) -> Result<String> {
    call_string_with_string("apkVersion", &path.to_string_lossy())
}

/// Hands the verified APK to the system installer; an empty bridge reply
/// means the installer was engaged (its UI or the fallback notification).
pub fn install_apk(path: &Path) -> Result<()> {
    let error = call_string_with_string("installApk", &path.to_string_lossy())?;
    if error.is_empty() {
        Ok(())
    } else {
        bail!("{error}")
    }
}

/// Installer progress reported by the shell: `idle`, `user_action`, or
/// `failed:<message>`.
pub fn install_state() -> Result<String> {
    call_string("installState", "()Ljava/lang/String;", &[])
}
