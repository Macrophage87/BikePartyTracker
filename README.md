# Bike Party Tracker

An Android app that lets everyone in a bike party / group ride see each other on one map — with ride roles, a shared route, and one-tap incident alerts.

## Features

- **Live location sharing, even when minimized.** A foreground service keeps publishing your position (every ~4 s) with the screen off or the app in the background.
- **GPX route import.** The ride leader loads a GPX file; the route is drawn on the map.
- **Leader-run rides with a password.** The leader picks a ride code, sets a password, and loads the route. The route is automatically shared with everyone who joins with that code + password.
- **Ride roles with distinct map icons.** Each rider picks a role — *Lead line, Corker, Sweep, Soundbike, Participant, Observer* — shown as a color-coded badge on the map. Roles can be changed mid-ride.
- **One-tap incident sharing.** First aid needed, emergency vehicle, detour required, mechanical, road hazard, stop & regroup. Incidents pin to the reporter's location, pop up as heads-up notifications for the whole group, and can be cleared by the reporter or the leader.

## How it works

There is **no app server to run**. Riders sync through any MQTT broker (the public
`broker.hivemq.com` by default — configurable in the app under *Server (advanced)*):

- Each rider publishes their position as a *retained* MQTT message, so late joiners instantly see the whole group.
- The leader's route and all incidents are retained the same way.
- **Everything is end-to-end encrypted.** The ride code + password derive both the AES-256-GCM message key (PBKDF2) and the hashed topic name, so the broker (and anyone else on it) sees only ciphertext on an unguessable topic. Riders without the password can neither find nor decrypt the ride.

Leaving a ride publishes an empty retained message, which removes your marker from everyone's map.

Maps are OpenStreetMap tiles via [osmdroid](https://github.com/osmdroid/osmdroid) — no Google Maps API key required.

## Using it

**Ride leader**
1. Enter your name, pick your role.
2. Enter (or generate) a ride code and set a password.
3. Toggle *I'm the ride leader* and optionally load a GPX route.
4. Tap *Start / join ride* and share the code + password with the group.

**Everyone else**
1. Enter your name and role.
2. Enter the ride code + password from the leader.
3. Tap *Start / join ride*.

On the map: the yellow-ringed marker is you. Tap **Report incident** to alert the group, **Incidents** to review/clear active ones, **Role** to change your role mid-ride.

## Building

Requires JDK 17+ and the Android SDK (API 34).

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Min SDK 26 (Android 8.0), target SDK 34.

## Privacy & safety notes

- Location is shared only while a ride is active; leaving the ride stops sharing and removes your retained position from the broker.
- The default public broker is fine for casual rides because payloads are encrypted, but you can point the app at your own broker (e.g. Mosquitto) for full control.
- Ride codes/passwords are never sent anywhere — they only derive keys locally.
