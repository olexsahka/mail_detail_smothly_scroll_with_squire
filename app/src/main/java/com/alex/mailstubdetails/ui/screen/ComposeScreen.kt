package com.alex.mailstubdetails.ui.screen

import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alex.mailstubdetails.ui.webview.SquireMode
import com.alex.mailstubdetails.ui.webview.SquireWebViewContainer

/**
 * Screen 3 — Compose / reply.
 *
 * Static TopAppBar → native input fields → formatting toolbar → Squire (editable).
 * Keyboard pushes layout up via imePadding + verticalScroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(onBack: () -> Unit) {
    var toField by remember { mutableStateOf("") }
    var ccField by remember { mutableStateOf("") }
    var subjectField by remember { mutableStateOf("") }
    var showCc by remember { mutableStateOf(false) }

    // Populated by SquireWebViewContainer.onWebViewReady — used by the toolbar.
    var editorRef by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Message") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Discard")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            editorRef?.evaluateJavascript("getHtml()") { _ -> onBack() }
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Send")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            // ── To ────────────────────────────────────────────────────────
            OutlinedTextField(
                value = toField,
                onValueChange = { toField = it },
                label = { Text("To") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                trailingIcon = {
                    TextButton(onClick = { showCc = !showCc }) {
                        Text(if (showCc) "Hide CC" else "Add CC")
                    }
                },
                singleLine = true
            )

            // ── CC (optional) ─────────────────────────────────────────────
            if (showCc) {
                OutlinedTextField(
                    value = ccField,
                    onValueChange = { ccField = it },
                    label = { Text("CC") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true
                )
            }

            // ── Subject ───────────────────────────────────────────────────
            OutlinedTextField(
                value = subjectField,
                onValueChange = { subjectField = it },
                label = { Text("Subject") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            // ── Formatting toolbar ────────────────────────────────────────
            FormattingToolbar(editorRef = { editorRef })

            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // ── Squire editor (editable) — same component, different mode ─
            SquireWebViewContainer(
                html = "",
                mode = SquireMode.EDITABLE,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 300.dp,
                onWebViewReady = { webView -> editorRef = webView }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Formatting toolbar ───────────────────────────────────────────────────────

@Composable
private fun FormattingToolbar(editorRef: () -> WebView?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FormatButton("B")  { editorRef()?.evaluateJavascript("execBold()", null) }
        FormatButton("I")  { editorRef()?.evaluateJavascript("execItalic()", null) }
        FormatButton("U")  { editorRef()?.evaluateJavascript("execUnderline()", null) }
        VerticalDivider(modifier = Modifier.height(24.dp))
        FormatButton("1.") { editorRef()?.evaluateJavascript("execOrderedList()", null) }
        FormatButton("•")  { editorRef()?.evaluateJavascript("execUnorderedList()", null) }
        VerticalDivider(modifier = Modifier.height(24.dp))
        FormatButton("T̶")  { editorRef()?.evaluateJavascript("execRemoveFormat()", null) }
    }
}

@Composable
private fun FormatButton(label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
