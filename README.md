# epic_v2
Rewrite epic projekt with kotlin and claude

## Bauen

Voraussetzungen: JDK 17 und Android SDK (z. B. über Android Studio).

```bash
./gradlew build          # kompilieren, ktlint, detekt, Lint, Tests
./gradlew assembleDebug  # Debug-APK unter app/build/outputs/apk/debug/
```

Bei jedem Pull Request baut GitHub Actions eine Debug-APK `epic-v2-<version>-<build>-debug.apk` und stellt sie im
Workflow-Lauf unter „Artifacts“ zum Download bereit. Architektur und Arbeitsregeln: [CLAUDE.md](CLAUDE.md).
