# Store listing and Play Console answers

Ready-to-paste text for the Google Play store listing, and the answers for the Play Console's
questionnaires. See [PLAY_STORE.md](PLAY_STORE.md) for the full release checklist.

## App details

| Field | Value |
| --- | --- |
| App name (max 30) | `Beirut Strike` |
| Category | Game → **Action** |
| Tags | Shooter, Multiplayer, Action |
| Contact email | `Ahmad.h.lahib@gmail.com` |

### Short description (max 80 characters)

```
Team shooter in real Beirut streets. 12 guns, 20 military ranks, live matches.
```

### Full description (max 4000 characters)

```
Beirut Strike is a fast multiplayer shooter played in 3D recreations of real Beirut neighbourhoods,
built from OpenStreetMap data: Downtown, the Souks, Hamra, Ain El Mreisseh and Raouche.

JOIN A ROOM, PICK A SIDE
Create a public or password-protected room, choose the map, the play area and the match length
(30 seconds to 1 hour), and invite your friends. Pick one of three factions, or create your own
team with its own name, flag and colour, and fight until the clock runs out. Want a tougher
match? Set a minimum rank, and only experienced players can join.

12 REAL GUNS
Pick your loadout from 12 guns, each with its own 3D model, sound, recoil, magazine and reload:
• Pistols: Beretta M9, Glock 17, Desert Eagle
• Rifles and machine guns: AK-47, M4A1, MP5, RPK, M249 SAW
• Sniper rifles: SVD Dragunov, M24, AWM, Barrett M82
Ammo runs out, so hunt the streets for ammo packs and scopes, and line up headshots from across
the city.

MOVE LIKE A SOLDIER
Walk, run, jump and crawl. Take cover behind real buildings, and switch between first- and
third-person view.

RISE THROUGH 20 MILITARY RANKS
Every kill, headshot and win earns XP. Climb from Private (جندي) through Sergeant, Captain and
General, all the way to Beirut Legend (أسطورة بيروت). Each promotion brings a new badge and a
rank-up celebration, and your badge shows on the scoreboard and the ranking. Follow your
progress, career stats and every rank on the Ranks / Career screen.

SCORE AND COMPETE
A live scoreboard tracks score, kills, deaths, K/D and accuracy for every player and team, and
the global ranking adds up your results across all matches.

MAKE IT YOURS
Choose your character and celebrate a win with a victory dance. Put your own face on the soldier
with the front camera, talk with speech bubbles, and leave photos in the streets for other
players to find.

• No account or sign-up needed: just type a name and play
• No ads
• Report and block tools, and a one-tap "Delete my data"

Map data © OpenStreetMap contributors.
```

### Graphics to prepare 🧑

| Asset | Size | Notes |
| --- | --- | --- |
| App icon | 512 × 512 PNG, 32-bit | Same design as the launcher icon, no transparency around the edges |
| Feature graphic | 1024 × 500 JPG/PNG | Title art: shown at the top of the listing |
| Phone screenshots | 2–8, 16:9 or 9:16, 1080 px+ on the short side | Suggested: the city in third person, first person with a gun, the scope, the ranks / career screen, the scoreboard, the room list |

Use only the game's own art in screenshots and graphics: fictional factions, no real party
flags or symbols. Keep other players' names and face photos (e.g. the Ranking screen) and cheat
rooms (the "∞" ammo, cheat banners) out of them.

**Screenshots:** `store/screenshots/` holds 8 at 1920 × 1080, each with a caption in the game's
gunmetal-and-gold style. They're made from raw phone screenshots in `store/raw/` (git-ignored):
```bash
java store/MakeScreenshots.java store/raw store/screenshots
```
Edit `SHOTS` in that file to pick other raw shots or captions.

**Trailer (Play's video field):** cut from a phone screen recording with ffmpeg, in the same
style, then uploaded to YouTube (Public or Unlisted, ads off, not age-restricted, embedding on):
```bash
java store/MakeTrailer.java <ffmpeg.exe> store/raw/<recording>.mp4 <work folder> store/raw/beirut_strike_trailer.mp4
```
Edit `SCENES` in that file to re-cut it. Record gameplay with the phone held landscape *before*
starting the recorder, so it fills the frame.

**AI declaration:** the app icon and the feature graphic were made with AI and are labelled as
such in Play Console; the screenshots and trailer are real game captures.

## Content rating questionnaire

Answer as **Game**, and:

| Question area | Answer |
| --- | --- |
| Violence | **Yes:** realistic violence against human characters with guns. No blood or gore. No violence against real people or events |
| Fear / horror | No |
| Sexuality, nudity | No |
| Language | No profanity in the game itself (players can type messages; see below) |
| Controlled substances (drugs, alcohol, tobacco) | No |
| Gambling / simulated gambling | No |
| **Users can interact or exchange content** | **Yes:** players chat with speech bubbles, share photos and create teams (name and flag picture) in rooms |
| Shares user's location with other users | No (positions are inside the game's map only) |
| Allows purchases of digital goods | No |
| Unrestricted internet access | No |

Expect roughly **PEGI 16 / ESRB Teen–Mature 17+ / USK 16**.

## Data safety form

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data encrypted in transit?** Yes (Firebase uses TLS; voice chat uses WebRTC's DTLS-SRTP).
**Do you provide a way for users to request that their data be deleted?** Yes (in the app, and by email). Deletion URL: <https://ahmadhlahib.github.io/BeirutStrike/delete-data/>.

| Data type | Collected | Shared* | Optional | Purpose |
| --- | --- | --- | --- | --- |
| Personal info → **Name** (player name) | Yes | No | No (a name is needed to play) | App functionality |
| Personal info → **User IDs** (Firebase anonymous ID) | Yes | No | No | App functionality, fraud prevention/security |
| Photos and videos → **Photos** (face photo, dropped photos, team flags) | Yes | No | Yes | App functionality |
| Messages → **Other in-app messages** (speech bubbles, captions) | Yes | No | Yes | App functionality |
| Audio → **Voice or sound recordings** (team voice chat, live only while the mic is on; sent phone to phone, never stored) | Yes | No | Yes | App functionality |
| App activity → **Other actions** (gameplay: game stats, scores, XP and rank) | Yes | No | No | App functionality |
| App info and performance → **Diagnostics** (sent by Google ML Kit face detection: device model, OS version, performance, error codes) | Yes | No | Yes (only with the face photo) | Analytics |
| Device or other IDs → **Device or other IDs** (ML Kit's per-install ID for those diagnostics) | Yes | No | Yes (only with the face photo) | Analytics |

ML Kit's part comes from [its data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
Only **Voice or sound recordings** is processed ephemerally (tick "Yes" for it); none of the others is.

\* In Play's terms, showing content to other users of the same app is **not** "sharing", and
Firebase acts as a service provider, which is not sharing either.

Not collected: location, contacts, financial info, health, email, phone number, web browsing,
music files, other audio files, files, calendar, crash logs or other app performance data.
