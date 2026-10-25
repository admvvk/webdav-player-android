# WebDAV Player for Android

Простой Android-плеер для стриминга видео/аудио прямо с WebDAV-сервера.

## Стек
- Kotlin + Jetpack Compose
- Navigation Compose
- Media3 / ExoPlayer для воспроизведения
- OkHttp для WebDAV (PROPFIND) и HTTP Basic auth

## MVP-функционал
1. Экран подключения: URL сервера, логин, пароль (Basic auth).
2. Список файлов/папок (WebDAV PROPFIND, Depth: 1), навигация по каталогам.
3. Воспроизведение видео/аудио файлов напрямую с сервера (стриминг, без
   промежуточного скачивания).

## Требования
- minSdk 26 (Android 8.0)
- compileSdk / targetSdk 34

## Сборка (Docker, без установки Android SDK на хосте)
```
docker run --rm --volume $(pwd):/project -w /project mingc/android-build-box \
  ./gradlew assembleDebug
```
APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

## Инфраструктура разработки
- build-VM (Debian 13, `dock`): Docker-хост для сборки/подписи APK.
- test-VM (Ubuntu 24.04, `android-dev`): headless Android-эмулятор
  (nested KVM) для тестирования через adb.
- Обе машины имеют доступ к этому репозиторию по SSH deploy key.
