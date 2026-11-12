# Architektur-Konzeption EPIC v2

Ausführliche Fassung zur [CLAUDE.md](../CLAUDE.md). Die Konzeption ist vom Nutzer bestätigt. Künftige, noch nicht
bestätigte Vorschläge werden mit **[Annahme]** markiert, ausstehende Entscheidungen mit **[Offen]**.

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
- **Google-Maps-API-Key im Repository eingecheckt** (`app/src/debug/res/values/google_maps_api.xml` und `release/`).
  v2 verwendet denselben Key weiter, aber nur über `local.properties`/GitHub-Secret (siehe 4.3).
- Static-Maps-Aufruf über `http://` mit veraltetem `sensor`-Parameter und ohne Key.

## 2. Zielarchitektur

### 2.1 Leitprinzipien und ihr Zusammenspiel

| Prinzip | Umsetzung in EPIC v2 |
|---|---|
| Vertical Slice Architecture | Ein Paket pro Fachfunktion; ein Slice enthält Screen, ViewModel, Use Case und slice-eigene Adapter. Neue Funktion = neuer Slice, bestehende Slices bleiben unberührt. |
| Screaming Architecture | Paketnamen sind Fachbegriffe (`rememberplace`, `shareplace`), keine Technikbegriffe (`ui`, `data`, `repository`) auf oberster Ebene. Technik liegt gesammelt in `infrastructure/`. |
| Domain-Driven Design | Ein Bounded Context „Orte merken“; Aggregat `Place` mit Value Objects; Repository als Port im Kern. Bewusst leichtgewichtig: keine Domain Events, kein CQRS. |
| Clean Architecture (Dependency Rule) | Abhängigkeiten zeigen nach innen: Screen → ViewModel → Use Case → `place/`. `place/` und Use Cases kennen weder Android noch Room noch Koin. |
| SOLID | SRP: ein Use Case = eine Aktion. OCP: neue Slices statt Änderung bestehender. LSP/ISP: kleine Ports (`PlaceRepository`, `PhotoStore`, `LocationProvider`, `AddressLookup`, `MapsLinkFactory`). DIP: Use Cases hängen von Ports ab, Implementierungen in `infrastructure/` werden per Koin verdrahtet. |
| Clean Code | Sprechende Namen, Funktionen mit einer Aufgabe, keine Nebenwirkungen in Gettern, keine Magic Numbers, ktlint/detekt ohne Befunde. |
| TDD | Jede Änderung beginnt mit einem fehlschlagenden Test (Red → Green → Refactor). |

### 2.2 Paketstruktur (ein Gradle-Modul `:app`)

```
app/src/main/kotlin/de/kd/epic2/
  place/
    Place.kt                 Aggregat (id, title, description, coordinate, photos, createdAt, updatedAt)
    PlaceId.kt, PlaceTitle.kt, Coordinate.kt, PhotoRef.kt     Value Objects
    PlaceRepository.kt       Port
    PhotoStore.kt            Port
  rememberplace/
    RememberPlaceScreen.kt, RememberPlaceViewModel.kt, RememberPlaceUseCase.kt
  editplace/                 EditPlaceScreen/ViewModel/UseCase (auch für eingehende Links, vorbefüllt)
  deleteplace/               DeletePlaceUseCase (löscht Ort und Fotos)
  listplaces/                ListPlacesScreen/ViewModel, ObservePlacesUseCase
  showmap/                   MapScreen/ViewModel (Maps Compose), Marker, Aktionsmenü
  shareplace/                SharePlaceUseCase, AddressLookup (Port), ShareContent
  routetoplace/              StartRouteUseCase
  receivegeolink/            GeoLinkParser, ReceiveGeoLinkUseCase
  backup/                    (nach MVP) BackupSettings, BackupUseCase
  location/                  LocationProvider (Port), Berechtigungs-Status
  infrastructure/
    database/                Room: EpicDatabase, PlaceEntity, PhotoEntity, DAOs, Migrationen, RoomPlaceRepository
    files/                   FilePhotoStore (files/photos/)
    maps/                    GoogleMapsLinkFactory, Intent-Starter
    location/                FusedLocationProvider, AndroidAddressLookup (Geocoder)
    di/                      Koin-Module
  app/                       EpicApplication, MainActivity, NavHost, Theme, Einstellungen
```

Tests spiegeln die Struktur (`app/src/test/kotlin/.../rememberplace/RememberPlaceUseCaseTest.kt` usw.).

Regeln (per Konsist-Architekturtest abgesichert):
- Ein Slice importiert keinen anderen Slice. Gemeinsames gehört nach `place/`, `location/` oder `infrastructure/`.
- `place/` und alle `…UseCase`-Klassen importieren nichts aus `android.*`, `androidx.*`, `java.*` (Zeit) oder
  `infrastructure/`.
