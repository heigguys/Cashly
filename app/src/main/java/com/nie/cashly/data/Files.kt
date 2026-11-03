package com.nie.cashly.data

import com.nie.cashly.core.*
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object BillFiles {
    private const val LIMIT = 24 * 1024 * 1024
    fun read(bytes: ByteArray): ImportPreview {
        require(bytes.size <= LIMIT) { "文件超过 24 MB，请按月导出账单" }
        val table = if (bytes.take(2) == listOf(80.toByte(), 75.toByte())) xlsx(bytes) else {
            require(bytes.none { it == 0.toByte() }) { "不支持此文件或文件已加密，请导入 CSV / 未加密 XLSX" }
            val utf8 = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            Csv.read(utf8.getOrElse { bytes.toString(charset("GB18030")) })
        }
        return BillParser.parse(table)
    }
    private fun xlsx(bytes: ByteArray): List<List<String>> {
        val parts = mutableMapOf<String, ByteArray>(); var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "xl/sharedStrings.xml" || name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= LIMIT) { "解压后的账单过大" }; out.write(buffer, 0, n) }
                    parts[name] = out.toByteArray()
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
        }
        fun xml(data: ByteArray): org.w3c.dom.Document {
            require(data.none { it == 0.toByte() }) { "XLSX 仅支持 UTF-8 XML" }
            val text = data.toString(Charsets.UTF_8)
            require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) { "不允许包含外部实体的账单" }
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(data))
        }
        val strings = parts["xl/sharedStrings.xml"]?.let { data ->
            val nodes = xml(data).getElementsByTagName("si"); (0 until nodes.length).map { nodes.item(it).textContent }
        }.orEmpty()
        val sheets = parts.filterKeys { it.startsWith("xl/worksheets/") }.toSortedMap()
        require(sheets.isNotEmpty()) { "未找到 XLSX 工作表；不支持加密文件" }
        for ((_, data) in sheets) {
            val nodes = xml(data).getElementsByTagName("row")
            val rows = (0 until nodes.length).map { ri ->
                val cells = (nodes.item(ri) as Element).getElementsByTagName("c"); val row = mutableListOf<String>()
                for (ci in 0 until cells.length) {
                    val cell = cells.item(ci) as Element
                    val col = cell.getAttribute("r").takeWhile { it.isLetter() }.fold(0) { n, c -> n * 26 + (c - 'A' + 1) } - 1
                    require(col in 0..512) { "列数异常" }; while (row.size <= col) row.add("")
                    require(cell.getElementsByTagName("f").length == 0) { "不接受带公式的账单，请导出原始账单" }
                    val v = cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                    row[col] = when (cell.getAttribute("t")) { "s" -> strings.getOrNull(v.toIntOrNull() ?: -1) ?: error("共享字符串无效"); "inlineStr" -> cell.getElementsByTagName("is").item(0)?.textContent.orEmpty(); else -> v }
                }; row
            }
            if (rows.any { it.any { value -> value.trim() in listOf("交易时间", "交易创建时间") } }) return rows
        }
        error("未识别到交易工作表；当前仅接受文本格式的交易时间")
    }
}

