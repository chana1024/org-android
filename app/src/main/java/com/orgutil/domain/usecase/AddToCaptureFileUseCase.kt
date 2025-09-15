package com.orgutil.domain.usecase

import com.orgutil.domain.repository.OrgFileRepository
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddToCaptureFileUseCase @Inject constructor(
    private val repository: OrgFileRepository
) {
    suspend operator fun invoke(content: String): Result<Unit> {
        return try {
            val formattedContent = formatAsOrgModeHeader(content)
            repository.appendToCaptureFile(formattedContent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatAsOrgModeHeader(content: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val lines = content.lines()
        
        return if (lines.size == 1) {
            // 单行内容，作为一级标题
            "\n* $content\n  :PROPERTIES:\n  :CREATED: $timestamp\n  :END:\n"
        } else {
            // 多行内容，第一行作为标题，其余作为内容
            val title = lines.first()
            val body = lines.drop(1).joinToString("\n") { "  $it" }
            "\n* $title\n  :PROPERTIES:\n  :CREATED: $timestamp\n  :END:\n$body\n"
        }
    }
}