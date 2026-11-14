package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveTvEntry(val addon: Addon, val catalogName: String, val channel: MetaPreview)
data class LiveTvState(val loading: Boolean = true, val entries: List<LiveTvEntry> = emptyList(), val error: String? = null)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val addons: AddonRepository,
    private val catalogs: CatalogRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(LiveTvState())
    val state: StateFlow<LiveTvState> = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = LiveTvState(loading = true)
            val found = mutableListOf<LiveTvEntry>()
            try {
                val installed = addons.getInstalledAddons().first()
                val channelCatalogs = installed.flatMap { addon ->
                    addon.catalogs.filter { it.type.toApiString(it.rawType).equals("channel", true) }
                        .map { addon to it }
                }
                channelCatalogs.forEach { (addon, catalog) ->
                    catalogs.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        skip = 0,
                        supportsSkip = catalog.supportsExtra("skip")
                    ).collect { result ->
                        when (result) {
                            is NetworkResult.Success -> result.data.items
                                .filter { it.apiType.equals("channel", true) }
                                .forEach { found += LiveTvEntry(addon, catalog.name, it) }
                            is NetworkResult.Error -> Unit
                            NetworkResult.Loading -> Unit
                        }
                    }
                }
                val unique = found.distinctBy { "${it.addon.id}:${it.channel.id}" }
                mutableState.value = LiveTvState(
                    loading = false,
                    entries = unique,
                    error = if (unique.isEmpty()) "No installed addons currently provide channel catalogs." else null
                )
            } catch (error: Exception) {
                mutableState.value = LiveTvState(false, found, error.message ?: "Unable to load Live TV channels.")
            }
        }
    }
}
