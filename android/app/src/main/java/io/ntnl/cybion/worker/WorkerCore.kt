package io.ntnl.cybion.worker

/**
 * JNI surface of the Rust worker core. The native side owns a single engine;
 * every call is safe to repeat.
 */
object WorkerCore {
    init {
        System.loadLibrary("cybion_worker_android")
    }

    /** Starts the worker engine; false means the native side rejected the start. */
    external fun nativeStart(filesDir: String, deviceInfoJson: String): Boolean

    /** Requests shutdown and waits briefly for the engine thread to exit. */
    external fun nativeStop()

    /** Returns the serialized status snapshot rendered by the UI. */
    external fun nativeStatus(): String

    /** Deletes the stored configuration and pending pairing; only while stopped. */
    external fun nativeReset(filesDir: String): Boolean
}
