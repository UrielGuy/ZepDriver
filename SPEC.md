# ZepDriver — Product Spec (V1)

An Android app for Burning Man Mutant Vehicle (art car) drivers: speed and
zone compliance, offline maps and search, navigation, and a fleet mesh so
every car running the app can see the others.

V1 targets **Black Rock City 2027**. Until 2027 data exists, the app ships with
the 2026 data (official GIS layers + the Public API snapshot) and the coded 2026
ruleset.

---

## 1. Principles

- **Offline first.** Everything works with no internet. Network is a bonus.
- **Rules are coded, not configured.** Drivers never draw zones or edit
  rules. Each year's DMV rules ship inside the app as a versioned ruleset
  (see §13, Annual maintenance).
- **Glanceable while driving.** Big digits, big buttons, voice for anything
  important. Nothing fiddly is possible while the vehicle moves.

---

## 2. Driving modes

| Mode | Entered when | What the driver can do |
|------|--------------|------------------------|
| **Parked** | Speed < ~0.5 mph for a few seconds and no active route | Everything: search, favorites, settings, plan routes, messaging, flashing the radio, export trips |
| **Moving** | Speed above threshold | Read-only. Live controls: the **Potties** button (inserts nearest porta-potty bank as the next stop), panning/zooming the map with **Re-center**, and tapping the map for info (no actions; the popup closes itself) |

Stopped mid-route the app unlocks: searching and choosing **Go now** makes
the place the next stop of the current route (or starts a route if there is
none).

Warnings, voice, mesh position sharing and trip logging run in both modes.
Mode switching uses hysteresis so stop-and-go traffic doesn't flap.

---

## 3. Speedometer and speed warnings

- Constant, large GPS speedometer (mph). Uses the platform Doppler speed
  (`Location.getSpeed()`), smoothed over a short window.
- Escalation (speed limit is 5 mph; thresholds include GPS tolerance):

| Speed (art car defaults) | Level | Output |
|-------|-------|--------|
| ≥ 5.5 mph | Caution | Speedometer turns amber |
| ≥ 6.0 mph | Warning | Red + loud siren + vibration + voice "Slow down" |
| ≥ 6.5 mph | Alarm | Flashing red, loud repeating siren, voice |

- The three thresholds are configurable per car (Settings → Car), in
  0.5 mph steps, never above 10 mph. Defaults depend on the vehicle type:
  5.5 / 6 / 6.5 mph for mutant and accessibility vehicles, 8 / 9 / 10 mph
  for bikes.

- Each level needs a short dwell time to trigger and to clear (hysteresis).
- Audio can be routed to the vehicle sound system (Bluetooth / aux).

---

## 4. Zones and rules engine

### 4.1 Zone types (coded per year)

