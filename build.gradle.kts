// 根构建脚本：只声明插件版本，由 app 模块按需 apply。
// AGP 9 起 Kotlin 编译由 AGP 内置（2.2.10），因此这里不出现 org.jetbrains.kotlin.android；
// 但 Compose 编译器插件仍需显式 apply，版本必须等于内置 Kotlin 版本。
plugins {
    alias(libs.plugins.android.application) apply false
    // hidden-api 桩模块用库插件；在根声明一次，避免子模块重复解析同一插件不同版本。
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
