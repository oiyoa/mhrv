import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import com.therealaleph.mhrv.SecretHasher
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Encrypts raw secrets into the blob format expected by SecretsManager.kt.
 * PBKDF2-HMAC-SHA256 (600k iterations) + AES-256-GCM.
 */
fun encryptSecrets(scriptIds: List<String>, authKey: String, password: String): String {
    val json = """{"script_ids":[${scriptIds.joinToString(",") { "\"$it\"" }}],"auth_key":"$authKey"}"""
    val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
    val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }

    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(password.toCharArray(), salt, 600000, 256)
    val tmp = factory.generateSecret(spec)
    val secret = SecretKeySpec(tmp.encoded, "AES")

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
    val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

    val blob = salt + iv + ciphertext
    return Base64.getEncoder().encodeToString(blob)
}

// Embedded secrets.
val localProperties = Properties()
val localPropertiesFile = project.rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

// Pull from env (CI) or local.properties (Dev)
val rawIds = System.getenv("MHRV_SCRIPT_IDS") ?: localProperties.getProperty("mhrv.script_ids") ?: ""
val rawKey = System.getenv("MHRV_AUTH_KEY") ?: localProperties.getProperty("mhrv.auth_key") ?: ""
val rawPwd = System.getenv("MHRV_PASSWORD") ?: localProperties.getProperty("mhrv.password") ?: ""

var secretsBlob = ""
var secretsHash = ""

if (rawIds.isNotEmpty() && rawKey.isNotEmpty() && rawPwd.isNotEmpty()) {
    val ids = rawIds.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
    secretsBlob = encryptSecrets(ids, rawKey, rawPwd)
    secretsHash = SecretHasher.calculateHash(ids, rawKey)

    println("BUILD: Encrypting embedded secrets for BuildConfig (hash: ${secretsHash.take(8)}...)")
}

