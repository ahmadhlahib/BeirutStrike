# Beirut Strike

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![Min SDK](https://img.shields.io/badge/min%20SDK-26-blue)
![Firebase](https://img.shields.io/badge/backend-Firebase-FFCA28?logo=firebase&logoColor=black)

**Beirut Strike** is a multiplayer third-person shooter for Android, set in real neighbourhoods of
Beirut. The streets and buildings are generated from OpenStreetMap data and drawn in 3D with
OpenGL ES. Players join a room, pick a team, and fight timed matches through the city. Positions,
shots and scores sync live between phones.

---

## Contents

- [Features](#features)
- [Gameplay reference](#gameplay-reference)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Project structure](#project-structure)
- [Maps](#maps)
- [Testing](#testing)
- [Contributing](#contributing)
- [Credits](#credits)
- [License](#license)

## Features

### World
- **Real Beirut maps:** Downtown, Beirut Souks, Hamra, Ain El Mreisseh and Raouche, built from
  OpenStreetMap data (building footprints and heights, roads, parks, squares, the sea and trees).
- **Adjustable play area:** from 200 m to 800 m square, or the whole map.
- **Minimap** with a full-screen map view, and first- or third-person camera.

### Multiplayer
- **Rooms:** public or password-protected. The password is checked by the database's security
  rules, not by the app.
- **Seven teams,** each with its own flag, colours and uniform. Teammates can't hurt each other.
- **Timed matches:** the room's creator sets the length, from 30 seconds to 1 hour. Every phone
  counts down to the same end time, which comes from the server.

### Combat
- **Two guns:** a semi-automatic pistol and a fully automatic AK-47, each with its own ammo,
  fire rate, accuracy, sound, recoil and detailed first-person model.
- **Pickups:** ammo packs and scopes are scattered through the streets and shared by the room.
  The first player to reach one gets it.
- **Scope:** 4× zoom for the AK-47, with longer view, aim and bullet range.
- **Movement:** walk, run, jump and crawl. Crawling is slow, but makes you low and hard to hit.

### Progression
- **Live scoreboard** during the match and final results at the end: score, kills, deaths, K/D,
  shots, accuracy and hits taken, for players and teams.
- **Career ranking** across all matches, with face photos.
- **Army ranks:** players with more than 100 successful shots become a **★ Commander**; everyone
  else is a Soldier.

### Social
- **Your face on your soldier:** the front camera and ML Kit face detection put your photo on
  your character's head.
- **Speech bubbles** and **photo drops** left in the city for other players to find.

### Offline mode
Without a Firebase configuration, the app still builds and runs as a single-player sandbox.

## Gameplay reference

### Controls

| Control | Action |
| --- | --- |
| Joystick (bottom left) | Walk; push further to run |
| Swipe on the city | Look and aim |
| **Shoot** | Fire. Hold for the AK-47; tap for the pistol |
| Gun button | Switch between the pistol and the AK-47 (shows bullets left) |
| **Scope** | Zoom in (AK-47 with a scope only) |
| **Jump** / **Crawl** | Jump, or lie down and crawl |
| **1st / 3rd person** | Switch camera view |
| **Say** / **Drop photo** | Speech bubble / leave a photo in the street |
| Timer (top centre) | Open the scoreboard |
| Name button (top left) | Menu: scoreboard, change team, retake face photo, street photos, leave room, log out |
| Minimap (top right) | Open the full map |

### Weapons

| | Pistol | AK-47 |
| --- | --- | --- |
| Fire mode | One shot per tap | Automatic while held |
| Fire rate | 1 shot per second | 10 shots per second |
| Starting bullets | 12 | 30 |
| Accuracy | High | Moderate spread |
| Ammo pack | +10 | +10 |

Each player has 5 health, and each hit removes 1. Killed players respawn after 4 seconds at a
random street and get at least their starting bullets back.

### Pickups

| Pickup | Per room | Effect | Respawns after |
| --- | --- | --- | --- |
| AK-47 ammo | 5 | +10 AK-47 bullets | 20 s |
| Pistol ammo | 4 | +10 pistol bullets | 20 s |
| Scope | 2 | Enables the AK-47 scope until death | 60 s |

### Scoring and ranks

- **Score** = shots that hit an enemy. It ranks the match scoreboard; kills and then fewer
  deaths break ties.
- **Career score** adds up scores across all matches and ranks the Ranking screen.
- **Rank:** ★ Commander above 100 career hits, otherwise Soldier.

## Tech stack

| Area | Technology |
| --- | --- |
| Language | Kotlin 1.9, Java 11 bytecode |
| Build | Gradle (Kotlin DSL), Android Gradle Plugin 8.7 |
| Platform | Android 8.0+ (min SDK 26), target SDK 34 |
| Rendering | Custom OpenGL ES 2.0 renderer, skinned glTF (`.glb`) soldier models |
| Camera | CameraX 1.3, ML Kit face detection |
| Backend | Firebase Realtime Database and Anonymous Authentication (fits the free Spark plan) |
| Audio | Sound effects synthesized at runtime (no audio assets) |
| Map data | OpenStreetMap, converted offline by a Java tool in `tools/` |

## Getting started

### Requirements
- Android Studio Ladybug (2024.2) or newer, with its bundled JDK
- An Android 8.0+ device or emulator with OpenGL ES 2.0
- Optional, for multiplayer: a Firebase project

### Build and run

```bash
git clone https://github.com/ahmadhlahib/BeirutStrike.git
cd BeirutStrike
./gradlew assembleDebug        # or open the project in Android Studio and run "app"
```

The debug APK is written to `app/build/outputs/apk/debug/`.

### Enable multiplayer

Multiplayer needs your own Firebase project. `google-services.json` is **not** kept in this repo.
Follow **[FIREBASE_SETUP.md](FIREBASE_SETUP.md)**, which covers creating the project, enabling
Anonymous sign-in, publishing the security rules from
[`firebase/database.rules.json`](firebase/database.rules.json), and placing `google-services.json`
in `app/`.

> **Important:** republish `firebase/database.rules.json` every time it changes. Older rules
> reject newer app features, such as creating rooms with a match length.

## Project structure

```
app/src/main/
├── java/com/example/beirutrun/
│   ├── *Activity.kt        Screens: login, face capture, rooms, team select, city, ranking
│   ├── Scoreboard.kt       Match scoreboard dialog
│   ├── Army.kt             Army ranks
│   ├── city/               3D renderer, map loading, soldiers and animation, weapons,
│   │                       pickups, scope overlay, minimap, joystick, sound
│   └── online/             Firebase sign-in, rooms, live player sync, stats, pickups, faces
├── assets/                 Maps (maps/*.bin + previews), soldier model, team flags, logo
└── res/                    Layouts, strings, drawables
firebase/                   Realtime Database security rules
tools/                      OpenStreetMap map pipeline
```

The database layout, and what each part stores, is described in
[FIREBASE_SETUP.md](FIREBASE_SETUP.md#what-is-stored-where).

## Maps

Maps are defined in [`tools/maps.txt`](tools/maps.txt): a bounding box, a start point and an
optional parent map to cut from. To add or rebuild a map:

1. Add a line to `tools/maps.txt`.
2. Run the pipeline. It needs Java 17+, and Gson from the Gradle cache, so build the app once first:
   ```bash
   bash tools/build_maps.sh <map-id>   # no arguments rebuilds every map
   ```
3. Register the map in `city/CityMaps.kt`.

The script downloads the area from OpenStreetMap and writes `assets/maps/<id>.bin` and a
top-down preview image.

## Testing

```bash
./gradlew testDebugUnitTest
```

Unit tests cover map loading, the soldier model and animations, sound synthesis, the match clock,
scoreboard ranking, army ranks, and weapon and pickup rules.

## Contributing

`main` is protected: every change goes through a pull request and needs the owner's approval.

1. Create a branch from `main`, for example `feature/my-change`.
2. Make sure `./gradlew assembleDebug testDebugUnitTest` passes.
3. Open a pull request describing the change, and note any change to the database rules.

## Credits

- Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, available
  under the [Open Database License](https://opendatacommons.org/licenses/odbl/).
- Soldier model by [Quaternius](https://quaternius.com); animations from
  [Mixamo](https://www.mixamo.com). Mixamo source files are not redistributed in this repository.
- Model conversion with [FBX2glTF](https://github.com/facebookincubator/FBX2glTF).

## License

No license has been chosen yet, so all rights are reserved by the author. Contact the repository
owner before reusing the code or assets.
