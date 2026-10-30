plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// OneDrive can lock generated test directories. Script builds keep all outputs local.
providers.gradleProperty("povBuildRoot").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "dev.pov.tv"
    compileSdk = 36
    // Match Kotlin internal symbols when auditing a locally signed release on a TV.
    testBuildType = providers.gradleProperty("povTestBuildType").orElse("debug").get()
    defaultConfig {
        applicationId = "dev.pov.tv.rajput"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "0.3.4-rajput"
        testInstrumentationRunner = providers.gradleProperty("povTestRunner")
            .orElse("dev.pov.tv.PlaybackAuditInstrumentation").get()
    }
    buildFeatures { buildConfig = true }
    // One codebase, two APKs: the TV app keeps its package (existing installs update in place),
    // the phone app is a separate install with its own release asset and update manifest.
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            buildConfigField("boolean", "MOBILE", "false")
            buildConfigField("String", "UPDATE_APK", "\"POV-Rajput-release.apk\"")
            buildConfigField("String", "UPDATE_MANIFEST", "\"update.json\"")
            buildConfigField("String", "UPDATE_PACKAGE", "\"dev.pov.tv.rajput\"")
            manifestPlaceholders += mapOf("mainOrientation" to "landscape", "playerOrientation" to "landscape")
        }
        create("mobile") {
            dimension = "device"
            applicationId = "dev.pov.rajput.mobile"
            buildConfigField("boolean", "MOBILE", "true")
            buildConfigField("String", "UPDATE_APK", "\"POV-Rajput-Mobile.apk\"")
            buildConfigField("String", "UPDATE_MANIFEST", "\"update-mobile.json\"")
            buildConfigField("String", "UPDATE_PACKAGE", "\"dev.pov.rajput.mobile\"")
            manifestPlaceholders += mapOf("mainOrientation" to "portrait", "playerOrientation" to "sensorLandscape")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    val releaseStore = providers.environmentVariable("POV_RELEASE_STORE_FILE").orNull
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs.create("distribution") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("POV_RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("POV_RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("POV_RELEASE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        // Optional side-by-side install for UI previews, e.g. -PpovAppIdSuffix=.preview
        debug { providers.gradleProperty("povAppIdSuffix").orNull?.let { applicationIdSuffix = it } }
        release {
            isMinifyEnabled = false
            providers.gradleProperty("povAppIdSuffix").orNull?.let { applicationIdSuffix = it }
            // Opt-in local sideload release: retain the installed preview certificate and data.
            // Normal release builds remain unsigned for signing with the distributor's own key.
            if (providers.gradleProperty("povPreviewRelease").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // Artwork only: HTTP/2 shares one connection per image server, with a disk cache for posters.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR code for device sign-in (encoder only).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
