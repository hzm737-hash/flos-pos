# Flos on Google Play

Everything needed for the Play Console listing.

| File | Use |
| --- | --- |
| `listing-cs.txt`, `listing-en.txt` | App name, short and full description (Czech default, English translation) |
| `icon-512.png` | App icon, 512 × 512, 32-bit PNG |
| `feature-graphic-1024x500.png` | Feature graphic, 1024 × 500, no transparency |
| Screenshots | Run **Actions → Google Play screenshots → Run workflow**; phone and tablet PNGs appear in the `play-assets` release |
| Privacy policy | `https://flos.gnocco.cz/privacy.html` (from `server/src/main/resources/static/privacy.html`) |

## App bundle and signing

* Google Play takes an Android App Bundle (`.aab`). With the repository secrets
  `FLOS_UPLOAD_KEYSTORE_BASE64` (the upload keystore, base64) and `FLOS_UPLOAD_PASSWORD`,
  every push to `main` builds `flos-play.aab` and puts it in the `latest` release.
* That key is the **upload key**. Play App Signing keeps the real signing key at Google, so a lost
  upload key can be reset through Play support. Keep the keystore and password safe anyway.
* `versionCode` is the CI run number, so each upload is higher than the one before.
* The app targets Android 16 (API 36), which Play requires for new apps since 31 August 2026.

## Donations

Play policy does not allow links to outside payment pages (PayPal and similar) unless the developer is
a registered charity. Flos therefore uses Google Play Billing: **More → Podpořit Flos** lists one-time
in-app products, consumed right away so they can be given again. Create them in Play Console under
**Monetize → Products → In-app products**:

| Product ID | Name (cs) | Suggested price |
| --- | --- | --- |
| `donate_small` | Malý příspěvek | 49 Kč |
| `donate_medium` | Příspěvek | 99 Kč |
| `donate_large` | Velký příspěvek | 249 Kč |

Until those products exist (or outside Google Play) the screen just says contributions are available in
the Google Play version. In-app products need a Google payments (merchant) profile; for a personal
developer account Google then shows the developer's address on the store page.

## Data safety form

* **Does the app collect or share user data?** Yes (only when the optional back office sync is on).
* **Encrypted in transit:** Yes (HTTPS). **Users can ask for deletion:** Yes (e-mail in the privacy policy).
* **Data types collected** (all *optional*, purpose *App functionality*, not shared, not used for ads):
  * Personal info: Name, Email address, Phone number (customers and staff the business enters)
  * Financial info: Purchase history (sales)
* **Shared with third parties:** No. Sending sales to the Czech tax office (EET), reading the bank API
  and sending messages are user-initiated transfers, which Play does not count as sharing.
* Payments for donations are handled by Google Play; the app never sees card details.

## Content rating (IARC questionnaire)

Category **Utility, Productivity, Communication or other**. Answer **No** to every question
(violence, sexuality, language, drugs, gambling, user interaction/sharing, location, digital purchases
other than the optional donation). Expected result: PEGI 3 / Everyone.

## Other App content answers

* Target audience: 18 and over. Not designed for children.
* Ads: No ads.
* App access: All functionality is available without special access (no login needed; demo products on
  first start).
* Government app: No. Financial features: None (Flos records sales, it does not move money).
* Health, news, COVID: No. Category: **Business**. Contact e-mail and website: flos.gnocco.cz.
