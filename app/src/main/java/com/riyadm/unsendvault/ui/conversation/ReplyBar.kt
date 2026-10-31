package com.riyadm.unsendvault.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.riyadm.unsendvault.R

object ReplyTestTags {
    const val FIELD = "reply_field"
    const val SEND = "reply_send"
}

/**
 * Reply box under a conversation. Sending goes through the chat app's own notification reply action, so it works only
 * while that notification is still showing ([available]); otherwise the box is disabled and says why. The draft is
 * kept in plain `remember` state on purpose, so it never lands in the saved-instance-state bundle.
 */
@Composable
fun ReplyBar(available: Boolean, onSend: (String) -> Boolean, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            when {
                !available -> Text(
                    stringResource(R.string.reply_unavailable),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                failed -> Text(
                    stringResource(R.string.reply_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        failed = false
                    },
                    enabled = available,
                    placeholder = { Text(stringResource(R.string.reply_hint)) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f).testTag(ReplyTestTags.FIELD),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = available && text.isNotBlank(),
                    onClick = {
                        if (onSend(text)) text = "" else failed = true
                    },
                    modifier = Modifier.testTag(ReplyTestTags.SEND),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.reply_send))
                }
            }
        }
    }
}
