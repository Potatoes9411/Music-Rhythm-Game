package com.rhythmphysics.buildtools

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.util.zip.ZipFile

/**
 * Poor man's lint "NewApi": checks every reference our classes make into platform classes
 * (android.*, java.*, javax.*, org.json, ...) against the framework jar of the *minimum* API level.
 * A reference that does not exist there is only safe behind a Build.VERSION.SDK_INT check, so
 * each finding reports whether the calling method reads SDK_INT (for manual review).
 */
object ApiCheck {
    private class Info(val superName: String?, val interfaces: List<String>, val members: Set<String>)

    class Finding(val caller: String, val ref: String, val guarded: Boolean) {
        override fun toString() = (if (guarded) "guarded   " else "UNGUARDED ") + "$ref   <- $caller"
    }

    /**
     * [apiJar]: framework jar of the min API (android.*, org.json...). [ctSym]: the JDK's ct.sym, whose
     * release-8 signatures stand in for the java.* surface (android-all jars ship no libcore).
     */
    fun run(apiJar: File, inputs: List<File>, ctSym: File? = null): List<Finding> {
        val api = HashMap<String, Info>()
        val sources = listOf(apiJar to { n: String -> if (n.endsWith(".class")) n.removeSuffix(".class") else null }) +
            listOfNotNull(ctSym?.let { it to { n: String ->
                // "<releases>/<module>/<pkg>/<Cls>.sig", keep entries valid for release 8
                val parts = n.split('/', limit = 3)
                if (parts.size == 3 && '8' in parts[0] && n.endsWith(".sig")) parts[2].removeSuffix(".sig") else null
            } })
        for ((jar, nameOf) in sources) ZipFile(jar).use { z ->
            for (e in z.entries()) {
                nameOf(e.name) ?: continue
                val cr = ClassReader(z.getInputStream(e).readBytes())
                val cn = ClassNode(); cr.accept(cn, ClassReader.SKIP_CODE)
                val members = HashSet<String>()
                cn.methods.forEach { members += it.name + it.desc }
                cn.fields.forEach { members += it.name + ":" }
                api[cn.name] = Info(cn.superName, cn.interfaces, members)
            }
        }
        val platformPrefixes = listOf("android/", "java/", "javax/", "org/json/", "org/xmlpull/", "dalvik/")
        fun isPlatform(owner: String) = platformPrefixes.any { owner.startsWith(it) }

        fun has(owner: String, member: String, seen: HashSet<String> = HashSet()): Boolean {
            if (!seen.add(owner)) return false
            val info = api[owner] ?: return false
            if (member in info.members) return true
            info.superName?.let { if (has(it, member, seen)) return true }
            return info.interfaces.any { has(it, member, seen) }
        }

        val findings = ArrayList<Finding>()
        val classes = ArrayList<ByteArray>()
        for (f in inputs) {
            if (f.isDirectory) f.walkTopDown().filter { it.name.endsWith(".class") }.forEach { classes += it.readBytes() }
            else if (f.name.endsWith(".jar")) ZipFile(f).use { z -> for (e in z.entries()) if (e.name.endsWith(".class") && !e.name.startsWith("META-INF/")) classes += z.getInputStream(e).readBytes() }
        }
        for (bytes in classes) {
            val cn = ClassNode(); ClassReader(bytes).accept(cn, 0)
            // Our own classes extending platform types: the superclass must exist.
            listOfNotNull(cn.superName).plus(cn.interfaces).filter(::isPlatform).forEach { t ->
                if (t !in api) findings += Finding(cn.name, "class $t", false)
            }
            for (m in cn.methods) {
                val guarded = m.instructions.any { it is FieldInsnNode && it.owner == "android/os/Build\$VERSION" && it.name == "SDK_INT" }
                val caller = "${cn.name}.${m.name}"
                for (insn in m.instructions) {
                    when (insn) {
                        is MethodInsnNode -> {
                            val owner = if (insn.owner.startsWith("[")) "java/lang/Object" else insn.owner
                            if (!isPlatform(owner)) continue
                            if (owner !in api) findings += Finding(caller, "class $owner", guarded)
                            else if (!has(owner, insn.name + insn.desc)) findings += Finding(caller, "$owner.${insn.name}${insn.desc}", guarded)
                        }
                        is FieldInsnNode -> {
                            if (!isPlatform(insn.owner)) continue
                            if (insn.owner !in api) findings += Finding(caller, "class ${insn.owner}", guarded)
                            else if (!has(insn.owner, insn.name + ":")) findings += Finding(caller, "${insn.owner}.${insn.name}", guarded)
                        }
                        is TypeInsnNode -> {
                            val t = Type.getObjectType(insn.desc).let { if (it.sort == Type.ARRAY) it.elementType else it }
                            if (t.sort == Type.OBJECT && isPlatform(t.internalName) && t.internalName !in api) findings += Finding(caller, "class ${t.internalName}", guarded)
                        }
                        is LdcInsnNode -> {
                            val c = insn.cst
                            if (c is Type && c.sort == Type.OBJECT && isPlatform(c.internalName) && c.internalName !in api) findings += Finding(caller, "class ${c.internalName}", guarded)
                        }
                    }
                }
            }
        }
        return findings.distinctBy { it.caller + it.ref }
    }
}
