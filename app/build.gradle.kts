import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Optional release signing, read from a gitignored keystore.properties (storeFile, storePassword,
// keyAlias, keyPassword) or the SCIF_STORE_FILE / SCIF_STORE_PASSWORD / SCIF_KEY_ALIAS /
// SCIF_KEY_PASSWORD environment variables. With neither, assembleRelease produces an unsigned APK.
val signingProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun signingValue(
    property: String,
    env: String,
): String? = (signingProperties.getProperty(property) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.scifsidekick.cleanroom"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.scifsidekick.cleanroom"
        minSdk = 26
        targetSdk = 37
        versionCode = 58
        versionName = "1.28.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    val releaseStoreFile = signingValue("storeFile", "SCIF_STORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "SCIF_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SCIF_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SCIF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("com.klinkerapps:android-smsmms:5.2.6")
    // Backs WatchdogWorker (periodic "is the service still alive" check) and SnoozeWorker (the
    // scheduled re-enable behind "Snooze forwarding") -- both need to survive process death and a
    // reboot, which is exactly what WorkManager is for and a bare coroutine/Handler isn't.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    testImplementation("junit:junit:4.13.2")
    // Android's unit-test stub jar throws "not mocked" for org.json.JSONObject/JSONArray method
    // bodies -- a real implementation on the test classpath shadows the stub so PayloadCodec and
    // the other org.json-based (de)serialization used throughout this app can actually be
    // exercised by a plain JVM unit test, without pulling in Robolectric for it.
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
}

// The androidTest runtime classpath is the one place in this project's dependency graph where
// kotlinx-serialization resolves inconsistently: something on that classpath specifically places
// a `strictly 1.7.3` constraint on kotlinx-serialization-core, while kotlinx-serialization-json
// itself stays resolved at 1.8.1 (the version every other configuration -- including the KSP
// annotation-processor classpath that actually WRITES Room's schema-bundle JSON at build time --
// already resolves to consistently, with no issue). A json 1.8.1 decoder calling a
// GeneratedSerializer method that core 1.7.3's plugin-generated classes don't implement throws
// AbstractMethodError the instant a schema bundle (e.g. inside a Room MigrationTestHelper test)
// is deserialized on-device -- confirmed by reproducing the failure on a physical device and
// tracing it to exactly this mismatch. Forcing core up to 1.8.1 on just this classpath matches it
// to the version already proven to work everywhere else, rather than moving every OTHER
// configuration to 1.7.3 and risking the same inconsistency in the other direction (which is
// exactly what happened when this was first tried scoped globally: it broke kspDebugKotlin
// instead, since Room's KSP tooling needs 1.8.1's GeneratedSerializer shape).
configurations.matching { it.name.contains("AndroidTest") }.configureEach {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1",
        )
    }
}

dependencyLocking {
    lockAllConfigurations()
}
