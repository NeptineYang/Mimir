// 应用内无线调试通道（vendor 自 Shizuku 的 manager/adb，Apache-2.0，见 NOTICE）。
//
// 职责：配对（SPAKE2 over TLS）、TLS 连接 adbd、执行一条 shell 命令。
// 不含任何 Mimir 业务语义，也不知道"特权服务"是什么——调用方在 app 层拼命令。
//
// 为什么要有 NDK：配对密码的一半来自 TLS exporter，另一半来自 SPAKE2，
// 而 SPAKE2 只在 BoringSSL 里实现，JNI 是唯一可行的桥。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.neptine.mimir.adblink"
    compileSdk = 37
    // 与本机已安装的 NDK 对齐；BoringSSL 的 AAR 版本后缀 ndk28 必须与之一致。
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 30
        // 与 app 一致：只留演示机（arm64）与 AVD（x86_64）。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // 代码里没有异常与 RTTI，静态链接 libc++ 可以少一个运行时依赖。
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    buildFeatures {
        // 消费 BoringSSL AAR 里的 prefab 包（头文件 + 静态库）。
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            // 本机只装了 3.22.1；vendor 的 CMakeLists 已相应下调版本要求。
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // TLS 的 exportKeyingMaterial：平台版同名方法是 @hide，用 Maven 版避开 hidden API。
    implementation(libs.conscrypt.android)
    // 只用于签发自签名的 adb 客户端证书（AdbKey）。
    implementation(libs.bouncycastle.bcpkix)
    // prefab 提供 BoringSSL 的 SPAKE2 与 AEAD 实现。
    implementation(libs.boringssl.ndk)
}
