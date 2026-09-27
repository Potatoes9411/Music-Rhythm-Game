import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
}

/*
 * Default General MIDI SoundFont: GeneralUser GS 1.471 by S. Christian Collins.
 * License (bundled as assets/soundfonts/GeneralUser-LICENSE.txt) explicitly allows use in software
 * projects. Fetched from the npm registry package "generaluser" and verified by SHA-256 so the
 * 31 MB binary does not live in git.
 */
val soundFontDir = layout.buildDirectory.dir("soundfont")
val fetchSoundFont by tasks.registering {
    val tgzUrl = "https://registry.npmjs.org/generaluser/-/generaluser-1.47.1.tgz"
    val tgzSha = "a2663987d6d46ef55ab6392aafec8157f3bd97c73840a83700c956f772f53c02"
    val sf2Sha = "f45b6b4a68b6bf3d792fcbb6d7de24dc701a0f89c5900a21ef3aaece993b839a"
    outputs.dir(soundFontDir)
    doLast {
        val dir = soundFontDir.get().asFile
        val sf2 = File(dir, "GeneralUser-GS.sf2")
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (sf2.exists() && sha(sf2) == sf2Sha) return@doLast
        dir.mkdirs()
        val tgz = File(dir, "generaluser.tgz")
        uri(tgzUrl).toURL().openStream().use { input -> tgz.outputStream().use { input.copyTo(it) } }
        check(sha(tgz) == tgzSha) { "SoundFont archive checksum mismatch" }
        copy {
            from(tarTree(resources.gzip(tgz))) { include("package/GeneralUser.sf2", "package/LICENSE.txt") }
            into(dir)
            eachFile { path = if (name.endsWith(".sf2")) "GeneralUser-GS.sf2" else "GeneralUser-LICENSE.txt" }
            includeEmptyDirs = false
        }
        check(sha(sf2) == sf2Sha) { "SoundFont checksum mismatch" }
        tgz.delete()
    }
}
