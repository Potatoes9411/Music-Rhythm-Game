package com.rhythmphysics.buildtools

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Minimal lambda desugarer.
 *
 * ART does not ship `java.lang.invoke.LambdaMetafactory`, and the dx dexer (the only
 * dexer reachable from Maven Central) does not desugar lambdas the way D8 does. This
 * pass rewrites every `invokedynamic` that bootstraps through LambdaMetafactory into a
 * call to a generated, ordinary class implementing the functional interface.
 *
 * Only Java-8-level constructs are handled (that is all dx accepts anyway).
 */
object Desugar {
    private const val LMF = "java/lang/invoke/LambdaMetafactory"
    private const val FLAG_SERIALIZABLE = 1
    private const val FLAG_MARKERS = 2
    private const val FLAG_BRIDGES = 4

    data class Stats(var classes: Int = 0, var lambdas: Int = 0, var skipped: Int = 0)

    /** Desugars one jar or class directory into [output] (a jar). */
    fun process(input: File, output: File, stats: Stats) {
        output.parentFile.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zos ->
            val seen = HashSet<String>()
            fun emit(name: String, bytes: ByteArray) {
                if (!seen.add(name)) return
                val e = ZipEntry(name)
                e.time = 0L
                zos.putNextEntry(e)
                zos.write(bytes)
                zos.closeEntry()
            }
            forEachEntry(input) { name, bytes ->
                when {
                    name.startsWith("META-INF/versions/") -> stats.skipped++
                    name.endsWith("module-info.class") -> stats.skipped++
                    name.endsWith(".class") -> {
                        stats.classes++
                        for ((outName, outBytes) in transformClass(bytes, stats)) emit(outName, outBytes)
                    }
                    name.endsWith("/") -> Unit
                    name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) -> Unit
                    else -> emit(name, bytes)
                }
            }
        }
    }

    private fun forEachEntry(input: File, block: (String, ByteArray) -> Unit) {
        if (input.isDirectory) {
            input.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                block(f.relativeTo(input).invariantSeparatorsPath, f.readBytes())
            }
        } else {
            ZipFile(input).use { zf ->
                zf.entries().toList().sortedBy { it.name }.forEach { e ->
                    if (!e.isDirectory) block(e.name, zf.getInputStream(e).readBytes())
                }
            }
        }
    }

    /** Returns (entryName, bytes) pairs: the rewritten class plus any generated lambda classes. */
    fun transformClass(bytes: ByteArray, stats: Stats = Stats()): List<Pair<String, ByteArray>> {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val host = node.name
        val isInterfaceHost = node.access and Opcodes.ACC_INTERFACE != 0
        val generated = ArrayList<Pair<String, ByteArray>>()
        val relax = HashSet<String>() // name+desc of private methods to relax
        val accessors = ArrayList<MethodNode>()
        var counter = 0
        var changed = false

        for (m in node.methods.toList()) {
            val insns = m.instructions ?: continue
            for (insn in insns.toArray()) {
                if (insn !is InvokeDynamicInsnNode) continue
                if (insn.bsm.owner != LMF) continue
                val alt = insn.bsm.name == "altMetafactory"
                val args = insn.bsmArgs
                val samType = args[0] as Type
                var impl = args[1] as Handle
                val instantiated = args[2] as Type
                val interfaces = ArrayList<String>()
                val bridges = ArrayList<Type>()
                if (alt) {
                    var i = 3
                    val flags = args[i++] as Int
                    if (flags and FLAG_MARKERS != 0) {
                        val n = args[i++] as Int
                        repeat(n) { interfaces += (args[i++] as Type).internalName }
                    }
                    if (flags and FLAG_BRIDGES != 0) {
                        val n = args[i++] as Int
                        repeat(n) { bridges += args[i++] as Type }
                    }
                    if (flags and FLAG_SERIALIZABLE != 0) interfaces += "java/io/Serializable"
                }

                // Private targets in the host must become reachable from the generated class.
                if (impl.owner == host) {
                    val target = node.methods.firstOrNull { it.name == impl.name && it.desc == impl.desc }
                    if (target != null && target.access and Opcodes.ACC_PRIVATE != 0) {
                        relax += impl.name + impl.desc
                        if (impl.tag == Opcodes.H_INVOKESPECIAL) {
                            impl = Handle(
                                if (isInterfaceHost) Opcodes.H_INVOKEINTERFACE else Opcodes.H_INVOKEVIRTUAL,
                                impl.owner, impl.name, impl.desc, isInterfaceHost,
                            )
                        }
                    }
                } else if (impl.tag == Opcodes.H_INVOKESPECIAL) {
                    // super::method reference: route through a static accessor on the host.
                    val acc = makeSpecialAccessor(host, impl, accessors.size)
                    accessors += acc
                    impl = Handle(Opcodes.H_INVOKESTATIC, host, acc.name, acc.desc, isInterfaceHost)
                }

                val ifaceType = Type.getReturnType(insn.desc)
                val lambdaName = "$host\$\$Lambda\$${counter++}"
                generated += "$lambdaName.class" to generateLambdaClass(
                    lambdaName, ifaceType.internalName, interfaces, insn.name,
                    samType, instantiated, impl, Type.getArgumentTypes(insn.desc), bridges,
                )
                val replacement = MethodInsnNode(
                    Opcodes.INVOKESTATIC, lambdaName, "create",
                    Type.getMethodDescriptor(ifaceType, *Type.getArgumentTypes(insn.desc)), false,
                )
                insns.set(insn, replacement)
                stats.lambdas++
                changed = true
            }
        }

        if (!changed) return listOf("$host.class" to bytes)

        for (m in node.methods) {
            if (relax.contains(m.name + m.desc)) {
                m.access = m.access and Opcodes.ACC_PRIVATE.inv()
                if (isInterfaceHost) m.access = m.access or Opcodes.ACC_PUBLIC
            }
        }
        node.methods.addAll(accessors)
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(cw)
        return listOf("$host.class" to cw.toByteArray()) + generated
    }

    private fun makeSpecialAccessor(host: String, impl: Handle, index: Int): MethodNode {
        val implArgs = Type.getArgumentTypes(impl.desc)
        val desc = Type.getMethodDescriptor(Type.getReturnType(impl.desc), Type.getObjectType(host), *implArgs)
        val mn = MethodNode(Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, "access\$super\$$index", desc, null, null)
        val il = InsnList()
        var slot = 0
        il.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, slot++))
        for (t in implArgs) {
            il.add(org.objectweb.asm.tree.VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot))
            slot += t.size
        }
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, impl.owner, impl.name, impl.desc, impl.isInterface))
        il.add(org.objectweb.asm.tree.InsnNode(Type.getReturnType(impl.desc).getOpcode(Opcodes.IRETURN)))
        mn.instructions = il
        return mn
    }

    private fun generateLambdaClass(
        name: String,
        iface: String,
        extraIfaces: List<String>,
        samName: String,
        samType: Type,
        instantiated: Type,
        impl: Handle,
        captured: Array<Type>,
        bridges: List<Type>,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val ifaces = (listOf(iface) + extraIfaces).distinct().toTypedArray()
        cw.visit(
            Opcodes.V1_6,
            Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_SYNTHETIC,
            name, null, "java/lang/Object", ifaces,
        )
        captured.forEachIndexed { i, t ->
            cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "f$i", t.descriptor, null, null).visitEnd()
        }
        val selfType = Type.getObjectType(name)

        // constructor
        run {
            val ctor = Method("<init>", Type.VOID_TYPE, captured)
            val g = GeneratorAdapter(Opcodes.ACC_PRIVATE, ctor, null, null, cw)
            g.visitCode()
            g.loadThis()
            g.invokeConstructor(Type.getObjectType("java/lang/Object"), Method.getMethod("void <init>()"))
            captured.forEachIndexed { i, t ->
                g.loadThis(); g.loadArg(i); g.putField(selfType, "f$i", t)
            }
            g.returnValue(); g.endMethod()
        }
        // static factory
        run {
            val fm = Method("create", Type.getObjectType(iface), captured)
            val g = GeneratorAdapter(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, fm, null, null, cw)
            g.visitCode()
            g.newInstance(selfType); g.dup()
            for (i in captured.indices) g.loadArg(i)
            g.invokeConstructor(selfType, Method("<init>", Type.VOID_TYPE, captured))
            g.returnValue(); g.endMethod()
        }
        // SAM implementation
        run {
            val sm = Method(samName, samType.descriptor)
            val g = GeneratorAdapter(Opcodes.ACC_PUBLIC, sm, null, null, cw)
            g.visitCode()
            val implArgs = Type.getArgumentTypes(impl.desc)
            val hasReceiver = impl.tag == Opcodes.H_INVOKEVIRTUAL || impl.tag == Opcodes.H_INVOKEINTERFACE ||
                impl.tag == Opcodes.H_INVOKESPECIAL
            val targetParams = ArrayList<Type>()
            if (hasReceiver) targetParams += Type.getObjectType(impl.owner)
            targetParams += implArgs
            if (impl.tag == Opcodes.H_NEWINVOKESPECIAL) {
                g.newInstance(Type.getObjectType(impl.owner)); g.dup()
            }
            var p = 0
            captured.forEachIndexed { i, t ->
                g.loadThis(); g.getField(selfType, "f$i", t)
                convert(g, t, targetParams[p]); p++
            }
            val samArgs = samType.argumentTypes
            val instArgs = instantiated.argumentTypes
            for (i in samArgs.indices) {
                g.loadArg(i)
                val via = instArgs.getOrElse(i) { samArgs[i] }
                convert(g, samArgs[i], via)
                convert(g, via, targetParams[p]); p++
            }
            when (impl.tag) {
                Opcodes.H_INVOKESTATIC -> g.visitMethodInsn(Opcodes.INVOKESTATIC, impl.owner, impl.name, impl.desc, impl.isInterface)
                Opcodes.H_INVOKEVIRTUAL -> g.visitMethodInsn(Opcodes.INVOKEVIRTUAL, impl.owner, impl.name, impl.desc, false)
                Opcodes.H_INVOKEINTERFACE -> g.visitMethodInsn(Opcodes.INVOKEINTERFACE, impl.owner, impl.name, impl.desc, true)
                Opcodes.H_INVOKESPECIAL -> g.visitMethodInsn(Opcodes.INVOKEVIRTUAL, impl.owner, impl.name, impl.desc, false)
                Opcodes.H_NEWINVOKESPECIAL -> g.visitMethodInsn(Opcodes.INVOKESPECIAL, impl.owner, "<init>", impl.desc, false)
                else -> error("Unsupported handle kind ${impl.tag} in $name")
            }
            val produced = if (impl.tag == Opcodes.H_NEWINVOKESPECIAL) Type.getObjectType(impl.owner) else Type.getReturnType(impl.desc)
            val wanted = samType.returnType
            if (wanted.sort == Type.VOID) {
                if (produced.sort != Type.VOID) g.pop(produced)
            } else {
                convert(g, produced, instantiated.returnType)
                convert(g, instantiated.returnType, wanted)
            }
            g.returnValue(); g.endMethod()
        }
        // Bridges delegate to the main SAM method.
        for (b in bridges) {
            if (b.descriptor == samType.descriptor) continue
            val bm = Method(samName, b.descriptor)
            val g = GeneratorAdapter(Opcodes.ACC_PUBLIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC, bm, null, null, cw)
            g.visitCode()
            g.loadThis()
            b.argumentTypes.forEachIndexed { i, t -> g.loadArg(i); convert(g, t, samType.argumentTypes[i]) }
            g.invokeVirtual(selfType, Method(samName, samType.descriptor))
            if (b.returnType.sort == Type.VOID) {
                if (samType.returnType.sort != Type.VOID) g.pop(samType.returnType)
            } else convert(g, samType.returnType, b.returnType)
            g.returnValue(); g.endMethod()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun GeneratorAdapter.pop(t: Type) { if (t.size == 2) pop2() else pop() }

    private val OBJECT = Type.getObjectType("java/lang/Object")

    private fun isPrimitive(t: Type) = t.sort in Type.BOOLEAN..Type.DOUBLE

    private fun boxOf(t: Type): Type = when (t.sort) {
        Type.BOOLEAN -> Type.getObjectType("java/lang/Boolean")
        Type.CHAR -> Type.getObjectType("java/lang/Character")
        Type.BYTE -> Type.getObjectType("java/lang/Byte")
        Type.SHORT -> Type.getObjectType("java/lang/Short")
        Type.INT -> Type.getObjectType("java/lang/Integer")
        Type.FLOAT -> Type.getObjectType("java/lang/Float")
        Type.LONG -> Type.getObjectType("java/lang/Long")
        Type.DOUBLE -> Type.getObjectType("java/lang/Double")
        else -> t
    }

    private fun primitiveOfBox(t: Type): Type? = when (t.internalName) {
        "java/lang/Boolean" -> Type.BOOLEAN_TYPE
        "java/lang/Character" -> Type.CHAR_TYPE
        "java/lang/Byte" -> Type.BYTE_TYPE
        "java/lang/Short" -> Type.SHORT_TYPE
        "java/lang/Integer" -> Type.INT_TYPE
        "java/lang/Float" -> Type.FLOAT_TYPE
        "java/lang/Long" -> Type.LONG_TYPE
        "java/lang/Double" -> Type.DOUBLE_TYPE
        else -> null
    }

    /** Emits a LambdaMetafactory-compatible adaptation from [from] to [to]. */
    private fun convert(g: GeneratorAdapter, from: Type, to: Type) {
        if (from == to) return
        val fp = isPrimitive(from)
        val tp = isPrimitive(to)
        when {
            fp && tp -> g.cast(from, to)
            fp && !tp -> {
                g.valueOf(from) // Integer.valueOf etc.
                if (to != OBJECT && to != boxOf(from)) g.checkCast(to)
            }
            !fp && tp -> {
                val prim = if (from.sort == Type.OBJECT) primitiveOfBox(from) else null
                if (prim != null) {
                    g.unbox(prim)
                    if (prim != to) g.cast(prim, to)
                } else {
                    g.unbox(to) // checkcast to Number/Boolean/Character then xxxValue()
                }
            }
            else -> if (to != OBJECT) g.checkCast(to)
        }
    }
}
