# RAFIKI Pin for Android

A Trusted Web Activity: the installed app opens https://rafiki-redesign.pages.dev/pin full screen in Chrome,
so it always runs the latest version of the web app (no app update needed for changes).

Build: `ANDROID_HOME=<sdk> gradle -p apps/android assembleRelease` with the signing key in
`keystore.properties` (never commit the key or its password; this repository is public):

    storeFile=/path/to/rafiki-pin.keystore
    storePassword=...
    keyAlias=rafiki-pin
    keyPassword=...

The key's SHA-256 fingerprint must be in /.well-known/assetlinks.json on the site, or the app
shows an address bar. When the app moves to rafikioutreach.org, change HOST in app/build.gradle
and the assetlinks file on that domain, then build and share a new APK.
