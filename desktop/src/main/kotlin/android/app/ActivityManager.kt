package android.app

import java.lang.management.ManagementFactory

/** Desktop-Ersatz: Arbeitsspeicher des Rechners. */
class ActivityManager {
    class MemoryInfo {
        @JvmField var totalMem: Long = 0L
        @JvmField var availMem: Long = 0L
        @JvmField var lowMemory: Boolean = false
    }

    fun getMemoryInfo(info: MemoryInfo) {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val sun = os as? com.sun.management.OperatingSystemMXBean
        info.totalMem = sun?.totalMemorySize ?: Runtime.getRuntime().maxMemory()
        info.availMem = sun?.freeMemorySize ?: Runtime.getRuntime().freeMemory()
        info.lowMemory = info.availMem < 512L * 1024 * 1024
    }
}
