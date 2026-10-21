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
}
