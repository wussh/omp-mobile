import java.security.KeyStore
import java.security.MessageDigest
import java.util.HexFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.omp.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.omp.mobile"
        minSdk = 26
        targetSdk = 37
        // Increment for each APK build, not for each source edit.
        versionCode = 13
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            val persistentKeystore = file(
                providers.environmentVariable("OMP_MOBILE_DEBUG_KEYSTORE").getOrElse(
                    "${System.getProperty("user.home")}/.config/omp-mobile/signing/debug.keystore",
                ),
            )
            check(persistentKeystore.isFile) {
                "Persistent debug keystore missing. Use scripts/build-debug-kube.sh; do not generate a replacement key."
            }
            val keystore = KeyStore.getInstance("JKS").apply {
                persistentKeystore.inputStream().use { load(it, "android".toCharArray()) }
            }
            val certificate = checkNotNull(keystore.getCertificate("androiddebugkey")) {
                "Persistent debug keystore has no androiddebugkey certificate."
            }
            val fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
            )
            check(fingerprint == rootProject.file("signing/debug-certificate.sha256").readText().trim()) {
                "Debug signing certificate mismatch. Restore the persistent keystore; do not rotate the key."
            }
            storeFile = persistentKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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


    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(project(":collab"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.markdown.m3)
    implementation(libs.markdown.code)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
