package com.orgutil.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.orgutil.ui.theme.OrgMono
import kotlin.math.roundToInt

/**
 * Minimal in-repo Markdown renderer for assistant messages (UI-2): headings,
 * nested bullet/numbered lists, GFM tables, blockquotes, horizontal rules,
 * bold/italic/strikethrough, inline code, fenced code blocks and links,
 * rendered with the existing Material/Org typography and teal theme.
 * No WebView, no network image loading. Deliberately conservative: anything
 * it does not recognise renders as a plain paragraph, so model output can
 * never be lost - only styled. The whole body is selectable for copy.
 * Links (markdown URLs and bare http(s) autolinks) open in the external
 * browser; other schemes keep the platform UriHandler semantics.
 */
@Composable
fun AssistantMarkdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    SelectionContainer(modifier = modifier) {
        Column {
            blocks.forEachIndexed { index, block ->
                when (block) {
                    is MdBlock.Heading -> SelectableLinkText(
                        text = block.spans.annotated(),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp, bottom = 2.dp)
                    )

                    is MdBlock.Paragraph -> SelectableLinkText(
                        text = block.spans.annotated(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    is MdBlock.Bullets -> block.items.forEach { item ->
                        MdListRow(
                            marker = bulletMarkers[item.level.coerceIn(0, bulletMarkers.lastIndex)] + "  ",
                            item = item
                        )
                    }

                    is MdBlock.Numbered -> block.items.forEach { item ->
                        MdListRow(marker = "${item.number}.  ", item = item)
                    }

                    is MdBlock.Quote -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(IntrinsicSize.Min)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        )
                        SelectableLinkText(
                            text = block.spans.annotated(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 4.dp)
                        )
                    }
                    is MdBlock.Rule -> HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    is MdBlock.Code -> Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        SelectableLinkText(
                            text = AnnotatedString(block.content),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }

                    is MdBlock.Table -> MdTable(block = block)
                }
            }
        }
    }
}

/**
 * String-annotation tag carrying a tappable URL on rendered reply text.
 * Chosen over [androidx.compose.ui.text.LinkAnnotation] deliberately: the
 * app runs material3 1.5.0-alpha18, which forces the beta (1.11.x) text
 * stack over the BOM pins, and that alpha plumbing proved unreliable for
 * real-device taps. Explicit tap hit-testing via string annotations works
 * on every Compose version.
 */
internal const val ChatUrlTag = "chat-url"

/**
 * The text primitive every rendered reply block uses. Foundation
 * [BasicText] — not material3-alpha Text — because on the app's current
 * text stack (see [ChatUrlTag]) SelectionContainer long-press selection
 * was demonstrated dead with material3-alpha Text while BasicText is the
 * primitive the platform's own selection wiring targets.
 *
 * Taps are hit-tested here against [ChatUrlTag] annotations and open
 * http(s) URLs in the external browser ([openChatLink]). Long presses are
 * left for the enclosing SelectionContainer (selection + copy toolbar):
 * [detectTapGestures] with an empty onLongPress suppresses the
 * tap-after-hold misfire without consuming the selection gesture.
 */
@Composable
private fun SelectableLinkText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color? = null,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val resolved = remember(style, color, fontWeight, textAlign) {
        var s = if (color != null) style.copy(color = color) else style
        if (fontWeight != null) s = s.copy(fontWeight = fontWeight)
        if (textAlign != null) s = s.copy(textAlign = textAlign)
        s
    }
    BasicText(
        text = text,
        modifier = modifier.pointerInput(text) {
            detectTapGestures(
                onLongPress = {},
                onTap = { pos ->
                    val layoutResult = layout ?: return@detectTapGestures
                    val offset = layoutResult.getOffsetForPosition(pos)
                    text.getStringAnnotations(ChatUrlTag, offset, offset)
                        .firstOrNull()
                        ?.let { openChatLink(context, uriHandler, it.item) }
                }
            )
        },
        style = resolved,
        onTextLayout = { layout = it },
        maxLines = maxLines,
        overflow = overflow
    )
}

