package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String?) -> Unit
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (showBuiltInHeader) Text("Live TV", style = MaterialTheme.typography.headlineLarge)
        when {
            state.loading -> LoadingIndicator()
            state.entries.isEmpty() -> {
                EmptyScreenState(title = "Live TV", subtitle = state.error)
                Button(onClick = viewModel::refresh) { Text("Refresh") }
            }
            else -> {
                Text("${state.entries.size} channels", style = MaterialTheme.typography.titleMedium)
                LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.entries, key = { "${it.addon.id}:${it.channel.id}" }) { entry ->
                        Card(
                            onClick = { onNavigateToDetail(entry.channel.id, entry.channel.apiType, entry.addon.baseUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.colors()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(entry.channel.name, style = MaterialTheme.typography.titleLarge)
                                Text("${entry.addon.displayName} · ${entry.catalogName}", style = MaterialTheme.typography.bodyMedium)
                                entry.channel.description?.takeIf(String::isNotBlank)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
