plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
