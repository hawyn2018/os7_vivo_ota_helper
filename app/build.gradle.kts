import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signing = Properties().apply {
    val path = rootProject.file("signing.properties")
    if (path.isFile) path.inputStream().use { load(it) }
}
val localSdkConfig = Properties().apply {
    val path = rootProject.file("local.properties")
    if (path.isFile) path.inputStream().use { load(it) }
}

android {
    namespace = "net.hawyn.vivootahelper"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    localSdkConfig.getProperty("ndk.path")?.let { ndkPath = it }
    defaultConfig {
        applicationId = "net.hawyn.vivootahelper"
        minSdk = 33
        targetSdk = 37
        versionCode = 4
        versionName = "2.2.0"
        resValue("string", "app_name", "lk升级助手")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            ndkBuild {
                arguments += listOf("NDK_APPLICATION_MK=${rootProject.file("patcher/Application.mk").absolutePath}")
            }
        }
    }
    signingConfigs {
        if (signing.isNotEmpty()) {
            create("localRelease") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("localRelease")
        }
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "lk升级助手（测试）")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    externalNativeBuild {
        ndkBuild { path = rootProject.file("patcher/Android.mk") }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("com.materialkolor:material-color-utilities:6.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
