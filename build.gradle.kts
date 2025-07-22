plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt) apply false // 필요 시
    alias(libs.plugins.jetbrainsCompose) apply false // Compose Multiplatform 사용 시
}
