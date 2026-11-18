plugins {
    alias(libs.plugins.murmur.jvm.library)
}

// D10: generate ProductName.kt from gradle.properties (murmur.productName).
val productName = providers.gradleProperty("murmur.productName").orElse("Murmur")
val genDir = layout.buildDirectory.dir("generated/productName/kotlin")
val generateProductName = tasks.register("generateProductName") {
    val name = productName
    val out = genDir
    inputs.property("name", name)
    outputs.dir(out)
    doLast {
        val f = out.get().file("app/murmur/core/model/ProductName.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "// GENERATED from gradle.properties murmur.productName. Do not edit.\n" +
                "package app.murmur.core.model\n\n" +
                "object ProductName {\n    const val NAME: String = \"${name.get()}\"\n}\n",
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateProductName) }
