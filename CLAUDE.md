# Mes parcours

Private Android GPS tracker for outdoor activities: track recording, OSM map, live
statistics, GPX & KML import/export, track merging.

Personal app (dedicated "to my father Thierry"), with no account and no server: all data
stays on the device.

- **The repository is in English**: code, comments, tests, workflows, documentation and
  release notes. The *app* is translated into several languages and follows the phone's
  language — see "Interface languages". Commit messages are in English too.
- Displayed name: "Mes parcours" (`app_name` in `res/values/strings.xml`, guarded in
  `ExampleRobolectricTest`). Formerly "Sillage". The name is a brand: it is not translated.
- `applicationId`: `com.toche.mesparcours` — Kotlin `namespace`: `com.example`
- License: GPL-3.0 (`LICENSE`, the full official text of the Free Software Foundation).
- Current version: `2.0.3` (`versionCode` 42). It is written only once, in
  `app/build.gradle.kts`; the "About" screen reads it through `BuildConfig.VERSION_NAME`.
  The `-thierry` suffix was dropped from `0.9.15` on.
- **Changelog ("What's new")**: the `RELEASES` list in `SettingsTab.kt`. Each entry lists
  string resources (`rel_<version>_<letter>`), translated in every language.
  - Up to `1.0` **excluded**: a single entry, that of the current version, whose content
    was replaced at each version. Development versions followed one another too fast for
    a history to be useful.
  - From `1.0`: **add** an entry at the top instead of replacing. The display already
    knows how to present several versions, separators and "current" chip included.
  - The "current" mention is deduced from `BuildConfig.VERSION_NAME` and only shows from
    two entries on; it is never to be moved by hand.
- The project was born from a Google AI Studio scaffold, entirely removed since (metadata,
  `secrets` plugin, `GEMINI_API_KEY`, Firebase BOM, original README, former
  `applicationId`). See "Frozen identity" below.

## Technical stack

Kotlin 2.2.10 · Jetpack Compose + Material 3 (fixed palette, see "Interface redesign") ·
Room 2.7 (KSP) · osmdroid · play-services-location · AGP 9.1.1 · Java 11 ·
minSdk 24 / targetSdk 36 / compileSdk 36.1

## Commands

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

The Android SDK is indicated by `local.properties` (not versioned). The `release` variant
requires the environment variables `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD`;
the key (`my-upload-key.jks`) is not in the repository.

On Windows, prefer `.\gradlew.bat`; `--offline` speeds up iterations noticeably once the
dependencies are cached.

**Building from Claude Code on the web.** The `debug-apk.yml` workflow was removed at the
author's request: the debug APK is now built in the session itself. The SDK installs there
when `dl.google.com` is reachable (which has not always been the case): download
`commandlinetools-linux-*_latest.zip`, make `cmdline-tools/latest/bin/*` executable
(`chmod -R +x`), accept the licenses, install `platforms;android-36` and
`build-tools;36.0.0`, write `sdk.dir=…` in `local.properties` (ignored by git), then
`./gradlew assembleDebug --no-daemon`. Maven Central sometimes answers 429: retry after a
pause, Gradle keeps in cache what it already obtained. Releases are still made by
`release.yml`. Lint cannot run in that environment (its own dependencies hit the same 429).

The APKs committed on `main` under `app/build/outputs/apk/` are therefore no longer updated
automatically: they date from the last run of the old workflow (2.0.1). A debug build
modifies that tracked file: copy the result elsewhere and `git checkout` the tracked copy.

**The `debug` build type carries its own `applicationId`** (suffix `.debug`, i.e.
`com.toche.mesparcours.debug`) **and its own `versionName`** (suffix `-debug`, for example
`0.9.16-debug`). Two intended consequences:

- A debug APK installs **next to** the release version, never over it: Android treats them
  as two distinct apps. No more need to uninstall the release to try a debug, or the reverse.
- The "About" screen of a debug APK says unambiguously that it is not the release
  version, even once installed.

**Beware of the debug key of the APK produced in CI.** `debug.keystore` not being
versioned, the runner has none and AGP makes up a new one **at each run**. Two debug APKs
from two runs therefore do not carry the same signature, and neither carries the one of the
author's machine: installing one over the other fails with "package signatures do not
match", and one must uninstall — thus lose the recorded tracks **of the debug app** (the
release, a distinct app, is never concerned). A CI APK serves to try a version, not to
update an existing installation. Committing a fixed `debug.keystore` would lift the limit (a
debug key is not a secret), at the price of the current rule that keeps it out.

## Code map

```
MainActivity ──> MainScreen ──> 4 tabs: TrackingTab · HistoryTab · ToolsTab · SettingsTab
                     └────────> DetailView (covers the tabs when a track is open)

TrackViewModel (single, created by TrackViewModel.Factory)
     │
     ▼
TrackRepository (singleton) ◄──── TrackingService (GPS foreground service)
     │                       ◄──── BootReceiver (resume after reboot/crash)
     ▼
AppDatabase / TrackDao  ──  entities Track + TrackPoint
```

- `data/` — models, `TrackDao`, `AppDatabase`, `TrackRepository`
- `service/TrackingService` — GPS acquisition, statistics accumulators, notification
- `ui/component/MapViewContainer` — osmdroid ↔ Compose bridge (the biggest file)
- `util/` — `Importer`, `Exporter`, `MediaStoreExporter`, `AutoBackupManager`,
  `SolarTimes`, `TunnelDetector`, `Altitude`, `ElevationAccumulator`, `FormatUtils`,
  `KmlColor`, `UserFacing`, style preferences

## Color of a track

**The color belongs to the track, not to the category** (since `0.13.0`). It is stored in
`Track.displayColor` and chosen by tapping the track's colored chip, in the history. The
three per-category colors that lived in preferences, the long press on tabs and the global
"keep file colors" switch all disappeared with this change.

`TrackStylePreferences.resolveTrackColor` is the only place that decides, in this order:

1. `displayColor` — the user's explicit choice for this track;
2. `sourceColor` — the color its file carried;
3. the default color of its category (`defaultColorFor`).

The explicit choice comes **before** the file's color, and that is deliberate: otherwise,
choosing a bold color on a colored KML would change nothing on screen, the chip getting
checked with no visible effect. Symmetrically, "original color" is expressed by setting
`displayColor` back to null — hence the choice of a nullable column rather than a separate
flag.

`displayColor` also takes precedence over `TrackPoint.segmentColor` when drawing: a color
requested by hand applies to the whole track, segments included.

**Line thickness belongs to the track too** (`Track.displayWidthDp`, migration 8→9), at the
author's request: it is set in the same sheet as the color ("Track appearance", the track's
chip in the history), and the general setting left the Settings screen. At null, the track
keeps the former general thickness (`pref_track_thickness_dp`, which no screen writes any
more): nothing changes on screen at update, no backfill to write. This general value also
serves for the track being recorded. `TrackStylePreferences.resolveThicknessDp` decides;
each overlaid line is drawn at its own thickness. Splitting and trimming pass it on to the
pieces, like the color.

**The backfill of existing colors is mandatory.** Migration 7→8 cannot fill `displayColor`
itself: the value to write lived in preferences, which a migration's SQL cannot read.
`TrackRepository.backfillDisplayColors`, called at startup from `TrackApplication` and
guarded by a preference, therefore writes to every existing track the color it displayed
before the update. Without it, the whole history of a user who had set their colors would
change appearance without their having asked for anything. It fits in a single
`UPDATE … WHERE displayColor IS NULL`, hence replayable and without loading a track into
memory.

## Colors read from imported files

A KML carries the color of each line, which the app keeps (see the resolution order above).

Three traps, each visible on screen if left unhandled:

- **KML writes its colors as AABBGGRR**, Android expects ARGB. Copying the value swaps red
  and blue. `KmlColor.parse` converts, `KmlColorTest` locks it.
- **The color belongs to the point, not to the track** (`TrackPoint.segmentColor`). A
  Google Earth export commonly gathers dozens of trips in a single file: one color per
  track would paint them all the same.
- **The style is resolved, not guessed.** A `<Placemark>` refers to a `<Style>` by
  identifier, possibly through a `<StyleMap>` that distinguishes the resting appearance
  from the hover one. Taking the first `<LineStyle>` at hand sometimes brings back the
  hover color, which the user never sees. `KmlStyleTable` does the resolution, with a depth
  bounded against circular references.

The style directory is collected by a **first read of the file**, before the points: a style
declared after the trip that uses it would otherwise arrive too late, the points being
already written to the database. This pass ignores the `<coordinates>`, so its footprint
stays at a few dozen entries whatever the file size.

A line with no color — every GPX, and KMLs without a line style — falls back on the palette
color. Without this fallback it would be drawn in black, hence invisible on the dark map
background. A fully transparent color is refused for the same reason.

Corollary at display: `buildSegmentsFromPoints` also cuts on a **color change**, an osmdroid
polyline carrying only one.

`TrackRepository` is the **source of truth** of the live state (`isTracking`, `livePoints`,
`liveStats`, `gpsStatus`…). The service writes, the interface reads.

## Tools

`ui/screen/ToolsTab.kt` centralizes the operations that apply to one or several tracks
already recorded. Each tool follows the same template: a track selection card
(`SingleTrackRow` for one, `MergeTrackRow` for several), one or two settings, a
confirmation button.

- **Merge tracks** — see invariant 5: merging happens in SQLite, no point goes through
  memory.
- **Split a track** — the inverse of merging, described below.
- **Trim a track** — remove its first and last minutes, described below.

**"Remove stationary points" was removed** before 1.0, at the author's request (see "Dead
weight"). **CSV conversion** followed the same fate in 1.1 (see "Dead weight").

### Splitting a track

`TrackRepository.splitTrack` separates a track into several, according to `SplitMode`:

- `SEGMENT_BREAKS` — at each `TrackPoint.isDiscontinuous`. This is the mode that handles
  the common case of an imported file gathering dozens of trips, each in its own
  `<coordinates>`.
- `TIME_GAP` — as soon as an interval exceeds a chosen threshold. **No effect on a GPX
  without `<time>` or on a KML**: import gives them a regular synthetic timestamp, so there
  is no gap to find. Splitting reports it instead of failing, and the interface points to
  the right mode through its subtitles.

Four points structure the implementation, each with its reason:

