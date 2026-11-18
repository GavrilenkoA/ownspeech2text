package app.murmur.spike

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(Modifier.padding(24.dp)) {
                Text("Murmur platform spike")
                Text(status)
                Button(onClick = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1) }) { Text("Grant mic + notifications") }
                Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open accessibility settings") }
                Button(onClick = { scheduleDefault() }) { Text("Download test model (UIDT)") }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    override fun onResume() { super.onResume(); report() }

    private fun handle(i: Intent?) {
        if (i?.getStringExtra("cmd") == "rec_bg") {
            // Control experiment: start recording from a visible activity, the caller then presses HOME.
            val r = Recorder("activity-then-background", perSecondLog = true)
            r.start(android.os.SystemClock.uptimeMillis())
            window.decorView.postDelayed({ Ev.log("rec_bg_result", "a11yConnected" to (SpikeA11yService.instance != null),
                "rec" to org.json.JSONObject(r.stop()).toString()) }, i.getLongExtra("ms", 6000))
        }
        if (i?.getStringExtra("cmd") == "download") {
            val r = DownloadJob.schedule(this, i.getStringExtra("url") ?: DEFAULT_URL, i.getStringExtra("name") ?: DEFAULT_NAME,
                i.getStringExtra("sha256") ?: DEFAULT_SHA, i.getLongExtra("size", DEFAULT_SIZE))
            Ev.log("uidt_schedule", "from" to "visible_activity", "result" to r)
        }
    }

    private fun scheduleDefault() {
        Ev.log("uidt_schedule", "from" to "button", "result" to DownloadJob.schedule(this, DEFAULT_URL, DEFAULT_NAME, DEFAULT_SHA, DEFAULT_SIZE))
    }

    private fun report() {
        val ops = getSystemService(AppOpsManager::class.java)
        // OPSTR_ACCESS_RESTRICTED_SETTINGS is @hide; the raw op string is what AOSP uses.
        val restricted = runCatching {
            @Suppress("DEPRECATION")
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", Process.myUid(), packageName)
        }.getOrElse { Ev.log("restricted_op_check_failed", "err" to it.toString()); -99 }
        val aapm = if (Build.VERSION.SDK_INT >= 36) runCatching {
            getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled
        }.getOrElse { "err:$it" } else "n/a"
        val installer = runCatching { packageManager.getInstallSourceInfo(packageName).let {
            "installer=${it.installingPackageName} initiating=${it.initiatingPackageName} source=${it.packageSource}" } }.getOrElse { it.toString() }
        status = "a11y connected=${SpikeA11yService.instance != null}\nrestricted_settings op=$restricted (0=allowed)\nAAPM=$aapm\n$installer"
        Ev.log("main_status", "a11yConnected" to (SpikeA11yService.instance != null), "restrictedSettingsOp" to restricted,
            "aapm" to aapm.toString(), "install" to installer)
    }

    companion object {
        const val DEFAULT_URL = "https://huggingface.co/openai/whisper-tiny/resolve/169d4a4341b33bc18d8881c4b69c2e104e1cc0af/model.safetensors"
        const val DEFAULT_NAME = "whisper-tiny.safetensors"
        const val DEFAULT_SHA = "7ebd0e69e78190ffe1438491fa05cc1f5c1aa3a4c4db3bc1723adbb551ea2395"
        const val DEFAULT_SIZE = 151061672L
    }
}
