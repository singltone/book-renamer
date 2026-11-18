package com.example.bookrenamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.util.Collections
import java.util.zip.ZipInputStream

// ---------- Дані ----------

data class Meta(val title: String?, val author: String?, val series: String?, val number: String?)

data class Book(val uri: Uri, val name: String, val base: String, val ext: String, val meta: Meta) {
    val isSeries: Boolean
        get() = !meta.series.isNullOrBlank() || !meta.number.isNullOrBlank()
}

enum class Part(val label: String) {
    SERIES("Серія + номер"),
    TITLE("Назва"),
    AUTHOR("Автор")
}

data class Settings(
    val useSeriesName: Boolean = true,
    val useNumber: Boolean = true,
    val useTitle: Boolean = true,
    val useAuthor: Boolean = true,
    val order: List<Part> = listOf(Part.SERIES, Part.TITLE, Part.AUTHOR),
    val sep: String = " - "
)

// ---------- Читання метаданих ----------

fun parseOpf(bytes: ByteArray): Meta {
    var title: String? = null
    var author: String? = null
    var series: String? = null
    var number: String? = null
    try {
        val p = Xml.newPullParser()
        p.setInput(bytes.inputStream(), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name.substringAfter(':')) {
                    "title" -> if (title == null) title = p.nextText().trim()
                    "creator" -> if (author == null) author = p.nextText().trim()
                    "meta" -> {
                        val name = p.getAttributeValue(null, "name")
                        val prop = p.getAttributeValue(null, "property")
                        when {
                            name == "calibre:series" -> series = p.getAttributeValue(null, "content")
                            name == "calibre:series_index" -> number = p.getAttributeValue(null, "content")
                            prop == "belongs-to-collection" && series == null -> series = p.nextText().trim()
                            prop == "group-position" && number == null -> number = p.nextText().trim()
                        }
                    }
                }
            }
            ev = p.next()
        }
    } catch (e: Exception) {
        // повертаємо те, що встигли прочитати
    }
    return Meta(title, author, series, number)
}

fun parseEpub(ctx: Context, uri: Uri): Meta {
    ctx.contentResolver.openInputStream(uri)?.use { raw ->
        ZipInputStream(raw).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.endsWith(".opf", true)) return parseOpf(zip.readBytes())
                e = zip.nextEntry
            }
        }
    }
    return Meta(null, null, null, null)
}

fun parseFb2(ctx: Context, uri: Uri): Meta {
    var title: String? = null
    var first: String? = null
    var last: String? = null
    var series: String? = null
    var number: String? = null
    var inAuthor = false
    var authorDone = false
    try {
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            val p = Xml.newPullParser()
            p.setInput(raw, null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "author" -> if (!authorDone) inAuthor = true
                        "first-name" -> if (inAuthor && first == null) first = p.nextText().trim()
                        "last-name" -> if (inAuthor && last == null) last = p.nextText().trim()
                        "book-title" -> if (title == null) title = p.nextText().trim()
                        "sequence" -> if (series == null) {
                            series = p.getAttributeValue(null, "name")
                            number = p.getAttributeValue(null, "number")
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (p.name == "author" && inAuthor) { inAuthor = false; authorDone = true }
                    if (p.name == "title-info") break
                }
                ev = p.next()
            }
        }
    } catch (e: Exception) {
        // повертаємо те, що встигли прочитати
    }
    val author = listOfNotNull(first, last).joinToString(" ").ifBlank { null }
    return Meta(title, author, series, number)
}

// Для PDF/DjVu: метаданих серії немає, шукаємо номер у назві файлу
val numberRegex = Regex("(?i)(?:книга|кн\\.?|book|bk|#|том|т\\.)\\s*(\\d{1,3})")

fun metaFromName(base: String): Meta {
    val n = numberRegex.find(base)?.groupValues?.get(1)
    return Meta(base, null, null, n)
}

fun loadBook(ctx: Context, uri: Uri, name: String): Book? {
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
    val base = if (dot > 0) name.substring(0, dot) else name
    if (ext !in setOf("epub", "fb2", "pdf", "djvu", "djv")) return null
    val meta = try {
        when (ext) {
            "epub" -> parseEpub(ctx, uri)
            "fb2" -> parseFb2(ctx, uri)
            else -> metaFromName(base)
        }
    } catch (e: Exception) {
        Meta(null, null, null, null)
    }
    return Book(uri, name, base, ext, meta)
}

fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0) ?: ""
    }
    return uri.lastPathSegment ?: ""
}

fun listFiles(ctx: Context, tree: Uri, recursive: Boolean): List<Pair<Uri, String>> {
    val res = mutableListOf<Pair<Uri, String>>()
    fun walk(docId: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val dirs = mutableListOf<String>()
        ctx.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (recursive) dirs.add(id)
                } else {
                    res.add(DocumentsContract.buildDocumentUriUsingTree(tree, id) to name)
                }
            }
        }
        dirs.forEach { walk(it) }
    }
    walk(DocumentsContract.getTreeDocumentId(tree))
    return res
}

// ---------- Побудова нового імені ----------

fun pad(n: String?): String? {
    if (n == null) return null
    val t = n.trim()
    val d = t.replace(',', '.').toDoubleOrNull() ?: return t
    return if (d == Math.floor(d)) "%02d".format(d.toInt()) else t
}

fun clean(s: String): String =
    s.replace(Regex("[\\\\/:*?\"<>|]"), "").replace(Regex("\\s+"), " ").trim()

