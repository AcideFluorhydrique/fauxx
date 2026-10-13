package com.fauxx.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.custompool.CustomPoolEntry
import com.fauxx.data.custompool.CustomPoolKind
import com.fauxx.data.custompool.CustomPoolRejection
import com.fauxx.data.custompool.CustomPoolRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the last add did, for the message under the input box (#328). [rejections] keeps the
 * order the reasons were first hit, so the UI lists them in that order.
 */
data class CustomPoolAddFeedback(
    val kind: CustomPoolKind,
    val added: Int,
    val rejections: Map<CustomPoolRejection, Int>,
) {
    val rejected: Int get() = rejections.values.sum()
}

/** State for the "Your searches and sites" screen (#328). */
@HiltViewModel
class CustomPoolViewModel @Inject constructor(
    private val repository: CustomPoolRepository,
) : ViewModel() {

    val entries: StateFlow<List<CustomPoolEntry>> = repository.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _feedback = MutableStateFlow<CustomPoolAddFeedback?>(null)
    val feedback: StateFlow<CustomPoolAddFeedback?> = _feedback.asStateFlow()

    /**
     * Adds [text] (one entry, or many, one per line) to the [kind] list. [onDone] gets whether
     * anything was added, so the screen can clear the input box only then and keep a failed
     * entry in place for the user to fix.
     */
    fun add(kind: CustomPoolKind, text: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val plan = repository.add(kind, text)
            _feedback.value = CustomPoolAddFeedback(kind, plan.accepted.size, plan.rejections)
            onDone(plan.accepted.isNotEmpty())
        }
    }

    fun remove(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun clear(kind: CustomPoolKind) {
        viewModelScope.launch {
            repository.clear(kind)
            _feedback.value = null
        }
    }

    fun dismissFeedback() {
        _feedback.value = null
    }
}
