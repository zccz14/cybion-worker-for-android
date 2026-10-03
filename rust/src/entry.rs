use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;

use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jstring};

use crate::engine::{self, DeviceInfo};

fn string_of(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(Into::into)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ntnl_cybion_worker_WorkerCore_nativeStart(
    mut env: JNIEnv,
    _this: JObject,
    files_dir: JString,
    device_info: JString,
) -> jboolean {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("cybion-worker"),
    );
    let result = catch_unwind(AssertUnwindSafe(|| -> anyhow::Result<()> {
        let files_dir = string_of(&mut env, &files_dir)
            .ok_or_else(|| anyhow::anyhow!("files dir is missing"))?;
        let device_json = string_of(&mut env, &device_info)
            .ok_or_else(|| anyhow::anyhow!("device info is missing"))?;
        let device: DeviceInfo = serde_json::from_str(&device_json)?;
        let config_dir = PathBuf::from(files_dir);
        std::fs::create_dir_all(&config_dir)?;
        crate::platform::init(&mut env)?;
        engine::start(config_dir, device)
    }));
    match result {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(error)) => {
            log::error!("nativeStart failed: {error}");
            JNI_FALSE
        }
        Err(_) => {
            log::error!("nativeStart panicked");
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ntnl_cybion_worker_WorkerCore_nativeStop(
    _env: JNIEnv,
    _this: JObject,
) {
    let _ = catch_unwind(AssertUnwindSafe(engine::stop));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ntnl_cybion_worker_WorkerCore_nativeStatus(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let payload = match serde_json::to_string(&engine::snapshot()) {
        Ok(payload) => payload,
        Err(error) => serde_json::json!({"error": error.to_string()}).to_string(),
    };
    match env.new_string(payload) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_ntnl_cybion_worker_WorkerCore_nativeReset(
    env: JNIEnv,
    _this: JObject,
    files_dir: JString,
) -> jboolean {
    let mut env = env;
    let result = catch_unwind(AssertUnwindSafe(|| -> anyhow::Result<()> {
        let files_dir = string_of(&mut env, &files_dir)
            .ok_or_else(|| anyhow::anyhow!("files dir is missing"))?;
        engine::reset(&PathBuf::from(files_dir))
    }));
    match result {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(error)) => {
            log::error!("nativeReset failed: {error}");
            JNI_FALSE
        }
        Err(_) => JNI_FALSE,
    }
}
