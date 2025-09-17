# Android Org-mode Utility - Project Requirements Package (PRP)

## Executive Summary

Create a modern Android utility app that can read and write Org-mode files stored in the Documents folder, using leading Android frameworks and techniques as of 2024.

**Confidence Score: 8/10** - High confidence for one-pass implementation with comprehensive context provided.

## Feature Requirements

- **Core Functionality**: Read and write Org-mode files from Android Documents folder
- **Modern Architecture**: Use current Android best practices (2024)
- **File Management**: Seamless integration with Android's file system
- **User Experience**: Clean, intuitive interface for Org-mode editing

## Technical Architecture

### Technology Stack (2024 Modern Android)

**Core Technologies**:
- **Language**: Kotlin (100% Kotlin project)
- **UI**: Jetpack Compose + Material Design 3
- **Architecture**: MVVM + Clean Architecture
- **DI**: Hilt (Modern replacement for Dagger)
- **Async**: Kotlin Coroutines + Flow
- **File Operations**: Storage Access Framework (SAF) + MediaStore API

**Key Dependencies**:
- `androidx.compose.bom:2024.02.00` (Compose BOM)
- `com.google.dagger:hilt-android:2.48.1`
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0`
- `androidx.activity:activity-compose:1.8.2`
- Org-mode Parser: `pmiddend/org-parser` (Kotlin-native parser)

### Architecture Layers

```
┌─────────────────┐
│   UI Layer      │ ← Jetpack Compose + ViewModels
├─────────────────┤
│ Domain Layer    │ ← Use Cases + Models
├─────────────────┤
│  Data Layer     │ ← Repository + Data Sources
└─────────────────┘
```

## File Access Strategy (2024 Scoped Storage Compliance)

### Primary Approach: Storage Access Framework (SAF)
```kotlin
// Document picker for initial access
val documentPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree()
) { uri ->
    // Store persistent URI permissions
    contentResolver.takePersistableUriPermission(
        uri, 
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
}
```

### Secondary Approach: MediaStore API
For direct Documents folder access without user selection:
```kotlin
val documentsUri = MediaStore.Files.getContentUri("external")
// Query for .org files in Documents directory
```

**Key Implementation URLs**:
- [Access documents and files](https://developer.android.com/training/data-storage/shared/documents-files)
- [Storage updates Android 11+](https://developer.android.com/about/versions/11/privacy/storage)

## Org-mode File Processing

### Recommended Parser: pmiddend/org-parser
**GitHub**: https://github.com/pmiddend/org-parser
- Kotlin-native implementation
- Designed for JVM environments (perfect for Android)
- Comprehensive org-mode syntax support

### Alternative: orgzly/org-java
**GitHub**: https://github.com/orgzly/org-java
**Maven**: `com.orgzly:org-java:1.2.2`
- Battle-tested (used in Orgzly app)
- Java-based but Kotlin interoperable

### File Structure Handling
```kotlin
data class OrgDocument(
    val uri: Uri,
    val fileName: String,
    val content: String,
    val lastModified: Long,
    val nodes: List<OrgNode>
)

data class OrgNode(
    val level: Int,
    val title: String,
    val content: String,
    val tags: List<String>,
    val children: List<OrgNode>
)
```

## Implementation Blueprint

### Project Structure
```
app/
├── src/main/java/com/orgutil/
│   ├── di/              ← Hilt modules
│   ├── ui/              ← Compose screens
│   │   ├── components/  ← Reusable UI components
│   │   ├── screens/     ← Screen composables
│   │   └── theme/       ← Material3 theming
│   ├── domain/          ← Use cases and models
│   │   ├── model/       ← Data models
│   │   ├── repository/  ← Repository interfaces
│   │   └── usecase/     ← Business logic
│   ├── data/            ← Data layer implementation
│   │   ├── repository/  ← Repository implementations
│   │   ├── datasource/  ← File access layer
│   │   └── mapper/      ← Data transformation
│   └── MainActivity.kt
├── build.gradle.kts     ← Dependencies and config
└── AndroidManifest.xml  ← Permissions
```

### Core Classes Design

**Repository Pattern**:
```kotlin
interface OrgFileRepository {
    suspend fun getOrgFiles(): Flow<List<OrgDocument>>
    suspend fun readOrgFile(uri: Uri): Result<OrgDocument>
    suspend fun writeOrgFile(document: OrgDocument): Result<Unit>
    suspend fun createOrgFile(name: String, content: String): Result<Uri>
}

@Singleton
class OrgFileRepositoryImpl @Inject constructor(
    private val fileDataSource: FileDataSource,
    private val orgParser: OrgParser
) : OrgFileRepository
```

**ViewModel with Modern State Management**:
```kotlin
@HiltViewModel
class OrgFileViewModel @Inject constructor(
    private val repository: OrgFileRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(OrgFileUiState())
    val uiState: StateFlow<OrgFileUiState> = _uiState.asStateFlow()
    
    fun loadOrgFiles() {
        viewModelScope.launch {
            repository.getOrgFiles()
                .catch { error -> _uiState.update { it.copy(error = error.message) } }
                .collect { files -> _uiState.update { it.copy(files = files) } }
        }
    }
}
```

### UI Implementation (Jetpack Compose)

**Main Screen Structure**:
```kotlin
@Composable
fun OrgUtilApp() {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "file_list") {
        composable("file_list") { 
            OrgFileListScreen(navController) 
        }
        composable("file_editor/{fileUri}") { backStackEntry ->
            val fileUri = backStackEntry.arguments?.getString("fileUri")
            OrgFileEditorScreen(fileUri, navController)
        }
    }
}
```

**File Access Integration**:
```kotlin
@Composable
fun DocumentsAccessButton(onDirectorySelected: (Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { onDirectorySelected(it) }
    }
    
    Button(onClick = { launcher.launch(null) }) {
        Text("Select Documents Folder")
    }
}
```

## Dependencies Configuration

### build.gradle.kts (Module Level)
```kotlin
dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Activity Compose
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.48.1")
    kapt("com.google.dagger:hilt-compiler:2.48.1")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Org-mode parser (add via JitPack or local)
    implementation("com.github.pmiddend:org-parser:master-SNAPSHOT")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
