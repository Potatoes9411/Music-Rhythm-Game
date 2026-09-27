package com.rhythmphysics.core.audio

import com.rhythmphysics.core.music.MusicEvent
import com.rhythmphysics.core.music.SourceType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 complex FFT. */
class FFT(val n: Int) {
    private val levels = 31 - Integer.numberOfLeadingZeros(n)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init { require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" } }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var size = 2
        while (size <= n) {
            val half = size / 2; val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tre = re[l] * cosT[k] + im[l] * sinT[k]
                    val tim = -re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tre; im[l] = im[j] - tim
                    re[j] += tre; im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/**
 * Offline analysis of real audio (streamed, bounded memory):
 *   mono PCM -> resample to 22.05 kHz -> STFT (1024/256, Hann) -> spectral flux, RMS, bands,
 *   centroid, chroma -> onsets (refined on a 2.9 ms energy envelope) -> tempo (autocorrelation
 *   with a log-tempo prior) -> beats (dynamic programming) -> downbeats (accent phase) ->
 *   sections (bar-level novelty) -> normalized MusicEvents.
 */
class AudioAnalyzer(private val sourceRate: Int, private val settings: AnalysisSettings = AnalysisSettings()) {
    companion object {
        const val VERSION = 1
        private const val FINE_HOP = 64 // 2.9 ms at 22.05 kHz
    }

    private val sr = settings.analysisSampleRate
    private val nfft = settings.fftSize
    private val hop = settings.hop
    private val fft = FFT(nfft)
    private val window = DoubleArray(nfft) { 0.5 - 0.5 * cos(2 * PI * it / nfft) }
    private val bins = nfft / 2 + 1

    // ---- resampler (windowed-sinc low-pass + linear interpolation) ----
    private val ratio = sourceRate.toDouble() / sr
    private val lpTaps: DoubleArray = run {
        val cutoff = min(1.0, 1.0 / ratio) * 0.45
        val m = 32
        DoubleArray(2 * m + 1) { i ->
            val x = i - m
            val sinc = if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            sinc * (0.54 - 0.46 * cos(2 * PI * i / (2 * m)))
        }.let { t -> val s = t.sum(); DoubleArray(t.size) { t[it] / s } }
    }
    private val history = DoubleArray(lpTaps.size)
    private var histPos = 0
    private var filteredPrev = 0.0
    private var filteredCur = 0.0
    private var srcIndex = 0L
    /** Starts past the FIR group delay (half the taps) so analysis time 0 == source time 0. */
    private var nextOut = ((lpTaps.size - 1) / 2).toDouble()

    // ---- analysis-rate buffers ----
    private val frameBuf = DoubleArray(nfft)
    private var frameFill = 0
    private var samplesIn = 0L
    private val re = DoubleArray(nfft)
    private val im = DoubleArray(nfft)
    private var prevLogMag = DoubleArray(bins)
    private val binHz = DoubleArray(bins) { it.toDouble() * sr / nfft }
    private val binPitchClass = IntArray(bins) { b ->
        val f = b.toDouble() * sr / nfft
        if (f < 55 || f > 2100) -1 else (((12 * log2(f / 440.0) + 69).roundToInt() % 12) + 12) % 12
    }

    // Per-frame features (growable)
    private var flux = FloatArray(4096); private var rms = FloatArray(4096)
    private var bass = FloatArray(4096); private var mid = FloatArray(4096); private var high = FloatArray(4096)
    private var centroid = FloatArray(4096); private var chroma = FloatArray(4096 * 12)
    private var peakBin = IntArray(4096); private var peakConf = FloatArray(4096)
    private var frames = 0

    // Fine energy envelope + waveform peaks
    private var fine = FloatArray(16384); private var fineCount = 0; private var fineAcc = 0.0; private var fineN = 0
    private var wavePeak = 0f; private var waveN = 0
    private val waveHop = sr / 50
    private var waveform = FloatArray(4096); private var waveCount = 0
    private var sumSq = 0.0

    fun feed(block: FloatArray, n: Int) {
        for (i in 0 until n) {
            history[histPos] = block[i].toDouble()
            histPos = (histPos + 1) % history.size
            // FIR low-pass at the source rate
            var acc = 0.0
            var h = histPos
            for (t in lpTaps.indices) { acc += lpTaps[t] * history[h]; h++; if (h == history.size) h = 0 }
            filteredPrev = filteredCur; filteredCur = acc
            // emit output samples whose position falls between srcIndex-1 and srcIndex
            while (nextOut <= srcIndex) {
                val frac = nextOut - (srcIndex - 1)
                val y = filteredPrev + (filteredCur - filteredPrev) * frac.coerceIn(0.0, 1.0)
                pushAnalysisSample(y)
                nextOut += ratio
            }
            srcIndex++
        }
    }

    private fun pushAnalysisSample(x: Double) {
        samplesIn++
        sumSq += x * x
        // fine envelope
        fineAcc += x * x; fineN++
        if (fineN == FINE_HOP) {
            if (fineCount == fine.size) fine = fine.copyOf(fine.size * 2)
            fine[fineCount++] = (fineAcc / FINE_HOP).toFloat(); fineAcc = 0.0; fineN = 0
        }
        // waveform peaks for the timeline UI
        wavePeak = max(wavePeak, abs(x).toFloat()); waveN++
        if (waveN == waveHop) { if (waveCount == waveform.size) waveform = waveform.copyOf(waveform.size * 2); waveform[waveCount++] = wavePeak; wavePeak = 0f; waveN = 0 }
        // STFT framing
        frameBuf[frameFill++] = x
        if (frameFill == nfft) {
            analyzeFrame()
            System.arraycopy(frameBuf, hop, frameBuf, 0, nfft - hop)
            frameFill = nfft - hop
        }
    }

    private fun grow() {
        val n = flux.size * 2
        flux = flux.copyOf(n); rms = rms.copyOf(n); bass = bass.copyOf(n); mid = mid.copyOf(n); high = high.copyOf(n)
        centroid = centroid.copyOf(n); chroma = chroma.copyOf(n * 12); peakBin = peakBin.copyOf(n); peakConf = peakConf.copyOf(n)
    }

    private fun analyzeFrame() {
        if (frames == flux.size) grow()
        var e = 0.0
        for (i in 0 until nfft) { val v = frameBuf[i]; e += v * v; re[i] = v * window[i]; im[i] = 0.0 }
        fft.transform(re, im)
        var fl = 0.0; var b = 0.0; var m = 0.0; var h = 0.0; var cNum = 0.0; var cDen = 0.0
        val chromaBase = frames * 12
        var bestMag = 0.0; var best = 0; var magSum = 0.0
        val logMag = DoubleArray(bins)
        for (k in 1 until bins) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            val lm = ln(1 + 100 * mag)
            logMag[k] = lm
            val d = lm - prevLogMag[k]
            if (d > 0) fl += d
            val p = mag * mag
            val f = binHz[k]
            when { f < 150 -> b += p; f < 2000 -> m += p; else -> h += p }
            cNum += f * mag; cDen += mag
            val pc = binPitchClass[k]
            if (pc >= 0) chroma[chromaBase + pc] += p.toFloat()
            if (f in 80.0..1000.0) { magSum += mag; if (mag > bestMag) { bestMag = mag; best = k } }
        }
        prevLogMag = logMag
        flux[frames] = fl.toFloat()
        rms[frames] = sqrt(e / nfft).toFloat()
        bass[frames] = b.toFloat(); mid[frames] = m.toFloat(); high[frames] = h.toFloat()
        centroid[frames] = if (cDen > 0) (cNum / cDen).toFloat() else 0f
        peakBin[frames] = best
        peakConf[frames] = if (magSum > 0) (bestMag / magSum * 10).toFloat().coerceAtMost(1f) else 0f
        frames++
    }

    /** Center time of STFT frame [i]. */
    private fun frameTime(i: Int) = (i * hop + nfft / 2.0) / sr

    fun finish(): AudioAnalysis {
        val duration = samplesIn.toDouble() / sr
        val n = frames
        if (n < 8) return empty(duration)
        // ---- onset detection function ----
        val odf = DoubleArray(n)
        val w = (0.35 * sr / hop).toInt()
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + flux[i]
        for (i in 0 until n) {
            val a = max(0, i - w); val bnd = min(n, i + w + 1)
            val localMean = (prefix[bnd] - prefix[a]) / (bnd - a)
            odf[i] = max(0.0, flux[i] - localMean)
        }
        val sorted = odf.sorted()
        val norm = max(1e-9, sorted[(n * 0.995).toInt().coerceAtMost(n - 1)])
        for (i in 0 until n) odf[i] = min(1.5, odf[i] / norm)

        // ---- onsets ----
        val onsets = ArrayList<Onset>()
        val minGapFrames = (settings.minOnsetGapSec * sr / hop).roundToInt().coerceAtLeast(1)
        val maskFrames = (0.09 * sr / hop).roundToInt()
        var last = -minGapFrames
        var lastStrength = 0.0
        for (i in 2 until n - 2) {
            val v = odf[i]
            if (v < settings.onsetThreshold) continue
            if (v < odf[i - 1] || v < odf[i + 1] || v < odf[i - 2] || v < odf[i + 2]) continue
            if (i - last < minGapFrames) continue
            // Post-onset masking: a much weaker peak right after a strong onset is its tail.
            if (i - last < maskFrames && v < 0.5 * lastStrength) continue
            onsets += Onset(refineOnset(frameTime(i)), v.toFloat().coerceAtMost(1f))
            last = i; lastStrength = v
        }

        // ---- tempo ----
        val fps = sr.toDouble() / hop
        val minLag = (fps * 60 / settings.maxBpm).toInt(); val maxLag = (fps * 60 / settings.minBpm).toInt().coerceAtMost(n / 2)
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) { var s = 0.0; for (i in 0 until n - lag) s += odf[i] * odf[i + lag]; ac[lag] = s / (n - lag) }
        var bestLag = minLag; var bestScore = -1.0
        for (lag in minLag..maxLag) {
            val bpm = 60 * fps / lag
            val prior = exp(-0.5 * (log2(bpm / 120.0) / 0.9).let { it * it })
            // reward harmonic support at 2x lag
            val support = if (2 * lag <= maxLag + 1) 0.5 * ac[2 * lag] else 0.0
            val sc = (ac[lag] + support) * prior
            if (sc > bestScore) { bestScore = sc; bestLag = lag }
        }
        val d = if (bestLag in (minLag + 1)..maxLag) (ac[bestLag - 1] - ac[bestLag + 1]) / (2 * (ac[bestLag - 1] - 2 * ac[bestLag] + ac[bestLag + 1])) else 0.0
        val period = bestLag + (if (d.isFinite()) d.coerceIn(-0.5, 0.5) else 0.0)
        val bpm = 60 * fps / period
        val acMean = (minLag..maxLag).map { ac[it] }.average()
        val bpmConfidence = ((ac[bestLag] - acMean) / max(1e-9, ac[bestLag])).toFloat().coerceIn(0f, 1f)

        // ---- beats (dynamic programming, Ellis 2007) ----
        val score = DoubleArray(n); val back = IntArray(n) { -1 }
        val tight = 100.0
        for (i in 0 until n) {
            var bestPrev = -1; var bestVal = 0.0
            val lo = max(0, i - (2 * period).toInt()); val hi = i - (period / 2).toInt()
            for (j in lo..hi) {
                if (j < 0) continue
                val r = ln((i - j) / period)
                val v = score[j] - tight * r * r
                if (bestPrev < 0 || v > bestVal) { bestVal = v; bestPrev = j }
            }
            score[i] = odf[i] + (if (bestPrev >= 0) max(0.0, bestVal) else 0.0)
            back[i] = if (bestPrev >= 0 && bestVal > 0) bestPrev else -1
        }
        // start from the best score in the last period
        var endIdx = n - 1; var endBest = -1.0
        for (i in max(0, n - period.toInt() - 1) until n) if (score[i] > endBest) { endBest = score[i]; endIdx = i }
        val beatFrames = ArrayList<Int>()
        var cur = endIdx
        while (cur >= 0) { beatFrames += cur; cur = back[cur] }
        beatFrames.reverse()
        // Extend the grid to the edges where the DP had no support (quiet intros/outros).
        val beatTimes = ArrayList<Double>()
        for (f in beatFrames) beatTimes += frameTime(f)
        if (beatTimes.isNotEmpty()) {
            val pSec = period / fps
            var t = beatTimes.first() - pSec
            while (t > 0.05) { beatTimes.add(0, t); t -= pSec }
            t = beatTimes.last() + pSec
            while (t < duration - 0.05) { beatTimes += t; t += pSec }
        }
        // Snap beats to nearby precise onsets.
        val refinedBeats = beatTimes.map { bt ->
            val o = nearestOnset(onsets, bt)
            if (o != null && abs(o.timeSec - bt) < 0.04) o.timeSec else bt
        }

        // ---- downbeats ----
        val bpb = settings.beatsPerBar.coerceIn(2, 12)
        val phaseScore = DoubleArray(bpb)
        refinedBeats.forEachIndexed { i, bt ->
            val f = frameIndex(bt)
            phaseScore[i % bpb] += sqrt(bass[f].toDouble()) + 0.5 * odf[f] * sqrt(rms[f].toDouble() + 1e-9)
        }
        val order = phaseScore.indices.sortedByDescending { phaseScore[it] }
        val phase = order[0]
        val dbConf = if (phaseScore[order[0]] > 0) ((phaseScore[order[0]] - phaseScore[order[1]]) / phaseScore[order[0]]).toFloat() else 0f
        val downbeats = refinedBeats.filterIndexed { i, _ -> i % bpb == phase }

        // ---- sections (bar-level novelty) ----
        val sections = sections(downbeats, duration)

        // ---- key ----
        val keyChroma = DoubleArray(12)
        for (f in 0 until n) {
            var mx = 0f
            for (p in 0 until 12) mx = max(mx, chroma[f * 12 + p])
            if (mx > 0f) for (p in 0 until 12) keyChroma[p] += (chroma[f * 12 + p] / mx).toDouble()
        }
        val key = estimateKey(keyChroma)

        // ---- events ----
        val events = buildEvents(refinedBeats, downbeats.toHashSet(), onsets, odf)

        // ---- waveform summary (capped) ----
        val cap = 4000
        val wf = if (waveCount <= cap) waveform.copyOf(waveCount).toList() else {
            val k = (waveCount + cap - 1) / cap
            (0 until (waveCount + k - 1) / k).map { i -> var mx = 0f; for (j in i * k until min(waveCount, (i + 1) * k)) mx = max(mx, waveform[j]); mx }
        }
        val waveRate = if (waveCount <= cap) 50f else (wf.size / duration).toFloat()
        val loud = if (samplesIn > 0) (10 * log10(sumSq / samplesIn + 1e-12)).toFloat() else -120f
        return AudioAnalysis(
            VERSION, settings, duration, sourceRate, bpm, bpmConfidence, refinedBeats, downbeats, dbConf,
            onsets, events, wf, waveRate, sections, key, loud,
        )
    }

    private fun empty(duration: Double) = AudioAnalysis(VERSION, settings, duration, sourceRate, 120.0, 0f, emptyList(), emptyList(), 0f,
        emptyList(), emptyList(), emptyList(), 50f, emptyList(), null, -120f)

    private fun frameIndex(t: Double) = ((t * sr - nfft / 2.0) / hop).roundToInt().coerceIn(0, frames - 1)

    /** Moves an onset estimate to the steepest rise of the 2.9 ms energy envelope nearby. */
    private fun refineOnset(t: Double): Double {
        val center = (t * sr / FINE_HOP).roundToInt()
        val lo = max(1, center - (0.035 * sr / FINE_HOP).toInt())
        val hi = min(fineCount - 1, center + (0.030 * sr / FINE_HOP).toInt())
        // Only rises that reach within 12 dB of the local peak count (ignores noise/reverb flicker).
        var maxDb = -200.0
        for (i in lo..min(fineCount - 1, hi + (0.02 * sr / FINE_HOP).toInt())) maxDb = max(maxDb, 10 * log10(fine[i] + 1e-10))
        var best = -1; var bestRise = 0.0
        for (i in lo..hi) {
            val prev = 10 * log10(fine[i - 1] + 1e-10)
            val curv = 10 * log10(fine[i] + 1e-10)
            if (curv < maxDb - 12) continue
            val rise = curv - prev
            if (rise > bestRise) { bestRise = rise; best = i }
        }
        return if (best < 0 || bestRise < 1.5) t else (best * FINE_HOP).toDouble() / sr
    }

    private fun nearestOnset(onsets: List<Onset>, t: Double): Onset? {
        var lo = 0; var hi = onsets.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (onsets[m].timeSec < t) lo = m + 1 else hi = m }
        val a = onsets.getOrNull(lo); val b = onsets.getOrNull(lo - 1)
        return listOfNotNull(a, b).minByOrNull { abs(it.timeSec - t) }
    }

