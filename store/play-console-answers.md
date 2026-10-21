# Play Console: suggested answers

Policy → App content. These match what the app does today; re-check them
whenever features change.

## Privacy policy
`https://github.com/UrielGuy/ZepDriver/blob/HEAD/PRIVACY.md`

## Ads
No, the app contains no ads.

## App access
All functionality is available without special access. Reviewer note:

> ZepDriver is built for Black Rock City, Nevada. Away from it, the map still
> shows the city, but warnings and navigation only act on positions inside it. Camp and art locations appear on Burning Man's
> release dates each year. The radio features need a Meshtastic LoRa radio
> (Heltec V3) and are optional.

## Content rating (IARC questionnaire)
- Category: "All other app types"
- Violence, sexuality, language, drugs, gambling: none
- Users can interact or exchange content: **Yes** (text messages over the radio mesh)
- Shares the user's current physical location with other users: **Yes** (over the radio mesh)
- Digital purchases: No

## Target audience and content
Ages **18 and over** only. Not designed for children.

## Data safety
- Does the app collect or share user data? **Yes**
- Is all data encrypted in transit? **No** (the radio mesh uses a channel key
  that's public in this repo, so treat it as unencrypted)
- Account creation: none. Data deletion: the developer stores no user data; trip
  logs are deleted in the app (Settings → Trip log).

| Data type | Collected | Shared | Optional | Purpose |
|---|---|---|---|---|
| Location → Precise location | Yes (sent over the radio mesh when a radio is connected) | No* | Yes | App functionality |
| Messages → Other in-app messages | Yes (texts over the radio mesh) | No* | Yes | App functionality |

\* Play doesn't count data sent to other users at the user's direction as
"sharing" (user-initiated transfers). If in doubt, declaring it as shared
is the conservative choice.

Not collected: the trip log, settings and favorites stay on the phone
(Android's own backup isn't app collection).

## Advertising ID
No, the app doesn't use an advertising ID.

## Foreground service permissions
The app declares only `FOREGROUND_SERVICE_LOCATION` (the Bluetooth radio link
runs inside the same location service, since it exists to share the car's
location).

- Tasks: **Navigation** and **User-initiated location sharing**
- Description:

  > ZepDriver is a driving co-pilot for art car drivers at Black Rock City.
  > While the driver has started driving, a foreground service with an
  > ongoing notification uses GPS to give spoken speed and restricted-area
  > warnings and turn-by-turn navigation, which must keep working with the
  > screen off or another app in front. If the driver has connected a LoRa
  > radio, the same service shares the car's position with other cars in
  > their fleet. The service stops when the driver taps Stop in the
  > notification.

- Video: [videos/foreground-service-location.mp4](assets/videos/foreground-service-location.mp4)
  (upload it to YouTube as **Unlisted** and paste the link). It shows a
  route being driven, the app going to the background, the notification
  updating with speed, destination and ETA, and the app coming back with
  the drive continued.

## Other declarations
News app: No · Health: No · Financial features: No · Government app: No ·
COVID-19 apps: No · Background location (`ACCESS_BACKGROUND_LOCATION`): not
requested.
