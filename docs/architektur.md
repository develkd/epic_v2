# Architektur-Konzeption EPIC v2

Ausführliche Fassung zur [CLAUDE.md](../CLAUDE.md). **[Annahme]** = Vorschlag, noch nicht bestätigt;
**[Offen]** = Entscheidung des Nutzers nötig.

## 1. Analyse der Vorlage (develkd/epic)

### 1.1 Technik heute

- Android-App, **Java**, ein Gradle-Modul `:app`, Android Gradle Plugin 2.3.3, Gradle 3.3, `compileSdk`/`targetSdk` 25,
  `minSdk` 16, Support Library 25 (keine AndroidX), Play Services Maps/Location 11.0.1, DataBinding aktiviert (kaum genutzt).
- Persistenz: eigener `SQLiteOpenHelper` (`EpicDatabase`, DB `de.master.kd.epic.sql.db`, Version 1), handgeschriebenes SQL.
- Ca. 2.400 Zeilen Java in 30 Klassen; Tests: nur generierte Beispieltests.

### 1.2 Bildschirme und Abläufe

| Klasse | Aufgabe |
|---|---|
| `SplashScreen` | 2 s Splash, dann Karte |
| `view.map.EpicMap` | Hauptbildschirm: Google-Karte, eigener Standort, Marker aller Positionen (Marker-Icon = Foto), FAB „Position merken“, Marker-Aktionsmenü (Bearbeiten, Löschen, Teilen, Route, Sync), Menüeintrag „Alle Marker verwalten“; verarbeitet eingehende Maps-Links und Lösch-Broadcasts aus der Liste |
| `view.map.interfaces.MenuBuilder` | animiertes FAB-Aktionsmenü |
| `view.position.PositionEditActivity` | Titel, Beschreibung, Foto (Kamera-Intent, nur Thumbnail), Karten-Vorschaubild; Speichern |
| `view.position.PositionListActivity` + `PositionItemAdatper` | Liste mit Kontextmenü (Bearbeiten, Route, Teilen, Sync, Löschen) |
| `view.navigation.NavigationActivity` | ungenutzte Drawer-Vorlage |

Kommunikation zwischen Activities über `startActivityForResult`, serialisierte `Position`-Objekte und Bitmaps in
Intents sowie `LocalBroadcastManager` (Liste → Karte).

### 1.3 Services

| Klasse | Aufgabe |
|---|---|
| `domain.interfaces.PositionService` | speichern (insert + update), alle laden, löschen, Suche per Koordinate bzw. Geo-String; legt unbekannte eingehende Orte als „Incoming“ an |
| `domain.interfaces.PositionRepository` | CRUD auf Tabelle `position` |
| `services.LocationService` | Berechtigungen, GPS-Dialog, `LocationManager`-Updates (10 s / 10 m), Parser für eingehende Maps-URLs |
| `services.LocationShareService` | Share-Intent mit Maps-Link + Adresse (Geocoder), Route über `google.navigation:` |
| `services.MapThumbnailService` | lädt 200×200 Static-Map per HTTP (synchron auf Main-Thread, StrictMode abgeschaltet) |
| `services.FileHandlingService`, `PictureService` | Bitmaps als PNG in `imageDir` speichern/laden, Marker-Icons skalieren/rotieren |
| `services.EpicMapReceiver` | Receiver für `PROVIDERS_CHANGED` (ohne Wirkung) |

### 1.4 Datenmodell der Vorlage

Tabelle `position`:

| Spalte | Typ | Bemerkung |
|---|---|---|
| `_id` | INTEGER PK AUTOINCREMENT | |
| `title` | TEXT NOT NULL | |
| `description` | TEXT NULL | |
| `latitude`, `longitude` | TEXT NOT NULL | Double als String |
| `map_path`, `picture_path` | TEXT NULL | Dateiname in `imageDir` |
| `create_date` | TEXT NOT NULL | Format `dd.MM.yyyy HH.mm.ss` (Locale GERMANY, ohne Zeitzone) |
| `update_date` | TEXT NULL | wie oben |

### 1.5 Schwächen, die v2 beheben soll

