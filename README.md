# Flos

Flos is an Android point-of-sale (till) app for Czech shops, cafés and restaurants.
It runs on Android phones and tablets (Android 8.0 and newer), prints receipts on
Bluetooth thermal printers, and can put a Czech **QR Platba** code on the receipt so the
customer can pay by bank transfer.

## What version 0.1 does

- **Sell screen**: product tiles by category, cart with quantities and discounts.
  Tablets show products and cart side by side; phones show a cart bar at the bottom.
- **Payments**: cash (with change calculation and rounding to whole crowns), card
  (external terminal), and QR Platba bank transfer (QR shown on screen and printed).
- **Receipts**: numbered per year (`2025-000001`), with business details, IČO/DIČ, and a
  VAT recap (21 % / 12 % / 0 %) for VAT payers. The QR variable symbol matches the receipt number.
- **Bluetooth printing**: ESC/POS over classic Bluetooth (SPP), 58 mm and 80 mm paper,
  Czech diacritics via CP852 / Windows-1250, or stripped for printers that can't print them.
- **History**: every receipt, reprint as a copy, cancel (storno).
- **Daily report** (uzávěrka): takings by payment type and VAT rate, printable.
- **Products and categories** management. Everything is stored on the device and works offline.

## Project layout

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin logic with unit tests: money, VAT, cart, Czech account → IBAN, QR Platba (SPAYD), receipt layout, ESC/POS encoder, daily report. |
| `app/` | The Android app: Jetpack Compose UI, Room database, Bluetooth printer. |

## Building

- Every push to `main` is built by GitHub Actions; download the APK from the run's **flos-apk** artifact.
- Locally: open the folder in Android Studio, or run `./gradlew :app:assembleDebug`.
- Core tests without an Android SDK: `./gradlew :core:test -Pflos.coreOnly=true`.

## Setting up a till

1. Pair the Bluetooth printer in Android's Bluetooth settings.
2. In Flos → **Nastavení**: fill in the business details, bank account, then pick the printer and paper width.
3. Press **Zkušební tisk** to check diacritics and the QR code. If letters look wrong, switch the character set.

## Roadmap (towards the full feature set)

Next steps, to be done one at a time: table map and open orders, stock and recipes,
staff accounts with PINs and attendance, card-terminal integration, cloud sync with a web
back office, multiple tills and branches, accounting export (Pohoda, ABRA Flexi), invoices,
customer display, public API, and fiscal reporting once Czech EET 2.0 rules are final.
