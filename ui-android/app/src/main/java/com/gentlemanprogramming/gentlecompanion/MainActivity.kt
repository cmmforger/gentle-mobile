package com.gentlemanprogramming.gentlecompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlecompanion.ui.theme.GentleCompanionTheme
import com.gentlemanprogramming.gentlecompanion.ui.theme.Tokens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GentleCompanionTheme {
                NodesScreen()
            }
        }
    }
}

private data class HostRow(val name: String, val meta: String, val state: String, val unreachable: Boolean)

// Placeholder rows until T10 lands the real lane-A host list. Copy follows
// design/gentle-companion/02-nodes.html.
private val placeholderHosts = listOf(
    HostRow(
        name = "workspace",
        meta = "erick@100.64.9.12 · herdr 0.9.5 · protocol 22 · 3 agents",
        state = "connected",
        unreachable = false,
    ),
    HostRow(
        name = "pi-lab",
        meta = "erick@192.168.1.31 · offline since 09:12 · retry 14:00",
        state = "unreachable",
        unreachable = true,
    ),
    HostRow(
        name = "api-box",
        meta = "erick@api.internal · last seen 2 h ago · jump: bastion",
        state = "reachable",
        unreachable = false,
    ),
)

/**
 * Nodes screen proof-of-theme: header, hairline host list, one accent action.
 * One surface, rows separated by a 1px `line` hairline, state as a word in
 * secondary text (unreachable in error colour, per the design system).
 */
@Composable
fun NodesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Gentle Companion",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Your hosts",
            color = Tokens.muted,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
        )
        placeholderHosts.forEachIndexed { index, host ->
            HostRowItem(host)
            if (index != placeholderHosts.lastIndex) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Tokens.line),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { /* Add host — pairing screen lands with T9 */ },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Tokens.accent,
                contentColor = Tokens.bg,
            ),
        ) {
            Text(text = "Add host", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HostRowItem(host: HostRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    text = host.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = host.state,
                    color = if (host.unreachable) Tokens.error else Tokens.muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = host.meta,
                color = Tokens.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "›", color = Tokens.muted, fontSize = 15.sp)
    }
}

@Preview(showBackground = true)
@Composable
private fun NodesScreenPreview() {
    GentleCompanionTheme {
        NodesScreen()
    }
}