1. **A single read, outside a transaction.** The track is reread page by page to locate the
   cuts; only the current page is in memory, so a track of several million points goes
   through. This read is long: holding it in a transaction would block the database for
   writing throughout. The bounds noted are **point identifiers**, which makes what follows
   independent of what was read.
2. **Statistics are accumulated during that same pass**, by `TrackStatsAccumulator`.
   Without that, each piece would have to be reread after creation — twice the track — or
   the parent's statistics reused, which would give each piece the distance of the whole
   trip.
3. **Writing fits in a single transaction**: creation of the tracks, then
   `copyPointRangeInto`, which lets SQLite copy each slice (invariant 5, same `ORDER BY id`
   essential). An interruption never leaves a half-done split.
4. **`clearFirstPointDiscontinuity` on each piece.** The first point of a slice is precisely
   the one that opened a new segment; in its new track, it only opens the line. Keeping the
   mark would draw an empty segment before it.

The original track is **never modified**, and is only deleted if the user ticks the box —
the pieces then contain exactly the same points. Each inherits the origin (`isImported`,
`isMerged`) and appearance (`sourceColor`, `displayColor`) of the parent, but never
`isSelectedForMap`: a split into thirty pieces would light them all up on the map at once.

Splitting into a single piece would only make a duplicate: it is refused, with a message
saying which of the two modes found nothing.

### Trimming a track

`TrackRepository.trimTrack` creates a copy cut of its first and last minutes. It is the most
ordinary need of a GPS tracker: the forgotten recording that kept going on the way back, or
started from the car park.

Same shape as splitting, and for the same reasons: a single read page by page, bounds noted
as **point identifiers**, statistics accumulated during that same pass, then a single
transaction where SQLite copies the slice (`copyPointRangeInto`, invariant 5).

**Bounds are compared with timestamps, never with a rank.** On a recording with gaps —
pause, signal loss — "remove the first five minutes" does not mean removing a fixed number
of points. `getFirstPointTimestamp` and `getLastPointTimestamp` give both ends without
loading a single point.

Three refusals, each with its message:

1. **Removing nothing** — the result would only be a duplicate.
2. **Removing more than the track's duration** — the two bounds cross.
3. **Leaving a single point** — neither line, nor distance, nor duration to draw from it.
   This third case is only reachable on a track with gaps: on evenly spaced points, any
   trim wide enough first makes the bounds cross, and the previous refusal triggers.

`clearFirstPointDiscontinuity` on the result, for the same reason as splitting: the point
that became first may have opened a segment, it now only opens the line.

The original track is never modified, and is only deleted if the user ticks the box. The
result inherits the origin and appearance of the parent, but never `isSelectedForMap`.

### `TrackStatsAccumulator`

`util/TrackStats.kt` carries **the project's only statistics computation**.
`TrackRepository.calculateStatsFromPoints` is now only a wrapper for a list already in
memory. Do not reintroduce a second computation for streams: two parallel implementations
would end up diverging, and the same track would then display different figures depending
on the path taken. `SplitTrackTest` compares the two paths on the same points, precisely to
lock this.

The first point received brings neither distance nor speed — it has no predecessor to
measure against — but it opens the reference altitude. That is what the original loop
already did by starting at index 1.

## The black veil of transitions: fixed in 0.16

Reported as: "when I open a track, the opening animation makes a weird black effect, and
it is the same when I go back".

**The cause.** The `Box` containing `MainScreen`'s `AnimatedContent` had no background. Yet
the transition combined a slide **and a fade**: at mid-course, both screens were half
transparent at the same time, so one saw through to the window background — that of
`Theme.DeviceDefault`, dark.

**The fix**, in two parts that complement each other:

- an **opaque background** on the container, so there is nothing left to glimpse behind;
- **the fade removed** from the two sliding branches. Two opaque screens sliding over each
  other never let anything show between them: the outgoing screen recedes by a fifth of the
  width while the incoming one travels a whole one, so they overlap from one end of the
  movement to the other. The fade did not make the transition softer, it made it
  translucent.

The fade remains on the third branch — the direct move from one track to another, with no
slide — where it is the only possible movement; the opaque background now serves as its
canvas.

## The sliced shadow of history cards: fixed in 0.17

Reported as: "during the track display animation, the shadow at the bottom is awful", then
twice more after two fixes that changed nothing.

**The cause, measured and not guessed.** `AnimatedVisibility` **clips its content to its own
bounds** as soon as a transition moves it. The entry was `slideInVertically { it / 6 }`: the
content moved down by a sixth of its height was therefore sliced flat at the bottom, rounded
corner and shadow included, throughout the animation.

