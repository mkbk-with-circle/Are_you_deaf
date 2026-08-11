package com.nierduolong.morningbell.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机崩溃日志。没有服务端也要能回答「昨天为什么闪退」：
 * 崩溃栈落盘到 files/crash/，设置页可以查看/导出，最多保留最近若干份。
 */
object CrashLogger {
    private const val DIR_NAME = "crash"
    private const val MAX_FILES = 10

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 记录失败绝不能影响系统原本的崩溃处理链路
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun logDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun recentReports(context: Context): List<File> =
        logDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clear(context: Context) {
        recentReports(context).forEach { it.delete() }
    }

    private fun write(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val stack =
            StringWriter().also { writer ->
                PrintWriter(writer).use { throwable.printStackTrace(it) }
            }.toString()
        val report =
            buildString {
                appendLine("time: $stamp")
                appendLine("thread: ${thread.name}")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("---")
                append(stack)
            }
        File(logDir(context), "crash-$stamp.txt").writeText(report)
        recentReports(context).drop(MAX_FILES).forEach { it.delete() }
    }
}
