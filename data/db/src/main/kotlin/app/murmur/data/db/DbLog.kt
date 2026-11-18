package app.murmur.data.db

import android.util.Log

/** D9: codes and numbers only, never text, paths or settings values. */
internal object DbLog {
    private const val TAG = "MurmurData"

    fun code(code: String, where: String) {
        Log.w(TAG, "$code at=$where")
    }
}
