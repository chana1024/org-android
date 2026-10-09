package com.orgutil.domain.usecase

import android.net.Uri
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddToCaptureFileUseCase @Inject constructor(
    private val repository: OrgFileRepository,
    private val agendaParser: OrgAgendaParser
) {
    /** Legacy plain capture — unchanged behavior, same output as before. */
    suspend operator fun invoke(content: String): Result<Unit> =
        invoke(content, CaptureOptions())

    /**
     * Capture with optional decorations (TODO keyword / SCHEDULED timestamp /
     * STYLE=habit repeat). The shared [CaptureEntryFormatter] builds one
     * valid heading; a structured capture is additionally re-parsed through
     * the real agenda parser BEFORE anything is written, so the keyword,
     * scheduled date/time, repeater and STYLE land exactly as chosen or the
     * write is refused. The whole read-append-verify sequence runs
     * non-cancellable: a dialog dismissed mid-save completes exactly one
     * write instead of leaving a half-reported one a retry would duplicate.
     */
    suspend operator fun invoke(content: String, options: CaptureOptions): Result<Unit> {
        return try {
            withContext(NonCancellable) {
                CaptureEntryFormatter.validate(content, options)?.let { reason ->
                    return@withContext Result.failure<Unit>(IllegalArgumentException(reason))
                }
                val formattedContent = CaptureEntryFormatter.format(content, options)

                if (!options.isPlain) {
                    verifyStructuredEntry(formattedContent, content, options)
                }

                // 先记录当前文件大小（如果存在）
                val originalSize = try {
                    repository.getCaptureFileSize()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    0L
                }

                // 执行追加操作 — a swallowed repository failure must never
                // reach the size check and masquerade as success.
                repository.appendToCaptureFile(formattedContent).getOrThrow()

                // 验证文件是否真的更新了
                val newSize = repository.getCaptureFileSize()
                if (newSize <= originalSize) {
                    throw Exception("文件可能未成功更新，请检查存储权限")
                }

                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse-back pre-write check: the formatted heading must re-read through
     * [OrgAgendaParser] as exactly the chosen options — todo keyword (null
     * keeps the Inbox rule `todo == null`), SCHEDULED date and time, and the
     * habit repeater/window/STYLE when one was chosen (or no habit when not).
     */
    private fun verifyStructuredEntry(formatted: String, content: String, options: CaptureOptions) {
        val entry = agendaParser
            .parseFile(Uri.EMPTY, INBOX_FILE_NAME, formatted)
            .firstOrNull()
            ?: error(PARSEBACK_FAILED)

        check(entry.todo == CaptureEntryFormatter.expectedParsedTodo(content, options)) {
            PARSEBACK_FAILED
        }
        check(entry.scheduled == options.scheduledDate) { PARSEBACK_FAILED }
        val expectedTime = options.scheduledDate?.let { options.scheduledTime }
        check(entry.scheduledTime == expectedTime) { PARSEBACK_FAILED }

        val habit = options.habit
        if (habit == null) {
            check(entry.habit == null) { PARSEBACK_FAILED }
        } else {
            val parsed = checkNotNull(entry.habit) { PARSEBACK_FAILED }
            check(
                parsed.scheduled == options.scheduledDate &&
                    parsed.srType == habit.repeaterType &&
                    parsed.srDays == habit.repeaterDays &&
                    parsed.drDays == habit.deadlineDays
            ) { PARSEBACK_FAILED }
        }
    }

    private companion object {
        const val INBOX_FILE_NAME = "inbox.org"
        const val PARSEBACK_FAILED =
            "保存前校验失败：生成的内容未按所选 TODO/SCHEDULED/习惯 解析，已取消写入"
    }
}
