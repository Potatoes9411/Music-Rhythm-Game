import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Android application module.
 *
 * This module is compiled with the plain Kotlin/JVM plugin against the AOSP framework jar
 * (API 35) and packaged by a Maven-Central-only pipeline:
 *
 *   kotlinc -> lambda desugar (ASM) -> dx -> aapt2 (apktool prebuilt) -> align -> apksig (v1+v2)
 *
 * Reason: the cloud environment this project was built in cannot reach dl.google.com, which
 * serves the Android Gradle Plugin, AndroidX and the SDK. The app therefore depends only on the
 * Android framework, Kotlin, kotlinx-coroutines and kotlinx-serialization. See README "Building".
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val appVersionCode = 1
val appVersionName = "0.1.0"
val minSdk = 26
val targetSdk = 35
val androidAllVersion = "15-robolectric-13954326"

val androidPlatform: Configuration by configurations.creating
val aaptTool: Configuration by configurations.creating { isTransitive = false }
val dxTool: Configuration by configurations.creating
val buildTools: Configuration by configurations.creating

dependencies {
    compileOnly("org.robolectric:android-all:$androidAllVersion")
    androidPlatform("org.robolectric:android-all:$androidAllVersion")
    aaptTool("org.apktool:apktool-cli:3.0.3")
    dxTool("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
    buildTools(project(":buildtools"))

    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    compileOnly(files(layout.buildDirectory.dir("intermediates/r-classes")))

    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:android-all:$androidAllVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline")
    }
}

val androidJar = providers.provider { androidPlatform.singleFile }
val bd = layout.buildDirectory

// ---- aapt2 -------------------------------------------------------------------------------

val extractAapt2 by tasks.registering(Copy::class) {
    from(zipTree(aaptTool.singleFile)) { include("prebuilt/linux/aapt2") }
    into(bd.dir("tools"))
    eachFile { path = name }
    includeEmptyDirs = false
    doLast { bd.file("tools/aapt2").get().asFile.setExecutable(true) }
}

val aapt2Compile by tasks.registering(Exec::class) {
    dependsOn(extractAapt2)
    val resDir = file("src/main/res")
    val out = bd.file("intermediates/aapt/compiled-res.zip")
    inputs.dir(resDir)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine(bd.file("tools/aapt2").get().asFile, "compile", "--dir", resDir, "-o", out.get().asFile)
}

fun registerLink(variant: String, debuggable: Boolean) = tasks.register("aapt2Link${variant}", Exec::class) {
    dependsOn(aapt2Compile)
    val manifest = file("src/main/AndroidManifest.xml")
    val compiled = bd.file("intermediates/aapt/compiled-res.zip")
    val outApk = bd.file("intermediates/aapt/$variant/resources.ap_")
    val rDir = bd.dir("generated/r-java/$variant")
    inputs.file(manifest); inputs.file(compiled)
    outputs.file(outApk); outputs.dir(rDir)
    doFirst {
        outApk.get().asFile.parentFile.mkdirs()
        rDir.get().asFile.deleteRecursively(); rDir.get().asFile.mkdirs()
    }
    val args = mutableListOf<Any>(
        bd.file("tools/aapt2").get().asFile, "link",
        "-o", outApk.get().asFile,
        "-I", androidJar.get(),
        "--manifest", manifest,
        "--java", rDir.get().asFile,
        "--min-sdk-version", minSdk, "--target-sdk-version", targetSdk,
        "--version-code", appVersionCode, "--version-name", appVersionName,
        "--auto-add-overlay", "--no-version-vectors",
    )
    if (debuggable) args += "--debug-mode"
    args += compiled.get().asFile
    commandLine(args)
}

val aapt2LinkDebug = registerLink("debug", true)
val aapt2LinkRelease = registerLink("release", false)

val compileR by tasks.registering(JavaCompile::class) {
    dependsOn(aapt2LinkDebug)
    source(bd.dir("generated/r-java/debug"))
    classpath = files()
    destinationDirectory.set(bd.dir("intermediates/r-classes"))
    // R.java only contains int constants; the JDK's java.lang is sufficient.
    options.release.set(8)
    options.compilerArgs.addAll(listOf("-Xlint:-options"))
}

tasks.named("compileKotlin") { dependsOn(compileR) }

// ---- desugar + dex ----------------------------------------------------------------------

val runtimeJars = configurations.runtimeClasspath

val desugar by tasks.registering(JavaExec::class) {
    dependsOn("classes", compileR)
    val out = bd.dir("intermediates/desugared")
    val kotlinClasses = bd.dir("classes/kotlin/main")
    val rClasses = bd.dir("intermediates/r-classes")
    inputs.files(kotlinClasses, rClasses, runtimeJars)
    outputs.dir(out)
    classpath = buildTools
    mainClass.set("com.rhythmphysics.buildtools.MainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("desugar", out.get().asFile.path, kotlinClasses.get().asFile.path, rClasses.get().asFile.path) +
            runtimeJars.get().files.map { it.path }
    })
}

