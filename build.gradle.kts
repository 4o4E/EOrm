import java.util.Properties
import kotlin.apply

plugins {
    kotlin("jvm") version "2.2.21" apply false
    publishing
    `java-library`
}

allprojects {
    group = "top.e404.eorm"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

val local = Properties().apply {
    val file = projectDir.resolve("local.properties")
    if (file.exists()) file.bufferedReader().use { load(it) }
}

val nexusUsername get() = local.getProperty("nexus.username") ?: ""
val nexusPassword get() = local.getProperty("nexus.password") ?: ""

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.gradle.maven-publish")
    apply(plugin = "org.gradle.java-library")

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
    }

    java {
        withSourcesJar()
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
                name = "snapshot"
                url = uri("https://nexus.e404.top:3443/repository/maven-snapshots/")
                credentials {
                    username = nexusUsername
                    password = nexusPassword
                }
            }
        }
    }
}