import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sanjinchat.wear"
    compileSdk = 35

    // 版本号 = 编译日期（yyyyMMdd），例如 20261024
    // versionCode 取整型用于比较，versionName 同值用于显示
    val buildDate = SimpleDateFormat("yyyyMMdd").format(Date())

    defaultConfig {
        applicationId = "com.sanjinchat.wear"
        minSdk = 26
        targetSdk = 34
        versionCode = buildDate.toInt()
        versionName = buildDate
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Standard Material3 (works on both Wear OS watches and phones/tablets)
    // 1.5.0-alpha14：官方 Material 3 Expressive API 已公开（MaterialExpressiveTheme / MotionScheme），
    // 且仍依赖 compose-ui 1.8.2，故无需提升 compileSdk / AGP
    implementation("androidx.compose.material3:material3:1.5.0-alpha14")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.json:json:20240205")

    // Coil for images
    implementation("io.coil-kt:coil-compose:2.6.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ZXing barcode scanner（扫码登录）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
