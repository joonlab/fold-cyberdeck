import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

// 서버 목록(deckd 가 도는 맥들)은 소스에 넣지 않는다.
// 우선순위: local.properties 의 deck.servers → gradle 속성 -Pdeck.servers → 환경변수 DECK_SERVERS.
// 형식: 이름@호스트:포트,이름@호스트:포트   (예시는 config.example.properties)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val deckServers: String =
    localProps.getProperty("deck.servers")
        ?: (project.findProperty("deck.servers") as String?)
        ?: System.getenv("DECK_SERVERS")
        ?: ""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kr.joonlab.foldlab"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.joonlab.foldlab"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 멀티터치는 `adb shell input` 으로 못 보낸다(단일 포인터만) — `sendevent` 는 root 필요.
        // 그래서 두/세 손가락 검증은 계측 테스트가 진짜 멀티포인터 이벤트를 흘려서 한다.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 재설치가 진짜 반영됐는지 눈으로 확인하려고 빌드 시각을 앱에 박는다.
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$stamp\"")
        buildConfigField("String", "DECK_SERVERS", "\"${deckServers.replace("\\", "").replace("\"", "")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.12.4")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    // ⚠️ compose ui-test 가 끌어오는 espresso 3.5.0 은 Android 17 에서 통째로 죽는다 —
    //    `NoSuchMethodException: android.hardware.input.InputManager.getInstance`.
    //    (Espresso 가 리플렉션으로 부르던 비공개 메서드가 없어졌다. 테스트 «전부»가 같은 예외로
    //     떨어지므로 로직 실패로 오진하기 쉽다.) 3.7.0 에서 고쳐졌다.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
