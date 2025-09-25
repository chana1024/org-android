### **功能规格文档：高效文件索引与全文搜索**

**1. 背景与问题**

当前应用在处理大量 `.org` 文件时，文件搜索功能（包括文件名和文件内容）采用的是实时遍历文件系统的方式。当文件数量增多、目录结构变深时，这种方式会导致严重的性能瓶颈，搜索响应缓慢，影响用户体验。

**2. 核心目标**

*   **高性能搜索**: 实现对文件名和文件内容的毫秒级搜索响应。
*   **UI 无卡顿**: 索引和搜索过程不能阻塞主线程，保证应用界面的流畅性。
*   **数据一致性**: 确保搜索索引能及时反映文件系统的实际变化（增、删、改）。
*   **用户可控**: 为用户提供管理索引范围的选项，以平衡索引速度和覆盖范围。

**3. 技术方案**

我们将借鉴 Obsidian 等现代知识管理工具的核心思想（首次全量索引 + 实时增量更新），并结合 Android 平台特性进行实现。

**3.1. 核心技术栈**

*   **数据库**: Android Room Persistence Library (基于 SQLite)。
*   **全文搜索**: SQLite FTS5 (Full-Text Search extension)。
*   **后台任务**: Android WorkManager。

**3.2. 数据库设计**

将在应用的私有目录中创建一个 Room 数据库，包含两张表：

1.  **文件元数据表 (`FileMetadataEntity`)**: 普通表，用于快速进行文件名搜索和存储文件状态。
    *   `path` (String, 主键): 文件绝对路径。
    *   `fileName` (String): 文件名。
    *   `lastModified` (Long): 文件最后修改时间戳，用于增量更新检查。
    *   `size` (Long): 文件大小。

2.  **文件内容索引表 (`FileContentFtsEntity`)**: FTS5 虚拟表，用于高效全文搜索。
    *   `rowid` (Integer): 自动关联到 `FileMetadataEntity` 的 `rowid`。
    *   `content` (String): 文件的全部文本内容。

**3.3. 索引服务 (`FileIndexingService`)**

将使用 `WorkManager` 实现一个后台服务，负责索引的创建和维护。

*   **全量索引 (Full Indexing)**:
    *   **时机**: 应用首次启动、用户手动触发全量重建、或数据库版本升级时。
    *   **流程**: 遍历用户指定的所有文件夹，读取每个 `.org` 文件的元信息和内容，分别存入上述两张表中。

*   **增量索引 (Incremental Indexing)**:
    *   **时机**: 应用切换到前台、用户在应用内保存文件、用户手动下拉刷新时触发。
    *   **流程**:
        1.  快速扫描文件系统，获取所有文件的 `path` 和 `lastModified`。
        2.  与数据库中的记录进行对比：
            *   **新增文件**: 数据库中不存在的路径，执行新增操作。
            *   **修改文件**: 路径存在，但 `lastModified` 时间戳不一致，执行更新操作。
            *   **删除文件**: 数据库中存在，但文件系统中已不存在的路径，执行删除操作。
    *   此过程仅处理有变化的文件，执行速度极快。

**3.4. 搜索逻辑改造**

*   `FileRepository` 将被重构，数据源从直接访问文件系统切换为访问 Room 数据库。
*   **文件名搜索**: 将通过 `DAO` 对 `FileMetadataEntity` 表执行 `LIKE` 查询。
*   **内容搜索**: 将通过 `DAO` 对 `FileContentFtsEntity` 表执行 `MATCH` 查询。
*   `FileListViewModel` 和相关 `UseCase` 将调用新的 `FileRepository` 方法来获取搜索结果。

**4. 用户体验 (UX)**

*   **后台执行与进度反馈**: 首次全量索引将在后台进行，并通过系统通知向用户展示进度，确保 UI 流畅且用户知情。
*   **用户控制**: 在应用的“设置”中，提供一个“索引管理”界面，允许用户：
    *   添加或删除需要被索引的根目录。
    *   手动触发全量索引重建。

**5. 任务分解 (Task Breakdown)**

1.  **环境配置**: 在 `build.gradle.kts` 中添加 Room, Lifecycle, Coroutines, 和 WorkManager 的依赖。
2.  **数据库实体与 DAO**: 创建 `FileMetadataEntity`, `FileContentFtsEntity` 两个实体类，并为其编写 `DAO` (Data Access Object) 接口。
3.  **Room 数据库设置**: 创建继承自 `RoomDatabase` 的 `AppDatabase` 类。
4.  **索引器实现**: 创建一个 `FileIndexerWorker` 类，实现 `WorkManager` 的 `CoroutineWorker`，并在其中包含全量和增量索引的逻辑。
5.  **仓库层重构**: 修改 `FileRepository` 的实现，使其通过 `DAO` 与数据库交互。
6.  **视图模型与 UI**:
    *   更新 `FileListViewModel` 以调用新的搜索逻辑。
    *   在 UI 线程外发起数据库请求。
    *   实现索引进度的通知栏显示。
7.  **设置界面**: (可选，建议) 创建一个新的 `Fragment/Composable` 作为索引管理界面。
8.  **测试**: 编写单元测试来验证 `DAO` 和 `Repository` 的逻辑正确性。
