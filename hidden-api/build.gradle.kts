// 隐藏 API 编译期桩模块。
//
// 存在的原因：设备层要在 shell uid 进程内调用 android.os.ServiceManager 与
// android.hardware.input.IInputManager，这两个类型是 framework 的 @hide 符号，
// 公开 SDK（android.jar）里没有，直接写会编译不过。
//
// 关键约束：本模块只能以 compileOnly 被引用。运行期这些类由 boot classpath 提供
// （PathClassLoader 的父加载器是 BootClassLoader，先命中 framework 实现），
// 桩里的方法体永不执行；一旦打进 APK 就可能触发 "resolved to unexpected implementation"。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.neptine.mimir.hidden"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
