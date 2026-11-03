package com.nie.cashly

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nie.cashly.core.*
import com.nie.cashly.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerIntegrationTest {
    private lateinit var db: CashlyDatabase
    private lateinit var repo: Repository
    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CashlyDatabase::class.java).build()
        repo = Repository(context, db); repo.init()
    }
    @After fun close() { db.close() }
    @Test fun refundReminderIsRecordedOnceAndCountsAsIncome() = runBlocking {
        val body = "你收到一笔26.80元退款，点此查看账单详情！"
        val parsed = parsePaymentNotification("com.eg.android.AlipayGphone", "退款提醒", body)
        val time = System.currentTimeMillis()
        repeat(2) { repo.captureNotification("refund-screenshot", "支付宝", body, time, parsed) }
        val entry = repo.dao.entries().single()
        assertEquals(2680L, entry.amount); assertEquals(Kind.REFUND, entry.kind); assertEquals("退款", entry.note)
        assertFalse(repo.dao.events().single().pending)
        val total = totals(repo.dao.entries().map { it.kind to it.amount })
        assertEquals(2680L, total.income); assertEquals(0L, total.expense)
        // Old stored refunds and restored backups use the same new accounting rule, without rewriting IDs.
        val backup = Backups.decode(Backups.encode(repo, "系统")); Backups.restore(repo, backup)
        repo.captureNotification("refund-screenshot", "支付宝", body, time, parsed)
        assertEquals(entry.id, repo.dao.entries().single().id)
        assertEquals(2680L, totals(repo.dao.entries().map { it.kind to it.amount }).income)
        assertEquals("退款", repo.dao.entries().single().note)
    }
    @Test fun refundNotesForImportManualEditAndOlderRecords() = runBlocking {
        val row = ImportRow("支付宝", 2680, Kind.REFUND, System.currentTimeMillis(), "合成商户", "退货差价", "REFUND001", "bill:refund-note")
        repo.commit(repo.preview(listOf(row)), emptyMap())
        val imported = repo.dao.entries().single()
        assertEquals("退款 · 退货差价", imported.note)
        repo.save(imported.copy(note = "补充说明"), true)
        assertEquals("退款 · 补充说明", repo.dao.entries().single().note)
        repo.commit(repo.preview(listOf(row)), emptyMap())
        assertEquals("退款 · 补充说明", repo.dao.entries().single().note)
        // Simulate records written by older versions, then restart initialization twice.
        repo.dao.update(imported.copy(note = "旧备注"))
        repo.init(); repo.init()
        assertEquals("退款 · 旧备注", repo.dao.entries().single().note)
        val oldBackup = Backups.decode(Backups.encode(repo, "系统")).let { it.copy(entries = it.entries.map { entry -> entry.copy(note = "备份备注") }) }
        Backups.restore(repo, oldBackup)
        assertEquals("退款 · 备份备注", repo.dao.entries().single().note)
        assertEquals(imported.id, repo.dao.entries().single().id)
        val income = Entry(amount = 100, kind = Kind.INCOME, platform = "现金", time = System.currentTimeMillis(), note = "工资")
        repo.save(income, false)
        assertEquals("工资", repo.dao.entries().find { it.id == income.id }!!.note)
    }
    @Test fun notificationReplayAndSeparatePayments() = runBlocking {
        val parsed = AlipayParser().parse("支付宝 支付成功 ¥12.34")
        repo.capture("notification:a", "支付宝", "synthetic", System.currentTimeMillis(), parsed)
        repo.capture("notification:a", "支付宝", "updated", System.currentTimeMillis(), parsed)
        repo.capture("notification:b", "支付宝", "synthetic", System.currentTimeMillis(), parsed)
        assertEquals(2, repo.dao.entries().size)
        repo.delete(repo.dao.entries().first())
        repo.capture("notification:a", "支付宝", "replay", System.currentTimeMillis(), parsed)
        assertEquals(1, repo.dao.entries().size)
    }
    @Test fun notificationUpdateUsesStableSlot() = runBlocking {
        val parsed = AlipayParser().parse("支付宝 支付成功 ¥12.34")
        repo.captureNotification("os-key", "支付宝", "synthetic", System.currentTimeMillis(), parsed)
        repo.captureNotification("os-key", "支付宝", "update", System.currentTimeMillis() + 1000, parsed)
        assertEquals(1, repo.dao.entries().size)
        repo.dao.removeSlot("os-key")
        repo.captureNotification("os-key", "支付宝", "new transaction", System.currentTimeMillis() + 2000, parsed)
        assertEquals(2, repo.dao.entries().size)
    }
    @Test fun xlsxInlineStrings() {
        val table = listOf(listOf("交易时间", "交易对方", "商品", "收/支", "金额(元)", "当前状态", "交易单号"), listOf("2026-11-01 12:30:00", "合成商户", "午餐", "支出", "12.34", "支付成功", "WX001"))
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet><sheetData>" + table.mapIndexed { ri, row ->
            "<row>" + row.mapIndexed { ci, text -> "<c r=\"${'A' + ci}${ri + 1}\" t=\"inlineStr\"><is><t>$text</t></is></c>" }.joinToString("") + "</row>"
        }.joinToString("") + "</sheetData></worksheet>"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { it.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml")); it.write(xml.toByteArray()); it.closeEntry() }
        assertEquals(1234L, BillFiles.read(out.toByteArray()).rows.single().amount)
    }
    @Test fun repeatedBillAndNotificationMergePreserveEdits() = runBlocking {
        val e = Entry(amount = 1234, kind = Kind.EXPENSE, platform = "微信", time = parseTime("2026-11-01 12:00:00"), category = "餐饮", note = "我的备注", timeEstimated = true)
        repo.save(e, false)
        val row = ImportRow("微信", 1234, Kind.EXPENSE, e.time, "合成商户", "账单备注", "ORDER001", "bill:1")
        var matches = repo.preview(listOf(row)); assertEquals(1, matches.first().candidates.size)
        repo.commit(matches, mapOf("bill:1" to e.id))
        matches = repo.preview(listOf(row)); repo.commit(matches, emptyMap())
        assertEquals(1, repo.dao.entries().size)
        assertEquals("我的备注", repo.dao.entries().first().note)
        assertEquals("餐饮", repo.dao.entries().first().category)
    }
    @Test fun importRollsBackOnConflictingOrder() = runBlocking {
        repo.save(Entry(amount = 100, kind = Kind.EXPENSE, platform = "微信", time = 1000, orderId = "ORDER1"), false)
        val rows = listOf(ImportRow("微信", 200, Kind.EXPENSE, 2000, "", "", "ORDER2", "bill:new"), ImportRow("微信", 999, Kind.EXPENSE, 1000, "", "", "ORDER1", "bill:conflict"))
        assertTrue(runCatching { repo.commit(repo.preview(rows), emptyMap()) }.isFailure)
        assertEquals(1, repo.dao.entries().size)
    }
    @Test fun backupRoundTripAndInvalidReferences() = runBlocking {
        repo.save(Entry(amount = 500, kind = Kind.INCOME, platform = "现金", time = 1000), false)
        repo.dao.slot(NotificationSlot("os-key", "notification:test"))
        val text = Backups.encode(repo, "深色"); val backup = Backups.decode(text)
        Backups.restore(repo, backup)
        assertEquals("notification:test", repo.dao.slot("os-key")?.eventKey)
        assertEquals(500L, repo.dao.entries().single().amount)
        assertTrue(runCatching { Backups.decode(text.replace("\"version\": 1", "\"version\": 99")) }.isFailure)
        assertTrue(runCatching { Backups.decode(text.replace("\"category\": \"其他\"", "\"category\": \"不存在\"")) }.isFailure)
        assertEquals(1, repo.dao.entries().size)
    }
    @Test fun pruneAndCategoryMigration() = runBlocking {
        val e = Entry(amount = 100, kind = Kind.EXPENSE, platform = "现金", time = 1000, category = "餐饮")
        repo.save(e, false); repo.renameCategory("餐饮", "三餐")
        assertEquals("三餐", repo.dao.entries().single().category)
        repo.dao.event(Event("old", "微信", 1000, "private", true, "synthetic")); repo.prune()
        assertTrue(repo.dao.events().isEmpty()); assertEquals(1, repo.dao.entries().size)
    }
}
