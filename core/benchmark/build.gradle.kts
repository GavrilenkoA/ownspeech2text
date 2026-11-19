plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:engine"))
    api(project(":core:store"))
    api(project(":core:catalog"))
    // The LLM fixed case renders the real prompt and runs the guard (validity gate, ADR-051).
    api(project(":core:prompt"))
    // FakeEngines, FakeUnitHealth, InMemoryBenchmarkRepository, VirtualClock, FakePromptRenderer (ARCHITECTURE 16).
    testImplementation(project(":testing"))
}
