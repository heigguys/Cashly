package com.nie.cashly.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.nie.cashly.core.*
import kotlinx.coroutines.flow.map
import kotlin.math.abs

private val Context.cashlySettings by preferencesDataStore("cashly_settings")
class Repository(val context: Context, val db: CashlyDatabase = CashlyDatabase.get(context)) {
    val dao = db.dao()
    val settings = context.cashlySettings.data.map { Settings(it[stringPreferencesKey("theme")] ?: "系统", it[longPreferencesKey("last")] ?: 0L, it[booleanPreferencesKey("connected")] ?: false) }
    suspend fun theme(value: String) { context.cashlySettings.edit { it[stringPreferencesKey("theme")] = value } }
    suspend fun connection(connected: Boolean) { context.cashlySettings.edit { it[booleanPreferencesKey("connected")] = connected } }
    suspend fun init() { db.withTransaction { if (dao.categories().isEmpty()) defaultCategories.forEach { dao.category(Category(it)) } }; prune() }
    private suspend fun classify(text: String): String = categoryFor(text).takeIf { candidate -> dao.categories().any { it.name == candidate } } ?: "其他"
    suspend fun prune() = dao.prune(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    suspend fun captureNotification(osKey: String, platform: String, text: String, time: Long, parsed: Parsed?) {
        val slot = db.withTransaction {
            dao.slot(osKey) ?: NotificationSlot(osKey, "notification:" + java.util.UUID.randomUUID()).also { dao.slot(it) }
        }
        capture(slot.eventKey, platform, text, time, parsed)
    }
    suspend fun capture(key: String, platform: String, text: String, time: Long, parsed: Parsed?) {
        context.cashlySettings.edit { it[longPreferencesKey("last")] = System.currentTimeMillis() }
        prune()
        if (parsed == null) return
        db.withTransaction {
            if (dao.source(key) != null) return@withTransaction
            val old = dao.event(key)
            if (old != null && !old.pending) return@withTransaction
            val event = Event(key, platform, old?.received ?: time, text, true, parsed.evidence, parsed.amount, parsed.kind, parsed.party, parsed.order)
            dao.event(event)
            if (parsed.certain) {
                val existing = parsed.order?.let { dao.byOrder(platform, it) }
                val entry = existing ?: Entry(amount = parsed.amount!!, kind = parsed.kind!!, platform = platform, time = time,
                    party = parsed.party, orderId = parsed.order, category = classify(parsed.party), timeEstimated = true)
                if (existing == null) dao.insert(entry)
                dao.source(Source(key, entry.id, "通知 · ${parsed.evidence}")); dao.resolve(key)
            }
        }
    }
    suspend fun save(entry: Entry, existing: Boolean, eventKey: String? = null) = db.withTransaction {
        require(entry.amount > 0 && entry.category.isNotBlank()) { "金额和分类不能为空" }
        require(dao.categories().any { it.name == entry.category }) { "分类不存在" }
        if (existing) dao.update(entry.copy(updated = System.currentTimeMillis())) else dao.insert(entry)
        if (eventKey != null) { dao.source(Source(eventKey, entry.id, "通知 · 用户确认")); dao.resolve(eventKey) }
        else if (!existing) dao.source(Source("manual:${entry.id}", entry.id, "手动记录"))
    }
    suspend fun delete(entry: Entry) = db.withTransaction {
        // Keep a resolved event tombstone so notification replay does not recreate a deleted record.
        dao.sources().filter { it.entryId == entry.id && it.key.startsWith("notification:") }.forEach { dao.resolve(it.key) }
        dao.delete(entry.id)
    }
    suspend fun renameCategory(old: String, target: String, remove: Boolean = false) = db.withTransaction {
        require(target.isNotBlank() && target != old) { "请输入不同的有效分类" }
        require(old != "其他") { "其他是系统兜底分类，不能删除或重命名" }
        dao.category(Category(target)); dao.migrateCategory(old, target); dao.deleteCategory(old)
    }
    suspend fun preview(rows: List<ImportRow>): List<ImportMatch> {
        require(rows.filter { it.order != null }.groupBy { it.platform to it.order }.values.all { group -> group.map { it.amount to it.kind }.distinct().size == 1 }) { "同一交易号出现不同金额或收支类型，请检查账单" }
        val entries = dao.entries(); val sources = dao.sources().associateBy { it.key }
        val seen = mutableSetOf<String>()
        return rows.map { row ->
            val identity = row.order?.let { row.platform + it } ?: row.sourceKey
            val exact = sources[row.sourceKey]?.entryId ?: row.order?.let { id -> entries.find { it.platform == row.platform && it.orderId == id }?.id }
            val repeated = !seen.add(identity)
            val candidates = if (exact == null && !repeated) entries.filter {
                it.platform == row.platform && it.kind == row.kind && it.amount == row.amount && abs(it.time - row.time) <= 10 * 60 * 1000 &&
                    (it.party.isBlank() || row.party.isBlank() || it.party == row.party)
            } else emptyList()
            ImportMatch(row, exact, candidates, repeated)
        }
    }
    suspend fun commit(matches: List<ImportMatch>, choices: Map<String, String>) = db.withTransaction {
        require(matches.all { it.candidates.isEmpty() || choices.containsKey(it.row.sourceKey) }) { "请逐项处理疑似重复记录" }
        matches.forEach { match ->
            if (match.repeated) return@forEach
            val row = match.row
            if (dao.source(row.sourceKey) != null) return@forEach
            val linkedId = match.exact ?: choices[row.sourceKey]?.takeIf { it != "new" }
            val existing = row.order?.let { dao.byOrder(row.platform, it) } ?: linkedId?.let { id -> dao.entries().find { it.id == id } }
            val entry = existing ?: Entry(amount = row.amount, kind = row.kind, platform = row.platform, time = row.time,
                category = classify(row.party + row.note), party = row.party, note = row.note, orderId = row.order)
            if (existing == null) dao.insert(entry) else {
                require(existing.amount == row.amount && existing.kind == row.kind && existing.platform == row.platform) { "交易号对应的金额或类型不一致，已取消导入" }
                dao.update(existing.copy(orderId = existing.orderId ?: row.order, party = existing.party.ifBlank { row.party },
                    time = if (existing.timeEstimated) row.time else existing.time, timeEstimated = false, updated = System.currentTimeMillis()))
            }
            dao.source(Source(row.sourceKey, entry.id, "${row.platform}账单导入"))
        }
    }
}
data class Settings(val theme: String = "系统", val last: Long = 0, val connected: Boolean = false)
data class ImportMatch(val row: ImportRow, val exact: String?, val candidates: List<Entry>, val repeated: Boolean)
