package au.howardagent.download

import android.app.ActivityManager
import android.content.Context
import android.os.Build

data class DeviceProfile(
    val ramGb: Int,
    val cpuCores: Int,
    val soc: String,
    val suitability: Suitability
)

enum class Suitability(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    MODERATE("Moderate"),
    LIMITED("Limited"),
    UNSUPPORTED("Unsupported")
}

object DeviceDetector {

    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val ramGb = (memInfo.totalMem / (1024L * 1024 * 1024)).toInt()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val soc = Build.SOC_MODEL.ifBlank { Build.HARDWARE }

        val suitability = when {
            ramGb >= 12 -> Suitability.EXCELLENT
            ramGb >= 8  -> Suitability.GOOD
            ramGb >= 6  -> Suitability.MODERATE
            ramGb >= 4  -> Suitability.LIMITED
            else        -> Suitability.UNSUPPORTED
        }

        return DeviceProfile(ramGb, cpuCores, soc, suitability)
    }

    fun maxRecommendedModelGb(ramGb: Int): Float = when {
        ramGb >= 16 -> 8f
        ramGb >= 12 -> 5f
        ramGb >= 8  -> 3.5f
        ramGb >= 6  -> 2f
        ramGb >= 4  -> 1f
        else        -> 0f
    }
}
