package com.example.gdsviewer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gdsviewer.R

private val CanvasColor = Color(0xFF101014)

/** The whole screen: top bar, dialogs and the body that depends on [ViewerViewModel.state]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: ViewerViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.openUri(uri)
    }
    val openFile = { picker.launch(arrayOf("*/*")) }
    var showLayers by remember { mutableStateOf(false) }
    var showCells by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val ready = vm.state is ViewerState.Ready

    Scaffold(
        containerColor = CanvasColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.fileName ?: "GDS Viewer", maxLines = 1)
                        val cell = vm.geometry?.cellName
                        if (ready && cell != null) {
                            Text(cell, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = openFile) {
                        Icon(painterResource(R.drawable.ic_folder_open), contentDescription = "Open file")
                    }
                    if (ready) {
                        IconButton(onClick = { showLayers = true }) {
                            Icon(painterResource(R.drawable.ic_layers), contentDescription = "Layers")
                        }
                        IconToggleButton(
                            checked = vm.rulerMode,
                            onCheckedChange = { vm.setRulerMode(it) },
                            colors = IconButtonDefaults.iconToggleButtonColors(
                                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Icon(painterResource(R.drawable.ic_straighten), contentDescription = "Ruler")
                        }
                        IconButton(onClick = { vm.fitToView() }) {
                            Icon(painterResource(R.drawable.ic_fit_screen), contentDescription = "Fit to screen")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (ready) {
                                DropdownMenuItem(
                                    text = { Text("Choose cell…") },
                                    onClick = { menuOpen = false; showCells = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Lock rotation") },
                                    trailingIcon = if (vm.rotationLocked) ({ CheckIcon() }) else null,
                                    onClick = { menuOpen = false; vm.toggleRotationLock() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Snap ruler to shapes") },
                                    trailingIcon = if (vm.snapEnabled) ({ CheckIcon() }) else null,
                                    onClick = { menuOpen = false; vm.toggleSnap() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Open sample layout") },
                                onClick = { menuOpen = false; vm.openSample() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().background(CanvasColor).padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = vm.state) {
                ViewerState.Empty -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_folder_open), contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Open a GDSII layout (.gds or .gds.gz)", textAlign = TextAlign.Center, color = Color.White)
                    Button(onClick = openFile) { Text("Open file") }
                    OutlinedButton(onClick = { vm.openSample() }) { Text("Try the sample layout") }
                }
                is ViewerState.Loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(s.message, color = Color.White)
                }
                is ViewerState.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = openFile) { Text("Open file") }
                }
                ViewerState.Ready -> LayoutViewer(vm)
            }
        }
    }

    if (showLayers && ready) LayerSheet(vm, onDismiss = { showLayers = false })
    if (showCells && ready) CellPickerDialog(vm, onDismiss = { showCells = false })
}

@Composable
private fun CheckIcon() {
    Icon(painterResource(R.drawable.ic_check), contentDescription = "On")
}
