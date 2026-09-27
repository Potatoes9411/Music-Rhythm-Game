import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Android application module.
 *
 * This module is compiled with the plain Kotlin/JVM plugin against the AOSP framework jar
 * (API 35) and packaged by a Maven-Central-only pipeline:
 *
 *   kotlinc -> lambda desugar (ASM) -> dx -> aapt2 (apktool prebuilt) -> align -> apksig (v2)
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

// ---- Robolectric device-less app tests (source set "roboTest") ------------------------------
// Runs MainActivity / screens / Canvas renderer on the JVM with Robolectric's real framework code
// and native (Skia) graphics. androidx.test:monitor is only on Google Maven, so a tiny test-only
// shim (src/roboTest/java/androidx/test) stands in for it; see README there.
val roboTest: SourceSet = sourceSets.create("roboTest")
val roboTestImplementation: Configuration by configurations.getting { extendsFrom(configurations.implementation.get()) }
val roboAndroidAll: Configuration by configurations.creating { isTransitive = false }
val minApiPlatform: Configuration by configurations.creating { isTransitive = false }
dependencies {
    roboTestImplementation("org.robolectric:android-all:$androidAllVersion") // annotation defaults reference android classes
    roboTestImplementation("org.robolectric:robolectric:4.17") {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.espresso")
    }
    roboTestImplementation("junit:junit:4.13.2")
    roboAndroidAll("org.robolectric:android-all-instrumented:$androidAllVersion-i7")
    minApiPlatform("org.robolectric:android-all:8.0.0_r4-robolectric-r1") // API 26 = minSdk
}
roboTest.compileClasspath += sourceSets.main.get().output
roboTest.runtimeClasspath += sourceSets.main.get().output
// Tests run on the host JVM (Robolectric needs 11+); only the APK code must stay Java-8/dx compatible.
listOf("roboTestCompileClasspath", "roboTestRuntimeClasspath").forEach { n ->
    configurations.named(n) { attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17) } }
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileRoboTestKotlin") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
tasks.named<JavaCompile>("compileRoboTestJava") { sourceCompatibility = "17"; targetCompatibility = "17" }

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
    dependsOn(dex, link, rootProject.tasks.named("fetchSoundFont"))
    val res = bd.file("intermediates/aapt/$variant/resources.ap_")
    val dexDir = bd.dir("intermediates/dex")
    val assetsDir = file("src/main/assets")
    // Bundled GeneralUser GS SoundFont + its license (downloaded and checksum-verified by :fetchSoundFont).
    val soundFontDir = rootProject.layout.buildDirectory.dir("soundfont")
    inputs.dir(soundFontDir)
    val out = bd.file("intermediates/apk/$variant/unsigned-aligned.apk")
    inputs.file(res); inputs.dir(dexDir); inputs.dir(assetsDir)
    outputs.file(out)
    classpath = buildTools
    mainClass.set("com.rhythmphysics.buildtools.MainKt")
    maxHeapSize = "2g"
    argumentProviders.add(CommandLineArgumentProvider {
        val assets = assetsDir.walkTopDown().filter { it.isFile }.map { "assets/${it.relativeTo(assetsDir).path.replace('\\', '/')}=${it.path}" }.toList()
        val sf = soundFontDir.get().asFile
        val soundFonts = listOf("GeneralUser-GS.sf2", "GeneralUser-LICENSE.txt").map { "assets/soundfonts/$it=${File(sf, it).path}" }
        // Assets are deflated (the SoundFont is read fully into memory anyway); media types stay stored.
        listOf("package", res.get().asFile.path, out.get().asFile.path, "-", dexDir.get().asFile.path) + assets + soundFonts
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

val roboConfig by tasks.registering {
    description = "Robolectric inputs: text manifest with uses-sdk, merged assets, linked resources, offline android-all."
    // The packaged (unsigned) debug APK doubles as Robolectric's resource APK, so tests see exactly
    // the resources and assets (bundled SoundFont) that ship.
    dependsOn(packageDebugApk, rootProject.tasks.named("fetchSoundFont"))
    val manifestIn = file("src/main/AndroidManifest.xml")
    val outDir = bd.dir("intermediates/robo")
    inputs.file(manifestIn); inputs.files(roboAndroidAll)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        val manifest = File(dir, "AndroidManifest.xml")
        manifest.parentFile.mkdirs()
        manifest.writeText(manifestIn.readText().replaceFirst(Regex("(<manifest[^>]*>)"),
            "$1\n    <uses-sdk android:minSdkVersion=\"$minSdk\" android:targetSdkVersion=\"$targetSdk\" />"))
        val assets = File(dir, "assets/soundfonts"); assets.mkdirs()
        val sf = rootProject.layout.buildDirectory.dir("soundfont").get().asFile
        listOf("GeneralUser-GS.sf2", "GeneralUser-LICENSE.txt").forEach { n ->
            val src = File(sf, n); val dst = File(assets, n)
            if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
        }
        val jars = File(dir, "jars"); jars.mkdirs()
        roboAndroidAll.files.forEach { f -> val d = File(jars, f.name); if (!d.exists()) f.copyTo(d) }
        val props = File(dir, "config/com/android/tools/test_config.properties"); props.parentFile.mkdirs()
        props.writeText(listOf(
            "android_merged_manifest=" + manifest.absolutePath,
            "android_merged_assets=" + File(dir, "assets").absolutePath,
            "android_resource_apk=" + bd.file("intermediates/apk/debug/unsigned-aligned.apk").get().asFile.absolutePath,
            "android_custom_package=com.rhythmphysics.app",
        ).joinToString("\n") + "\n")
    }
}
roboTest.runtimeClasspath += files(bd.dir("intermediates/robo/config"))

val roboTestTask = tasks.register<Test>("roboTest") {
    description = "Robolectric app tests (activity, screens, Android Canvas backend screenshots)."
    group = "verification"
    dependsOn(roboConfig)
    testClassesDirs = roboTest.output.classesDirs
    classpath = roboTest.runtimeClasspath
    useJUnit()
    maxHeapSize = "4g"
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", bd.dir("intermediates/robo/jars").get().asFile.absolutePath)
    systemProperty("rp.artifacts", rootProject.file("artifacts").absolutePath)
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    outputs.upToDateWhen { false }
}

val apiCheck by tasks.registering(JavaExec::class) {
    description = "Lists app/core references to platform APIs missing on minSdk ($minSdk), flagging unguarded ones."
    group = "verification"
    dependsOn(tasks.named("compileKotlin"), project(":core").tasks.named("compileKotlin"))
    classpath = buildTools
    mainClass.set("com.rhythmphysics.buildtools.MainKt")
    maxHeapSize = "2g"
    val report = rootProject.file("artifacts/test-reports/api-check-min26.txt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("api-check", minApiPlatform.singleFile.path, report.path, file("api-check-allowlist.txt").path,
            bd.dir("classes/kotlin/main").get().asFile.path,
            project(":core").layout.buildDirectory.dir("classes/kotlin/main").get().asFile.path)
    })
}

// Every APK build re-verifies minSdk API compatibility.
listOf("assembleDebugApk", "assembleReleaseApk").forEach { n -> tasks.named(n) { dependsOn(apiCheck) } }
