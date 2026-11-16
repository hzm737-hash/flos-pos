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

## Selling and donations

The first release is free, with no in-app purchases. Selling the app or in-app products later needs a
Google payments (merchant) profile; on a personal developer account Google then shows the developer's
address on the store page, so that waits for the company account (the app can be moved there with
Play Console **App transfer**).

## Foreground service declaration

Flos declares `FOREGROUND_SERVICE_SPECIAL_USE` for `OrderWatchService` (keeps receiving orders and rings
while the screen is off, only when switched on in Pokročilé nastavení). Play Console asks about it under
**App content → Foreground service permissions**: choose *Other / special use*, describe it as
"Point-of-sale till keeps receiving restaurant orders from tables and the website and rings for them while
the screen is off; the user switches it on in the settings", and add a short screen video if asked.

## Testing before production

A personal developer account created after 13 November 2023 must run a **closed test with at least 12
testers for 14 days in a row** before it can apply for production. Organization accounts skip this.

## Data safety form

* **Does the app collect or share user data?** Yes (only when the optional back office sync is on).
* **Encrypted in transit:** Yes (HTTPS). **Users can ask for deletion:** Yes (e-mail in the privacy policy).
* **Data types collected** (all *optional*, purpose *App functionality*, not shared, not used for ads):
  * Personal info: Name, Email address, Phone number, Address (customers and staff the business enters, and web orders from the restaurant's own ordering page)
  * Financial info: Purchase history (sales)
* **Shared with third parties:** No. Sending sales to the Czech tax office (EET), reading the bank API
  and sending messages are user-initiated transfers, which Play does not count as sharing.

## Content rating (IARC questionnaire)

Category **Utility, Productivity, Communication or other**. Answer **No** to every question
(violence, sexuality, language, drugs, gambling, user interaction/sharing, location, digital purchases). Expected result: PEGI 3 / Everyone.

## Other App content answers

* Target audience: 18 and over. Not designed for children.
* Ads: No ads.
* App access: All functionality is available without special access (no login needed; demo products on
  first start).
* Government app: No. Financial features: None (Flos records sales, it does not move money).
* Health, news, COVID: No. Category: **Business**. Contact e-mail and website: flos.gnocco.cz.
