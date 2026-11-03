// app 模块构建脚本。
// 分层以包边界表达（设计文档 §1.2）：core / device / observation / agent / data / service / ui。
// 刻意不写 kotlin { compilerOptions }：AGP 9 内置 Kotlin 下 jvmTarget 取 compileOptions.targetCompatibility。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.neptine.mimir"
    // compileSdk 37：2026 年的 AndroidX 主线（Compose 1.12.x、navigation 2.10.x）要求编译期至少 API 37。
    // targetSdk 仍为 36：运行期行为以演示机 HyperOS 4.0 对齐，二者可以不同（设计文档 §2.1）。
    compileSdk = 37

    defaultConfig {
        // Shizuku 的 provider authority 由 server 端拼成 "<applicationId>.shizuku"，改包名即断链。
        applicationId = "com.neptine.mimir"
        minSdk = 30
        targetSdk = 36
        versionCode = 12
        versionName = "0.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 设备层 AIDL：IMimirShell 跑在 Shizuku UserService（shell uid）里，
        // 建屏、取帧、注入、ime 切换全部经它转发。
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    // hiltViewModel()：ViewModel 取 Hilt 图。1.4.0 已移除"传 NavBackStackEntry"的重载，
    // 只能无参调用，taskId 由 SavedStateHandle 从路由参数取。
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // 模型 API Key 存在 EncryptedSharedPreferences 里，密钥由 Android Keystore 持有且不可导出。
    implementation(libs.androidx.security.crypto)

    // 端侧 OCR：控件树被 App 封锁时（微信等）用中文文本识别补出元素表，见设计文档 §2.3.2。
    implementation(libs.mlkit.text.chinese)

    // Shizuku：api 用于 binder 调用，provider 提供 <provider> 组件（authority 由 server 端拼成 <applicationId>.shizuku）。
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // 隐藏 API 只作编译期符号：运行期由 boot classpath 的 framework 实现接管，
    // 打进 APK 会引入重复类风险（见 hidden-api/build.gradle.kts 注释）。
    compileOnly(project(":hidden-api"))
}