data class Backup(val entries: List<Entry>, val sources: List<Source>, val categories: List<Category>, val events: List<Event>, val theme: String, val slots: List<NotificationSlot>)
object Backups {
    suspend fun encode(repo: Repository, theme: String): String = repo.db.withTransaction {
        val root = JSONObject().put("format", "cashly").put("version", 1).put("theme", theme)
        root.put("categories", JSONArray(repo.dao.categories().map { it.name }))
        root.put("entries", JSONArray(repo.dao.entries().map { e -> JSONObject().apply {
            put("id", e.id); put("amount", e.amount); put("kind", e.kind.name); put("platform", e.platform); put("time", e.time)
            put("category", e.category); put("party", e.party); put("note", e.note); put("orderId", e.orderId ?: JSONObject.NULL)
            put("timeEstimated", e.timeEstimated); put("refundOf", e.refundOf ?: JSONObject.NULL); put("created", e.created); put("updated", e.updated)
        } }))
        root.put("sources", JSONArray(repo.dao.sources().map { JSONObject().put("key", it.key).put("entryId", it.entryId).put("label", it.label) }))
        root.put("slots", JSONArray(repo.dao.slots().map { JSONObject().put("key", it.key).put("eventKey", it.eventKey) }))
        // Preserve replay tombstones and pending decisions, never include raw notification text in backups.
        root.put("events", JSONArray(repo.dao.events().map { JSONObject().put("key", it.key).put("platform", it.platform).put("received", it.received)
            .put("pending", it.pending).put("evidence", it.evidence).put("amount", it.amount ?: JSONObject.NULL).put("kind", it.kind?.name ?: JSONObject.NULL)
            .put("party", it.party).put("orderId", it.orderId ?: JSONObject.NULL) }))
        root.toString(2)
    }
    fun decode(text: String): Backup {
        val root = JSONObject(text)
        require(root.getString("format") == "cashly" && root.getInt("version") == 1) { "备份格式或版本不受支持" }
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
        val categories = root.getJSONArray("categories").let { a -> (0 until a.length()).map { Category(a.getString(it)) } }
        require(categories.any { it.name == "其他" } && categories.all { it.name.isNotBlank() } && categories.distinct().size == categories.size) { "备份分类无效" }
        val entries = root.getJSONArray("entries").objects().map { o ->
            listOf("amount", "time", "created", "updated").forEach { key -> require(Regex("\\d+").matches(o.get(key).toString())) { "金额和时间必须为整数" } }
            Entry(id = o.getString("id"), amount = o.getLong("amount"), kind = Kind.valueOf(o.getString("kind")),
            platform = o.getString("platform"), time = o.getLong("time"), category = o.getString("category"), party = o.getString("party"), note = o.getString("note"),
            orderId = o.nullable("orderId"), timeEstimated = o.getBoolean("timeEstimated"), refundOf = o.nullable("refundOf"), created = o.getLong("created"), updated = o.getLong("updated")) }
        require(entries.all { it.id.isNotBlank() && it.amount > 0 && it.time > 0 && it.category in categories.map(Category::name) && it.platform in listOf("微信", "支付宝", "现金", "银行卡", "其他") }) { "备份交易数据无效" }
        require(entries.map { it.id }.distinct().size == entries.size) { "备份交易 ID 重复" }
        val orders = entries.filter { it.orderId != null }.map { it.platform to it.orderId }
        require(orders.distinct().size == orders.size) { "备份交易号重复" }
        val ids = entries.map { it.id }.toSet()
        require(entries.all { it.refundOf == null || it.refundOf in ids }) { "退款引用无效" }
        val sources = root.getJSONArray("sources").objects().map { Source(it.getString("key"), it.getString("entryId"), it.getString("label")) }
        require(sources.all { it.key.isNotBlank() && it.entryId in ids } && sources.map { it.key }.distinct().size == sources.size) { "备份来源引用无效" }
        val events = root.getJSONArray("events").objects().map { Event(it.getString("key"), it.getString("platform"), it.getLong("received"), "", it.getBoolean("pending"), it.getString("evidence"),
            if (it.isNull("amount")) null else it.getLong("amount"), it.nullable("kind")?.let(Kind::valueOf), it.getString("party"), it.nullable("orderId")) }
        require(events.map { it.key }.distinct().size == events.size && events.all { it.received > 0 && (it.amount == null || it.amount > 0) }) { "通知事件无效" }
        val theme = root.getString("theme"); require(theme in listOf("系统", "浅色", "深色")) { "外观设置无效" }
        val slots = root.getJSONArray("slots").objects().map { NotificationSlot(it.getString("key"), it.getString("eventKey")) }
        require(slots.all { it.key.isNotBlank() && it.eventKey.startsWith("notification:") } && slots.map { it.key }.distinct().size == slots.size) { "通知来源标识无效" }
        return Backup(entries, sources, categories, events, theme, slots)
    }
    suspend fun restore(repo: Repository, backup: Backup) {
        repo.db.withTransaction {
            repo.dao.clearSources(); repo.dao.clearEntries(); repo.dao.clearEvents(); repo.dao.clearCategories(); repo.dao.clearSlots()
            backup.categories.forEach { repo.dao.category(it) }; backup.entries.forEach { repo.dao.insert(it) }
            backup.sources.forEach { repo.dao.source(it) }; backup.events.forEach { repo.dao.event(it) }
            backup.slots.forEach { repo.dao.slot(it) }
        }
        repo.theme(backup.theme); repo.prune()
    }
    fun csv(entries: List<Entry>): String = "\uFEFF" + (listOf(listOf("交易时间", "平台", "类型", "金额", "分类", "交易对象", "备注")) + entries.map {
        listOf(formatTime(it.time), it.platform, it.kind.label, money(it.amount), it.category, it.party, it.note)
    }).joinToString("\r\n") { row -> row.joinToString(",") { Csv.cell(if (it.startsWith("=") || it.startsWith("+") || it.startsWith("-") || it.startsWith("@")) "'" + it else it) } }
}
