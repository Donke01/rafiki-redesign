# RAFIKI Pin: answers for Play's Data safety form

Play Console › Policy › App content › Data safety. Based on the code as of Oct 2026 (Android app in
`apps/android-native`, server in `functions/` and `lib/`). Where a choice is a judgement call, the conservative
answer is given and the reason is noted. Re-check if the app starts collecting something new.

Google's definitions that matter here:
- **Collected** = sent off the phone (to our server or to a service working for us).
- **Shared** = given to a *third party*. Sending data to a **service provider that processes it on our behalf**
  (monday.com, Cloudflare, Resend, OpenAI or Google Speech-to-Text, OpenRouteService, Open-Meteo, Google Places)
  is **not** sharing. Opening Google Maps or WhatsApp because the user tapped a link is a user-initiated
  transfer and is **not** sharing either.
- Data processed only on the phone (not sent) is not collected.

## Overview section

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS only; the app only talks to `https://` addresses) |
| Do you provide a way for users to request that their data is deleted? | **Yes**: by e-mail to info@rafikioutreach.org (see the privacy policy). OWNER: Play may ask for a URL; use https://pin.rafikioutreach.org/pin-privacy (section "Your choices and deleting your data"). |
| Account creation | Accounts are created by the office (PIN) or come from monday.com; users can't sign up in the app. Pick "My app does not allow users to create an account" only if the form offers it for invite-only apps; otherwise choose the option for accounts created outside the app and give the deletion e-mail. |

## Data types

For every type below: **Shared: No**. **Processed ephemerally: No** (it is stored). Purposes are only the
ones listed; nothing is used for analytics, advertising or marketing, fraud prevention aside from sign-in limits.

### Location
| Type | Collected | Required or optional | Purposes | Notes |
|---|---|---|---|---|
| Precise location | **Yes** | **Optional** (the app works without the permission; pinning and check-in need it) | App functionality | Sent with pins, visit reports, new sites and road reports; and, only if the person switches on "Share my location with the office", their latest position (kept 12 h), seen by admins and by people on the same trip; and a live location someone chooses to share in a chat. |
| Approximate location | **Yes** | Optional | App functionality | The app asks for coarse as well as fine location; answer Yes to be safe. |

### Personal info
| Type | Collected | Required or optional | Purposes | Notes |
|---|---|---|---|---|
| Name | **Yes** | Required | App functionality, Account management | Team members' names; donors' names (entered by the office). |
| Email address | **Yes** | Optional | App functionality, Account management | Added by the office for team members and donors; used for alerts and donor updates. |
| Phone number | **Yes** | Optional | App functionality | Team members' phone numbers added by the office, shown to the team so they can call or WhatsApp. (Community SMS reports come from water committees by SMS, not from the app.) |
| User IDs | **Yes** | Required | App functionality, Account management | Sign-in session, monday.com user id for people who sign in with monday.com, donor code (stored hashed). |
| Address, race, political or religious beliefs, sexual orientation, other info | No | | | |

### Financial info
| Type | Collected | Notes |
|---|---|---|
| Purchase history, credit info, payment info | No | The app records project costs (repair costs, fuel, receipts) of the organisation, not the user's own finances. OWNER: if you prefer to be cautious, declare "Other financial info: Yes, optional, App functionality" for trip receipts and costs. |

### Health and fitness
No.

### Messages
| Type | Collected | Notes |
|---|---|---|
| Emails, SMS or MMS | No | The app does not read the phone's messages. |
| Other in-app messages | **Yes**, optional, App functionality | Team chat (messages between team members, kept on our Cloudflare server until deleted), plus visit and repair notes. |

### Photos and videos
| Type | Collected | Required or optional | Purposes |
|---|---|---|---|
| Photos | **Yes** | Optional | App functionality |
| Videos | **Yes** | Optional | App functionality (videos sent in team chat, shrunk to 720p on the phone; thank-you videos for donor pages) |

### Audio files
| Type | Collected | Required or optional | Purposes | Notes |
|---|---|---|---|---|
| Voice or sound recordings | **Yes** | Optional | App functionality | Voice notes (up to 2 min) on visit reports and in team chat. They may be typed out by OpenAI or Google Speech-to-Text acting for us (service providers, not sharing). |
| Music files, other audio | No | | | |

### Files and docs
| Type | Collected | Notes |
|---|---|---|
| Files and docs | No | Receipt pictures are photos (above). |

### Calendar, Contacts
No. The app does not read the phone's contacts or calendar.

### App activity
| Type | Collected | Required or optional | Purposes | Notes |
|---|---|---|---|---|
| App interactions | **Yes** | Required | App functionality | The team activity log (who reported, pinned or fixed what, kept 30 days) and "last seen" in the team list. |
| In-app search history | No | | | Place searches are sent to answer them (Google Places, Photon, Nominatim) but are not stored against the user. OWNER: our server caches the results of a search text for 30 days without a name; if you want to be strict, declare "Yes, optional, App functionality". |
| Installed apps, other user-generated content, other actions | **Other user-generated content: Yes**, optional, App functionality | Visit reports, readings, new sites, road reports, trip plans, fuel logs and receipts. |

### Web browsing
No.

### App info and performance
| Type | Collected | Notes |
|---|---|---|
| Crash logs | **No** | No crash-reporting SDK. "Report a problem" opens the user's own e-mail app with the app version and phone model; nothing is sent unless they send it. |
| Diagnostics | No | |
| Other app performance data | No | |

### Device or other IDs
**No.** The app uses no advertising ID, Android ID or device identifier. (Map tile servers see the phone's IP
address when they send map pictures, like any web request; this is not collected by us.)

## Security practices (shown on the listing)
- Data is encrypted in transit: **Yes**.
- You can request that data be deleted: **Yes**.
- Committed to Play Families Policy: **No** (not for children).
- Independent security review: **No**.

## Third parties the app talks to, for reference

| Service | From | Data | Role |
|---|---|---|---|
| Cloudflare | server | everything the app sends, including chat (Durable Objects, R2) | hosting and storage (service provider) |
| monday.com | server | project notes, photos, voice notes, names | the organisation's project records (service provider) |
| OpenAI / Google Speech-to-Text | server | voice-note audio | speech to text, only when a key is set (service provider) |
| Resend | server | e-mail addresses, alert text | sends e-mail (service provider) |
| OpenRouteService, Open-Meteo | server | coordinates of planned visits | routes and weather (service provider) |
| Google Places, Photon, Nominatim | server | place search text | geocoding (service provider) |
| OpenStreetMap, Esri, Google Map Tiles | phone | map tile requests (area on screen, IP) | map pictures |
| Google favicon service | phone | which site's icon to show (monday.com, Google Maps) | small icons |
| Google Play services code scanner | phone | camera frames, processed on the phone | reads QR stickers; the app gets only the text |
| Google Play (play build) | phone | Play's own update check | in-app updates |
| Google Maps, WhatsApp, e-mail app | phone | a place or message, when the user taps | user-initiated, not sharing |

Also note: `android:allowBackup="true"`, so Android's own backup may copy the app's settings to the user's Google
account. That is Google's backup on the user's behalf, not collection by us.
