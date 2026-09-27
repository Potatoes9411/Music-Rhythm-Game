package com.rhythmphysics.core.synth

import com.rhythmphysics.core.midi.MidiFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * SoundFont 2 sample-playback synthesizer (pure Kotlin, used on Android and the JVM).
 * Implements the SF2.01 generator model with default + custom modulators, DAHDSR envelopes,
 * two LFOs, a resonant low-pass, exclusive classes, sustain pedal and a small stereo reverb.
 * Not thread-safe: the owner serializes calls (the app renders offline or from one audio thread).
 */
class Sf2Synth(val sf: SoundFont, val sampleRate: Int = 44100, val maxVoices: Int = 72) {

    inner class Channel(val index: Int) {
        val cc = IntArray(128)
        var program = 0
        var bankMsb = 0
        var pitchBend = 8192
        var pressure = 0
        var bendRangeSemis = 2
        var rpn = 0x3FFF
        var sustain = false
        fun reset() {
            cc.fill(0); cc[7] = 100; cc[10] = 64; cc[11] = 127; cc[91] = 40
            program = 0; bankMsb = 0; pitchBend = 8192; pressure = 0; bendRangeSemis = 2; rpn = 0x3FFF; sustain = false
        }
        init { reset() }
        val isDrum get() = index == 9
    }

    private inner class Voice {
        var active = false
        var channel = 0
        var key = 0
        var vel = 0
        var age = 0L
        var released = false
        var sustained = false
        var exclusiveClass = 0
        lateinit var layer: ResolvedLayer
        val g = IntArray(Gen.COUNT)          // modulated generator values
        val modOffsets = IntArray(Gen.COUNT + 1)
        var pitchModCents = 0.0
        // sample playback
        var pos = 0.0
        var start = 0; var end = 0; var loopStart = 0; var loopEnd = 0; var loopMode = 0
        var baseIncrement = 0.0
        // envelopes: stage 0 delay,1 attack,2 hold,3 decay,4 sustain,5 release,6 done
        var vStage = 0; var vTime = 0.0; var vLevelDb = -100.0; var vAttackAmp = 0.0
        var mStage = 0; var mTime = 0.0; var mLevel = 0.0
        var modLfoPhase = 0.0; var vibLfoPhase = 0.0; var lfoTime = 0.0
        // filter state
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0; var filterOn = false
        var lastFc = -1.0; var lastQ = -1.0
        var gainL = 0.0; var gainR = 0.0; var reverb = 0.0
        var releaseStartDb = 0.0
    }

    val channels = Array(16) { Channel(it) }
    private val voices = Array(maxVoices) { Voice() }
    private var clock = 0L
    private val reverb = Reverb(sampleRate)
    var masterGain = 1.0
    var reverbLevel = 0.22

    val activeVoiceCount: Int get() = voices.count { it.active }

    fun reset() {
        voices.forEach { it.active = false }
        channels.forEach { it.reset() }
        reverb.clear()
    }

    // ---- MIDI input ----------------------------------------------------------------------

    fun noteOn(ch: Int, key: Int, vel: Int) {
        if (vel <= 0) { noteOff(ch, key); return }
        val c = channels[ch and 15]
        val bank = if (c.isDrum) 128 else c.bankMsb
        val preset = sf.findPreset(bank, c.program) ?: return
        val layers = sf.resolve(preset, key, vel)
        for (layer in layers) {
            val excl = layer.gens[Gen.EXCLUSIVE_CLASS]
            if (excl != 0) for (v in voices) if (v.active && v.channel == c.index && v.exclusiveClass == excl && v.age != clock + 1) fastRelease(v)
        }
        clock++
        for (layer in layers) startVoice(c, key, vel, layer)
    }

    fun noteOff(ch: Int, key: Int) {
        val c = channels[ch and 15]
        for (v in voices) if (v.active && v.channel == c.index && v.key == key && !v.released) {
            if (c.sustain) v.sustained = true else release(v)
        }
    }

