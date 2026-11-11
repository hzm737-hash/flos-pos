// Flos back office – Czech / English.
// The page is written in Czech. In English mode every text node and placeholder that matches a
// Czech key is swapped for its English text, including content rendered later (MutationObserver).
'use strict';

const LANGS = { cs: 'Čeština', en: 'English' };
let LANG = 'cs';
try { LANG = localStorage.getItem('flos_lang') === 'en' ? 'en' : 'cs'; } catch (e) { /* storage blocked */ }
const LOCALE = LANG === 'en' ? 'en-GB' : 'cs-CZ';
document.documentElement.lang = LANG;

const EN = {
  'Na pokladně': 'On the till',
  'Fotka': 'Photo',
  'Odebrat fotku': 'Remove photo',
  'Ikona (bez fotky)': 'Icon (without photo)',
  'Ikona': 'Icon',
  'Varianty a přídavky': 'Variants and extras',
  'Každý řádek jedna skupina. Hvězdička za názvem = lze vybrat více (přídavky), jinak se vybírá jedna varianta. Cena za volbou se přičte.': 'One group per line. A star after the name = several can be chosen (extras), otherwise one variant is chosen. The price after a choice is added.',
  'Ceny v jiných cenících': 'Prices in other price lists',
  'Cena se zadává při prodeji': 'Price entered at the sale',
  'cena při prodeji': 'price at sale',
  'varianty': 'variants',
  '+ Přidat další firmu…': '+ Add another company…',
  '+ Přidat další firmu': '+ Add another company',
  'Přepnuto na': 'Switched to',
  'Nová firma': 'New company',
  'Každá firma má vlastní IČO a DIČ, produkty, provozovny, pokladny a tržby. Přepínáte mezi nimi nahoře v liště.': 'Each company has its own company ID and VAT number, products, branches, tills and sales. Switch between them in the top bar.',
  'Založit firmu': 'Create company',
  'Všechny firmy': 'All companies',
  'Jeden účet může spravovat více firem s různými DIČ. Každá firma může mít více provozoven a pokladen.': 'One account can manage several companies with different VAT numbers. Each company can have several branches and tills.',
  'aktuální': 'current',
  'Přepnout': 'Switch',
  'Bankovní převod': 'Bank transfer',
  'Stravenky': 'Meal vouchers',
  'Na fakturu': 'On invoice',
  // shell, login
  'Flos – back office': 'Flos – back office',
  'Back office pokladního systému': 'Till system back office',
  'Heslo': 'Password',
  'Přihlásit': 'Sign in',
  'Nemáte účet? Založit firmu': 'No account? Register your business',
  'Název firmy': 'Company name',
  'Vaše jméno': 'Your name',
  'Heslo (min. 8 znaků)': 'Password (min. 8 characters)',
  'Založit účet': 'Create account',
  'Odhlásit': 'Sign out',
  'Přehled': 'Dashboard',
  'Účtenky': 'Receipts',
  'Produkty': 'Products',
  'Sklad': 'Stock',
  'Provozovny a pokladny': 'Branches and tills',
  'Zaměstnanci': 'Staff',
  'Docházka': 'Attendance',
  'Exporty': 'Exports',
  'Firma': 'Company',
  'Načítám…': 'Loading…',
  'Přihlášení vypršelo': 'Your session has expired',
  'Chyba': 'Error',
  'Export se nezdařil': 'Export failed',
  'Uloženo': 'Saved',
  'Uložit': 'Save',
  'Zrušit': 'Cancel',
  'Zavřít': 'Close',
  'Smazat': 'Delete',
  'Vytvořit': 'Create',
  'Zobrazit': 'Show',
  'Stáhnout': 'Download',
  'Žádná data': 'No data',
  'Žádné účtenky v tomto období.': 'No receipts in this period.',
  'Žádný produkt nemá zapnuté sledování zásob. Zapněte ho u produktu.': 'No product has stock tracking turned on. Turn it on in the product.',
  'Částka': 'Amount',
  'Hledat…': 'Search…',
  'Od': 'From',
  'Do': 'To',
  'Vše': 'All',
  'Všechny': 'All',
  'Všechny provozovny': 'All branches',
  // payments, roles, stock kinds, statuses
  'Hotově': 'Cash',
  'Kartou': 'Card',
  'QR převod': 'QR transfer',
  'Majitel': 'Owner',
  'Vedoucí': 'Manager',
  'Pokladní': 'Cashier',
  'Naskladnění': 'Goods received',
  'Odpis': 'Write-off',
  'Oprava': 'Correction',
  'Inventura': 'Stocktake',
  'Převod – příjem': 'Transfer – in',
  'Převod – výdej': 'Transfer – out',
  'Prodej': 'Sale',
  'Storno prodeje': 'Sale voided',
  'aktivní': 'active',
  'neaktivní': 'inactive',
  'Aktivní': 'Active',
  'spárováno': 'paired',
  'nespárováno': 'not paired',
  'dochází': 'running low',
  'chybí': 'missing',
  'čeká': 'pending',
  'ověřeno': 'verified',
  'potvrzeno': 'confirmed',
  'nastaven': 'set',
  'odmítnuto': 'rejected',
  'zrušen': 'revoked',
  'v práci': 'at work',
  'Bez EET': 'No EET',
  // dashboard
  'Přehled tržeb': 'Sales overview',
  'Provozovna': 'Branch',
  'Tržba': 'Revenue',
  'Účtenek': 'Receipts',
  'Průměrná účtenka': 'Average receipt',
  'Storna': 'Voids',
  'Hrubá marže': 'Gross margin',
  'Náklad': 'Cost',
  'Podle dní': 'By day',
  'Podle hodin': 'By hour',
  'Podle obsluhy': 'By cashier',
  'Podle provozoven': 'By branch',
  'Podle způsobu platby': 'By payment method',
  'Nejprodávanější produkty': 'Best-selling products',
  'Základ': 'Base',
  'Sazba': 'Rate',
  'DPH': 'VAT',
  'Rozpis DPH': 'VAT breakdown',
  'Den': 'Day',
  'Hodina': 'Hour',
  'Ks': 'Qty',
  // receipts
  'Čas': 'Time',
  'Číslo': 'Number',
  'Platba': 'Payment',
  'Obsluha': 'Cashier',
  'Celkem': 'Total',
  'Účtenka': 'Receipt',
  'Položka': 'Item',
  'Množství': 'Quantity',
  'Cena': 'Price',
  'Sleva': 'Discount',
  'Přijato': 'Received',
  'Pokladna': 'Till',
  'Stornováno': 'Voided',
  'STORNO': 'VOID',
  // products
  'Nový produkt': 'New product',
  'Upravit produkt': 'Edit product',
  'Nová kategorie': 'New category',
  'Kategorie': 'Category',
  'Kategorie:': 'Categories:',
  'Bez kategorie': 'No category',
  'Název': 'Name',
  'Kód (PLU/SKU)': 'Code (PLU/SKU)',
  'Kód / EAN': 'Code / EAN',
  'Kód': 'Code',
  'Jednotka': 'Unit',
  'Sazba DPH': 'VAT rate',
  'Prodejní cena vč. DPH (Kč)': 'Selling price incl. VAT (CZK)',
  'Nákupní cena (Kč za jednotku)': 'Purchase price (CZK per unit)',
  'Barva dlaždic': 'Tile colour',
  'Pořadí': 'Order',
  'Sledovat skladovou zásobu': 'Track stock',
  'Minimální zásoba': 'Minimum stock',
  'Prodává se na pokladně (jinak jen surovina)': 'Sold at the till (otherwise an ingredient only)',
  'Receptura': 'Recipe',
  'Při prodeji se ze skladu odečtou suroviny místo samotného produktu.': 'When sold, the ingredients are taken from stock instead of the product itself.',
  '+ Přidat surovinu': '+ Add ingredient',
  'množství': 'quantity',
  'Smazat produkt?': 'Delete product?',
  'surovina': 'ingredient',
  'suroviny': 'ingredients',
  'surovin': 'ingredients',
  'ano': 'yes',
  'ne': 'no',
  // stock
  'Hodnota zásob:': 'Stock value:',
  'Pod minimem:': 'Below minimum:',
  'Produkt': 'Product',
  'Zásoba': 'Stock',
  'Minimum': 'Minimum',
  'Hodnota': 'Value',
  'Pohyby (posledních 30 dní)': 'Movements (last 30 days)',
  'Druh': 'Type',
  'Poznámka': 'Note',
  'Poznámka / doklad': 'Note / document',
  'Naskladnit': 'Receive goods',
  'Převod': 'Transfer',
  'Převod mezi provozovnami': 'Transfer between branches',
  'Inventura (skutečný stav)': 'Stocktake (actual count)',
  'Do provozovny': 'To branch',
  '+ Další položka': '+ Another item',
  'nákup. cena/j.': 'purchase price/unit',
  'Zadejte množství': 'Enter a quantity',
  // branches, tills
  'Nová provozovna': 'New branch',
  'Nová pokladna': 'New till',
  'Adresa': 'Address',
  'Ev. jednotka EET': 'EET unit',
  'Označení evidenční jednotky (EET 2.0, přiděleno v DIS+)': 'Registration unit ID (EET 2.0, assigned in DIS+)',
  'Označení pokladny pro EET (max 20 znaků)': 'Till ID for EET (max 20 characters)',
  'Označení EET': 'EET ID',
  'Pokladny': 'Tills',
  'Stav': 'Status',
  'Naposledy online': 'Last online',
  'Párovací kód': 'Pairing code',
  'Párovací kód:': 'Pairing code:',
  'Nový párovací kód:': 'New pairing code:',
  'Nový kód': 'New code',
  'Na pokladně otevřete Nastavení → Back office a zadejte adresu serveru a párovací kód (platí 24 hodin).': 'On the till open Settings → Back office and enter the server address and the pairing code (valid for 24 hours).',
  // staff, attendance
  'Nový zaměstnanec': 'New employee',
  'Zaměstnanec': 'Employee',
  'Jméno': 'Name',
  'Role': 'Role',
  'E-mail (pro vedoucí a majitele)': 'E-mail (for managers and owners)',
  'PIN na pokladně': 'Till PIN',
  '(4–8 číslic)': '(4–8 digits)',
  'PIN (4–8 číslic)': 'PIN (4–8 digits)',
  'Nový PIN (4–8 číslic)': 'New PIN (4–8 digits)',
  'Nové heslo': 'New password',
  'Pokladní se přihlašují na pokladně PINem. Vedoucí a majitel se přihlašují sem e-mailem a heslem. Zaměstnance může spravovat jen majitel.': 'Cashiers sign in at the till with a PIN. Managers and owners sign in here with e-mail and password. Only the owner can manage staff.',
  'Odpracováno celkem': 'Total hours worked',
  'Hodin': 'Hours',
  'Směny': 'Shifts',
  'Příchod': 'Clock in',
  'Odchod': 'Clock out',
  'Minut': 'Minutes',
  // EET
  'Potvrzeno': 'Confirmed',
  'Čeká na odeslání': 'Waiting to be sent',
  'Odmítnuto': 'Rejected',
  'K řešení': 'Needs attention',
  'Tržby odesílá do EET přímo pokladna (podepisuje je pokladním certifikátem). Čekající tržby pokladna odešle sama, jakmile má internet; zákonná lhůta je 48 hodin.': 'Each till sends its sales to EET itself (signed with the EET certificate). Pending sales are sent automatically once the till is online; the legal deadline is 48 hours.',
  // exports
  'Exporty pro účetnictví': 'Accounting exports',
  'Tržby (CSV)': 'Sales (CSV)',
  'Položky (CSV)': 'Items (CSV)',
  'Jedna řádka na účtenku, s rozpisem DPH. Pro Excel a většinu účetních programů.': 'One line per receipt, with the VAT breakdown. For Excel and most accounting software.',
  'Všechny prodané položky.': 'All items sold.',
  'Prodejky pro import do POHODY (Soubor → Datová komunikace → XML import/export).': 'Sales documents for import into POHODA (File → Data communication → XML import/export).',
  // API keys
  'API pro integrace': 'Integration API',
  'API klíč umožní e-shopu, účetnímu nebo jinému systému číst data (READ) nebo upravovat produkty a sklad (WRITE).': 'An API key lets an e-shop, your accountant or another system read data (READ) or change products and stock (WRITE).',
  'Volání:': 'Usage:',
  'API klíč umožní e-shopu, účetnímu nebo jinému systému číst data (READ) nebo upravovat produkty a sklad (WRITE). Volání:': 'An API key lets an e-shop, your accountant or another system read data (READ) or change products and stock (WRITE). Usage:',
  'Oprávnění': 'Permission',
  'Jen čtení': 'Read only',
  'Čtení i zápis': 'Read and write',
  'Vytvořit klíč': 'Create key',
  'např. E-shop': 'e.g. E-shop',
  'Nový klíč – zkopírujte si ho, znovu se nezobrazí:': 'New key – copy it now, it will not be shown again:',
  'Začátek klíče': 'Key prefix',
  'Vytvořen': 'Created',
  'Naposledy použit': 'Last used',
  'Zrušit klíč': 'Revoke key',
  '(produkty, sklad, účtenky, přehledy, exporty).': '(products, stock, receipts, reports, exports).',
  // company
  'Sídlo': 'Registered address',
  'IČO': 'Company ID (IČO)',
  'DIČ': 'VAT ID (DIČ)',
  'Plátce DPH': 'VAT payer',
  // server errors
  'API klíč má jen oprávnění ke čtení': 'This API key is read-only',
  'Cena nesmí být záporná': 'The price cannot be negative',
  'Chybí cílová provozovna': 'The target branch is missing',
  'Chybí přihlášení': 'Not signed in',
  'E-mail už je použit': 'This e-mail is already in use',
  'Heslo musí mít alespoň 8 znaků': 'The password must have at least 8 characters',
  'Hlavní provozovna': 'Main branch',
  'Jen pro spárované pokladny': 'Only for paired tills',
  'Jen pro uživatele': 'Only for users',
  'Nedostatečná oprávnění': 'Insufficient permissions',
  'Nejvýše 500 účtenek najednou': 'At most 500 receipts at once',
  'Neplatné nebo prošlé přihlášení': 'Invalid or expired sign-in',
  'Neplatný PIN': 'Invalid PIN',
  'Neplatný nebo prošlý párovací kód': 'Invalid or expired pairing code',
  'Nesprávný e-mail nebo heslo': 'Wrong e-mail or password',
  'Neznámá role': 'Unknown role',
  'PIN musí mít 4–8 číslic': 'The PIN must have 4–8 digits',
  'Produkt nemůže být surovinou sám sobě': 'A product cannot be its own ingredient',
  'Převod do stejné provozovny': 'Transfer to the same branch',
  'Rozsah musí být READ nebo WRITE': 'Scope must be READ or WRITE',
  'Sazba DPH musí být 0, 12 nebo 21 %': 'The VAT rate must be 0, 12 or 21 %',
  'Tuto akci může provést jen majitel': 'Only the owner can do this',
  'Uživatel nenalezen': 'User not found',
  'Vedoucí a majitel potřebují e-mail pro přihlášení': 'Managers and owners need an e-mail to sign in',
  'Vyplňte firmu, jméno a e-mail': 'Fill in the company, name and e-mail',
  'Vyplňte jméno': 'Fill in the name',
  'Vyplňte název': 'Fill in the name',
  'Účet s tímto e-mailem už existuje': 'An account with this e-mail already exists',
  'Registrace je uzavřena. Nového uživatele přidá majitel v sekci Uživatelé.': 'Sign-up is closed. The owner adds new users under Users.',
};

