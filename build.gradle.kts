import java.util.Properties
import kotlin.apply
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm") version "2.2.21" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    publishing
    `java-library`
}

val local = Properties().apply {
    val file = projectDir.resolve("local.properties")
    if (file.exists()) file.bufferedReader().use { load(it) }
}

fun propertyOrEnv(propertyName: String, envName: String): String {
    return providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(envName).orNull
        ?: local.getProperty(propertyName)
        ?: ""
}

val releaseVersion = providers.gradleProperty("releaseVersion").orNull
    ?: providers.environmentVariable("RELEASE_VERSION").orNull
    ?: "1.0.0-SNAPSHOT"
val githubRepository = propertyOrEnv("github.repository", "GITHUB_REPOSITORY")
    .ifBlank { "4o4E/EOrm" }
val githubUrl = "https://github.com/$githubRepository"
val nexusReleasesUrl = propertyOrEnv("nexus.releases.url", "NEXUS_RELEASES_URL")
    .ifBlank { "https://nexus.e404.top:3443/repository/maven-releases/" }
val nexusSnapshotsUrl = propertyOrEnv("nexus.snapshots.url", "NEXUS_SNAPSHOTS_URL")
    .ifBlank { "https://nexus.e404.top:3443/repository/maven-snapshots/" }
val nexusUsername = propertyOrEnv("nexus.username", "NEXUS_USERNAME")
val nexusPassword = propertyOrEnv("nexus.password", "NEXUS_PASSWORD")
val hasSigningKey = propertyOrEnv("signingInMemoryKey", "SIGNING_KEY").isNotBlank()
val isSnapshotVersion = releaseVersion.endsWith("-SNAPSHOT")

allprojects {
    group = "top.e404.eorm"
    version = releaseVersion

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.gradle.maven-publish")
    apply(plugin = "org.gradle.java-library")
    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "jacoco")

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        
        implementation(kotlin("stdlib"))
        testImplementation(kotlin("test"))
        testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
        testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        finalizedBy("jacocoTestReport")
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    if (project.name == "eorm-core") {
        tasks.withType<JacocoCoverageVerification>().configureEach {
            dependsOn(tasks.withType<Test>())
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = "0.90".toBigDecimal()
                    }
                    limit {
                        counter = "BRANCH"
                        value = "COVEREDRATIO"
                        minimum = "0.65".toBigDecimal()
                    }
                }
            }
        }

        tasks.named("check") {
            dependsOn("jacocoTestCoverageVerification")
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    java {
        withSourcesJar()
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        if (hasSigningKey) signAllPublications()

        coordinates(rootProject.group.toString(), project.name, rootProject.version.toString())

        pom {
            name.set(project.name)
            description.set("${project.name} module for EOrm")
            url.set(githubUrl)
            licenses {
                license {
                    name.set("GNU GPLv3")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.en.html")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("4o4E")
                    name.set("4o4E")
                    email.set("4o4E@users.noreply.github.com")
                    organization.set("4o4E")
                    organizationUrl.set("https://github.com/4o4E")
                    url.set("https://github.com/4o4E")
                }
            }
            scm {
                url.set(githubUrl)
                connection.set("scm:git:git://github.com/$githubRepository.git")
                developerConnection.set("scm:git:ssh://git@github.com/$githubRepository.git")
            }
        }
    }

    publishing {
        repositories {
            maven {
                name = "nexus"
                url = uri(if (isSnapshotVersion) nexusSnapshotsUrl else nexusReleasesUrl)
                credentials {
                    username = nexusUsername
                    password = nexusPassword
                }
            }
        }
    }
}
