package com.nie.cashly.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter

enum class Kind(val label: String) { EXPENSE("支出"), INCOME("收入"), REFUND("退款"), TRANSFER("内部转账") }
val defaultCategories = listOf("餐饮", "交通", "购物", "住房", "娱乐", "医疗", "学习", "工资", "奖金", "其他")
fun cents(text: String): Long {
    val input = text.trim().removePrefix("￥").removePrefix("¥").removeSuffix("元").trim()
    require(Regex("(?:\\d+|\\d{1,3}(?:,\\d{3})+)(\\.\\d{1,2})?").matches(input)) { "请输入正数金额，最多两位小数" }
    val value = input.replace(",", "")
    return BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().also { require(it > 0) { "金额必须大于零" } }
}
fun money(value: Long): String = BigDecimal.valueOf(value, 2).toPlainString()
fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
fun categoryFor(text: String): String = when {
    listOf("餐", "饭", "咖啡", "外卖", "奶茶").any(text::contains) -> "餐饮"
    listOf("地铁", "公交", "打车", "滴滴", "车票").any(text::contains) -> "交通"
    listOf("超市", "购物", "淘宝", "京东").any(text::contains) -> "购物"
    listOf("房租", "水费", "电费").any(text::contains) -> "住房"
    text.contains("工资") -> "工资"
    text.contains("奖金") -> "奖金"
    else -> "其他"
}
fun parseTime(text: String): Long {
    val cleaned = text.trim().replace('/', '-').replace('T', ' ')
    return LocalDateTime.parse(cleaned, DateTimeFormatter.ofPattern(if (cleaned.length == 16) "uuuu-MM-dd HH:mm" else "uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
fun formatTime(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
data class Totals(val income: Long, val expense: Long) { val balance get() = income - expense }
fun totals(rows: List<Pair<Kind, Long>>) = Totals(rows.filter { it.first == Kind.INCOME }.sumOf { it.second },
    rows.sumOf { when(it.first) { Kind.EXPENSE -> it.second; Kind.REFUND -> -it.second; else -> 0L } })

data class Parsed(val amount: Long?, val kind: Kind?, val party: String, val order: String?, val evidence: String, val certain: Boolean)
interface PaymentParser { fun parse(text: String): Parsed? }
class WeChatParser : PaymentParser { override fun parse(text: String) = parsePayment(text, true) }
class AlipayParser : PaymentParser { override fun parse(text: String) = parsePayment(text, false) }
private fun parsePayment(text: String, wechat: Boolean): Parsed? {
    if (listOf("交易失败", "支付失败", "待支付", "等待付款", "付款请求", "请付款", "优惠券", "立减", "红包封面").any(text::contains)) return null
    // Restrict to transaction-style templates. Ordinary chats are never considered proof of payment.
    val triggers = if (wechat) listOf("微信支付", "收款到账", "微信收款", "支付成功", "转账到账", "退款到账", "转账成功")
        else listOf("支付宝", "支付成功", "收款到账", "到账提醒", "退款成功", "退款到账", "转账成功")
    if (triggers.none(text::contains)) return null
    val numeric = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?"
    val amount = Regex("(?:[¥￥]\\s*($numeric)(?![\\d.,])|(?<![\\d.,])($numeric)\\s*元)")
        .findAll(text).mapNotNull { m -> runCatching { cents(m.groupValues[1].ifEmpty { m.groupValues[2] }) }.getOrNull() }.toList()
    val kind = when {
        text.contains("本人账户") && listOf("互转", "转入", "转出").any(text::contains) -> Kind.TRANSFER
        listOf("退款到账", "退款成功").any(text::contains) -> Kind.REFUND
        listOf("收款到账", "微信收款", "转账到账", "收入", "收到转账").any(text::contains) -> Kind.INCOME
        listOf("支付成功", "付款成功", "支出", "转账成功").any(text::contains) -> Kind.EXPENSE
        else -> null
    }
    val success = listOf("支付成功", "付款成功", "收款到账", "微信收款", "转账到账", "退款到账", "退款成功", "转账成功").any(text::contains)
    val order = Regex("(?:交易号|订单号|商户单号)[：:\\s]+([A-Za-z0-9_-]{6,})").find(text)?.groupValues?.get(1)
    val party = Regex("(?:商户|交易对象|收款方|付款方)[：:]\\s*([^\\n，,]+)").find(text)?.groupValues?.get(1)?.trim().orEmpty()
    val certain = success && amount.size == 1 && kind != null
    return Parsed(amount.singleOrNull(), kind, party, order, if (certain) "成功提示、唯一金额、明确收支方向；通知模板待真机验证" else "交易信息不足，请核对金额、方向与状态", certain)
}

data class ImportRow(val platform: String, val amount: Long, val kind: Kind, val time: Long, val party: String, val note: String, val order: String?, val sourceKey: String)
data class ImportPreview(val rows: List<ImportRow>, val errors: List<String>, val skipped: Int)

object Csv {
    fun read(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> { require(quoted || cell.isEmpty()) { "CSV 引号格式错误" }; quoted = !quoted }
                c == ',' && !quoted -> { row.add(cell.toString()); cell.clear() }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear(); rows.add(row.toList()); row.clear()
                }
                else -> cell.append(c)
            }; i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row.toList()) }
        return rows
    }
    fun cell(text: String) = "\"" + text.replace("\"", "\"\"") + "\""
}

