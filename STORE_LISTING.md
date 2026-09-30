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
Team shooter in real Beirut streets. Timed matches, two guns, live ranking.
```

### Full description (max 4000 characters)

```
Beirut Strike is a fast multiplayer shooter played in 3D recreations of real Beirut neighbourhoods,
built from OpenStreetMap data: Downtown, the Souks, Hamra, Ain El Mreisseh and Raouche.

JOIN A ROOM, PICK A SIDE
Create a public or password-protected room, choose the map, the play area and the match length
(30 seconds to 1 hour), and invite your friends. Pick one of three factions, or create your own
team with its own name, flag and colour, and fight until the clock runs out.

TWO GUNS, REAL CHOICES
• Pistol: precise, one shot per tap.
• AK-47: fully automatic, 10 rounds a second.
Every bullet counts. Ammo runs out, so hunt the streets for ammo packs, and find a scope to zoom
in and hit targets from across the city.

MOVE LIKE A SOLDIER
Walk, run, jump and crawl. Take cover behind real buildings, and switch between first- and
third-person view.

SCORE, RANK UP, BECOME COMMANDER
A live scoreboard tracks score, kills, deaths, K/D and accuracy for every player and team. Your
successful shots add up across all matches on the global ranking. Land more than 100 and you're
promoted to Commander, with a gold star next to your face.

MAKE IT YOURS
Put your own face on your soldier with the front camera, talk with speech bubbles, and leave
photos in the streets for other players to find.

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
| Phone screenshots | 2–8, 16:9 or 9:16, 1080 px+ on the short side | Suggested: the city in third person, first person with the AK-47, the scope, the scoreboard, the ranking, the room list |

Use only the game's own art in screenshots and graphics: fictional factions, no real party
flags or symbols.

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
**Is all of the user data encrypted in transit?** Yes (Firebase uses TLS).
**Do you provide a way for users to request that their data be deleted?** Yes (in the app, and by email).

| Data type | Collected | Shared* | Optional | Purpose |
| --- | --- | --- | --- | --- |
| Personal info → **Name** (player name) | Yes | No | No (a name is needed to play) | App functionality |
| Personal info → **User IDs** (Firebase anonymous ID) | Yes | No | No | App functionality, fraud prevention/security |
| Photos and videos → **Photos** (face photo, dropped photos, team flags) | Yes | No | Yes | App functionality |
| Messages → **Other in-app messages** (speech bubbles, captions) | Yes | No | Yes | App functionality |
| App activity → **Other user-generated content / in-app actions** (game stats, scores) | Yes | No | No | App functionality |

\* In Play's terms, showing content to other users of the same app is **not** "sharing", and
Firebase acts as a service provider, which is not sharing either.

Not collected: location, contacts, financial info, health, email, phone number, device or other
IDs, web browsing, audio, files, calendar, app diagnostics or crash logs.
