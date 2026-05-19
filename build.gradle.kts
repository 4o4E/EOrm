import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.GradleException
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

val isCi = providers.environmentVariable("GITHUB_ACTIONS")
    .map { it == "true" }
    .orElse(false)

val isCiTag = providers.environmentVariable("GITHUB_REF_TYPE")
    .map { it == "tag" }
    .orElse(providers.environmentVariable("GITHUB_REF").map { it.startsWith("refs/tags/") })
    .orElse(false)

val localSnapshotVersion = "0.0.1-SNAPSHOT"
val publishVersion = providers.provider {
    if (isCi.get() && isCiTag.get()) {
        providers.environmentVariable("GITHUB_REF_NAME").orNull ?: localSnapshotVersion
    } else {
        localSnapshotVersion
    }
}

val githubUrl = "https://github.com/4o4E/EOrm"
val nexusReleasesUrl = "https://nexus.e404.top:3443/repository/maven-releases/"
val nexusSnapshotsUrl = "https://nexus.e404.top:3443/repository/maven-snapshots/"
val publishExcludedProjects = emptySet<String>()

fun nexusCredential(propertyName: String, ciSecretEnvName: String): Provider<String> =
    if (isCi.get()) providers.environmentVariable(ciSecretEnvName) else providers.gradleProperty(propertyName)

allprojects {
    group = "top.e404.eorm"
    version = publishVersion.get()

    repositories {
        mavenCentral()
    }
}

subprojects {
    val shouldPublish = project.name !in publishExcludedProjects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.gradle.java-library")
    apply(plugin = "jacoco")
    if (shouldPublish) {
        apply(plugin = "org.gradle.maven-publish")
        apply(plugin = "com.vanniktech.maven.publish")
    }

    dependencies {
        testImplementation(kotlin("test"))
        testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
        testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    }

    tasks.test {
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

    if (shouldPublish) {
        configure<MavenPublishBaseExtension> {
            val centralReleaseEnabled = isCi.get() && isCiTag.get() && !rootProject.version.toString().endsWith("-SNAPSHOT")
            if (centralReleaseEnabled) {
                publishToMavenCentral()
                // Central 需要签名，本地 Nexus 和 mavenLocal 不依赖签名配置。
                signAllPublications()
            }

            coordinates(rootProject.group.toString(), project.name, rootProject.version.toString())

            pom {
                name.set(project.name)
                description.set("${project.name} module for EOrm")
                url.set(githubUrl)
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("4o4E")
                        name.set("4o4E")
                        email.set("869951226@qq.com")
                        organization.set("4o4E")
                        organizationUrl.set("https://github.com/4o4E")
                    }
                }
                scm {
                    url.set(githubUrl)
                    connection.set("scm:git:$githubUrl.git")
                    developerConnection.set("scm:git:$githubUrl.git")
                }
            }
        }

        publishing {
            repositories {
                maven {
                    name = "nexus"
                    val isSnapshotVersion = rootProject.version.toString().endsWith("-SNAPSHOT")
                    url = uri(if (isSnapshotVersion) nexusSnapshotsUrl else nexusReleasesUrl)
                    credentials {
                        username = nexusCredential("nexus.username", "NEXUS_USERNAME").orNull
                        password = nexusCredential("nexus.password", "NEXUS_PASSWORD").orNull
                    }
                }
            }
        }

        val centralReleaseEnabled = isCi.get() && isCiTag.get() && !rootProject.version.toString().endsWith("-SNAPSHOT")
        if (centralReleaseEnabled) {
            tasks.matching {
                it.name == "publishToMavenCentral" ||
                    it.name == "publishAndReleaseToMavenCentral" ||
                    (it.name.startsWith("publish") && it.name.endsWith("ToMavenCentralRepository"))
            }.configureEach {
                onlyIf {
                    require(isCi.get()) {
                        "Maven Central 只允许在 CI 中发布，本地请使用 publishAllPublicationsToNexusRepository 或 publishToMavenLocal"
                    }
                    require(isCiTag.get()) {
                        "Maven Central 只允许通过 CI tag 发布"
                    }
                    require(!rootProject.version.toString().endsWith("-SNAPSHOT")) {
                        "Maven Central 只能发布由 GITHUB_REF_NAME 推导出的正式版本，不能发布 SNAPSHOT"
                    }
                    true
                }
            }
        } else {
            val centralBlockedMessage = if (isCi.get()) {
                "Maven Central 只能发布由 GITHUB_REF_NAME 推导出的正式版本，不能发布 SNAPSHOT"
            } else {
                "Maven Central 只允许在 CI 中发布，本地请使用 publishAllPublicationsToNexusRepository 或 publishToMavenLocal"
            }
            listOf(
                "publishToMavenCentral",
                "publishAndReleaseToMavenCentral",
                "publishAllPublicationsToMavenCentralRepository",
                "publishMavenPublicationToMavenCentralRepository",
            ).forEach { taskName ->
                tasks.register(taskName) {
                    group = "publishing"
                    description = "阻止当前环境发布到 Maven Central"
                    doFirst {
                        throw GradleException(centralBlockedMessage)
                    }
                }
            }
        }
    }
}
