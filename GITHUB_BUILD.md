# Build the APK on GitHub (no Android Studio needed)

1. Unzip this source over your v0.5.0 Freqazoid project folder (keeps its logo, res/ and debug-signing/ key).
   Delete `app/src/main/java/com/freakazoid/watch/MonitorActivity.java` and
   `app/src/main/java/com/freqazoid/membrane/AudioSpectrum.kt` if they are there.
2. On github.com: New repository → any name (Private is fine) → Create.
3. On the new repo page: "uploading an existing file" → drag in everything INSIDE the project folder
   (including the `.github` folder) → Commit changes.
4. Open the Actions tab. "Build APK" starts automatically (about 5–10 min). Green check = done.
5. Click the run → Artifacts → freqazoid-apk → you get a zip with `app-debug.apk`.

Install (same APK for both):
- Phone: copy the APK over and open it (allow "install unknown apps"), or `adb install -r app-debug.apk`.
- Galaxy Watch6: Settings → Developer options → ADB debugging + Wireless debugging, then
  `adb connect <watch-ip>:<port>` (use `adb pair` first if asked) and
  `adb -s <watch-ip>:<port> install -r app-debug.apk`.

If the run fails (red X): open it, copy the error lines from "Build debug APK", and paste them to Claude.
The key in debug-signing/ is a public development key — fine for testing, not for the Play Store.