// Texts with a variable part.
const EN_PATTERNS = [
  [/^Účtenka (.+)$/, (m) => `Receipt ${m[1]}`],
  [/^Pokladna (\d+)$/, (m) => `Till ${m[1]}`],
  [/^Nový párovací kód: (.+)$/, (m) => `New pairing code: ${m[1]}`],
  [/^Párovací kód: (.+)$/, (m) => `Pairing code: ${m[1]}`],
  [/^Chyba (\d+)$/, (m) => `Error ${m[1]}`],
  [/^Chybný parametr (.+)$/, (m) => `Invalid parameter ${m[1]}`],
  [/^Neplatná data: (.+)$/, (m) => `Invalid data: ${m[1]}`],
  [/^Neznámá surovina (.+)$/, (m) => `Unknown ingredient ${m[1]}`],
  [/^Neznámý druh pohybu (.+)$/, (m) => `Unknown movement type ${m[1]}`],
  [/^Neznámý produkt (.+)$/, (m) => `Unknown product ${m[1]}`],
];

/** Translate one Czech string; unknown text is returned unchanged. */
function t(cs) {
  if (LANG !== 'en' || cs == null) return cs;
  const s = String(cs);
  const key = s.trim().replace(/\s+/g, ' ');
  if (!key) return s;
  let out = EN[key];
  if (out === undefined) {
    for (const [re, fn] of EN_PATTERNS) { const m = key.match(re); if (m) { out = fn(m); break; } }
  }
  if (out === undefined) return s;
  const lead = s.match(/^\s*/)[0], trail = s.match(/\s*$/)[0];
  return lead + out + trail;
}

