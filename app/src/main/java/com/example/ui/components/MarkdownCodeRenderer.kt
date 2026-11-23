package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay

sealed interface MarkdownSegment {
    data class Prose(val text: String) : MarkdownSegment
    data class CodeBlock(val language: String, val code: String) : MarkdownSegment
}

fun parseMarkdownSegments(input: String): List<MarkdownSegment> {
    if (input.isEmpty()) return emptyList()
    val segments = mutableListOf<MarkdownSegment>()
    val lines = input.lines()
    val proseBuffer = StringBuilder()
    val codeBuffer = StringBuilder()
    var inCode = false
    var codeLang = "code"

    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                if (proseBuffer.isNotEmpty()) {
                    val prose = proseBuffer.toString().trim()
                    if (prose.isNotEmpty()) segments.add(MarkdownSegment.Prose(prose))
                    proseBuffer.clear()
                }
                inCode = true
                codeLang = trimmed.removePrefix("```").trim().ifEmpty { "code" }
            } else {
                segments.add(
                    MarkdownSegment.CodeBlock(
                        language = codeLang,
                        code = codeBuffer.toString().trimEnd()
                    )
                )
                codeBuffer.clear()
                inCode = false
                codeLang = "code"
            }
        } else {
            if (inCode) {
                if (codeBuffer.isNotEmpty()) codeBuffer.append('\n')
                codeBuffer.append(line)
            } else {
                if (proseBuffer.isNotEmpty()) proseBuffer.append('\n')
                proseBuffer.append(line)
            }
        }
    }

    if (inCode && codeBuffer.isNotEmpty()) {
        segments.add(MarkdownSegment.CodeBlock(language = codeLang, code = codeBuffer.toString().trimEnd()))
    } else if (proseBuffer.isNotEmpty()) {
        val prose = proseBuffer.toString().trim()
        if (prose.isNotEmpty()) segments.add(MarkdownSegment.Prose(prose))
    }
    return segments
}

@Composable
fun MarkdownMessageContent(
    content: String,
    onSaveCodeSnippet: (language: String, code: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(content) { parseMarkdownSegments(content) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Prose -> {
                    val styledText = rememberStyledProse(segment.text)
                    SelectionContainer {
                        Text(
                            text = styledText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                is MarkdownSegment.CodeBlock -> {
                    CodeBlockCard(
                        language = segment.language,
                        code = segment.code,
                        onSaveSnippet = { onSaveCodeSnippet(segment.language, segment.code) },
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberStyledProse(text: String): AnnotatedString {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val inlineCodeFg = MaterialTheme.colorScheme.primary
    return remember(text, inlineCodeBg, inlineCodeFg) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("**", i) -> {
                        val end = text.indexOf("**", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    text[i] == '`' -> {
                        val end = text.indexOf('`', i + 1)
                        if (end != -1 && end > i + 1) {
                            withStyle(
                                SpanStyle(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    background = inlineCodeBg,
                                    color = inlineCodeFg,
                                    fontSize = 13.5.sp,
                                )
                            ) {
                                append(" ${text.substring(i + 1, end)} ")
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    onSaveSnippet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFF050811),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 4.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = language.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onSaveSnippet,
                        modifier = Modifier.testTag("save_code_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BookmarkAdd,
                            contentDescription = stringResource(R.string.save_snippet),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.save_snippet),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        },
                        modifier = Modifier.testTag("copy_code_button"),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.copy_code),
                            modifier = Modifier.size(16.dp),
                            tint = if (copied) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (copied) "Copied" else stringResource(R.string.copy_code),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (copied) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            SelectionContainer {
                Text(
                    text = code,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFFE2E8F0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(14.dp),
                )
            }
        }
    }
}
