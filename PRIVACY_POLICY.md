# Beirut Strike Privacy Policy

**Last updated:** 6 October 2026

Beirut Strike ("the game", "we") is a multiplayer game for Android published by **Ahmad EL Lahib**.
This policy explains what information the game collects, how it is used and shared, and how you
can delete it.

**Contact:** [Ahmad.h.lahib@gmail.com](mailto:Ahmad.h.lahib@gmail.com)

## Summary

- You don't need an account: you choose a player name and play as a guest. If you choose to
  sign in with Google (so your progress follows you to another phone), we receive your Google
  account's email address and name. There is no password of ours.
- What you share in the game (name, optional face photo, photos you drop, teams you create,
  messages, scores) is shown to other players. While your mic is on, your teammates hear your
  voice live. It is never recorded or stored.
- There are no ads and no selling of data. The game doesn't track how you play for analytics; the
  only diagnostics are the technical ones Google's face detection sends (see below).
- You can delete all your data at any time from the in-game menu: **Delete my data**.

## Information the game collects

| Information | Why | Who can see it |
| --- | --- | --- |
| **Player name** you type in | To show who you are in rooms, on the scoreboard and in the ranking | Other players |
| **Face photo** (optional, taken with the front camera) | Shown on your character's head, on scoreboards and in the ranking | Other players |
| **Photos you drop** in the city (optional, taken with the camera) | Shown where you dropped them, with your caption | Players in the same room |
| **Teams you create** (optional): a team name, a flag picture you choose from your phone, and a colour | Offered as a team in the room, and shown on its players | Players in the same room |
| **Messages** (speech bubbles and captions) | Shown above your character | Players in the same room |
| **Voice** (optional, only while your mic is on) | Team voice chat. It goes straight from your phone to your teammates' phones, and is not recorded or stored | Your teammates in the same room (they hear it live) |
| **Game activity:** team, position in the game's map, shots, hits, kills, deaths, wins, XP (military rank), money and guns bought, match and career scores | To run the multiplayer game, scoreboards and ranking, and to keep your progress | Other players (money and guns: not shown) |
| **A user ID** created by Firebase Authentication: a random one when you play as a guest | To tell players apart and protect your data so only you can change it | Not shown |
| **Your Google account's email address and name** (optional, only if you sign in with Google), kept by Firebase Authentication | To sign you in, so your career (XP, rank, money and guns) follows you to another phone | Only us (not shown to players) |
| **Reports** you send about other players | So we can review abusive content | Only us |
| **Face detection diagnostics** (only when you take a face photo): device model and Android version, how well face detection ran (speed, settings, error codes) and a per-install ID | Sent by Google ML Kit to Google, to keep face detection working well | Google only |

The face photo is found and cropped **on your phone** using Google ML Kit face detection. The game
does not use face recognition or identify you from your photo. Your photo is never sent to Google
for this: ML Kit only sends the technical diagnostics above, encrypted, and Google doesn't pass
them on to anyone else (see
[ML Kit's data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)).

The game **does not** collect your real-world location, contacts, phone number, email address or
advertising ID. Positions are inside the game's map only.

**Camera:** the game asks for camera permission only to take your face photo and photos you drop.
Both are optional.

**Microphone:** the game asks for microphone permission only for team voice chat, which is
optional. Your mic starts off, and is only used while you have turned it on and the game is on
screen.

## How information is stored and shared

The game uses **Google Firebase** (Realtime Database and Authentication) to store and share game
data between players. Firebase processes data on our behalf under
[Google's terms and privacy policy](https://firebase.google.com/support/privacy). All data is sent
over encrypted connections (HTTPS/TLS). Google ML Kit sends its face detection diagnostics to
Google as described above.

**Team voice chat** connects your phone directly to your teammates' phones (WebRTC, encrypted).
Your voice never goes through our servers. To set up these connections, phones exchange short
technical messages through Firebase, including network (IP) addresses. Players in the same room
can see them, and they are deleted as soon as they are read or when you leave. Your teammates'
phones, and Google's STUN server (which helps phones find each other), can see your IP address.

We do not sell, rent or share your information with anyone else, except where the law requires it.

## How long information is kept

- **Room data** (positions, messages, dropped photos, teams created in the room, match scores) is
  deleted once a room is left
  empty (the game removes empty rooms automatically, usually within minutes).
- **Voice** is not recorded or kept at all. Voice chat setup messages are deleted as soon as they
  are read, or when you leave.
- **Your face photo and career** (career score, XP and military rank) are kept until you delete them.
- **Reports** are kept while we review them, and deleted afterwards.

## Deleting your data

- **In the game:** open the menu (your name, top left, in the city) and choose **Delete my data**.
  This deletes your face photo, career (score, XP, rank, money and guns), match scores, dropped photos and your
  account (guest or Google sign-in) from our servers, and everything the game stored on your phone.
- **By email:** write to [Ahmad.h.lahib@gmail.com](mailto:Ahmad.h.lahib@gmail.com) with your player name if you can't use the game (for
  example, if you uninstalled it). We will delete your data within 30 days.

Uninstalling the game deletes the data stored on your phone, but not the data on our servers. Use
one of the options above for that. Step-by-step instructions: <https://ahmadhlahib.github.io/BeirutStrike/delete-data/>.

## Safety

You can **report** another player's messages or photos, or **block** a player, from the photo view
and from **Players** in the game menu. You can report a team a player created (its name or flag)
by long-pressing it on the team screen. Blocked players' messages and photos are hidden on your
phone, and you never hear their voice. You can also mute all your teammates, or one teammate
from **Players**. We review reports and remove content that breaks the rules.

## Children

The game is intended for people aged **16 and over** and is not directed at children. If you
believe a child has shared personal information in the game, contact us and we will delete it.

## Changes

If this policy changes, the new version will be published at the same address with a new "Last
updated" date.
