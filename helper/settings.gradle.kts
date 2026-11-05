// coverfo 화면 도우미 (com.coverfo.soria) — 소리 앱과 따로 만드는 작은 앱 (v1)
// 이 폴더(helper)는 소리 앱 빌드와 따로 돈다 (.github/workflows/helper.yml). 소리 앱 설정은 건드리지 않는다.
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
rootProject.name = "coverfo-soria"
include(":soria")
