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
