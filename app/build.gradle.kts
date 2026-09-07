import java.net.URI
import java.net.URISyntaxException
import java.io.FileInputStream
import java.util.Locale
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

abstract class GenerateHuaweiManifestOverlayTask : DefaultTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writer().use { outputWriter ->
                outputWriter.write(
                """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:allowBackup="true"
        tools:replace="android:allowBackup" />
</manifest>
""".trimIndent()
                )
            }
        }
    }
}

abstract class VerifyBackendBaseUrlContractTask : DefaultTask() {
    @TaskAction
    fun verify() {
        val rejectedValues = listOf(
            "",
            "http://api.example",
            "https://",
            "https://[",
            "https://api.example:65536",
            "https://api.example:99999",
            "HTTPS://ToDo.Example"
        )
        rejectedValues.forEach { candidate ->
            check(runCatching { BackendUrlContract.validate(candidate) }.isFailure) {
                "Release backend URL regression: invalid value was accepted: '$candidate'"
            }
        }

        check(BackendUrlContract.resolve("", "https://env.example").isEmpty()) {
            "Release backend URL regression: explicit empty value fell back to BACKEND_BASE_URL."
        }
        check(BackendUrlContract.resolve("https://explicit.example///", "http://env.example") ==
            "https://explicit.example") {
            "Release backend URL regression: explicit property did not take precedence."
        }
        check(BackendUrlContract.normalize("  https://api.example///  ") == "https://api.example") {
            "Release backend URL regression: trim/trailing slash normalization changed."
        }
        BackendUrlContract.validate("https://api.example:65535/path")
    }
}

object BackendUrlContract {
    fun normalize(rawValue: String): String = rawValue.trim().trimEnd('/')

    fun validate(value: String) {
        require(value.isNotEmpty()) {
            "Release builds require a non-empty backendBaseUrl after normalization " +
                "(-PbackendBaseUrl takes precedence over BACKEND_BASE_URL)."
        }
        val backendUri = try {
            URI(value)
        } catch (exception: URISyntaxException) {
            throw GradleException("Release backendBaseUrl is malformed.", exception)
        }
        val normalizedScheme = backendUri.scheme?.lowercase(Locale.ROOT)
        val normalizedHost = backendUri.host?.lowercase(Locale.ROOT)
        require(normalizedScheme == "https" && normalizedHost != null && backendUri.userInfo == null) {
            "Release backendBaseUrl must be a valid HTTPS URL."
        }
        require(backendUri.port == -1 || backendUri.port in 1..65535) {
            "Release backendBaseUrl must use a valid TCP port between 1 and 65535."
        }
        require(backendUri.rawQuery == null && backendUri.rawFragment == null) {
            "Release backendBaseUrl must not contain a query or fragment."
        }
        require(!value.equals("https://todo.example", ignoreCase = true) &&
            !normalizedHost.equals("todo.example", ignoreCase = true)) {
            "Release backendBaseUrl must not use the TODO.example placeholder."
        }
    }

    fun resolve(explicitValue: String?, environmentValue: String): String =
        normalize(explicitValue ?: environmentValue)
}

// Upload keystore lives outside version control (see .gitignore). When it's absent — other
// developer machines, CI — the release build type simply stays unsigned instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseKeystore) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

if (hasReleaseKeystore) {
    val requiredReleaseSigningProperties = listOf(
        "storeFile",
        "storePassword",
        "keyAlias",
        "keyPassword"
    )
    val missingReleaseSigningProperties = requiredReleaseSigningProperties.filter { propertyName ->
        keystoreProperties.getProperty(propertyName).isNullOrBlank()
    }
    if (missingReleaseSigningProperties.isNotEmpty()) {
        throw GradleException(
            "keystore.properties exists, but Release signing requires these non-empty properties: " +
                missingReleaseSigningProperties.joinToString(", ")
        )
    }
}

fun normalizeBackendBaseUrl(rawValue: String): String = BackendUrlContract.normalize(rawValue)

fun validateReleaseBackendUrl(value: String) = BackendUrlContract.validate(value)

fun resolveBackendBaseUrl(explicitValue: String?, environmentValue: String): String =
    BackendUrlContract.resolve(explicitValue, environmentValue)

fun DependencyHandler.googleImplementation(dependency: Any) {
    add("googleImplementation", dependency)
}

fun DependencyHandler.huaweiImplementation(dependency: Any) {
    add("huaweiImplementation", dependency)
}