| Type | Meaning | 2026 zones |
|------|---------|------------|
| **No-entry** | May not be driven into at all | Great Circle around the Man, Center Camp plaza, the Center Camp frontage (Rod's Road where it replaces the Esplanade, 5:35–6:24, and the 6:00 road from it to the plaza), walk-in camping (beyond K, 2:00–5:00), outside the trash fence |
| **Cross-only** | May cross, may not drive along | Esplanade (its open-playa side is fine), 3/6/9/12 promenades, community paths |
| **License-restricted** | Only some licenses | City streets for playa-only (incl. LCU) licenses: direct route camp ↔ open playa only |
| **Sound-off** | Amplified sound must be off while active | City streets 2:00–10:00 (quiet hours); Temple burn perimeter on Temple burn night |
| **Caution** | Reminder only | Plazas; Man burn perimeter on burn night |

Rules live in code (`core/.../rules/RuleSet2026.kt`) with their sources.
Items not quoted directly from DMV documents are marked `VERIFY` there and
must be checked in the annual review (§13). Geometry comes from the Burning
Man GIS data.

### 4.2 Warning levels

| Level | Trigger | Output |
|-------|---------|--------|
| **Advisory** (soft) | Crossing a cross-only road; within lookahead of a restricted zone | Subtle chime + banner ("Crossing Esplanade — don't drive along it") |
| **Approach warning** | Predicted to enter a no-entry zone within N seconds at current heading and speed | Tone + voice ("Restricted zone ahead") |
| **Violation alarm** (loud) | Inside a no-entry zone, or driving *along* a cross-only road | Loud repeating alarm, flashing screen, voice |

"Driving along" a road = inside the road corridor with heading roughly
parallel to the road for more than a set distance (≈1.5× the road width or a
few seconds), so a normal perpendicular crossing never alarms.

### 4.3 Vehicle and license profile (preferences)

Every car configures its own license once, in Parked mode. Used by warnings
and routing:

- License class:
  - **Mutant Vehicle**: city streets and open playa
  - **Mutant Vehicle, playa only** (includes large / LCU vehicles): city only
    on the direct route between home camp and the open playa
  - **Accessibility Vehicle**: may drive city streets; the participant with
    the disability must be aboard; max 20 ft × 7 ft; same pedestrian-area
    restrictions; not subject to the MV sound policy
- License hours: day / night / both (warns when driving outside them,
  using sunrise/sunset at BRC)
- Has a sound system (enables sound-policy reminders)
- Home camp (BRC address)
- Vehicle length and width
- Where the phone is mounted on the vehicle (offset from the front), so
  geofences and art buffers are measured from the vehicle's actual edges,
  not the phone

---

## 5. Location display

- Tapping your own car on the map shows where you are as a **BRC address**
  (plus your speed): `7:45 & Esplanade`,
  `4:30 & G (mid-block)`, or `Deep playa 2:00 @ 3,400 ft`.
- Search accepts BRC addresses ("4:30 & G").
- Tapping anything else on the map shows a small popup: fleet cars (address,
  when last heard, speed), camps, art, potties, places, favorites, Home and the
  landmarks (name, kind, address, short description), or the BRC address of
  an empty spot. Parked, it offers **Go now**, **Add stop** and **Save**.
- The driving view keeps the map's center clear: no address bar; GPS / radio /
  route-preparation indicators sit in a small corner chip, and in landscape
  the alert and navigation panels are capped in width at the top start.

---

## 6. Data

### 6.1 Sources

- **GIS data** (innovate.burningman.org, no key): street centerlines and
  outlines, plazas, trash fence, toilets, points of interest.
- **Burning Man Public API** (key required): camps and art with locations.
  Fetched **at build time** and bundled. The API key never ships in the app.

### 6.2 Updates

- The app checks a project-hosted manifest (in this repo) whenever a
  connection exists and downloads newer bundles in the background
  (WorkManager). Each file is verified against the manifest's SHA-256 and
  swapped in atomically; the last good bundle is kept as a fallback.
- Bundle signing is a later improvement; V1 relies on HTTPS + checksums.

### 6.3 Search and favorites

- Full-text search over camps and art (name, description, address).
- Favorites named by the driver (defaulting to the place's own name), plus Home. Home is always shown on the map; favorites are marked on it too.

---

## 7. Map

- MapLibre Native, fully offline vector tiles generated from GIS data.
- 3D view (tilted, heading-up while moving) with extruded art pieces,
  camp blocks, other app vehicles (from the mesh) and the active route.
- Zones rendered by type (no-entry, cross-only, active time-bound).
- 3D models of the Man (on the 2026 "Cryptomeria" tower), the Temple
  ("Temple of the Moon") and Center Camp's Canopy.
- Home always shown; favorites marked with their names.
- Our own car is a blocky 3D model, drawn larger than life (×3) so it's easy
  to find, with a dot when zoomed out. Settings → App → *My vehicle on the
  map*: art car (default, sized from the car profile), car, pickup truck,
  school bus or golf cart.

---

## 8. Navigation

- Multiple stops, reorderable in Parked mode.
- **Art safe distance:** configurable, default **80 ft**, measured from the
  vehicle's edges (uses the vehicle profile).
- **Routing rules depend on the license profile:**
  - Open playa: obstacle-aware routing (art buffers + no-entry zones).
  - City: street-graph routing only where the license allows driving in
    the city (e.g. to/from home camp). Cross-only roads are crossed, never
    followed.
  - If a destination can't legally be reached by the vehicle, route to the
    closest legal point and say "Park here, walk ~N ft".
- **Potties button:** inserts the nearest porta-potty bank (by route
  distance) as the next stop. Available in all modes.
- Voice turn-by-turn guidance in the city only. On the open playa the route,
  next-turn banner, distance and ETA are shown but not spoken (warnings
  always speak).

---

## 8a. Voice

- Every fixed warning sentence (speed, zones, license, sound policy) is
  pre-recorded at build time with Piper's public-domain LJSpeech voice and
  bundled, so warnings always speak, even without an offline phone voice.
  The sentences come from one catalog in core (`Speech`), checked by tests.
- Turn-by-turn directions (camp and street names) use the phone's
  text-to-speech. On first run, and in Settings, the app checks for an
  offline English voice and offers Android's install screen, plus a Test
  voice button.

## 9. Day / night themes

- Chosen automatically from the ambient light sensor, with hysteresis.
- Falls back to sunrise/sunset times if the sensor is missing or blocked.
- Day: high-contrast, sunlight-readable. Night: dark, dim, red-shifted.

---

## 10. Fleet mesh (Meshtastic)

### 10.1 Hardware

- **Recommended board:** Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262), US
  915 MHz version, ~$20 on Amazon. Upgrade the antenna and mount it high.
- Powered from vehicle USB.

### 10.2 Flashing

- Plug the board into the phone over USB-OTG. The app detects it (CP2102
  USB-serial), flashes a bundled Meshtastic firmware build, then writes the
  ZepDriver config.
- Implementation: `usb-serial-for-android` + a Kotlin port of the esptool
  ROM bootloader protocol.

### 10.3 Network

- **One shared ZepDriver channel compiled into the app**: same channel
  name, key and LoRa settings for everyone. No QR codes or setup.
- Use a frequency slot / modem preset separate from Meshtastic's default
  public channel to avoid playa congestion.
- The shared key is not a secret (anyone can extract it from the APK),
  so mesh traffic should be treated as public to anyone determined.
- Modem preset MEDIUM_FAST, hop limit 3, to keep airtime low with many cars.
- The Meshtastic firmware image for the Heltec V3 (GPL-3) is bundled in the
  APK at build time (fetched from Meshtastic's releases by Gradle), so
  flashing works offline. The app shows the GPL notice and a link to the
  exact firmware source; if the bundled image is missing it downloads it. The app talks to the radio with a
  small hand-written protobuf codec rather than the GPL `.proto` files.
- Position broadcast interval adapts to motion (more often when moving,
  rarely when parked) to stay within LoRa airtime limits.

### 10.4 Features

- **Car name required:** the first connection to a radio asks for the car
  name; the app won't connect until one is set. It becomes the node's
  owner name, which other cars see.
- Show every app vehicle on the map with its name and time last seen.
- Short text messages between app instances (composed in Parked mode).
- Only current positions are shared. Trails are never sent over the mesh.

---

## 11. Trip logging

- Records the GPS track, speeds and warning events while driving.
- Export as **KML** (opens in Google Earth, importable into Google My Maps)
  and **GPX**, via the Android share sheet.
- **Privacy, set up ahead of time:**
  - Recording can be turned off entirely; then nothing is written.
  - Retention: keep trips 1 day, 1 week, 1 month, or until deleted. Older
    points are deleted automatically.
  - Backup is the driver's choice: by default the log stays on the phone
    (Android's no-backup folder, never in a cloud backup). Opting in moves it
    to the regular database folder, which Android backs up to the driver's
    Google account.
  - The log is never sent over the mesh or to any server. It can be deleted
    at any time.
- **Own trail on the map:** optionally show our own car's path over the
  last N hours or days (configurable window). Drawn from the local log only.

---

## 12. Out of scope for V1

- Events / schedules
- Sharing trails over the mesh
- Choosing a different year's map
- Peer-to-peer data sharing between phones

---

## 13. Annual maintenance (required before every burn)

**A few weeks before each burn:**

1. Review the current DMV Mutant Vehicle rules and sound policy on
   burningman.org. Update the coded ruleset (zone types, cross-only
   roads, no-entry areas, time windows, speed limit) and bump its version.
2. Pull the new year's GIS data and re-generate map tiles and zone geometry.
3. Re-fetch camps and art from the Public API (once locations are
   released) and publish a new data bundle.

---

## 14. Tech stack

- Kotlin, Jetpack Compose, Android min SDK 26
- `:core`: pure-Kotlin module with all domain logic (rules, geometry,
  routing, BRC addresses, search, mesh protocol, flasher, exports), unit
  tested against the real data
- MapLibre Native Android (offline GeoJSON layers, 3D extrusions)
- In-memory search index; SQLite for the trip log
- Foreground service for location, warnings, mesh and logging
- WorkManager for data updates
- Meshtastic protobufs over BLE; `usb-serial-for-android` for flashing
- Android TextToSpeech for voice

## 15. Phases

1. Speedometer, speed warnings, zones and rules engine, offline map, BRC
   address display, day/night themes, modes, vehicle/license profile
2. Search, favorites, routing (multi-stop, art buffer, license-aware),
   Potties button, voice guidance, trip logging + export
3. Meshtastic flashing, fleet positions, messaging

## 16. Open questions

- Burning Man API key access and permission to bundle camp/art data
- Which license types and limitations to support in the profile
- Hosting for the update manifest and data bundles
