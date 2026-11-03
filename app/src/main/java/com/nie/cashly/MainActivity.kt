package com.nie.cashly

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.nie.cashly.core.*
import com.nie.cashly.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { CashlyApp() }
    }
}

class CashlyViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    val entries = repo.dao.observeEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pending = repo.dao.observePending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories = repo.dao.observeCategories().map { it.map(Category::name) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultCategories)
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())
    var busy by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var preview by mutableStateOf<ImportPreview?>(null)
    var matches by mutableStateOf<List<ImportMatch>>(emptyList())
    var backup by mutableStateOf<Backup?>(null)
    init { work { repo.init(); ready = true } }
    fun work(success: String? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch { try { withContext(Dispatchers.IO) { block() }; if (success != null) message = success }
            catch (e: Exception) { message = e.message ?: "操作失败，请重试" } finally { busy = false } }
    }
    fun importFile(uri: android.net.Uri) = work {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readNBytes(24 * 1024 * 1024 + 1) } ?: error("无法读取文件")
        val result = BillFiles.read(bytes); val match = repo.preview(result.rows)
        withContext(Dispatchers.Main) { preview = result; matches = match }
    }
    fun readBackup(uri: android.net.Uri) = work {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readNBytes(24 * 1024 * 1024 + 1) } ?: error("无法读取备份")
        require(bytes.size <= 24 * 1024 * 1024) { "备份过大" }
        val result = Backups.decode(bytes.toString(Charsets.UTF_8)); withContext(Dispatchers.Main) { backup = result }
    }
    fun export(uri: android.net.Uri, json: Boolean) = work("文件已保存") {
        val text = if (json) Backups.encode(repo, settings.value.theme) else Backups.csv(repo.dao.entries())
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("无法写入文件")
    }
}

