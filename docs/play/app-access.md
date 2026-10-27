# RAFIKI Pin: app access for Google's reviewers

Play Console › Policy › App content › App access.

RAFIKI Pin is behind a sign-in, so choose **"All or some functionality in my app is restricted"** and add one set
of instructions. Google's reviewers must be able to sign in for as long as the app is on Play, so use a dedicated
reviewer account, never a real person's PIN.

## Make the reviewer PIN (owner, in the app)

1. Open RAFIKI Pin as an office admin.
2. Go to **Office › Team** and add a new person, for example the name **"Google Play Review"**, home country Kenya,
   **not** an admin, and no phone number or e-mail.
3. Let the app make a new PIN for that person (or type one only you know). Write it down somewhere safe.
4. Check it works: sign out on a spare phone (or the web app at pin.rafikioutreach.org/pin), sign in with that PIN.
5. Do not put the PIN in this repository, a commit, a chat or an e-mail. It only goes into Play Console.

If you ever need to revoke it, remove "Google Play Review" in Office › Team; then make a new one and update
Play Console the same day, or the next review will fail.

## Fill in Play Console › App access

- **Name:** Reviewer sign-in
- **Username:** leave empty (the app has no username)
- **Password:** the reviewer PIN from step 3 (type it here yourself)
- **Any other information required to access your app** (paste and adjust):

      RAFIKI Pin is the field app of the charity RAFIKI Outreach; accounts are created by the office.
      1. Open the app. On the welcome screen tap "Sign in".
      2. Type the PIN given above on the keypad ("Enter your PIN"). No username is needed. It needs an internet
         connection the first time.
      3. A short "Get started" welcome follows; choose any answers.
      4. You are signed in as "Google Play Review", a field team member (not an admin). You can browse the map and
         projects, open a project, and start "Report a visit". Please don't send real reports if you can avoid it;
         if you do, the office will delete them.
      Location permission is optional; without it the map simply doesn't show your position.
      Donor mode ("I'm a donor" on the welcome screen) needs a donor code; it is not required for review.

- Tick **"Allow Android to use the credentials you provide for performance and app compatibility testing"** if you
  are happy for Google's automated tests to sign in too (recommended; they don't send reports).

If the PIN you made is longer than 4 digits, add to the instructions: tap "My PIN is longer" first.
