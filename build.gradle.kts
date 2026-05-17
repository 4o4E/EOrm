import java.util.Properties
import kotlin.apply
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm") version "2.2.21" apply false
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
val githubPackagesUrl = propertyOrEnv("github.packages.url", "GITHUB_PACKAGES_URL")
    .ifBlank { "https://maven.pkg.github.com/$githubRepository" }
val githubPackagesUsername = propertyOrEnv("github.packages.username", "GITHUB_ACTOR")
val githubPackagesToken = propertyOrEnv("github.packages.token", "GITHUB_TOKEN")

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

    afterEvaluate {
        publishing.publications.create<MavenPublication>("java") {
            from(components["kotlin"])
            artifact(tasks.getByName("sourcesJar"))
            artifactId = project.name
            groupId = rootProject.group.toString()
            version = rootProject.version.toString()
        }
    }

    publishing {
        repositories {
            maven {
                name = "githubPackages"
                url = uri(githubPackagesUrl)
                credentials {
                    username = githubPackagesUsername
                    password = githubPackagesToken
                }
            }
        }
    }
}
