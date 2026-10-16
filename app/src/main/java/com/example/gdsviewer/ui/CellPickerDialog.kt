package com.example.gdsviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.gdsviewer.R

/** Dialog to choose which cell of the library to display. Top cells are listed first. */
@Composable
fun CellPickerDialog(vm: ViewerViewModel, onDismiss: () -> Unit) {
    val library = vm.library ?: return
    val current = vm.frame?.name
    var query by remember { mutableStateOf("") }
    val topNames = remember(library) { library.topCells.map { it.name }.toSet() }
    val names = remember(library, query) {
        library.cells.map { it.name }
            .filter { it.contains(query, ignoreCase = true) }
            .sortedWith(compareBy({ it !in topNames }, { it.lowercase() }))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose cell") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(names, key = { it }) { name ->
                        ListItem(
                            modifier = Modifier.clickable {
                                vm.selectCell(name)
                                onDismiss()
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(name) },
                            supportingContent = if (name in topNames) ({ Text("top cell") }) else null,
                            trailingContent = if (name == current) ({
                                Icon(painterResource(R.drawable.ic_check), contentDescription = "Current cell")
                            }) else null,
                        )
                    }
                }
            }
        },
    )
}
