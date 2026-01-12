plugins {
    kotlin("jvm") version "2.2.20"
}

group = "top.e404.eorm"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.clojars.org/")
}

dependencies {
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.20")
    testImplementation(kotlin("test"))
    testImplementation("com.h2database:h2:2.2.224")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(8)
}