    private fun sections(downbeats: List<Double>, duration: Double): List<Double> {
        if (downbeats.size < 12) return emptyList()
        // Bar feature: chroma (normalized) + band energies (log, standardized).
        val bars = downbeats.size - 1
        val feats = Array(bars) { DoubleArray(15) }
        for (b in 0 until bars) {
            val f0 = frameIndex(downbeats[b]); val f1 = max(f0 + 1, frameIndex(downbeats[b + 1]))
            for (f in f0 until f1) {
                for (p in 0 until 12) feats[b][p] += chroma[f * 12 + p].toDouble()
                feats[b][12] += ln(1 + bass[f].toDouble()); feats[b][13] += ln(1 + mid[f].toDouble()); feats[b][14] += ln(1 + high[f].toDouble())
            }
            val cn = sqrt((0 until 12).sumOf { feats[b][it] * feats[b][it] }) + 1e-9
            for (p in 0 until 12) feats[b][p] /= cn
            for (k in 12..14) feats[b][k] /= (f1 - f0)
        }
        for (k in 12..14) {
            val mean = feats.map { it[k] }.average(); val sd = sqrt(feats.map { (it[k] - mean) * (it[k] - mean) }.average()) + 1e-9
            for (b in 0 until bars) feats[b][k] = (feats[b][k] - mean) / sd * 0.35
        }
        fun dist(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += (a[i] - b[i]) * (a[i] - b[i]); return sqrt(s) }
        val k = 4
        val novelty = DoubleArray(bars)
        for (b in k until bars - k + 1) {
            var cross = 0.0; var within = 0.0
            for (i in b - k until b) for (j in b until b + k) cross += dist(feats[i], feats[j])
            for (i in b - k until b) for (j in b - k until b) within += dist(feats[i], feats[j]) / 2
            for (i in b until b + k) for (j in b until b + k) within += dist(feats[i], feats[j]) / 2
            novelty[b] = cross / (k * k) - within / (k * k)
        }
        val mean = novelty.average(); val sd = sqrt(novelty.map { (it - mean) * (it - mean) }.average()) + 1e-9
        val out = ArrayList<Double>()
        var lastB = -100
        val candidates = (k until bars - k + 1).filter { b -> novelty[b] > mean + 0.5 * sd && novelty[b] >= novelty[b - 1] && novelty[b] >= novelty[min(bars - 1, b + 1)] }
            .sortedByDescending { novelty[it] }
        val chosen = ArrayList<Int>()
        for (b in candidates) if (chosen.all { abs(it - b) >= 4 }) chosen += b
        for (b in chosen.sorted()) { if (b - lastB >= 4) { out += downbeats[b]; lastB = b } }
        return out.filter { it > 4.0 && it < duration - 4.0 }
    }

