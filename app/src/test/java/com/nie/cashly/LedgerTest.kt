package com.nie.cashly

import com.nie.cashly.core.*
import org.junit.Assert.*
import org.junit.Test

/** All notification and bill examples in these tests are SYNTHETIC, not real app samples. */
class LedgerTest {
    @Test fun exactMoney() {
        assertEquals(123456L, cents("¥1,234.56")); assertEquals("0.01", money(1))
        listOf("0", "-1", "0.001", "1e5", "abc", "1,23", "999999999999999999999").forEach { assertTrue(runCatching { cents(it) }.isFailure) }
    }
    @Test fun transactionTypes() {
        assertEquals(Kind.EXPENSE, WeChatParser().parse("微信支付\n支付成功 ¥12.34")?.kind)
        assertEquals(Kind.INCOME, WeChatParser().parse("微信收款\n收款到账 10.00元")?.kind)
        assertEquals(Kind.EXPENSE, AlipayParser().parse("支付宝\n支付成功 15.00元")?.kind)
        assertEquals(Kind.REFUND, AlipayParser().parse("支付宝\n退款到账 ¥10.00")?.kind)
        assertEquals(Kind.TRANSFER, AlipayParser().parse("支付宝\n本人账户互转 转账成功 ¥10.00")?.kind)
    }
    @Test fun uncertainNotificationsNeverAutoPost() {
        assertEquals(123456L, AlipayParser().parse("支付宝 支付成功 ¥1,234.56")!!.amount)
        assertFalse(AlipayParser().parse("支付宝 支付成功 ¥1.234")!!.certain)
        assertFalse(WeChatParser().parse("微信支付\n支付成功，金额已隐藏")!!.certain)
        assertFalse(AlipayParser().parse("支付宝\n¥20.00")!!.certain)
        assertFalse(AlipayParser().parse("支付宝\n支付成功 ¥20.00 余额¥100.00")!!.certain)
        assertNull(WeChatParser().parse("今晚吃饭吗"))
        assertNull(AlipayParser().parse("支付宝 待支付 ¥20.00"))
        assertNull(WeChatParser().parse("微信支付 支付失败 ¥20.00"))
        assertNull(AlipayParser().parse("支付宝 优惠券 ¥20.00"))
    }
    @Test fun refundsAndTransfers() {
        val t = totals(listOf(Kind.INCOME to 10000L, Kind.EXPENSE to 5000L, Kind.REFUND to 1000L, Kind.TRANSFER to 90000L))
        assertEquals(10000L, t.income); assertEquals(4000L, t.expense); assertEquals(6000L, t.balance)
    }
    @Test fun csvQuotedFieldsAndEncodingMarkers() {
        assertEquals(listOf(listOf("a,b", "a\"b", "c\nd")), Csv.read("\"a,b\",\"a\"\"b\",\"c\nd\""))
        assertTrue(runCatching { Csv.read("\"unfinished") }.isFailure)
    }
    private val header = "交易时间,交易对方,商品,收/支,金额(元),当前状态,交易单号"
    @Test fun billPreviewAndErrors() {
        val result = BillParser.parse(Csv.read("说明行\n$header\n2026-11-01 12:30:00,合成商户,午餐,支出,12.34,支付成功,WX001\n2026-11-01 13:30:00,合成商户,退款,收入,2.34,退款成功,WX002\n2026-11-01 14:30:00,合成商户,商品,支出,5.00,交易关闭,WX003\n时间错误,商户,商品,支出,1.00,支付成功,WX004"))
        assertEquals(2, result.rows.size); assertEquals(1, result.skipped); assertEquals(1, result.errors.size)
        assertEquals(Kind.REFUND, result.rows[1].kind)
        assertEquals("微信", result.rows[0].platform)
    }
    @Test fun stableImportKeysAndDistinctTransactions() {
        val rows = Csv.read("$header\n2026-11-01 12:30:00,合成商户,午餐,支出,12.34,支付成功,WX001\n2026-11-01 12:30:01,合成商户,午餐,支出,12.34,支付成功,WX002")
        val a = BillParser.parse(rows); val b = BillParser.parse(rows)
        assertEquals(a.rows.map { it.sourceKey }, b.rows.map { it.sourceKey })
        assertNotEquals(a.rows[0].sourceKey, a.rows[1].sourceKey)
        assertTrue(runCatching { BillParser.parse(Csv.read("unknown,headers")) }.isFailure)
        val identical = BillParser.parse(Csv.read("$header\n2026-11-01 12:30:00,合成商户,午餐,支出,12.34,支付成功,\n2026-11-01 12:30:00,合成商户,午餐,支出,12.34,支付成功,"))
        assertNotEquals(identical.rows[0].sourceKey, identical.rows[1].sourceKey)
    }
    @Test fun originalRefundedExpenseMustNotBecomeAnotherRefund() {
        val result = BillParser.parse(Csv.read("$header\n2026-11-01 12:30:00,合成商户,午餐,支出,12.34,已退款,WX001"))
        assertTrue(result.rows.isEmpty()); assertEquals(1, result.errors.size)
        val partial = BillParser.parse(Csv.read("$header,成功退款（元）\n2026-11-01 12:30:00,合成商户,午餐,支出,12.34,支付成功,WX001,2.00"))
        assertTrue(partial.rows.isEmpty()); assertEquals(1, partial.errors.size)
    }
    @Test fun localTimeRoundTripAndMonth() {
        val value = parseTime("2026-10-31 23:59:59")
        assertEquals("2026-10-31 23:59:59", formatTime(value))
        assertTrue(parseTime("2026-11-01 00:00:00") > value)
        assertTrue(runCatching { parseTime("2026-02-30 12:00:00") }.isFailure)
    }
}
