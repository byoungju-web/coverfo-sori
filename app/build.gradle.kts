plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* 서명 키: GitHub Secrets(SORI_KEYSTORE_B64 · SORI_KS_PASS)가 있으면 그 키로 서명 → 업데이트를 지우지 않고 덮어 설치할 수 있다.
   없으면 디버그 키(빌드할 때마다 바뀜 → 업데이트 때 앱을 지웠다 다시 설치해야 함) */
val ksPath: String? = System.getenv("SORI_KEYSTORE")
val runNo: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.coverfo.sori"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coverfo.sori"
        minSdk = 26
        targetSdk = 35
        versionCode = runNo
        versionName = "1.0.$runNo"
        /* 음성 인식(Vosk)·JNA 의 기기용 파일은 요즘 폰(ARM)용만 넣어 설치 파일을 작게 */
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures { buildConfig = true }

    /* v4: 한 코드에서 두 가지 설치 파일
       · full  (APK — coverfo.com/app 에서 나눠 줌): 설치된 모든 앱을 말로 열 수 있음
       · store (구글 플레이 AAB): 플레이 정책에 맞춰 정해 둔 앱 목록만 열 수 있음 */
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            buildConfigField("boolean", "STORE", "false")
        }
        create("store") {
            dimension = "dist"
            buildConfigField("boolean", "STORE", "true")
        }
    }

    signingConfigs {
        create("sori") {
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("SORI_KS_PASS")
                keyAlias = "sori"
                keyPassword = System.getenv("SORI_KS_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null) signingConfigs.getByName("sori") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.kakao.sdk:v2-navi:2.20.6")          // 카카오내비 바로 안내 (카카오 공식 Android SDK · Kotlin 2.0 과 맞는 버전)
    implementation("androidx.browser:browser:1.8.0")         // Google 로그인을 크롬 창(Custom Tab)에서
    /* ① 이름 부르기: 폰 안에서만 도는 음성 인식 Vosk (Apache 2.0) — 공식 예제(vosk-android-demo)와 같은 버전 */
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
