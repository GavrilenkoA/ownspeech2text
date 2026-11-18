pluginManagement {
    includeBuild("build-logic")
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
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "murmur"

// Module graph and dependency rules: docs/ARCHITECTURE.md section 2. Owners: docs/wp/*.md.
// Pure Kotlin/JVM (host-testable, no Android imports)
include(":core:model")
include(":core:catalog")
include(":core:context")
include(":core:prompt")
include(":core:ocr")
include(":core:audio")
include(":core:engine")
include(":core:store")
include(":core:benchmark")
include(":core:dictation")
include(":testing")
// Inference processes (:asr, :llm) and their binder API
include(":infer:api")
include(":infer:asr")
include(":infer:llm")
// Data layer (:main process)
include(":data:db")
include(":data:models")
// Platform glue (:main process)
include(":platform:a11y")
include(":platform:overlay")
include(":platform:audio")
// UI
include(":ui:design")
include(":feature:history")
include(":feature:dashboard")
include(":feature:models")
include(":feature:settings")
include(":feature:onboarding")
// Application shell
include(":app")
// Emulator end-to-end test target ("another app"), never shipped
include(":testapps:target")