**The fix**: the slide leaves `AnimatedVisibility`, which disappears. It is driven by hand by
an `Animatable` applied as a `graphicsLayer` — which clips nothing, `clip` being `false` by
default. Opacity there stays **binary** (0 before the card's turn, 1 afterwards): at zero
nothing is drawn, so there is no shadow to slice.

**How the cause was finally found.** The author attached a screen recording. The frames were
extracted and the column of pixels crossing the card read one by one: during the animation,
the card stopped on a sharp edge with the background color immediately below — not one pixel
of shadow — then the gradient reappeared all at once on the last frame. The container was
clipping, it was not an offscreen buffer.

**The two fades removed before that were removed for another reason, and they stay
removed.** An *intermediate* opacity makes the element compose offscreen in a buffer **at the
exact dimensions of the card**: what overflowed — hence the shadow — ends up sliced there.
There were two: that of `AnimatedVisibility`'s `enter`, and the one **`animateItem()` applies
by itself** — its `fadeInSpec` and `fadeOutSpec` parameters default to a spring, not `null`,
which its name does not let you guess. Hence the call
`animateItem(fadeInSpec = null, fadeOutSpec = null)`, to be kept.

**Do not reintroduce a progressive fade or an `AnimatedVisibility` transition** on an
element that carries a shadow without first removing its elevation: these are two distinct
mechanisms, and each is enough to slice the shadow.

**Lesson of method, and this is the second time the project pays for it** (see "The zoom
jumped when pressing 'locate'"). Two fixes were pushed claiming to hold the cause, on the
sole strength of a plausible mechanism. They were right in themselves — fades do slice the
shadow — but it was not what showed on screen. Three minutes spent extracting the frames
from the video gave the answer that two builds had not. **Faced with a visual defect, look
at the pixels before reasoning about the mechanism.**

## Invariants not to break

Each of these points fixed a real bug; the code comments explain why. Reread before
touching them.

1. **Double-checked singletons** (`AppDatabase`, `TrackRepository`). Two instances = two
   sets of `StateFlow`s and of Room invalidation trackers: the service feeds one while the
   screen observes the other, and the interface silently stops updating.
2. **All Room migrations are mandatory.** Removing one of the declared migrations —
   `MIGRATION_4_5` (viewport index), `MIGRATION_5_6` (`Track.sourceColor`), `MIGRATION_6_7`
   (`TrackPoint.segmentColor`), `MIGRATION_7_8` (`Track.displayColor`), `MIGRATION_8_9`
   (`Track.displayWidthDp`), `MIGRATION_9_10` (`Track.isPaused`) — would make the database
   unreadable for any user coming from an earlier version. Every new schema version must
   come with its own. Two safeguards, since `0.11.2`, where the invariant rested on
   vigilance alone: the database is no longer built with `fallbackToDestructiveMigration()`
   but with `fallbackToDestructiveMigrationOnDowngrade()` — a forgotten migration now makes
   opening fail instead of silently erasing the history; and `MigrationChainTest` checks
   that the declared migrations form a continuous chain from the oldest reachable version to
   `DATABASE_VERSION`. The schema version is a separate constant (`DATABASE_VERSION`) rather
   than a number written in the annotation: that is what makes it readable by the test.
   `exportSchema = true` has Room write the schema of each version to `app/schemas/` (path
   set by `room.schemaLocation` in `app/build.gradle.kts`, without which compilation fails).
   These files **are to be committed**: they appear after the first compilation and are what
   a future real migration test would need, one that opens a database of an old version and
   applies the migrations to it.
   On `track_points`, prefer a column that is **nullable and without a default value**:
   SQLite then only modifies the schema, where a default value would rewrite every row —
   several million on an imported track.
3. **Viewport-based display.** `getDisplayPoints()` returns a subsampled silhouette + the
   detail of the visible area. These points serve **only for drawing**. Never export from
   them: export goes through `forEachPoint()`, which rereads 100% of the points page by page.
   Corollary: **any programmatic move of the map must republish the visible area.** osmdroid
   only produces a usable scroll event for the user's gestures; a `controller.animateTo()`
   therefore left the ViewModel on the old area, and the newly visible part of a dense track
   stayed reduced to its silhouette until the map was dragged by hand. During automatic
   following, republication is periodic and not hooked to the recentering: positions arrive
   about once per second and would cancel an effect depending on them before the animation
   ended.
4. **Streaming import and export.** `Importer.PointSink` and `Exporter.GpxWriter` /
   `KmlWriter` never keep more than one batch in memory — a track of several million points
   must go through. KML is parsed with SAX, not `XmlPullParser` (which would rebuild the
   whole `<coordinates>` block in memory).
   **The SAX parser is hardened** (`newSaxParser`): an imported file comes from outside and
   must not be able to declare XML entities. Without that, a few kilobytes of nested
   entities are enough to exhaust memory. The flags are set on a best-effort basis — an
   implementation that did not know one must not prevent every import.
5. **Merging happens in SQLite** (`INSERT … SELECT`), no point goes through memory. The
   `ORDER BY id` of `copyPointsInto` is essential: without it, the planner may copy in
   latitude order.
6. **`lastLocation == null` ⇒ new segment.** This is the only discontinuity mechanism
   (start, exit from pause, resume). Never initialize it with the last point of a resumed
   track: that would add the straight-line distance between the old segment and the resume
   point.
7. **GPS point processing serialized** on `pointExecutor` (a single thread).
   `LocationResult` delivers several positions at once; in parallel, the shared accumulators
   get corrupted.
8. **GPS simulation must never activate in release.** In `TrackingService` it is guarded by
   `BuildConfig.DEBUG` — otherwise the app would record an invented trip that the user would
   take for a real one.
9. **Never call `isSystemInDarkTheme()` anywhere but in `NightMode.kt`.** The dark theme can
   be driven by the light sensor, not just by Android's setting. `MyApplicationTheme`
   resolves the state once and publishes it through `LocalIsDarkTheme`: that is what
   everything escaping Material must read, the map's tile filter in particular.

## Night mode

`ui/theme/NightMode.kt` decides on the dark theme, according to `pref_night_mode_source`:

- `system` — Android's setting (default behavior).
- `solar` — the real sun times, plus a temporary switch in tunnels
  (`pref_night_mode_tunnel`, on by default).

Two pure building blocks, without Android dependency, hence directly testable:

**`util/SolarTimes`** computes sunrise and sunset (sunrise equation, NOAA formulation) to
within two minutes of the published times. This computation deliberately replaces a call to
a weather service: it works offline — in a tunnel, in the mountains — needs no API key and
sends the user's position to nobody. The polar cases (`PolarDay` / `PolarNight`) must stay
handled explicitly: without them, `acos` returns `NaN`.

The position comes from `pref_last_lat` / `pref_last_lng`, already written by the ViewModel
at each map move. The GPS is not turned on to choose a background color: one degree of
longitude only shifts the times by four minutes, an accuracy of a few tens of kilometers is
plenty.

**`util/TunnelDetector`** tells a tunnel from an interior. An absolute threshold — what the
previous version used — confuses "it is dark" with "I am indoors in broad daylight": a lit
living room sits around 150 lux and made the app switch to dark in broad daylight. The
retained criterion therefore combines three conditions on entry: absolute darkness
(≤ 20 lux), relative collapse against the ambient brightness of the preceding moments
(≤ 8%), and persistence for one second.

The ambient reference is **frozen while indoors**. Without that freeze, it would drift to the
level of the car park where one is parked, the relative collapse would disappear and the
display would go back to light in the dark. Happy corollary: nightfall, slow, is followed by
the reference and never mistaken for a tunnel — the solar computation handles it.

The sensor is only listened to by day (at night, the theme is already dark) and listening is
tied to the lifecycle.

## Altitude

`Location.getAltitude()` returns a height above the **WGS84 ellipsoid**, whereas any altitude
read on a map or a sign refers to the **geoid**, mean sea level. The gap — 45 to 50 m over
metropolitan France — is a systematic bias, not noise. Displaying the raw value made the
altitude constantly about fifty meters too high.

`util/Altitude.kt` holds the whole chain:

- **`AltitudeResolver`** discards readings without altitude (`getAltitude()` returns `0.0` in
  that case, which used to be recorded as a real altitude), converts to sea level through
  `AltitudeConverter` — geoid model embedded in Android 14+, offline — and rejects altitudes
  whose vertical uncertainty exceeds 20 m. `resolve()` does I/O: **never on the main
  thread**. Below Android 14, it returns nothing: better to show "—" than a value wrong by
  50 m. This is the only place to plug an embedded geoid to cover earlier versions.
- **`AltitudeSmoother`** chains a moving median — which discards an isolated outlier instead
  of averaging it — then an exponential average.

`util/ElevationAccumulator.kt` accumulates elevation **against a reference altitude** that
only moves beyond 3 m. The previous criterion, a 0.80 m threshold between consecutive
points, was wrong twice: it missed steady climbs (at 1 Hz on a 10% slope, altitude only
rises by 0.14 m per second, so nothing was counted) and inflated elevation when stopped,
where noise crosses the threshold in one direction then the other.

The **recorded** altitude is the converted and smoothed one: exported GPX files therefore
carry an `<ele>` tag above sea level, as the format requires. The display reads
`TrackRepository.currentAltitude`, a flow separate from `currentUserLocation` because
`TrackPoint.altitude` is not nullable and cannot tell "unknown" from "zero".

The elevation segment break hooks onto the **same signal as the line's discontinuity**
(`lastLocation == null`, invariant 6), and not onto a flag written from the main thread.

## Dark theme of the map

Mapnik tiles are darkened by `DARK_TILES_COLOR_FILTER` in `MapViewContainer`: **inversion,
then a 180° hue rotation**. The result is Mapnik's colors darkened — dark green for grass,
deep blue for water, a dark background in place of cream paper, light labels hence readable.

Two approaches preceded this one, and each failed for a different reason worth keeping in
mind before touching it again:

- `TilesOverlay.INVERT_COLORS`, a bare inversion. Inverting flips hue as much as lightness:
  forests became magenta and water brown.
- Desaturate then invert. The dominant tint disappeared, but every color with it: the map
  became a gradient of grays, without the immediate cue of green and blue.

The half-turn hue rotation after inversion puts the hues back in place and leaves only
lightness inverted. Each row of `HUE_ROTATE_180_MATRIX` sums to 1, which guarantees that
grays — roads, labels — take no tint.

Known limit: pure white comes out pure black, darker than the background (`#13100A`). Small
white Mapnik roads therefore have little contrast. It was already the case with the two
previous approaches; fixing it would require compressing the output range, at the price of
overall contrast.

Satellite view is never filtered: an aerial photo has no light background to invert, and
darkening it would make it unreadable.

## Conventions

- **English everywhere in the repository**: code, comments, tests (including test names),
  workflows, documentation, commit messages, release notes. Comments explain *why*, not
  *what* — often the bug they prevent. Keep that register.
- **User-facing text is never written in code**: it lives in string resources, translated
  (see "Interface languages").
- Compose everywhere, no layout XML. No navigation library: navigation is a `when` on a
  tab `String` (`record`, `history`, `tools`, `settings`) in `MainScreen`.
- Preferences through `android.preference.PreferenceManager` (deprecated but uniform across
  the project — do not migrate halfway).
- `testTag`s placed on interface elements for Compose tests. They never derive from a
  displayed label (which changes with the language).
- **Settings screen**: one group per subject (`GroupLabel` + `SettingsGroup`, rows
  separated by 2 dp in a block with 28 dp corners), one row per setting (`SettingsRow`,
  `GroupItem`), and never two unrelated settings on the same row. One more setting must
  only cost one row, not a copied block. The order of groups follows frequency of use, from
  most common to rarest.
- **Messages to the user**: `LocalAppMessenger.current.show(…)`, no `Toast`, in everything
  that is Compose. Only `AutoBackupManager`, outside composition, keeps its own.

## Interface languages

The app follows the phone's language: `values/` is **English** (fallback for any unplanned
language), `values-xx/` the translations — fr, es, pt, de, it, ru, zh, ja, ar (RTL) and hi.
`res/xml/locales_config.xml` declares them for the "App language" picker of Android 13+;
keep it aligned with the `values-xx` folders.

- **No visible text hard-coded.** Compose: `stringResource(R.string.…)`; outside
  composition: `context.getString`. **Positional** arguments (`%1$s`), and `%%` for a literal
  percent sign in a string that has arguments. `app_name` stays "Mes parcours" everywhere
  (`translatable="false"`).
- **No plurals to agree**: the sentence is turned around a number ("Selected: 3") rather
  than writing six forms per language.
- **The data layer has no `Context`**: its errors are `UserFacingArgumentException` /
  `UserFacingStateException` (`util/UserFacing.kt`) carrying the string's identifier; the
  screen resolves it at display time (`Throwable.userMessage`).
- **Never a translatable string as state**: GPS status is the `GpsStatus` enum (it used to
  be "Signal trouvé" compared character for character).
- **Dates and numbers**: `FormatUtils` (initialized by `TrackApplication`) uses
  `DateFormat.getBestDateTimePattern` and `Locale.getDefault()`; no more `Locale.FRANCE`.
- **Adding a language**: a complete `values-xx/strings.xml` folder + one line in
  `locales_config.xml`. A missing text falls back on English.
- Track names already recorded ("Track on … at …") stay in the language of their creation,
  like a name typed by hand.
- **The changelog is translated** (`rel_*` resources): a new version adds its strings in
  **all** languages.
- **Release notes shown in the update dialog** come from `update.json`: `notes` (English) and
  the optional `notesI18n` (a map keyed by language code). `AvailableUpdate.notesFor(language)`
  picks the phone's language and falls back on English. See "Publishing a release".
- **Screenshots in another language**: `@Config(qualifiers = "de-" + RobolectricDeviceQualifiers.Pixel8)`
  (the language goes **before** the screen qualifiers).

## Interface redesign

The author supplied a complete mockup (interactive HTML export: "Mes parcours", "Screens",
"Design system"): 57 screen states, each in light and dark, in a Material 3 Expressive
style. **It is not committed to the repository**; a session resuming the work without it
must ask the author for it again.

**The redesign is applied to the whole app.** First planned in stages, one APK at each, it
was finally done in one go at the author's request ("do everything, you will give me the
app from start to finish"), after a first APK of the base stage alone that did not resemble
the mockup enough. Choices of the author that still hold:

1. **Fixed palette**, no more dynamic color (Material You) on Android 12+. Same colors
   whatever the wallpaper.
2. Do not carry over the mockup's 2.x version numbers, nor its drawn maps: the map stays
   OpenStreetMap and its dark filter (see "Dark theme of the map").

**What is where:**

- `ui/theme/` — colors (`Color.kt`), `Theme.kt` (shapes, `LocalRecordingColor`), fonts and
  type scale (`Type.kt`, including `StatXlTextStyle` and `QuoteFontFamily`).
- `ui/component/Expressive.kt` — the shared building blocks: `CookieShape` and `SunShape`
  shapes (taken point by point from the mockup), `ShapeBadge`, `TrackTile`, `MpSwitch`,
  buttons, `MpDialog`, `MpSheet`, `WaveProgress`, and the messages at the bottom of the
  screen (`AppMessenger` / `LocalAppMessenger` / `AppMessageHost`) that replace Android's
  `Toast` in the Compose interface.
- `ui/component/MainNavigationBar.kt` — the floating bar. It fades away when a tool or a
  settings page (what's new, About) occupies the screen.
- Each screen was redone in its file: `HistoryTab`, `DetailView`, `ToolsTab`, `SettingsTab`
  (with the "What's new" and "About" pages), `UpdatePrompt` (steps as bottom sheets),
  `WelcomeScreen` (three pages), `TrackingTab` and the layers of `MapViewContainer`
  (markers in theme colors, hidden-tracks banner, "Force display" dialog).

**Deliberate departures from the mockup**, so as to promise nothing the app does not do:

- **Merge**: the mockup says the originals remain; in reality merging combines them into the
  oldest (invariant 5, `mergeAndSaveTracks`). The texts say what really happens.
- **Export**: the mockup writes directly to Downloads; the app keeps Android's file picker.
- **Update**: no APK size (unknown before download). "Continue in background" tucks the
  sheet away, the download continues as long as the app stays open, and the sheet comes back
  by itself at the next step — no download outside the app.
- **Tunnel**: the "Dark in tunnels" row only appears in solar mode, the only mode where it
  acts.
- **Record screen: former layout and sizes.** The author kept the mockup's design (colors,
  fonts, shapes, shadows), but wanted back **the place and size** of the former elements. A
  single-row statistics banner at the top (speed and altitude, then distance, speed and
  altitude during a recording — **no duration**, at the author's request; GPS status dot on
  the right; signal alerts that cover it for a moment), the hidden-tracks banner just below.
  **No map background button on this screen**, at the author's request: the choice is made
  only in Settings (the mockup offered one, removed). At the bottom right, round buttons in a
  column at the former sizes: Recenter (56 dp, always visible, filled when following is
  active), then Pause/Resume (56 dp) and the main button (72 dp: Start, New track during the
  choice, Stop during recording); during the choice, Cancel (48 dp) and Resume a track
  (56 dp) above. **Do not go back to the giant map or the centered button of the mockup.**
- **Position dot: blue**, not the theme color (`POSITION_DOT_BLUE` in `MapViewContainer`,
  #1A73E8 — the Google Maps blue from before the redesign). Requested by the author: it is
  what the eye looks for first on the map. The start and finish markers stay in theme colors.
- **Hidden tracks**: "Keep hidden" replaces "Hide tracks" in the hidden-tracks banner — the
  tracks already are hidden, the button only tucks the banner away.

**Mockup novelties already done**: elevation profile in the detail sheet, three-page welcome,
dates in the changelog (GitHub publication dates, `date` field of `Release`), next day/night
switch under "Sunrise and sunset". **Still to do**: preview of pieces before a split, date of
an ignored update, "New" chip on an imported track, Pause button in the notification,
"SIMULATION" badge (GPS simulation is not exposed to the ViewModel), heading cone under the
position dot, app icon.

**Fonts**: Bricolage Grotesque (titles, figures) and Figtree (interface), official variable
files from Google Fonts in `res/font`, SIL OFL 1.1 license (texts in `licenses/`). Bricolage
is used with its width and optical-size axes, which Android only applies from API 26: on
Android 7, the font shows in its default instance, neither condensed nor bolded.

**Looking at pixels without a phone.** Run the tests with `-Proborazzi.test.record=true`
(in the session, since `debug-apk.yml` was removed): images are written to
`app/src/test/screenshots/refonte/`, not to be committed. `ScreensScreenshotTest` populates
an in-memory database like the mockup and photographs every screen and its main states
(sheets and dialogs included, through `captureScreenRoboImage`). Robolectric draws the real
Compose interface there — fonts, colors, layout — enough to compare it with the mockup even
before installing the APK. It is the direct application of the lesson of "The sliced
shadow": look before reasoning. Limits: no map tiles (no network), shadows not very faithful,
and the language is that of the test (English by default; see "Interface languages" for
other languages — long German texts and right-to-left Arabic are worth a look after any
layout change).

## Current state

- `assembleDebug` and `testDebugUnitTest` pass.
- 196 unit tests in 23 suites: `ScreensScreenshotTest` (33, screenshots only — see
  "Interface redesign"), `Iso8601Test` (16), `UpdateManifestTest` (17), `TrimTrackTest`
  (14), `SplitTrackTest` (13), `BearingTest` (10), `SolarTimesTest` (9), `KmlColorTest` (8),
  `AltitudeSmootherTest` (7), `KmlExportTest` (7), `RecordingGuardsTest` (7),
  `KmlStyleTableTest` (7), `TrackSegmentsTest` (7), `CoordinateTokenizerTest` (6),
  `DarkTilesColorFilterTest` (6), `DesignSystemScreenshotTest` (6, screenshots only),
  `ElevationAccumulatorTest` (6), `TunnelDetectorTest` (6), `MergeTracksTest` (5),
  `MigrationChainTest` (3), plus three legacy scaffold tests (`ExampleUnitTest`,
  `ExampleRobolectricTest`, `GreetingScreenshotTest` with Roborazzi).
  Running the whole suite in one go fails locally because of Robolectric native-library
  conflicts: run the suites one at a time (`--tests 'com.example.<Suite>'`). This count had
  once come to lie; keep it up to date together with the tests themselves.
- Under git since the first commit of the `0.9.8-thierry` state, branch `main`.

## Where the project stands

### What is in place

- The repository exists and is **public**: `https://github.com/ToftMalone/Mes-parcours`,
  default branch `main`. Its visibility is not a detail — `update.json` is downloaded without
  a token, and a private repository answered 404, which silently made the whole update search
  inoperative.
- **The signing keystore exists** and the repository's four secrets are filled in.
  `assembleRelease` therefore produces a signed APK, locally as in continuous integration.
- **Many versions have been published**: the publishing chain runs for real, `update.json`
  answers at the expected URL, and an installed app detects following versions by itself.

### Awaiting an action from the author

- Nothing blocks the publishing chain any more.
- **1.0 is published**, without waiting for the month of usage feedback initially planned on
  0.15: the author decided. Splitting and both CSV converters (added in 0.14 and 0.15) had
  already been confirmed working in the field.

### Review of 2.0.3: recording, pause, import

A "bugs, flaws, non-optimal things" review, at the author's request. Eight points fixed; two
others noted and set aside by the author (see the end). Changelog deliberately reduced to
"Bug fixes" and "Battery usage optimization", at their request.

1. **The track being recorded can no longer be merged or deleted.** Merging offered it, the
   history and the detail sheet offered "Delete": the row disappeared under the service,
   which kept writing to it, and every following point was silently refused by the foreign
   key. `TrackRepository.isBeingRecorded` decides; `deleteTrack` returns false and
   `mergeAndSaveTracks` throws, on top of the screens no longer offering the action.
2. **The pause is stored in the database** (`Track.isPaused`, migration 9→10). It only lived
   in the service: a process killed during a pause, or a phone rebooted, resumed the
   recording by itself. `ACTION_RESTORE` (BootReceiver) and the `START_STICKY` restart
   reread the state from the database; a pause received by a freshly recreated service
   (`hasSession` false) is written to the track left open, then the service stops.
   `persistPauseState` rereads `isPaused` under a lock, so that an old write never goes
   through after a more recent one.
3. **The average speed carries on after a resume**: `ResumeState` carries the sum and count
   of retained speeds, which the service used to reset to zero.
4. **Export opens the destination in "wt" mode**: in "w", some document providers do not
   empty the file, and overwriting a longer export left its tail behind the new content.
5. **No more background resume without the "Always" permission** (`RecordingRecovery`).
   Since Android 14, `startForeground` of a location service launched in the background
   throws without it; it was caught, but Android then killed the app for lack of a
   foreground. A notification warns instead, and the track waits paused.
6. **Pause, resume, stop and stopwatch all go through the main thread**
   (`TrackingService.applyState`). The stopwatch used to be started from the points thread
   after a resume's preparation: a pause requested during that preparation went first, and
   it ran during the pause.
7. **When paused and the app is left, the GPS goes economical** (reduced accuracy, one
   position every ten seconds) instead of full accuracy at 1 Hz. With the screen open,
   accuracy stays full for the blue dot. The signal-loss alert now ignores pauses.
8. **Import is no longer a single transaction**, which locked the database for writing and
   froze a recording in progress until the import ended. One transaction per batch; the
   reserved row carries `startTime = Track.IMPORT_PLACEHOLDER_START_TIME`, which hides it
   from the history, a failure deletes it, and `cleanupInterruptedImports` (at startup)
   catches the one of an import killed along with the app.

**Noted, not handled for now:**

- `insertPoint` suspends: `pointScope`'s single thread serializes the pieces of code between
  two suspensions, not entire coroutines. Two positions delivered together would therefore
  read the same previous position, and distance would be counted A→B + A→C. To verify, then
  fix by advancing `lastLocation` before writing or by processing positions in a single queue.
- The live line, copied and rebuilt at every point (see "Speed and battery of 1.3").

### Audit of 0.11.2

A complete code review produced a list of thirty-two points, of which twenty-two were
handled in that version (see the changelog and "Dead weight"). The remaining ten were set
aside by the author, knowingly:

- **Satellite view goes through Google's tile servers** (`mt0-3.google.com`,
  `MapViewContainer`). The preference still carries the name `usgs_sat`, inherited from a
  public imagery that is no longer the one used. Two consequences: it is a use outside the
  terms of service, liable to be cut without notice, and the area consulted goes to Google —
  which the "nothing leaves the device" promise does not lead one to expect.
- The osmdroid tile cache is in `externalCacheDir`: readable by another app on Android 9 and
  earlier.
- The anti-drift filter `dist > 1.0` discards intervals under one meter: at 1 Hz, slow
  walking (under 3.6 km/h) is structurally undercounted.
- `FLAG_KEEP_SCREEN_ON` is set unconditionally in `MainActivity`: the screen never turns off
  as long as the app is open, even outside recording. **Intended, not a defect** — confirmed
  by the author during the 1.2 battery review: not limiting it to recording is an assumed
  choice, not an oversight.
- Export to Downloads does not work on Android 9 and earlier: `MediaStoreExporter` then uses
  public storage without `WRITE_EXTERNAL_STORAGE` being declared. Silent failure.
- The diagonal line (see below) remains unfixed, at the author's request.

### Security audit before 1.0

Complete security-oriented review: manifest, update chain, import and export, storage,
database queries, continuous integration workflows. Nine points noted, **six fixed**, three
left as is and documented below.

Nothing serious was found, and most of it was already in place — not to be undone: XML
parser hardened against entities (invariant 4), Room queries all parameterized, XML escaping
at export, sanitized export file names, cleartext traffic refused by default, all
`PendingIntent`s immutable, service and FileProvider not exported, and workflows that pass
their inputs through the environment rather than interpolating them into a shell.

**Fixed:**

1. **Preferences went into the cloud backup.** `backup_rules.xml` and
   `data_extraction_rules.xml` excluded the database and external storage, but not the
   `sharedpref` domain — where `pref_last_lat` / `pref_last_lng` keep the last map position
   displayed, rewritten at every move, hence in practice the home. Excluding the history
   without excluding preferences let the most telling point of all leave. Both files now
   exclude `sharedpref`.
2. **The update APK was dropped on external storage.** The fingerprint is checked during
   writing, but several seconds pass before the user taps "Install": on Android 9 and
   earlier, which we still cover, any app with the storage permission could replace the file
   in that interval. Android does refuse an APK signed with another key **bearing the same
   package name** — but an APK with a different package name would install, trusted by a
   user in the middle of an update. `UpdateDownloader` now writes to `filesDir`, inaccessible
   to other apps on every version, and `file_paths.xml` exposes that path through
   `files-path` instead of `external-files-path`.
3. **The SHA-256 fingerprint became mandatory.** See "App update" for the reasoning.
4. **The APK's address is constrained to GitHub**, and no longer merely to HTTPS. The check
   is on the parsed host, never on the text of the address: `https://github.com.example.test/`
   does start with "https://github.com".
5. **Reading the manifest is bounded** to 65,536 characters. Without a cap, an abnormal
   response made memory grow until a crash, for a request the user did not even ask for.
6. **`EnvironmentUtils`' diagnostic log is guarded by `BuildConfig.DEBUG`.** It detailed the
   brand, model and fingerprint of the device in the distributed version, where they serve
   no one.

**Set aside, knowingly:**

- **The boot receiver is exported without protection.** It is mandatory to receive
  `BOOT_COMPLETED`; it cannot therefore be closed. A third-party app can address it an
  explicit intent imitating the action, but it only does something if there already is an
  interrupted recording to resume: neither a leak, nor a cold start of a recording.
- **The tile cache is in `externalCacheDir`** — already noted by the 0.11.2 audit. Readable
  by another app on Android 9 and earlier, which betrays the areas consulted.
- **Automatic backup writes to `Download/Mes parcours/`.** This is the intended feature, not
  a defect; on Android 9 and earlier, any app with read permission therefore accesses every
  trip. A trade-off between files that are easy to retrieve and confined files.

Also remaining is the satellite view served by Google, already arbitrated in the 0.11.2
audit: it is the only place where the "nothing leaves the device" promise is not kept.

### Security audit of 1.1

Second security-oriented review, at the author's request. **Nothing serious:** the
pre-1.0 hardening holds, and most of what follows is a safeguard rather than a flaw. Four
points fixed.

**An assumption verified rather than carried over.** The comment of `newSaxParser` claimed
that the GPX path has nothing to harden, kXML2 ignoring the document type declaration. That
is correct, and AOSP's source confirms it: `readEntityDeclaration` only stores an entity in
`documentEntities` if `FEATURE_PROCESS_DOCDECL` is active — false by default, never enabled
here — and external entities are mapped to the empty string. Neither "billion laughs" nor
XXE through GPX. **Do not enable that flag**, it alone holds the guarantee.

**Fixed:**

1. **`parseGPX` accumulated text without bound.** `textBuffer.append(parser.text)` had no
   cap and was only emptied at the next `START_TAG`: a `<desc>` of several tens of megabytes
   was copied whole. The KML path bounds each of its buffers — it was an asymmetry, not a
   choice. Now truncated to [MAX_GPX_TEXT_LENGTH], and read by `getTextCharacters` rather
   than `parser.text`, which first made a complete copy. **kXML2's internal buffer remains
   the floor**: it accumulates the text node whatever we do, and only moving GPX to SAX —
   like KML — would remove it. The fix divides the cost by about three, it does not bound it.
2. **`MediaStoreExporter` did not sanitize the file name.** The Android 9 and earlier branch
   did `File(targetDir, fileName)` without checking anything: a path separator in the name
   escaped the target folder. Not exploitable as it stands — the only remaining caller
   composes its name from a date — but the safeguard that `safeFileName` provided had
   disappeared with sharing, and CSV conversion, which derived that name from the chosen
   file, had just been removed. `sanitizeFileName` now keeps it at the single place that
   opens the file.
3. **Update APKs went into the backup.** Neither `backup_rules.xml` nor
   `data_extraction_rules.xml` excluded the `file` domain, and `clearDownloads` was only
   called on discovering a newer version: some twenty MiB stayed on disk between two
   releases, and in the backup with the rest. Not a privacy problem — an APK is public and
   re-downloadable — but enough to exhaust the backup quota and make it fail silently. The
   folder is excluded from both routes, and cleanup happens at every launch, on
   `Dispatchers.IO` (it used to run on the main thread).
4. **Shell interpolation in workflows.** `${{ }}` is replaced before the shell sees the line:
   `debug-apk.yml` and `release.yml`'s publish step composed commands from the `versionName`
   this way. Exploiting that already requires write access to the repository, but it was the
   only breach of the rule the rest of the workflows apply. All these values now go through
   the environment.

**Noted with no follow-up, for lack of real stakes:** `isMinifyEnabled = false` in release
(no obfuscation, assumed choice); the Room database unencrypted at rest, protected by the
sandbox and device encryption — SQLCipher would only make sense against a physical
extraction on an unlocked device; and the update manifest's `versionName`, displayed with no
length bound, which only becomes misleading if the GitHub account itself is compromised —
a case where the signing key stays out of reach.

### Security audit of 1.3

Third security-oriented review, at the author's request: app code, workflows, complete git
history. **Nothing serious**, the hardening of the two previous audits holds. Three points
fixed, one left aside by the author, two noted for the record.

**Verified sound**: no secret in the git history (no keystore was ever versioned,
`GEMINI_API_KEY` never carried a real value there); Room queries all static or
parameterized; only the launcher and `BootReceiver` exported, no deep link; all traffic over
HTTPS.

**Fixed:**

1. **Workflow actions were designated by tag** (`@v4`). A tag moves: its author, or whoever
   steals their account, can make it point at other code — it happened in 2025 to
   `tj-actions/changed-files`. The stake is the signing key: `setup-gradle` injects its own
   code into the Gradle that signs the APK with the keystore password, and whoever holds the
   key can publish an update the app would install as legitimate. All actions are now pinned
   by commit identifier, the readable version in a comment; the commits chosen are those the
   `v4` tags pointed at when changed, so behavior is unchanged. The procedure to bump a
   version is at the top of each workflow. **Do not go back to a tag.**
2. **The update APK could come from any GitHub repository, and any package was presented to
   the installer.** Two gaps that added up. `isTrustedApkUrl` only looked at the host, yet
   anyone can publish an APK on `github.com`; it now constrains the path to the prefix of
   this repository's releases. And the SHA-256 fingerprint, which comes from the same
   `update.json` as the address, rules out a damaged file but not a substituted one: what
   protects is the signature, which Android only checks **against the same package name**.
   An APK with a different name installed as a second app, trusted by a user in the middle
   of an update. `UpdateDownloader.isExpectedApk` therefore requires the release's package
   name (`BuildConfig.UPDATE_PACKAGE_NAME`, which stays the release's in the debug build)
   and the announced `versionCode`. The certificate is deliberately **not** compared by the
   app: once the package name is guaranteed, Android does it itself, and redoing it exposed
   to the risk — unverifiable without a device — that a version of Android does not return
   the signature of an uninstalled archive, which would make every update be refused. These
   checks only protect the versions that carry them: 1.3 already installed still accepts the
   old rule until it is updated.
3. **The KML `<coordinates>` splitter accumulated without bound.** The current token grew
   until the next blank: a block of several hundred megabytes without a space exhausted
   memory, from the very first read (the one that collects styles). It was the asymmetry the
   1.1 audit had fixed for GPX text, left open here. The token is capped
   (`MAX_COORDINATE_TOKEN_LENGTH`, 128 characters, where a real point is a few dozen) and, if
   it overflows, **discarded rather than truncated**: a cut point would be read as another
   point, elsewhere. `CoordinateTokenizerTest` locks both sides of the bound. The remaining
   floor is Expat's, which keeps a whole opening tag with its attributes in memory — like
   kXML2 for GPX.

**Not retained by the author for now:** import accepts `NaN`, `Infinity` and out-of-range
coordinates — Kotlin's `toDoubleOrNull` recognizes both words, verified. A `NaN` latitude
makes the import fail (SQLite writes it as `NULL` in a `NOT NULL` column); `Infinity` or a
latitude of 1000 go through, and give absurd statistics, even a derailing map. A booby-trapped
file therefore only harms its own import.

**Noted for the record:**

- The debug APK committed on `main` is public and debuggable: with USB debugging on, `run-as`
  reads its database. To be reserved for trials; the release for daily use.
- `gradle-wrapper.properties` carries no `distributionSha256Sum`: the Gradle distribution is
  downloaded over HTTPS but without fingerprint verification (`setup-gradle` validates the
  wrapper JAR, not the distribution).

### Battery audit of 1.2

Review aimed at consumption, at the author's request. Two points fixed, one set aside
knowingly.

**Fixed:**

1. **An infinite animation ran for nothing in `MapViewContainer`.** `TrackHiddenPulse`
   (`pulseAlpha`) animated in a loop as long as the map was composed — hence most of the time
   the app is open — for a value that was **read nowhere**, neither in this file nor
   elsewhere. An active animation clock keeps the CPU from resting between frames; here, for
   nothing. Removed together with its orphaned imports (`rememberInfiniteTransition`,
   `animateFloat`, `infiniteRepeatable`, unqualified `tween`, `RepeatMode`, `LinearEasing`) —
   `Spring`/`spring` stay, used elsewhere in the file.
2. **The recording notification was rewritten about once per second**, even with the screen
   off. Deduplication by identical text (see "Background notification") already avoided
   strict duplicates, but the displayed duration changes every second: most calls to
   `notify()` still went through. `TrackingService.updateStatsNotification` now consults
   `PowerManager.isInteractive`: screen on, behavior unchanged (deduplicated by content);
   screen off, at most one call per minute (`SCREEN_OFF_NOTIFY_INTERVAL_MS`). Returning to
   screen on republishes **immediately**, without waiting for the end of the window —
   verified by simulation. `lastNotificationText` is deliberately not updated in the blocked
   branch: the cheap text comparison keeps running at every tick; only the call to
   `notify()`, the costly one, is held back.

**Set aside, knowingly:** `FLAG_KEEP_SCREEN_ON` set unconditionally in `MainActivity` (noted
by the 0.11.2 audit). Confirmed by the author: intended, not a defect.

**Not verifiable in a web session**: both fixes touch respectively Compose
(`MapViewContainer`) and an Android service (`TrackingService`), neither testable by the
existing unit suites. The notification cadence logic was verified separately by a Java
simulation independent of Android (screen on: unchanged; screen off ten minutes: at most a
handful of calls; return to screen on: immediate republication). A debug APK remains the
only verification worth having before publishing.

### Speed and battery of 1.3: the blue dot rebuilt at every GPS point

Review aimed at "speed and battery", at the author's request. One point fixed, a second
identified but left for a next pass.

**Fixed:** `createBlueDotIcon` (bitmap, canvas, three circles drawn) was called at every run
of `drawMarkers` — hence about once per second throughout a recording, since a new GPS point
invalidates the polyline cache that triggers `rebuildMapOverlays`. Nothing in this icon
depends on the current state: the result is strictly identical from one call to the next.
Cached in `MapState` (`blueDotIcon`, built on first need then reused), as the polylines
already are.

**Identified, not fixed — the most rewarding lead of the two.** `state.points` (fed by
`TrackViewModel.livePoints`, see "Arbitrated, before 1.0": `_livePoints.value =
_livePoints.value + point`, unbounded during a recording) changes reference at every GPS
point. As `cachedPointsPolylines` is invalidated as soon as `state.points != points`, **the
current line is entirely recomputed and copied into a new `Polyline` every second**:
`buildSegmentsFromPoints` goes over all the points from the start, and `setPoints()` copies
everything. The cost per call grows with the time already elapsed in the recording —
quadratic over a whole outing, like the cost already accepted for `_livePoints` itself, but
here in the map layer rather than in the repository, hence potentially visible on screen
(stutter) on an already long track, not just costly for battery.

Fixing this without touching `_livePoints` (author's choice, not questioned): extend the
current segment's `Polyline` with the single new point rather than rebuilding all segments
from scratch. Trickier than caching the icon — the case where the new point opens a segment
(`isDiscontinuous`) must be handled correctly, which must start a separate `Polyline` rather
than extend the existing one. Left for a next pass, at the author's explicit request to
only handle the icon for now.

**Not verifiable in a web session**, for the same reason as the battery audit above: Compose.
A debug APK remains the verification worth having before publishing.

### Focus mode zoomed by itself: fixed in 0.12.0

Reported as: "when I turn on focus mode sometimes it zooms in or out by itself".

**The cause**: the `LaunchedEffect(recenterTrigger, isAutoFollowActive)` of
`MapViewContainer` that recenters the camera and resets the zoom to `pref_default_zoom` was
guarded by `recenterTrigger > 0 || isAutoFollowActive`. `recenterTrigger` only increases
(never reset to zero): from the first tap on the recenter button in a session,
`recenterTrigger > 0` stays true forever. Yet `LaunchedEffect` reruns its whole body at
**each change** of either of its keys — including when `isAutoFollowActive` goes from `true`
to `false`. A simple touch of the screen to disengage focus mode (the existing invariant:
any touch calls `onAutoFollowChanged(false)`) therefore triggered this recenter-rezoom too —
right after, even during, the user's manual gesture. The map then seemed to cancel its own
zoom or recentering by itself.

**The fix**: the guard now only depends on the current value of `isAutoFollowActive`.
`recenterTrigger` stays among the keys (to force a new run when tapping the button again
while focus mode is already active, a case where its value alone would not change), but no
longer takes part in the run condition. The recenter-rezoom therefore only triggers when
focus mode is actually active — never at the moment it has just turned off.

**This fix was not enough**, the field test showed it: the real cause was elsewhere, and this
one was only an aggravation. See the next section.

### The real cause of the unwanted zoom: the multi-touch controller, recreated endlessly

Symptom unchanged after the fix above: "it zooms in or out by itself", including **after**
letting go of the screen, and only in focus mode.

`MapViewContainer` called `map.setMultiTouchControls(isInteractivityEnabled)` in the
`AndroidView`'s `update` block, hence at **every recomposition**. Yet, in osmdroid, this is
not an idempotent setting:

```java
public void setMultiTouchControls(final boolean on) {
    mMultiTouchController = on ? new MultiTouchController<Object>(this, false) : null;
}
```

Each call **throws away the current controller and builds a new one**. During a recording in
focus mode, each GPS position changes `points` and `currentUserLocation`, hence recomposes:
the controller was replaced once or twice a second, necessarily in the middle of a pinch,
which lasts longer than that.

Why it is the zoom that suffers: osmdroid drives the **real** zoom level during a pinch,
relative to the zoom noted when the gesture opened — `setMultiTouchScale(scale)` does
`setZoomLevel(log2(scale) + mStartAnimationZoom)`. And it only resets the anchor point to
null in the **end-of-gesture** `selectObject`. A controller replaced midway never receives
that end: `mMultiTouchScaleCurrentPoint` stays set forever, and `getProjection()` keeps
readjusting every projection to it (`adjustOffsets`) at each frame drawn. Hence a
misadjustment that **outlives the gesture** and shows by itself, without touching the screen.

Focus mode aggravated it further: `getDraggableObjectAtPoint` returns `null` as long as
`isAnimating()` is true, and `animateTo` is relaunched at each GPS position for a one-second
animation — pinches were therefore often refused then picked up again at the wrong moment,
on a stale zoom reference.

The fix fits in a guard: only call `setMultiTouchControls` when the value really changes
(`MapState.appliedMultiTouch`, deliberately nullable to tell "never called" from "called with
false"). It is the discipline the file already applied just above to the tile source and
`mapOrientation`; it had only been forgotten here.

**To remember for the whole `update` block**: it runs at every recomposition, hence several
times per second during a recording. Anything called in it must be either idempotent and
free, or guarded by a comparison. A setter that *allocates* something has no business there
without a guard.

This fix is real and kept — but **it was still not the reported defect**. See the next
section.

### The zoom jumped when pressing "locate": the real cause, at last

Decisive precision from the author, after two diagnoses off target: "when I press the locate
button to go into focus, it zooms in or out by itself — **at the moment I click**". Not
during a pinch, not during the trip: at the press.

The recentering simply did, besides centering the map:

```kotlin
val zoomLevelToSet = prefs.getFloat("pref_default_zoom", 16.5f).toDouble()
mapView.controller.setZoom(zoomLevelToSet)
```

Two superimposed errors:

- **`pref_default_zoom` is not a setting**, despite its name. No screen writes it (see
  "Awaiting arbitration": the imperial preference has the same flaw); it is fed by
  `TrackViewModel.lastMapZoom`, hence by the last stored zoom of the map. Yet
  `persistMapStateThrottled` bounds that storing to once per second **with no catch-up of the
  last value**: the final zoom of a gesture almost never is the one that ends up in
  preferences, it is a zoom noted in the middle of the previous gesture. The button brought
  the map back to it, hence a jump forward or backward unrelated to anything visible.
- **Even exact, that value had no right to be imposed.** "Locate" means "center on me", not
  "change my scale". Recentering and rezooming are two distinct actions; only one was asked.

The current zoom is now kept. Only exception kept: if the map is zoomed out so far that
tracks are no longer drawn (`ZOOM_THRESHOLD`), we zoom in — otherwise "locate" would leave
the user centered on themselves but at the scale of a country.

**Lesson of method, the one that is worth the most here.** Three versions missed on this
single defect, and the first two were pushed claiming "this is the real cause". Both fixes
are right in themselves and are kept — but they answered a symptom the author had never
described. The initial wording ("sometimes it zooms in or out by itself") had been read as
"at an unpredictable moment", whereas it meant "without my asking". **Asking when exactly
the defect happens costs one question; guessing it cost three builds and two wrong
diagnoses.**

### 3D mode spun constantly: fixed in 0.11.6

Measured on a video of a real trip: the map flipped by about 100°, came back, and started
again two to three times a second — thirty-four changes of direction in fourteen seconds.

**The cause was not GPS noise**, contrary to what this section had assumed.
`computeCurrentBearing` fell back on the points of the displayed track as soon as the GPS
position had not changed since the previous pass. Yet the screen was consulted **with the
recording paused**: `livePoints` no longer moves in that case and keeps forever the bearing
it had at the time of the pause. The map therefore alternated between the real bearing and
this fossilized one, at the rate of recompositions.

The fallback on the track now only applies **for lack of a known position**. When a position
exists but has not moved enough, the function returns null and the caller keeps the last
bearing: better to keep the previous one than to invent another.

The smoothing recommended here was added right after (`smoothedBearing`,
`BEARING_SMOOTHING`): the orientation approaches the target along the **shortest arc**,
which removes the full turn when crossing north and replaces jolts with a continuous
rotation. `BearingTest` locks both points.

Lesson to keep: this defect was made visible by the move to `sample` in 0.11.5, which makes
the display reevaluate twice a second instead of once per GPS position. The trap had always
been there; it just did not trigger often enough to be seen.

### Loading tracks during automatic following: the cause, found in 0.11.5

**Exact symptom, as reported**: by car, focus mode active, the points already on screen when
it was activated stay correct, but everything discovered while moving on shows as **long
straight lines** — the subsampled silhouette instead of the detail. Touching the map, zooming
in or out sets everything right again.

**The cause.** `onScroll` republishes the visible area, and the ViewModel filtered it with
`debounce`, which only emits after a silence. Yet osmdroid notifies `onScroll` **at every
frame of its animations**: `MapController.onAnimationUpdate` calls `setMapCenter` about sixty
times a second, which goes down into `MapView.scrollTo`, which notifies the listeners — as
osmdroid's own comment says. In automatic following, `animateTo` is relaunched at each GPS
position for a one-second animation: the animations chain without interruption, the silence
never comes, and `debounce` emits nothing at all any more. No reload was triggered as long as
the mode stayed active. A gesture cuts following (`setOnTouchListener` →
`onAutoFollowChanged(false)`), animations stop, the silence passes, everything reloads at once.

`sample` replaces `debounce`: it emits the most recent area at a regular interval as long as
some arrive, and lets the last one through once the flow dries up. A continuous flow can no
longer starve it. **Never put `debounce` back on this flow**: the source may be continuous by
construction.

Corollary handled at the same time: `onScroll` also stored the map position at each frame,
i.e. four preference writes per frame and over two hundred disk accesses per second. Bounded
to once per second (`MAP_STATE_PERSIST_MS`).

**Three versions missed before this one**, and it is worth knowing why so as not to go back:

- **0.11** assumed that the camera lagged behind the position and that the published area was
  therefore wrong (`reportAutoFollowViewport`). Right in itself, kept, no effect on this
  defect.
- **0.11.3** replaced `flatMapLatest` with `conflate` (a started read is no longer
  cancelled) and spaced out the polling's republications. Two real improvements, but they
  concerned the polling loop whereas the deluge came from `onScroll`. Tried in the field: no
  change.
- **0.11.4** cached the points of tracks under `fullLoadLimit` (`fullPointsCache`), which were
  fully reread at each republication. A real performance gain, but it could not unblock a
  reload that was never triggered.

These three versions remain useful, none is to be undone. The lesson lies elsewhere: the
defect was not in the loading circuit, but in the filter placed at its entrance. What follows
describes the first two attempts, kept for the record.

### What 0.11.3 and 0.11.4 had fixed along the way

Symptom: by car, focus mode active, the overlaid tracks stop loading as one moves, until a
drag or a manual zoom sets everything right at once.

**0.11 had got the cause wrong.** It had assumed the camera lagged behind the position
(`animateTo` rejected during an animation) and that the republished area was therefore wrong;
`reportAutoFollowViewport` rebuilds from the GPS position rather than from
`map.boundingBox`. Right in itself, and kept — but it was not what broke the display.

The two real causes, found in 0.11.3:

1. **`flatMapLatest` cancelled the read in progress.** `selectedImportedPoints` and
   `selectedTrackPoints` start a read at each new area, and `flatMapLatest` cancels the
   previous one. Outside following, areas only arrive on the user's gestures: harmless. In
   focus mode, they arrived every second (`AUTO_FOLLOW_VIEWPORT_POLL_MS`), so that any read
   exceeding a second was cancelled before having produced anything, restarted from zero, and
   was cancelled again — indefinitely. Hence the exact symptom: a manual gesture cuts
   following (`setOnTouchListener` → `onAutoFollowChanged(false)`), hence the republications,
   hence the read finally succeeds and everything appears at once. Fixed by `conflate()`: a
   started read always runs to completion, the areas that arrived meanwhile overwrite each
   other, and the most recent is processed next.
2. **The area was republished every second for nothing.** The polling sent the position at
   each turn, whereas the published area overflows the screen by `VIEWPORT_MARGIN`: as long
   as we have moved less than that margin, the screen is already covered. Worse,
   `getDisplayPoints` fully rereads tracks under `fullLoadLimit` points — a reread that does
   not even depend on the area. The polling now only republishes beyond
   `AUTO_FOLLOW_RELOAD_FRACTION` of the screen width traveled, or on a zoom change (to keep:
   without this second case, a zoom-in would stay on the coarse silhouette).

These two points were one of the "levers" left in arbitration below; they no longer appear
there.

**Third cause, found in 0.11.4 after a field test** that reported "better, but it still
glitches". The `pointCount <= fullLoadLimit` branch of `getDisplayPoints` was the only one to
cache nothing: a track of fewer than 60,000 points was fully reread from the database at each
republication of the area, whereas its result does not depend on the framing. In automatic
following, that amounted to asking SQLite again for tens of thousands of rows per displayed
track, and rebuilding as many objects, for a result identical to the previous one.
`fullPointsCache` fixes that; returning the same instance also makes `MapViewContainer`'s
comparison immediate, which used to go over every point to conclude nothing had changed.
Every write to a track's points already goes through `invalidatePointCaches`, so the cache
cannot stay stale.

What remains possible if the defect persists: tracks **above** `fullLoadLimit` redo a
`countPointsInBounds` then a `getDetailPointsInBounds` at each republication, these two
really depending on the area; and `MapViewContainer` invalidates its polylines as a block, so
that a single dense track that moves has those of all the others rebuilt on the main thread.
A per-track polyline cache would be the next step.

### Awaiting arbitration

- `VIEWPORT_MARGIN` went from 0.35 to 0.60 at the author's request. To evaluate. Watch the
  indirect effect documented above the constant: the widened area also serves
  `coversMostOfTrack`, so the display falls back sooner on the silhouette alone. If the line
  looks coarser, measure that coverage on the really visible area rather than on the widened
  one.
- `Importer` accumulates elevation **without threshold**, whereas recording and resuming go
  through `ElevationAccumulator`. To harmonize, but it would change the statistics of future
  imports.
- `FormatUtils` can format everything in miles and feet, `MainActivity` reads `pref_is_metric`
  at startup, but **no screen writes that preference**: imperial support is unreachable.
  Either add a setting, or remove the code.
- No automatic cut on an abnormal distance jump. An imported track whose file does not
  separate its segments therefore appears in one piece, without the app being able to guess.

## Dead weight: handled

The cleanup was done. What was removed, and must not come back by inadvertence:

- Dependencies declared and never called, remnants of an abandoned Drive backup: `retrofit`,
  `converter-moshi`, `moshi-kotlin` (+ its KSP processor), `okhttp`, `logging-interceptor`,
  `credentials`, `credentials-play-services-auth`, `googleid`. They traveled in every
  installed APK without a single line of code touching them.
  `work-runtime-ktx` had suffered the same fate, for the same reason — it has come back since
  with `UpdateCheckWorker` (see "Background notification"), a real caller this time.
- Dead code: `Exporter.exportToGPX` / `exportToKML` (in-memory variants, replaced by the
  incremental writers), `MediaStoreExporter.saveToLocalDownloads`,
  `TrackRepository.insertPoints`, `getPointsForTrackFlow`, `getSelectedImportedPointsFlow`
  and their DAO queries, `AutoBackupPreferences.isDestLocal` / `setDestLocal`.
- Second pass, `0.11.2`, after an audit of all the source code:
  - **Sharing a whole track.** `shareGPX` / `shareKML` had no caller any more — the button had
    disappeared from the interface, not the code: with them went `shareTrack`,
    `shareCachedFile`, `safeFileName`, the `cacheDir/exports` folder and its `<cache-path>`
    entry in `file_paths.xml`. This last point is not cosmetic: every path declared there can
    be exposed through the `FileProvider`, and an unused path is a surface offered for
    nothing.
  - Three never-called composables of `TrackingTab`: `SportRadarBackground`, `StatColumn`,
    `GpsStatusSquare` (about 270 lines).
  - `UpdatePrompt.findUpdate`, written for a manual search that never existed.
  - **Pace**: `LiveStats.paceMinPerKm` was computed in three places and
    `FormatUtils.formatPace` was called nowhere. No screen displayed it.
  - `TrackRepository.getPointsForTrack` and `setLivePoints`, with no caller.
  - 55 unused imports, mostly in `TrackingTab` and `HistoryTab`.
  - `OsmConfig.init` was called twice, in `TrackApplication` then in `MainActivity`; the
    second call did nothing (`isInitialized` guard).
- Asset never loaded: `france_boundary.geojson`.
- `MainScreen`'s GPS simulation was not guarded by `BuildConfig.DEBUG`, unlike
  `TrackingService`'s: on a real device whose heuristic emulator detection triggered wrongly,
  or simply with no active location provider, a fictitious blue dot circled in Paris.
  Fixed — invariant 8 applies to both.

- Third pass, before 1.0, at the author's request: **the "Remove stationary points" tool**.
  Gone with it: `TrackRepository.removeStationaryPoints`,
  `TrackViewModel.removeStationaryPoints`, the composables `RemoveStationaryPointsTool` and
  `SingleTrackRow` (of which it was the only caller), `formatThreshold`, the
  `Tool.REMOVE_STATIONARY` entry and `RemoveStationaryPointsTest`. `statsRecomputeLimit` and
  `calculateStatsFromPoints` stay: `loadResumeState` still uses them.

- Fourth pass, in 1.1, at the author's request: **CSV conversion**, both ways. Gone with it:
  `util/CsvConverter.kt` and `CsvConverterTest`, `TrackRepository.convertGpxKmlToCsv` /
  `convertCsvToGpxKml`, `TrackViewModel.convertGpxKmlToCsv` / `convertCsvToGpxKml`, the
  composable `CsvConversionTool` and `ToolsTab.kt`'s own `FormatCheckboxRow` (of which it was
  the only caller — `SettingsTab.kt`'s is a distinct block, never concerned), and the
  `Tool.CSV_CONVERT` entry. `MediaStoreExporter.saveToLocalDownloadsStreaming` stays: the
  automatic backup still uses it.

`Track.isMerged` is no longer dead weight: the column again carries the history's "Merged"
tab.

### Arbitrated, before 1.0

- **`TrackRepository._livePoints` grows without bound during a recording**, and each point
  copies the whole list (`_livePoints.value + point`). The cost is therefore quadratic over
  the duration, and the list ends up weighing several megabytes. The cap already exists for
  resuming (`liveTailLimit`, 20,000 points); applying it here would bound the cost, at the
  price of the start of the line disappearing from screen on a recording of more than five
  hours. **Left as is, at the author's request**: the cost remains hypothetical (few
  recordings exceed five hours), the fix's effect would be very visible.

### Left to arbitrate

- The `libs.versions.toml` entries matching the removed dependencies stayed: inert at
  compile time, they document what existed.

## Welcome screen

`ui/screen/WelcomeScreen.kt`, shown only once, on the very first launch: a short
presentation of the app, then the permissions requested (location, notifications from
Android 13), each explained in one sentence before being asked for. The "all the time"
location permission is not requested there: `MainScreen` chains it right after, with the
justification dialog that existed before this screen.

`util/OnboardingPreferences` holds the single preference that decides whether to show it
(`pref_onboarding_completed`). It is set on tapping "Get started", whether the permission
requested at that moment is granted or refused — the user's choice is enough to close the
welcome, they can always grant it later from the Record tab.

**Mandatory backfill, like for per-track colors** (see `TrackRepository.backfillDisplayColors`):
without it, a user who had already answered the permission requests and recorded tracks would
see this screen once too often, at the update that introduces it.
`OnboardingPreferences.backfillIfAlreadyUsed`, called by `TrackApplication` before any
database access, relies on two clues: the location permission already granted, or the
database file already present on disk. This second clue must be checked **before** opening
the database — opening it creates it if it did not exist yet, which would skew the detection.

`MainScreen` distinguishes, by means of a value frozen at composition
(`onboardingWasAlreadyCompletedAtStart`), a user who had already passed the welcome on a
previous launch — for whom the automatic re-request of the permission at startup keeps
applying, as before this screen was added — from a user who has just closed it. Without that
distinction, refusing the permission in the welcome screen immediately triggered a second
identical request, this time without explanation: the same effect keyed on the mutable state
fired again as soon as it turned true.

## App update

The app updates from **GitHub Releases**, without an app store.

`util/update/AppUpdate.kt` reads an `update.json` file attached to each release, through the
URL `https://github.com/OWNER/REPO/releases/latest/download/update.json`. That URL form is
stable: GitHub always redirects it to the most recent release, which avoids the GitHub API,
its quotas and its token.

```json
{
  "versionCode": 9,
  "versionName": "0.9.9",
  "apkUrl": "https://github.com/…/releases/download/v0.9.9/mes-parcours-0.9.9.apk",
  "sha256": "3b1f…",
  "notes": ["First change", "Second change"],
  "notesI18n": {"fr": ["Première nouveauté", "Deuxième nouveauté"]}
}
```

- The comparison is on **`versionCode`**, an integer. Comparing "0.9.10" and "0.9.9" as
  strings would conclude the opposite.
- `notes` is English; `notesI18n` is optional and keyed by language code. The app shows the
  notes of the phone's language and falls back on `notes`
  (`AvailableUpdate.notesFor`). Nothing else depends on the translations.
- `apkUrl` **must be HTTPS and served by `github.com`**: this file is going to be installed —
  it is the only place in the project where an address read elsewhere decides what will run on
  the device. Requiring HTTPS authenticates the transport; constraining the host means a
  tampered manifest cannot redirect the installation elsewhere. The check is on the **parsed
  host**, never on the text of the address: `https://github.com.example.test/` does start
  with "https://github.com" without being GitHub. `UpdateManifest.parse` rejects the rest.
  **The path is constrained too**, since the 1.3 security audit: only the attachments of this
  repository's releases (`/ToftMalone/Mes-parcours/releases/download/`) are accepted, with no
  encoding and no `..` segment that would allow leaving the prefix once the address is
  resolved by the server.
- As long as `UpdateConfig.GITHUB_OWNER` or `GITHUB_REPO` is empty, **no request is issued**
  and the app behaves as before.
- A failed search is silent: not reaching GitHub is none of the user's concern.
- `util/update/UpdateDownloader` downloads into a `.part` renamed at the end, so as never to
  present a truncated APK to the installer, and checks the announced size.
- **`sha256`: the APK's fingerprint, checked during writing.** The size check is not enough:
  `contentLength` is -1 when the server answers with chunked encoding, and an interrupted
  download then goes unnoticed. `release.yml` computes it at publication time.
  The field is **mandatory since the pre-1.0 audit**, absent as well as malformed: a manifest
  without a usable fingerprint no longer leads to any proposal. It had been optional so that
  releases predating its introduction stayed installable — a leniency without object, the app
  only ever consulting `releases/latest`, every release produced by `release.yml` carrying a
  fingerprint. Offering an update that cannot be verified is worse than offering nothing.
- **The APK is downloaded to internal storage** (`filesDir/updates`), and exposed to the
  installer by the FileProvider. External storage remained modifiable by another app between
  the fingerprint check and the tap on "Install": see "Security audit before 1.0", point 2.
- **The APK must carry the release's package name and the announced `versionCode`**, checked
  after download (`UpdateDownloader.isExpectedApk`); otherwise it is deleted without being
  presented. This is what makes the signature enforceable: Android refuses another key for
  the same package name, but would install a different name as a second app. See "Security
  audit of 1.3", point 2.
- Silent installation is impossible for an ordinary app: the system always shows its
  confirmation screen.

Ignoring the banner ("Later") does not make the update disappear: a red badge on the
"Settings" tab and a card at the top of that screen remain, with a button that reopens the
same banner. `MainScreen` keeps the detected update (`onUpdateAvailable`, called once by
`UpdatePrompt`) and an `updateReopenTrigger` counter incremented by that button;
`UpdatePrompt` reads it to reopen its dialog on the already known update, without
consulting the network again or restarting the app.

### Background notification

`UpdatePrompt` only consults the network when the app opens: an update published while it
stays closed for several days was only seen at the next launch, if there was one.
`util/update/UpdateCheckWorker` fills that gap with a notification, without paying for it in
battery.

- **Every three hours** (`UpdateCheckWorker.CHECK_INTERVAL_HOURS`), through a periodic
  `WorkManager` task (`PeriodicWorkRequestBuilder`), scheduled by `TrackApplication.onCreate`
  and taken up at each startup with no effect if it already is
  (`ExistingPeriodicWorkPolicy.KEEP` — replacing it at each launch would amount, on an app
  opened several times a day, to never letting the first delay elapse). WorkManager itself
  shifts this callback within a wide window and batches it with other apps' tasks: that is
  what sets it apart from a home-made timer, and what answers the main worry of a periodic
  check — draining the battery. `Constraints.setRequiresBatteryNotLow(true)` even gives up
  that beat when the device is already low.
  Downside of `KEEP`: changing that rate between versions does not change it by itself for
  an installation that had already scheduled it with the old one — the existing task is
  never replaced. Harmless as long as the app has not been published with an earlier rate.
- **A single notification per version.** `UpdateCheckPreferences` remembers the
  `versionCode` already notified; without that memory, the same ignored update would be
  reported again at each pass as long as it remains the most recent published.
- Directly reuses `UpdateChecker.fetchLatest` and `UpdateManifest`: no security or validation
  rule duplicated, the same address, the same mandatory fingerprint, the same GitHub host
  constraint.
- WorkManager had been removed from the project (see "Dead weight") for lack of a caller; it
  came back for this single use, which is exactly its role: a periodic task that survives the
  app restarting and respects system constraints rather than bypassing them.
- **`UpdateCheckWorker.schedule` is protected by `runCatching`.** `TrackApplication` calls it
  at each startup, including in Robolectric tests — which instantiate that same application
  without going through WorkManager's automatic initializer. Without this protection,
  `WorkManager.getInstance` threw an `IllegalStateException` there that made the 70 tests
  starting the application fail at once (everything using `RobolectricTestRunner`, even pure
  computations unrelated to updates) — found at the build of the first version published with
  this task.

## Publishing a release

**Only publish on the author's explicit request** ("publish", "publish 2.0.x"…). A request
for fixes, even accompanied by the release notes to display, is **not** a request to
publish: 2.0.3 was published that way by deduction, without the author having asked for it.
Fix, build, push and provide the debug APK, then wait for their go-ahead.

`.github/workflows/release.yml` does everything: tests, signed build, `update.json`, GitHub
publication. Two ways to trigger it, for an identical result.

**By an annotated tag**, from a development machine:

```bash
git tag -a v0.9.9 -m "Title" -m "First change" -m "Second change"
git push origin v0.9.9
```

The tag body becomes the notes displayed in the update dialog, one bullet per line. The
workflow **refuses to publish** if the tag does not match the `versionName` of
`app/build.gradle.kts`: without that safeguard, one would publish a version that nobody could
install over the previous one.

**By hand** (Actions tab → "Publish a release" → Run workflow), entering the notes, one per
line, **in English**. The tag is then deduced from the `versionName` and created by the
publication itself. The optional `notes_i18n` input takes translations as a JSON object keyed
by language code (`{"fr": ["…"], "es": ["…"]}`): they end up in `update.json`'s `notesI18n`
and the app shows the one matching the phone's language. Anything that is not a JSON object
is ignored.

This second route is not a convenience: **creating a tag requires write rights on references
that a Claude Code session on the web does not have.** It can push code to a branch, but
`git push origin <tag>` comes back to it as HTTP 403. Without this manual trigger,
publishing would require a development machine at hand — which defeats the point of being
able to develop from the web.

The tag ↔ `versionName` safeguard has nothing to check on this route, the tag being deduced
from the version. It is replaced by a refusal to republish an already published version,
which plays the same role: forcing the increment before starting again.

### Repository secrets to fill in

| Secret | Content |
| --- | --- |
| `KEYSTORE_BASE64` | The `.jks` keystore encoded in base64 |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |
| `KEY_ALIAS` | Key alias (`upload` by default) |

```bash
base64 -w 0 my-upload-key.jks > keystore.base64.txt
```

### Building a signed version locally

Create at the root a `keystore.properties` file, **never versioned**:

```properties
storeFile=C:/path/to/my-upload-key.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

`app/build.gradle.kts` reads it first, then falls back on environment variables — this
second route is the one taken by continuous integration. No password therefore ever appears
in the code or in the git history.

### Creating the keystore, once and for all

```bash
keytool -genkeypair -v -keystore my-upload-key.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```

**To be backed up somewhere other than the development machine.** Losing it makes every
later update impossible to install: one would have to start again from an uninstall, hence
from the loss of the users' tracks.

## Frozen identity

The project moved to **in-place updates**, which keep the user's tracks. Three points follow,
and they are no longer negotiable:

1. **`applicationId = "com.toche.mesparcours"` is final for the release.** Changing it would
   make every update fail: Android would install a second app next to the first, and the
   first one's database would become inaccessible. (It has successively been
   `com.aistudio.mytracks.eaddfb`, inherited from the AI Studio scaffold, then
   `com.toche.sillage` — that is over.) The `debug` build type, for its part, deliberately
   carries a different `applicationIdSuffix`: see "Commands" above for the reason
   (side-by-side installation with the release, never an update of one by the other).
2. **The signing key is final.** An APK signed by another key is refused with "package
   signatures do not match" and forces an uninstall. Distributed versions must therefore all
   be signed with the `release` key, never with `debug.keystore`.
3. **Every Room schema change requires its migration** (invariant 2). It was theoretical as
   long as we uninstalled each time; it is now vital, because `fallbackToDestructiveMigration()`
   would erase the tracks without warning.

Nothing references the `applicationId` in plain text: the manifest goes through
`${applicationId}.fileprovider` and Kotlin code through `context.packageName`.

The `namespace` and the Kotlin package `com.example` remain, for their part, those of the
scaffold. No effect at runtime, but renaming them would touch every source file as well as
`TrackingService`'s action constants (`"com.example.service.action.START"`, etc.).
