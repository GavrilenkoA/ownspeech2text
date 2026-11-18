package app.murmur.spike

import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/** One JSON line per event: `EV <name> {...}` under tag MurmurSpike. the tools/ scripts grep these. */
object Ev {
    const val TAG = "MurmurSpike"
    fun log(event: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject()
        o.put("pid", Process.myPid())
        o.put("up", SystemClock.uptimeMillis())
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        Log.i(TAG, "EV $event $o")
    }
}