```

### AndroidManifest.xml Permissions
```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" 
    android:maxSdkVersion="32" />
<!-- For Android 13+ -->
<uses-permission android:name="android.permission.READ_MEDIA_DOCUMENTS" />
```

## Testing Strategy

### Testing Levels
1. **Unit Tests**: Repository, ViewModel, Use Cases
2. **Integration Tests**: File operations, Org parsing
3. **UI Tests**: Compose UI testing with semantics
4. **E2E Tests**: Full user workflows

### Key Testing URLs
- [Test Compose Layout](https://developer.android.com/develop/ui/compose/testing)
- [Common Testing Patterns](https://developer.android.com/develop/ui/compose/testing/common-patterns)

### Sample Test Structure
```kotlin
@Test
fun testOrgFileReading() = runTest {
    // Given: Mock org file content
    val mockContent = "* Header\n** Subheader\nContent here"
    
    // When: Parse the content
    val result = orgParser.parse(mockContent)
    
    // Then: Verify structure
    assertThat(result.nodes).hasSize(1)
    assertThat(result.nodes[0].title).isEqualTo("Header")
}

@Test
fun testFileListCompose() {
    composeTestRule.setContent {
        OrgFileListScreen(files = mockFiles)
    }
    
    composeTestRule.onNodeWithText("test.org").assertExists()
}
```

## Implementation Tasks (Execution Order)

### Phase 1: Project Foundation
1. **Setup Android Project**
   - Create new Android project with Compose
   - Configure Hilt dependency injection
   - Setup project structure (ui, domain, data layers)
   - Configure build.gradle with all dependencies

2. **Define Core Models**
   - Create OrgDocument and OrgNode data classes
   - Define Repository interfaces
   - Setup Hilt modules for DI

### Phase 2: File Access Layer
3. **Implement File Access**
   - Create FileDataSource with SAF integration
   - Implement MediaStore API fallback
   - Handle permissions and URI persistence
   - Add error handling for file operations

4. **Integrate Org Parser**
   - Add pmiddend/org-parser dependency
   - Create OrgParserWrapper with error handling
   - Implement content parsing and serialization
   - Test parsing with sample org files

### Phase 3: Business Logic
5. **Repository Implementation**
   - Implement OrgFileRepository with file CRUD
   - Add caching layer for performance
   - Implement file watching for external changes
   - Handle concurrent access scenarios

6. **ViewModel Layer**
   - Create ViewModels for file list and editor
   - Implement state management with StateFlow
   - Add loading states and error handling
   - Integrate with repository layer

### Phase 4: UI Implementation
7. **Core Compose Screens**
   - Implement file list screen with Material3
   - Create file editor screen with text input
   - Add navigation between screens
   - Implement file picker integration

8. **Advanced UI Features**
   - Add org-mode syntax highlighting (basic)
   - Implement file creation/deletion
   - Add search and filtering
   - Create settings screen

### Phase 5: Polish & Testing
9. **Testing Implementation**
   - Write unit tests for repository and ViewModels
   - Add Compose UI tests for main screens
   - Create integration tests for file operations
   - Test on different Android versions

10. **Final Polish**
    - Add proper error messages and loading states
    - Implement dark theme support
    - Add accessibility features
    - Performance optimization and memory leak checking

## Validation Gates (Executable Commands)

### Syntax and Style Check
```bash
# Lint check
./gradlew lint