- Gesamte Logik in Activities, keine Schichten-Trennung, keine DI, nicht testbar.
- Netzwerk-IO auf dem Main-Thread (StrictMode `permitAll`), DB-Zugriffe synchron im UI-Thread.
- Berechtigungsprüfung logisch fehlerhaft (`&&` statt `||`, `hasPermission` invertiert).
- `onUpgrade` löscht alle Daten; Koordinaten/Datum als Text; `equals`/`hashCode` mit NPE-Risiko.
- Kamera liefert nur Thumbnail; Rotation hart auf 90° gesetzt.
- Hart codierte UI-Texte; Bitmaps in Intents (Größenlimit).
- **Google-Maps-API-Key im Repository eingecheckt** (`app/src/debug/res/values/google_maps_api.xml`) – in v2 nicht
  übernehmen; Empfehlung an den Nutzer: Key in der Google Cloud Console rotieren/einschränken.
- Static-Maps-Aufruf über `http://` mit veraltetem `sensor`-Parameter und ohne Key.

## 2. Zielarchitektur

### 2.1 Schichten

```
┌──────────────── UI (Compose) ────────────────┐
│ Screens, Komponenten – zustandslos           │
├──────────────── Presentation ────────────────┤
│ ViewModels: UiState (StateFlow), Events      │
├──────────────── Domain (reines Kotlin) ──────┤
│ Modelle, UseCases, Ports (Interfaces)        │
├──────────────── Data / Plattform ────────────┤
│ Repositories, Room, Location, Media, Geocoder│
└──────────────────────────────────────────────┘
```

- Abhängigkeiten zeigen nur nach unten bzw. auf Interfaces der Domain (Dependency Inversion).
- **Unidirectional Data Flow**: UI sendet Events an das ViewModel, ViewModel ruft UseCases, Ergebnis fließt als
  neuer `UiState` zurück. Einmalige Effekte (Share-Intent starten, Navigation) als `Channel`/`SharedFlow`.
- Zwischen Screens werden nur **IDs** übergeben (`positionId`), nie Objekte oder Bitmaps.

### 2.2 Module

| Modul | Inhalt | Abhängig von |
|---|---|---|
| `:app` | `Application`, `MainActivity` (Single-Activity), NavHost, Hilt-Wurzel, Deep-Link-Filter | alle `:feature:*`, `:core:*` |
| `:core:model` | `Position`, `PositionId`, `Coordinate`, `ImageRef` | – |
| `:core:domain` | UseCases (`ObservePositions`, `GetPosition`, `SavePosition`, `DeletePosition`, `ResolveIncomingGeoLink`, `BuildShareContent`), Ports (`PositionRepository`, `LocationProvider`, `ReverseGeocoder`, `ImageStore`, `MapPreviewProvider`) | `:core:model` |
| `:core:database` | Room-DB, `PositionEntity`, `PositionDao`, Migrationen, Schema-Export | `:core:model` |
| `:core:data` | Implementierungen der Ports, Mapper Entity ↔ Modell | `:core:domain`, `:core:database`, `:core:location`, `:core:media` |
| `:core:location` | Fused Location Provider als `Flow<Coordinate>`, Berechtigungs-/Einstellungsstatus, Geocoder | `:core:model` |
| `:core:media` | Foto-Aufnahme-Ziel (FileProvider-URI), Speichern/Laden/Löschen von Bildern, Vorschaubild | `:core:model` |
| `:core:ui` | Material-3-Theme, gemeinsame Composables, Berechtigungs-Dialoge | – |
| `:feature:map` | Karte, Marker, Aktionsmenü, FAB „Position merken“ | `:core:domain`, `:core:ui` |
| `:feature:positions` | Liste, Swipe/Kontextaktionen | `:core:domain`, `:core:ui` |
| `:feature:position-edit` | Formular, Foto, Vorschaubild, Validierung | `:core:domain`, `:core:ui` |

**[Annahme]** Diese Aufteilung ist das Zielbild. Zum Start ist es zulässig, mit `:app`, `:core:*` und einem
Feature-Modul zu beginnen und weitere Feature-Module bei Bedarf abzuspalten – Paketstruktur aber von Anfang an
entsprechend den Modulen anlegen.

### 2.3 Tech-Stack (Vorschlag)

