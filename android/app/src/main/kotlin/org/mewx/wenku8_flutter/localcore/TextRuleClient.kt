package org.mewx.wenku8_flutter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A bounded synchronous bridge used only by the existing background import worker. */
class TextRuleClient(private val context: Context, options: TextImportRules) : AutoCloseable {
    private val connected = CountDownLatch(1)
    private val responses = LinkedBlockingQueue<Pair<Int, Bundle>>()
    @Volatile private var remote: Messenger? = null
    private var sequence = 0
    private var bound = false
    private var outstanding = false
    private var binder: IBinder? = null
    private val death = IBinder.DeathRecipient { responses.offer(-1 to Bundle().apply { putString("error", "目录规则处理过慢或已中断，请简化表达式后重试") }) }
    private val replies = Messenger(Handler(Looper.getMainLooper()) { response -> responses.offer(response.arg1 to Bundle(response.data)); true })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; remote = Messenger(service); runCatching { service.linkToDeath(death, 0) }; connected.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; death.binderDied(); connected.countDown() }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
    }
    init {
        check(Looper.myLooper() != Looper.getMainLooper()) { "目录规则应在后台处理" }
        try {
            options.validate()
            bound = context.bindService(Intent(context, TextRuleService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound && connected.await(5, TimeUnit.SECONDS) && remote != null) { "目录规则进程无法启动，请重试" }
            request(TextRuleService.INITIALIZE, Bundle().apply { putString("options", options.json()) })
        } catch (error: Exception) { close(); throw error }
    }
    private fun request(code: Int, data: Bundle): Bundle {
        val identity = ++sequence
        val response = remote ?: error("目录规则进程已关闭")
        outstanding = true
        response.send(Message.obtain(null, code, identity, 0).apply { this.data = data; replyTo = replies })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (true) {
            val result = responses.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                ?: error("目录规则处理过慢，请简化表达式后重试")
            if (result.first == -1 || result.first == identity) {
                outstanding = false
                result.second.getString("error")?.let { error(it) }
                return result.second
            }
        }
    }
    fun classify(lines: List<String>): List<Int> {
        require(lines.size <= 128 && lines.all { it.length <= 90 })
        return request(TextRuleService.CLASSIFY, Bundle().apply { putStringArray("lines", lines.toTypedArray()) }).getIntArray("matches")?.toList() ?: error("目录规则没有返回结果")
    }
    override fun close() {
        if (outstanding) runCatching { remote?.send(Message.obtain(null, TextRuleService.STOP)) }
        runCatching { binder?.unlinkToDeath(death, 0) }
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
        remote = null
    }
}