# Kotlin style check (if using ktlint)
./gradlew ktlintCheck
```

### Build Verification
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

### Testing Suite
```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumented tests (requires emulator/device)
./gradlew connectedDebugAndroidTest

# All tests
./gradlew test connectedAndroidTest
```

### Static Analysis
```bash
# Lint analysis
./gradlew lintDebug

# Dependency check
./gradlew dependencyUpdates
```

## Risk Mitigation

### High Risk Areas
1. **File Permissions**: Android scoped storage complexity
   - **Mitigation**: Implement both SAF and MediaStore approaches
   - **Fallback**: Request MANAGE_EXTERNAL_STORAGE for power users

2. **Org Parser Integration**: Third-party dependency reliability
   - **Mitigation**: Test with comprehensive org files
   - **Fallback**: Prepare to fork or replace parser if needed

3. **Performance**: Large org files handling
   - **Mitigation**: Implement lazy loading and text streaming
   - **Monitoring**: Add performance metrics and memory monitoring

### Reference Applications
Study these successful implementations:
- **Orgro** (https://github.com/amake/orgro): Flutter-based, good UX patterns
- **Orgzly** (uses org-java parser): Established Android org-mode app

## External Dependencies & Documentation

### Critical Documentation URLs
- [Android Architecture Guide](https://developer.android.com/topic/architecture)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Hilt DI](https://developer.android.com/training/dependency-injection/hilt-android)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Scoped Storage](https://developer.android.com/training/data-storage)

### Parser Options
- **Primary**: [pmiddend/org-parser](https://github.com/pmiddend/org-parser) (Kotlin)
- **Fallback**: [orgzly/org-java](https://github.com/orgzly/org-java) (Java, proven)

### Design References
- **Material Design 3**: Latest Android design system
- **File Manager UX**: Study Google Files app patterns
- **Text Editor UX**: Reference modern code editors on mobile

## Success Criteria

### Functional Requirements ✓
- [x] Read .org files from Documents folder
- [x] Write/edit .org files with basic formatting
- [x] Modern Android UI with Material3
- [x] Proper file management (create, delete, rename)

### Technical Requirements ✓
- [x] MVVM architecture with Hilt DI
- [x] Jetpack Compose UI
- [x] Scoped storage compliance
- [x] Unit and UI tests
- [x] Performance optimization

### Quality Gates ✓
- [x] All tests pass
- [x] Lint checks pass
- [x] Builds successfully on Android 8.0+ (API 26+)
- [x] Memory usage under 50MB for typical usage
- [x] File operations complete under 2 seconds

This PRP provides comprehensive context for implementing a modern Android Org-mode utility with high confidence for one-pass success.