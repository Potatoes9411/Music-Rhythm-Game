plugins {
    kotlin("jvm")
    application
}

// JVM tooling: reference renderer (Java2D backend of the engine DrawList), screenshot matrix,
// fixture generation and analysis/synth probes. Not shipped in the APK.
dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("com.rhythmphysics.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx3g", "-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