android {
    namespace = "com.therealaleph.mhrv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.therealaleph.mhrv"
        minSdk = 24 // Android 7.0 — covers 99%+ of live devices.
        targetSdk = 34
        versionCode = 160
        versionName = "1.9.28"

        // Ship all four mainstream Android ABIs:
        //   - arm64-v8a      — 95%+ of real-world Android phones since 2019
        //   - armeabi-v7a    — older/cheaper devices still on 32-bit ARM
        //   - x86_64         — Android emulator on Intel Macs + Chromebooks
        //   - x86            — legacy 32-bit Intel emulator; cheap to include
        // Per-ABI .so files push the APK up to ~50 MB, but users expect one
        // APK that Just Works rather than "pick the right ABI" which nobody
        // does correctly. Google Play would auto-split; we ship universal.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        buildConfigField("String", "ENCRYPTED_SECRETS", "\"$secretsBlob\"")
        buildConfigField("String", "SECRETS_HASH", "\"$secretsHash\"")
    }

    // Generate the hashing utility into the build folder so the app can use the 
    // exact same logic as the build script without needing buildSrc.
    val genPath = layout.buildDirectory.dir("generated/source/mhrv/main")
    val generateSecretHasher = tasks.register("generateSecretHasher") {
        val inputFile = project.rootProject.file("buildSrc/src/main/kotlin/com/therealaleph/mhrv/SecretHasher.kt")
        val outputFile = genPath.get().file("com/therealaleph/mhrv/SecretHasher.kt").asFile
        inputs.file(inputFile)
        outputs.file(outputFile)
        doLast {
            outputFile.parentFile.mkdirs()
            outputFile.writeText(inputFile.readText())
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs(genPath)
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Ensure the file is generated before compilation
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        dependsOn(generateSecretHasher)
    }

    signingConfigs {
        create("release") {
            // Committed keystore — fixed signature across machines and
            // across CI runs. Using the auto-generated debug keystore
            // (as v1.0.0 / v1.0.1 did) makes every release APK fail to
            // install over the previous one with
            // INSTALL_FAILED_UPDATE_INCOMPATIBLE, because Android treats
            // a signature change as "different app": the user has to
            // uninstall first. That's awful UX.
            //
            // The password is in plaintext because this is an
            // open-source project without Play Store identity. A
            // forked/rebuilt APK signed with a different key is
            // fundamentally a different install path anyway — the
            // protection model here is "trust the source tree you
            // pulled from," not "trust that we hold a key you can't
            // see." If you're forking, generate your own key, commit
            // it, and ship.
            storeFile = file("release.jks")
            storePassword = "mhrv-rs-release"
            keyAlias = "mhrv-rs"
            keyPassword = "mhrv-rs-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Per-ABI APK splits in addition to the universal APK.
    //
    // Issue #136: GitHub Releases is filtered from inside IR, and the
    // universal APK (~50 MB, all four ABIs bundled) is the bottleneck —
    // users on slow or unstable censorship-tunnel paths often can't
    // pull down 50 MB reliably. Per-ABI APKs are ~15 MB each (only one
    // copy of libmhrv_rs.so + libtun2proxy.so instead of four), which
    // is small enough to succeed where the universal fails.
    //
    // Keeping the universal APK too (`isUniversalApk = true`) because
    // existing download paths / docs / Telegram mirrors all reference
    // the universal name — removing it would break every link in the
    // wild. The per-ABI outputs are additive.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
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
        compose = true
        buildConfig = true
    }

    // libmhrv_rs.so is produced by `cargo ndk` in the repo root and dropped
    // under app/src/main/jniLibs/<abi>/. The cargoBuild task below runs
    // that before each assembleDebug / assembleRelease.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    // AppCompatDelegate.setApplicationLocales is the only thing we need
    // out of AppCompat — lets us flip the whole app locale at runtime
    // from MhrvApp.onCreate without touching every composable.
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Compose UI.
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // QR code generation + scanning (self-contained, no ML Kit needed).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Secure Storage & Encryption (Phase 1)
    implementation("com.google.crypto.tink:tink-android:1.15.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// --------------------------------------------------------------------------
// Cross-compile the Rust crate to arm64 Android and drop the .so into the
// place Android's packager looks. We hand the work off to `cargo ndk` which
// wraps the right CC / AR / linker env vars for us.
//
// This ties to the `assemble*` task so every debug/release build triggers
// a `cargo ndk` — no manual step. In CI we'd cache the target/ dir to
// avoid full rebuilds.
// --------------------------------------------------------------------------
val rustCrateDir = rootProject.projectDir.parentFile
val jniLibsDir = file("src/main/jniLibs")

/**
 * Locate the `cargo` executable on the host system.
 */
fun resolveCargoExecutable(): String {
    val candidates = buildList {
        System.getenv("CARGO")?.takeIf { it.isNotBlank() }?.let(::add)
        System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }?.let { add("$it/bin/cargo") }
        add("${System.getProperty("user.home")}/.cargo/bin/cargo")
        add("/opt/homebrew/bin/cargo")
        add("/usr/local/bin/cargo")
    }
    val found = candidates.firstOrNull { file(it).canExecute() }
    return found ?: throw GradleException("Could not locate Cargo.")
}

val cargoExecutable = resolveCargoExecutable()

// After cargo-ndk dumps artifacts into each jniLibs/<abi>/ dir, the
// tun2proxy cdylib lands as `libtun2proxy-<hash>.so` (rustc's deps/ naming
// convention, because tun2proxy is a transitive dep not a root crate).
// Android's System.loadLibrary expects a stable name, and the hash changes
// between builds, so we normalize it to `libtun2proxy.so` in every ABI dir.
// Also deletes any stale hash-suffixed copies from previous builds.
fun normalizeTun2proxySo() {
    val jniLibsRoot = file("src/main/jniLibs")
    if (!jniLibsRoot.isDirectory) return
    jniLibsRoot.listFiles()?.filter { it.isDirectory }?.forEach { abiDir ->
        val hashed = abiDir.listFiles { f -> f.name.matches(Regex("libtun2proxy-[0-9a-f]+\\.so")) }
            ?: emptyArray()
        val newest = hashed.maxByOrNull { it.lastModified() }
        if (newest != null) {
            val target = abiDir.resolve("libtun2proxy.so")
            if (target.exists()) target.delete()
            newest.copyTo(target, overwrite = true)
        }
        hashed.forEach { it.delete() }
    }
}

// All ABIs we ship. Keep in sync with `android.defaultConfig.ndk.abiFilters`
// above; if these drift, the APK either includes .so files with no matching
// ABI entry (dead weight) or advertises ABIs with no .so (runtime
// UnsatisfiedLinkError on devices that pick that split).
val androidAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

tasks.register<Exec>("cargoBuildDebug") {
    group = "build"
    // Intentionally ALWAYS uses --release. The Rust debug build is 80+MB
    // of unoptimized object code vs 3MB with release; the 20x APK bloat is
    // never worth it just for a Rust stack trace you wouldn't see in
    // logcat anyway. If you need Rust debug symbols, temporarily drop
    // `--release` below and accept the APK size.
    description = "Cross-compile mhrv_rs for all ABIs (release — same as cargoBuildRelease)"
    workingDir = rustCrateDir
    commandLine(buildList<String> {
        add(cargoExecutable); add("ndk")
        androidAbis.forEach { add("-t"); add(it) }
        add("-o"); add(jniLibsDir.absolutePath)
        add("build"); add("--release")
    })
    doLast { normalizeTun2proxySo() }
}

tasks.register<Exec>("cargoBuildRelease") {
    group = "build"
    description = "Cross-compile mhrv_rs for all ABIs (release)"
    workingDir = rustCrateDir
    commandLine(buildList<String> {
        add(cargoExecutable); add("ndk")
        androidAbis.forEach { add("-t"); add(it) }
        add("-o"); add(jniLibsDir.absolutePath)
        add("build"); add("--release")
    })
    doLast { normalizeTun2proxySo() }
}

// Hook the right cargo task in front of each Android build variant.
tasks.configureEach {
    when (name) {
        "mergeDebugJniLibFolders" -> dependsOn("cargoBuildDebug")
        "mergeReleaseJniLibFolders" -> dependsOn("cargoBuildRelease")
    }
}
