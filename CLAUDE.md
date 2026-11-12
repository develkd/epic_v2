# CLAUDE.md – EPIC v2

Leitfaden für Claude Code (und Menschen) in diesem Repository. Die ausführliche Architektur-Konzeption steht in
[`docs/architektur.md`](docs/architektur.md); diese Datei fasst sie zusammen und legt die Arbeitsregeln fest.

> **Stand:** Konzeption, noch kein Produktivcode. Alles, was mit **[Annahme]** oder **[Offen]** markiert ist, ist noch
> nicht vom Nutzer bestätigt und muss vor bzw. bei der Umsetzung entschieden werden (Liste unten).

## 1. Ziel und Umfang

EPIC („Every-Position-In-Charge“) ist eine **Android-App**, mit der man Orte merkt (z. B. wo das Auto/Fahrrad steht),
sie auf einer Karte wiederfindet, dorthin navigiert und sie mit anderen teilt.

`epic_v2` ist **Redesign und Rewrite in Kotlin** der Vorlage [develkd/epic](https://github.com/develkd/epic)
(Java, 2017, Android SDK 25, nur lesen). Ziel ist **funktionale Parität** mit der Vorlage auf einem modernen,
testbaren Stack – nicht eine 1:1-Übersetzung des Codes.

Funktionsumfang der Vorlage (= Umfang v2, MVP):

| Funktion | Vorlage | v2 |
|---|---|---|
| Karte mit eigener Position (GPS/Netzwerk), Marker aller gespeicherten Orte | ja | MVP |
| Aktuelle Position merken (Titel, Beschreibung, Foto, Karten-Vorschaubild) | ja | MVP |
| Ort bearbeiten / löschen (aus Karte und Liste) | ja | MVP |
| Liste aller Orte (Foto, Karte, Titel, Datum, Beschreibung) | ja | MVP |
| Teilen als Text mit Maps-Link und Adresse (Reverse-Geocoding) | ja | MVP |
| Route/Navigation zu einem Ort über externe Navi-App | ja | MVP |
| Eingehende Maps-Links (`maps.google.com/?q=lat,lng`) öffnen und als Ort „Incoming“ speichern | ja | MVP |
| Synchronisieren | nur Platzhalter („nicht freigeschaltet“) | **[Offen]** |
| Navigation-Drawer (Import, Galerie, …) | ungenutzte Vorlage | entfällt **[Annahme]** |

## 2. Fachliche Domänen

Eine zentrale Entität **Position** (gemerkter Ort) und vier fachliche Bereiche:

- **Positionen** – Anlegen, Ändern, Löschen, Auflisten, Suchen per Koordinate.
- **Standort** – Berechtigungen, Ortungsdienst an/aus, laufende Standort-Updates.
- **Medien** – Foto zur Position, Karten-Vorschaubild, Ablage im App-Speicher.
- **Teilen & Navigation** – Share-Text, Reverse-Geocoding, Route-Intent, eingehende Geo-Links.

Datenmodell (Kotlin, v2):

```kotlin
data class Position(
    val id: PositionId,          // Long, von Room vergeben
    val title: String,           // Pflicht
    val description: String?,
    val coordinate: Coordinate,  // latitude/longitude als Double (Vorlage: TEXT)
    val photo: ImageRef?,        // Datei im App-Speicher
    val mapPreview: ImageRef?,
    val createdAt: Instant,
    val updatedAt: Instant?,
)
```

## 3. Zielarchitektur (Kurzfassung)

- **Clean Architecture light + MVVM/UDF**: `UI (Compose) → ViewModel (StateFlow) → UseCases → Repository-Interfaces`
  im Domain-Layer, Implementierungen im Data-Layer. Domain ist reines Kotlin ohne Android-Abhängigkeiten.
- **Gradle-Module** (Kotlin DSL, Version Catalog `gradle/libs.versions.toml`, Convention-Plugins in `build-logic/`):

```
:app                      Application, Navigation, DI-Wurzel, Manifest/Deep-Links
:core:model               Domänenmodell (reines Kotlin)
:core:domain              UseCases, Repository-/Port-Interfaces (reines Kotlin)
:core:data                Repository-Implementierungen
:core:database            Room (Entities, DAO, Migrationen)
:core:location            Fused Location, Berechtigungen, Geocoder
:core:media               Foto/Vorschaubild speichern & laden
:core:ui                  Theme, gemeinsame Compose-Komponenten
:feature:map              Kartenansicht, Marker, Aktionsmenü
:feature:positions        Liste der Orte
:feature:position-edit    Anlegen/Bearbeiten inkl. Foto
```

- **Tech-Stack-Vorschlag** **[Annahme]**: Kotlin 2.x, Jetpack Compose + Material 3, Navigation Compose,
  Hilt, Coroutines/Flow, Room (KSP), Play Services Location, Maps Compose **[Offen: Kartenanbieter]**, Coil,
  `ActivityResultContracts.TakePicture` (+ FileProvider), `java.time`, minSdk 26 / target = aktuelles SDK.
- **Build**: Gradle Wrapper, `./gradlew build` (Kompilieren, Lint, Tests). Qualität: ktlint (bzw. Spotless) + detekt.

Details, Begründungen und Abläufe: [`docs/architektur.md`](docs/architektur.md).

## 4. Datenhaltung

- Lokal in **Room** (SQLite), Tabelle `position`; Koordinaten als `REAL`, Zeitstempel als Epoch-Millis (UTC).
- Bilder als Dateien im app-internen Speicher (`filesDir/images/`), in der DB nur der relative Pfad.
- Schema-Änderungen ausschließlich über **Room-Migrationen** mit exportiertem Schema (`schemas/`) – kein
  „Drop & Recreate“ wie in der Vorlage.
- Kein Server, kein Konto. **[Offen]** Sync/Backend.

## 5. Schnittstellen

| Richtung | Schnittstelle |
|---|---|
| Eingehend | Deep-Link `http(s)://maps.google.com/?q=<lat>,<lng>` → Ort finden oder als „Incoming“ anlegen; zusätzlich `geo:`-URIs **[Annahme]** |
| Ausgehend | Share-Intent `text/plain`: Betreff = Titel + Adresse, Text = `https://maps.google.com/?q=<lat>,<lng>` |
| Ausgehend | Route: `google.navigation:q=<lat>,<lng>`, Fallback `geo:`-Intent für andere Navi-Apps **[Annahme]** |
| Extern | Android `Geocoder` (Reverse-Geocoding), Fused Location Provider |
| Extern | Karten-SDK + Static-Map-Vorschaubild **[Offen: Anbieter, API-Key-Handling]** |

API-Keys **nie** ins Repository: Einbindung über `local.properties`/CI-Secret
(z. B. Secrets-Gradle-Plugin). Hinweis: Die Vorlage enthält einen eingecheckten Maps-Key – nicht übernehmen.

## 6. Teststrategie

- **Unit-Tests** (JUnit 5, kotlin.test/AssertJ, MockK, Turbine, `kotlinx-coroutines-test`) für Domain, UseCases,
  ViewModels, Parser (Geo-Link) – Pflicht für jede neue Logik.
- **Integrationstests**: Room-DAO und Migrationen (in-memory, Robolectric bzw. instrumentiert).
- **UI-Tests**: Compose UI Tests für Kernabläufe (Ort merken, bearbeiten, löschen, Liste).
- Fakes statt Mocks für Repositories bevorzugen; Domain-Layer ohne Android testbar.
- CI (GitHub Actions) **[Annahme]**: `./gradlew build` auf jedem PR gegen `develop`.

## 7. Konventionen

- Sprache: Code, Bezeichner, Commit-Typen auf **Englisch**; Doku, PR-Texte und UI-Texte (Strings) auf **Deutsch**
  (UI-Texte über `strings.xml`, nie hart codiert).
- Paket-Basis: `de.master.kd.epic` **[Offen: gleiche `applicationId` wie Vorlage?]**.
- Kotlin-Style: offizielle Kotlin Coding Conventions, ktlint/detekt ohne Warnungen.
- Unveränderliche `data class`es, `sealed interface` für UI-State/Events, keine `!!`.
- Kein Netzwerk/Datei-IO auf dem Main-Thread (Dispatchers per DI injizieren).
- Commits: Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `build:`, `chore:`).

