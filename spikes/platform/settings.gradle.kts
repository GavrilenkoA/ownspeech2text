pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "murmur-platform-spike"
include(":app")     // app.murmur.spike        : accessibility service, bubble, mic, :infer, UIDT
include(":target")  // app.murmur.spike.target : the "other app" (EditText, Compose, WebView, FLAG_SECURE)
