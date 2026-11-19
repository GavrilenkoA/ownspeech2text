// Used by core/prompt/tools/run_on_art.sh: prints the test runtime classpath of :core:prompt.
allprojects {
    if (path == ":core:prompt") {
        afterEvaluate {
            tasks.register("printTestRuntimeClasspath") {
                val cp = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
                    .getByName("test").runtimeClasspath
                dependsOn(cp)
                doLast { println("CLASSPATH=" + cp.files.joinToString(":")) }
            }
        }
    }
}
