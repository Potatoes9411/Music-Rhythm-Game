package com.rhythmphysics.core.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder

class SoundFontException(msg: String) : Exception(msg)

/** SF2 generator operators (SoundFont 2.01 §8.1.2). */
object Gen {
    const val START_ADDRS_OFFSET = 0; const val END_ADDRS_OFFSET = 1; const val STARTLOOP_ADDRS_OFFSET = 2
    const val ENDLOOP_ADDRS_OFFSET = 3; const val START_ADDRS_COARSE = 4; const val MOD_LFO_TO_PITCH = 5
    const val VIB_LFO_TO_PITCH = 6; const val MOD_ENV_TO_PITCH = 7; const val INITIAL_FILTER_FC = 8
    const val INITIAL_FILTER_Q = 9; const val MOD_LFO_TO_FILTER_FC = 10; const val MOD_ENV_TO_FILTER_FC = 11
    const val END_ADDRS_COARSE = 12; const val MOD_LFO_TO_VOLUME = 13; const val CHORUS_SEND = 15
    const val REVERB_SEND = 16; const val PAN = 17; const val DELAY_MOD_LFO = 21; const val FREQ_MOD_LFO = 22
    const val DELAY_VIB_LFO = 23; const val FREQ_VIB_LFO = 24; const val DELAY_MOD_ENV = 25; const val ATTACK_MOD_ENV = 26
    const val HOLD_MOD_ENV = 27; const val DECAY_MOD_ENV = 28; const val SUSTAIN_MOD_ENV = 29; const val RELEASE_MOD_ENV = 30
    const val KEY_TO_MOD_ENV_HOLD = 31; const val KEY_TO_MOD_ENV_DECAY = 32; const val DELAY_VOL_ENV = 33
    const val ATTACK_VOL_ENV = 34; const val HOLD_VOL_ENV = 35; const val DECAY_VOL_ENV = 36; const val SUSTAIN_VOL_ENV = 37
    const val RELEASE_VOL_ENV = 38; const val KEY_TO_VOL_ENV_HOLD = 39; const val KEY_TO_VOL_ENV_DECAY = 40
    const val INSTRUMENT = 41; const val KEY_RANGE = 43; const val VEL_RANGE = 44; const val STARTLOOP_ADDRS_COARSE = 45
    const val KEYNUM = 46; const val VELOCITY = 47; const val INITIAL_ATTENUATION = 48; const val ENDLOOP_ADDRS_COARSE = 50
    const val COARSE_TUNE = 51; const val FINE_TUNE = 52; const val SAMPLE_ID = 53; const val SAMPLE_MODES = 54
    const val SCALE_TUNING = 56; const val EXCLUSIVE_CLASS = 57; const val OVERRIDING_ROOT_KEY = 58
    const val COUNT = 61

    val DEFAULTS = IntArray(COUNT).also { d ->
        d[INITIAL_FILTER_FC] = 13500
        for (g in intArrayOf(DELAY_MOD_LFO, DELAY_VIB_LFO, DELAY_MOD_ENV, ATTACK_MOD_ENV, HOLD_MOD_ENV, DECAY_MOD_ENV,
            RELEASE_MOD_ENV, DELAY_VOL_ENV, ATTACK_VOL_ENV, HOLD_VOL_ENV, DECAY_VOL_ENV, RELEASE_VOL_ENV)) d[g] = -12000
        d[SCALE_TUNING] = 100
        d[OVERRIDING_ROOT_KEY] = -1
        d[KEYNUM] = -1
        d[VELOCITY] = -1
        d[KEY_RANGE] = 127 shl 8 // lo=0, hi=127 (packed lo | hi<<8)
        d[VEL_RANGE] = 127 shl 8
    }

    /** Generators that only exist at instrument level (not summed with preset values). */
    val NON_ADDITIVE = setOf(START_ADDRS_OFFSET, END_ADDRS_OFFSET, STARTLOOP_ADDRS_OFFSET, ENDLOOP_ADDRS_OFFSET,
        START_ADDRS_COARSE, END_ADDRS_COARSE, STARTLOOP_ADDRS_COARSE, ENDLOOP_ADDRS_COARSE, KEYNUM, VELOCITY,
        SAMPLE_ID, SAMPLE_MODES, EXCLUSIVE_CLASS, OVERRIDING_ROOT_KEY, KEY_RANGE, VEL_RANGE, INSTRUMENT)
}

