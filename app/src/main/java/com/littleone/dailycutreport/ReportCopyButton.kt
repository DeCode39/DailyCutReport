package com.littleone.dailycutreport

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReportCopyButton(busy: Boolean, onCopyDay: () -> Unit, onCopyWeek: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
        modifier = Modifier.size(48.dp).clip(CircleShape).combinedClickable(
            enabled = !busy,
            role = Role.Button,
            onClickLabel = "Copy selected day's report JSON",
            onLongClickLabel = "Copy seven days ending on selected date as JSON",
            onClick = onCopyDay,
            onLongClick = onCopyWeek
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy daily report JSON")
        }
    }
}