    fun controlChange(ch: Int, cc: Int, value: Int) {
        val c = channels[ch and 15]
        when (cc) {
            0 -> c.bankMsb = value
            64 -> { c.sustain = value >= 64; if (!c.sustain) for (v in voices) if (v.active && v.channel == c.index && v.sustained) { v.sustained = false; release(v) } }
            101 -> c.rpn = (c.rpn and 0x7F) or (value shl 7)
            100 -> c.rpn = (c.rpn and 0x3F80) or value
            6 -> if (c.rpn == 0) c.bendRangeSemis = value.coerceIn(0, 24)
            120, 123 -> { for (v in voices) if (v.active && v.channel == c.index) if (cc == 120) v.active = false else release(v) }
            121 -> { val p = c.program; val b = c.bankMsb; c.reset(); c.program = p; c.bankMsb = b }
        }
        c.cc[cc and 127] = value.coerceIn(0, 127)
        for (v in voices) if (v.active && v.channel == c.index) updateModulated(v)
    }

    fun programChange(ch: Int, program: Int) { channels[ch and 15].program = program and 127 }

    fun pitchBend(ch: Int, value14: Int) {
        val c = channels[ch and 15]
        c.pitchBend = value14.coerceIn(0, 16383)
        for (v in voices) if (v.active && v.channel == c.index) updateModulated(v)
    }

    fun channelPressure(ch: Int, value: Int) {
        val c = channels[ch and 15]
        c.pressure = value
        for (v in voices) if (v.active && v.channel == c.index) updateModulated(v)
    }

    fun allNotesOff() { for (v in voices) if (v.active) release(v) }

    // ---- voice management -------------------------------------------------------------------

    private fun allocVoice(): Voice {
        voices.firstOrNull { !it.active }?.let { return it }
        // steal: quietest released voice, else oldest
        return voices.filter { it.released }.minByOrNull { it.vLevelDb } ?: voices.minBy { it.age }
    }

    private fun startVoice(c: Channel, key: Int, vel: Int, layer: ResolvedLayer) {
        val v = allocVoice()
        v.active = true; v.channel = c.index; v.key = key; v.vel = vel; v.age = clock
        v.released = false; v.sustained = false; v.layer = layer
        v.exclusiveClass = layer.gens[Gen.EXCLUSIVE_CLASS]
        val s = layer.sample
        val gg = layer.gens
        v.start = (s.start + gg[Gen.START_ADDRS_OFFSET] + gg[Gen.START_ADDRS_COARSE] * 32768).coerceIn(0, sf.samples.size - 1)
        v.end = (s.end + gg[Gen.END_ADDRS_OFFSET] + gg[Gen.END_ADDRS_COARSE] * 32768).coerceIn(v.start + 1, sf.samples.size)
        v.loopStart = (s.loopStart + gg[Gen.STARTLOOP_ADDRS_OFFSET] + gg[Gen.STARTLOOP_ADDRS_COARSE] * 32768).coerceIn(v.start, v.end - 1)
        v.loopEnd = (s.loopEnd + gg[Gen.ENDLOOP_ADDRS_OFFSET] + gg[Gen.ENDLOOP_ADDRS_COARSE] * 32768).coerceIn(v.loopStart + 1, v.end)
        v.loopMode = gg[Gen.SAMPLE_MODES] and 3
        if (v.loopEnd - v.loopStart < 2) v.loopMode = 0
        v.pos = v.start.toDouble()
        v.vStage = 0; v.vTime = 0.0; v.vLevelDb = -100.0; v.vAttackAmp = 0.0
        v.mStage = 0; v.mTime = 0.0; v.mLevel = 0.0
        v.lfoTime = 0.0; v.modLfoPhase = 0.0; v.vibLfoPhase = 0.0
        v.x1 = 0.0; v.x2 = 0.0; v.y1 = 0.0; v.y2 = 0.0; v.lastFc = -1.0; v.lastQ = -1.0
        updateModulated(v)
    }

