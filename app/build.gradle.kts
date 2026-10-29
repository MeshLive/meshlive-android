import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Optional 32-bit ARM build (default OFF, the shipped ABI set is unchanged): -PenableArmeabiV7a=true
// adds armeabi-v7a so phones whose ro.product.cpu.abilist lacks arm64-v8a (32-bit userspace such as
// some Android Go builds) can install. Cost and caveats: see NativeCompat.kt (rule native-abi-coverage).
val enableArmeabiV7a = (project.findProperty("enableArmeabiV7a") as String?)?.toBoolean() == true

// Per-ABI release APKs (tools/make_release.py passes -PsplitAbis=true -PenableArmeabiV7a=true; default OFF, so
// debug and benchmark builds stay one APK). Splits produce arm64-v8a, armeabi-v7a and a universal APK, each with
// its own versionCode = versionCode * 10 + ABI digit (the F-Droid/Signal convention; ARCHITECTURE "Distribution and releases").
val splitAbis = (project.findProperty("splitAbis") as String?)?.toBoolean() == true
val universalApk = (project.findProperty("universalApk") as String?)?.toBoolean() != false
val abiVersionDigits = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)

// RENAME POINT (one line): the installed application id. The Kotlin package and `namespace` below stay
// com.example.bluetoothtest on purpose (renaming them is not needed for a new id). Override for a trial build
// with -PappId=<id>. A new id is a different app to Android: it cannot update an install of the old one.
val appId = (project.findProperty("appId") as String?) ?: "io.github.meshlive.app"

// Optional native-build overrides, so a Linux or F-Droid build needs no machine-specific path:
// -PaomSourceDir=<libaom checkout at the pinned commit>, -PperlExecutable=<perl>.
// Without them: reference-repos/libaom, and Git for Windows' perl only when it exists (Windows hosts).
val aomSourceDir = project.findProperty("aomSourceDir") as String?
val perlExecutable = (project.findProperty("perlExecutable") as String?)
    ?: file("C:/Program Files/Git/usr/bin/perl.exe").takeIf { it.isFile }?.path

