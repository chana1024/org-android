# Org-Android

An Android application for managing and editing Org-mode files on mobile devices.

## Features

- **Org-mode File Management**: Browse, read, and edit Org-mode files stored on your device
- **Full-Text Search**: Search files by name and content with database-backed indexing
- **Favorites**: Quick access to frequently used files
- **Quick Capture**: Widget support for fast note capture
- **Modern UI**: Built with Jetpack Compose and Material Design 3
- **File Rendering**: Native Org-mode syntax rendering with support for headings, lists, and formatting

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: MVVM with Clean Architecture
- **Dependency Injection**: Hilt
- **Database**: Room with FTS (Full-Text Search)
- **Async Operations**: Kotlin Coroutines
- **Navigation**: Navigation Compose
- **Org Parser**: org-java library

## Requirements

- Android API 26+ (Android 8.0 Oreo or higher)
- Target SDK: 34

## Building

```bash
./gradlew assembleDebug
```

For release build:
```bash
./gradlew assembleRelease
```

## Project Structure

```
app/src/main/java/com/orgutil/
├── data/           # Data layer (repositories, data sources, database)
├── di/             # Dependency injection modules
├── domain/         # Domain layer (models, use cases, repository interfaces)
├── ui/             # UI layer (screens, viewmodels, components, theme)
├── widget/         # Home screen widgets
└── worker/         # Background workers for file indexing
```

## Key Components

- **File Browser**: Navigate and manage Org files with subdirectory support
- **File Editor**: View and edit Org files with syntax highlighting
- **Search**: Fast file search powered by SQLite FTS
- **Capture Screen**: Quick note capture with template support
- **Background Indexing**: Automatic file content indexing using WorkManager

## License

[Add your license here]

## Contributing

[Add contributing guidelines here]