private val Teal = Color(0xFF28786A)
@Composable
fun CashlyApp(vm: CashlyViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dark = settings.theme == "深色" || (settings.theme == "系统" && isSystemInDarkTheme())
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF8CD3BF), onPrimary = Color(0xFF123A30), primaryContainer = Color(0xFF254D42), onPrimaryContainer = Color(0xFFDBF4E8),
        secondaryContainer = Color(0xFF324B42), onSecondaryContainer = Color(0xFFDBF4E8), tertiary = Color(0xFFE2AF8C), surfaceTint = Color(0xFF8CD3BF), surfaceContainer = Color(0xFF1D2824), background = Color(0xFF131B19), surface = Color(0xFF1D2824))
        else lightColorScheme(primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFE0EFE7), onPrimaryContainer = Color(0xFF163E34),
            secondary = Color(0xFF4C635B), secondaryContainer = Color(0xFFE0EFE7), onSecondaryContainer = Color(0xFF163E34), tertiary = Color(0xFFA96840), surfaceTint = Teal,
            surfaceContainer = Color(0xFFEDF2EB), background = Color(0xFFF6F7F2), surface = Color(0xFFFFFFFF))
    MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) { CashlyContent(vm) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashlyContent(vm: CashlyViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle(); val pending by vm.pending.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle(); val settings by vm.settings.collectAsStateWithLifecycle()
    val listenerConnected by com.nie.cashly.capture.CashlyListener.connectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current; val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState(); val route = current?.destination?.route ?: "首页"
    var editor by remember { mutableStateOf<Entry?>(null) }; var adding by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<Event?>(null) }; var showPending by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }; var permissionIntro by remember { mutableStateOf(false) }
    var exportWarning by remember { mutableStateOf(false) }; var authorized by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }; var categoryFilter by remember { mutableStateOf("全部") }
    val snack = remember { SnackbarHostState() }
    LifecycleResumeEffect(Unit) {
        authorized = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        vm.work { vm.repo.prune() }
        onPauseOrDispose { }
    }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFile) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::readBackup) }
    val backupWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { uri -> vm.export(uri, true) } }
    val csvWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { uri -> vm.export(uri, false) } }
    val monthRows = entries.filter { YearMonth.from(Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault())) == month }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = { Column { Text(if (route == "首页") "Cashly" else route, fontWeight = FontWeight.Bold); Text("把每一笔，过成心中有数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { listOf("首页" to Icons.Outlined.Home, "账单" to Icons.Outlined.ReceiptLong, "统计" to Icons.Outlined.BarChart, "我的" to Icons.Outlined.PersonOutline).forEach { (name, icon) ->
            NavigationBarItem(selected = route == name, onClick = { nav.navigate(name) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, null) }, label = { Text(name) })
        } } },
        floatingActionButton = { if (!vm.busy) ExtendedFloatingActionButton(onClick = { editor = null; event = null; adding = true }, modifier = Modifier.semantics { contentDescription = "记一笔" }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("记一笔") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.ready) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (vm.busy) "正在打开账本…" else "账本加载失败，请重新启动应用") }; return@Column }
            NavHost(nav, startDestination = "首页", modifier = Modifier.weight(1f)) {
                composable("首页") { Page {
                    item { MonthSelector(month) { month = it } }
                    item { Summary(monthRows) }
                    item { Panel {
                        Text("自动记账", fontWeight = FontWeight.Bold)
                        Text(if (!authorized) "未开启通知访问" else if (listenerConnected) "已授权 · 服务已连接" else "已授权 · 连接待确认")
                        Text(if (settings.last == 0L) "尚未接收支持应用的通知" else "最近接收：${formatTime(settings.last)}", style = MaterialTheme.typography.bodySmall)
                        Text("仅记录可读取的交易通知；缺漏请导入账单补齐。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { permissionIntro = true }) { Text(if (authorized) "查看授权说明" else "开启自动记账") }
                        if (pending.isNotEmpty()) TextButton(onClick = { showPending = true }) { Text("${pending.size} 笔待确认 →") }
                    } }
                    item { SectionTitle("近期账目", "${monthRows.size} 笔") }
                    if (monthRows.isEmpty()) item { EmptyState("这个月还没有账目", "点击「记一笔」，或在「我的」中导入账单。") }
                    items(monthRows.take(8), key = { it.id }) { entry -> EntryRow(entry) { editor = entry; adding = true } }
                } }
                composable("账单") {
                    var query by remember { mutableStateOf("") }; var platform by remember { mutableStateOf("全部") }; var kind by remember { mutableStateOf("全部") }
                    val filtered = monthRows.filter { (platform == "全部" || it.platform == platform) && (kind == "全部" || it.kind.label == kind) && (categoryFilter == "全部" || it.category == categoryFilter) && (it.party + it.note).contains(query, ignoreCase = true) }
                    Page {
                        item { MonthSelector(month) { month = it } }
                        item { OutlinedTextField(query, { query = it }, label = { Text("搜索交易对象、备注") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Selector(platform, listOf("全部", "微信", "支付宝", "现金", "银行卡", "其他"), { platform = it }, Modifier.weight(1f)); Selector(kind, listOf("全部") + Kind.entries.map { it.label }, { kind = it }, Modifier.weight(1f)); Selector(categoryFilter, listOf("全部") + categories, { categoryFilter = it }, Modifier.weight(1f)) } }
                        if (filtered.isEmpty()) item { EmptyState("暂无符合条件的账目", "试试更换月份或筛选条件。") }
                        filtered.groupBy { formatTime(it.time).take(10) }.forEach { (date, rows) ->
                            item { SectionTitle(date, "${rows.size} 笔") }; items(rows, key = { it.id }) { e -> EntryRow(e) { editor = e; adding = true } }
                        }
                    }
                }
                composable("统计") { Page {
                    item { MonthSelector(month) { month = it } }; item { Summary(monthRows) }
                    item { SectionTitle("支出分类", "退款抵减支出") }
                    val spending = monthRows.filter { it.kind == Kind.EXPENSE || it.kind == Kind.REFUND }.groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { if (it.kind == Kind.REFUND) -it.amount else it.amount } }.toList().sortedByDescending { it.second }
                    if (spending.isEmpty()) item { EmptyState("还没有统计数据", "记下第一笔支出后，就能看到分类分布。") }
                    items(spending) { (category, value) -> Panel(onClick = { categoryFilter = category; nav.navigate("账单") { launchSingleTop = true } }) {
                        SectionTitle(category, "¥${money(value)}")
                        LinearProgressIndicator(progress = { (value.coerceAtLeast(0).toFloat() / spending.sumOf { it.second.coerceAtLeast(0) }.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } }
                    item { SectionTitle("每日趋势", "收入 / 净支出") }
                    item { DailyChart(monthRows, month) }
                } }
                composable("我的") { Page {
                    item { Panel { Text("本机账本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("离线使用 · 无需账号 · 数据由你掌握") } }
                    item { ActionRow("通知访问", if (authorized) "已授权，查看设置" else "开启后可识别交易通知") { permissionIntro = true } }
                    item { ActionRow("待确认记录", "${pending.size} 笔等待处理") { showPending = true } }
                    item { ActionRow("分类管理", "新增、重命名与迁移分类") { showCategories = true } }
                    item { ActionRow("导入账单", "CSV / XLSX · 真实导出格式待样本验证") { importPicker.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/vnd.ms-excel")) } }
                    item { ActionRow("导出 CSV", "用于查看，不用于恢复") { csvWriter.launch("Cashly-${LocalDate.now()}.csv") } }
                    item { ActionRow("完整备份", "JSON · 包含个人财务数据") { exportWarning = true } }
                    item { ActionRow("恢复备份", "校验后确认覆盖当前账本") { restorePicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) } }
                    item { Panel { Text("外观", fontWeight = FontWeight.Bold); Choices(listOf("系统", "浅色", "深色"), settings.theme) { value -> vm.work { vm.repo.theme(value) } } } }
                    item { ActionRow("清除原始通知文本", "最长保留 30 天，清除不影响账目") { vm.work("原始通知文本已清除") { vm.repo.dao.clearRaw() } } }
                    item { Text("通知无法覆盖所有交易。建议每月导入账单核对。\n账本不参与系统云备份，请主动保存备份文件。", style = MaterialTheme.typography.bodySmall) }
                } }
            }
        }
    }
    if (permissionIntro) AlertDialog(onDismissRequest = { permissionIntro = false }, title = { Text("开启通知自动记账") }, text = { Text("系统授权允许读取通知，Cashly 仅处理微信和支付宝的交易通知，在本机解析金额和收支。不会上传通知。\n\n无通知、内容隐藏或应用被强制停止时可能漏记，可通过账单导入补齐。你可以随时撤销权限。") }, confirmButton = { TextButton(onClick = { permissionIntro = false; context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("前往系统设置") } }, dismissButton = { TextButton(onClick = { permissionIntro = false }) { Text("暂不开启") } })
    if (exportWarning) AlertDialog(onDismissRequest = { exportWarning = false }, title = { Text("保存明文备份") }, text = { Text("备份包含个人财务数据，请保存到你信任的位置。原始通知文本不会包含在备份中。") }, confirmButton = { TextButton(onClick = { exportWarning = false; backupWriter.launch("Cashly-${LocalDate.now()}.json") }) { Text("选择保存位置") } }, dismissButton = { TextButton(onClick = { exportWarning = false }) { Text("取消") } })
    vm.backup?.let { backup -> AlertDialog(onDismissRequest = { if (!vm.busy) vm.backup = null }, title = { Text("覆盖当前账本？") }, text = { Text("备份已通过校验，包含 ${backup.entries.size} 笔账目、${backup.categories.size} 个分类。恢复将覆盖当前账本，建议先备份现有数据。") }, confirmButton = { TextButton(enabled = !vm.busy, onClick = { vm.work("账本已恢复") { Backups.restore(vm.repo, backup); withContext(Dispatchers.Main) { vm.backup = null } } }) { Text("确认覆盖") } }, dismissButton = { TextButton(enabled = !vm.busy, onClick = { vm.backup = null }) { Text("取消") } }) }
    if (showCategories) CategoryDialog(vm, categories) { showCategories = false }
    if (showPending) AlertDialog(onDismissRequest = { showPending = false }, title = { Text("待确认记录") }, text = { LazyColumn {
        if (pending.isEmpty()) item { Text("没有待确认记录") }
        items(pending, key = { it.key }) { e -> Column(Modifier.padding(vertical = 8.dp)) {
            Text("${e.platform} · ${formatTime(e.received)}", fontWeight = FontWeight.Bold)
            Text(e.raw.ifBlank { "原始文本已清除，请核对后填写" }, maxLines = 5)
            Row { TextButton(onClick = { event = e; editor = null; adding = true; showPending = false }) { Text("确认并填写") }; TextButton(onClick = { vm.work { vm.repo.dao.resolve(e.key) } }) { Text("忽略") } }
        } }
    } }, confirmButton = { TextButton(onClick = { showPending = false }) { Text("关闭") } })
    if (adding) EntryEditor(vm, editor, event, categories) { adding = false; event = null; editor = null }
    if (vm.preview != null) ImportDialog(vm)
}

@Composable private fun Page(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}
@Composable private fun Panel(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
@Composable private fun SectionTitle(title: String, subtitle: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun EmptyState(title: String, hint: String) { Panel { Icon(Icons.Outlined.AccountBalanceWallet, null, tint = MaterialTheme.colorScheme.primary); Text(title, fontWeight = FontWeight.SemiBold); Text(hint, style = MaterialTheme.typography.bodyMedium) } }
@Composable private fun ActionRow(title: String, subtitle: String, action: () -> Unit) { Panel(onClick = action) { SectionTitle(title, "›"); Text(subtitle, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun MonthSelector(month: YearMonth, change: (YearMonth) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { change(month.minusMonths(1)) }) { Icon(Icons.Outlined.ChevronLeft, "上个月") }; Text("${month.year} 年 ${month.monthValue} 月", fontWeight = FontWeight.SemiBold); IconButton(onClick = { change(month.plusMonths(1)) }) { Icon(Icons.Outlined.ChevronRight, "下个月") } } }
@Composable private fun Summary(rows: List<Entry>) {
    val total = totals(rows.map { it.kind to it.amount })
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(26.dp)) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("本月净支出", style = MaterialTheme.typography.labelLarge)
        Text("¥ ${money(total.expense)}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("收入 ＋", style = MaterialTheme.typography.labelMedium); Text("¥ ${money(total.income)}") }; Column { Text("结余", style = MaterialTheme.typography.labelMedium); Text("¥ ${money(total.balance)}") } }
    } }
}
@Composable private fun EntryRow(entry: Entry, click: () -> Unit) { Panel(onClick = click) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Icon(if (entry.kind == Kind.INCOME) Icons.Outlined.SouthWest else if (entry.kind == Kind.REFUND) Icons.Outlined.Undo else if (entry.kind == Kind.TRANSFER) Icons.Outlined.SwapHoriz else Icons.Outlined.NorthEast, null, Modifier.padding(10.dp)) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(entry.party.ifBlank { entry.category }, fontWeight = FontWeight.SemiBold); Text("${entry.platform} · ${entry.category} · ${entry.kind.label}", style = MaterialTheme.typography.labelSmall) }
        Text((when (entry.kind) { Kind.INCOME, Kind.REFUND -> "+"; Kind.EXPENSE -> "−"; Kind.TRANSFER -> "↔" }) + money(entry.amount), fontWeight = FontWeight.Bold)
    }
} }
@Composable private fun Choices(values: List<String>, selected: String, select: (String) -> Unit) { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { FilterChip(selected = it == selected, onClick = { select(it) }, label = { Text(it) }) } } }
@Composable private fun Selector(value: String, values: List<String>, select: (String) -> Unit, modifier: Modifier = Modifier) { var expanded by remember { mutableStateOf(false) }; Box(modifier) { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(value, maxLines = 1) }; DropdownMenu(expanded, { expanded = false }) { values.distinct().forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { select(item); expanded = false }) } } } }