object BillParser {
    fun parse(table: List<List<String>>): ImportPreview {
        fun clean(s: String) = s.trim().removePrefix("\uFEFF")
        val headerIndex = table.indexOfFirst { row -> row.any { clean(it) in listOf("交易时间", "交易创建时间") } && row.any { clean(it).startsWith("金额") } }
        require(headerIndex >= 0) { "未知账单表头：需要交易时间、金额、收/支和交易状态。真实导出格式尚待样本验证" }
        val header = table[headerIndex].map(::clean)
        fun index(vararg names: String) = header.indexOfFirst { h -> names.any { h == it || (it == "金额" && h.startsWith(it)) } }
        val ti = index("交易时间", "交易创建时间"); val ai = index("金额"); val ki = index("收/支", "收支"); val si = index("交易状态", "当前状态"); val pi = index("交易对方"); val oi = index("交易单号", "交易号"); val ni = index("商品", "商品名称", "商品说明")
        require(listOf(ti,ai,ki,si).all { it >= 0 }) { "缺少时间、金额、收/支或状态列，不能安全导入" }
        val isWechat = header.contains("交易单号"); val platform = if (isWechat) "微信" else "支付宝"
        val rows = mutableListOf<ImportRow>(); val errors = mutableListOf<String>(); var skipped = 0
        val occurrences = mutableMapOf<String, Int>()
        table.drop(headerIndex + 1).forEachIndexed { n, row ->
            fun get(i: Int) = row.getOrNull(i)?.trim()?.removeSuffix("\t").orEmpty()
            if (row.all { it.isBlank() } || row.firstOrNull()?.trim()?.startsWith("---") == true) return@forEachIndexed
            runCatching {
                val status = get(si)
                if (listOf("关闭", "失败", "待付款", "待支付").any(status::contains)) { skipped++; return@forEachIndexed }
                require(status in listOf("支付成功", "交易成功", "已收钱", "收款成功", "退款成功", "已退款", "退款", "转账成功")) { "未支持的交易状态" }
                header.indices.filter { header[it].contains("退款") && (header[it].contains("金额") || header[it].startsWith("成功退款")) }.forEach { index ->
                    val value = get(index).removePrefix("¥").removePrefix("￥").replace(",", "").trim()
                    require(value in listOf("", "-", "/") || value.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) == 0) { "含退款汇总字段，请使用独立退款明细或手动核对" }
                }
                val kind = when {
                    status in listOf("退款成功", "已退款", "退款") -> { require(get(ki) == "收入") { "退款状态可能属于原支出，请核对独立退款明细" }; Kind.REFUND }
                    get(ki) == "支出" -> Kind.EXPENSE
                    get(ki) == "收入" -> Kind.INCOME
                    else -> throw IllegalArgumentException("收支类型不明确，请手动核对")
                }
                val order = get(oi).takeIf { it.isNotBlank() && it != "/" && it != "-" }
                val canonical = row.joinToString("\u001f") { clean(it) }
                val occurrence = occurrences.getOrDefault(canonical, 0); occurrences[canonical] = occurrence + 1
                rows.add(ImportRow(platform, cents(get(ai)), kind, parseTime(get(ti)), get(pi), get(ni), order, "bill:" + hash(platform + canonical + "#" + occurrence)))
            }.onFailure { errors.add("第 ${headerIndex + n + 2} 行：${it.message}") }
        }
        return ImportPreview(rows, errors, skipped)
    }
}
