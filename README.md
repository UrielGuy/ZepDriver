# ZepDriver

An Android app for Burning Man Mutant Vehicle (art car) drivers:

- **Speed and zone compliance:** a big GPS speedometer, plus warnings that get
  louder as you go faster (5.5 / 6 / 6.5 mph) or get close to and enter
  restricted areas. The Esplanade, promenades and paths can be crossed but
  not driven along. The Great Circle, Center Camp and walk-in camping are
  off limits. The rules are coded per year and per license type.
- **Offline map and search:** a 3D playa map, search over camps, art and BRC
  addresses, favorites, and tap-for-info on the map: tap your car (a 3D art
  car, car, pickup, school bus or golf cart, your pick) to see your BRC
  address ("7:45 & Esplanade"), or tap a camp, art piece or fleet car.
- **Navigation:** multiple stops, an 80 ft art safe distance, street routing
  in the city that follows your license, and a one-tap **Potties** button.
- **Fleet mesh:** a cheap Meshtastic LoRa radio (Heltec V3), flashed and set
  up over USB from the app, then connected over Bluetooth. Every ZepDriver
  car shares its position and can text the others.
- **Trip log:** your own trail on the map, exportable to KML (Google Earth /
  My Maps) or GPX.

See [SPEC.md](SPEC.md) for the full product spec.

*This app is not affiliated, endorsed, or verified by Burning Man Project.*

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

### Testing at home

Debug builds (the APK CI publishes) have **Settings → Simulator**. It
replaces GPS with a simulated car at Black Rock City: drive it with an
on-screen pad, run a guided tour that sets off each warning in turn, or let
it follow the active route. Time can run faster (×2 to ×20) so you don't
have to wait out real playa speed, and long-pressing the map moves the car
there. Optional fake fleet cars show up in the fleet view. Simulated
positions are never sent over the mesh, but they are recorded in the trip
log. The simulator is always off when the app starts.

## Data

The app bundles last year's public data in `app/src/main/assets/playa/<year>/`.
The upcoming year's camps and art come from the Burning Man API at build
time and are sealed into the APK, following Burning Man's location embargo
(SPEC.md §6.1a). They are never committed.

**API key:** add it as a repository secret named `BURNING_MAN_API_KEY`
(GitHub → Settings → Secrets and variables → Actions → New repository
secret). CI passes it only to `tools/data/build_sealed.sh`. Without it,
builds simply ship last year's data. For a Play Store release, add the
Play App Signing certificate's SHA-256 as the repository variable
`ZEP_EXTRA_CERTS` so release builds can open the sealed data.

To regenerate last year's public bundle:

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