@Composable private fun DailyChart(rows: List<Entry>, month: YearMonth) {
    val days = (1..month.lengthOfMonth()).map { day -> totals(rows.filter { Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).dayOfMonth == day }.map { it.kind to it.amount }) }
    val max = days.maxOfOrNull { maxOf(it.income, kotlin.math.abs(it.expense)) }?.coerceAtLeast(1) ?: 1L
    val incomeColor = MaterialTheme.colorScheme.primary; val expenseColor = MaterialTheme.colorScheme.tertiary
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("● 收入", color = incomeColor); Text("● 净支出", color = expenseColor) }
        Canvas(Modifier.fillMaxWidth().height(140.dp).semanticsChart()) {
            val step = size.width / days.size; val baseline = size.height * .8f
            drawLine(Color.Gray, androidx.compose.ui.geometry.Offset(0f, baseline), androidx.compose.ui.geometry.Offset(size.width, baseline))
            days.forEachIndexed { i, value ->
                val x = i * step; val a = value.income.toFloat() / max * baseline; val b = value.expense.toFloat() / max * baseline
                drawLine(incomeColor, androidx.compose.ui.geometry.Offset(x + step * .3f, baseline), androidx.compose.ui.geometry.Offset(x + step * .3f, baseline - a), step * .28f)
                drawLine(expenseColor, androidx.compose.ui.geometry.Offset(x + step * .7f, baseline), androidx.compose.ui.geometry.Offset(x + step * .7f, baseline - b.coerceAtLeast(-size.height * .2f)), step * .28f)
            }
        }
        Text("1 日 → ${month.lengthOfMonth()} 日 · 退款可能使净支出低于零", style = MaterialTheme.typography.labelSmall)
        var day by remember(month) { mutableStateOf("1") }
        Selector("$day 日", (1..month.lengthOfMonth()).map { "$it 日" }, { day = it.substringBefore(" ") })
        val total = days[day.toInt() - 1]; Text("收入 ¥${money(total.income)} · 净支出 ¥${money(total.expense)}")
    }
}
private fun Modifier.semanticsChart(): Modifier = semantics { contentDescription = "每日收入和净支出趋势图，下方可选择日期查看具体数值" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditor(vm: CashlyViewModel, original: Entry?, event: Event?, categories: List<String>, close: () -> Unit) {
    var amount by remember { mutableStateOf(original?.amount?.let(::money) ?: event?.amount?.let(::money) ?: "") }
    var kind by remember { mutableStateOf(original?.kind ?: event?.kind ?: Kind.EXPENSE) }
    var platform by remember { mutableStateOf(original?.platform ?: event?.platform ?: "微信") }
    var category by remember { mutableStateOf(original?.category ?: "其他") }
    var time by remember { mutableStateOf(formatTime(original?.time ?: event?.received ?: System.currentTimeMillis())) }
    var party by remember { mutableStateOf(original?.party ?: event?.party ?: "") }
    var note by remember { mutableStateOf(original?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }; var deleteConfirm by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    LaunchedEffect(original?.id) { if (original != null) sources = withContext(Dispatchers.IO) { vm.repo.dao.sources().filter { it.entryId == original.id } } }
    ModalBottomSheet(onDismissRequest = { if (!vm.busy) close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (original == null) "记下这一笔" else "交易详情", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Choices(Kind.entries.map { it.label }, kind.label) { value -> kind = Kind.entries.first { it.label == value } }
            OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, prefix = { Text("¥ ") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Selector(platform, listOf("微信", "支付宝", "现金", "银行卡", "其他"), { platform = it }, Modifier.weight(1f)); Selector(category, categories, { category = it }, Modifier.weight(1f)) }
            OutlinedTextField(time, { time = it }, label = { Text("时间 yyyy-MM-dd HH:mm:ss") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(party, { party = it }, label = { Text("交易对象（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            if (kind == Kind.REFUND) Text(if (original?.refundOf == null) "未关联退款 · 统计时抵减支出" else "已关联原交易", style = MaterialTheme.typography.bodySmall)
            if (original?.timeEstimated == true || event != null) Text("通知时间为接收时间，请按实际交易核对。", style = MaterialTheme.typography.bodySmall)
            original?.orderId?.let { Text("平台交易号：$it", style = MaterialTheme.typography.bodySmall) }
            if (sources.isNotEmpty()) Text("来源：\n" + sources.joinToString("\n") { it.label }, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !vm.busy, onClick = {
                runCatching {
                    val value = cents(amount); val timestamp = parseTime(time)
                    require(timestamp > 0) { "交易时间无效" }
                    val entry = original?.copy(amount = value, kind = kind, platform = platform, category = category, time = timestamp, party = party.trim(), note = note.trim(), timeEstimated = false)
                        ?: Entry(amount = value, kind = kind, platform = platform, category = category, time = timestamp, party = party.trim(), note = note.trim(), orderId = event?.orderId)
                    vm.work("账目已保存") { vm.repo.save(entry, original != null, event?.key); withContext(Dispatchers.Main) { close() } }
                }.onFailure { error = it.message ?: "请检查填写内容" }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (vm.busy) "正在保存…" else "保存账目") }
            if (original != null) TextButton(enabled = !vm.busy, onClick = { deleteConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("删除这笔账目", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleteConfirm && original != null) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除这笔账目？") }, text = { Text("删除后统计会同步更新。") }, confirmButton = { TextButton(enabled = !vm.busy, onClick = { vm.work("账目已删除") { vm.repo.delete(original); withContext(Dispatchers.Main) { deleteConfirm = false; close() } } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
}

@Composable
private fun CategoryDialog(vm: CashlyViewModel, categories: List<String>, close: () -> Unit) {
    var selected by remember { mutableStateOf("新增分类") }; var name by remember { mutableStateOf("") }; var target by remember { mutableStateOf("其他") }
    AlertDialog(onDismissRequest = close, title = { Text("分类管理") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Selector(selected, listOf("新增分类") + categories, { selected = it; name = if (it == "新增分类") "" else it })
        OutlinedTextField(name, { name = it }, label = { Text("分类名称") }, singleLine = true)
        if (selected == "其他") Text("其他是系统兜底分类，保留用于未识别的交易。")
        Button(enabled = selected != "其他" && name.trim().isNotEmpty() && name.trim() != selected && !vm.busy, onClick = {
            val old = selected; val value = name.trim()
            vm.work("分类已保存") {
                require(value.length <= 20) { "分类名称最多 20 个字" }
                require(value !in vm.repo.dao.categories().map { it.name }) { "分类名称已存在" }
                if (old == "新增分类") vm.repo.dao.category(Category(value)) else vm.repo.renameCategory(old, value)
                withContext(Dispatchers.Main) { selected = "新增分类"; name = "" }
            }
        }) { Text(if (selected == "新增分类") "新增" else "重命名并更新账目") }
        if (selected != "新增分类" && selected != "其他") {
            Text("删除时，将此分类的账目迁移到：")
            Selector(target, categories.filter { it != selected }, { target = it })
            TextButton(enabled = !vm.busy && target != selected, onClick = { val old = selected; val dest = target; vm.work("分类已迁移并删除") { vm.repo.renameCategory(old, dest, true); withContext(Dispatchers.Main) { selected = "新增分类"; name = "" } } }) { Text("迁移并删除分类", color = MaterialTheme.colorScheme.error) }
        }
    } }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

@Composable
private fun ImportDialog(vm: CashlyViewModel) {
    val preview = vm.preview ?: return
    val choices = remember(preview) { mutableStateMapOf<String, String>() }
    val candidates = vm.matches.filter { it.candidates.isNotEmpty() }
    val exact = vm.matches.count { it.exact != null || it.repeated }
    val unresolved = candidates.any { it.row.sourceKey !in choices }
    AlertDialog(onDismissRequest = { if (!vm.busy) vm.preview = null }, title = { Text("导入预览") }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("有效 ${preview.rows.size} 笔 · 已记录/文件内重复 $exact 笔\n疑似重复 ${candidates.size} 笔 · 已跳过 ${preview.skipped} 笔\n异常 ${preview.errors.size} 行") }
        if (preview.rows.isNotEmpty()) item { Text("${formatTime(preview.rows.minOf { it.time })}\n至 ${formatTime(preview.rows.maxOf { it.time })}", style = MaterialTheme.typography.bodySmall) }
        if (preview.rows.isNotEmpty()) item { Text("明细预览（前 30 笔）", fontWeight = FontWeight.Bold) }
        items(preview.rows.take(30)) { row -> Text("${formatTime(row.time)} · ${row.platform}\n${row.kind.label} ¥${money(row.amount)} · ${row.party.ifBlank { "交易对象未提供" }}", style = MaterialTheme.typography.bodySmall) }
        if (preview.errors.isNotEmpty()) {
            item { Text("文件存在异常，暂不允许导入。请修正文件后重试，避免漏记。", color = MaterialTheme.colorScheme.error) }
            items(preview.errors.take(40)) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        items(candidates, key = { it.row.sourceKey }) { match -> Column {
            val row = match.row
            Text("${row.platform} ${row.kind.label} ¥${money(row.amount)}\n${row.party} · ${formatTime(row.time)}", fontWeight = FontWeight.SemiBold)
            val selected = choices[row.sourceKey]
            FilterChip(selected = selected == "new", onClick = { choices[row.sourceKey] = "new" }, label = { Text("这是另一笔，新增记录") })
            match.candidates.forEach { entry -> FilterChip(selected = selected == entry.id, onClick = { choices[row.sourceKey] = entry.id }, label = { Text("合并：${entry.party.ifBlank { entry.category }} ${formatTime(entry.time)}") }) }
        } }
        item { Text("账单格式及通知模板尚待真实样本验证，请核对预览后导入。", style = MaterialTheme.typography.bodySmall) }
    } }, confirmButton = { TextButton(enabled = !vm.busy && preview.rows.isNotEmpty() && preview.errors.isEmpty() && !unresolved, onClick = {
        val matches = vm.matches; val decisions = choices.toMap()
        vm.work("账单已导入并核对") { vm.repo.commit(matches, decisions); withContext(Dispatchers.Main) { vm.preview = null } }
    }) { Text(if (vm.busy) "正在导入…" else "确认导入") } }, dismissButton = { TextButton(enabled = !vm.busy, onClick = { vm.preview = null }) { Text("取消") } })
}