/** SF2 modulator (§8.2). */
data class Modulator(val src: Int, val dest: Int, val amount: Int, val amtSrc: Int, val transform: Int) {
    /** Identity for override rules (all but amount). */
    val key: Long get() = (src.toLong() shl 48) or (dest.toLong() shl 32) or (amtSrc.toLong() shl 16) or transform.toLong()
}

class SampleHeader(
    val name: String, val start: Int, val end: Int, val loopStart: Int, val loopEnd: Int,
    val sampleRate: Int, val originalPitch: Int, val pitchCorrection: Int, val link: Int, val type: Int,
)

/** A zone with its generator values set (flag array) and modulators. */
class Zone(val gens: IntArray, val set: BooleanArray, val mods: List<Modulator>) {
    fun keyLo() = if (set[Gen.KEY_RANGE]) gens[Gen.KEY_RANGE] and 0xFF else 0
    fun keyHi() = if (set[Gen.KEY_RANGE]) (gens[Gen.KEY_RANGE] ushr 8) and 0xFF else 127
    fun velLo() = if (set[Gen.VEL_RANGE]) gens[Gen.VEL_RANGE] and 0xFF else 0
    fun velHi() = if (set[Gen.VEL_RANGE]) (gens[Gen.VEL_RANGE] ushr 8) and 0xFF else 127
    fun matches(key: Int, vel: Int) = key in keyLo()..keyHi() && vel in velLo()..velHi()
}

class Instrument(val name: String, val global: Zone?, val zones: List<Zone>)
class Preset(val name: String, val program: Int, val bank: Int, val global: Zone?, val zones: List<Zone>)

/** One fully resolved layer for a note: final generator values + modulators + sample. */
class ResolvedLayer(val gens: IntArray, val mods: List<Modulator>, val sample: SampleHeader)

/**
 * Parsed SoundFont 2 bank. Sample data is kept as 16-bit PCM (24-bit extension ignored).
 */
class SoundFont(val name: String, val samples: ShortArray, val sampleHeaders: List<SampleHeader>, val instruments: List<Instrument>, val presets: List<Preset>) {

    private val presetIndex = presets.associateBy { (it.bank shl 8) or it.program }

    fun findPreset(bank: Int, program: Int): Preset? =
        presetIndex[(bank shl 8) or program]
            ?: (if (bank == 128) presetIndex[(128 shl 8)] else presetIndex[program])
            ?: presets.firstOrNull { it.bank == bank }
            ?: presets.firstOrNull()

    /** Resolves every layer that sounds for (preset, key, velocity). */
    fun resolve(preset: Preset, key: Int, vel: Int): List<ResolvedLayer> {
        val out = ArrayList<ResolvedLayer>(4)
        for (pz in preset.zones) {
            if (!pz.matches(key, vel)) continue
            val instIdx = pz.gens[Gen.INSTRUMENT]
            val inst = instruments.getOrNull(instIdx) ?: continue
            // Preset-level values (global overridden by zone), relative offsets.
            val pg = IntArray(Gen.COUNT); val pset = BooleanArray(Gen.COUNT)
            preset.global?.let { g -> for (i in 0 until Gen.COUNT) if (g.set[i]) { pg[i] = g.gens[i]; pset[i] = true } }
            for (i in 0 until Gen.COUNT) if (pz.set[i]) { pg[i] = pz.gens[i]; pset[i] = true }
            for (iz in inst.zones) {
                if (!iz.matches(key, vel)) continue
                val gens = Gen.DEFAULTS.copyOf()
                inst.global?.let { g -> for (i in 0 until Gen.COUNT) if (g.set[i]) gens[i] = g.gens[i] }
                for (i in 0 until Gen.COUNT) if (iz.set[i]) gens[i] = iz.gens[i]
                for (i in 0 until Gen.COUNT) if (pset[i] && i !in Gen.NON_ADDITIVE) gens[i] += pg[i]
                val sample = sampleHeaders.getOrNull(gens[Gen.SAMPLE_ID]) ?: continue
                if (sample.type and 0x8000 != 0) continue // ROM samples unsupported
                // Modulators: defaults, overridden by instrument global, then instrument zone (by identity);
                // preset modulators are added on top.
                val mods = LinkedHashMap<Long, Modulator>()
                for (m in DefaultModulators.list) mods[m.key] = m
                inst.global?.mods?.forEach { mods[it.key] = it }
                iz.mods.forEach { mods[it.key] = it }
                val all = ArrayList(mods.values)
                preset.global?.mods?.let { all.addAll(it) }
                all.addAll(pz.mods)
                out += ResolvedLayer(gens, all, sample)
            }
        }
        return out
    }

