package com.siliconleap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Markdown 渲染（commonmark 解析），用于更新说明等场景。 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val document = remember(markdown) { Parser.builder().build().parse(markdown) }
    Column(modifier) {
        document.childNodes().forEach { Block(it) }
    }
}

@Composable
private fun Block(node: Node) {
    when (node) {
        is Heading -> {
            val level = node.level
            Text(
                text = node.inline(codeBackground),
                fontSize = when (level) {
                    1 -> 20.sp
                    2 -> 18.sp
                    else -> 16.sp
                },
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
        }

        is Paragraph -> Text(
            text = node.inline(codeBackground),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        is BulletList -> Column(Modifier.padding(vertical = 2.dp)) {
            node.childNodes().forEach { ListItemRow(it, "•") }
        }

        is OrderedList -> Column(Modifier.padding(vertical = 2.dp)) {
            val start = node.startNumber
            node.childNodes().forEachIndexed { index, item ->
                ListItemRow(item, "${start + index}.")
            }
        }

        is FencedCodeBlock -> CodeBlock(node.literal)
        is IndentedCodeBlock -> CodeBlock(node.literal)

        is ThematicBreak -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(1.dp)
                .background(MiuixTheme.colorScheme.outline),
        )

        is BlockQuote -> Column(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .padding(start = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Column { node.childNodes().forEach { Block(it) } }
            }
        }

        else -> Column {
            node.childNodes().forEach { Block(it) }
        }
    }
}

private val codeBackground: Color
    @Composable get() = MiuixTheme.colorScheme.secondary.copy(alpha = 0.18f)

/** 遍历子节点（commonmark Node 基础 API：firstChild / next）。 */
private fun Node.childNodes(): List<Node> {
    val out = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        out += child
        child = child.next
    }
    return out
}

@Composable
private fun ListItemRow(item: Node, bullet: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = bullet,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MiuixTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            item.childNodes().forEach { Block(it) }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MiuixTheme.colorScheme.secondary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = code,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onBackground,
        )
    }
}

private fun Node.inline(codeBg: Color): AnnotatedString = buildAnnotatedString {
    inline(this@inline, codeBg)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.inline(node: Node, codeBg: Color) {
    when (node) {
        is Text -> append(node.literal)
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            node.childNodes().forEach { inline(it, codeBg) }
        }

        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            node.childNodes().forEach { inline(it, codeBg) }
        }

        is Code -> withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                background = codeBg,
            ),
        ) {
            append(node.literal)
        }

        is Link -> node.childNodes().forEach { inline(it, codeBg) }
        is SoftLineBreak, is HardLineBreak -> append("\n")
        else -> node.childNodes().forEach { inline(it, codeBg) }
    }
}
