plugins {
    `tbp-module`
    `maven-publish`
    id("java-test-fixtures")
}

repositories {
    mavenCentral()
    maven("https://repo.faststats.dev/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    api(project(":api"))
    compileOnly(libs.paper.api)
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "thebrewingproject-bukkit"
            from(components["java"])
            pom {
                name = "TheBrewingProject Bukkit API"
                description = "API for TheBrewingProject - Bukkit"
                url = "https://tbp.breweryteam.dev/docs/welcome/"
                licenses {
                    license {
                        name = "The MIT license"
                        url =
                            "https://raw.githubusercontent.com/BreweryTeam/TheBrewingProject/refs/heads/master/LICENSE"
                    }
                }
                scm {
                    connection = "scm:git:git//github.com/BreweryProject/thebrewingproject.git"
                    developerConnection = "scm:git:ssh://github.com:BreweryProject/thebrewingproject.git"
                    url = "https://github.com/BreweryProject/thebrewingproject"
                }
            }
        }
    }
    repositories {
        maven {
            name = "breweryteam"
            url = uri("https://repo.breweryteam.dev/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }

        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Verify the exact resolved shipping API while preserving the default MockBukkit gate.
tasks.register("verifyPaperApi") {
    doLast {
        val expected = libs.paper.api.get().versionConstraint.requiredVersion
        val api = configurations.compileClasspath.get().resolvedConfiguration.resolvedArtifacts.single {
            it.moduleVersion.id.group == "io.papermc.paper" && it.name == "paper-api"
        }
        check(api.moduleVersion.id.version == expected) {
            "Expected paper-api:$expected, resolved ${api.moduleVersion.id}"
        }
        logger.lifecycle("paper-api:jar:${api.moduleVersion.id.version}")
    }
}
