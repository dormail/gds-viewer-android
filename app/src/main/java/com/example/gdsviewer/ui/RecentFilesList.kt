package com.example.gdsviewer.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.gdsviewer.R
import com.example.gdsviewer.data.RecentFile

/**
 * Rows for the recent files: name, "5 minutes ago" (plus "saved copy" if we keep our own copy)
 * and a remove button. Used both on the start screen and in the "Recent files" dialog.
 */
@Composable
fun RecentFilesList(
    files: List<RecentFile>,
    onOpen: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        for (file in files) {
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(file) }.padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    val now = System.currentTimeMillis()
                    val ago = if (now - file.openedAt < DateUtils.MINUTE_IN_MILLIS) {
                        "Just now" // the relative formatter would say "0 minutes ago"
                    } else {
                        DateUtils.getRelativeTimeSpanString(file.openedAt, now, DateUtils.MINUTE_IN_MILLIS)
                    }
                    Text(
                        if (file.cachedPath != null) "$ago · saved copy" else "$ago",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRemove(file) }) {
                    Icon(
                        painterResource(R.drawable.ic_close), contentDescription = "Remove from recent files",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, // the canvas is not a theme color, so set it
                    )
                }
            }
        }
    }
}
