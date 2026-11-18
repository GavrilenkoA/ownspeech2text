plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:context"))
}

// D8: prompts/ is the single source of truth. Package the shipped prompt files into this module's
// resources at build time (classpath /prompts/...), never copy them by hand. Eval-only files (eval/,
// tests/, *.fx.jsonl fixture shots, examples/) are not shipped.
val promptsDir = rootDir.resolve("prompts")
val packagePrompts = tasks.register<Sync>("packagePrompts") {
    from(promptsDir) {
        include("system/*.md", "profiles/*.json", "shots/*.jsonl", "tones/*.json", "context.schema.v2.json")
        exclude("shots/*.fx.jsonl")
    }
    into(layout.buildDirectory.dir("generated/promptResources/prompts"))
}
sourceSets.named("main") {
    resources.srcDir(packagePrompts.map { it.destinationDir.parentFile })
}

tasks.withType<Test>().configureEach {
    // Golden tests read prompts/examples in place (byte-for-byte parity with prompts/render.py).
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
}
