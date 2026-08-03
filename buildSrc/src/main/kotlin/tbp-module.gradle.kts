plugins {
    `java-library`
}

group = "dev.jsinco.brewery"
version = System.getenv("VERSION")?.let {
    if (!it.matches("^v\\d+\\.\\d+\\.\\d+(-[a-z0-9.]+)?".toRegex())) {
        if (System.getenv("JITPACK")?.equals("true", true) ?: false) {
            return@let null
        }
        throw IllegalArgumentException("Invalid version name '$it', it has to follow convention v*.*.*")
    }
    it.replace("^v".toRegex(), "")
} ?: getGitHash()

fun getGitHash(): String {
    return providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput
        .asText.get().trim()
}

// See settings.gradle.kts for what `-Ppaper26.2` selects. Paper's whole 26.x API line is published as
// Java 25 bytecode, so it will not resolve onto a Java 21 compile classpath at all: retargeting the
// compile classpath and raising the toolchain are the same change and cannot be done separately.
val paper262 = providers.gradleProperty("paper26.2").isPresent

java {
    toolchain.languageVersion = JavaLanguageVersion.of(if (paper262) 25 else 21)
}

// The 26.2 gate compiles the test sources but does not run them. MockBukkit publishes no 26.2 mock,
// so anything the suite reported under 26.2 would be the harness failing, not this plugin. Running
// `./gradlew test` without the flag stays the correctness gate.
if (paper262) {
    tasks.withType<Test>().configureEach {
        enabled = false
    }
}
