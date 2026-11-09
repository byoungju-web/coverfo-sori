// coverfo 문자 시험 앱 (com.coverfo.smstest) — 문자 보내기·연락처·전화 걸기 권한이 든 앱이
// 폰에 설치되는지(플레이 프로텍트에 막히는지) 먼저 확인하는 작은 시험 앱. 소리 앱 빌드와 따로 돈다 (.github/workflows/smstest.yml)
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "coverfo-smstest"
include(":app")
