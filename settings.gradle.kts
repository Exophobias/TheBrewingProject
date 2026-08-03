rootProject.name = "TheBrewingProject"

pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Compile target. The default is upstream's Paper version so that `./gradlew test` keeps running the
// MockBukkit suite against the API it was written for. `-Ppaper26.2` retargets the compile classpath
// at the Paper 26.2 API, which is what the server actually runs, and builds the jar that ships.
//
// 26.2 is deliberately NOT the default: MockBukkit publishes no 26.2 mock, so under 26.2 the suite
// can only fail on the harness rather than on this plugin, and a green there would mean nothing.
// Note that the 26.x paper-api line is versioned `26.2.build.92-stable`, not `26.2-R0.1-SNAPSHOT`;
// only spigot-api uses the R0.1-SNAPSHOT form.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            if (providers.gradleProperty("paper26.2").isPresent) {
                version("paper", "26.2.build.92-stable")
            }
        }
    }
}

include("datagenerator")
include("api")
include("core")
include("bukkit-api")
include("bukkit-impl")
include("migration")
