package dev.koggles.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "About & licenses": every file under assets/licenses (code and bundled content), tap to read. */
@Composable
fun LicensesScreen(onClose: () -> Unit) {
    val assets = LocalContext.current.assets
    val files = remember { assets.list("licenses").orEmpty().sorted() }
    var openFile by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize().testTag("licenses").blockTouchesBelow()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            TextButton(onClick = onClose) { Text("Close") }
            Text("About & licenses", style = MaterialTheme.typography.titleLarge)
            Text(
                "Koggles is built on projectM (LGPL 2.1) and the Cardboard SDK (Apache 2.0), and bundles " +
                    "the Cream of the Crop presets and the Milkdrop texture pack.",
                style = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(Modifier.testTag("licenses-list"), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(files, key = { it }) { name ->
                    Column(Modifier.fillMaxWidth().clickable { openFile = if (openFile == name) null else name }) {
                        Text((if (openFile == name) "▾ " else "▸ ") + name, Modifier.padding(vertical = 8.dp))
                        if (openFile == name) {
                            val text = remember(name) {
                                runCatching { assets.open("licenses/$name").bufferedReader().use { it.readText() } }
                                    .getOrElse { "Unreadable: ${it.message}" }
                            }
                            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