    private fun release(v: Voice) {
        if (v.released) return
        v.released = true
        v.releaseStartDb = currentDb(v)
        v.vStage = 5; v.vTime = 0.0
        v.mStage = 5; v.mTime = 0.0
        if (v.loopMode == 3) v.loopMode = 0
    }

    private fun fastRelease(v: Voice) {
        release(v)
        v.g[Gen.RELEASE_VOL_ENV] = -7200 // ~16 ms choke (hi-hat pedal etc.)
    }

    // ---- modulators -------------------------------------------------------------------------

    private fun concave(x: Double) = if (x <= 0.0) 0.0 else if (x >= 0.9999) 1.0 else (-(40.0 / 96.0) * log10(1 - x)).coerceIn(0.0, 1.0)
    private fun convex(x: Double) = if (x <= 0.0001) 0.0 else if (x >= 1.0) 1.0 else (1 + (40.0 / 96.0) * log10(x)).coerceIn(0.0, 1.0)

    private fun source(op: Int, v: Voice, c: Channel): Double {
        val index = op and 0x7F
        val isCc = op and 0x80 != 0
        var x: Double = if (isCc) c.cc[index] / 127.0 else when (index) {
            0 -> return 1.0
            2 -> v.vel / 127.0
            3 -> v.key / 127.0
            10 -> 0.0
            13 -> c.pressure / 127.0
            14 -> c.pitchBend / 16383.0
            16 -> c.bendRangeSemis / 127.0
            else -> 0.0
        }
        if ((op shr 8) and 1 == 1) x = 1 - x
        val bipolar = (op shr 9) and 1 == 1
        return when ((op shr 10) and 0x3F) {
            1 -> if (bipolar) (if (x > 0.5) concave(2 * x - 1) else -concave(1 - 2 * x)) else concave(x)
            2 -> if (bipolar) (if (x > 0.5) convex(2 * x - 1) else -convex(1 - 2 * x)) else convex(x)
            3 -> if (bipolar) (if (x >= 0.5) 1.0 else -1.0) else (if (x >= 0.5) 1.0 else 0.0)
            else -> if (bipolar) 2 * x - 1 else x
        }
    }

    private fun updateModulated(v: Voice) {
        val c = channels[v.channel]
        v.modOffsets.fill(0)
        for (m in v.layer.mods) {
            if (m.src == 0 && m.amtSrc == 0) continue
            var value = source(m.src, v, c) * source(m.amtSrc, v, c) * m.amount
            if (m.transform == 2) value = abs(value)
            val dest = if (m.dest == DefaultModulators.DEST_PITCH) Gen.COUNT else m.dest
            if (dest in 0..Gen.COUNT) v.modOffsets[dest] += value.toInt()
        }
        for (i in 0 until Gen.COUNT) v.g[i] = v.layer.gens[i] + v.modOffsets[i]
        v.pitchModCents = v.modOffsets[Gen.COUNT].toDouble()
        val s = v.layer.sample
        val keyUsed = if (v.layer.gens[Gen.KEYNUM] >= 0) v.layer.gens[Gen.KEYNUM] else v.key
        val root = if (v.layer.gens[Gen.OVERRIDING_ROOT_KEY] >= 0) v.layer.gens[Gen.OVERRIDING_ROOT_KEY] else s.originalPitch
        val cents = (keyUsed - root) * v.g[Gen.SCALE_TUNING] + v.g[Gen.COARSE_TUNE] * 100.0 + v.g[Gen.FINE_TUNE] +
            s.pitchCorrection + v.pitchModCents
        v.baseIncrement = 2.0.pow(cents / 1200.0) * s.sampleRate / sampleRate
        // Pan & gain.
        val pan = v.g[Gen.PAN].coerceIn(-500, 500) / 1000.0 + 0.5
        v.gainL = cos(pan * PI / 2); v.gainR = sin(pan * PI / 2)
        v.reverb = v.g[Gen.REVERB_SEND].coerceIn(0, 1000) / 1000.0
    }