    companion object {
        fun parse(bytes: ByteArray): SoundFont {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun id(off: Int) = String(bytes, off, 4, Charsets.ISO_8859_1)
            if (bytes.size < 12 || id(0) != "RIFF" || id(8) != "sfbk") throw SoundFontException("Not a SoundFont 2 file")
            val chunks = HashMap<String, Pair<Int, Int>>() // name -> (offset, length)
            var name = "SoundFont"
            fun walk(start: Int, end: Int) {
                var off = start
                while (off + 8 <= end) {
                    val cid = id(off)
                    val len = bb.getInt(off + 4)
                    if (len < 0 || off + 8 + len > bytes.size) throw SoundFontException("Corrupt chunk $cid")
                    if (cid == "LIST") walk(off + 12, off + 8 + len) else chunks[cid] = (off + 8) to len
                    if (cid == "INAM") name = String(bytes, off + 8, len, Charsets.ISO_8859_1).trimEnd('\u0000').trim()
                    off += 8 + len + (len and 1)
                }
            }
            walk(12, minOf(bytes.size, 8 + bb.getInt(4)))
            fun need(n: String) = chunks[n] ?: throw SoundFontException("Missing $n chunk")
            val (smplOff, smplLen) = need("smpl")
            val samples = ShortArray(smplLen / 2)
            ByteBuffer.wrap(bytes, smplOff, smplLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

            fun str(off: Int) = String(bytes, off, 20, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
            val (shdrOff, shdrLen) = need("shdr")
            val shdrs = (0 until shdrLen / 46 - 1).map { i ->
                val o = shdrOff + i * 46
                SampleHeader(str(o), bb.getInt(o + 20), bb.getInt(o + 24), bb.getInt(o + 28), bb.getInt(o + 32),
                    bb.getInt(o + 36), bytes[o + 40].toInt() and 0xFF, bytes[o + 41].toInt(), bb.getShort(o + 42).toInt() and 0xFFFF,
                    bb.getShort(o + 44).toInt() and 0xFFFF)
            }
            fun readGens(off: Int, len: Int) = (0 until len / 4).map { i ->
                val o = off + i * 4
                (bb.getShort(o).toInt() and 0xFFFF) to bb.getShort(o + 2).toInt()
            }
            fun readMods(off: Int, len: Int) = (0 until len / 10).map { i ->
                val o = off + i * 10
                Modulator(bb.getShort(o).toInt() and 0xFFFF, bb.getShort(o + 2).toInt() and 0xFFFF, bb.getShort(o + 4).toInt(),
                    bb.getShort(o + 6).toInt() and 0xFFFF, bb.getShort(o + 8).toInt() and 0xFFFF)
            }
            fun readBags(off: Int, len: Int) = (0 until len / 4).map { i -> (bb.getShort(off + i * 4).toInt() and 0xFFFF) to (bb.getShort(off + i * 4 + 2).toInt() and 0xFFFF) }

            fun zones(bags: List<Pair<Int, Int>>, gens: List<Pair<Int, Int>>, mods: List<Modulator>, from: Int, to: Int, terminal: Int): Pair<Zone?, List<Zone>> {
                var global: Zone? = null
                val list = ArrayList<Zone>()
                for (b in from until to) {
                    if (b + 1 >= bags.size) break
                    val (g0, m0) = bags[b]; val (g1, m1) = bags[b + 1]
                    val gv = IntArray(Gen.COUNT); val set = BooleanArray(Gen.COUNT)
                    for (gi in g0 until minOf(g1, gens.size)) {
                        val (op, amt) = gens[gi]
                        if (op < Gen.COUNT) {
                            gv[op] = if (op == Gen.KEY_RANGE || op == Gen.VEL_RANGE) amt and 0xFFFF else amt
                            set[op] = true
                        }
                    }
                    val zm = mods.subList(minOf(m0, mods.size), minOf(m1, mods.size)).toList()
                    val z = Zone(gv, set, zm)
                    if (!set[terminal]) { if (b == from) global = z } else list += z
                }
                return global to list
            }

            val igens = need("igen").let { readGens(it.first, it.second) }
            val imods = need("imod").let { readMods(it.first, it.second) }
            val ibags = need("ibag").let { readBags(it.first, it.second) }
            val (instOff, instLen) = need("inst")
            val nInst = instLen / 22
            val instruments = (0 until nInst - 1).map { i ->
                val o = instOff + i * 22
                val b0 = bb.getShort(o + 20).toInt() and 0xFFFF
                val b1 = bb.getShort(o + 22 + 20).toInt() and 0xFFFF
                val (g, z) = zones(ibags, igens, imods, b0, b1, Gen.SAMPLE_ID)
                Instrument(str(o), g, z)
            }
            val pgens = need("pgen").let { readGens(it.first, it.second) }
            val pmods = need("pmod").let { readMods(it.first, it.second) }
            val pbags = need("pbag").let { readBags(it.first, it.second) }
            val (phdrOff, phdrLen) = need("phdr")
            val nPre = phdrLen / 38
            val presets = (0 until nPre - 1).map { i ->
                val o = phdrOff + i * 38
                val b0 = bb.getShort(o + 24).toInt() and 0xFFFF
                val b1 = bb.getShort(o + 38 + 24).toInt() and 0xFFFF
                val (g, z) = zones(pbags, pgens, pmods, b0, b1, Gen.INSTRUMENT)
                Preset(str(o), bb.getShort(o + 20).toInt() and 0xFFFF, bb.getShort(o + 22).toInt() and 0xFFFF, g, z)
            }
            return SoundFont(name, samples, shdrs, instruments, presets)
        }
    }
}

/** SF2.01 default modulators (§8.4). */
object DefaultModulators {
    val list = listOf(
        Modulator(0x0502, Gen.INITIAL_ATTENUATION, 960, 0, 0),   // velocity -> attenuation (concave, negative)
        Modulator(0x0102, Gen.INITIAL_FILTER_FC, -2400, 0, 0),   // velocity -> filter cutoff
        Modulator(0x000D, Gen.VIB_LFO_TO_PITCH, 50, 0, 0),       // channel pressure -> vibrato
        Modulator(0x0081, Gen.VIB_LFO_TO_PITCH, 50, 0, 0),       // CC1 mod wheel -> vibrato
        Modulator(0x0587, Gen.INITIAL_ATTENUATION, 960, 0, 0),   // CC7 volume -> attenuation
        Modulator(0x028A, Gen.PAN, 1000, 0, 0),                  // CC10 pan -> pan
        Modulator(0x058B, Gen.INITIAL_ATTENUATION, 960, 0, 0),   // CC11 expression -> attenuation
        Modulator(0x00DB, Gen.REVERB_SEND, 200, 0, 0),           // CC91 -> reverb send
        Modulator(0x00DD, Gen.CHORUS_SEND, 200, 0, 0),           // CC93 -> chorus send
        Modulator(0x020E, 256 + 0, 12700, 0x0010, 0),            // pitch wheel * sensitivity -> pitch (virtual dest)
    )
    /** Virtual destination used for the pitch-wheel modulator (fine tune in cents). */
    const val DEST_PITCH = 256
}
