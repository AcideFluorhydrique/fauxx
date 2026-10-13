package com.fauxx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.R
import com.fauxx.data.custompool.CustomPoolKind
import com.fauxx.data.custompool.CustomPoolMixer
import com.fauxx.data.custompool.CustomPoolRejection
import com.fauxx.data.custompool.CustomPoolValidator
import com.fauxx.ui.viewmodels.CustomPoolAddFeedback
import com.fauxx.ui.viewmodels.CustomPoolViewModel
import kotlin.math.roundToInt

/**
 * "Your searches and sites" (#328): the user's own search terms and websites, mixed into the noise
 * alongside the built-in per-language lists. One input box per list takes a single entry or a
 * paste of many, one per line; what could not be added is explained under it.
 */
@Composable
fun CustomPoolScreen(
    onBack: () -> Unit,
    viewModel: CustomPoolViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val kind = if (selectedTab == 0) CustomPoolKind.SEARCH else CustomPoolKind.SITE
    var input by rememberSaveable(kind) { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val shown = entries.filter { it.kind == kind }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // End padding keeps the title clear of the language and help icons drawn over the
            // top-right corner (104dp from the screen edge, as on the Log screen).
            Row(
                modifier = Modifier.padding(top = 16.dp, end = 88.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.faq_back),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(R.string.custom_pool_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        item {
            Text(
                stringResource(
                    R.string.custom_pool_intro,
                    (CustomPoolMixer.SHARE_PER_ENTRY * 100).roundToInt(),
                    (CustomPoolMixer.MAX_SHARE * 100).roundToInt()
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; viewModel.dismissFeedback() },
                    text = { Text(stringResource(R.string.custom_pool_tab_searches)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; viewModel.dismissFeedback() },
                    text = { Text(stringResource(R.string.custom_pool_tab_sites)) }
                )
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = {
                            Text(
                                stringResource(
                                    if (kind == CustomPoolKind.SEARCH) R.string.custom_pool_input_searches
                                    else R.string.custom_pool_input_sites
                                )
                            )
                        },
                        placeholder = {
                            Text(
                                stringResource(
                                    if (kind == CustomPoolKind.SEARCH) R.string.custom_pool_placeholder_searches
                                    else R.string.custom_pool_placeholder_sites
                                )
                            )
                        },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(
                                R.string.custom_pool_count,
                                shown.size,
                                CustomPoolValidator.MAX_ENTRIES_PER_KIND
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                viewModel.add(kind, input) { addedAny -> if (addedAny) input = "" }
                            },
                            enabled = input.isNotBlank()
                        ) {
                            Text(stringResource(R.string.custom_pool_add_button))
                        }
                    }
                    feedback?.takeIf { it.kind == kind }?.let { AddFeedbackText(it) }
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                Text(
                    stringResource(
                        if (kind == CustomPoolKind.SEARCH) R.string.custom_pool_empty_searches
                        else R.string.custom_pool_empty_sites
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            item {
                TextButton(onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.custom_pool_clear_button), color = MaterialTheme.colorScheme.error)
                }
            }
            items(shown, key = { it.id }) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        entry.value,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.remove(entry.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.custom_pool_remove_content_desc, entry.value),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.custom_pool_clear_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        if (kind == CustomPoolKind.SEARCH) R.string.custom_pool_clear_dialog_body_searches
                        else R.string.custom_pool_clear_dialog_body_sites
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear(kind)
                    confirmClear = false
                }) { Text(stringResource(R.string.custom_pool_clear_button), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun AddFeedbackText(feedback: CustomPoolAddFeedback) {
    val summary = if (feedback.rejected == 0) {
        stringResource(R.string.custom_pool_result_added, feedback.added)
    } else {
        stringResource(R.string.custom_pool_result, feedback.added, feedback.rejected)
    }
    Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        color = if (feedback.added > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
    // One line per distinct reason, in the order they were first hit.
    feedback.rejections.keys.forEach { reason ->
        Text(
            rejectionText(reason, feedback.kind),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun rejectionText(reason: CustomPoolRejection, kind: CustomPoolKind): String = when (reason) {
    CustomPoolRejection.EMPTY -> ""
    CustomPoolRejection.TOO_LONG -> stringResource(
        R.string.custom_pool_reason_too_long,
        if (kind == CustomPoolKind.SEARCH) CustomPoolValidator.MAX_QUERY_LENGTH else CustomPoolValidator.MAX_URL_LENGTH
    )
    CustomPoolRejection.INVALID_CHARACTERS -> stringResource(R.string.custom_pool_reason_invalid_characters)
    CustomPoolRejection.NOT_A_WEB_ADDRESS -> stringResource(R.string.custom_pool_reason_not_web_address)
    CustomPoolRejection.HAS_CREDENTIALS -> stringResource(R.string.custom_pool_reason_credentials)
    CustomPoolRejection.LOCAL_ADDRESS -> stringResource(R.string.custom_pool_reason_local_address)
    CustomPoolRejection.BLOCKED -> stringResource(
        if (kind == CustomPoolKind.SEARCH) R.string.custom_pool_reason_blocked_search
        else R.string.custom_pool_reason_blocked_site
    )
    CustomPoolRejection.DUPLICATE -> stringResource(R.string.custom_pool_reason_duplicate)
    CustomPoolRejection.LIMIT_REACHED -> stringResource(
        R.string.custom_pool_reason_limit,
        CustomPoolValidator.MAX_ENTRIES_PER_KIND
    )
}
