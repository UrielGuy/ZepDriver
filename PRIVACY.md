# ZepDriver privacy policy

*Last updated: October 21, 2026*

ZepDriver is a free, non-commercial app for Burning Man art car (Mutant
Vehicle) drivers. It has no accounts, no ads and no analytics, and the
developer runs no servers that receive your data.

## What the app uses, and where it goes

**Your location.** ZepDriver uses your phone's GPS while it's running to
show your speed and position, warn you about restricted areas, and navigate.
It stays on your phone unless you connect a Meshtastic radio:

- **With a radio connected**, the app broadcasts your car's position, your
  car name and any texts you send over the LoRa radio mesh, so other
  ZepDriver cars nearby can see you. Anyone with a compatible radio on the
  same channel can receive these. Nothing is sent over the internet.
- **Without a radio**, your location never leaves the phone.

**Trip log.** If you leave "Record my trips" on, the app keeps your own GPS
track on the phone so it can show your trail and let you export it. You can
turn recording off, have old trips deleted automatically, or delete them all
at any time in Settings → Trip log. By default the trip log is excluded from
Android's backup. If you choose "Back up to my Google account", it becomes
part of your phone's Android backup with Google, under your Google account.
Exported files go only where you choose to share them.

**Settings and favorites** (your car's profile, home camp, favorites) are
stored on the phone and included in Android's backup if you have it on.

**Internet.** When the phone is online, the app downloads map and camp data
updates from this project's GitHub repository. That request carries no
personal information. GitHub, like any web server, sees the phone's IP
address. The app uses the time in the response to check the date.

**Bluetooth and USB** are used only to connect to and set up your own
Meshtastic radio.

## What we don't do

- No accounts, tracking, analytics, ads or selling of data.
- No location or personal data is sent to the developer or to any server.

## Children

ZepDriver is meant for adult art car drivers and isn't directed at children.

## Changes and contact

Changes to this policy are published here, in the app's repository, with
their date. Questions: open an issue at
https://github.com/UrielGuy/ZepDriver/issues

This app is not affiliated, endorsed, or verified by Burning Man Project.