val scanIndy by tasks.registering(JavaExec::class) {
    dependsOn(desugar)
    val dir = bd.dir("intermediates/desugared")
    classpath = buildTools
    mainClass.set("com.rhythmphysics.buildtools.MainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("scan-indy") + (dir.get().asFile.listFiles()?.filter { it.name.endsWith(".jar") }?.map { it.path } ?: emptyList())
    })
}

val dex by tasks.registering(JavaExec::class) {
    dependsOn(desugar, scanIndy)
    val inDir = bd.dir("intermediates/desugared")
    val outDir = bd.dir("intermediates/dex")
    inputs.dir(inDir)
    outputs.dir(outDir)
    classpath = dxTool
    mainClass.set("com.android.dx.command.Main")
    maxHeapSize = "3g"
    doFirst { outDir.get().asFile.deleteRecursively(); outDir.get().asFile.mkdirs() }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--dex", "--min-sdk-version=$minSdk", "--multi-dex", "--output=${outDir.get().asFile.path}") +
            (inDir.get().asFile.listFiles()?.filter { it.name.endsWith(".jar") }?.sortedBy { it.name }?.map { it.path } ?: emptyList())
    })
}

// ---- package + sign ---------------------------------------------------------------------

// apksig 2.3.0 (the Maven Central build) reaches into JDK-internal X.509 classes.
val signerJvmArgs = listOf(
    "--add-exports=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-exports=java.base/sun.security.pkcs=ALL-UNNAMED",
    "--add-exports=java.base/sun.security.util=ALL-UNNAMED",
)

val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

val debugKeystoreTask by tasks.registering(Exec::class) {
    onlyIf { !debugKeystore.exists() }
    doFirst { debugKeystore.parentFile.mkdirs() }
    commandLine(
        "keytool", "-genkeypair", "-keystore", debugKeystore, "-storetype", "JKS",
        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US",
    )
}

fun registerPackage(variant: String, link: TaskProvider<Exec>) = tasks.register("package${variant.replaceFirstChar { it.uppercase() }}Apk", JavaExec::class) {
    dependsOn(dex, link)
    val res = bd.file("intermediates/aapt/$variant/resources.ap_")
    val dexDir = bd.dir("intermediates/dex")
    val assetsDir = file("src/main/assets")
    val out = bd.file("intermediates/apk/$variant/unsigned-aligned.apk")
    inputs.file(res); inputs.dir(dexDir); inputs.dir(assetsDir)
    outputs.file(out)
    classpath = buildTools
    mainClass.set("com.rhythmphysics.buildtools.MainKt")
    maxHeapSize = "2g"
    argumentProviders.add(CommandLineArgumentProvider {
        val assets = assetsDir.walkTopDown().filter { it.isFile }.map { "assets/${it.relativeTo(assetsDir).path.replace('\\', '/')}=${it.path}" }.toList()
        listOf("package", res.get().asFile.path, out.get().asFile.path, "assets/", dexDir.get().asFile.path) + assets
    })
}

val packageDebugApk = registerPackage("debug", aapt2LinkDebug)
val packageReleaseApk = registerPackage("release", aapt2LinkRelease)

val assembleDebugApk by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Builds, aligns and signs the debug APK (build/outputs/apk/debug)."
    dependsOn(packageDebugApk, debugKeystoreTask)
    jvmArgs(signerJvmArgs)
    val input = bd.file("intermediates/apk/debug/unsigned-aligned.apk")
    val out = bd.file("outputs/apk/debug/rhythm-physics-debug.apk")
    inputs.file(input)
    outputs.file(out)
    classpath = buildTools
    mainClass.set("com.rhythmphysics.buildtools.MainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("sign", input.get().asFile.path, out.get().asFile.path, debugKeystore.path, "android", "androiddebugkey", "android")
    })
}

val releaseStore = providers.gradleProperty("rp.release.storeFile")

val signReleaseApk by tasks.registering(JavaExec::class) {
    dependsOn(packageReleaseApk)
    onlyIf { releaseStore.isPresent }
    jvmArgs(signerJvmArgs)
    val input = bd.file("intermediates/apk/release/unsigned-aligned.apk")
    val out = bd.file("outputs/apk/release/rhythm-physics-release.apk")
    classpath = buildTools
    mainClass.set("com.rhythmphysics.buildtools.MainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "sign", input.get().asFile.path, out.get().asFile.path, releaseStore.get(),
            providers.gradleProperty("rp.release.storePassword").get(),
            providers.gradleProperty("rp.release.keyAlias").get(),
            providers.gradleProperty("rp.release.keyPassword").get(),
        )
    })
}

val assembleReleaseApk by tasks.registering(Copy::class) {
    group = "build"
    description = "Builds the aligned release APK (unsigned), and a signed copy when rp.release.* properties are set."
    dependsOn(packageReleaseApk, signReleaseApk)
    from(bd.file("intermediates/apk/release/unsigned-aligned.apk"))
    into(bd.dir("outputs/apk/release"))
    rename { "rhythm-physics-release-unsigned.apk" }
}

tasks.test { useJUnitPlatform() }
