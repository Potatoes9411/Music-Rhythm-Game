plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    implementation("com.android.tools.build:apksig:2.3.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("com.rhythmphysics.buildtools.MainKt")
}

tasks.test { useJUnitPlatform() }
