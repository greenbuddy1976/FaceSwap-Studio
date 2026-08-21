plugins {
    id("com.android.application")
}

val faceswapAbi = providers.gradleProperty("faceswapAbi").orElse("arm64-v8a")
val releaseStoreFile = providers.environmentVariable("RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val releaseSigningAvailable = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.greenbuddy.faceswapstudio"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.greenbuddy.faceswapstudio"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += faceswapAbi.get()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
        }
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += listOf("onnx", "bin")
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.matching { it.name == "packageRelease" || it.name == "assembleRelease" }.configureEach {
    doFirst {
        check(releaseSigningAvailable) {
            "Release signing variables are required; an unsigned or debug-signed final APK is forbidden."
        }
    }
}

val modelDirectory = layout.projectDirectory.dir("src/main/assets/models")

tasks.register("verifyModelAssets") {
    group = "verification"
    description = "Verifies that clean, build-time model assets are present and plausible."
    doLast {
        val required = mapOf(
            "arcface_w600k_r50.onnx" to 150_000_000L,
            "inswapper_128_fp16.onnx" to 240_000_000L,
            "emap.bin" to 1_048_576L,
            "models.lock.json" to 100L
        )
        required.forEach { (name, minimumSize) ->
            val file = modelDirectory.file(name).asFile
            check(file.isFile) { "Missing required clean model asset: $name. Run scripts/fetch_models.py first." }
            check(file.length() >= minimumSize) { "Model asset $name is truncated (${file.length()} bytes)." }
        }
        check(modelDirectory.file("emap.bin").asFile.length() == 1_048_576L) {
            "emap.bin must contain exactly 512x512 float32 values."
        }
    }
}

tasks.named("preBuild").configure { dependsOn("verifyModelAssets") }

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.1")
    implementation("androidx.lifecycle:lifecycle-livedata:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
