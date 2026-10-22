package dev.omp.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omp.mobile.data.CollabRepository
import dev.omp.mobile.data.ThemeMode
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: CollabRepository,
) : ViewModel() {
    val nickname = repository.nickname
    val theme = repository.theme
    val session = repository.session

    fun saveNickname(value: String) {
        viewModelScope.launch { repository.setNickname(value) }
    }

    fun saveTheme(mode: ThemeMode) {
        viewModelScope.launch { repository.setTheme(mode) }
    }
}