- Navigation zwischen Slices nur in `app/` über Routen mit `PlaceId` – nie Objekte oder Bilder übergeben.

### 2.3 Weg zu iOS (Kotlin Multiplatform)

- Heute: `:app` (Android). `place/`, Use Cases und Link-Parser/-Bau sind bereits plattformneutral.
- Später: Modul `:shared` (KMP, `commonMain`) übernimmt diese Teile slice-weise; Room (KMP), Koin und
  `kotlinx-datetime` funktionieren dort ohne Umbau. Android-spezifisch bleiben Standort, Kamera und Karte.
- iOS-Karte: Google Maps SDK for iOS. UI für iOS (Compose Multiplatform oder SwiftUI) wird entschieden, wenn iOS ansteht.

### 2.4 Tech-Stack

| Bereich | Wahl | Begründung |
|---|---|---|
| Sprache | Kotlin 2.x (K2), JVM-Toolchain 17 | Vorgabe; KMP-fähig |
| UI | Jetpack Compose, Material 3, Navigation Compose | modern, deklarativ, später Compose Multiplatform möglich |
| DI | **Koin** | einfach, ohne Codegenerierung, läuft auch auf iOS (KMP) |
| Nebenläufigkeit | Coroutines + Flow | Standard, KMP-fähig |
| Persistenz | Room (KSP) | KMP-fähig, Migrationstests |
| Zeit | `kotlinx-datetime` | KMP-fähig |
| Standort | Play Services Location (Fused Location Provider) | genauer und stromsparender als `LocationManager` |
| Karte | Google Maps über Maps Compose | Entscheidung: Google Maps bleibt |
| Bilder | Coil 3 | Laden/Skalieren, KMP-fähig |
| Kamera | `ActivityResultContracts.TakePicture` + FileProvider | volle Auflösung, keine Kamera-Berechtigung nötig |
| Secrets | Secrets-Gradle-Plugin | API-Key aus `local.properties`/CI |
| Qualität | ktlint (Spotless), detekt, Android Lint, Konsist | in CI verpflichtend |
| Tests | kotlin.test/JUnit 5, Turbine, coroutines-test, Robolectric, Compose UI Test | siehe Abschnitt 6 |

Mindest-Android-Version: **aktuelle Version minus 5** (Stand Ende 2026 voraussichtlich Android 12 / API 31; beim
Projektstart anhand der dann aktuellen Version festlegen). targetSdk/compileSdk = aktuelle Version.

### 2.5 Build und APK

- Gradle Wrapper, Kotlin DSL, Version Catalog `gradle/libs.versions.toml`.
- `./gradlew build` = Kompilieren + ktlint + detekt + Lint + Unit-Tests.
- **Versionierung:** `versionName` = SemVer aus `gradle.properties` (z. B. `0.1.0`), `versionCode` = laufende
  Build-Nummer (in CI `GITHUB_RUN_NUMBER`, lokal `1`). Die Build-Version wird in der App unter „Info“ angezeigt.
- **GitHub Actions** (`.github/workflows/build.yml`), bei jedem PR gegen `develop`:
  1. Build und alle Tests.
  2. Debug-APK bauen und als Artefakt `epic-v2-<versionName>-<versionCode>-debug.apk` hochladen.
  - `MAPS_API_KEY` kommt aus den Repository-Secrets.
- Signierte Release-APK/Play-Store-Upload: später (Keystore als GitHub-Secret nötig).

## 3. Fachmodell (DDD)

### 3.1 Glossar (Ubiquitous Language)

| Deutsch | Code | Bedeutung |
|---|---|---|
| Ort | `Place` | gemerkte Position mit Titel, optionaler Beschreibung und Fotos |
| Koordinate | `Coordinate` | Breiten-/Längengrad; gültig: −90…90 bzw. −180…180 |
| Titel | `PlaceTitle` | Pflicht, nicht leer, max. 100 Zeichen |
| Foto | `PhotoRef` | Verweis auf eine Bilddatei eines Ortes |
| Ort merken | `RememberPlace` | neuen Ort am aktuellen Standort anlegen |
| Eingehender Link | `GeoLink` | Google-Maps- oder `geo:`-Link, den eine andere App an EPIC übergibt |
| Sicherung | `Backup` | vom Nutzer eingeschaltete Sicherung/Synchronisation |

### 3.2 Aggregat `Place`

```kotlin
data class Place(
    val id: PlaceId?,              // null bis zum ersten Speichern
    val title: PlaceTitle,
    val description: String?,
    val coordinate: Coordinate,
    val photos: List<PhotoRef>,    // mehrere Fotos, Reihenfolge = Anzeige
    val createdAt: Instant,
    val updatedAt: Instant?,
)
```

