plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* 서명 키: 소리 앱과 같은 키(GitHub Secrets SORI_KEYSTORE_B64 · SORI_KS_PASS)로 서명해야
   소리 앱만 이 도우미에게 일을 시킬 수 있다 (서명 권한 com.coverfo.soria.CONTROL) */
val ksPath: String? = System.getenv("SORI_KEYSTORE")
val runNo: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.coverfo.soria"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coverfo.soria"
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