    private fun tcToSec(tc: Int) = 2.0.pow(tc.coerceIn(-12000, 8000) / 1200.0)

    private fun currentDb(v: Voice): Double = when (v.vStage) {
        1 -> if (v.vAttackAmp <= 0.0) -100.0 else 20 * log10(v.vAttackAmp)
        else -> v.vLevelDb
    }

    // ---- rendering --------------------------------------------------------------------------

    private val block = 32
    private val tmp = DoubleArray(block)
    private val revIn = FloatArray(block)
    private val revL = FloatArray(block)
    private val revR = FloatArray(block)

    /** Renders [frames] stereo frames, *adding* nothing: output arrays are overwritten. */
    fun render(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            for (i in 0 until n) { left[offset + done + i] = 0f; right[offset + done + i] = 0f; revIn[i] = 0f }
            for (v in voices) if (v.active) renderVoice(v, left, right, offset + done, n)
            reverb.process(revIn, revL, revR, n)
            for (i in 0 until n) {
                val l = (left[offset + done + i] + revL[i] * reverbLevel) * masterGain
                val r = (right[offset + done + i] + revR[i] * reverbLevel) * masterGain
                left[offset + done + i] = softClip(l.toFloat())
                right[offset + done + i] = softClip(r.toFloat())
            }
            done += n
        }
    }

    private fun softClip(x: Float): Float = when {
        x > 0.95f -> (0.95f + 0.05f * kotlin.math.tanh((x - 0.95f) / 0.05f))
        x < -0.95f -> (-0.95f - 0.05f * kotlin.math.tanh((-x - 0.95f) / 0.05f))
        else -> x
    }

    private fun renderVoice(v: Voice, left: FloatArray, right: FloatArray, off: Int, n: Int) {
        val dt = n.toDouble() / sampleRate
        val g = v.g
        // Envelopes evaluated once per block (block = 0.7 ms at 44.1 kHz), gain ramped linearly.
        val startAmp = envAmp(v)
        advanceVolEnv(v, dt)
        advanceModEnv(v, dt)
        if (v.vStage == 6) { v.active = false; return }
        val endAmp = envAmp(v)
        // LFOs
        v.lfoTime += dt
        val modLfo = lfoValue(v.lfoTime, g[Gen.DELAY_MOD_LFO], g[Gen.FREQ_MOD_LFO], v, true)
        val vibLfo = lfoValue(v.lfoTime, g[Gen.DELAY_VIB_LFO], g[Gen.FREQ_VIB_LFO], v, false)
        val pitchCents = v.mLevel * g[Gen.MOD_ENV_TO_PITCH] + modLfo * g[Gen.MOD_LFO_TO_PITCH] + vibLfo * g[Gen.VIB_LFO_TO_PITCH]
        val inc = v.baseIncrement * (if (pitchCents != 0.0) 2.0.pow(pitchCents / 1200.0) else 1.0)
        // Filter
        val fcCents = g[Gen.INITIAL_FILTER_FC] + v.mLevel * g[Gen.MOD_ENV_TO_FILTER_FC] + modLfo * g[Gen.MOD_LFO_TO_FILTER_FC]
        updateFilter(v, fcCents, g[Gen.INITIAL_FILTER_Q].toDouble())
        // Attenuation (cB) incl. mod LFO tremolo.
        val attenCb = g[Gen.INITIAL_ATTENUATION].coerceIn(0, 1440) + modLfo * g[Gen.MOD_LFO_TO_VOLUME]
        val atten = 10.0.pow(-attenCb / 200.0)
        val samples = sf.samples
        var pos = v.pos
        for (i in 0 until n) {
            val ip = pos.toInt()
            val frac = pos - ip
            val s0 = sampleAt(samples, v, ip - 1); val s1 = sampleAt(samples, v, ip)
            val s2 = sampleAt(samples, v, ip + 1); val s3 = sampleAt(samples, v, ip + 2)
            // 4-point cubic Hermite
            val c1 = 0.5 * (s2 - s0)
            val c2 = s0 - 2.5 * s1 + 2 * s2 - 0.5 * s3
            val c3 = 0.5 * (s3 - s0) + 1.5 * (s1 - s2)
            tmp[i] = ((c3 * frac + c2) * frac + c1) * frac + s1
            pos += inc
            if (v.loopMode == 1 || v.loopMode == 3) {
                if (pos >= v.loopEnd) pos -= (v.loopEnd - v.loopStart)
            } else if (pos >= v.end - 1) {
                // one-shot finished
                for (k in i + 1 until n) tmp[k] = 0.0
                v.vStage = 6
                break
            }
        }
        v.pos = pos
        if (v.filterOn) {
            var x1 = v.x1; var x2 = v.x2; var y1 = v.y1; var y2 = v.y2
            for (i in 0 until n) {
                val x = tmp[i]
                val y = v.b0 * x + v.b1 * x1 + v.b2 * x2 - v.a1 * y1 - v.a2 * y2
                x2 = x1; x1 = x; y2 = y1; y1 = y
                tmp[i] = y
            }
            if (abs(y1) < 1e-12) y1 = 0.0
            if (abs(y2) < 1e-12) y2 = 0.0
            v.x1 = x1; v.x2 = x2; v.y1 = y1; v.y2 = y2
        }
        val scale = atten / 32768.0
        for (i in 0 until n) {
            val amp = (startAmp + (endAmp - startAmp) * (i + 1) / n) * scale
            val s = tmp[i] * amp
            left[off + i] += (s * v.gainL).toFloat()
            right[off + i] += (s * v.gainR).toFloat()
            revIn[i] += (s * v.reverb).toFloat()
        }
        if (v.vStage == 6) v.active = false
    }

    private fun sampleAt(samples: ShortArray, v: Voice, idx: Int): Double {
        var i = idx
        if (v.loopMode == 1 || v.loopMode == 3) {
            if (i >= v.loopEnd) i -= (v.loopEnd - v.loopStart)
        }
        if (i < v.start) i = v.start
        if (i >= v.end) return 0.0
        return samples[i].toDouble()
    }

    private fun lfoValue(time: Double, delayTc: Int, freqCents: Int, v: Voice, isMod: Boolean): Double {
        val delay = tcToSec(delayTc)
        if (time < delay) return 0.0
        val f = 8.176 * 2.0.pow(freqCents.coerceIn(-16000, 4500) / 1200.0)
        val phase = (time - delay) * f
        // triangle wave -1..1
        val p = phase - kotlin.math.floor(phase)
        return if (p < 0.25) p * 4 else if (p < 0.75) 2 - p * 4 else p * 4 - 4
    }

    private fun updateFilter(v: Voice, fcCents: Double, qCb: Double) {
        if (fcCents >= 13500 && qCb <= 0) { v.filterOn = false; return }
        if (abs(fcCents - v.lastFc) < 1.0 && abs(qCb - v.lastQ) < 0.5 && v.filterOn) return
        v.lastFc = fcCents; v.lastQ = qCb
        val fc = (8.176 * 2.0.pow(fcCents.coerceIn(1500.0, 13500.0) / 1200.0)).coerceAtMost(sampleRate * 0.45)
        val qDb = (qCb / 10.0).coerceIn(0.0, 96.0)
        val q = maxOf(0.7071, 10.0.pow(qDb / 20.0))
        val w0 = 2 * PI * fc / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cw = cos(w0)
        val a0 = 1 + alpha
        // Keep unity DC gain; resonance adds a peak (approximate SF2 behavior without extra gain loss).
        v.b0 = (1 - cw) / 2 / a0; v.b1 = (1 - cw) / a0; v.b2 = (1 - cw) / 2 / a0
        v.a1 = -2 * cw / a0; v.a2 = (1 - alpha) / a0
        v.filterOn = true
    }

    private fun envAmp(v: Voice): Double = when (v.vStage) {
        0 -> 0.0
        1 -> v.vAttackAmp
        6 -> 0.0
        else -> if (v.vLevelDb <= -100) 0.0 else 10.0.pow(v.vLevelDb / 20.0)
    }

    private fun advanceVolEnv(v: Voice, dt: Double) {
        val g = v.g
        v.vTime += dt
        val keyOffset = 60 - v.key
        when (v.vStage) {
            0 -> { if (v.vTime >= tcToSec(g[Gen.DELAY_VOL_ENV])) { v.vStage = 1; v.vTime = 0.0 } }
            1 -> {
                val a = tcToSec(g[Gen.ATTACK_VOL_ENV])
                v.vAttackAmp = if (a <= 0.0) 1.0 else (v.vTime / a).coerceAtMost(1.0)
                if (v.vTime >= a) { v.vStage = 2; v.vTime = 0.0; v.vLevelDb = 0.0 }
            }
            2 -> {
                v.vLevelDb = 0.0
                if (v.vTime >= tcToSec(g[Gen.HOLD_VOL_ENV] + keyOffset * g[Gen.KEY_TO_VOL_ENV_HOLD])) { v.vStage = 3; v.vTime = 0.0 }
            }
            3 -> {
                val sustainDb = -g[Gen.SUSTAIN_VOL_ENV].coerceIn(0, 1440) / 10.0
                val decay = tcToSec(g[Gen.DECAY_VOL_ENV] + keyOffset * g[Gen.KEY_TO_VOL_ENV_DECAY])
                v.vLevelDb = maxOf(sustainDb, -100.0 * (v.vTime / decay))
                if (v.vLevelDb <= sustainDb) { v.vStage = 4; v.vLevelDb = sustainDb }
                if (v.vLevelDb <= -99.0) v.vStage = 6
            }
            4 -> { if (v.vLevelDb <= -99.0) v.vStage = 6 }
            5 -> {
                val rel = tcToSec(g[Gen.RELEASE_VOL_ENV])
                v.vLevelDb = v.releaseStartDb - 100.0 * (v.vTime / rel)
                if (v.vLevelDb <= -90.0) v.vStage = 6
            }
        }
    }

    private fun advanceModEnv(v: Voice, dt: Double) {
        val g = v.g
        v.mTime += dt
        val keyOffset = 60 - v.key
        when (v.mStage) {
            0 -> if (v.mTime >= tcToSec(g[Gen.DELAY_MOD_ENV])) { v.mStage = 1; v.mTime = 0.0 }
            1 -> { val a = tcToSec(g[Gen.ATTACK_MOD_ENV]); v.mLevel = (v.mTime / a).coerceAtMost(1.0); if (v.mTime >= a) { v.mStage = 2; v.mTime = 0.0 } }
            2 -> { v.mLevel = 1.0; if (v.mTime >= tcToSec(g[Gen.HOLD_MOD_ENV] + keyOffset * g[Gen.KEY_TO_MOD_ENV_HOLD])) { v.mStage = 3; v.mTime = 0.0 } }
            3 -> {
                val sus = 1.0 - g[Gen.SUSTAIN_MOD_ENV].coerceIn(0, 1000) / 1000.0
                val d = tcToSec(g[Gen.DECAY_MOD_ENV] + keyOffset * g[Gen.KEY_TO_MOD_ENV_DECAY])
                v.mLevel = maxOf(sus, 1.0 - v.mTime / d)
                if (v.mLevel <= sus) v.mStage = 4
            }
            5 -> { val r = tcToSec(g[Gen.RELEASE_MOD_ENV]); v.mLevel = maxOf(0.0, v.mLevel - dt / r) }
        }
    }

    /** Small Schroeder/Freeverb-style stereo reverb. */
    private class Reverb(sr: Int) {
        private val combL = intArrayOf(1116, 1188, 1277, 1356).map { Comb((it * sr / 44100.0).toInt()) }
        private val combR = intArrayOf(1139, 1211, 1300, 1379).map { Comb((it * sr / 44100.0).toInt()) }
        private val apL = intArrayOf(556, 441).map { AllPass((it * sr / 44100.0).toInt()) }
        private val apR = intArrayOf(579, 464).map { AllPass((it * sr / 44100.0).toInt()) }
        fun clear() { (combL + combR).forEach { it.buf.fill(0f) }; (apL + apR).forEach { it.buf.fill(0f) } }
        fun process(input: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
            for (i in 0 until n) {
                val x = input[i] * 0.2f
                var l = 0f; var r = 0f
                for (c in combL) l += c.process(x)
                for (c in combR) r += c.process(x)
                for (a in apL) l = a.process(l)
                for (a in apR) r = a.process(r)
                outL[i] = l; outR[i] = r
            }
        }
        class Comb(size: Int) {
            val buf = FloatArray(size); var idx = 0; var store = 0f
            fun process(x: Float): Float {
                val out = buf[idx]
                store = out * 0.8f + store * 0.2f
                buf[idx] = x + store * 0.84f
                idx = (idx + 1) % buf.size
                return out
            }
        }
        class AllPass(size: Int) {
            val buf = FloatArray(size); var idx = 0
            fun process(x: Float): Float {
                val b = buf[idx]
                val out = -x + b
                buf[idx] = x + b * 0.5f
                idx = (idx + 1) % buf.size
                return out
            }
        }
    }
}

