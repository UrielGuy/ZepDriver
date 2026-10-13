# ZepDriver — Product Spec (V1)

An Android app for Burning Man Mutant Vehicle (art car) drivers: speed and
zone compliance, offline maps and search, navigation, and a fleet mesh so
every car running the app can see the others.

V1 targets **Black Rock City 2027**. Development uses the public 2026 data.

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
| **Moving** | Speed above threshold, or a route is active | Read-only. The only live control is the **Potties** button (inserts nearest porta-potty bank as the next stop) |

Warnings, voice, mesh position sharing and trip logging run in both modes.
Mode switching uses hysteresis so stop-and-go traffic doesn't flap.

---

## 3. Speedometer and speed warnings

- Constant, large GPS speedometer (mph). Uses the platform Doppler speed
  (`Location.getSpeed()`), smoothed over a short window.
- Escalation (speed limit is 5 mph; thresholds include GPS tolerance):

| Speed | Level | Output |
|-------|-------|--------|
| ≥ 5.5 mph | Caution | Speedometer turns amber |
| ≥ 6.0 mph | Warning | Red + tone + vibration + voice "Slow down" |
| ≥ 6.5 mph | Alarm | Flashing red, loud repeating alarm, voice |

- Each level needs a short dwell time to trigger and to clear (hysteresis).
- Audio can be routed to the vehicle sound system (Bluetooth / aux).

---

## 4. Zones and rules engine

### 4.1 Zone types (coded per year)

| Type | Meaning | Examples |
|------|---------|----------|
| **No-entry area** | May not be driven into at all | Man base / burn perimeter, Center Camp area, Temple area, outside the trash fence |
| **Cross-only road** | May cross it, may not drive along it | Esplanade, promenades / keyhole roads, other restricted roads per that year's DMV rules |
| **Time-bound zone** | Active only during set times | Burn-night perimeters, quiet hours |
| **License-restricted** | Depends on the driver's license profile | City streets for playa-only licenses |

Exact zone membership comes from the current year's DMV rules, verified
before each burn. Geometry comes from the Burning Man GIS data.

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

Set once in Parked mode, used by warnings and routing:

- License type: day / night, full city / playa-only, any other
  license limitations
- Home camp (BRC address)
- Vehicle length and width
- Where the phone is mounted on the vehicle (offset from the front), so
  geofences and art buffers are measured from the vehicle's actual edges,
  not the phone

---

## 5. Location display

- Current position shown as a **BRC address**: `7:45 & Esplanade`,
  `4:30 & G (mid-block)`, or `Deep playa 2:00 @ 3,400 ft`.
- Search accepts BRC addresses ("4:30 & G").

---

## 6. Data

### 6.1 Sources

- **GIS data** (innovate.burningman.org, no key): street centerlines and
  outlines, plazas, trash fence, toilets, points of interest.
- **Burning Man Public API** (key required): camps and art with locations.
  Fetched **at build time** and bundled. The API key never ships in the app.

### 6.2 Updates

- The app checks a project-hosted manifest whenever a connection exists
  and downloads newer bundles in the background (WorkManager). Data is
  swapped in atomically; the last good bundle is kept as a fallback.
- Bundles are signed so the app only accepts project-published data.

### 6.3 Search and favorites

- Full-text search over camps and art (name, description, address).
- Favorites with labels: Home, My art piece, plus any custom places.

---

## 7. Map

- MapLibre Native, fully offline vector tiles generated from GIS data.
- 3D view (tilted, heading-up while moving) with extruded art pieces,
  camp blocks, other app vehicles (from the mesh) and the active route.
- Zones rendered by type (no-entry, cross-only, active time-bound).

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
- Voice turn-by-turn guidance.

---

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
- Position broadcast interval adapts to motion (more often when moving,
  rarely when parked) to stay within LoRa airtime limits.

### 10.4 Features

- Show every app vehicle on the map with its name and time last seen.
- Short text messages between app instances (composed in Parked mode).

---

## 11. Trip logging

- Records the GPS track, speeds and warning events while driving.
- Export as **KML** (opens in Google Earth, importable into Google My Maps)
  and **GPX**, via the Android share sheet.
- Stored on the device only; deletable.

---

## 12. Out of scope for V1

- Events / schedules
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
- MapLibre Native Android (offline vector tiles, 3D extrusions)
- Room / SQLite FTS for search
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
