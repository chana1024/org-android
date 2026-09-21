package com.orgutil.data.repository

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.domain.agenda.OrgAgenda
import com.orgutil.domain.agenda.OrgAgendaBuilder
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.repository.OrgAgendaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgAgendaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTreeStore: DocumentTreeStore,
    private val parser: OrgAgendaParser,
    private val builder: OrgAgendaBuilder
) : OrgAgendaRepository {

    override suspend fun loadAgenda(): Result<OrgAgenda> = withContext(Dispatchers.IO) {
        runCatching {
            val root = documentTreeStore.requireTreeDocumentFile()
            if (!root.exists() || !root.isDirectory) {
                throw IOException("Document directory not accessible")
            }

            val gtdDir = root.findFile(GTD_DIR_NAME)
                ?.takeIf { it.isDirectory }
                ?: throw IOException("gtd directory not found")

            val entries = GTD_FILE_NAMES.flatMap { fileName ->
                val file = gtdDir.findFile(fileName)?.takeIf { it.isFile } ?: return@flatMap emptyList()
                parser.parseFile(
                    uri = file.uri,
                    fileName = fileName,
                    content = readFile(file)
                )
            }

            builder.build(
                entries = entries,
                goalText = root.findFile(GOAL_FILE_NAME)
                    ?.takeIf { it.isFile }
                    ?.let { readFile(it).trimEnd() }
                    .orEmpty()
            )
        }
    }

    private fun readFile(file: DocumentFile): String {
        return context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } ?: throw IOException("Could not open ${file.name ?: "org file"} for reading")
    }

    private companion object {
        const val GTD_DIR_NAME = "gtd"
        const val GOAL_FILE_NAME = "agenda_goal.org"
        val GTD_FILE_NAMES = listOf(
            "inbox.org",
            "gtd.org",
            "areas.org",
            "projects.org",
            "someday.org",
            "tickler.org",
            "routines.org"
        )
    }
}