| Bereich | Wahl | Bemerkung |
|---|---|---|
| Sprache | Kotlin 2.x (K2), JVM-Toolchain 17 | |
| UI | Jetpack Compose, Material 3, Navigation Compose (typsichere Routen) | ersetzt XML-Layouts |
| Architektur | AndroidX ViewModel, Lifecycle, `collectAsStateWithLifecycle` | |
| DI | Hilt | **[Offen]** alternativ Koin |
| Nebenläufigkeit | Kotlin Coroutines + Flow | Dispatcher per DI |
| Persistenz | Room (KSP) | Schema-Export, Migrationstests |
| Standort | Play Services Location (Fused Location Provider) | ersetzt `LocationManager` |
| Karte | Maps Compose (Google) | **[Offen]** alternativ MapLibre/osmdroid |
| Bilder | Coil 3 | Laden/Skalieren statt eigener Bitmap-Logik |
| Kamera | `ActivityResultContracts.TakePicture` + FileProvider | volle Auflösung, EXIF-Rotation |
| Zeit | `java.time.Instant` | minSdk 26, sonst Desugaring |
| Secrets | Secrets-Gradle-Plugin (`local.properties`) | Key nie im Repo |
| Qualität | ktlint/Spotless, detekt, Android Lint | in CI verpflichtend |

### 2.4 Build

- Gradle Wrapper, **Kotlin DSL**, Version Catalog `gradle/libs.versions.toml`.
- Convention-Plugins in `build-logic/` (z. B. `epic.android.library`, `epic.android.feature`, `epic.kotlin.library`),
  damit Module schlanke Build-Skripte haben.
- Wichtige Tasks: `./gradlew build`, `./gradlew test`, `./gradlew connectedAndroidTest`, `./gradlew detekt`,
  `./gradlew spotlessCheck` (bzw. `ktlintCheck`).
- Build-Typen `debug`/`release`; Release mit R8. Signatur-Daten nur über CI-Secrets.

## 3. Datenhaltung

### 3.1 Room-Schema v2

```kotlin
@Entity(tableName = "position")
data class PositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double,
    val photoPath: String?,      // relativ zu filesDir/images/
    val mapPreviewPath: String?,
    val createdAt: Long,         // Epoch-Millis UTC
    val updatedAt: Long?,
)
```

- Index auf `(latitude, longitude)` für die Suche bei eingehenden Links.
- `PositionDao` liefert `Flow<List<PositionEntity>>` für Karte und Liste (beide Ansichten aktualisieren sich automatisch –
  ersetzt den LocalBroadcast der Vorlage).
- Löschen einer Position löscht auch ihre Bilddateien (im Repository, transaktional gedacht).
- Koordinatenvergleich mit Toleranz statt String-Gleichheit **[Annahme]**: z. B. ±0,00001° (~1 m).

### 3.2 Übernahme alter Daten **[Offen]**

Nur relevant, wenn v2 die alte App per Update ersetzt (gleiche `applicationId` `de.master.kd.epic` und Signatur).
Dann einmalige Migration: alte DB `de.master.kd.epic.sql.db` lesen, Text-Koordinaten in `Double`, Datum
`dd.MM.yyyy HH.mm.ss` (Europe/Berlin angenommen) in `Instant` umwandeln, Bilder aus `app_imageDir` nach
`files/images/` verschieben. Andernfalls neue `applicationId` und keine Migration.

## 4. Schnittstellen

### 4.1 Eingehende Links

- Intent-Filter `VIEW` für `http(s)://maps.google.com` (wie Vorlage); **[Annahme]** zusätzlich `geo:`-Schema und
  `https://www.google.com/maps`.
- Parser als reine Kotlin-Funktion in `:core:domain` (`GeoLinkParser`), vollständig unit-getestet; liefert
  `Coordinate?` statt String-Splitting.
- `ResolveIncomingGeoLink`: existierende Position zur Koordinate öffnen, sonst neue Position „Incoming“ anlegen
  (Verhalten der Vorlage) – **[Offen]** ob stattdessen erst der Bearbeiten-Dialog erscheinen soll.

### 4.2 Ausgehend