/** Offline MIDI -> PCM renderer. */
object MidiRenderer {
    /**
     * Renders [midi] through [synth]; [sink] receives interleaving-free stereo blocks.
     * Rendering is deterministic (no threads, no clocks).
     */
    fun render(
        midi: MidiFile, synth: Sf2Synth, tailSec: Double = 2.5,
        progress: ((Double) -> Unit)? = null,
        sink: (left: FloatArray, right: FloatArray, frames: Int) -> Unit,
    ) {
        synth.reset()
        val sr = synth.sampleRate
        val events = midi.channelEvents
        val totalFrames = ((midi.durationSec + tailSec) * sr).toLong()
        val bufL = FloatArray(1024); val bufR = FloatArray(1024)
        var frame = 0L
        var ei = 0
        var lastReport = 0L
        while (frame < totalFrames) {
            // Apply every event due at or before this frame.
            while (ei < events.size && (events[ei].timeSec * sr).toLong() <= frame) {
                val e = events[ei++]
                when (e.status) {
                    0x90 -> synth.noteOn(e.channel, e.data1, e.data2)
                    0x80 -> synth.noteOff(e.channel, e.data1)
                    0xB0 -> synth.controlChange(e.channel, e.data1, e.data2)
                    0xC0 -> synth.programChange(e.channel, e.data1)
                    0xE0 -> synth.pitchBend(e.channel, e.data1 or (e.data2 shl 7))
                    0xD0 -> synth.channelPressure(e.channel, e.data1)
                }
            }
            val nextEventFrame = if (ei < events.size) (events[ei].timeSec * sr).toLong() else totalFrames
            val n = minOf(1024L, maxOf(1L, nextEventFrame - frame), totalFrames - frame).toInt()
            synth.render(bufL, bufR, 0, n)
            sink(bufL, bufR, n)
            frame += n
            if (progress != null && frame - lastReport > sr) { lastReport = frame; progress(frame.toDouble() / totalFrames) }
        }
        progress?.invoke(1.0)
    }
}
