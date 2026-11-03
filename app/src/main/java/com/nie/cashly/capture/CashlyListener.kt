package com.nie.cashly.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.*
import com.nie.cashly.core.*
import com.nie.cashly.data.Repository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.TimeUnit

class CashlyListener : NotificationListenerService() {
    companion object { val connectionState = kotlinx.coroutines.flow.MutableStateFlow(false) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<StatusBarNotification, Boolean>>(Channel.UNLIMITED)
    private val repository by lazy { Repository(applicationContext) }
    override fun onCreate() {
        super.onCreate()
        scope.launch { for ((sbn, removed) in queue) runCatching {
            if (removed) repository.dao.removeSlot(sbn.key) else process(sbn)
        } }
    }
    override fun onListenerConnected() {
        connectionState.value = true
        val active = activeNotifications.orEmpty()
        scope.launch {
            repository.connection(true)
            val keys = active.map { it.key }.toSet()
            repository.dao.slots().filter { it.key !in keys }.forEach { repository.dao.removeSlot(it.key) }
            active.forEach { onNotificationPosted(it) }
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("notification-retention", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PruneWorker>(12, TimeUnit.HOURS).build())
    }
    override fun onListenerDisconnected() { connectionState.value = false; scope.launch { repository.connection(false) } }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in listOf("com.tencent.mm", "com.eg.android.AlipayGphone") && sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) queue.trySend(sbn to false)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in listOf("com.tencent.mm", "com.eg.android.AlipayGphone")) queue.trySend(sbn to true)
    }
    private suspend fun process(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val text = "$title\n$body"
        val wechat = sbn.packageName == "com.tencent.mm"
        // WeChat ordinary conversations are not transaction templates even if a contact mentions payment.
        val trustedTitle = if (wechat) title in listOf("微信支付", "微信收款助手", "微信支付助手")
            else title in listOf("支付宝", "支付成功", "收款到账", "到账提醒", "退款到账", "交易提醒")
        val parsed = if (trustedTitle) (if (wechat) WeChatParser() else AlipayParser()).parse(text) else null
        repository.captureNotification(sbn.key, if (wechat) "微信" else "支付宝", text, sbn.postTime, parsed)
    }
    override fun onDestroy() { connectionState.value = false; queue.close(); scope.cancel(); super.onDestroy() }
}
class PruneWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { Repository(applicationContext).prune(); Result.success() } catch (_: Exception) { Result.retry() }
}
