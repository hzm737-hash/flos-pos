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
- **Printing**: ESC/POS receipt printers over **Bluetooth** (classic SPP) or **Wi-Fi / LAN**
  (raw TCP, port 9100), 58 mm and 80 mm paper, Czech diacritics via CP852 / Windows-1250,
  or stripped for printers that can't print them.
- **EET 2.0** (evidence tržeb from 2027), interface v4.1 per the Financial Administration's
  documentation on eet.gov.cz:
  - SOAP 1.1 over HTTPS (TLS 1.2+, server certificate checked), `OdeslaniTrzby` operation.
  - WS-Security signature of `soap:Body` with the cash-register certificate (.p12 from
    MOJE daně): exclusive C14N, SHA-256, RSA-SHA256, X509v3 BinarySecurityToken.
  - Confirmation (POK), error codes and warnings are read and stored for every sale.
  - Offline operation: every sale is stored first; if the answer doesn't come within the
    response time limit (at least 2 s, configurable) the receipt prints without POK and the
    sale is resent automatically (new message UUID, `prvni_zaslani=false`) whenever the
    device is online. The app warns after 24 h; the legal limit is 48 h.
  - Storno is recorded as a new document with a negative amount.
  - Playground and production endpoints, and verification mode (`overeni`) for testing.
- **History**: every receipt, reprint as a copy, cancel (storno).
- **Daily report** (uzávěrka): takings by payment type and VAT rate, printable.
- **Products and categories** management. Everything is stored on the device and works offline.

## Languages

Czech is the default everywhere; English can be chosen.

- **Till app:** Settings → "Jazyk / Language". The switch is instant. Texts are written in Czech in the code and wrapped in `tr("…")`; English lives in `app/src/main/java/cz/flos/pos/ui/En.kt`. A missing English text falls back to Czech. Receipts and EET data always stay in Czech, as the law expects.
- **Web back office:** the "English" / "Čeština" button in the header (and on the sign-in page), remembered per browser. The dictionary is in `server/src/main/resources/static/i18n.js`.

## Project layout

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin logic with unit tests: money, VAT, cart, Czech account → IBAN, QR Platba (SPAYD), receipt layout, ESC/POS encoder, daily report. |
| `app/` | The Android app: Jetpack Compose UI, Room database, Bluetooth printer. |

## Building

- Every push to `main` is built by GitHub Actions; download the APK from the run's **flos-apk** artifact.
- Locally: open the folder in Android Studio, or run `./gradlew :app:assembleDebug`.
- Core tests: `./gradlew :core:test`.

## Setting up a till

1. Pair the Bluetooth printer in Android's Bluetooth settings.
2. In Flos → **Nastavení**: fill in the business details, bank account, then pick the printer and paper width.
3. Press **Zkušební tisk** to check diacritics and the QR code. If letters look wrong, switch the character set.

## Orders from the restaurant's website

A restaurant's own website can send delivery and pick-up orders to the till.

1. On the till, switch on **Pokročilé nastavení → Objednávky z webu** and press **Zveřejnit menu znovu**.
   The menu address it shows (`/menu/<slug>`) is what the website needs.
2. On the back office, list the website in `FLOS_WEB_ORIGINS`, e.g.
   `https://pizzafresca.cz,https://www.pizzafresca.cz`, so its pages may call the API (CORS).
3. The website posts the order to `POST /menu/<slug>/web-order` (`WebOrder` in `core/WebOrders.kt`) and
   asks for the answer with `GET /menu/<slug>/web-order/<id>?k=<token>`.
4. The till shows each order with the customer, the lines at the till's own prices and a ready time.
   Accepting saves it as an open order and prints the ticket; the customer's page shows the time.

Dishes are matched by their menu number in the product's code (barcode field), otherwise by name.
Half & half costs half of each pizza; an extra on one half costs half, unless it is always charged in full.
The customer's name, phone and address are kept apart from the order and erased after 30 days.

## Roadmap (towards the full feature set)

Next steps, to be done one at a time: table map and open orders, stock and recipes,
staff accounts with PINs and attendance, card-terminal integration, cloud sync with a web
back office, multiple tills and branches, accounting export (Pohoda, ABRA Flexi), invoices,
customer display and public API.
