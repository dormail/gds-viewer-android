package com.example.gdsviewer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

/** Bottom sheet listing all layers: tap toggles visibility, long-press shows only that layer. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LayerSheet(vm: ViewerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Layers", style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = { vm.setAllLayersVisible(true) }) { Text("All") }
                TextButton(onClick = { vm.setAllLayersVisible(false) }) { Text("None") }
            }
        }
        Text(
            "Tap to show/hide · long-press to show only one layer",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        val numbers = NumberFormat.getIntegerInstance(Locale.getDefault())
        LazyColumn {
            items(vm.layers, key = { it.key }) { layer ->
                val color = Color(layer.color)
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { vm.toggleLayer(layer.key) },
                        onLongClick = { vm.soloLayer(layer.key) },
                    ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color.copy(alpha = 0.4f))
                                .border(2.dp, color, RoundedCornerShape(4.dp)),
                        )
                    },
                    headlineContent = { Text("Layer ${layer.key.layer}/${layer.key.datatype}") },
                    supportingContent = { Text("${numbers.format(layer.shapeCount)} shapes") },
                    trailingContent = { Checkbox(checked = layer.visible, onCheckedChange = null) },
                )
            }
        }
    }
}