/** One bullet/numbered row: marker plus wrapping content, indented by nesting level. */
@Composable
private fun MdListRow(marker: String, item: MdItem) {    Row(modifier = Modifier.padding(start = (item.level * 12).dp, top = 1.dp, bottom = 1.dp)) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        SelectableLinkText(
            text = item.spans.annotated(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** One table cell; header cells are bold, both wrap and honour column alignment. */
@Composable
private fun MdCell(spans: List<MdSpan>, align: TableAlign, header: Boolean, width: Dp) {
    SelectableLinkText(
        text = spans.annotated(),
        style = if (header) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.Bold else null,
        color = if (header) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = when (align) {
            TableAlign.LEFT -> TextAlign.Left
            TableAlign.CENTER -> TextAlign.Center
            TableAlign.RIGHT -> TextAlign.Right
        },
        modifier = Modifier
            .width(width)
            .padding(horizontal = MdCellPaddingHorizontal, vertical = MdCellPaddingVertical)
    )
}

// ---- GFM table ----

/** Cell inner horizontal padding — must stay in sync with [MdCell]. */
private val MdCellPaddingHorizontal = 6.dp

/** Cell inner vertical padding; roomy enough to separate the row rules. */
private val MdCellPaddingVertical = 5.dp

/** Widest a column may get before long cell text starts wrapping. */
private val MdTableColumnCap = 260.dp

/** Smallest scrollbar thumb that stays comfortable to grab and drag. */
private val MdScrollbarMinThumb = 32.dp

/** Track inset so the scrollbar lines up with the table content edges. */
private val MdScrollbarTrackInset = 6.dp

/** Touch height of the scrollbar track/thumb hit areas. */
private val MdScrollbarTouchHeight = 20.dp

/**
 * GFM table: rounded card, fixed column widths measured once from the cell
 * text, a strong rule under the header and a light rule between rows, and —
 * when the measured table overflows the card — an always-visible horizontal
 * scrollbar whose thumb can be dragged or tapped to seek.
 *
 * Column widths come from a single text-measurement pass (not intrinsic
 * queries on the tree), so the layout is deterministic, nothing is clipped
 * or dropped: a column is never narrower than its widest unbreakable token,
 * and only wraps when its longest line exceeds [MdTableColumnCap].
 */
@Composable
private fun MdTable(block: MdBlock.Table, modifier: Modifier = Modifier) {
    val columnCount = maxOf(
        block.header.size,
        block.aligns.size,
        block.rows.maxOfOrNull { it.size } ?: 0
    )
    if (columnCount == 0) return

    val headerStyle = MaterialTheme.typography.bodyMedium
    val bodyStyle = MaterialTheme.typography.bodySmall
    val columns = rememberColumnWidths(block, columnCount, headerStyle, bodyStyle)
    val tableWidth = columns.reduce { total, column -> total + column }
    val scrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Captured here: inside Column's content lambda the
            // BoxWithConstraintsScope receiver is shadowed by its DslMarker.
            val viewportWidth = maxWidth
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .padding(horizontal = MdScrollbarTrackInset, vertical = 6.dp)
                ) {
                    Row(modifier = Modifier.width(tableWidth)) {
                        repeat(columnCount) { column ->
                            MdCell(
                                spans = block.header.getOrElse(column) { emptyList() },
                                align = block.aligns.getOrElse(column) { TableAlign.LEFT },
                                header = true,
                                width = columns[column]
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.width(tableWidth),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    block.rows.forEachIndexed { rowIndex, row ->
                        Row(modifier = Modifier.width(tableWidth)) {
                            repeat(columnCount) { column ->
                                MdCell(
                                    spans = row.getOrElse(column) { emptyList() },
                                    align = block.aligns.getOrElse(column) { TableAlign.LEFT },
                                    header = false,
                                    width = columns[column]
                                )
                            }
                        }
                        if (rowIndex < block.rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.width(tableWidth),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
                MdTableScrollbar(
                    state = scrollState,
                    viewportWidth = viewportWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Horizontal scrollbar for the table card. Visible whenever the table is
 * actually scrollable; the thumb width mirrors the viewport/content ratio,
 * its position is derived from the scroll position at placement time (no
 * per-frame recomposition), and it supports thumb dragging plus tap-to-seek
 * anywhere on the track.
 */
@Composable
private fun MdTableScrollbar(
    state: ScrollState,
    viewportWidth: Dp,
    modifier: Modifier = Modifier
) {
    val isScrollable by remember(state) {
        derivedStateOf { state.maxValue > 0 }
    }
    if (!isScrollable) return

    val density = LocalDensity.current
    val viewportPx = with(density) { viewportWidth.toPx() }
    val contentPx = viewportPx + state.maxValue
    val trackPx = viewportPx - with(density) { (MdScrollbarTrackInset * 2).toPx() }
    val thumbPx = (trackPx * viewportPx / contentPx)
        .coerceAtLeast(with(density) { MdScrollbarMinThumb.toPx() })
        .coerceAtMost(trackPx)
    val thumbWidth = with(density) { thumbPx.toDp() }
    val maxThumbOffsetPx = trackPx - thumbPx
    val trackInsetPx = with(density) { MdScrollbarTrackInset.toPx() }

    Box(
        modifier = modifier
            .height(MdScrollbarTouchHeight)
            .pointerInput(state, trackPx, thumbPx, maxThumbOffsetPx, trackInsetPx) {
                detectTapGestures { offset: Offset ->
                    // Tap anywhere on the track: centre the thumb there.
                    val x = (offset.x - trackInsetPx).coerceIn(0f, trackPx)
                    val target = if (maxThumbOffsetPx > 0f) {
                        ((x - thumbPx / 2f) / maxThumbOffsetPx) * state.maxValue
                    } else {
                        0f
                    }
                    if (!target.isNaN()) {
                        state.dispatchRawDelta(target - state.value)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = MdScrollbarTrackInset)
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(2.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    // Centre the 20dp hit area on the 4dp track first, then
                    // shift horizontally — otherwise the visual thumb sits
                    // below the track.
                    .align(Alignment.Center)
                    .offset {
                        // Read at placement time: the thumb tracks the scroll
                        // without recomposing while it moves.
                        val fraction =
                            if (state.maxValue == 0) 0f else state.value.toFloat() / state.maxValue
                        IntOffset(
                            x = (fraction * maxThumbOffsetPx).roundToInt()
                                .coerceIn(0, maxThumbOffsetPx.roundToInt()),
                            y = 0
                        )
                    }
                    .size(width = thumbWidth, height = MdScrollbarTouchHeight)
                    .pointerInput(state, maxThumbOffsetPx) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            // 1:1 with the thumb: content follows the finger.
                            state.dispatchRawDelta(dragAmount)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}

/**
 * Measures every column once per parsed table: the single-line width of its
 * widest cell (header measured bold, like the renderer), capped at
 * [MdTableColumnCap], but never narrower than the widest unbreakable token
 * in the column — so wrapping only ever happens at a space, never mid-word,
 * and nothing is clipped.
 */
@Composable
private fun rememberColumnWidths(
    block: MdBlock.Table,
    columnCount: Int,
    headerStyle: TextStyle,
    bodyStyle: TextStyle
): List<Dp> {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(block, columnCount, headerStyle, bodyStyle, textMeasurer, density) {
        val capPx = with(density) { MdTableColumnCap.toPx() }.toInt()
        val cellPaddingPx = with(density) { (MdCellPaddingHorizontal * 2).toPx() }.toInt()
        val widthsPx = (0 until columnCount).map { column ->
            val headerSpans = block.header.getOrElse(column) { emptyList() }
            val bodySpans = block.rows.map { it.getOrElse(column) { emptyList() } }
            val singleLine = maxOf(
                headerSpans.singleLineWidth(textMeasurer, headerStyle.copy(fontWeight = FontWeight.Bold)),
                bodySpans.maxOfOrNull { it.singleLineWidth(textMeasurer, bodyStyle) } ?: 0
            )
            val widestToken = (listOf(headerSpans) + bodySpans)
                .maxOfOrNull { it.widestTokenWidth(textMeasurer, bodyStyle) } ?: 0
            maxOf(minOf(singleLine, capPx), widestToken) + cellPaddingPx
        }
        widthsPx.map { with(density) { it.toDp() } }
    }
}

/** Width-relevant mirror of [annotated]: same spans/styles, no colours. */
private fun List<MdSpan>.widthAnnotated(): AnnotatedString = buildAnnotatedString {
    for (span in this@widthAnnotated) {
        when (span) {
            is MdSpan.Plain -> append(span.text)
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is MdSpan.Strikethrough -> append(span.text)
            is MdSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(" " + span.text + " ") }
            is MdSpan.Link -> append(span.text)
        }
    }
}

/** Unconstrained single-line width of a cell, in pixels. */
private fun List<MdSpan>.singleLineWidth(measurer: TextMeasurer, style: TextStyle): Int {
    if (isEmpty()) return 0
    return measurer.measure(widthAnnotated(), style).size.width
}

/**
 * Width of the widest whitespace-delimited token in a cell, in pixels,
 * measured bold as a conservative upper bound — a column narrower than
 * this would have to break a word or clip it.
 */
private fun List<MdSpan>.widestTokenWidth(measurer: TextMeasurer, style: TextStyle): Int {
    var widest = 0
    val boldStyle = style.copy(fontWeight = FontWeight.Bold)
    val monoStyle = boldStyle.copy(fontFamily = FontFamily.Monospace)
    for (span in this) {
        val (text, spanStyle) = when (span) {
            is MdSpan.Plain -> span.text to boldStyle
            is MdSpan.Bold -> span.text to boldStyle
            is MdSpan.Italic -> span.text to boldStyle
            is MdSpan.Strikethrough -> span.text to boldStyle
            is MdSpan.Code -> " ${span.text} " to monoStyle
            is MdSpan.Link -> span.text to boldStyle
        }
        for (token in text.split(whitespaceRegex).filter { it.isNotEmpty() }) {
            widest = maxOf(widest, measurer.measure(AnnotatedString(token), spanStyle).size.width)
        }
    }
    return widest
}

private val whitespaceRegex = Regex("\\s+")

// ---- model ----

private sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Bullets(val items: List<MdItem>) : MdBlock
    data class Numbered(val items: List<MdItem>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data object Rule : MdBlock
    data class Code(val content: String) : MdBlock
    data class Table(
        val aligns: List<TableAlign>,
        val header: List<List<MdSpan>>,
        val rows: List<List<List<MdSpan>>>
    ) : MdBlock
}

/** One list row: nesting depth 0..3 and, for ordered lists, its source number. */
private data class MdItem(val level: Int, val number: String, val spans: List<MdSpan>)

/** Column alignment captured from the delimiter row (`:---`, `:---:`, `---:`). */
private enum class TableAlign { LEFT, CENTER, RIGHT }

private sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Strikethrough(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Link(val text: String, val url: String) : MdSpan
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)$")
private val numberedRegex = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val quoteRegex = Regex("^ {0,3}> ?(.*)$")
private val hrRegex = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
private val tableDelimiterRegex = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

/** Nesting markers for bullet levels 0..3. */
private val bulletMarkers = listOf("•", "◦", "▪", "·")

/** `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, `[label](url)` - plain text between. */
private val inlineRegex = Regex(
    """~~(.+?)~~|\*\*(.+?)\*\*|\*(\S(?:[^*]*?\S)?)\*|`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)"""
)

/**
 * Bare http(s) URLs in plain text (GFM autolinks): no angle brackets
 * needed. CJK ideographs/punctuation/fullwidth forms terminate the URL —
 * they cannot appear unencoded in a real URL, and Chinese prose runs them
 * directly against the link with no space ("…见 https://x.io。下一句").
 */
private val autolinkRegex = Regex(
    """\bhttps?://[^\s<>"`\[\]　-ヿ一-鿿＀-￯]+""",
    RegexOption.IGNORE_CASE
)

/**
 * Sentence punctuation that trails a matched URL is never part of it:
 * "see https://example.com/a." links to .../a and keeps the period as text.
 * A closing paren (ASCII or CJK) survives only when the URL also carries
 * its opener. CJK marks are included because chat replies are mostly
 * Chinese prose, where URLs end with 。、，等 rather than ".".
 */
private fun trimUrlPunctuation(url: String): String {
    var end = url.length
    while (end > "https://".length) {
        val last = url[end - 1]
        if (last in ".,;:!?，。；：！？、") {
            end--
        } else if (last == ')' || last == '）') {
            val body = url.substring(0, end - 1)
            if (body.count { it == '(' } > body.count { it == ')' }) break // paired inside the URL
            end--
        } else if (last == '"' || last == '\'' || last == '”' || last == '’') {
            end--
        } else {
            break
        }
    }
    return url.substring(0, end)
}

/**
 * Splits one span's text around bare http(s) URLs; segments map back
 * through [styled] so bold/italic/strikethrough formatting survives around
 * the autolinked URL (the URL itself takes link styling). Code spans are
 * never passed here - inline code stays plain by design.
 */
private fun splitAutolinks(text: String, styled: (String) -> MdSpan): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    var cursor = 0
    for (match in autolinkRegex.findAll(text)) {
        val url = trimUrlPunctuation(match.value)
        if (url.length <= "https://".length || !isHttpUrl(url)) continue
        if (match.range.first > cursor) out.add(styled(text.substring(cursor, match.range.first)))
        out.add(MdSpan.Link(url, url))
        cursor = match.range.first + url.length
    }
    if (out.isEmpty()) return listOf(styled(text))
    if (cursor < text.length) out.add(styled(text.substring(cursor)))
    return out
}

private fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var bullets: MutableList<MdItem>? = null
    var numbered: MutableList<MdItem>? = null
    var quote: StringBuilder? = null
    var inFence = false
    val fence = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(parseInline(paragraph.toString())))
            paragraph.clear()
        }
    }

    fun flushLists() {
        bullets?.let { blocks.add(MdBlock.Bullets(it)) }
        bullets = null
        numbered?.let { blocks.add(MdBlock.Numbered(it)) }
        numbered = null
    }

    fun flushQuote() {
        quote?.let { blocks.add(MdBlock.Quote(parseInline(it.toString().trimEnd('\n')))) }
        quote = null
    }

    fun flushAll() {
        flushParagraph(); flushLists(); flushQuote()
    }

    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        if (inFence) {
            if (line.startsWith("```")) {
                blocks.add(MdBlock.Code(fence.toString().trimEnd('\n')))
                fence.clear()
                inFence = false
            } else {
                fence.append(lines[i]).append('\n')
            }
            i++
            continue
        }
        // Table: header row containing '|', next line a delimiter with >= 2 columns.
        val delimiterLine = lines.getOrNull(i + 1)
        if (line.contains('|') && delimiterLine != null &&
            tableDelimiterRegex.matches(delimiterLine) && splitTableRow(delimiterLine).size >= 2
        ) {
            flushAll()
            val aligns = splitTableRow(delimiterLine).map(::delimiterAlign)
            val header = splitTableRow(line).map(::parseInline)
            val rows = mutableListOf<List<List<MdSpan>>>()
            var j = i + 2
            while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                rows.add(splitTableRow(lines[j]).map(::parseInline))
                j++
            }
            blocks.add(MdBlock.Table(aligns, header, rows))
            i = j
            continue
        }
        val heading = headingRegex.matchEntire(line)
        val bullet = if (heading == null) bulletRegex.matchEntire(line) else null
        val numItem = if (heading == null && bullet == null) numberedRegex.matchEntire(line) else null
        val quoteLine = if (heading == null && bullet == null && numItem == null) quoteRegex.matchEntire(line) else null
        val level = (line.takeWhile { it == ' ' }.length / 2).coerceIn(0, 3)
        when {
            line.startsWith("```") -> {
                flushAll()
                inFence = true
            }

            line.isBlank() -> flushAll()

            heading != null -> {
                flushAll()
                blocks.add(MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2])))
            }

            hrRegex.matches(line) -> {
                flushAll()
                blocks.add(MdBlock.Rule)
            }

            bullet != null -> {
                flushParagraph(); flushQuote()
                if (numbered != null) flushLists()
                (bullets ?: mutableListOf<MdItem>().also { bullets = it })
                    .add(MdItem(level, "", parseInline(bullet.groupValues[1])))
            }

            numItem != null -> {
                flushParagraph(); flushQuote()
                if (bullets != null) flushLists()
                (numbered ?: mutableListOf<MdItem>().also { numbered = it })
                    .add(MdItem(level, numItem.groupValues[1], parseInline(numItem.groupValues[2])))
            }

            quoteLine != null -> {
                flushParagraph(); flushLists()
                val quoteBuilder = quote ?: StringBuilder().also { quote = it }
                if (quoteBuilder.isNotEmpty()) quoteBuilder.append('\n')
                quoteBuilder.append(quoteLine.groupValues[1])
            }

            else -> {
                flushLists(); flushQuote()
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    // Unterminated fence: never drop content.
    if (inFence && fence.isNotEmpty()) blocks.add(MdBlock.Code(fence.toString().trimEnd('\n')))
    flushParagraph(); flushLists(); flushQuote()
    return blocks
}

/** Splits a table row on '|', dropping one leading/trailing pipe; cells are trimmed. */
private fun splitTableRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.removePrefix("|")
    if (body.endsWith("|")) body = body.removeSuffix("|")
    return body.split("|").map { it.trim() }
}

/** Maps a delimiter cell like ':---', ':---:' or '---:' to a column alignment. */
private fun delimiterAlign(cell: String): TableAlign {
    val left = cell.startsWith(":")
    val right = cell.endsWith(":")
    return if (left && right) TableAlign.CENTER else if (right) TableAlign.RIGHT else TableAlign.LEFT
}

private fun parseInline(text: String): List<MdSpan> {
    if (text.isEmpty()) return listOf(MdSpan.Plain(""))
    val spans = mutableListOf<MdSpan>()
    var cursor = 0
    for (m in inlineRegex.findAll(text)) {
        if (m.range.first > cursor) spans.add(MdSpan.Plain(text.substring(cursor, m.range.first)))
        val groups = m.groupValues
        when {
            groups[1].isNotEmpty() -> spans.add(MdSpan.Strikethrough(groups[1]))
            groups[2].isNotEmpty() -> spans.add(MdSpan.Bold(groups[2]))
            groups[3].isNotEmpty() -> spans.add(MdSpan.Italic(groups[3]))
            groups[4].isNotEmpty() -> spans.add(MdSpan.Code(groups[4]))
            groups[5].isNotEmpty() -> spans.add(MdSpan.Link(groups[5], groups[6]))
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) spans.add(MdSpan.Plain(text.substring(cursor)))
    // Bare http(s) URLs in the leftover text runs become links too, in every
    // context parseInline feeds (paragraphs, list items, table cells, quotes,
    // headings) and inside bold/italic/strikethrough runs; inline code spans
    // stay plain by design.
    return spans.flatMap { span ->
        when (span) {
            is MdSpan.Plain -> splitAutolinks(span.text) { MdSpan.Plain(it) }
            is MdSpan.Bold -> splitAutolinks(span.text) { MdSpan.Bold(it) }
            is MdSpan.Italic -> splitAutolinks(span.text) { MdSpan.Italic(it) }
            is MdSpan.Strikethrough -> splitAutolinks(span.text) { MdSpan.Strikethrough(it) }
            else -> listOf(span)
        }
    }
}

@Composable
private fun List<MdSpan>.annotated(): AnnotatedString {
    // Theme reads happen in composable context; the builder lambda is not one.
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerLow
    return buildAnnotatedString {
        for (span in this@annotated) {
            when (span) {
                is MdSpan.Plain -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is MdSpan.Strikethrough -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough)
                ) { append(span.text) }
                is MdSpan.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                ) { append(" " + span.text + " ") }
                is MdSpan.Link -> {
                    // URL carried as a string annotation; taps are hit-tested
                    // by SelectableLinkText (see ChatUrlTag) instead of the
                    // alpha LinkAnnotation plumbing.
                    pushStringAnnotation(ChatUrlTag, span.url)
                    withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    ) { append(span.text) }
                    pop()
                }
            }
        }
    }
}

/**
 * http(s) links open in the user's external browser (explicit ACTION_VIEW);
 * every other scheme — Org file/id/internal note links, mailto, custom
 * protocols — keeps the existing [androidx.compose.ui.platform.UriHandler]
 * semantics and is never sent to a browser. Internal (not private) so the
 * chat screen's provider source-link rows share the exact same flow.
 */
internal fun openChatLink(context: Context, uriHandler: UriHandler, url: String) {
    if (!isHttpUrl(url)) {
        uriHandler.openUri(url)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (first: ActivityNotFoundException) {
        // No default handler visible: fall back to the resolver chooser so
        // the user can still pick one; only a truly empty system gets a
        // clear, concise failure notice instead of a dead tap.
        try {
            context.startActivity(
                Intent.createChooser(intent, "打开链接").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (second: ActivityNotFoundException) {
            Toast.makeText(context, "没有应用可以打开此链接", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Browser-handled schemes only. */
private fun isHttpUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
