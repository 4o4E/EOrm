plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation(kotlin("reflect"))
    testImplementation("com.h2database:h2:2.2.224")
}