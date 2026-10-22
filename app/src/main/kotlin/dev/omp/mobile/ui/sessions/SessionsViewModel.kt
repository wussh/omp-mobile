package dev.omp.mobile.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omp.mobile.collab.PairingCredentials
import dev.omp.mobile.collab.formatCollabLink
import dev.omp.mobile.data.CollabRepository
import dev.omp.mobile.data.PairingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: CollabRepository,
) : ViewModel() {
    val pairings = repository.pairings
    val session = repository.session

    private val _pairingResult = MutableStateFlow<PairingResult?>(null)
    val pairingResult: StateFlow<PairingResult?> = _pairingResult.asStateFlow()

    fun pair(credentials: PairingCredentials, label: String?) {
        _pairingResult.value = null
        viewModelScope.launch {
            _pairingResult.value = repository.pair(formatCollabLink(credentials), label)
        }
    }

    fun clearPairingResult() {
        _pairingResult.value = null
    }

    fun select(id: String) {
        viewModelScope.launch { repository.select(id) }
    }

    fun rename(id: String, label: String) {
        viewModelScope.launch { repository.rename(id, label) }
    }

    fun forget(id: String) {
        viewModelScope.launch { repository.forget(id) }
    }

    fun retry() = repository.retry()

    fun disconnect() = repository.disconnect()

    fun clearNotice() = repository.clearNotice()
}