const I18N_ATTRS = ['placeholder', 'title', 'aria-label'];
function translateTree(root) {
  if (LANG !== 'en' || !root) return;
  if (root.nodeType === Node.TEXT_NODE) { const n = t(root.nodeValue); if (n !== root.nodeValue) root.nodeValue = n; return; }
  if (root.nodeType !== Node.ELEMENT_NODE) return;
  if (root.closest && root.closest('.code, code, script, style, [data-no-i18n]')) return;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => (n.parentElement && n.parentElement.closest('.code, code, script, style, [data-no-i18n]')) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
  });
  const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode);
  nodes.forEach((n) => { const v = t(n.nodeValue); if (v !== n.nodeValue) n.nodeValue = v; });
  const els = [root, ...root.querySelectorAll('[placeholder],[title],[aria-label]')];
  els.forEach((el) => I18N_ATTRS.forEach((a) => { if (el.hasAttribute && el.hasAttribute(a)) { const v = t(el.getAttribute(a)); if (v !== el.getAttribute(a)) el.setAttribute(a, v); } }));
}

function setLanguage(lang) {
  try { localStorage.setItem('flos_lang', lang); } catch (e) { /* storage blocked */ }
  location.reload();
}

function initI18n() {
  document.querySelectorAll('[data-lang-toggle]').forEach((b) => {
    const other = LANG === 'en' ? 'cs' : 'en';
    b.textContent = other === 'en' ? 'English' : 'Čeština';
    b.setAttribute('lang', other);
    b.addEventListener('click', () => setLanguage(other));
  });
  if (LANG !== 'en') return;
  document.title = t(document.title);
  translateTree(document.body);
  new MutationObserver((list) => {
    for (const m of list) {
      if (m.type === 'characterData') translateTree(m.target);
      else m.addedNodes.forEach(translateTree);
    }
  }).observe(document.body, { childList: true, subtree: true, characterData: true });
}
initI18n();
