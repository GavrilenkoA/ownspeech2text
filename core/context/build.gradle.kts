plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:ocr"))
}

// D8: prompts/ is the single source of truth. The app category table is packaged at build time under this module's
// own resource path (AppCategories.RESOURCE), never copied by hand.
val packageAppCategories = tasks.register<Sync>("packageAppCategories") {
    from(rootDir.resolve("prompts/tones")) {
        include("app_categories.v1.json")
        into("app/murmur/core/context")
    }
    into(layout.buildDirectory.dir("generated/contextResources"))
}
sourceSets.named("main") {
    resources.srcDir(packageAppCategories)
}

tasks.withType<Test>().configureEach {
    // Tests read prompts/context.schema.v2.json, prompts/examples and prompts/tones in place.
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
}
