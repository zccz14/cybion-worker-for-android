import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val rustDir = rootProject.layout.projectDirectory.dir("../rust").asFile
val jniLibsDir = layout.buildDirectory.dir("rustJniLibs")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    workingDir = rustDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.get().asFile.absolutePath,
        "build", "--release",
    )
    inputs.dir(File(rustDir, "src"))
    inputs.file(File(rustDir, "Cargo.toml"))
    inputs.file(File(rustDir, "Cargo.lock"))
    outputs.dir(jniLibsDir)
}

android {
    namespace = "io.ntnl.cybion.worker"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.ntnl.cybion.worker"
        minSdk = 30
        targetSdk = 35
        // The Cybion console enables worker checks (diagnostics) for workers at
        // 0.1.4 or newer; this app implements the full checks route family, so
        // its version tracks that supported-worker floor.
        versionCode = 5
        versionName = "0.1.7"
    }

    signingConfigs {
        val keystorePath = System.getenv("CYBION_ANDROID_KEYSTORE")
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CYBION_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CYBION_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("CYBION_ANDROID_KEY_PASSWORD")
                // The keystore is generated with openssl as PKCS12; name and type
                // are stated explicitly so no JKS heuristics are involved.
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].jniLibs.srcDir(jniLibsDir)

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}
