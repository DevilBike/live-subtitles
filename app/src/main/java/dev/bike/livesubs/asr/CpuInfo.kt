package dev.bike.livesubs.asr

import java.io.File

/** What the recognizer needs to know about the phone's CPU. */
internal object CpuInfo {

    /**
     * CPU features the native library was compiled to use (from the
     * `whisper.armArch` build setting) that this phone lacks. Running without
     * them would crash with an illegal instruction, so the app checks first.
     */
    fun missingFeatures(armArch: String): List<String> {
        val have = runCatching {
            File("/proc/cpuinfo").readLines()
                .filter { it.startsWith("Features") }
                .flatMap { it.substringAfter(':').trim().split(' ') }
                .toSet()
        }.getOrElse { return emptyList() }
        if (have.isEmpty()) return emptyList()
        val need = buildList {
            if ("dotprod" in armArch) add("asimddp")
            if ("fp16" in armArch) add("asimdhp")
        }
        return need.filter { it !in have }
    }

    /**
     * Threads for whisper.cpp: one per fast core, at most 4. Phones mix fast
     * and slow cores, and a thread stuck on a slow core holds up the rest.
     */
    fun recognizerThreads(): Int {
        val maxFreqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        val fast = if (maxFreqs.isEmpty()) {
            Runtime.getRuntime().availableProcessors() / 2
        } else {
            val top = maxFreqs.max()
            maxFreqs.count { it >= top * 0.7 }
        }
        return fast.coerceIn(2, 4)
    }
}
