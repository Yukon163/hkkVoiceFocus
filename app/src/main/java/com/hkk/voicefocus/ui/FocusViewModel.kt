package com.hkk.voicefocus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hkk.voicefocus.*
import com.hkk.voicefocus.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class FocusViewModel(app: Application) : AndroidViewModel(app) {
    val repo = app.repository
    private val prefs = app.getSharedPreferences("settings", 0)
    val theme = MutableStateFlow(prefs.getString("theme", "system")!!)
    fun theme(value: String) { theme.value = value; prefs.edit().putString("theme", value).apply() }
    fun change(p: Project, persist: Boolean = true) {
        repo.update(p.copy(modified = System.currentTimeMillis()), false)
        if (persist) save(p)
    }
    fun gains(p: Project, id: String, gain: Float) { change(p.copy(stems = p.stems.map { if (it.id == id) it.copy(gain = gain.coerceIn(0f, 2f)) else it }), false) }
    fun save(p: Project) { viewModelScope.launch(Dispatchers.IO) {
        runCatching { repo.persist(p.id) }.onFailure { repo.message.value = "配比已保存在工作缓存，但公共工程同步失败：${it.message}" }
    } }
    fun preset(p: Project, kind: String) = change(p.copy(stems = p.stems.map {
        it.copy(gain = when (kind) { "focus" -> if (it.id == "vocals") 1.2f else .45f; else -> 1f },
            muted = (kind == "voice" && it.id != "vocals") || (kind == "music" && it.id == "vocals"))
    }))
    fun delete(p: Project) { viewModelScope.launch(Dispatchers.IO) { runCatching { repo.delete(p) }.onFailure { repo.message.value = it.message } } }
    override fun onCleared() { repo.player.pause() }
}
