package com.orgutil.domain.agenda

import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgAgendaParser @Inject constructor() {

    fun parseFile(uri: Uri, fileName: String, content: String): List<OrgAgendaEntry> {
        val fileTags = parseFileTags(content)
        val flatEntries = parseFlatEntries(uri, fileName, content, fileTags)
        return buildTree(flatEntries)
    }

    private fun parseFlatEntries(
        uri: Uri,
        fileName: String,
        content: String,
        fileTags: Set<String>
    ): List<OrgAgendaEntry> {
        val matches = HEADLINE_REGEX.findAll(content).toList()
        return matches.mapIndexed { index, match ->
            val nextOffset = matches.getOrNull(index + 1)?.range?.first ?: content.length
            val subtree = content.substring(match.range.first, nextOffset)
            val stars = match.groupValues[1]
            val remainder = match.groupValues[2].trim()
            val parsed = parseHeadlineRemainder(remainder)
            val headlineLine = match.value
            val titleOffset = headlineLine.indexOf(parsed.title)
                .takeIf { it >= 0 }
                ?.let { match.range.first + it }
                ?: match.range.first
            OrgAgendaEntry(
                uri = uri,
                fileName = fileName,
                level = stars.length,
                todo = parsed.todo,
                title = parsed.title,
                priority = parsed.priority,
                tags = fileTags + parsed.tags,
                scheduled = parsePlanningDate(subtree, "SCHEDULED"),
                deadline = parsePlanningDate(subtree, "DEADLINE"),
                timestamp = parsePlainTimestampDate(subtree),
                sourceOffset = match.range.first,
                titleOffset = titleOffset
            )
        }
    }

    private fun buildTree(flatEntries: List<OrgAgendaEntry>): List<OrgAgendaEntry> {
        if (flatEntries.isEmpty()) return emptyList()
        val result = mutableListOf<MutableAgendaEntry>()
        val stack = mutableListOf<MutableAgendaEntry>()

        flatEntries.forEach { entry ->
            val mutable = MutableAgendaEntry(entry)
            while (stack.isNotEmpty() && stack.last().entry.level >= entry.level) {
                stack.removeAt(stack.lastIndex)
            }
            if (stack.isEmpty()) {
                result += mutable
            } else {
                stack.last().children += mutable
            }
            stack += mutable
        }

        return result.map { it.toEntry() }
    }

    private fun parseHeadlineRemainder(remainder: String): ParsedHeadline {
        val rawTags = TAGS_REGEX.find(remainder)?.groupValues?.get(1).orEmpty()
        val tags = rawTags.split(":").filter { it.isNotBlank() }.toSet()
        val withoutTags = if (rawTags.isNotBlank()) {
            remainder.removeSuffix(":$rawTags:").trimEnd()
        } else {
            remainder
        }

        var remaining = withoutTags
        val todo = remaining.substringBefore(" ").takeIf { it in TODO_KEYWORDS }
        if (todo != null) {
            remaining = remaining.removePrefix(todo).trimStart()
        }

        val priority = PRIORITY_REGEX.find(remaining)?.groupValues?.get(1)
        if (priority != null) {
            remaining = remaining.replaceFirst(PRIORITY_REGEX, "").trimStart()
        }

        return ParsedHeadline(
            todo = todo,
            priority = priority,
            title = remaining,
            tags = tags
        )
    }

    private fun parseFileTags(content: String): Set<String> {
        return FILETAGS_REGEX.find(content)
            ?.groupValues
            ?.get(1)
            ?.split(":")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    private fun parsePlanningDate(subtree: String, keyword: String): LocalDate? {
        val regex = Regex("""$keyword:\s*<(\d{4}-\d{2}-\d{2})[^>]*>""")
        val date = regex.find(subtree)?.groupValues?.get(1) ?: return null
        return LocalDate.parse(date, DATE_FORMAT)
    }

    private fun parsePlainTimestampDate(subtree: String): LocalDate? {
        return ACTIVE_TIMESTAMP_REGEX.findAll(subtree)
            .firstOrNull { match -> !isPlanningTimestamp(subtree, match.range.first) }
            ?.groupValues
            ?.get(1)
            ?.let { LocalDate.parse(it, DATE_FORMAT) }
    }

    private fun isPlanningTimestamp(subtree: String, timestampStart: Int): Boolean {
        val lineStart = subtree.lastIndexOf('\n', startIndex = timestampStart).let { index ->
            if (index == -1) 0 else index + 1
        }
        val prefix = subtree.substring(lineStart, timestampStart).trimEnd()
        return prefix.endsWith("SCHEDULED:") || prefix.endsWith("DEADLINE:")
    }

    private data class ParsedHeadline(
        val todo: String?,
        val priority: String?,
        val title: String,
        val tags: Set<String>
    )

    private class MutableAgendaEntry(
        val entry: OrgAgendaEntry,
        val children: MutableList<MutableAgendaEntry> = mutableListOf()
    ) {
        fun toEntry(parentTitles: List<String> = emptyList()): OrgAgendaEntry {
            return entry.copy(
                parentTitles = parentTitles,
                children = children.map { it.toEntry(parentTitles + entry.title) }
            )
        }
    }

    private companion object {
        private val TODO_KEYWORDS = setOf(
            "TODO",
            "NEXT",
            "WAIT",
            "HOLD",
            "PROJ",
            "AREA",
            "MAYBE",
            "DONE",
            "CANCELLED",
            "DROPPED"
        )
        private val HEADLINE_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        private val FILETAGS_REGEX = Regex("""(?im)^#\+FILETAGS:\s*(.+)$""")
        private val TAGS_REGEX = Regex("""\s+:([A-Za-z0-9_@#%:.-]+):$""")
        private val PRIORITY_REGEX = Regex("""\[#([A-Z])]\s*""")
        private val ACTIVE_TIMESTAMP_REGEX = Regex("""<(\d{4}-\d{2}-\d{2})[^>]*>""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    }
}
