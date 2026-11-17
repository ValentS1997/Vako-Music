# Сторонние компоненты

Лицензия Vako Music применяется к собственному коду проекта. Она не меняет условий сторонних компонентов.

| Компонент | Использование | Лицензия |
| --- | --- | --- |
| [vladimir120307-droid/yandex-music-downloader](https://github.com/vladimir120307-droid/yandex-music-downloader) | Основа переработанных для Android клиента Яндекс Музыки и загрузчика аудио; независимый проект, не официальный SDK Яндекса | MIT, [полный текст и авторство](THIRD_PARTY_LICENSE.md) |
| [AndroidX и Jetpack Compose](https://github.com/androidx/androidx) | Интерфейс, Activity и вспомогательные библиотеки | Apache License 2.0, [текст](licenses/APACHE-2.0.txt) |
| [AndroidX Media3](https://github.com/androidx/media) | Воспроизведение видео | Apache License 2.0, [текст](licenses/APACHE-2.0.txt) |
| [Kotlin](https://github.com/JetBrains/kotlin) | Язык и стандартная библиотека | Apache License 2.0, [текст](licenses/APACHE-2.0.txt) |
| [Gradle Wrapper](https://github.com/gradle/gradle) | Воспроизводимая сборка | Apache License 2.0, [текст](licenses/APACHE-2.0.txt) |

Точные версии прямых зависимостей зафиксированы в `app/build.gradle.kts` и корневом `build.gradle.kts`; транзитивные зависимости определяются Gradle. Android SDK и системные API не включены в исходники репозитория.
