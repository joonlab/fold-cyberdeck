plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9.0부터 Kotlin 지원이 AGP에 내장됐다 — kotlin.android 플러그인은 넣으면 오히려 에러.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
