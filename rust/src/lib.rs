// The Android JNI entry points (`entry`) are the only production consumers of
// these modules; host builds exist to run the unit tests.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

mod bash;
mod computer;
mod config;
mod delivery;
mod engine;
mod pairing;
mod platform;
mod protocol;
mod resources;
mod state;
mod tools;
mod upgrade;

#[cfg(target_os = "android")]
mod entry;