## 8. Arbeitsregeln

- **Niemals direkt auf `develop` pushen.** `develop` erhält Änderungen ausschließlich über gemergte Pull Requests –
  auch bei kleinen Korrekturen, auch beim ersten Commit, und **nie per Force-Push**.
- Jede Änderung entsteht auf einem **eigenen Branch** von `origin/develop`
  (`feature/…`, `fix/…`, `docs/…`, `refactor/…`, `build/…`) und geht per **Pull Request gegen `develop`**.
- PR-Texte auf Deutsch; PR-Vorlage beachten, falls vorhanden. Merges macht der Nutzer bzw. erfolgen nach Review.
- `develkd/epic` ist **nur Vorlage zum Lesen** – dort nichts ändern.
- **Skill `neue-session`** unter `.claude/skills/neue-session/SKILL.md`: Verlangt der Nutzer eine neue Session, wird
  zuerst eine komplett neue Session erstellt und die Anweisung dort ausgeführt (Details im Skill).
- Annahmen und offene Entscheidungen in Doku und PRs immer als solche kennzeichnen; Architektur-Änderungen in
  `docs/architektur.md` nachziehen.

## 9. Offene Entscheidungen (Kurzliste)

1. **Kartenanbieter**: Google Maps (Maps Compose, API-Key, Play Services) vs. OpenStreetMap (MapLibre/osmdroid, kein Key).
2. **Karten-Vorschaubild**: Static-Maps-API (Key, Kosten) vs. Snapshot der eingebetteten Karte vs. weglassen.
3. **Datenübernahme**: Gleiche `applicationId` und Migration der alten DB (`de.master.kd.epic.sql.db`, Texte/Datumsformat
   `dd.MM.yyyy HH.mm.ss`) – oder Neuinstallation ohne Übernahme.
4. **Sync/Backup**: entfällt, Android Auto Backup, Export/Import-Datei oder echtes Backend.
5. **DI**: Hilt (Vorschlag) vs. Koin.
6. **minSdk** (Vorschlag 26) und Mindest-Android-Version der Zielgruppe.
7. **Plattform**: nur Android (Vorschlag) oder später Kotlin Multiplatform (iOS).
8. **Foto**: Vollauflösung über FileProvider (Vorschlag) statt Thumbnail wie in der Vorlage; mehrere Fotos pro Ort?
9. **Sprachen**: nur Deutsch oder Deutsch + Englisch.
