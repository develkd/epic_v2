# CLAUDE.md – EPIC v2

Leitfaden für Claude Code (und Menschen) in diesem Repository. Die ausführliche Architektur-Konzeption steht in
[`docs/architektur.md`](docs/architektur.md); diese Datei fasst sie zusammen und legt die verbindlichen Prinzipien und
Arbeitsregeln fest.

> **Stand:** Konzeption vom Nutzer bestätigt, noch kein Produktivcode. Künftig gilt: Neue, noch nicht bestätigte
> Vorschläge werden mit **[Annahme]** markiert, ausstehende Entscheidungen mit **[Offen]**.

## 1. Ziel und Umfang

EPIC („Every-Position-In-Charge“) ist eine App, mit der man **Orte merkt** (z. B. wo Auto oder Fahrrad steht), sie auf
einer Karte wiederfindet, dorthin navigiert und sie mit anderen teilt.

`epic_v2` ist **Redesign und Rewrite in Kotlin** der Vorlage [develkd/epic](https://github.com/develkd/epic)
(Java, 2017, Android SDK 25, nur lesen). Ziel ist **funktionale Parität** mit der Vorlage auf einem modernen, testbaren
Stack – keine 1:1-Übersetzung des Codes.

- **Plattform:** zuerst Android (APK, gebaut per Gradle/GitHub Actions); **später iOS** über Kotlin Multiplatform.
  Fachlogik deshalb von Anfang an plattformneutral (kein Android, kein `java.*` im fachlichen Kern).
- **Neue App-ID** – keine Übernahme der Daten aus der alten App. App-ID und Paket-Basis: `de.master.kd.epic2`.
- **Sprachen der App:** Deutsch, Türkisch, Englisch.

| Fachfunktion | v2 |
|---|---|
| Karte (Google Maps, in der App) mit eigenem Standort und Markern aller gemerkten Orte | MVP |
| Aktuellen Standort als Ort merken (Titel, Beschreibung, **mehrere Fotos**) | MVP |
| Ort bearbeiten / löschen (aus Karte und Liste) | MVP |
| Liste aller Orte (Fotos, Titel, Datum, Beschreibung) | MVP |
| Ort teilen (Text mit Google-Maps-Link und Adresse) | MVP |
| Route zum Ort über die Google-Maps-App | MVP |
| Eingehende Google-Maps-Links öffnen → **Bearbeiten-Formular**, vorbefüllt | MVP |
| Sicherung/Synchronisation – **standardmäßig aus**, vom Nutzer einschaltbar | nach MVP |
| Karten-Vorschaubild | entfällt |
| Navigation-Drawer der Vorlage | entfällt |

## 2. Verbindliche Prinzipien

1. **Vertical Slice Architecture** – Code wird nach Fachfunktionen geschnitten, nicht nach technischen Schichten. Jeder
   Slice enthält alles für seine Funktion (UI, ViewModel, Use Case, ggf. eigener Adapter).
2. **Screaming Architecture (Uncle Bob)** – die Paketstruktur „schreit“ die Fachlichkeit: Wer den Verzeichnisbaum sieht,
   erkennt, was die App tut (`rememberplace`, `shareplace` …), nicht welches Framework sie nutzt.
3. **Domain-Driven Design** – Ubiquitous Language (Glossar in `docs/architektur.md`), ein Aggregat `Place`, Value
   Objects (`Coordinate`, `PlaceTitle`, `PhotoRef` …) mit Validierung im Konstruktor, Repository als Port.
4. **SOLID** – kleine Klassen mit einer Verantwortung, Abhängigkeiten auf Interfaces (Ports), Implementierungen
   austauschbar; Fachlogik hängt nie von Framework-Code ab (Dependency Rule aus Clean Architecture).
5. **Clean Code** – sprechende Namen, kleine Funktionen, keine Kommentare als Ersatz für klaren Code, keine toten
   Codepfade, keine Magic Numbers.
6. **Test-Driven Development** – Red → Green → Refactor: erst ein fehlschlagender Test, dann minimaler Code, dann
   aufräumen. Kein Produktivcode ohne vorherigen Test.
7. **Minimale Änderungen** – nur den Code ändern, der für die Aufgabe nötig ist. Kein „Aufräumen nebenbei“, keine
   unaufgeforderten Umformatierungen; Verbesserungsideen stattdessen als Vorschlag melden.

## 3. Zielarchitektur (Kurzfassung)

Zum Start **ein Gradle-Modul `:app`**, gegliedert in Pakete pro Slice. Für iOS wird der plattformneutrale Teil später in
ein KMP-Modul `:shared` verschoben – die Slices bleiben dabei erhalten.

```
de/master/kd/epic2/
  place/              Gemeinsamer fachlicher Kern: Aggregat Place, Value Objects, PlaceRepository (Port)
  rememberplace/      Slice: Ort am aktuellen Standort merken
  editplace/          Slice: Ort bearbeiten (auch Ziel eingehender Links)
  deleteplace/        Slice: Ort löschen (inkl. Fotos)
  listplaces/         Slice: Liste aller Orte
  showmap/            Slice: Karte mit eigenem Standort und Markern
  shareplace/         Slice: Ort teilen
  routetoplace/       Slice: Route über Google Maps starten
  receivegeolink/     Slice: eingehenden Google-Maps-Link auswerten
  backup/             Slice: Sicherung (opt-in) – nach MVP
  location/           Fähigkeit: Standort und Berechtigungen
  infrastructure/     Room-DB, Dateispeicher, Google-Maps-Links, Koin-Module
  app/                Application, MainActivity, Navigation, Theme
```

Innerhalb eines Slice: `…Screen` (Compose) → `…ViewModel` (StateFlow) → `…UseCase` → Ports aus `place/` bzw. dem Slice.
Use Cases und `place/` sind reines Kotlin (keine Android-Imports). Slices rufen sich nicht gegenseitig auf; Gemeinsames
liegt in `place/`, `location/` oder `infrastructure/`.

**Tech-Stack:** Kotlin 2.x, Jetpack Compose + Material 3, Navigation Compose, **Koin** (DI, auch für KMP/iOS geeignet),
Coroutines/Flow, **Room** (KMP-fähig, KSP), `kotlinx-datetime`, Play Services Location, **Maps Compose (Google Maps)**,
Coil 3. **minSdk = aktuelle Android-Version minus 5** (Stand Ende 2026 voraussichtlich Android 12 / API 31 – beim
Projektstart prüfen), targetSdk = aktuell.

**Build:** Gradle Wrapper, Kotlin DSL, Version Catalog `gradle/libs.versions.toml`. `./gradlew build` kompiliert, prüft
(ktlint, detekt, Android Lint) und testet. GitHub Actions baut bei jedem PR eine **Debug-APK mit Build-Version** im
Namen und stellt sie als Download bereit (Details in `docs/architektur.md`).

## 4. Datenhaltung

- Lokal in **Room**: Tabellen `place` und `photo` (mehrere Fotos pro Ort); Koordinaten als `REAL`, Zeitstempel als
  Epoch-Millis (UTC).
- Fotos als Dateien im app-internen Speicher (`files/photos/`), in der DB nur der relative Pfad.
- Schema-Änderungen nur über Room-Migrationen mit exportiertem Schema (`schemas/`) – nie „Drop & Recreate“.
- Sicherung/Synchronisation ist **standardmäßig aus**; der Nutzer schaltet sie in den Einstellungen ein. Mechanismus:
  Export/Import einer Sicherungsdatei an einen vom Nutzer gewählten Ort, optional regelmäßig (siehe `docs/architektur.md`).

## 5. Schnittstellen

| Richtung | Schnittstelle |
|---|---|
| Teilen | Share-Text mit Titel, Adresse und `https://www.google.com/maps/search/?api=1&query=<lat>,<lng>` |
| Route | `https://www.google.com/maps/dir/?api=1&destination=<lat>,<lng>` – öffnet die Google-Maps-App (Android und iOS), sonst den Browser |
| Eingehend | Google-Maps-Links (`maps.google.com/?q=…`, `www.google.com/maps/…?query=…`/`destination=…`) und `geo:`-URIs → Bearbeiten-Formular |
| Karte | Google Maps SDK über Maps Compose; später iOS: Google Maps SDK for iOS |
| Extern | Fused Location Provider, Android `Geocoder` (Adresse beim Teilen) |

**Google-Maps-API-Key:** Es wird der bestehende Key der Vorlage weiterverwendet – aber **nie im Repository**. Lokal in
`local.properties` (`MAPS_API_KEY=…`), in CI als GitHub-Secret `MAPS_API_KEY`; Einbindung per Secrets-Gradle-Plugin.

## 6. Teststrategie

- **TDD für jede Änderung** (siehe Prinzipien). Ein PR wird erst gestellt, wenn **alle Tests grün** sind.
- **Unit-Tests** (kotlin.test/JUnit 5, Turbine, `kotlinx-coroutines-test`; Fakes statt Mocks) für `place/`, Use Cases,
  ViewModels, Link-Bau und Link-Parser.
- **Integrationstests**: Room-DAOs und Migrationen (in-memory, Robolectric).
- **UI-Tests**: Compose UI Tests für die Kernabläufe der Slices.
- **Architekturtests** (Konsist): Slices importieren sich nicht gegenseitig; `place/` und Use Cases ohne Android-Imports.

## 7. Konventionen

- **Code auf Englisch** (Bezeichner, Kommentare, Commit-Typen). Doku und PR-Texte auf Deutsch.
- UI-Texte nur über String-Ressourcen in drei Sprachen: `values/` (Englisch, Fallback), `values-de/`, `values-tr/`. Keine hart codierten Texte.
- Offizielle Kotlin Coding Conventions; ktlint und detekt ohne Befunde.
- Unveränderliche `data class`es, `sealed interface` für UI-State/Events, kein `!!`.
- Kein Netzwerk-/Datei-/DB-Zugriff auf dem Main-Thread; Dispatcher per DI.
- Commits: Conventional Commits (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `build:`, `chore:`).

## 8. Arbeitsregeln für Claude

- **Keine Umsetzung ohne Rückfrage:** Vor jeder Aufgabe bzw. jedem PR einen kurzen Plan (Was, welche Slices/Dateien,
  welche Tests) und offene Fragen vorlegen und auf das OK des Nutzers warten.
- **Verbesserungstipps geben:** Bei jeder Aufgabe aktiv Verbesserungen vorschlagen (Architektur, Fachlichkeit, Tests,
  UX) – als Vorschlag, nicht eigenmächtig umsetzen.
- **Nur den zu ändernden Code anfassen** (siehe Prinzip 7).
- **Niemals direkt auf `develop` pushen.** `develop` erhält Änderungen ausschließlich über gemergte Pull Requests –
  auch bei kleinen Korrekturen und beim ersten Commit, **nie per Force-Push**.
- Jede Änderung auf einem **eigenen Branch** von `origin/develop` (`feature/…`, `fix/…`, `docs/…`, `refactor/…`,
  `build/…`) und per **Pull Request gegen `develop`**, erst wenn alle Tests grün sind. PR-Texte auf Deutsch;
  PR-Vorlage beachten, falls vorhanden. Gemergt wird vom Nutzer.
- `develkd/epic` ist **nur Vorlage zum Lesen** – dort nichts ändern.
- **Skill `neue-session`** unter `.claude/skills/neue-session/SKILL.md`: Verlangt der Nutzer eine neue Session, wird
  zuerst eine komplett neue Session erstellt und die Anweisung dort ausgeführt.
- Annahmen und offene Entscheidungen immer kennzeichnen; Architektur-Änderungen in `docs/architektur.md` nachziehen.

## 9. Entscheidungen und offene Punkte

Getroffene Entscheidungen und offene Punkte stehen in [`docs/architektur.md`, Abschnitt 9](docs/architektur.md#9-entscheidungen).
Alle Entscheidungen für den Android-MVP sind getroffen. Die UI-Technik für iOS wird entschieden, wenn iOS ansteht.