android {
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    namespace = "com.example.bluetoothtest"
    compileSdk = 36

    // Distribution flavours (SWITCHES "Check for updates"): `github` is what make_release.py builds and ships on the
    // release page; it holds the opt-in updater (online check, download, PackageInstaller) and declares
    // REQUEST_INSTALL_PACKAGES. `fdroid` is what the F-Droid recipe builds: no updater code runs, no
    // install permission, no network request of its own. `play` is the Google Play build (ARCHITECTURE "Distribution and releases"): Play
    // updates it, so no updater and no install permission. All three offer "Share app nearby" with the same split-install
    // refusal: outbound sharing is not self-updating (docs/PLAY_RELEASE.md). Play omits the `location` foreground-service
    // type pending a supported justification for the Android 10-11 background-scan use. All three carry the
    // "newer version nearby" notice and share one application id.
    // Build commands name the flavour: assembleGithubDebug, assembleGithubRelease, assembleFdroidRelease, bundlePlayRelease.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER_AVAILABLE", "true")
            buildConfigField("String", "DISTRIBUTION", "\"github\"")
            buildConfigField("boolean", "APP_SHARE_AVAILABLE", "true")
            buildConfigField("boolean", "LOCATION_FGS_AVAILABLE", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER_AVAILABLE", "false")
            buildConfigField("String", "DISTRIBUTION", "\"fdroid\"")
            buildConfigField("boolean", "APP_SHARE_AVAILABLE", "true")
            buildConfigField("boolean", "LOCATION_FGS_AVAILABLE", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER_AVAILABLE", "false")
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
            buildConfigField("boolean", "APP_SHARE_AVAILABLE", "true")
            buildConfigField("boolean", "LOCATION_FGS_AVAILABLE", "false")
        }
    }

    defaultConfig {
        applicationId = appId
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.4.4-beta"
        // versionCode/versionName are literals: no date, git hash or counter, so a rebuild of a commit gets
        // the same version (the make_release.py and fastlane changelog readers parse these two lines).

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AGP rejects ndk.abiFilters together with splits.abi; the split configuration below picks the ABIs.
        if (!splitAbis) {
            ndk {
                abiFilters += "arm64-v8a"
                if (enableArmeabiV7a) abiFilters += "armeabi-v7a"
            }
        }
        // Read by NativeCompat for its BENCHMARK native_compat line: the ABIs this build configuration
        // produces (a split APK carries only its own).
        buildConfigField(
            "String",
            "NATIVE_ABIS",
            if (enableArmeabiV7a) "\"arm64-v8a,armeabi-v7a\"" else "\"arm64-v8a\"",
        )

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                perlExecutable?.let { arguments += "-DPERL_EXECUTABLE=$it" }
                aomSourceDir?.let { arguments += "-DAOM_SOURCE_DIR=$it" }
            }
        }
    }

    if (splitAbis) {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a")
                isUniversalApk = universalApk
            }
        }
    }

    // F-Droid and reproducible builds: no Google-encrypted dependency block in the APK signing block
    // (vcsInfo is switched off in the release build type below: AGP 9 otherwise writes
    // META-INF/version-control-info.textproto, whose content depends on how the source was checked out).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release signing key lives outside the repo (never commit it). Path override:
    // -PreleaseKeystoreProperties=<file>; default ~/.bluetoothtest-release/keystore.properties.
    val releaseKeystoreProperties = Properties().apply {
        val file = (project.findProperty("releaseKeystoreProperties") as String?)?.let(::file)
            ?: file("${System.getProperty("user.home")}/.bluetoothtest-release/keystore.properties")
        if (file.isFile) file.inputStream().use { stream -> load(stream) }
    }
    // Google Play upload key (ARCHITECTURE "Distribution and releases"), separate from the release key above so a Play bundle is never signed
    // with the GitHub/F-Droid key by accident. Path override: -PplayUploadKeystoreProperties=<file>; default
    // ~/.meshlive-play-upload/keystore.properties. Without it bundlePlayRelease is unsigned (sign it before uploading).
    val playUploadKeystoreProperties = Properties().apply {
        val file = (project.findProperty("playUploadKeystoreProperties") as String?)?.let(::file)
            ?: file("${System.getProperty("user.home")}/.meshlive-play-upload/keystore.properties")
        if (file.isFile) file.inputStream().use { stream -> load(stream) }
    }
    signingConfigs {
        for ((name, properties) in listOf("release" to releaseKeystoreProperties, "playUpload" to playUploadKeystoreProperties)) {
            if (properties.getProperty("storeFile") == null) continue
            create(name) {
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }
    // Release signing is chosen per flavour (the release build type sets none, so the flavour's applies; debug keeps
    // the debug key): github and fdroid use the release key, play only the upload key.
    productFlavors {
        getByName("github") { signingConfigs.findByName("release")?.let { signingConfig = it } }
        getByName("fdroid") { signingConfigs.findByName("release")?.let { signingConfig = it } }
        getByName("play") { signingConfigs.findByName("playUpload")?.let { signingConfig = it } }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                // Release only: strips Log.v/d/i. Debug is not minified and keeps every log line.
                "proguard-release-logs.pro",
            )
            // Reproducible builds: AGP 9 puts META-INF/version-control-info.textproto into release APKs by default.
            // A normal git clone (F-Droid's buildserver) writes the commit hash, a git worktree or source export
            // writes NO_VALID_GIT_FOUND, so the 0.4.1-beta APKs could not match an F-Droid build byte for byte.
            vcsInfo {
                include = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Built-in Kotlin (AGP 9) replaces the kotlin-android plugin and kotlinOptions: jvmTarget now follows
    // compileOptions.targetCompatibility (11), the value kotlinOptions set before.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

androidComponents {
    onVariants { variant ->
        // Google Play takes one bundle per release (Play makes the per-ABI APKs itself), so the play release's
        // versionCode is the universal one, versionCode * 10 + 0: the same release number (versionCode / 10,
        // ReleaseVersion) as the GitHub APKs of that release, and strictly increasing per release as Play requires.
        if (variant.flavorName == "play" && variant.buildType == "release") {
            variant.outputs.forEach { output -> output.versionCode.get()?.let { output.versionCode.set(it * 10) } }
            return@onVariants
        }
        if (!splitAbis) return@onVariants
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val base = output.versionCode.get() ?: return@forEach
            output.versionCode.set(base * 10 + (abiVersionDigits[abi] ?: 0))
        }
    }
}

// With product flavours AGP no longer defines `testDebugUnitTest` (the name becomes ambiguous), but that is the one
// command docs, tools (tools/refresh_public_export.py) and every agent use. This alias keeps it: the github flavour's
// unit tests run once (main code is shared), and the fdroid flavour must still compile with its tests. A single
// class: `:app:testGithubDebugUnitTest --tests <class>`.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Unit tests of the github flavour (debug) and a compile check of the fdroid and play flavours' tests."
    dependsOn("testGithubDebugUnitTest", "compileFdroidDebugUnitTestKotlin", "compilePlayDebugUnitTestKotlin")
}

// Every resolved configuration is locked in app/gradle.lockfile (ARCHITECTURE "Build notes", toolchain pins).
dependencyLocking {
    lockAllConfigurations()
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.camera.viewfinder.view)
    // QR verification codes (Apache-2.0; bundles ZXing core). Runs fully offline.
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
