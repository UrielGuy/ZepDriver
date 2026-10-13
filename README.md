# ZepDriver

An Android app for Burning Man Mutant Vehicle (art car) drivers:

- **Speed and zone compliance:** a big GPS speedometer, plus warnings that get
  louder as you go faster (5.5 / 6 / 6.5 mph) or get close to and enter
  restricted areas. The Esplanade, promenades and paths can be crossed but
  not driven along. The Great Circle, Center Camp and walk-in camping are
  off limits. The rules are coded per year and per license type.
- **Offline map and search:** a 3D playa map, search over camps, art and BRC
  addresses, favorites, and your position shown as a BRC address
  ("7:45 & Esplanade").
- **Navigation:** multiple stops, an 80 ft art safe distance, street routing
  in the city that follows your license, and a one-tap **Potties** button.
- **Fleet mesh:** a cheap Meshtastic LoRa radio (Heltec V3), flashed and set
  up over USB from the app, then connected over Bluetooth. Every ZepDriver
  car shares its position and can text the others.
- **Trip log:** your own trail on the map, exportable to KML (Google Earth /
  My Maps) or GPX.

See [SPEC.md](SPEC.md) for the full product spec.

## Install (testers)

On your Android phone, open
**https://github.com/UrielGuy/ZepDriver/releases/download/latest-debug/zepdriver-debug.apk**
and install it. If Android asks, allow installing apps from your browser.
That link always points at the newest build, and each new build installs as
an update that keeps your settings.

## Building

```sh
./gradlew :core:test          # domain logic + tests (plain JVM)
./gradlew :app:assembleDebug  # Android APK (needs the Android SDK)
```

CI (GitHub Actions) runs both and uploads the debug APK as the
`zepdriver-debug-apk` artifact.

In environments without Google's Maven repository, pass `-Pzep.skipApp=true`
to build only `:core`.

## Data

The app bundles one year's data in `app/src/main/assets/playa/<year>/`. To
regenerate it:

```sh
git clone https://github.com/burningmantech/innovate-GIS-data
git clone https://github.com/iBurnApp/iBurn-Data
python3 tools/data/build_playa_data.py \
  --gis innovate-GIS-data \
  --api iBurn-Data/data/2026/APIData/APIData.bundle \
  --extra iBurn-Data/data/2026/geo \
  --year 2026 --out app/src/main/assets/playa/2026
```

Installed apps pick up a new bundle automatically: when they have internet,
they check `manifest.json` on this repo's default branch.

Data is subject to the
[Terms of Service for Burning Man APIs and Datasets](https://innovate.burningman.org/terms-of-service-for-burning-man-apis-and-datasets/).
Map data: Burning Man Project GIS. Art and camp data: Burning Man Public API
via [iBurn-Data](https://github.com/iBurnApp/iBurn-Data).

## Yearly maintenance

A few weeks before each burn, review the DMV rules and update the coded
ruleset, then rebuild the data bundle for the new year (SPEC.md §13).

## Radio hardware

- **Board:** Heltec WiFi LoRa 32 V3, US 915 MHz version, about $20 on Amazon.
- **Setup:** plug it into the phone with a USB-C OTG cable, then go to
  Settings → Radio → *Flash & set up USB radio*. The Meshtastic firmware
  ships inside the app, so this works offline.
- **Antenna:** use a better one than the stock antenna, mounted high on the
  car.