    private fun estimateKey(ch: DoubleArray): Int? {
        if (ch.sum() <= 0) return null
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        var best = 0; var bestV = -1e9
        for (tonic in 0 until 12) for (profile in listOf(major, minor)) {
            var v = 0.0
            for (p in 0 until 12) v += ch[(p + tonic) % 12] * profile[p]
            if (v > bestV) { bestV = v; best = tonic }
        }
        return best
    }

    private fun buildEvents(beats: List<Double>, downSet: Set<Double>, onsets: List<Onset>, odf: DoubleArray): List<MusicEvent> {
        val rmsMax = (0 until frames).maxOf { rms[it] }.coerceAtLeast(1e-6f)
        val bassMax = (0 until frames).maxOf { bass[it] }.coerceAtLeast(1e-9f)
        val midMax = (0 until frames).maxOf { mid[it] }.coerceAtLeast(1e-9f)
        val highMax = (0 until frames).maxOf { high[it] }.coerceAtLeast(1e-9f)
        data class Proto(val t: Double, val beat: Boolean, val down: Boolean, var onset: Float)
        val protos = ArrayList<Proto>()
        for (b in beats) protos += Proto(b, true, b in downSet, 0f)
        protos.sortBy { it.t }
        // merge onsets into nearby beats, otherwise standalone events
        for (o in onsets) {
            var lo = 0; var hi = protos.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (protos[m].t < o.timeSec) lo = m + 1 else hi = m }
            val near = listOfNotNull(protos.getOrNull(lo), protos.getOrNull(lo - 1)).filter { it.beat }.minByOrNull { abs(it.t - o.timeSec) }
            if (near != null && abs(near.t - o.timeSec) < 0.05) near.onset = max(near.onset, o.strength)
            else protos += Proto(o.timeSec, false, false, o.strength)
        }
        protos.sortBy { it.t }
        return protos.mapIndexed { idx, p ->
            val f = frameIndex(p.t)
            val f2 = min(frames - 1, f + 2) // features just after the attack
            val r = rms[f2] / rmsMax
            val ch = FloatArray(12) { chroma[f2 * 12 + it] }
            val chMax = ch.maxOrNull() ?: 0f
            if (chMax > 0) for (i in 0 until 12) ch[i] /= chMax
            val onsetS = if (p.onset > 0) p.onset else odf[f].toFloat().coerceIn(0f, 1f)
            val beatStrength = if (p.down) 1f else if (p.beat) 0.6f else 0.2f
            val importance = (0.45f * onsetS + 0.3f * beatStrength + 0.25f * r).coerceIn(0f, 1f)
            val pitch = if (peakConf[f2] > 0.35f && peakBin[f2] > 0) (12 * log2(binHz[peakBin[f2]] / 440.0) + 69).roundToInt() else null
            MusicEvent(
                id = idx.toLong(), timeSec = p.t, sourceType = if (p.beat) SourceType.AUDIO_BEAT else SourceType.AUDIO_ONSET,
                midiNote = pitch, velocity = r, onsetStrength = onsetS, beatStrength = beatStrength,
                isBeat = p.beat, isDownbeat = p.down, rms = r,
                bassEnergy = (bass[f2] / bassMax), midEnergy = (mid[f2] / midMax), highEnergy = (high[f2] / highMax),
                spectralCentroid = centroid[f2], chroma = ch, importance = importance,
            )
        }
    }
}
