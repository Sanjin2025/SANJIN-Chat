// ==================== SanjinChat Wear OS App 构建脚本（Kotlin 1.9 + AGP 8.5） ====================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sanjin.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sanjin.wear"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
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
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        viewBinding = false
        buildConfig = true
    }
    composeOptions {
        // Kotlin 1.9.24 对应 Compose 编译器扩展 1.5.15
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources { excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1") }
    }
}

dependencies {
    // ---- Core Library Desugaring (让 minSdk 26 也能使用 Java 8+ 特性) ----
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // ---- AndroidX 核心 ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // ---- Wear OS ----
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.wear.compose:compose-navigation:1.3.1")

    // ---- Material Components (主题需要 Theme.MaterialComponents Bridge) ----
    implementation("com.google.android.material:material:1.12.0")

    // ---- Compose UI (Material2 / MD2 深色) ----
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- 网络 ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- 数据存储 ----
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
