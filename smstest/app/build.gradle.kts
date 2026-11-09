plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* 서명 키: 소리 앱과 같은 키(GitHub Secrets SORI_KEYSTORE_B64 · SORI_KS_PASS) — 실제 소리 앱과 같은 조건에서 시험하려고 */
val ksPath: String? = System.getenv("SORI_KEYSTORE")
val runNo: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.coverfo.smstest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coverfo.smstest"
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
