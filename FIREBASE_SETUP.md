# Turning on multiplayer (Firebase)

Without these steps the game still builds and plays, but only on one phone
("Offline · only this phone" under your name in the city). After them, everyone who
installs your build shares the same city: they see each other walking, their speech
bubbles, faces and dropped photos.

Everything below fits in Firebase's free **Spark** plan: photos and faces are stored in
the Realtime Database, so Cloud Storage (which needs the paid Blaze plan) is not used.

## 1. Create the project
1. Go to <https://console.firebase.google.com> and **Add project** (Google Analytics is not needed).
2. **Build → Authentication → Get started → Sign-in method → Anonymous → Enable.**
   Players never see a login; each phone gets a hidden id that the security rules use.
3. **Build → Realtime Database → Create database.** Pick a location close to your players
   and start in **locked mode**.
4. In the database's **Rules** tab, replace everything with the contents of
   [`firebase/database.rules.json`](firebase/database.rules.json) and **Publish**.

## 2. Add the Android app
1. **Project settings (gear) → Your apps → Android.**
2. Package name: `com.alahib.beirutstrike` (the `applicationId` in `app/build.gradle.kts`).
3. Download **`google-services.json`** and put it in the **`app/`** folder
   (`beirutrun/app/google-services.json`, next to `app/build.gradle.kts`), not the project root.
   Do this *after* creating the Realtime Database, so the file contains the database URL:
   open it and check it has a `"firebase_url"` line.
4. **Skip the console's "Add Firebase SDK" step.** The project is already set up; the plugin
   and `firebase-bom` lines it suggests would clash with the versions pinned here and break the build.
5. Build and run. The label under your name should read **Online**.

## Checking it works
Install on two phones (or a phone and an emulator), log in with different names and walk
towards each other. Each shows up as the other's chosen character with their face, their name
tag, and a blue dot on the minimap. "Say" bubbles and dropped photos appear on both.

## What is stored where
| Path | What | Written by |
| --- | --- | --- |
| `players/{uid}` | Name, character, position, speech bubble. Deleted automatically when the phone disconnects or the app goes to the background. | That player only |
| `faces/{uid}` | Face photo (small WebP, base64) | That player only |
| `drops/{id}` | Where a photo was dropped, its caption and author | Its author (can also remove it) |
| `dropPhotos/{id}` | The dropped photo (JPEG, base64, ~100–200 KB) | Its author |
| `hits/{uid}/{id}` | A bullet that hit that player; their phone counts it (5 kill) and deletes it | The shooter creates it; the victim removes it |
| `rooms/{room}/stats/{uid}` | Scoreboard: kills, deaths, shots, hits, hits taken. Kept when the player leaves. | That player only |
| `rooms/{room}/pickups/{slot}` | Ammo packs (10 bullets for the pistol or the AK-47) and scopes lying in the street, and when someone took one. Taking and putting one back are transactions, so only one player gets each. | Any member of the room |
| `reports/{id}` | Reports of a player, message or photo, for the owner to review in the Firebase console. The app can only add reports, never read them. | The reporter |
| `career/{uid}` | A player's totals over every game: shots, successful shots (their score), kills, deaths, hits taken, XP and wins. Used by the Ranking screen; XP decides their military rank (see `progression/`). | That player only |
| `roomList/{room}/duration`, `startedAt` | Game length (30 s to 1 h) and when the game started. Each can only be set once. | Length: the room's creator. Start: the first player into the city |

**After updating the app, publish [`firebase/database.rules.json`](firebase/database.rules.json)
again.** Older rules don't know about game lengths and scores, and refuse to create rooms.

Shots and health travel with each player in `players/{uid}` (`shotSeq`, `health`, `dead`).
The shooter's phone decides whether a bullet hit, which keeps the game simple but means a
modified app could cheat; fine among friends, not for a public game.

Each phone downloads a photo or face once and keeps it, so the free plan's 10 GB/month of
downloads goes a long way.

## Troubleshooting
- **"Can't reach the online city"**: usually the rules weren't published, or Anonymous sign-in
  isn't enabled. Check Logcat for the `OnlineWorld` tag.
- **Stays on "Connecting…"**: the phone has no internet, or `google-services.json` was downloaded
  before the database existed (download it again).
- Photos dropped while offline are kept on the phone and shared the next time it goes online.