fun quoteBuildConfigString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

val hasExplicitBackendBaseUrl = project.hasProperty("backendBaseUrl")
val backendBaseUrlRaw = if (hasExplicitBackendBaseUrl) {
    resolveBackendBaseUrl(project.findProperty("backendBaseUrl")?.toString(), "")
} else {
    resolveBackendBaseUrl(null, providers.environmentVariable("BACKEND_BASE_URL").orNull.orEmpty())
}
val backendBaseUrl = backendBaseUrlRaw

fun requestedTaskName(taskPath: String): String = taskPath.substringAfterLast(':')

fun taskTargetsReleaseVariant(taskPath: String): Boolean {
    val taskName = requestedTaskName(taskPath)
    return taskName.equals("assemble", ignoreCase = true) ||
        taskName.equals("build", ignoreCase = true) ||
        taskName.equals("bundle", ignoreCase = true) ||
        taskName.equals("package", ignoreCase = true) ||
        taskName.contains("Release", ignoreCase = true) ||
        taskName.matches(Regex("(?i)(assemble|bundle|package)(Google|Huawei)"))
}

// This check intentionally runs while the app project is being configured. It therefore covers
// direct and aggregate Release tasks, including compile<Store>ReleaseKotlin, before task actions.
if (gradle.startParameter.taskNames.any(::taskTargetsReleaseVariant)) {
    validateReleaseBackendUrl(backendBaseUrl)
}

val huaweiManifestOverlay = layout.buildDirectory.file(
    "generated/manifests/huawei/AndroidManifest.xml"
)
val generateHuaweiManifestOverlay = tasks.register<GenerateHuaweiManifestOverlayTask>(
    "generateHuaweiManifestOverlay"
) {
    description = "Generates the Huawei manifest merger override as a declared build output."
    outputFile.set(huaweiManifestOverlay)
}

android {
    namespace = "com.example.convert2video"
    compileSdk = 36

    flavorDimensions += "store"

    productFlavors {
        create("google") {
            dimension = "store"
            buildConfigField("String", "STORE_ID", "\"google_play\"")
            buildConfigField("boolean", "GOOGLE_SERVICES_ENABLED", "true")
        }
        create("huawei") {
            dimension = "store"
            buildConfigField("String", "STORE_ID", "\"huawei\"")
            buildConfigField("boolean", "GOOGLE_SERVICES_ENABLED", "false")
        }
    }
    sourceSets.getByName("huawei").manifest.srcFile(huaweiManifestOverlay)
    defaultConfig {
        applicationId = "com.convert2video"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BACKEND_BASE_URL", quoteBuildConfigString(backendBaseUrl))
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // RecordingQuickActionTest uses default manifest resources. The AGP unit toggle is
            // module-global, so Config.NONE tests also get resource packaging.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Harness alias: the app has store flavors, so the exact legacy task must compile both stores.
tasks.register("compileDebugKotlin") {
    group = "verification"
    description = "Compiles Google and Huawei debug Kotlin variants."
    dependsOn("compileGoogleDebugKotlin", "compileHuaweiDebugKotlin")
}

tasks.configureEach {
    if (name.startsWith("processHuawei") && name.endsWith("Manifest")) {
        dependsOn(generateHuaweiManifestOverlay)
    }
}

tasks.register<VerifyBackendBaseUrlContractTask>("verifyBackendBaseUrlContract") {
    group = "verification"
    description = "Runs the backend URL precedence, normalization, and Release validation contract."
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    add("googleImplementation", libs.play.services.auth)
    googleImplementation(libs.play.billing.ktx)
    huaweiImplementation(libs.huawei.iap)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.glance.appwidget)
    testImplementation(libs.junit)
    // ApplicationProvider; unit-only. Do not add this alias to androidTest.
    testImplementation(libs.androidx.test.core)
    // test-only RobolectricTestRunner (JVM Android shadows).
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json impl for JVM unit tests only -- the Android SDK stub returns defaults under
    // isReturnDefaultValues, which would make JSON assertions vacuously pass. Never packaged into
    // the APK (main/androidTest still use the platform's org.json).
    testImplementation(libs.json)
    // Real SQLite for JVM migration row-preservation tests (androidTest companion).
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // GrantPermissionRule; catalog-pinned rules. androidTest core is espresso/ext-junit
    // transitive (currently 1.7.0). Not aligning this sprint.
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
