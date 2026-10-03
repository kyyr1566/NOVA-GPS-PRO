import java.io.File
import java.net.URI
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------------------------
// License configuration (PUBLIC values only – never put a private key anywhere in this project).
// Each value is read from, in order: -P<NAME> on the Gradle command line, the <NAME> environment
// variable (CI), or local.properties (git-ignored, for local development).
//
//   LICENSE_SERVER_BASE_URL  https://… base URL of the license activation server (HTTPS only)
//   LICENSE_PUBLIC_KEY       base64 raw 32-byte Ed25519 PUBLIC key of the license issuer
//   ACTIVATION_PUBLIC_KEY    base64 raw 32-byte Ed25519 PUBLIC key of the activation server
//
// Left empty, the build still compiles but is "not configured": no license can be activated
// (fail closed). Release builds (assemble/bundle/package…Release) REFUSE to build unless all three
// are set and valid, so a release can never ship without real licensing.
// ---------------------------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun licenseSetting(name: String): String =
    listOf(project.findProperty(name) as? String, System.getenv(name), localProps.getProperty(name))
        .firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

val licenseServerBaseUrl = licenseSetting("LICENSE_SERVER_BASE_URL")
val licensePublicKey = licenseSetting("LICENSE_PUBLIC_KEY")
val activationPublicKey = licenseSetting("ACTIVATION_PUBLIC_KEY")

val licenseSettings = linkedMapOf(
    "LICENSE_SERVER_BASE_URL" to licenseServerBaseUrl,
    "LICENSE_PUBLIC_KEY" to licensePublicKey,
    "ACTIVATION_PUBLIC_KEY" to activationPublicKey
)
licenseSettings.forEach { (name, value) ->
    if (!Regex("^[A-Za-z0-9+/=_:.\\-]*$").matches(value))
        throw GradleException("$name contains unsupported characters")
}

fun isEd25519PublicKey(b64: String): Boolean = try {
    Base64.getMimeDecoder().decode(b64.replace('-', '+').replace('_', '/')).size == 32
} catch (e: IllegalArgumentException) { false }

// A production server can never be a loopback / private-network / placeholder host.
fun isNonProductionHost(url: String): Boolean {
    val host = try { URI(url).host?.lowercase() } catch (e: Exception) { null } ?: return true
    return !host.contains('.') || host.startsWith("[") ||
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") ||
        host.endsWith(".invalid") || host.endsWith(".test") || host.endsWith(".example") ||
        Regex("(.+\\.)?example\\.(com|org|net)").matches(host) ||
        Regex("(0\\.0\\.0\\.0|127\\..*|10\\..*|192\\.168\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*|169\\.254\\..*)").matches(host)
}

// ---------------------------------------------------------------------------------------------
// Release signing – the keystore and its passwords are NEVER part of the repository. They are read from
// -P<NAME>, the <NAME> environment variable, or keystore.properties (git-ignored), in that order:
//   NOVA_RELEASE_STORE_FILE  NOVA_RELEASE_STORE_PASSWORD  NOVA_RELEASE_KEY_ALIAS  NOVA_RELEASE_KEY_PASSWORD
// If none is provided the release APK is produced UNSIGNED (it cannot be installed until signed).
// ---------------------------------------------------------------------------------------------
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingSetting(name: String): String =
    listOf(project.findProperty(name) as? String, System.getenv(name), signingProps.getProperty(name))
        .firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

val releaseStoreFile = signingSetting("NOVA_RELEASE_STORE_FILE")
val releaseStorePassword = signingSetting("NOVA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingSetting("NOVA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingSetting("NOVA_RELEASE_KEY_PASSWORD")
val signingValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = signingValues.all { it.isNotEmpty() }
val releaseSigningPartial = !releaseSigningConfigured && signingValues.any { it.isNotEmpty() }

gradle.taskGraph.whenReady {
    if (allTasks.any { Regex("(assemble|bundle|package)Release.*").matches(it.name) }) {
        val problems = mutableListOf<String>()
        if (!licenseServerBaseUrl.startsWith("https://", ignoreCase = true) || licenseServerBaseUrl.length <= 8)
            problems += "LICENSE_SERVER_BASE_URL must be an https:// URL"
        else if (isNonProductionHost(licenseServerBaseUrl))
            problems += "LICENSE_SERVER_BASE_URL must be a real public host (no localhost, private/loopback address or placeholder domain)"
        if (releaseSigningPartial)
            problems += "release signing is partially configured: set all of NOVA_RELEASE_STORE_FILE/_STORE_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD, or none (unsigned build)"
        if (releaseSigningConfigured) {
            val ks = rootProject.file(releaseStoreFile).canonicalFile
            if (!ks.isFile) problems += "NOVA_RELEASE_STORE_FILE does not exist"
            if (ks.path.startsWith(rootProject.projectDir.canonicalPath + File.separator))
                problems += "the signing keystore must live OUTSIDE the project/Git working tree"
        }
        if (!isEd25519PublicKey(licensePublicKey)) problems += "LICENSE_PUBLIC_KEY must be a base64 32-byte Ed25519 public key"
        if (!isEd25519PublicKey(activationPublicKey)) problems += "ACTIVATION_PUBLIC_KEY must be a base64 32-byte Ed25519 public key"
        if (problems.isNotEmpty())
            throw GradleException("Release build refused – license configuration missing/invalid:\n  - " + problems.joinToString("\n  - "))
    }
}

android {
    namespace = "com.nova.gpspro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.gpspro"
        minSdk = 30          // Android 11
        targetSdk = 35       // Android 15+ (runs on 16)
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en", "ar")

        buildConfigField("String", "LICENSE_SERVER_BASE_URL", "\"$licenseServerBaseUrl\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licensePublicKey\"")
        buildConfigField("String", "ACTIVATION_PUBLIC_KEY", "\"$activationPublicKey\"")
    }

    buildFeatures { buildConfig = true }   // carries the three license settings above (public values only)

    signingConfigs {
        if (releaseSigningConfigured) create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")   // offline QR encode/decode (pure Java)
    testImplementation("junit:junit:4.13.2")
}
