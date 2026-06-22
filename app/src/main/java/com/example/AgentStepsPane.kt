package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun AgentStepsPane(vm: MainViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.agentSteps.size) { if (vm.agentSteps.isNotEmpty()) listState.animateScrollToItem(vm.agentSteps.size - 1) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(vm.agentSteps) { step ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(OllamaCard).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    step.type.uppercase(),
                    color = when (step.type) {
                        "think"       -> OllamaPurple
                        "tool_call"   -> OllamaGreen
                        "tool_result" -> TerminalGreen
                        "error"       -> OllamaRed
                        "user"        -> OllamaBlue
                        else          -> OllamaTextDim
                    },
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp)
                )
                Text(
                    step.content, color = OllamaText, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
