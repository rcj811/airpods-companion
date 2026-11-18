package com.rcj811.podscompanion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent event log (survives disconnects and restarts) shown in Settings. */
object DiagLog {
    private const val MAX = 80
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private var ctx: Context? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        ctx = context.applicationContext
        val saved = context.getSharedPreferences("diag", Context.MODE_PRIVATE).getString("log", null)
        if (saved != null && _lines.value.isEmpty()) _lines.value = saved.split("\u0001").filter { it.isNotEmpty() }
    }

    @Synchronized
    fun add(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        if (_lines.value.firstOrNull()?.substringAfter("  ") == msg) return // skip exact repeats
        val next = (listOf(line) + _lines.value).take(MAX)
        _lines.value = next
        ctx?.getSharedPreferences("diag", Context.MODE_PRIVATE)?.edit()?.putString("log", next.joinToString("\u0001"))?.apply()
    }

    fun clear() {
        _lines.value = emptyList()
        ctx?.getSharedPreferences("diag", Context.MODE_PRIVATE)?.edit()?.remove("log")?.apply()
    }
}
