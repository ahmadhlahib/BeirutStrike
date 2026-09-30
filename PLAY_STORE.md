# Publishing on Google Play

This is the checklist for releasing Beirut Strike on Google Play, from creating the developer
account to going live. Steps marked 🧑 happen in a browser or on your computer; everything the
code needs is already in this repository.

| | |
| --- | --- |
| **Package name** | `com.alahib.beirutstrike` (permanent: it can never change after the first upload) |
| **Target SDK** | 36 (Android 16) |
| **Upload format** | Android App Bundle (`.aab`) |
| **Privacy policy** | [PRIVACY_POLICY.md](PRIVACY_POLICY.md) |
| **Store listing text** | [STORE_LISTING.md](STORE_LISTING.md) |

---

## 1. Create the developer account 🧑

1. Sign up at <https://play.google.com/console/signup> ($25, one-time) and verify your identity.
2. Choose **Personal** or **Organization**:
   - A **personal** account must run a **closed test with at least 12 testers, opted in for 14
     days in a row**, before it can apply for production (step 7).
   - An **organization** account (needs a D-U-N-S number) skips that requirement.

## 2. Point Firebase at the new package name 🧑

The package name changed from `com.example.beirutrun`, so Firebase needs to know about the new one.

1. In the [Firebase console](https://console.firebase.google.com), open the project, then
   **Project settings → Your apps → Add app → Android**.
2. Package name: `com.alahib.beirutstrike`. Register it.
3. Download the new **`google-services.json`** and replace `app/google-services.json`. It lists
   both apps; the build picks the right one.
4. **Realtime Database → Rules:** paste in [`firebase/database.rules.json`](firebase/database.rules.json)
   and **Publish**. Do this every time the rules file changes.
5. Recommended: in **Google Cloud console → APIs & Services → Credentials**, restrict the Android
   API key to `com.alahib.beirutstrike` and the SHA-1 fingerprints of your upload key and of Play's
   app signing key (Play Console → **Test and release → App integrity**).

## 3. Create the upload key 🧑

Google Play signs the app with its own key (Play App Signing). You sign each upload with an
**upload key**, which you create once. If you ever lose it, Google can reset it.

```bash
keytool -genkeypair -v -keystore C:/Users/<you>/keys/beirutstrike-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

It asks for a password and your name and organisation (these appear in the certificate, not in
the store).

`keytool` comes with Android Studio (`<Android Studio>/jbr/bin/keytool`). Keep the `.jks` file
**outside the project** and back it up.

Then create **`keystore.properties`** in the project root. It is git-ignored; never commit it:

```properties
storeFile=C:/Users/<you>/keys/beirutstrike-upload.jks
storePassword=<the keystore password>
keyAlias=upload
keyPassword=<the key password>
```

## 4. Build the release bundle

```bash
./gradlew bundleRelease
```

The signed bundle is `app/build/outputs/bundle/release/app-release.aab`.

Before **every** new upload, raise `versionCode` (and usually `versionName`) in
`app/build.gradle.kts`. Play refuses a version code it has already seen.

## 5. Create the app and fill in its pages 🧑

In Play Console: **Create app** → name **Beirut Strike**, type **Game**, **Free**. Then complete
every item under **Policy → App content** and **Grow → Store presence**:

| Page | What to enter |
| --- | --- |
| **Privacy policy** | A public URL to [PRIVACY_POLICY.md](PRIVACY_POLICY.md), e.g. its GitHub page. The contact email is already filled in |
| **App access** | *All functionality is available without special access* (no login; players just type a name) |
| **Ads** | *No, my app does not contain ads* |
| **Content rating** | The questionnaire: see [STORE_LISTING.md](STORE_LISTING.md#content-rating-questionnaire) |
| **Target audience** | **16 and over**, not appealing to children (avoids the Families policy) |
| **Data safety** | See [STORE_LISTING.md](STORE_LISTING.md#data-safety-form) |
| **Government apps / financial / health** | No |
| **Store listing** | Name, descriptions and graphics: see [STORE_LISTING.md](STORE_LISTING.md) |

## 6. Test 🧑

1. **Test and release → Testing → Internal testing:** create a release, upload the `.aab`, add
   yourself and a few friends by email, and install from the opt-in link. Play checks the bundle
   and reports any problems here first.
2. **Closed testing** (required for personal accounts): create a track, add **at least 12
   testers**, upload the `.aab` and roll it out. Testers must opt in and stay opted in for **14
   days**. Ask them to actually play; Google asks about the test when you apply for production.

## 7. Go live 🧑

**Dashboard → Apply for production** (personal accounts, after the closed test), then create a
**Production** release with the same or a newer `.aab`. Review usually takes a few days, and can
take longer for a new account.

---

## Policy notes

- **Teams must stay fictional.** Don't name teams after real parties, militias or other groups,
  or use their flags. Play rejects apps that depict or glorify real armed groups, and treats some
  (designated terrorist organisations) as a zero-tolerance violation.
- **Teams players create** (name and flag picture) fall under the same rule. The app asks for
  fictional teams, but players can still upload a real party's flag: review `team` reports
  quickly and delete the team from `rooms/{room}/teams/` in the Firebase console.
- **Content from players** (speech bubbles, photo drops, created teams) must stay reportable and blockable.
  Review reports in the Firebase console under `reports/`, and delete offending content (and ban
  repeat offenders by removing their data) promptly.
- **Delete my data** must keep working. The privacy policy also offers deletion by email; answer
  those requests.
- A shooter set in a real city with a recent history of conflict can still get extra scrutiny.
  Keep the store listing about gameplay: no references to real conflicts, parties or events.
