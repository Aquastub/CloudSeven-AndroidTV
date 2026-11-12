package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.local.ThemePackageDataStore
import com.nuvio.tv.core.theme.ThemePackage
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemeSettingsUiState(
    val selectedTheme: AppTheme = AppTheme.WHITE,
    val availableThemes: List<AppTheme> = listOf(AppTheme.WHITE) + AppTheme.entries.filterNot { it == AppTheme.WHITE },
    val selectedFont: AppFont = AppFont.INTER,
    val availableFonts: List<AppFont> = AppFont.entries.toList(),
    val installedPackages: List<ThemePackage> = emptyList(),
    val activePackageId: String? = null,
    val packageMessage: String? = null
)

sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
    data class ActivatePackage(val id: String?) : ThemeSettingsEvent()
    data class RemovePackage(val id: String) : ThemeSettingsEvent()
}

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val themeDataStore: ThemeDataStore,
    private val themePackageDataStore: ThemePackageDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThemeSettingsUiState())
    val uiState: StateFlow<ThemeSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            themeDataStore.selectedTheme
                .distinctUntilChanged()
                .collectLatest { theme ->
                    _uiState.update { state ->
                        if (state.selectedTheme == theme) state else state.copy(selectedTheme = theme)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.selectedFont
                .distinctUntilChanged()
                .collectLatest { font ->
                    _uiState.update { state ->
                        if (state.selectedFont == font) state else state.copy(selectedFont = font)
                    }
                }
        }
        viewModelScope.launch {
            themePackageDataStore.installed.collectLatest { packages ->
                _uiState.update { it.copy(installedPackages = packages) }
            }
        }
        viewModelScope.launch {
            themePackageDataStore.active.collectLatest { active ->
                _uiState.update { it.copy(activePackageId = active?.id) }
            }
        }
    }

    private fun currentTheme(): AppTheme {
        return _uiState.value.selectedTheme
    }

    fun onEvent(event: ThemeSettingsEvent) {
        when (event) {
            is ThemeSettingsEvent.SelectTheme -> selectTheme(event.theme)
            is ThemeSettingsEvent.SelectFont -> selectFont(event.font)
            is ThemeSettingsEvent.ActivatePackage -> viewModelScope.launch { themePackageDataStore.activate(event.id) }
            is ThemeSettingsEvent.RemovePackage -> viewModelScope.launch { themePackageDataStore.remove(event.id) }
        }
    }

    private fun selectTheme(theme: AppTheme) {
        if (currentTheme() == theme) return
        viewModelScope.launch {
            themeDataStore.setTheme(theme)
        }
    }

    private fun selectFont(font: AppFont) {
        if (_uiState.value.selectedFont == font) return
        viewModelScope.launch {
            themeDataStore.setFont(font)
        }
    }

    fun installThemeArchive(bytes: ByteArray) {
        viewModelScope.launch {
            runCatching { ThemePackage.readArchive(bytes) }
                .onSuccess { themePackageDataStore.install(it); _uiState.update { state -> state.copy(packageMessage = "Installed ${it.name}") } }
                .onFailure { error -> _uiState.update { state -> state.copy(packageMessage = error.message ?: "Theme import failed") } }
        }
    }

}
