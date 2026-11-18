package app.murmur

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val native = NativeBridge.hello()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                SkeletonScreen(native)
            }
        }
    }
}

@Composable
fun SkeletonScreen(nativeMessage: String) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = Greeting.title(widthDp),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("title"),
            )
            Text("width = $widthDp dp", style = MaterialTheme.typography.bodyLarge)
            Text(
                nativeMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("native"),
            )
        }
    }
}
