package com.example.gdsviewer.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The "Recent files" dialog opened from the ⋮ menu. Closes itself when the list becomes empty. */
@Composable
fun RecentFilesDialog(vm: ViewerViewModel, onDismiss: () -> Unit) {
    val files = vm.recentFiles
    if (files.isEmpty()) {
        onDismiss()
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recent files") },
        text = {
            RecentFilesList(
                files,
                onOpen = { onDismiss(); vm.openRecent(it.id) },
                onRemove = { vm.removeRecent(it.id) },
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