Invarianten werden in den Value Objects geprüft. Fotos werden nur über das Aggregat hinzugefügt/entfernt
(`withPhotoAdded`, `withPhotoRemoved`), damit Datei und Datenbank konsistent bleiben.

## 4. Schnittstellen

### 4.1 Google-Maps-Links (ausgehend)

Einheitlich die plattformübergreifenden Google-Maps-URLs – sie öffnen auf Android und iOS die Google-Maps-App, sonst den
Browser:

- **Teilen:** `https://www.google.com/maps/search/?api=1&query=<lat>,<lng>`; Share-Text: Titel, Adresse
  (Reverse-Geocoding, Fallback „Keine Adresse vorhanden“ – übersetzt) und Link.
- **Route:** `https://www.google.com/maps/dir/?api=1&destination=<lat>,<lng>`.
- Erzeugt von `MapsLinkFactory` (Port, reines Kotlin, unit-getestet); Koordinaten mit Punkt als Dezimaltrenner
  unabhängig von der Gerätesprache.

### 4.2 Eingehende Links

- Intent-Filter für `https://maps.google.com`, `https://www.google.com/maps` und `geo:`.
- `GeoLinkParser` (reines Kotlin) erkennt `?q=<lat>,<lng>`, `query=`, `destination=`, `@<lat>,<lng>` und `geo:<lat>,<lng>`;
  liefert `Coordinate?`.
- Ablauf: Gibt es bereits einen Ort an dieser Koordinate (Toleranz ca. 1 m), wird er angezeigt; sonst
  öffnet sich das **Bearbeiten-Formular** mit vorbefüllter Koordinate und Titelvorschlag – gespeichert wird erst durch
  den Nutzer.

### 4.3 Google Maps SDK und API-Key

- Die Karte in der App bleibt (Maps Compose).
- Es wird **derselbe API-Key** wie in der Vorlage verwendet. Er wird **nie eingecheckt**:
  lokal `local.properties` → `MAPS_API_KEY=…`; CI: Repository-Secret `MAPS_API_KEY`.
- Hinweis: Ist der Key in der Google Cloud Console auf Android-Apps beschränkt, müssen die neue App-ID und der
  SHA-1-Fingerabdruck des neuen Signaturschlüssels dort ergänzt werden.

### 4.4 Weitere Plattformdienste

- Standort: Fused Location Provider als `Flow<Coordinate>`; Ortungsdienst aktivieren über `SettingsClient`.
- Adresse: Android `Geocoder` hinter dem Port `AddressLookup` (asynchron, Fehler → Fallback-Text).

## 5. Datenhaltung

### 5.1 Room-Schema

```kotlin
@Entity(tableName = "place", indices = [Index("latitude", "longitude")])
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long,          // Epoch-Millis UTC
    val updatedAt: Long?,
)

@Entity(
    tableName = "photo",
    foreignKeys = [ForeignKey(PlaceEntity::class, ["id"], ["placeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("placeId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeId: Long,
    val relativePath: String,     // relativ zu files/photos/
    val position: Int,            // Reihenfolge
    val takenAt: Long,
)
```

- DAOs liefern `Flow`, Karte und Liste aktualisieren sich automatisch.
- Beim Löschen eines Ortes löscht `DeletePlaceUseCase` zuerst die Fotodateien über `PhotoStore`, dann den Datensatz.
- Schema-Export nach `app/schemas/`, jede Änderung mit Migration und Migrationstest.
- Keine Übernahme von Daten der alten App (neue App-ID).

### 5.2 Sicherung/Synchronisation (opt-in)

Entscheidung: **keine automatische Synchronisation**. In den Einstellungen gibt es einen Schalter „Sicherung“, der
standardmäßig **aus** ist. Android Auto Backup wird für die App deaktiviert (`allowBackup=false`), damit ohne
Zustimmung nichts das Gerät verlässt.

Mechanismus (entschieden):
1. **Export/Import** einer Sicherungsdatei (ZIP mit JSON + Fotos) an einen vom Nutzer gewählten Ort
   (Storage Access Framework, z. B. Google Drive, Downloads). Funktioniert ohne Server, auch später auf iOS.
2. Bei eingeschaltetem Schalter regelmäßige Sicherung per WorkManager an den gewählten Ort.
3. Echte Synchronisation zwischen Geräten (Backend) ist nicht geplant; nur bei späterem Bedarf neu bewerten.

## 6. Teststrategie (TDD)

| Ebene | Werkzeuge | Gegenstand |
|---|---|---|
| Unit (JVM) | kotlin.test/JUnit 5, Turbine, coroutines-test | Value Objects, `Place`, Use Cases, ViewModels, `GeoLinkParser`, `MapsLinkFactory` |
| Integration | Room in-memory + Robolectric, `MigrationTestHelper` | DAOs, Repository, Migrationen, `FilePhotoStore` |
| UI | Compose UI Test (Robolectric) | Kernabläufe je Slice |
| Architektur | Konsist | Slice-Grenzen, Dependency Rule |
| Statisch | ktlint, detekt, Android Lint | jeder Build |