- **Teilen**: `ACTION_SEND`, `text/plain`; Betreff = Titel + Adresse (Reverse-Geocoding, asynchron, mit Fallback
  „Keine Adresse vorhanden“), Text = `https://maps.google.com/?q=<lat>,<lng>`.
- **Route**: `google.navigation:q=<lat>,<lng>` an Google Maps; falls nicht installiert, `geo:<lat>,<lng>?q=<lat>,<lng>`
  an beliebige Karten-App.
- **Einstellungen**: Ortungsdienst über `SettingsClient`/`ResolvableApiException` statt Settings-Activity.

### 4.3 Externe Dienste

- Karten-SDK und Vorschaubild **[Offen]** (siehe offene Entscheidungen). Alle Netzwerkzugriffe über HTTPS und
  hinter Ports (`MapPreviewProvider`), damit austauschbar und testbar.

## 5. Berechtigungen

- `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` zur Laufzeit über Compose-Permission-Handling; App bleibt ohne
  Berechtigung nutzbar (Liste, manuelles Anlegen **[Offen]**).
- Kamera ohne `CAMERA`-Berechtigung über `TakePicture`-Contract; `WRITE_EXTERNAL_STORAGE` entfällt (nur
  app-interner Speicher).
- `INTERNET`/`ACCESS_NETWORK_STATE` nur für Karte/Vorschaubild.

## 6. Teststrategie

| Ebene | Werkzeuge | Gegenstand |
|---|---|---|
| Unit (JVM) | JUnit 5, kotlin.test/AssertJ, MockK, Turbine, coroutines-test | Domain, UseCases, `GeoLinkParser`, Mapper, ViewModels |
| Integration | Room in-memory (Robolectric), `MigrationTestHelper` | DAO, Migrationen, Datenübernahme |
| UI | Compose UI Test (Robolectric bzw. instrumentiert) | Position merken, bearbeiten, löschen, Liste, Teilen-Effekt |
| Statisch | detekt, ktlint/Spotless, Android Lint | jeder PR |

Regeln: Neue Logik nur mit Tests; Fakes für Ports (`FakePositionRepository`, `FakeLocationProvider`) in einem
`testing`-Quellset bzw. Modul `:core:testing` **[Annahme]**; keine echten Netzwerk-/Standortzugriffe in Tests.

## 7. Umsetzungsreihenfolge (Vorschlag)

1. Projektgerüst: Gradle (Kotlin DSL, Version Catalog, build-logic), `:app` mit Compose-Theme, CI-Workflow.
2. `:core:model`, `:core:domain`, `:core:database`, `:core:data` mit Tests.
3. `:feature:positions` (Liste) und `:feature:position-edit` ohne Karte/Foto.
4. `:core:location` + `:feature:map` (Karte, eigener Standort, Marker, Position merken).
5. Foto und Vorschaubild (`:core:media`).
6. Teilen, Route, eingehende Links.
7. Optional: Datenübernahme aus v1, Sync.

## 8. Offene Entscheidungen

| # | Thema | Optionen | Vorschlag |
|---|---|---|---|
| 1 | Kartenanbieter | Google Maps (Key, Play Services) / MapLibre bzw. osmdroid (OSM, kein Key) | Google Maps (Parität, Deep-Links) |
| 2 | Karten-Vorschaubild | Static-Maps-API / Snapshot der Karte / weglassen | Snapshot der Karte (kein zusätzlicher Key) |
| 3 | Datenübernahme v1 | gleiche `applicationId` + Migration / Neuinstallation | klären, ob v1 produktiv installiert ist |
| 4 | Sync/Backup | entfällt / Auto Backup / Export-Import / Backend | Auto Backup im MVP, Rest später |
| 5 | DI | Hilt / Koin | Hilt |
| 6 | minSdk | 24 / 26 / höher | 26 |
| 7 | Plattform | nur Android / Kotlin Multiplatform | nur Android, Domain KMP-tauglich halten |
| 8 | Fotos | ein Foto in voller Auflösung / mehrere | ein Foto, volle Auflösung |
| 9 | Sprachen | Deutsch / Deutsch + Englisch | Deutsch + Englisch über Ressourcen |
| 10 | Eingehender Link | sofort als „Incoming“ speichern / erst Bearbeiten-Dialog | Bearbeiten-Dialog vorbefüllt |
