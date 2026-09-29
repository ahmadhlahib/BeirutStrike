# Beirut Strike

A multiplayer third-person shooter for Android, set in real neighbourhoods of Beirut. The streets
and buildings are generated from OpenStreetMap data and drawn in 3D with OpenGL ES. Players join a
room, pick a team, and walk, run, crawl, jump and shoot their way around the city. Everyone sees
each other live.

Made for fun, to play with friends.

## Features

- **Real Beirut maps.** Downtown, Beirut Souks, Hamra, Ain El Mreisseh and Raouche, built from
  OpenStreetMap: building footprints and heights, roads, parks, squares, the sea and trees.
- **Online rooms.** Create a room (optionally with a password) or join one from the list. Players
  in the same room share the same city.
- **Teams.** Six teams, each with its own flag, colours and uniform. The team picker shows how
  many players are on each team.
- **Combat.** Shooting with health bars, hit detection and deaths. You can also crawl (slow, low
  and hard to hit) and jump.
- **Timed games and scoreboard.** The room's creator sets the game length, from 30 seconds to
  an hour. A countdown shows how much time is left. When it runs out, a scoreboard shows every
  player's and team's score, kills, deaths, K/D, shots, accuracy and hits taken. You can also open
  the live scoreboard from the top-left menu at any time. When the game is over, you can go straight
  to creating a new room.
- **Ranking and army ranks.** A player's score is how many of their shots hit an enemy, added up
  over every game. The Ranking screen lists every player with their face, score, shots, kills and
  success rate. Players with more than 100 successful shots become a ★ Commander; everyone else is
  a Soldier.
- **Your face on your soldier.** The front camera and ML Kit face detection put your face on
  your character's head.
- **Say and drop.** Speech bubbles over your character, and photos you drop in the city for
  other players to find.
- **Minimap**, a full-map view, on-screen joystick and swipe-to-aim controls.
- **Animated soldiers** (Quaternius and Mixamo models) and synthesized sound effects.
- **Offline mode.** Without Firebase set up, the game still builds and plays on a single phone.

## Tech

- Kotlin, Android SDK 26+ (target 34), Gradle Kotlin DSL
- OpenGL ES 2.0 renderer with skinned glTF (`.glb`) models
- CameraX + ML Kit face detection
- Firebase Realtime Database + Anonymous Auth for multiplayer (fits the free Spark plan)

## Getting started

1. Open the project in Android Studio and run the `app` configuration on a phone or emulator.
2. To turn on multiplayer, follow [FIREBASE_SETUP.md](FIREBASE_SETUP.md). It covers creating the
   Firebase project, the database rules in [`firebase/database.rules.json`](firebase/database.rules.json)
   and where to put `google-services.json`.

## Project layout

| Path | What |
| --- | --- |
| `app/src/main/java/.../beirutrun/` | Screens: login, face capture, rooms, team select, city |
| `.../beirutrun/city/` | 3D renderer, map loading, soldiers and animation, minimap, joystick, sound |
| `.../beirutrun/online/` | Firebase sign-in, room directory and live player sync |
| `app/src/main/assets/` | Map files (`maps/*.bin`), soldier models, team flags, logo |
| `firebase/` | Realtime Database security rules |
| `tools/` | Map build pipeline and source model/animation files |

## Adding or rebuilding maps

Maps are listed in [`tools/maps.txt`](tools/maps.txt) (bounding box and start point). To add one,
add a line there, run the build script (needs Java 17+ and one Gradle build first for Gson), and
register the map in `city/CityMaps.kt`:

```bash
bash tools/build_maps.sh <map-id>   # or no arguments to rebuild all maps
```

This downloads the area from OpenStreetMap and writes `assets/maps/<id>.bin` and a preview image.

## Credits

- Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL)
- Soldier model by Quaternius; animations from Mixamo
