package com.orgutil.data.agent.tools

import com.orgutil.domain.chat.AgentTool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The registry IS the capability boundary: nothing outside this list exists
 * for the agent (no shell, no out-of-tree access). Those belong to a future
 * Pi/Termux sidecar engine with its own authorization surface.
 */
@Singleton
class AgentToolRegistry @Inject constructor(
    listFiles: OrgListFilesTool,
    search: OrgSearchTool,
    readFile: OrgReadFileTool,
    parseOutline: OrgParseOutlineTool,
    writeFile: OrgWriteFileTool,
    createFile: OrgCreateFileTool,
    deleteFile: OrgDeleteFileTool,
    renameFile: OrgRenameFileTool,
    gitSync: GitSyncTool
) : com.orgutil.domain.chat.AgentToolCatalog {

    override val tools: List<AgentTool> = listOf(
        listFiles, search, readFile, parseOutline,
        writeFile, createFile, deleteFile, renameFile,
        gitSync
    )

    override fun byName(name: String): AgentTool? = tools.find { it.name == name }
}
