package com.rhythmphysics.buildtools

import java.io.File

/**
 * Command-line entry used by the app module's APK pipeline tasks.
 *
 *   desugar <outDir> <input...>
 *   package <resources.ap_> <out.apk> <storePrefixes,comma> <dexDir> [assetName=file ...]
 *   sign <in.apk> <out.apk> <keystore> <storePass> <alias> <keyPass>
 *   scan-indy <jar...>        (fails if any LambdaMetafactory indy remains)
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "desugar" -> {
            val outDir = File(args[1])
            outDir.deleteRecursively(); outDir.mkdirs()
            val stats = Desugar.Stats()
            args.drop(2).forEachIndexed { i, path ->
                val f = File(path)
                if (!f.exists()) return@forEachIndexed
                val base = if (f.isDirectory) "dir$i" else f.nameWithoutExtension
                Desugar.process(f, File(outDir, "%03d-%s.jar".format(i, base)), stats)
            }
            println("desugar: ${stats.classes} classes, ${stats.lambdas} lambdas rewritten, ${stats.skipped} entries skipped")
        }
        "package" -> {
            val res = File(args[1]); val out = File(args[2])
            val store = args[3].split(',').filter { it.isNotBlank() }.toSet()
            val dexes = File(args[4]).listFiles { f -> f.name.endsWith(".dex") }!!.toList()
            val assets = args.drop(5).associate { a -> a.substringBefore('=') to File(a.substringAfter('=')) }
            ApkPackager.build(res, dexes, assets, out, store)
            println("package: ${out.length()} bytes, ${dexes.size} dex")
        }
        "sign" -> {
            ApkPackager.sign(File(args[1]), File(args[2]), File(args[3]), args[4], args[5], args[6])
            println("sign: ${File(args[2]).length()} bytes")
        }
        "api-check" -> {
            // api-check <min-api framework jar> <report file> <allowlist> <classes dir|jar>...
            // java.* is checked against the JDK's ct.sym (release 8); android.* against the min-API jar.
            val ctSym = File(System.getProperty("java.home"), "lib/ct.sym").takeIf { it.exists() }
            val findings = ApiCheck.run(File(args[1]), args.drop(4).map(::File), ctSym)
            val report = File(args[2]); report.parentFile?.mkdirs()
            report.writeText("# :app:apiCheck — references missing on minSdk; 'guarded' = caller reads Build.VERSION.SDK_INT\n" +
                findings.joinToString("\n", postfix = "\n") { it.toString() })
            val allowed = File(args[3]).takeIf { it.exists() }?.readLines()?.map { it.substringBefore('#').trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            // Entries are "ref <- caller"; "* <- some/Prefix*" allows every finding in callers with that prefix.
            val prefixes = allowed.filter { it.startsWith("* <- ") && it.endsWith("*") }.map { it.removePrefix("* <- ").removeSuffix("*") }
            val bad = findings.filter { f -> !f.guarded && "${f.ref.removePrefix("class ")} <- ${f.caller}" !in allowed && prefixes.none { f.caller.startsWith(it) } }
            println("api-check: ${findings.size} references newer than min API, ${bad.size} unguarded and not allow-listed -> $report")
            bad.forEach { println("  $it") }
            if (bad.isNotEmpty()) { System.err.println("api-check FAILED"); kotlin.system.exitProcess(1) }
        }
        "scan-indy" -> {
            var bad = 0
            args.drop(1).forEach { path ->
                java.util.zip.ZipFile(path).use { zf ->
                    zf.entries().toList().filter { it.name.endsWith(".class") }.forEach { e ->
                        val bytes = zf.getInputStream(e).readBytes()
                        val cn = org.objectweb.asm.tree.ClassNode()
                        org.objectweb.asm.ClassReader(bytes).accept(cn, 0)
                        cn.methods.forEach { m ->
                            m.instructions?.forEach { insn ->
                                if (insn is org.objectweb.asm.tree.InvokeDynamicInsnNode) {
                                    bad++
                                    if (bad < 20) println("indy remains: ${cn.name}.${m.name} -> ${insn.bsm.owner}.${insn.bsm.name}")
                                }
                            }
                        }
                    }
                }
            }
            if (bad > 0) error("$bad invokedynamic instructions remain")
            println("scan-indy: clean")
        }
        else -> error("unknown command ${args.toList()}")
    }
}