fun newName(b: Book, s: Settings): String {
    val parts = s.order.mapNotNull { p ->
        when (p) {
            Part.SERIES -> if (!b.isSeries) null else listOfNotNull(
                if (s.useSeriesName) b.meta.series?.takeIf { it.isNotBlank() } else null,
                if (s.useNumber) pad(b.meta.number) else null
            ).joinToString(" ").ifBlank { null }
            Part.TITLE -> if (s.useTitle) (b.meta.title?.takeIf { it.isNotBlank() } ?: b.base) else null
            Part.AUTHOR -> if (s.useAuthor) b.meta.author?.takeIf { it.isNotBlank() } else null
        }
    }
    val core = clean(parts.joinToString(s.sep)).take(200).trim()
    return if (core.isBlank()) b.name else core + "." + b.ext
}

// ---------- Інтерфейс ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var s by remember { mutableStateOf(Settings()) }
    var filter by remember { mutableStateOf(0) } // 0 усі, 1 серійні, 2 без серії
    var recursive by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Оберіть теку або файли") }
    var showPreview by remember { mutableStateOf(false) }
    var excluded by remember { mutableStateOf(setOf<Uri>()) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            status = "Читаю книги..."
            books = withContext(Dispatchers.IO) {
                listFiles(ctx, uri, recursive).mapNotNull { loadBook(ctx, it.first, it.second) }
            }
            status = "Знайдено книг: ${books.size}"
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data ?: return@rememberLauncherForActivityResult
        val uris = mutableListOf<Uri>()
        val clip = d.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            d.data?.let { uris.add(it) }
        }
        scope.launch {
            status = "Читаю книги..."
            books = withContext(Dispatchers.IO) {
                uris.mapNotNull { u -> loadBook(ctx, u, displayName(ctx, u)) }
            }
            status = "Вибрано книг: ${books.size}"
        }
    }

    val visible = books.filter {
        when (filter) {
            1 -> it.isSeries
            2 -> !it.isSeries
            else -> true
        }
    }
    val plan = visible.map { it to newName(it, s) }.filter { it.second != it.first.name }

    // ----- Екран перегляду змін -----
    if (showPreview) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Перегляд змін: ${plan.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = {
                    excluded = if (excluded.isEmpty()) plan.map { it.first.uri }.toSet() else emptySet()
                }) { Text("Усі") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(plan) { (b, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = b.uri !in excluded,
                            onCheckedChange = { on -> excluded = if (on) excluded - b.uri else excluded + b.uri }
                        )
                        Column {
                            Text("було: ${b.name}", style = MaterialTheme.typography.bodySmall)
                            Text("стане: $n", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { showPreview = false }) { Text("Назад") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    scope.launch {
                        val todo = plan.filter { it.first.uri !in excluded }
                        val ok = withContext(Dispatchers.IO) {
                            todo.count { (b, n) ->
                                try {
                                    DocumentsContract.renameDocument(ctx.contentResolver, b.uri, n) != null
                                } catch (e: Exception) {
                                    false
                                }
                            }
                        }
                        status = "Перейменовано: $ok із ${todo.size}. Щоб продовжити, оберіть теку чи файли знову."
                        books = emptyList()
                        showPreview = false
                    }
                }) { Text("Перейменувати (${plan.count { it.first.uri !in excluded }})") }
            }
        }
        return
    }

    // ----- Головний екран -----
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text("Перейменування книг", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { pickFolder.launch(null) }) { Text("Обрати теку") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                pickFiles.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                })
            }) { Text("Обрати файли") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = recursive, onCheckedChange = { recursive = it })
            Spacer(Modifier.width(8.dp))
            Text("Теки всередині теки")
        }
        Text("$status (серійних: ${books.count { it.isSeries }}, показано: ${visible.size})")

        Spacer(Modifier.height(12.dp))
        Text("Які книги", style = MaterialTheme.typography.titleMedium)
        Row {
            listOf("Усі", "Серійні", "Несерійні").forEachIndexed { i, label ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                Spacer(Modifier.width(6.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Що ставити в ім'я", style = MaterialTheme.typography.titleMedium)
        CheckRow("Серія", s.useSeriesName) { s = s.copy(useSeriesName = it) }
        CheckRow("Номер", s.useNumber) { s = s.copy(useNumber = it) }
        CheckRow("Назва", s.useTitle) { s = s.copy(useTitle = it) }
        CheckRow("Автор", s.useAuthor) { s = s.copy(useAuthor = it) }

        Spacer(Modifier.height(12.dp))
        Text("Порядок частин", style = MaterialTheme.typography.titleMedium)
        s.order.forEachIndexed { i, part ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${part.label}", Modifier.weight(1f))
                TextButton(onClick = {
                    val l = s.order.toMutableList()
                    if (i > 0) { Collections.swap(l, i, i - 1); s = s.copy(order = l) }
                }) { Text("▲") }
                TextButton(onClick = {
                    val l = s.order.toMutableList()
                    if (i < l.size - 1) { Collections.swap(l, i, i + 1); s = s.copy(order = l) }
                }) { Text("▼") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Розділювач", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Тире" to " - ", "Пробіл" to " ", "Крапка" to ". ", "Підкреслення" to "_").forEach { (label, value) ->
                FilterChip(selected = s.sep == value, onClick = { s = s.copy(sep = value) }, label = { Text(label) })
                Spacer(Modifier.width(6.dp))
            }
        }
        OutlinedTextField(
            value = s.sep,
            onValueChange = { s = s.copy(sep = it) },
            label = { Text("Свій розділювач") },
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))
        visible.firstOrNull()?.let {
            Text("Приклад:", style = MaterialTheme.typography.titleSmall)
            Text(it.name, style = MaterialTheme.typography.bodySmall)
            Text("→ ${newName(it, s)}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
        }
        Button(
            enabled = plan.isNotEmpty(),
            onClick = { excluded = emptySet(); showPreview = true }
        ) { Text("Показати зміни (${plan.size})") }
    }
}
