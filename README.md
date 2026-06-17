# EasyPod

EasyPod is an Android podcast manager for subscriptions, playback, downloads,
SmartPlay queues, widgets, backup/restore, OPML import/export, WebDAV sync, and
legacy library import.

## Build

Use Java 17 or the JBR bundled with Android Studio:

```powershell
.\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
```

For release signing, set all of these environment variables together:

- `EASYPOD_KEYSTORE_FILE`
- `EASYPOD_KEYSTORE_PASSWORD`
- `EASYPOD_KEY_ALIAS`
- `EASYPOD_KEY_PASSWORD`
