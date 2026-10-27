# RAFIKI Pin: putting it on Google Play (owner steps)

Everything here is done by the owner in Play Console (https://play.google.com/console), signed in with the
personal developer account. Files to paste are in this folder (`docs/play/`).

Two builds come from the same code (`apps/android-native`):

| Build | Gradle task | Output | Where it goes |
|---|---|---|---|
| Website | `assembleSiteRelease` | `app/build/outputs/apk/site/release/app-site-release.apk` | `/app/rafiki-pin.apk` on the website, as today |
| Google Play | `bundlePlayRelease` | `app/build/outputs/bundle/playRelease/app-play-release.aab` | uploaded to Play Console |

Both have the same app id (`org.rafikioutreach.pin`) and the same signing key, so a phone that installed the
website APK can take the next update from Google Play without losing its data. Give every release one version
code and use it for both builds. (The Play build has no self-update and no "install unknown apps" permission; it
gets updates through Google Play.)

## 1. Create the app

1. Play Console › **Create app**.
2. App name **RAFIKI Pin**, default language **English (United States)**, **App**, **Free**.
3. Tick the declarations (Developer Program Policies, US export laws) and **Create app**.

## 2. Play App Signing with our existing key

We keep the key the website APK is signed with, so Google signs the Play version with that same key.

1. Play Console › your app › **Test and release › Setup › App signing** (or it is offered the first time you
   upload a bundle).
2. Choose **"Use existing app signing key from Java keystore"** (it may say "Export and upload a key from Java
   keystore").
3. Download the two files it offers: **the PEPK tool (`pepk.jar`)** and **the encryption public key
   (`encryption_public_key.pem`)**. Send me (Claude) only the `.pem` file. It is a public key, so it is safe to send;
   you don't need to send `pepk.jar` (I download it from Google).
4. I run PEPK on the machine that has the keystore. It encrypts our key so only Google can open it:

       java -jar pepk.jar --keystore=<our keystore> --alias=<our key alias> --output=rafiki-pin-signing-key.zip \
            --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem

   It asks for the keystore and key passwords (from `keystore.properties`, never written into the repo).
   I send you back **`rafiki-pin-signing-key.zip`**.
5. In the same App signing page, **upload `rafiki-pin-signing-key.zip`** and **Save**.
6. **The upload key.** The upload key is the key you sign each bundle with before uploading it; Google checks it,
   then re-signs the app with the app signing key from step 5. By default the upload key is **the same key** (our
   existing one), which is what `bundlePlayRelease` uses today. That works, but if that one key ever leaked, it
   could not be replaced. Recommended: on the same page choose **"Create a new upload key"** later (I can make one
   and the certificate file to upload); then a lost upload key can be reset by Google support, while the app
   signing key stays safe with Google. Keep a backup of the existing keystore and its passwords offline either way.

## 3. First upload: a closed test (required for personal accounts)

Google requires new personal developer accounts to run a **closed test with at least 12 testers who stay opted
in for 14 days in a row** before you can apply for production.

1. Make a tester list: either a **Google Group** (e.g. rafiki-pin-testers@googlegroups.com, add people's Google
   account e-mails), or an **e-mail list** in Play Console (Test and release › Testing › Closed testing ›
   Testers › Create e-mail list). Add at least **15** people to be safe (staff, volunteers, family with Android
   phones and Google accounts). They must use the Google account that is on their phone.
2. **Testing › Closed testing › Create track** (or use "Alpha"), add the tester list, and set a feedback e-mail.
3. **Create new release** › upload `app-play-release.aab` › release name e.g. `2.22.0 (47)` › notes › **Review and
   roll out**. The first review can take a few days.
4. Copy the **opt-in link** from the track's Testers tab and send it to every tester. Each tester must open it,
   tap **Become a tester**, then install RAFIKI Pin from Play. People who have the website APK installed get the
   Play version as an update (same key), but the Play version must have a version code at least as high as theirs.
5. Keep **12 or more testers opted in for 14 consecutive days**. Ask them to open the app a few times; Google asks
   later how testers used it. Push an update to the closed track during the 14 days if you have one; it helps.
6. Production access shows on the **Dashboard** once the 14 days are done.

## 4. Fill in App content and the store listing (during the 14 days)

Play Console › **Policy › App content** (and **Grow users › Store presence**). Each has a file here:

1. **Privacy policy:** `https://pin.rafikioutreach.org/pin-privacy`. Read the page first: it is a draft, with
   `OWNER:` notes in its source (`pin-privacy.html`) to confirm, then publish it with the website.
2. **App access:** follow `app-access.md` (make a reviewer PIN in Office › Team; type it only into Play Console).
3. **Ads:** No ads.
4. **Content rating:** `content-rating.md`.
5. **Target audience and content:** `target-audience.md` (18 and over).
6. **Data safety:** `data-safety.md`.
7. **Government apps, financial features, health, news, COVID-19:** No / none (see `target-audience.md`).
8. **Advertising ID:** "No" (the app doesn't use it).
9. **Store listing:** `listing.md` (English, then Swahili as a translation), icon, feature graphic and phone
   screenshots from `graphics/`. Category **Business**; contact e-mail; website.
10. **Store settings:** category and contact details as in `listing.md`.

## 5. Apply for production

1. After the 14 days, Dashboard › **Apply for production**. Answer the questions about the closed test (how many
   testers, what feedback, what you changed) and about the app's audience and readiness.
2. Google usually answers within about a week. When approved: **Production › Create new release**, upload the
   same or a newer bundle, choose countries (Kenya, Tanzania, Uganda, Malawi, and wherever donors live, or all
   countries), and roll out (you can start at a percentage).

## Every later release

1. Raise `versionCode` and `versionName` in `apps/android-native/app/build.gradle.kts` (once, for both builds).
2. Build both: `assembleSiteRelease` (website APK, plus `/app/version.json`) and `bundlePlayRelease` (Play).
3. Upload the bundle to the closed track or production in Play Console. The Play app offers the update itself
   (Google Play's in-app update: it downloads in the background, then asks for one restart).

## Requirements to keep in mind

- **Target API level:** since 31 Aug 2026 new apps and updates must target Android 16 (API 36). The app now does.
- **Never** add download-and-install code, `REQUEST_INSTALL_PACKAGES`, or links to the APK to the Play build.
- Keep the privacy policy page reachable without signing in, and keep the reviewer PIN working.
