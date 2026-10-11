package io.github.xomel45.openschool

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "5 мин", "3 ч", "вчера": how long ago the app noticed the item. */
fun ago(ts: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
    val m = maxOf(0, (nowSeconds - ts) / 60)
    if (m < 1) return "только что"
    if (m < 60) return "$m мин"
    val h = Math.round(m / 60.0)
    if (h < 24) return "$h ч"
    return if (h < 48) "вчера" else "${Math.round(h / 24.0)} дн."
}

/** The "what's new" feed behind the bell; a click marks the item read and opens the page it belongs to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedSheet(news: NewsStore, onDismiss: () -> Unit, onOpen: (Kind) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Что нового", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (news.unread > 0) TextButton({ news.markRead() }) { Text("Прочитать все") }
            }
            if (news.feed.isEmpty()) Text("Пока ничего нового.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(news.feed, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { news.markRead(setOf(item.id)); onOpen(item.kind) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.kind == Kind.Mark && item.value != null) MarkChip(item.value, 36) else Text(if (item.kind == Kind.Homework) "📝" else "⚠", fontSize = 22.sp, modifier = Modifier.padding(end = 4.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.title, fontWeight = if (item.read) FontWeight.Normal else FontWeight.Bold)
                            Text(item.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(ago(item.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
