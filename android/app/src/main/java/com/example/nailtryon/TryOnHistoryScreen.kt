package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared by result and history: identical available width, original aspect, no crop. */
@Composable
internal fun TryOnPreview(bitmap: Bitmap, description: String, modifier: Modifier = Modifier) {
    Image(
        bitmap.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)
            .background(Color(0xFFFFE9E9), RoundedCornerShape(18.dp)),
    )
}

@Composable
internal fun TryOnHistoryScreen(history: List<TryOnHistoryEntry>, store: TryOnHistoryStore, loaded: Boolean) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("history-list"),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("历史记录", fontSize = 23.sp, color = Color(0xFF211F21))
            Text("回顾最近的试戴效果", fontSize = 13.sp, color = Color(0xFF777174), modifier = Modifier.padding(top = 6.dp))
        }
        if (!loaded) item { CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp)) }
        else if (history.isEmpty()) item { Text("还没有试戴记录", color = Color(0xFF777174)) }
        items(history, key = { it.id }) { entry ->
            val bitmap by produceState<Bitmap?>(null, entry.id, store) {
                value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(store.imageFile(entry).absolutePath) }
            }
            Column {
                Text(entry.name, fontSize = 16.sp, color = Color(0xFF211F21), modifier = Modifier.padding(bottom = 10.dp).testTag("history-name-${entry.id}"))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                    val image = bitmap
                    if (image != null) TryOnPreview(image, "历史试戴效果：${entry.name}", Modifier.testTag("history-image-${entry.id}"))
                    else Box(
                        Modifier.fillMaxWidth().aspectRatio(entry.width.toFloat() / entry.height)
                            .background(Color(0xFFFFE9E9), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    val date = remember(entry.createdAt) {
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.createdAt))
                    }
                    Text(
                        date, fontSize = 11.sp, color = Color.White,
                        modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp).testTag("history-time-${entry.id}"),
                    )
                }
            }
        }
    }
}
