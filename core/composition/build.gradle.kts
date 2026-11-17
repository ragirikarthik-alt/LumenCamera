plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":domain"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