Regeln:
- Red → Green → Refactor; kein Produktivcode ohne vorher fehlschlagenden Test.
- Fakes für Ports (`FakePlaceRepository`, `FakeLocationProvider`, `FakePhotoStore`) statt Mock-Frameworks.
- Keine echten Netzwerk-, Standort- oder Kamera-Zugriffe in Tests.
- Ein PR wird erst gestellt, wenn lokal **alle Tests grün** sind; CI muss ebenfalls grün sein.

## 7. Lokalisierung

- Sprachen: Deutsch, Türkisch, Englisch (`values-de/`, `values-tr/`, `values/` = Englisch als Fallback).
- Datums- und Zahlenformat nach Gerätesprache; Koordinaten in Links immer mit Punkt.
- Ein Lint-Check stellt sicher, dass kein String in einer der drei Sprachen fehlt.

## 8. Umsetzungsreihenfolge (Vorschlag)

Jeder Schritt = eigener Plan mit Rückfrage, eigener Branch, eigener PR.

1. Projektgerüst: Gradle (Kotlin DSL, Version Catalog), `:app` mit Compose-Theme, Koin, ktlint/detekt/Konsist,
   GitHub-Actions-Workflow mit Debug-APK und Build-Version.
2. `place/` (Aggregat, Value Objects) und Room-Infrastruktur.
3. Slices `listplaces`, `rememberplace` (ohne Standort: manuelle Koordinate im Test), `editplace`, `deleteplace`.
4. `location/` und Slice `showmap` (Google Maps, eigener Standort, Marker).
5. Fotos (mehrere pro Ort).
6. Slices `shareplace`, `routetoplace`, `receivegeolink`.
7. Lokalisierung Deutsch/Türkisch/Englisch vervollständigen.
8. Slice `backup` (opt-in).

## 9. Entscheidungen

| # | Thema | Entscheidung |
|---|---|---|
| 1 | Karte | Google Maps bleibt: Karte in der App (Maps Compose) + Aufruf der Google-Maps-App für die Route |
| 2 | Karten-Vorschaubild | entfällt |
| 3 | App-ID / alte Daten | neue App-ID, keine Datenübernahme |
| 4 | Sicherung/Sync | nicht automatisch; Nutzer kann sie einschalten (Mechanismus offen, siehe 5.2) |
| 5 | DI | Koin |
| 6 | minSdk | aktuelle Android-Version minus 5 |
| 7 | Plattform | Android jetzt, iOS später über Kotlin Multiplatform |
| 8 | Fotos | mehrere Fotos pro Ort |
| 9 | Sprachen | Deutsch, Türkisch, Englisch |
| 10 | Eingehender Link | Bearbeiten-Formular, vorbefüllt |
| 11 | Module | ein Gradle-Modul `:app`, Pakete pro Slice |
| 12 | Sprache im Code | Englisch |
| 13 | Rückfragen | ein Plan mit Rückfragen pro Aufgabe/PR, Umsetzung nach OK |
| 14 | TDD im Verlauf | kein separater Test-Commit nötig; PR erst, wenn alle Tests grün sind |
| 15 | APK | Debug-APK pro PR mit Build-Version |
| 16 | Google-Maps-Links | plattformübergreifende URLs (`https://www.google.com/maps/...?api=1`) |
| 17 | API-Key | derselbe Key wie in der Vorlage, nur über `local.properties`/GitHub-Secret |
| 18 | Sicherungs-Mechanismus | Export/Import einer Datei (ZIP mit JSON + Fotos) an einen vom Nutzer gewählten Ort; bei eingeschaltetem Schalter zusätzlich regelmäßig per WorkManager |
| 19 | App-ID / Paket | `de.kd.epic2` |
| 20 | Fallback-Sprache | Englisch |
| 21 | Titel-Länge, Koordinaten-Toleranz | max. 100 Zeichen; ca. 1 m |

Später zu entscheiden: UI-Technik für iOS (Compose Multiplatform oder SwiftUI), wenn iOS ansteht.

### Aufgaben des Nutzers (außerhalb des Repositorys)

- Google-Maps-API-Key in der Google Cloud Console auf Android-Apps beschränken und App-ID `de.kd.epic2` mit dem
  SHA-1-Fingerabdruck des Signaturschlüssels eintragen (Debug-Schlüssel für CI-APKs, später Release-Schlüssel).
- Key als Repository-Secret `MAPS_API_KEY` in GitHub hinterlegen.
