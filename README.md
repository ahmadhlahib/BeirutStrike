# Beirut Strike

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![Min SDK](https://img.shields.io/badge/min%20SDK-26-blue)
![Firebase](https://img.shields.io/badge/backend-Firebase-FFCA28?logo=firebase&logoColor=black)
![Google Play](https://img.shields.io/badge/Google%20Play-closed%20testing-414141?logo=googleplay&logoColor=white)

**Beirut Strike** is a multiplayer third-person shooter for Android, set in real neighbourhoods of
Beirut. The streets and buildings are generated from OpenStreetMap data and drawn in 3D with
OpenGL ES. Players join a room, pick a team, and fight timed matches through the city. Positions,
shots and scores sync live between phones, and teammates can talk over voice chat.

> **Status:** version 1.2.0 is in **closed testing** on Google Play. The public release follows
> once the test is done.

---

## Contents

- [Features](#features)
- [Gameplay reference](#gameplay-reference)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Project structure](#project-structure)
- [Maps](#maps)
- [Characters](#characters)
- [People in the street](#people-in-the-street)
- [Gun models](#gun-models)
- [Testing](#testing)
- [Publishing](#publishing)
- [Contributing](#contributing)
- [Credits](#credits)
- [License](#license)

## Features

### World
- **Real Beirut maps:** Downtown, Beirut Souks, Hamra, Ain El Mreisseh and Raouche, built from
  OpenStreetMap data (building footprints and heights, roads, parks, squares, the sea and trees).
- **A realistic city:** textured facades (sandstone, plaster, concrete, glass) with shop fronts,
  asphalt streets with sidewalks, lane markings, zebra crossings and street lamps, palm and leafy
  trees, warm sunlight and sky reflections on glass and sea.
- **Traffic and people in the street:** cars drive on the right, turn at junctions and stop for
  people; their wheels roll and steer. Passers-by walk the sidewalks, stop to chat or talk on
  the phone, and run from gunfire. They're scenery: shooting them counts for no one.
- **Adjustable play area:** from 200 m to 800 m square, or the whole map.
- **Minimap** with a full-screen map view: teammates where they are, enemies only as wide pulsing red
  circles (120 m across) they are somewhere inside, never their exact spot (on or off when
  creating a room, for everyone in it, or in the solo setup), and a gun view (first
  person) or 3D person (third person) camera.

### Solo
- **Play against bots:** after choosing a name, pick **Solo** or **Multiplayer**. A solo game
  runs on the phone alone (no internet): choose the map, play area and length, 1 to 8 bots, their
  difficulty, and whether some bots fight on your team (**Bots on my team**: half of them).
- **Bots** walk the streets, find their way to you over the street network, spot you when
  nothing blocks their view, react and shoot with real guns. **Easy** bots see you up close,
  react slowly and miss a lot; **Medium** ones hunt you down and sidestep; **Hard** ones spot you
  from 110 m, react fast and lie down to shoot from afar. Moving and running make you harder
  to hit. Bots on different teams fight each other too. They don't throw grenades.
- Same scoreboard and results as online, with **Play again**. Solo games are practice: no XP,
  ranks or Ranking (`solo/SoloMatch.kt`, `solo/StreetGraph.kt`, `SoloMatchTest`).

### Multiplayer
- **Invites:** after creating a room (or from **Invite friends** in the menu), share it through
  the share menu, WhatsApp first. The link opens the website's join page (`docs/join/`), whose button opens the game
  on the room, password included (`RoomInvite.kt`, the `beirutstrike://join` link), or Google
  Play without the game.
- **Rooms:** public or password-protected. The password is checked by the database's security
  rules, not by the app.
- **Minimum rank:** a room's creator can let in only players of a chosen rank or higher, up to
  their own rank. The room list shows it, and the security rules enforce it against each
  player's career XP.
- **Teams:** three fictional factions in every room, each with its own flag, colours and uniform.
  Players can **add a team** to the room: a name, a flag picture from their phone and a colour
  (up to six per room). Everyone in the room sees it, and it can be reported with a long press.
  Teammates can't hurt each other.
- **Team voice chat:** talk to your teammates, phone to phone (WebRTC). Your mic is off until
  you turn it on, and you can mute all teammates. Voice is streamed live, never recorded.
- **Timed matches:** the room's creator sets the length, from 30 seconds to 1 hour. Every phone
  counts down to the same end time, which comes from the server.

### Combat
- **Twelve guns:** pick a pistol, a primary and a sniper rifle (Beretta M9 to Barrett M82), each
  with its own ammo, fire rate, accuracy, sound, recoil and detailed 3D model. See
  [Weapons](#weapons).
- **Touch controls built for shooting:** Shoot sits under the minimap; slide your finger on it to
  aim while you fire.
- **Pickups:** ammo packs and scopes are scattered through the streets and shared by the room.
  The first player to reach one gets it.
- **Scopes:** zoom in through a scope, with longer view, aim and bullet range.
- **Grenades:** frag, flashbang, smoke and molotov, thrown and bouncing in 3D.
- **Movement:** walk, run, jump and crawl. Crawling is slow, but makes you low and hard to hit.
- **Ladders:** yellow ladders up some buildings (marked on the minimap) lead to their roofs, to
  shoot down from above. Everyone sees you climb and stand up there, and can shoot you there too.

### Progression
- **Money:** every player starts with $2,000 and earns $300 per kill, $100 more for a headshot
  and $1,000 for winning, in online games that count toward careers (not solo or cheat rooms).
  The balance shows on the rank card and the guns screen, and is copied to the online career
  (`progression/Wallet.kt`, `online/CareerWallet.kt`).
- **Guns cost money:** the Beretta M9, AK-47 and SVD are free; the others are bought once on the
  guns screen (from $1,500 for the Glock 17 to $12,000 for the Barrett M82) and kept for good
  (`GunPrices`).
- **Live scoreboard** during the match and final results at the end: score, kills, deaths, K/D,
  shots, accuracy and hits taken, for players and teams.
- **Career ranking** across all matches, with face photos.
- **Military ranks:** 20 levels from جندي Private to أسطورة بيروت Beirut Legend, earned with XP
  from kills, headshots and wins. A rank-up celebration plays in the game, and the **Ranks /
  career** screen (tap your rank on the rooms screen) shows your progress and every rank.

### Social
- **Characters:** after choosing a team, pick who to play as (the built-in soldier, Ahmad El
  Lahib or Ali El Lahib), shown turning in 3D. Everyone wears army camouflage and boots in their
  team's colours, and everyone in the room sees your character.
- **Your face on your character (optional):** turn it on on the character screen, and the front
  camera and ML Kit face detection put your photo on the character's head. It's off by default,
  and never used on characters with a real face of their own.
- **Speech bubbles** and **photo drops** left in the city for other players to find.
- **Accounts:** play as a guest, or **Sign in with Google** on the name screen so your XP, rank,
  money and guns follow you to any phone. Signing in keeps a guest's progress; a Google account
  that already plays elsewhere is switched to instead (`online/GoogleAccount.kt`). It needs the
  Google provider enabled in Firebase Authentication and the app's SHA-1 fingerprints (debug,
  upload and Play app signing keys) added to the Firebase Android app.
- **Safety and privacy:** report or block other players, and delete all your data from the menu.

### Offline mode
Without a Firebase configuration, the app still builds and runs as a single-player sandbox.

## Gameplay reference

### Controls

| Control | Action |
| --- | --- |
| Joystick (bottom left) | Walk; push further to run |
| Medkits (beside the hearts, top left) | **+1** gives a heart back, **Full** fills them all; each shows how many you carry |
| Store (at an arms store's counter) | Opens the shop: magazines, a scope, medkits and grenades, and swapping to another gun you own |
| Swipe on the city | Look and aim |
| Big red button (under the minimap) | Shoot. Hold for the AK-47; tap for the pistol. Slide your finger on it to aim while firing |
| Jump / Crawl (small, under Shoot) | Jump, or lie down and crawl; Crawl is lit while lying down |
| Reload (round, bottom right) | Change the magazine |
| Scope (round, left of Reload) | Look through the scope (sniper rifles, the M4, or a primary with a found scope); lit while scoped |
| Zoom in / out (round, left of Scope) | Change the scope's magnification, while scoped |
| **Gun view / 3D person** (top, left of the minimap) | Switch between seeing through your soldier's eyes (gun in front) and seeing your soldier from behind |
| Grenade (round, under Gun view) | Throw a grenade of the kind shown beside it |
| Grenade kind (round, beside Grenade) | Change grenade: frag, flashbang, smoke, molotov (shows how many are left) |
| Mic / Speaker (small, beside the grenades; online) | Team voice chat: my mic on or off, and mute all teammates |
| Gun button (under the grenades) | Switch between your pistol, primary and sniper rifle (shows rounds in the magazine / spare) |
| Say / Drop photo (round, above the joystick) | Opens **Say** (speech bubble, or a cheat code, see below) and **Drop photo** (leave a photo in the street) above it |
| Left Shoot (red, right of Say / Drop photo) | A second Shoot button for the left thumb: works like the big one |
| Climb up / Climb down (above View photo) | Shows at the foot of a ladder, or beside its top on a roof: climbs it |
| Timer (top centre) | Open the scoreboard |
| Name button (top left) | Menu: scoreboard, players (report / block), change team, change guns, retake face photo, street photos, leave room, log out, delete my data |
| Minimap (top right) | Open the full map |

### Weapons

After choosing a team, each player picks **three guns**: a pistol, a primary and a sniper rifle
(defaults in bold). The gun button switches between them. Figures follow the real guns, scaled
to the game: distances are about a fifth of the real ones and bullets fly at a tenth of their
real speed, so they can be seen. Damage is in hearts; every player has 5.

| Slot | Gun | Magazine × mags | Fire | Reload | Range | Damage | Scope |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pistol | **Beretta M9** | 15 × 6 = 90 | semi-auto | 2.2 s | 40 m | 1 | – |
| | Glock 17 | 17 × 6 = 102 | semi-auto, quicker | 2.0 s | 38 m | 1 | – |
| | Desert Eagle | 7 × 6 = 42 | semi-auto, slow | 2.6 s | 45 m | 2 | – |
| Primary | **AK-47** | 30 × 4 = 120 | 600/min | 2.6 s | 80 m | 1 | pickup |
| | M4A1 | 30 × 4 = 120 | 800/min | 2.4 s | 90 m | 1 | 4× ACOG |
| | MP5 | 30 × 4 = 120 | 800/min | 2.5 s | 55 m | 1 | pickup |
| | RPK | 40 × 4 = 160 | 600/min | 3.2 s | 100 m | 1 | pickup |
| | M249 SAW | 100 × 3 = 300 | 750/min | 6.5 s | 100 m | 1 | pickup |
| Sniper | **SVD Dragunov** | 10 × 4 = 40 | semi-auto | 3.0 s | 170 m | 3 | 2× / 4× |
| | M24 | 5 × 4 = 20 | bolt action | 4.5 s | 200 m | 4 | 4× / 10× |
| | AWM | 5 × 4 = 20 | bolt action | 3.7 s | 215 m | 5 | 3× / 6× / 12× |
| | Barrett M82 | 10 × 4 = 40 | semi-auto | 4.5 s | 230 m | 5 | 5× / 10× |

- **Handling** differs too: hip-fire spread, recoil, and walking speed (the M249 and Barrett are
  heavy, the MP5 is light). Sniper rifles are only accurate through their scope.
- **Reloading** happens on its own when a magazine runs dry, or with the reload button. It takes
  as long as with the real gun, with its sounds (magazine out and in, slide, charging handle,
  belt cover or bolt), and switching guns cuts it short. Rounds left in the old magazine are kept.
- **Scopes:** tap the scope button to look through it, then zoom in and out between its
  magnifications. Sniper rifles and the M4 always have one; the other primaries can pick one up.

Killed players respawn after 4 seconds at a random street, with every gun loaded and at least
its starting rounds and grenades.

### Ladders

Each map has ladders up some ordinary flat-roofed buildings of one to seven storeys, at least
30 m apart, up to 14 in a room, the same for everyone. They are bright yellow, and marked on the
minimap by small yellow squares. Stand at the foot of one and tap **Climb up**: the climb is
automatic (3 m a second) and you can't shoot or throw on the way, so pick your moment. On the
roof you walk up to its edge but not off it; tap **Climb down** beside the ladder to come back.
Killed on a ladder, you end up at whichever end was nearer.

### Grenades

Every player also carries grenades, thrown with the grenade button towards where you look (a
weaker throw lying down; in gun view you see your hand pull back and throw it while the gun is
lowered). They bounce off walls, roofs and the ground, and every phone in the room
sees the same throw. Tap the button below it to change kind. More lie in the street (see
[Pickups](#pickups)), up to twice what you start with.

| Grenade | Carried | Goes off | Effect |
| --- | --- | --- | --- |
| Frag | 2 | after 3 s | Blast up to 7 m: 5 hearts right on it, fewer further away; walls stop it. It can hurt you too, and shakes the camera nearby |
| Flashbang | 2 | after 1.6 s | Blinds anyone who can see it within 24 m (worst up close and looking at it) and leaves their ears ringing; no damage |
| Smoke | 1 | after 1.8 s | A grey cloud 5.5 m across for 20 s: hides players and their name tags, and the crosshair can't pick out enemies through it |
| Molotov | 1 | on impact | Fire 3.2 m across for 8 s, taking a heart a second from anyone standing in it (you too) |

### Pickups and arms stores

Only magazines lie in the streets:

| Pickup | Per room | Effect | Respawns after |
| --- | --- | --- | --- |
| Rifle magazine | 8 | +1 magazine for the primary carried | 20 s |
| Pistol magazine | 6 | +1 magazine for the pistol carried | 20 s |
| Sniper rounds | 3 | +1 magazine for the sniper rifle carried | 40 s |

Every map has 4 **arms stores**: kiosks with a striped awning and a gold sign beside the streets,
the first one near the start, marked with a green **$** on the maps (`city/Stores.kt`, the same
spots on every phone). At a store's counter a **Store** button opens the shop (the game goes on
meanwhile):

| Item | Price | Limit |
| --- | --- | --- |
| Pistol / primary / sniper magazine | $100 / $200 / $300 | a magazine above the starting rounds |
| Scope (for a primary without one, until death) | $500 | 1 |
| Small medkit (+1 heart) | $150 | 5 carried |
| Big medkit (all hearts) | $400 | 3 carried |
| Frag / flashbang / smoke / molotov | $300 / $200 / $200 / $300 | as carried before |
| Swap to another gun you own (for its slot) | free | |

In solo games everything in the stores is free (solo games pay nothing either).

Medkits are used when you choose, with the buttons beside your hearts (top left): **+1** gives
one heart back, **Full** fills all five. Every life starts with 3 small and 2 big medkits; the
ones carried are lost when you die. Scope, grenade and medkit pickup spots are still in
`PickupKind.SLOTS` so older versions agree on them, but they aren't shown in the streets. Pickup
kinds must also be allowed in `firebase/database.rules.json` (`PickupKindsTest` checks it).

### Cheat codes

Cheats only work in rooms created with **Allow cheats (just for fun)**, and offline. Those rooms
are marked "Cheats on · not ranked" in the room list: scores there still show on the room's
scoreboard but **don't count toward the Ranking**, for anyone in the room.

Type a code in the **Say** box. It isn't shown to other players, and it only changes the game on
your phone. Case, extra spaces and a final `!` or `.` don't matter. In a normal room, a code just
shows "Cheats are off in this room".

| Code | Effect |
| --- | --- |
| `unlimited ammo` | Bullets never run out |
| `unlimited health` | Hits never cost a heart, and health goes back to full |
| `find a scope` | Gives you a scope for the AK-47 |
| `full health` | Health back to full, once |
| `super speed` | Walk and run twice as fast |
| `rapid fire` | Both guns fire automatically, twice as fast |
| `cancel cheats` | Turns every cheat off and takes back a cheat scope |

### Scoring and ranks

- **Score** = shots that hit an enemy. It ranks the match scoreboard; kills and then fewer
  deaths break ties.
- **Career score** adds up scores across all matches and ranks the Ranking screen.
- **XP** (online games without cheats only): kill +50, headshot +25 more (the killing shot hit
  the top of the body), win +500 (the top score, not shared).
- **Rank** follows from total XP. Everyone starts at level 1, جندي Private; level 20, أسطورة
  بيروت Beirut Legend, needs 45,500 XP.
- **Room minimum rank:** stored as that rank's XP (`roomList/{room}/minXp`). Opening the rooms
  screen brings this phone's XP and the online career's together, since rooms go by the career.

#### Ranks and XP: where to change things

| What | Where |
|---|---|
| XP needed for each level | `LEVEL_XP` in `progression/XpConfig.kt` |
| XP for each event | `XpReward` in `progression/XpConfig.kt` |
| Rank names and badges | `Rank` in `progression/Rank.kt` |
| Badge artwork | `res/drawable/rank_*.xml`: replace any of them under the same name. The placeholders come from `java tools/RankBadges.java app/src/main/res/drawable` |
| Rank-up sound | Add `assets/sounds/rank_up.wav` (`SoundEffects.rankUp`) |

Level and rank are never stored; they are worked out from total XP (`progression/Progression.kt`),
which is kept on the phone (`PlayerProgress`) and added to `career/{uid}/xp` online. A mission
system can give XP with `PlayerProgress.award(context, XpReward.MISSION)`, plus
`OnlineWorld.countXp` for the career. In debug builds, long-press the badge on the Ranks /
career screen for +500 XP to try a rank-up; tapping it replays your last promotion.

## Tech stack

| Area | Technology |
| --- | --- |
| Language | Kotlin 1.9, Java 11 bytecode |
| Build | Gradle 8.13 (Kotlin DSL), Android Gradle Plugin 8.11 |
| Platform | Android 8.0+ (min SDK 26), target SDK 36 (Android 16) |
| Rendering | Custom OpenGL ES 2.0 renderer; skinned glTF (`.glb`) characters and street people, glTF car models |
| Camera | CameraX 1.3, ML Kit face detection |
| Backend | Firebase Realtime Database and Authentication: guests (anonymous) or Sign in with Google via Credential Manager (fits the free Spark plan) |
| Audio | Gunshot and reload recordings plus effects synthesized at runtime |
| Voice chat | WebRTC (stream-webrtc-android 1.3), peer-to-peer between teammates, signalled through Firebase |
| Map data | OpenStreetMap, converted offline by a Java tool in `tools/` |

## Getting started

### Requirements
- Android Studio Narwhal (2025.1) or newer, with its bundled JDK
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
│   ├── *Activity.kt        Screens: login, face capture, rooms, team select, city, ranking, career
│   ├── Scoreboard.kt       Match scoreboard dialog
│   ├── RankUpOverlay.kt    Rank-up celebration
│   ├── progression/        Ranks, XP thresholds and rewards, progress (plain Kotlin, tested)
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

## Characters

Players choose a character after their team. The built-in soldier (`assets/models/soldier.glb`)
is always there; every folder in `app/src/main/assets/models/characters/` with a
`character.glb` adds another, found by the app on its own.

To add a Mixamo character:

1. Download the character and these animations from Mixamo as **FBX**, with **In Place** ticked
   for the moving ones, and keep the sources in `tools/models/characters/<id>/`.
2. Convert each to `.glb` with FBX2glTF, into `app/src/main/assets/models/characters/<id>/`:
   ```bash
   tools/bin/FBX2glTF.exe -b -i "character.fbx" -o app/src/main/assets/models/characters/<id>/character
   ```
3. Name the animation files after the clip they play:

   | File | Mixamo animation (for example) |
   | --- | --- |
   | `idle.glb` | Rifle Aiming Idle |
   | `shoot.glb` | Firing Rifle |
   | `walk.glb` | Walking |
   | `run.glb` | Rifle Run |
   | `run_back.glb` | Run Backwards |
   | `strafe_left.glb`, `strafe_right.glb` | Strafe (one each way) |
   | `death.glb` | Dying |
   | `jump.glb` (optional) | Jump |

4. Add `character.json`: `{"name": "Ahmad El Lahib", "ownFace": true}`. Set `ownFace` when the
   character has a real face, so players' face photos are never put on it.
5. Optionally, dances: download them from Mixamo as FBX (with skin is fine), convert each with
   FBX2glTF, strip it to the motion only (a few hundred KB instead of 10+ MB), and list them in
   `character.json`:
   ```bash
   java -cp "$GSON" tools/StripAnimation.java dance.glb app/src/main/assets/models/characters/<id>/dance_wave.glb
   ```
   ```json
   "dances": [{"file": "dance_wave", "name": "Wave hip-hop"}, {"file": "dance_soul", "name": "Northern soul spin"}]
   ```
   Each dance gets a button on the character screen to watch it, and when that character wins a
   game it plays one of them (in the 3D-person view, facing the camera) before the results.

Characters wear army kit: textures of materials named like clothes (`outfit`, `top`, `bottom`,
`shirt`, `pants`, `jeans`, `jacket`...) are repainted as woodland camouflage in the team's
uniform colours, keeping their folds and seams, and shoes (`shoe`, `sneaker`, `boot`) become
dark boots (`city/ArmyOutfit.kt`). Skin, face, eyes and hair keep their own textures. Add
`"army": false` to `character.json` to keep a character's own clothes. `ArmyOutfitTest` checks
it and writes the repainted textures to `app/build/army-outfit/` to look at.

Bone names with or without the `mixamorig:` prefix both work. `MixamoSoldierTest` checks every
character folder for the clips the game needs. Keep characters light: every player's character
is animated on the phone each frame (Ahmad El Lahib has about 45,000 vertices).

## People in the street

Cars and passers-by are made up on each phone (`city/CityLife.kt`). The 8 people nearest the
camera (within 35 m) can be drawn with real character models; everyone else, and everyone when
there are no models, is drawn from simple shapes.

The sources are Mixamo downloads (FBX), kept out of the app:

| Folder | What |
| --- | --- |
| `tools/models/pedestrians/characters/` | one `<name>.fbx` per person: any Mixamo character, downloaded as FBX (T-pose) |
| `tools/models/pedestrians/animations/` | shared by everyone, FBX with **In Place** ticked: `Walking.fbx`, `Running (1).fbx`, `Standing Idle.fbx`, `Talking On A Cell Phone.fbx`, `Running Turn 180.fbx` (turning to flee), and the deaths `Falling Back Death.fbx`, `Falling Forward Death.fbx`, `Flying Back Death.fbx` |

To add a person, put their FBX in `characters/` and run:

```bash
bash tools/build_pedestrians.sh
./gradlew testDebugUnitTest --tests "*PedestrianModelsTest*"
```

The script converts each character with FBX2glTF, cuts it to about a third of its triangles
with 1024 px WebP textures using gltfpack (`tools/bin/gltfpack.exe`, from
github.com/zeux/meshoptimizer; a Mixamo character drops from ~50 MB and ~30,000 vertices to
~1 MB and ~10,000), renames its bones from Mixamo's numbered `mixamorig7:` to `mixamorig:` so the
shared animations fit (`tools/RenameMixamoBones.java`), and strips the animations to their
motion. Everything goes to `app/src/main/assets/models/pedestrians/`. The test loads every
person with the shared animations, checks they are under 12,000 vertices and prints the sizes.
Every other person talks on the phone (instead of the standing idle) when they stop. People
can be shot (or caught in a grenade): they fall with one of the death clips (backwards when shot
from the front, forwards from behind) and lie there for 30 s; it counts for no one's score.
When there's shooting nearby, people heading towards it turn round (the turn clip) and run.

Each person in the street gets one of the models at random. Materials without a texture whose
names look like clothes (`shirt`, `pants`, `top`, `body`, `cloth`...) are recoloured per person,
so one model can make several different-looking people.

### Cars

The traffic is drawn with the car models in `app/src/main/assets/models/cars/` (`city/CarModels.kt`);
with none there, cars are drawn from simple shapes. Today they are from Kenney's
[Car Kit](https://kenney.nl/assets/car-kit) (CC0); the full kit is unpacked in
`tools/models/cars/kenney/` (ignored by git).

Every `.glb` in the folder is used, with its textures beside it (`Textures/colormap.png` for
Kenney). Nodes named `wheel…` with a side (`wheel-front-left`, `wheel-back-right`...) are the
wheels: they roll as the car drives, and the front ones steer into turns. Anything else,
including a spare wheel with no side, is the body. Low-poly kits are toy-shaped, so bodies are
stretched to real proportions (`SCALE_X`, `SCALE_Y`, `SCALE_Z`) with round wheels resting on the
road. How often each car appears is set in `WEIGHTS`.

To add or swap cars, copy the `.glb` files (and their textures) into the folder and run:

```bash
./gradlew testDebugUnitTest --tests "*CarModelsTest*"
```

The test checks each car's size, that it has four wheels on the road (two that steer) and that
the traffic rolls and steers them, and prints each car's size and vertex count.

## Gun models

The guns in Gun view and in soldiers' hands are 3D models from Sketchfab (credits in
`assets/guns3d/CREDITS.txt`). A gun without a model is drawn from boxes (`city/GunModels.kt`).

To replace or add one:

1. Download the model from Sketchfab as **glTF** and save the zip as
   `tools/models/guns/<gun>.zip`, named after the gun (`m9`, `glock17`, `deagle`, `ak47`, `m4`,
   `mp5`, `rpk`, `m249`, `svd`, `m24`, `awm`, `m82`), then unzip it into a folder of that name.
2. See which way it points, then set its line in `GUNS` in `tools/GunModelTool.java`: the axes
   pointing to the muzzle and up, the real length, and where the grip and left hand go:
   ```bash
   GSON=$(find ~/.gradle/caches -name "gson-2.8.9.jar" | head -1)
   java -cp "$GSON" tools/GunModelTool.java orient <unzipped models dir> grid.png
   ```
3. Pack them into the app, with a picture marking the grip, left hand and muzzle to check:
   ```bash
   java -cp "$GSON" tools/GunModelTool.java pack <unzipped models dir> app/src/main/assets/guns3d check.png
   ```

The first-person arms come from the animated "AK74U | FREE ANIMATION" pack
(`tools/models/guns/ak74u_arms.zip`, unzipped to an `ak74u_arms` folder): the arms in its idle
pose, without its gun, cut into a right arm placed on each gun's grip and a left arm placed on
its handguard. Their sleeves take the team's uniform colour:
```bash
java -Xmx3g -cp "$GSON" tools/GunModelTool.java fpsarms <unzipped models dir>/ak74u_arms app/src/main/assets/guns3d
```
(The tool's older `arms` command poses the simpler rigged "First Person hands rigged" model instead.)

## Testing

```bash
./gradlew testDebugUnitTest
```

Unit tests cover map loading, the soldier model and animations, sound synthesis, the match clock,
scoreboard ranking, army ranks, and weapon and pickup rules.

## Publishing

The app is on Google Play under the package name `com.alahib.beirutstrike`. Version 1.2.0
(versionCode 7) is in **closed testing**; production comes after the test (Play asks for at
least 12 testers opted in for 14 days in a row). Raise `versionCode` before every upload.

- **[PLAY_STORE.md](PLAY_STORE.md):** release checklist: developer account, Firebase, upload key,
  building the `.aab`, testing tracks and going live.
- **[STORE_LISTING.md](STORE_LISTING.md):** store listing text, content rating and Data safety
  answers.
- **[PRIVACY_POLICY.md](PRIVACY_POLICY.md):** the privacy policy linked from the store.

**Forcing an update:** set `config/minVersion` in the Realtime Database (Firebase console →
Realtime Database → Data) to the oldest versionCode still allowed. Versions from 1.3.1 on show
"Update required" and only let the player open Google Play; every version records its
versionCode at `versions/{uid}`, and the rules only let versions at or above the minimum create
or join rooms, so versions older than the check (1.3.0 and before) are kept out of online games
too. Raise it only once the new version is live on every track its players use. Delete
`config/minVersion` to lift it (`online/AppVersion.kt`).

Release builds are signed with an upload key described in a local, git-ignored
`keystore.properties`:

```bash
./gradlew bundleRelease   # → app/build/outputs/bundle/release/app-release.aab
```

## Contributing

`main` is protected: every change goes through a pull request and needs the owner's approval.

1. Create a branch from `main`, for example `feature/my-change`.
2. Make sure `./gradlew assembleDebug testDebugUnitTest` passes.
3. Open a pull request describing the change, and note any change to the database rules.

## Credits

- Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, available
  under the [Open Database License](https://opendatacommons.org/licenses/odbl/).
- Soldier model by [Quaternius](https://quaternius.com); animations from
  [Mixamo](https://www.mixamo.com). The Ahmad El Lahib character and its animations are from
  Mixamo too; their FBX sources are in `tools/models/characters/ahmad/`.
- Car models from the [Car Kit](https://kenney.nl/assets/car-kit) by [Kenney](https://kenney.nl) (CC0).
- Model conversion with [FBX2glTF](https://github.com/facebookincubator/FBX2glTF).
- Gunshot and reload recordings (by Beeld en Geluid and Mike Koenig) from
  [Wikimedia Commons](https://commons.wikimedia.org) under CC BY-SA; details in
  [`app/src/main/assets/sounds/CREDITS.txt`](app/src/main/assets/sounds/CREDITS.txt). The belt rattle
  and the cries are generated by the game.
- 3D gun models and first-person arms from [Sketchfab](https://sketchfab.com) under CC BY 4.0, most by TastyTony
  (also Friendly and quick_loop), and BURNER for the arms; details in
  [`app/src/main/assets/guns3d/CREDITS.txt`](app/src/main/assets/guns3d/CREDITS.txt).
- Gun photos on the loadout screen from [Wikimedia Commons](https://commons.wikimedia.org),
  public domain or under Creative Commons licences; authors and licences are in
  [`app/src/main/assets/guns/CREDITS.txt`](app/src/main/assets/guns/CREDITS.txt) and in the app
  (**Photo and sound credits** on the loadout screen). The adapted photos keep their original licences.

## License

No license has been chosen yet, so all rights are reserved by the author. Contact the repository
owner before reusing the code or assets.
