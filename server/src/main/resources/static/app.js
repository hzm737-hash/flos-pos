// Flos back office – a small single-page app over the REST API (/api/v1).
'use strict';

const $ = (s, el = document) => el.querySelector(s);
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const kc = (h) => (Number(h || 0) / 100).toLocaleString(LOCALE, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + (LANG === 'en' ? ' CZK' : ' Kč');
const qty = (q) => Number(q || 0).toLocaleString(LOCALE, { maximumFractionDigits: 3 });
const toHaler = (s) => Math.round(parseFloat(String(s).replace(/\s/g, '').replace(',', '.')) * 100) || 0;
const fromHaler = (h) => (Number(h || 0) / 100).toFixed(2).replace('.', ',');
const dt = (ms) => ms ? new Date(ms).toLocaleString(LOCALE) : '–';
const today = () => new Date().toLocaleDateString('sv-SE');
const PAYMENT = { CASH: 'Hotově', CARD: 'Kartou', QR_TRANSFER: 'QR převod', BANK_TRANSFER: 'Bankovní převod', MEAL_VOUCHER: 'Stravenky', INVOICE: 'Na fakturu' };
const ROLE = { OWNER: 'Majitel', MANAGER: 'Vedoucí', CASHIER: 'Pokladní' };
const KIND = { RECEIPT: 'Naskladnění', WASTE: 'Odpis', ADJUST: 'Oprava', INVENTORY: 'Inventura', TRANSFER_IN: 'Převod – příjem', TRANSFER_OUT: 'Převod – výdej', SALE: 'Prodej', SALE_RETURN: 'Storno prodeje' };

[PAYMENT, ROLE, KIND].forEach((m) => Object.keys(m).forEach((k) => { m[k] = t(m[k]); }));

let token = null;
try { token = localStorage.getItem('flos_token'); } catch (e) { /* storage blocked */ }
let me = null;
const cache = {};

function toast(msg) {
  const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toast.timer); toast.timer = setTimeout(() => t.classList.add('hidden'), 3500);
}

async function api(path, opts = {}) {
  const res = await fetch('/api/v1' + path, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  if (res.status === 401 && !path.startsWith('/auth/')) { logout(); throw new Error('Přihlášení vypršelo'); }
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error((data && data.error) || ('Chyba ' + res.status));
  return data;
}

async function download(path, filename) {
  const res = await fetch('/api/v1' + path, { headers: { Authorization: 'Bearer ' + token } });
  if (!res.ok) { toast('Export se nezdařil'); return; }
  const blob = await res.blob();
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

function setToken(t) { token = t; try { t ? localStorage.setItem('flos_token', t) : localStorage.removeItem('flos_token'); } catch (e) {} }
function logout() { api('/auth/logout', { method: 'POST' }).catch(() => {}); setToken(null); showLogin(); }

function showLogin() { $('#app').classList.add('hidden'); $('#login').classList.remove('hidden'); }

async function start() {
  if (!token) return showLogin();
  try { me = await api('/me'); } catch (e) { return showLogin(); }
  $('#login').classList.add('hidden'); $('#app').classList.remove('hidden');
  $('#org-name').textContent = me.user.name;
  await loadCompanies();
  route();
}

// ---------- several companies (each with its own IČO/DIČ) under one login ----------
async function loadCompanies() {
  const list = await api('/companies').catch(() => []);
  const sel = $('#company-switch');
  sel.innerHTML = list.map((c) => `<option value="${c.id}" ${c.current ? 'selected' : ''}>${esc(c.name)}${c.dic ? ' · ' + esc(c.dic) : ''}</option>`).join('')
    + (me.user.role === 'OWNER' ? `<option value="new">${esc(t('+ Přidat další firmu…'))}</option>` : '');
  sel.onchange = async () => {
    if (sel.value === 'new') { sel.value = String(me.org.id); return companyDialog(); }
    try { me = await api('/companies/' + sel.value + '/switch', { method: 'POST', body: {} }); Object.keys(cache).forEach((k) => delete cache[k]); toast(t('Přepnuto na') + ' ' + me.org.name); route(); }
    catch (err) { toast(err.message); }
  };
}
function companyDialog() {
  const d = $('#cdlg');
  d.innerHTML = `<form><h2>Nová firma</h2><p class="muted">Každá firma má vlastní IČO a DIČ, produkty, provozovny, pokladny a tržby. Přepínáte mezi nimi nahoře v liště.</p>
    <label>Název<input name="name" required></label><div class="row"><label>IČO<input name="ico"></label><label>DIČ<input name="dic"></label></div>
    <label>Sídlo<input name="address"></label><label class="check"><input type="checkbox" name="vatPayer"> Plátce DPH</label>
    <div class="actions"><button type="button" id="cancel">Zrušit</button><button class="primary">Založit firmu</button></div></form>`;
  $('#cancel', d).onclick = () => d.close();
  $('form', d).onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    try {
      const o = await api('/companies', { method: 'POST', body: { name: f.get('name'), ico: f.get('ico'), dic: f.get('dic'), address: f.get('address'), vatPayer: f.get('vatPayer') === 'on' } });
      me = await api('/companies/' + o.id + '/switch', { method: 'POST', body: {} });
      Object.keys(cache).forEach((k) => delete cache[k]);
      d.close(); await loadCompanies(); location.hash = '#company'; route();
    } catch (err) { toast(err.message); }
  };
  d.showModal();
}

$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault(); const f = new FormData(e.target);
  try { const r = await api('/auth/login', { method: 'POST', body: { email: f.get('email'), password: f.get('password') } }); setToken(r.token); start(); }
  catch (err) { $('#login-error').textContent = err.message; }
});
// Sign-up is only offered while it is open (on a live server: until the first owner exists).
api('/auth/signup').then(r => { if (!r.open) $('#register-box').hidden = true; }).catch(() => {});
$('#register-form').addEventListener('submit', async (e) => {
  e.preventDefault(); const f = Object.fromEntries(new FormData(e.target));
  try { const r = await api('/auth/register', { method: 'POST', body: f }); setToken(r.token); start(); }
  catch (err) { $('#login-error').textContent = err.message; }
});
$('#logout').addEventListener('click', logout);
$('#menu-toggle').addEventListener('click', () => $('#nav').classList.toggle('open'));
document.querySelectorAll('#nav a').forEach((a) => a.addEventListener('click', () => { location.hash = a.dataset.page; $('#nav').classList.remove('open'); }));
window.addEventListener('hashchange', route);

const pages = {};
async function route() {
  const page = (location.hash || '#dashboard').slice(1);
  document.querySelectorAll('#nav a').forEach((a) => a.classList.toggle('active', a.dataset.page === page));
  const main = $('#main'); main.innerHTML = '<p class="muted">Načítám…</p>';
  try { await (pages[page] || pages.dashboard)(main); } catch (e) { main.innerHTML = `<p class="error">${esc(e.message)}</p>`; }
}

async function branches() { return (cache.branches = await api('/branches')); }
function branchSelect(name, list, selected, withAll) {
  return `<select name="${name}">${withAll ? '<option value="">Všechny provozovny</option>' : ''}${list.map((b) => `<option value="${b.id}" ${b.id == selected ? 'selected' : ''}>${esc(b.name)}</option>`).join('')}</select>`;
}
function rangeToolbar(extra = '') {
  return `<form class="toolbar" id="range"><label>Od<input type="date" name="from" value="${today()}"></label><label>Do<input type="date" name="to" value="${today()}"></label>${extra}<button class="primary">Zobrazit</button></form>`;
}
function qs(form) { return new URLSearchParams([...new FormData(form)].filter(([, v]) => v !== '')).toString(); }
function bars(rows, label = (r) => r.key) {
  const max = Math.max(1, ...rows.map((r) => r.amount));
  return `<div class="bars">${rows.map((r) => `<div class="bar"><span>${esc(label(r))}</span><div class="track"><div class="fill" style="width:${(100 * r.amount / max).toFixed(1)}%"></div></div><span class="amount">${kc(r.amount)}</span></div>`).join('') || '<p class="muted">Žádná data</p>'}</div>`;
}

// ---------- Dashboard ----------
pages.dashboard = async (main) => {
  const bl = await branches();
  main.innerHTML = `<h1>Přehled tržeb</h1>${rangeToolbar(`<label>Provozovna${branchSelect('branchId', bl, '', true)}</label>`)}<div id="out"></div>`;
  const load = async () => {
    const r = await api('/reports/summary?' + qs($('#range')));
    $('#out').innerHTML = `
      <div class="grid">
        <div class="card kpi"><div class="label">Tržba</div><div class="value">${kc(r.revenue)}</div></div>
        <div class="card kpi"><div class="label">Účtenek</div><div class="value">${r.receipts}</div></div>
        <div class="card kpi"><div class="label">Průměrná účtenka</div><div class="value">${kc(r.averageReceipt)}</div></div>
        <div class="card kpi"><div class="label">Hrubá marže</div><div class="value">${r.grossMargin == null ? '–' : kc(r.grossMargin)}</div></div>
        <div class="card kpi"><div class="label">Storna</div><div class="value">${r.cancelled}</div></div>
      </div>
      <div class="row">
        <div><h2>Podle způsobu platby</h2>${bars(r.byPayment, (x) => PAYMENT[x.key] || x.key)}</div>
        <div><h2>Podle provozoven</h2>${bars(r.byBranch)}</div>
        <div><h2>Podle dní</h2>${bars(r.byDay)}</div>
        <div><h2>Podle hodin</h2>${bars(r.byHour, (x) => x.key + ':00')}</div>
      </div>
      <h2>DPH</h2>
      <table><tr><th>Sazba</th><th class="num">Základ</th><th class="num">DPH</th><th class="num">Celkem</th></tr>
      ${r.byVat.map((v) => `<tr><td>${v.rate} %</td><td class="num">${kc(v.base)}</td><td class="num">${kc(v.vat)}</td><td class="num">${kc(v.gross)}</td></tr>`).join('')}</table>
      <h2>Nejprodávanější produkty</h2>
      <table><tr><th>Produkt</th><th class="num">Ks</th><th class="num">Tržba</th><th class="num">Náklad</th></tr>
      ${r.topProducts.map((p) => `<tr><td>${esc(p.name)}</td><td class="num">${qty(p.quantity)}</td><td class="num">${kc(p.revenue)}</td><td class="num">${p.cost ? kc(p.cost) : '–'}</td></tr>`).join('')}</table>
      <h2>Podle obsluhy</h2>${bars(r.byCashier)}`;
  };
  $('#range').addEventListener('submit', (e) => { e.preventDefault(); load(); });
  await load();
};

// ---------- Sales ----------
pages.sales = async (main) => {
  const bl = await branches();
  main.innerHTML = `<h1>Účtenky</h1>${rangeToolbar(`<label>Provozovna${branchSelect('branchId', bl, '', true)}</label>`)}<div id="out"></div><dialog id="dlg"></dialog>`;
  const load = async () => {
    const sales = await api('/sales?' + qs($('#range')));
    const names = Object.fromEntries(bl.map((b) => [b.id, b.name]));
    $('#out').innerHTML = `<table><tr><th>Čas</th><th>Číslo</th><th>Provozovna</th><th>Platba</th><th>Obsluha</th><th>EET</th><th class="num">Celkem</th></tr>
      ${sales.map((s, i) => `<tr class="clickable ${s.cancelled ? 'cancelled' : ''}" data-i="${i}"><td>${dt(s.createdAt)}</td><td>${esc(s.number)}</td><td>${esc(names[s.branchId])}</td>
      <td>${PAYMENT[s.payment] || esc(s.payment)}</td><td>${esc(s.cashier)}</td><td>${eetBadge(s.eetStatus)}</td><td class="num">${kc(s.received)}</td></tr>`).join('')}</table>
      ${sales.length ? '' : '<p class="muted">Žádné účtenky v tomto období.</p>'}`;
    document.querySelectorAll('tr[data-i]').forEach((tr) => tr.addEventListener('click', () => {
      const s = sales[tr.dataset.i]; const d = $('#dlg');
      d.innerHTML = `<form method="dialog"><h2>Účtenka ${esc(s.number)}</h2><p class="muted">${dt(s.createdAt)} · ${PAYMENT[s.payment] || ''} · ${esc(s.cashier)}</p>
        <table><tr><th>Položka</th><th class="num">Ks</th><th class="num">Cena</th></tr>${s.items.map((i) => `<tr><td>${esc(i.name)}${i.discountPercent ? ` (−${i.discountPercent} %)` : ''}</td><td class="num">${qty(i.quantity)}</td><td class="num">${kc(Math.round(i.unitPrice * i.quantity))}</td></tr>`).join('')}</table>
        <p><b>Celkem ${kc(s.received)}</b>${s.cancelled ? ` · <span class="badge bad">Storno ${esc(s.stornoNumber || '')}</span>` : ''}</p>
        <p class="muted">EET: ${eetBadge(s.eetStatus)} ${s.eetPok ? 'POK ' + esc(s.eetPok) : ''}</p><div class="actions"><button>Zavřít</button></div></form>`;
      d.showModal();
    }));
  };
  $('#range').addEventListener('submit', (e) => { e.preventDefault(); load(); });
  await load();
};
function eetBadge(s) {
  if (!s) return '<span class="badge">–</span>';
  return { CONFIRMED: '<span class="badge ok">potvrzeno</span>', PENDING: '<span class="badge warn">čeká</span>', REJECTED: '<span class="badge bad">odmítnuto</span>', VERIFIED: '<span class="badge">ověřeno</span>' }[s] || esc(s);
}

// ---------- Products ----------
const ICONS = ['coffee', 'cafe', 'tea', 'drink', 'beer', 'wine', 'cocktail', 'spirits', 'pizza', 'burger', 'fastfood', 'kebab', 'pasta', 'noodles', 'rice', 'soup',
  'fish', 'tapas', 'breakfast', 'egg', 'salad', 'bakery', 'cake', 'cookie', 'icecream', 'meal', 'grocery', 'basket', 'flower', 'star'];
const iconSelect = (v) => `<select name="icon"><option value="">–</option>${ICONS.map((i) => `<option value="${i}" ${i === v ? 'selected' : ''}>${i}</option>`).join('')}</select>`;
const imgUrl = (ref) => (/^\d+-[0-9a-f]{32}$/.test(ref || '') ? '/img/' + ref : '');

/** Makes a photo at most 800 px wide/high and sends it as JPEG; returns its reference. */
async function uploadPhoto(file) {
  const bmp = await createImageBitmap(file);
  const k = Math.min(1, 800 / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas'); c.width = Math.round(bmp.width * k); c.height = Math.round(bmp.height * k);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  const data = c.toDataURL('image/jpeg', 0.85);
  return (await api('/images', { method: 'POST', body: { mime: 'image/jpeg', data } })).image;
}
pages.products = async (main) => {
  const [products, categories] = await Promise.all([api('/products'), api('/categories')]);
  const catName = Object.fromEntries(categories.map((c) => [c.id, c.name]));
  main.innerHTML = `<h1>Produkty</h1>
    <div class="toolbar"><button class="primary" id="new">Nový produkt</button><button id="newcat">Nová kategorie</button>
    <input id="filter" placeholder="Hledat…" style="max-width:240px"></div>
    <p class="muted">${t('Kategorie:')} ${categories.map((c) => `<a href="#" data-cat="${c.id}">${esc(c.name)}</a>`).join(', ') || '–'}</p>
    <table id="tbl"><tr><th></th><th>Název</th><th>Kategorie</th><th>Kód / EAN</th><th class="num">Cena</th><th class="num">DPH</th><th>Sklad</th><th>Receptura</th></tr>
    ${products.map((p) => `<tr class="clickable" data-id="${p.id}" data-name="${esc(p.name.toLowerCase())}"><td>${imgUrl(p.image) ? `<img class="thumb" alt="" src="${imgUrl(p.image)}">` : ''}</td><td>${esc(p.name)}${p.openPrice ? ' <span class="badge">cena při prodeji</span>' : ''}${p.options ? ' <span class="badge">varianty</span>' : ''}${p.sellable ? '' : ' <span class="badge">surovina</span>'}</td><td>${esc(catName[p.categoryId] || '')}</td>
      <td>${esc(p.sku)} ${esc(p.barcode)}</td><td class="num">${kc(p.price)}</td><td class="num">${p.vatPercent} %</td><td>${p.trackStock ? t('ano') + ' (' + esc(p.unit) + ')' : ''}</td><td>${p.recipe.length ? p.recipe.length + ' ' + t(p.recipe.length === 1 ? 'surovina' : p.recipe.length < 5 ? 'suroviny' : 'surovin') : ''}</td></tr>`).join('')}</table>
    <dialog id="dlg"></dialog>`;
  $('#filter').addEventListener('input', (e) => document.querySelectorAll('#tbl tr[data-name]').forEach((tr) => tr.classList.toggle('hidden', !tr.dataset.name.includes(e.target.value.toLowerCase()))));
  $('#new').addEventListener('click', () => productDialog(null, products, categories));
  document.querySelectorAll('#tbl tr[data-id]').forEach((tr) => tr.addEventListener('click', () => productDialog(products.find((p) => p.id == tr.dataset.id), products, categories)));
  $('#newcat').addEventListener('click', () => categoryDialog(null));
  document.querySelectorAll('[data-cat]').forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); categoryDialog(categories.find((c) => c.id == a.dataset.cat)); }));
};

function productDialog(p, products, categories) {
  const d = $('#dlg'); const isNew = !p;
  p = p || { name: '', price: 0, vatPercent: me.org.vatPayer ? 21 : 0, sku: '', barcode: '', unit: 'ks', costPrice: 0, trackStock: false, sellable: true, minStock: 0, recipe: [], options: '', prices: '', openPrice: false, icon: '', image: '' };
  let image = p.image || '';
  const ingredients = products.filter((x) => x.id !== p.id);
  const recipeRow = (r = { ingredientId: '', qty: '' }) => `<div class="recipe-line"><select name="ing">${ingredients.map((x) => `<option value="${x.id}" ${x.id == r.ingredientId ? 'selected' : ''}>${esc(x.name)} (${esc(x.unit)})</option>`).join('')}</select>
    <input name="ingqty" type="number" step="0.001" min="0" value="${r.qty}" placeholder="množství"><button type="button" class="danger" data-rm>✕</button></div>`;
  d.innerHTML = `<form><h2>${isNew ? 'Nový produkt' : 'Upravit produkt'}</h2>
    <label>Název<input name="name" required value="${esc(p.name)}"></label>
    <div class="row"><label>Prodejní cena vč. DPH (Kč)<input name="price" value="${fromHaler(p.price)}"></label>
    <label>Sazba DPH<select name="vatPercent">${[21, 12, 0].map((v) => `<option value="${v}" ${v == p.vatPercent ? 'selected' : ''}>${v} %</option>`).join('')}</select></label></div>
    <div class="row"><label>Kategorie<select name="categoryId"><option value="">–</option>${categories.map((c) => `<option value="${c.id}" ${c.id == p.categoryId ? 'selected' : ''}>${esc(c.name)}</option>`).join('')}</select></label>
    <label>Jednotka<input name="unit" value="${esc(p.unit)}"></label></div>
    <div class="row"><label>Kód (PLU/SKU)<input name="sku" value="${esc(p.sku)}"></label><label>EAN<input name="barcode" value="${esc(p.barcode)}"></label></div>
    <div class="row"><label>Nákupní cena (Kč za jednotku)<input name="costPrice" value="${fromHaler(p.costPrice)}"></label><label>Minimální zásoba<input name="minStock" type="number" step="0.001" value="${p.minStock}"></label></div>
    <h2>Na pokladně</h2>
    <div class="row"><label>Fotka<span class="photo"><img id="ph" alt="" ${imgUrl(image) ? `src="${imgUrl(image)}"` : 'hidden'}>
      <input type="file" id="phfile" accept="image/jpeg,image/png,image/webp"><button type="button" id="phdel" ${image ? '' : 'hidden'}>Odebrat fotku</button></span></label>
    <label>Ikona (bez fotky)${iconSelect(p.icon)}</label></div>
    <label>Varianty a přídavky<textarea name="options" rows="3" placeholder="Velikost: Malá, Velká +40&#10;Přísady*: Sýr +15, Šunka +25">${esc(p.options)}</textarea></label>
    <p class="muted">Každý řádek jedna skupina. Hvězdička za názvem = lze vybrat více (přídavky), jinak se vybírá jedna varianta. Cena za volbou se přičte.</p>
    <label>Ceny v jiných cenících<textarea name="prices" rows="2" placeholder="Rozvoz: 89&#10;Happy hour: 49">${esc(p.prices)}</textarea></label>
    <label class="check"><input type="checkbox" name="openPrice" ${p.openPrice ? 'checked' : ''}> Cena se zadává při prodeji</label>
    <h2>Sklad</h2>
    <label class="check"><input type="checkbox" name="trackStock" ${p.trackStock ? 'checked' : ''}> Sledovat skladovou zásobu</label>
    <label class="check"><input type="checkbox" name="sellable" ${p.sellable ? 'checked' : ''}> Prodává se na pokladně (jinak jen surovina)</label>
    <h2>Receptura</h2><p class="muted">Při prodeji se ze skladu odečtou suroviny místo samotného produktu.</p>
    <div id="recipe">${p.recipe.map(recipeRow).join('')}</div><button type="button" id="addrow">+ Přidat surovinu</button>
    <div class="actions">${isNew ? '' : '<button type="button" class="danger" id="del">Smazat</button>'}<button type="button" id="cancel">Zrušit</button><button class="primary">Uložit</button></div></form>`;
  const form = $('form', d);
  const bindRm = () => d.querySelectorAll('[data-rm]').forEach((b) => (b.onclick = () => b.parentElement.remove()));
  $('#addrow', d).onclick = () => { $('#recipe', d).insertAdjacentHTML('beforeend', recipeRow()); bindRm(); };
  bindRm();
  $('#phfile', d).onchange = async (e) => {
    const f = e.target.files[0]; if (!f) return;
    try { image = await uploadPhoto(f); $('#ph', d).src = imgUrl(image); $('#ph', d).hidden = false; $('#phdel', d).hidden = false; }
    catch (err) { toast(err.message); }
  };
  $('#phdel', d).onclick = () => { image = ''; $('#ph', d).hidden = true; $('#phdel', d).hidden = true; $('#phfile', d).value = ''; };
  $('#cancel', d).onclick = () => d.close();
  if (!isNew) $('#del', d).onclick = async () => { if (confirm(t('Smazat produkt?'))) { await api('/products/' + p.id, { method: 'DELETE' }); d.close(); route(); } };
  form.onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(form);
    const ings = f.getAll('ing'); const qtys = f.getAll('ingqty');
    const body = {
      name: f.get('name'), price: toHaler(f.get('price')), vatPercent: +f.get('vatPercent'), categoryId: f.get('categoryId') ? +f.get('categoryId') : null,
      unit: f.get('unit') || 'ks', sku: f.get('sku'), barcode: f.get('barcode'), costPrice: toHaler(f.get('costPrice')), minStock: +f.get('minStock') || 0,
      trackStock: f.get('trackStock') === 'on', sellable: f.get('sellable') === 'on', sortOrder: p.sortOrder || 0, active: true,
      recipe: ings.map((id, i) => ({ ingredientId: +id, qty: +qtys[i] })).filter((r) => r.qty > 0),
      options: f.get('options').trim(), prices: f.get('prices').trim(), openPrice: f.get('openPrice') === 'on', icon: f.get('icon') || '', image,
    };
    try { await api(isNew ? '/products' : '/products/' + p.id, { method: isNew ? 'POST' : 'PUT', body }); d.close(); toast('Uloženo'); route(); }
    catch (err) { toast(err.message); }
  };
  d.showModal();
}

function categoryDialog(c) {
  const d = $('#dlg'); const isNew = !c; c = c || { name: '', color: 0xFF2E7D6B, sortOrder: 0, icon: '' };
  const hex = '#' + (Number(c.color) & 0xFFFFFF).toString(16).padStart(6, '0');
  d.innerHTML = `<form><h2>${isNew ? 'Nová kategorie' : 'Kategorie'}</h2><label>Název<input name="name" required value="${esc(c.name)}"></label>
    <label>Barva dlaždic<input name="color" type="color" value="${hex}"></label><label>Pořadí<input name="sortOrder" type="number" value="${c.sortOrder}"></label><label>Ikona${iconSelect(c.icon)}</label>
    <div class="actions">${isNew ? '' : '<button type="button" class="danger" id="del">Smazat</button>'}<button type="button" id="cancel">Zrušit</button><button class="primary">Uložit</button></div></form>`;
  $('#cancel', d).onclick = () => d.close();
  if (!isNew) $('#del', d).onclick = async () => { await api('/categories/' + c.id, { method: 'DELETE' }); d.close(); route(); };
  $('form', d).onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    const body = { name: f.get('name'), color: 0xFF000000 + parseInt(f.get('color').slice(1), 16), sortOrder: +f.get('sortOrder') || 0, icon: f.get('icon') || '' };
    try { await api(isNew ? '/categories' : '/categories/' + c.id, { method: isNew ? 'POST' : 'PUT', body }); d.close(); route(); } catch (err) { toast(err.message); }
  };
  d.showModal();
}

// ---------- Stock ----------
pages.stock = async (main) => {
  const [bl, products] = await Promise.all([branches(), api('/products')]);
  const stocked = products.filter((p) => p.trackStock);
  main.innerHTML = `<h1>Sklad</h1>
    <form class="toolbar" id="sel"><label>Provozovna${branchSelect('branchId', bl, bl[0] && bl[0].id, false)}</label>
    <button type="button" class="primary" data-kind="RECEIPT">Naskladnit</button><button type="button" data-kind="INVENTORY">Inventura</button>
    <button type="button" data-kind="WASTE">Odpis</button><button type="button" data-kind="TRANSFER">Převod</button></form>
    <div id="levels"></div><h2>Pohyby (posledních 30 dní)</h2><div id="moves"></div><dialog id="dlg"></dialog>`;
  const load = async () => {
    const b = $('#sel').branchId.value;
    const from = new Date(Date.now() - 30 * 86400000).toLocaleDateString('sv-SE');
    const [levels, moves] = await Promise.all([api('/stock?branchId=' + b), api(`/stock/movements?branchId=${b}&from=${from}&to=${today()}`)]);
    const total = levels.reduce((s, l) => s + l.value, 0);
    $('#levels').innerHTML = `<p class="muted">Hodnota zásob: <b>${kc(total)}</b> · Pod minimem: <b>${levels.filter((l) => l.low).length}</b></p>
      <table><tr><th>Produkt</th><th class="num">Zásoba</th><th class="num">Minimum</th><th class="num">Hodnota</th></tr>
      ${levels.map((l) => `<tr><td>${esc(l.productName)} ${l.low ? '<span class="badge bad">dochází</span>' : ''}</td><td class="num">${l.qty.toLocaleString(LOCALE)} ${esc(l.unit)}</td><td class="num">${l.minStock.toLocaleString(LOCALE)}</td><td class="num">${kc(l.value)}</td></tr>`).join('')}</table>
      ${stocked.length ? '' : '<p class="muted">Žádný produkt nemá zapnuté sledování zásob. Zapněte ho u produktu.</p>'}`;
    $('#moves').innerHTML = `<table><tr><th>Čas</th><th>Druh</th><th>Produkt</th><th class="num">Množství</th><th>Poznámka</th></tr>
      ${moves.map((m) => `<tr><td>${dt(m.createdAt)}</td><td>${KIND[m.kind] || m.kind}</td><td>${esc(m.productName)}</td><td class="num">${m.qty.toLocaleString(LOCALE)}</td><td>${esc(m.note || m.ref)}</td></tr>`).join('')}</table>`;
  };
  $('#sel').branchId.addEventListener('change', load);
  document.querySelectorAll('[data-kind]').forEach((btn) => btn.addEventListener('click', () => stockDialog(btn.dataset.kind, +$('#sel').branchId.value, bl, stocked, load)));
  await load();
};

function stockDialog(kind, branchId, bl, products, done) {
  const d = $('#dlg');
  const title = { RECEIPT: 'Naskladnění', INVENTORY: 'Inventura (skutečný stav)', WASTE: 'Odpis', TRANSFER: 'Převod mezi provozovnami' }[kind];
  const line = () => `<div class="recipe-line"><select name="pid">${products.map((p) => `<option value="${p.id}">${esc(p.name)} (${esc(p.unit)})</option>`).join('')}</select>
    <input name="qty" type="number" step="0.001" min="0" placeholder="množství">${kind === 'RECEIPT' ? '<input name="cost" placeholder="nákup. cena/j.">' : '<button type="button" class="danger" data-rm>✕</button>'}</div>`;
  d.innerHTML = `<form><h2>${title}</h2>${kind === 'TRANSFER' ? `<label>Do provozovny${branchSelect('to', bl.filter((b) => b.id !== branchId), '', false)}</label>` : ''}
    <div id="lines">${line()}</div><button type="button" id="add">+ Další položka</button><label>Poznámka / doklad<input name="note"></label>
    <div class="actions"><button type="button" id="cancel">Zrušit</button><button class="primary">Uložit</button></div></form>`;
  $('#add', d).onclick = () => $('#lines', d).insertAdjacentHTML('beforeend', line());
  $('#cancel', d).onclick = () => d.close();
  $('form', d).onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    const pids = f.getAll('pid'); const qtys = f.getAll('qty'); const costs = f.getAll('cost');
    const lines = pids.map((id, i) => ({ productId: +id, qty: parseFloat(String(qtys[i]).replace(',', '.')), unitCost: costs[i] ? toHaler(costs[i]) : null }))
      .filter((l, i) => qtys[i] !== '' && !isNaN(l.qty));
    if (!lines.length) { toast('Zadejte množství'); return; }
    try {
      await api('/stock/movements', { method: 'POST', body: { branchId, kind, lines, toBranchId: f.get('to') ? +f.get('to') : null, note: f.get('note') || '' } });
      d.close(); toast('Uloženo'); done();
    } catch (err) { toast(err.message); }
  };
  d.showModal();
}

// ---------- Branches & tills ----------
pages.branches = async (main) => {
  const [bl, devices] = await Promise.all([branches(), api('/devices')]);
  main.innerHTML = `<h1>Provozovny a pokladny</h1><div class="toolbar"><button class="primary" id="nb">Nová provozovna</button><button id="nd">Nová pokladna</button></div>
    <table><tr><th>Provozovna</th><th>Adresa</th><th>Ev. jednotka EET</th><th>Pokladny</th></tr>
    ${bl.map((b) => `<tr class="clickable" data-b="${b.id}"><td>${esc(b.name)}</td><td>${esc(b.address)}</td><td>${b.eetIdJednotky ?? '<span class="badge warn">chybí</span>'}</td>
      <td>${devices.filter((d) => d.branchId === b.id).length}</td></tr>`).join('')}</table>
    <h2>Pokladny</h2><table><tr><th>Název</th><th>Provozovna</th><th>Označení EET</th><th>Stav</th><th>Naposledy online</th><th></th></tr>
    ${devices.map((d) => `<tr><td>${esc(d.name)}</td><td>${esc((bl.find((b) => b.id === d.branchId) || {}).name)}</td><td>${esc(d.eetIdPokl)}</td>
      <td>${d.pairingCode ? `Párovací kód <span class="code">${esc(d.pairingCode)}</span>` : d.pairedAt ? '<span class="badge ok">spárováno</span>' : '<span class="badge">nespárováno</span>'}</td>
      <td>${dt(d.lastSeenAt)}</td><td><button data-pair="${d.id}">Nový kód</button></td></tr>`).join('')}</table>
    <p class="muted">Na pokladně otevřete Nastavení → Back office a zadejte adresu serveru a párovací kód (platí 24 hodin).</p><dialog id="dlg"></dialog>`;
  $('#nb').onclick = () => branchDialog(null);
  document.querySelectorAll('[data-b]').forEach((tr) => tr.addEventListener('click', () => branchDialog(bl.find((b) => b.id == tr.dataset.b))));
  $('#nd').onclick = () => deviceDialog(bl);
  document.querySelectorAll('[data-pair]').forEach((btn) => btn.addEventListener('click', async () => {
    const r = await api(`/devices/${btn.dataset.pair}/pair`, { method: 'POST' }); toast('Nový párovací kód: ' + r.pairingCode); route();
  }));
};
function branchDialog(b) {
  const d = $('#dlg'); const isNew = !b; b = b || { name: '', address: '', eetIdJednotky: null, active: true };
  d.innerHTML = `<form><h2>${isNew ? 'Nová provozovna' : 'Provozovna'}</h2><label>Název<input name="name" required value="${esc(b.name)}"></label>
    <label>Adresa<input name="address" value="${esc(b.address)}"></label><label>Označení evidenční jednotky (EET 2.0, přiděleno v DIS+)<input name="eet" type="number" value="${b.eetIdJednotky ?? ''}"></label>
    <div class="actions"><button type="button" id="cancel">Zrušit</button><button class="primary">Uložit</button></div></form>`;
  $('#cancel', d).onclick = () => d.close();
  $('form', d).onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    const body = { name: f.get('name'), address: f.get('address'), eetIdJednotky: f.get('eet') ? +f.get('eet') : null, active: true };
    try { await api(isNew ? '/branches' : '/branches/' + b.id, { method: isNew ? 'POST' : 'PUT', body }); d.close(); route(); } catch (err) { toast(err.message); }
  };
  d.showModal();
}
function deviceDialog(bl) {
  const d = $('#dlg');
  d.innerHTML = `<form><h2>Nová pokladna</h2><label>Název<input name="name" required value="Pokladna ${Date.now() % 1000}"></label>
    <label>Provozovna${branchSelect('branchId', bl, bl[0] && bl[0].id, false)}</label><label>Označení pokladny pro EET (max 20 znaků)<input name="eet" maxlength="20" value="POKL1"></label>
    <div class="actions"><button type="button" id="cancel">Zrušit</button><button class="primary">Vytvořit</button></div></form>`;
  $('#cancel', d).onclick = () => d.close();
  $('form', d).onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    try { const r = await api('/devices', { method: 'POST', body: { name: f.get('name'), branchId: +f.get('branchId'), eetIdPokl: f.get('eet') } }); d.close(); toast('Párovací kód: ' + r.pairingCode); route(); }
    catch (err) { toast(err.message); }
  };
  d.showModal();
}

// ---------- Staff & attendance ----------
pages.staff = async (main) => {
  const users = await api('/users');
  main.innerHTML = `<h1>Zaměstnanci</h1><div class="toolbar"><button class="primary" id="nu">Nový zaměstnanec</button></div>
    <table><tr><th>Jméno</th><th>Role</th><th>E-mail</th><th>PIN na pokladně</th><th>Stav</th></tr>
    ${users.map((u) => `<tr class="clickable" data-u="${u.id}"><td>${esc(u.name)}</td><td>${ROLE[u.role]}</td><td>${esc(u.email || '')}</td><td>${u.hasPin ? 'nastaven' : '–'}</td><td>${u.active ? 'aktivní' : 'neaktivní'}</td></tr>`).join('')}</table>
    <p class="muted">Pokladní se přihlašují na pokladně PINem. Vedoucí a majitel se přihlašují sem e-mailem a heslem. Zaměstnance může spravovat jen majitel.</p><dialog id="dlg"></dialog>`;
  $('#nu').onclick = () => userDialog(null);
  document.querySelectorAll('[data-u]').forEach((tr) => tr.addEventListener('click', () => userDialog(users.find((u) => u.id == tr.dataset.u))));
};
function userDialog(u) {
  const d = $('#dlg'); const isNew = !u; u = u || { name: '', email: '', role: 'CASHIER', active: true };
  d.innerHTML = `<form><h2>${isNew ? 'Nový zaměstnanec' : esc(u.name)}</h2><label>Jméno<input name="name" required value="${esc(u.name)}"></label>
    <label>Role<select name="role">${Object.entries(ROLE).map(([k, v]) => `<option value="${k}" ${k === u.role ? 'selected' : ''}>${v}</option>`).join('')}</select></label>
    <label>E-mail (pro vedoucí a majitele)<input name="email" type="email" value="${esc(u.email || '')}"></label>
    <div class="row"><label>${isNew ? 'Heslo' : 'Nové heslo'}<input name="password" type="password" minlength="8"></label><label>${isNew ? 'PIN' : 'Nový PIN'} (4–8 číslic)<input name="pin" inputmode="numeric" pattern="[0-9]{4,8}"></label></div>
    <label class="check"><input type="checkbox" name="active" ${u.active ? 'checked' : ''}> Aktivní</label>
    <div class="actions"><button type="button" id="cancel">Zrušit</button><button class="primary">Uložit</button></div></form>`;
  $('#cancel', d).onclick = () => d.close();
  $('form', d).onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    const body = { name: f.get('name'), role: f.get('role'), email: f.get('email') || null, password: f.get('password') || null, pin: f.get('pin') || null, active: f.get('active') === 'on' };
    try { await api(isNew ? '/users' : '/users/' + u.id, { method: isNew ? 'POST' : 'PUT', body }); d.close(); route(); } catch (err) { toast(err.message); }
  };
  d.showModal();
}
pages.attendance = async (main) => {
  main.innerHTML = `<h1>Docházka</h1>${rangeToolbar()}<div id="out"></div>`;
  const load = async () => {
    const rows = await api('/attendance?' + qs($('#range')));
    const per = {}; rows.forEach((r) => { per[r.userName] = (per[r.userName] || 0) + (r.minutes || 0); });
    $('#out').innerHTML = `<h2>Odpracováno celkem</h2><table><tr><th>Zaměstnanec</th><th class="num">Hodin</th></tr>${Object.entries(per).map(([n, m]) => `<tr><td>${esc(n)}</td><td class="num">${(m / 60).toFixed(2).replace('.', ',')}</td></tr>`).join('')}</table>
      <h2>Směny</h2><table><tr><th>Zaměstnanec</th><th>Příchod</th><th>Odchod</th><th class="num">Minut</th></tr>${rows.map((r) => `<tr><td>${esc(r.userName)}</td><td>${dt(r.clockIn)}</td><td>${r.clockOut ? dt(r.clockOut) : '<span class="badge warn">v práci</span>'}</td><td class="num">${r.minutes ?? ''}</td></tr>`).join('')}</table>`;
  };
  $('#range').addEventListener('submit', (e) => { e.preventDefault(); load(); });
  await load();
};

// ---------- EET ----------
pages.eet = async (main) => {
  main.innerHTML = `<h1>EET 2.0</h1>${rangeToolbar()}<div id="out"></div>`;
  const load = async () => {
    const r = await api('/eet?' + qs($('#range')));
    $('#out').innerHTML = `<div class="grid"><div class="card kpi"><div class="label">Potvrzeno</div><div class="value">${r.confirmed}</div></div>
      <div class="card kpi"><div class="label">Čeká na odeslání</div><div class="value">${r.pending}</div></div>
      <div class="card kpi"><div class="label">Odmítnuto</div><div class="value">${r.rejected}</div></div>
      <div class="card kpi"><div class="label">Bez EET</div><div class="value">${r.other}</div></div></div>
      <h2>K řešení</h2><table><tr><th>Čas</th><th>Číslo</th><th>Pokladna</th><th>Stav</th><th class="num">Částka</th></tr>
      ${r.problems.map((s) => `<tr><td>${dt(s.createdAt)}</td><td>${esc(s.number)}</td><td>${s.deviceId}</td><td>${eetBadge(s.eetStatus)}</td><td class="num">${kc(s.received)}</td></tr>`).join('')}</table>
      <p class="muted">Tržby odesílá do EET přímo pokladna (podepisuje je pokladním certifikátem). Čekající tržby pokladna odešle sama, jakmile má internet; zákonná lhůta je 48 hodin.</p>`;
  };
  $('#range').addEventListener('submit', (e) => { e.preventDefault(); load(); });
  await load();
};

// ---------- Exports ----------
pages.exports = async (main) => {
  main.innerHTML = `<h1>Exporty pro účetnictví</h1>${rangeToolbar().replace('<button class="primary">Zobrazit</button>', '')}
    <div class="grid"><div class="card"><h2>Tržby (CSV)</h2><p class="muted">Jedna řádka na účtenku, s rozpisem DPH. Pro Excel a většinu účetních programů.</p><button class="primary" data-x="sales.csv">Stáhnout</button></div>
    <div class="card"><h2>Položky (CSV)</h2><p class="muted">Všechny prodané položky.</p><button class="primary" data-x="items.csv">Stáhnout</button></div>
    <div class="card"><h2>POHODA (XML)</h2><p class="muted">Prodejky pro import do POHODY (Soubor → Datová komunikace → XML import/export).</p><button class="primary" data-x="pohoda.xml">Stáhnout</button></div></div>`;
  document.querySelectorAll('[data-x]').forEach((b) => b.addEventListener('click', () => {
    const q = qs($('#range')); download(`/export/${b.dataset.x}?${q}`, 'flos-' + b.dataset.x);
  }));
  $('#range').addEventListener('submit', (e) => e.preventDefault());
};

// ---------- API keys ----------
pages.apikeys = async (main) => {
  const keys = await api('/api-keys');
  main.innerHTML = `<h1>API pro integrace</h1><p class="muted">API klíč umožní e-shopu, účetnímu nebo jinému systému číst data (READ) nebo upravovat produkty a sklad (WRITE).
    Volání: <code>Authorization: Bearer &lt;klíč&gt;</code> na adresy <code>/api/v1/…</code> (produkty, sklad, účtenky, přehledy, exporty).</p>
    <form class="toolbar" id="nk"><label>Název<input name="name" required placeholder="např. E-shop"></label><label>Oprávnění<select name="scope"><option value="READ">Jen čtení</option><option value="WRITE">Čtení i zápis</option></select></label><button class="primary">Vytvořit klíč</button></form>
    <div id="newkey"></div>
    <table><tr><th>Název</th><th>Začátek klíče</th><th>Oprávnění</th><th>Vytvořen</th><th>Naposledy použit</th><th></th></tr>
    ${keys.map((k) => `<tr><td>${esc(k.name)}</td><td><code>${esc(k.prefix)}…</code></td><td>${k.scope}</td><td>${dt(k.createdAt)}</td><td>${dt(k.lastUsedAt)}</td>
      <td>${k.revoked ? '<span class="badge bad">zrušen</span>' : `<button class="danger" data-rev="${k.id}">Zrušit</button>`}</td></tr>`).join('')}</table>`;
  $('#nk').onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    const k = await api('/api-keys', { method: 'POST', body: { name: f.get('name'), scope: f.get('scope') } });
    $('#newkey').innerHTML = `<div class="card"><b>Nový klíč – zkopírujte si ho, znovu se nezobrazí:</b><p class="code" style="word-break:break-all">${esc(k.key)}</p></div>`;
  };
  document.querySelectorAll('[data-rev]').forEach((b) => b.addEventListener('click', async () => { await api('/api-keys/' + b.dataset.rev, { method: 'DELETE' }); route(); }));
};

// ---------- Company ----------
pages.company = async (main) => {
  const o = await api('/org');
  main.innerHTML = `<h1>Firma</h1><form class="card" id="org" style="max-width:560px;display:flex;flex-direction:column;gap:10px">
    <label>Název<input name="name" required value="${esc(o.name)}"></label><div class="row"><label>IČO<input name="ico" value="${esc(o.ico)}"></label><label>DIČ<input name="dic" value="${esc(o.dic)}"></label></div>
    <label>Sídlo<input name="address" value="${esc(o.address)}"></label><label class="check"><input type="checkbox" name="vatPayer" ${o.vatPayer ? 'checked' : ''}> Plátce DPH</label>
    <div class="actions"><button class="primary">Uložit</button></div></form>
    <h2>Všechny firmy</h2><p class="muted">Jeden účet může spravovat více firem s různými DIČ. Každá firma může mít více provozoven a pokladen.</p>
    <div id="companies"></div>`;
  const list = await api('/companies');
  $('#companies').innerHTML = `<table><tr><th>Firma</th><th>IČO</th><th>DIČ</th><th>Role</th><th></th></tr>
    ${list.map((c) => `<tr><td>${esc(c.name)}</td><td>${esc(c.ico)}</td><td>${esc(c.dic)}</td><td>${ROLE[c.role] || c.role}</td>
      <td>${c.current ? '<span class="badge ok">aktuální</span>' : `<button data-sw="${c.id}">Přepnout</button>`}</td></tr>`).join('')}</table>
    ${me.user.role === 'OWNER' ? '<button class="primary" id="add-company">+ Přidat další firmu</button>' : ''}`;
  document.querySelectorAll('[data-sw]').forEach((b) => b.addEventListener('click', async () => {
    me = await api('/companies/' + b.dataset.sw + '/switch', { method: 'POST', body: {} }); Object.keys(cache).forEach((k) => delete cache[k]); await loadCompanies(); route();
  }));
  $('#add-company')?.addEventListener('click', companyDialog);
  $('#org').onsubmit = async (e) => {
    e.preventDefault(); const f = new FormData(e.target);
    try { await api('/org', { method: 'PUT', body: { name: f.get('name'), ico: f.get('ico'), dic: f.get('dic'), address: f.get('address'), vatPayer: f.get('vatPayer') === 'on' } }); toast('Uloženo'); me = await api('/me'); loadCompanies(); }
    catch (err) { toast(err.message); }
  };
};

// ---------- Till settings (the tills with "Nastavení z back office" switched on) ----------
const FEATURES = {
  QTY_BEFORE: 'Množství před produktem', ADDONS: 'Přídavky pro kategorie', NOTES: 'Poznámky pro kuchyň', OPEN_PRICE: 'Cena při prodeji a ruční položka',
  SERVICE_CHARGE: 'Servisní poplatek', STORNO_REASON: 'Důvod storna a záznam', EUR_CASH: 'Hotovost v eurech', BIRTHDAYS: 'Narozeniny zákazníků',
  WAITER_TABLES: 'Stoly podle obsluhy', TABLE_PLAN: 'Plán stolů a přesun účtu', QR_ORDERING: 'Objednávky z QR kódu na stole',
};
// [key, label, type]: t = text, a = more lines, b = yes/no, i = number, or a list of choices [value, label].
const TILL_SETTINGS = [
  ['Firma a účtenka', [
    ['businessName', 'Název na účtence', 't'], ['ico', 'IČO', 't'], ['dic', 'DIČ', 't'], ['vatPayer', 'Plátce DPH', 'b'],
    ['phone', 'Telefon', 't'], ['email', 'E-mail', 't'], ['web', 'Web', 't'], ['registry', 'Zápis v rejstříku', 't'],
    ['openingHours', 'Otevírací doba', 'a'], ['footer', 'Patička účtenky', 't'], ['receiptExtraFooter', 'Další text pod účtenkou', 'a'],
    ['receiptShowCashier', 'Tisknout jméno obsluhy', 'b'], ['receiptItemVat', 'DPH u každé položky', 'b'], ['receiptBigTotal', 'Velký součet', 'b'],
    ['documentTerms', 'Podmínky na nabídkách a zálohových fakturách', 'a'], ['quoteValidDays', 'Platnost nabídky (dny)', 'i'],
    ['bankAccount', 'Bankovní účet pro QR platbu', 't']]],
  ['Provozovna', [['address1', 'Adresa – 1. řádek', 't'], ['address2', 'Adresa – 2. řádek', 't']]],
  ['Tiskárna a obrazovka', [
    ['printerType', 'Druh tiskárny', [['BLUETOOTH', 'Bluetooth / COM port'], ['NETWORK', 'Wi-Fi / síťová'], ['SYSTEM', 'Tiskárna ve Windows']]],
    ['printerHost', 'IP adresa síťové tiskárny', 't'], ['printerPort', 'Port síťové tiskárny', 'i'],
    ['printerAddress', 'Adresa Bluetooth / COM tiskárny', 't'], ['printerName', 'Název tiskárny', 't'],
    ['paperWidth', 'Šířka papíru', [['MM58', '58 mm'], ['MM80', '80 mm']]],
    ['charset', 'Znaková sada', [['ASCII', 'Bez diakritiky'], ['CP852', 'CP852 (Latin 2)'], ['CP1250', 'Windows-1250']]],
    ['autoPrint', 'Tisknout účtenku automaticky', 'b'], ['openDrawer', 'Otevírat pokladní zásuvku', 'b'],
    ['customerDisplay', 'Zákaznický displej', 'b'], ['language', 'Jazyk pokladny', [['cs', 'Čeština'], ['en', 'English']]]]],
  ['EET', [
    ['eetEnabled', 'Evidovat tržby (EET)', 'b'], ['eetEnvironment', 'Prostředí', [['PLAYGROUND', 'Playground (test)'], ['PRODUCTION', 'Produkce']]],
    ['eetVerification', 'Ověřovací režim', 'b'], ['eetEic', 'EIČ (prázdné = DIČ)', 't'], ['eetTimeoutMs', 'Čekat na odpověď (ms)', 'i'],
    ['eetIdJednotky', 'Označení evidenční jednotky', 't'], ['eetIdPokl', 'Označení pokladny', 't']]],
  ['Platby a banka', [
    ['bankSource', 'Potvrzení plateb z banky', [['OFF', 'Vypnuto'], ['EMAIL', 'E-mail od banky'], ['FIO', 'Fio banka API']]],
    ['imapHost', 'Server e-mailu (IMAP)', 't'], ['imapPort', 'Port IMAP', 'i'], ['imapUser', 'Uživatel e-mailu', 't'], ['imapFolder', 'Složka', 't'],
    ['transferDueDays', 'Převod po splatnosti po (dnech)', 'i'], ['tipsOwner', 'Spropitné patří', [['STAFF', 'Zaměstnancům'], ['BUSINESS', 'Provozovně']]],
    ['eurAccount', 'Účet pro platby v EUR', 't'], ['eurRateManual', 'Vlastní kurz EUR pro převody', 't'], ['eurCashRate', 'Vlastní kurz EUR pro hotovost', 't'],
    ['hiddenPayments', 'Skryté způsoby platby (oddělené čárkou)', 't']]],
  ['Ceny a slevy', [
    ['serviceTypes', 'Druhy prodeje', 'a'], ['discountRules', 'Automatické slevy', 'a'], ['maxCashierDiscount', 'Nejvyšší sleva pokladní (%)', 'i'],
    ['serviceChargePercent', 'Servisní poplatek (%)', 'i'], ['serviceChargeVat', 'DPH servisního poplatku (%)', 'i'],
    ['loyaltyCrownsPerPoint', 'Kč za 1 věrnostní bod (0 = vypnuto)', 'i'],
    ['scaleWeightPrefixes', 'Váhové etikety – předpony s hmotností', 't'], ['scalePricePrefixes', 'Váhové etikety – předpony s cenou', 't'],
    ['scalePluDigits', 'Počet číslic PLU', 'i']]],
  ['Stoly a obsluha', [
    ['tables', 'Stoly (oddělené čárkou)', 'a'], ['tablePlan', 'Plán stolů', 'a'], ['waiters', 'Obsluha (oddělená čárkou)', 't'],
    ['waitersAway', 'Obsluha mimo směnu', 'a'], ['waiterTables', 'Stoly podle obsluhy', 'a'], ['staffRoles', 'Role obsluhy', 'a'],
    ['permissions', 'Co role nesmějí', 'a'], ['qrOrderApprove', 'Objednávky ze stolu musí potvrdit obsluha', 'b'],
    ['qrOrderPrint', 'Tisknout objednávky ze stolu do kuchyně', 'b']]],
  ['Kuchyň', [['addons', 'Přídavky pro kategorie', 'a'], ['quickNotes', 'Rychlé poznámky', 'a']]],
  ['Zprávy a menu', [
    ['msgReservation', 'Zpráva k rezervaci', 'a'], ['msgReminder', 'Připomínka rezervace', 'a'], ['msgOrderReady', 'Objednávka je hotová', 'a'],
    ['msgDocument', 'Zpráva k dokladu', 'a'], ['menuUrl', 'Adresa menu', 't'], ['menuNote', 'Poznámka pod menu', 'a']]],
  ['Pokročilé funkce', [['advanced', 'Zapnuté funkce', 'f']]],
];
const SECRETS = { eetCertificate: 'Certifikát EET', eetCertPassword: 'Heslo certifikátu EET', imapPassword: 'Heslo e-mailu', fioToken: 'Token Fio banky' };

function settingField(key, label, type, value) {
  const has = value !== undefined; const v = has ? value : '';
  const hint = has ? '' : ' placeholder="– zatím nepřišlo z pokladny"';
  if (type === 'b') return `<label class="check"><input type="checkbox" data-k="${key}" ${v === 'true' ? 'checked' : ''}> ${esc(label)}</label>`;
  if (type === 'a') return `<label>${esc(label)}<textarea data-k="${key}" rows="3"${hint}>${esc(v)}</textarea></label>`;
  if (type === 'i') return `<label>${esc(label)}<input type="number" data-k="${key}" value="${esc(v)}"${hint}></label>`;
  if (type === 'f') {
    const on = new Set(v.split(',').map((x) => x.trim()).filter(Boolean));
    return `<div data-k="${key}" data-features="1">${Object.entries(FEATURES).map(([f, l]) =>
      `<label class="check"><input type="checkbox" value="${f}" ${on.has(f) ? 'checked' : ''}> ${esc(l)}</label>`).join('')}</div>`;
  }
  if (Array.isArray(type)) return `<label>${esc(label)}<select data-k="${key}">${has ? '' : '<option value="">–</option>'}${type.map(([o, l]) =>
    `<option value="${o}" ${o === v ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select></label>`;
  return `<label>${esc(label)}<input data-k="${key}" value="${esc(v)}"${hint}></label>`;
}
function fieldValue(el, before) {
  if (el.dataset.features) {
    const own = (before || '').split(',').map((x) => x.trim()).filter((x) => x && !(x in FEATURES)); // e.g. switches only a till knows
    return [...own, ...[...el.querySelectorAll('input:checked')].map((i) => i.value)].join(',');
  }
  return el.type === 'checkbox' ? String(el.checked) : el.value;
}

pages.tills = async (main) => {
  const view = await api('/till-settings');
  const deviceKeys = new Set(view.deviceKeys);
  const scopes = [{ id: '', name: 'Celá firma (všechny pokladny)' }, ...view.devices];
  let scope = '';
  const render = () => {
    const dev = view.devices.find((d) => String(d.id) === scope);
    const values = Object.fromEntries(Object.entries(dev ? dev.values : view.company).map(([k, x]) => [k, x.value]));
    const groups = TILL_SETTINGS.map(([title, fields]) => [title, fields.filter(([k]) => deviceKeys.has(k) === !!dev)]).filter(([, f]) => f.length);
    $('#ts').innerHTML = `${dev ? `<p class="muted">Naposledy online: ${dt(dev.lastSeenAt)}</p>
      <div class="card"><h2>Hesla a tokeny</h2><p class="muted">Zůstávají jen v pokladně, tady vidíte jen, jestli jsou nastavené.</p>
      ${Object.entries(SECRETS).map(([k, l]) => `<div>${esc(l)}: ${dev.secrets.includes(k) ? '<span class="badge ok">nastaveno</span>' : '<span class="badge">nenastaveno</span>'}</div>`).join('')}</div>` : ''}
      ${groups.map(([title, fields]) => `<div class="card settings-group"><h2>${esc(title)}</h2>${fields.map(([k, l, t]) => settingField(k, l, t, values[k])).join('')}</div>`).join('')}
      <div class="actions"><button class="primary" id="save-ts">Uložit</button></div>`;
    $('#save-ts').onclick = async () => {
      const changed = {};
      document.querySelectorAll('#ts [data-k]').forEach((el) => {
        const k = el.dataset.k; const v = fieldValue(el, values[k]);
        if (values[k] === undefined ? (v !== '' && v !== 'false') : v !== values[k]) changed[k] = v;
      });
      if (!Object.keys(changed).length) return toast('Nic se nezměnilo');
      try {
        await api('/till-settings', { method: 'PUT', body: { deviceId: dev ? dev.id : null, values: changed } });
        toast('Uloženo. Pokladny si změny stáhnou do minuty.'); route();
      } catch (err) { toast(err.message); }
    };
  };
  main.innerHTML = `<h1>Nastavení pokladen</h1>
    <p class="muted">Nastavení se sem dostane z pokladen, které mají zapnuté Pokročilé nastavení → Nastavení z back office. Změny se do pokladen dostanou do minuty, platí vždy poslední změna (v pokladně nebo tady). Hesla a tokeny zůstávají jen v pokladně.</p>
    <div class="toolbar"><label>Pro<select id="scope">${scopes.map((s) => `<option value="${s.id}">${esc(s.name)}</option>`).join('')}</select></label></div>
    ${view.devices.length ? '' : '<p class="muted">Zatím tu nejsou žádné pokladny. Přidejte je v části Provozovny a pokladny.</p>'}
    <div id="ts"></div>`;
  $('#scope').onchange = (e) => { scope = e.target.value; render(); };
  render();
};

// ---------- Live from the tills (read only; tills with "Přehled pokladny v back office" switched on) ----------
const CASH_KIND = { FLOAT: 'Počáteční stav', IN: 'Vklad', OUT: 'Výběr', CLOSE: 'Uzavření směny' };
const DOC_KIND = { QUOTE: 'Nabídka', PROFORMA: 'Zálohová faktura', SALES_ORDER: 'Objednávka' };
const DOC_STATUS = { OPEN: 'otevřený', DONE: 'vyřízený', CANCELLED: 'zrušený' };
const BOOKING = { BOOKED: 'rezervováno', SEATED: 'usazeni', NO_SHOW: 'nepřišli', CANCELLED: 'zrušeno' };
const TABLE_ORDER = { NEW: 'čeká', FETCHED: 'na pokladně', ACCEPTED: 'přijato', REJECTED: 'odmítnuto' };
const day = (d) => (d == null ? '–' : new Date(d * 86400000).toLocaleDateString(LOCALE));
const LIVE_TABS = [['orders', 'Otevřené stoly'], ['cash', 'Hotovost a směny'], ['customers', 'Zákazníci'], ['reservations', 'Rezervace'],
  ['documents', 'Doklady'], ['audit', 'Záznam'], ['qr', 'Objednávky ze stolů']];

function liveTab(tab, s) {
  const none = '<p class="muted">Nic tu není.</p>';
  if (tab === 'orders') return s.openOrders.length ? s.openOrders.map((o) => `<div class="card live-order"><h3>${o.table ? 'Stůl ' + esc(o.table) : 'Objednávka č. ' + (o.number ?? '')}
      <span class="muted">· ${dt(o.openedAt)}${o.cashier ? ' · ' + esc(o.cashier) : ''}${o.serviceType ? ' · ' + esc(o.serviceType) : ''}</span>
      ${o.kitchenReady ? '<span class="badge ok">hotovo v kuchyni</span>' : ''}<b class="right">${kc(o.total)}</b></h3>
      ${o.note ? `<p class="muted">${esc(o.note)}</p>` : ''}
      <table>${o.items.map((i) => `<tr><td>${qty(i.quantity)}×</td><td>${esc(i.name)}${i.discountPercent ? ` <span class="badge">−${i.discountPercent} %</span>` : ''}</td><td class="num">${kc(i.unitPrice)}</td></tr>`).join('')}</table></div>`).join('') : none;
  if (tab === 'cash') {
    const c = s.cash;
    return `${c ? `<div class="grid">${[['Má být v zásuvce', c.expected], ['Počáteční stav', c.float], ['Hotovost z prodeje', c.sales], ['Vráceno', c.refunds], ['Vklady', c.cashIn], ['Výběry', c.cashOut]]
        .map(([l, v]) => `<div class="card kpi"><div class="label">${l}</div><div class="value">${kc(v)}</div></div>`).join('')}</div>
        <p class="muted">Směna od: ${c.since ? dt(c.since) : 'začátku'}${s.eetWaiting ? ` · <span class="badge warn">EET čeká: ${s.eetWaiting}</span>` : ''}</p>` : ''}
      <h2>Pohyby v této směně</h2>${s.cashMoves.length ? `<table><tr><th>Čas</th><th>Druh</th><th class="num">Částka</th><th>Poznámka</th><th>Kdo</th></tr>
        ${s.cashMoves.map((m) => `<tr><td>${dt(m.at)}</td><td>${CASH_KIND[m.kind] || esc(m.kind)}</td><td class="num">${kc(m.amount)}</td><td>${esc(m.note)}</td><td>${esc(m.cashier)}</td></tr>`).join('')}</table>` : none}
      <h2>Uzavřené směny</h2>${s.shiftCloses.length ? `<table><tr><th>Uzavřeno</th><th class="num">Napočítáno</th><th class="num">Mělo být</th><th class="num">Rozdíl</th><th>Kdo</th></tr>
        ${s.shiftCloses.map((x) => `<tr><td>${dt(x.at)}</td><td class="num">${kc(x.counted)}</td><td class="num">${x.expected == null ? '–' : kc(x.expected)}</td>
          <td class="num">${x.expected == null ? '–' : kc(x.counted - x.expected)}</td><td>${esc(x.cashier)}</td></tr>`).join('')}</table>` : none}`;
  }
  if (tab === 'customers') return s.customers.length ? `<p class="muted">Zákazníků: ${s.customers.length}</p><table><tr><th>Jméno</th><th>Telefon</th><th>E-mail</th><th class="num">Body</th><th class="num">Sleva</th><th>Narozeniny</th><th>Karta</th><th>Poznámka</th></tr>
      ${s.customers.map((x) => `<tr><td>${esc(x.name)}</td><td>${esc(x.phone)}</td><td>${esc(x.email)}</td><td class="num">${x.points}</td><td class="num">${x.discountPercent ? x.discountPercent + ' %' : ''}</td>
        <td>${esc(x.birthday || '')}</td><td>${esc(x.cardCode || '')}</td><td>${esc(x.note)}</td></tr>`).join('')}</table>` : none;
  if (tab === 'reservations') return s.reservations.length ? `<table><tr><th>Kdy</th><th>Jméno</th><th>Telefon</th><th class="num">Hosté</th><th>Stůl</th><th>Stav</th><th>Poznámka</th></tr>
      ${s.reservations.map((r) => `<tr><td>${dt(r.at)}</td><td>${esc(r.name)}</td><td>${esc(r.phone)}</td><td class="num">${r.guests}</td><td>${esc(r.table || '')}</td>
        <td>${BOOKING[r.status] || esc(r.status)}</td><td>${esc(r.note)}</td></tr>`).join('')}</table>` : none;
  if (tab === 'documents') return s.documents.length ? `<table><tr><th>Číslo</th><th>Druh</th><th>Vytvořeno</th><th>Zákazník</th><th class="num">Celkem</th><th>Termín</th><th>Stav</th></tr>
      ${s.documents.map((d) => `<tr><td>${esc(d.number)}</td><td>${DOC_KIND[d.kind] || esc(d.kind)}</td><td>${dt(d.createdAt)}</td><td>${esc(d.customer)}</td><td class="num">${kc(d.total)}</td>
        <td>${day(d.dueDay)}</td><td>${DOC_STATUS[d.status] || esc(d.status)}</td></tr>`).join('')}</table>` : none;
  if (tab === 'audit') return s.audit.length ? `<table><tr><th>Čas</th><th>Co se stalo</th><th>Kdo</th></tr>
      ${s.audit.map((a) => `<tr><td>${dt(a.at)}</td><td>${esc(a.text)}</td><td>${esc(a.author)}</td></tr>`).join('')}</table>` : none;
  return '';
}

async function tableOrdersHtml() {
  const list = await api('/table-orders?days=2');
  if (!list.length) return '<p class="muted">Za poslední dva dny žádné objednávky ze stolů.</p>';
  return `<table><tr><th>Čas</th><th>Stůl</th><th>Položky</th><th>Poznámka</th><th>Stav</th></tr>
    ${list.map((o) => `<tr><td>${dt(o.createdAt)}</td><td>${esc(o.table)}</td>
      <td>${o.lines.map((l) => `${l.qty}× ${esc(l.name)}${l.choices ? ` <span class="muted">(${esc(l.choices)})</span>` : ''}${l.note ? ` – <i>${esc(l.note)}</i>` : ''}`).join('<br>')}</td>
      <td>${esc(o.note)}</td><td>${TABLE_ORDER[o.status] || esc(o.status)}</td></tr>`).join('')}</table>`;
}

pages.live = async (main) => {
  const devices = await api('/till-live');
  const withData = devices.filter((d) => d.snapshot);
  let dev = withData[0] ? String(withData[0].id) : '';
  let tab = 'orders';
  main.innerHTML = `<h1>Živě z pokladen</h1>
    <p class="muted">Co se děje na pokladnách, které mají zapnuté Pokročilé nastavení → Přehled pokladny v back office. Pokladny to posílají každou minutu, tady se to jen čte.</p>
    <div class="toolbar"><label>Pokladna<select id="live-dev">${devices.map((d) => `<option value="${d.id}" ${String(d.id) === dev ? 'selected' : ''}>${esc(d.name)}${d.snapshot ? '' : ' (nic neposlala)'}</option>`).join('')}</select></label>
    <button id="live-refresh">Obnovit</button></div>
    <div class="tabs">${LIVE_TABS.map(([k, l]) => `<button data-tab="${k}">${l}</button>`).join('')}</div><div id="live"></div>`;
  const show = async () => {
    document.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('primary', b.dataset.tab === tab));
    const box = $('#live');
    if (tab === 'qr') { box.innerHTML = '<p class="muted">Načítám…</p>'; box.innerHTML = await tableOrdersHtml(); return; }
    const d = devices.find((x) => String(x.id) === dev);
    if (!d || !d.snapshot) { box.innerHTML = '<p class="muted">Tato pokladna zatím nic neposlala. Zapněte na ní Pokročilé nastavení → Přehled pokladny v back office.</p>'; return; }
    box.innerHTML = `<p class="muted">Stav z ${dt(d.snapshot.takenAt)}</p>` + liveTab(tab, d.snapshot);
  };
  $('#live-dev').onchange = (e) => { dev = e.target.value; show(); };
  $('#live-refresh').onclick = () => route();
  document.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => { tab = b.dataset.tab; show().catch((e) => toast(e.message)); }));
  await show();
};

// ---- App updates: new Android builds for the tills ----
const mb = (n) => (n / 1048576).toFixed(1) + ' MB';

pages.updates = async (main) => {
  const v = await api('/app-releases');
  const newest = v.releases.find((r) => r.active);
  main.innerHTML = `<h1>Aktualizace</h1>
    <p class="muted">Nahrajte novou verzi aplikace pro Android (soubor flos.apk). Pokladny, které mají zapnuté Pokročilé nastavení → Aktualizace z back office, ji nabídnou v Nastavení → Back office a nainstaluje se přes původní aplikaci, data zůstanou. Aplikace z Google Play se aktualizují jen přes Google Play.</p>
    <h2>Pokladny</h2>
    <table><thead><tr><th>Pokladna</th><th>Systém</th><th>Verze</th><th>Naposledy</th><th>Stav</th></tr></thead><tbody>
    ${v.devices.map((d) => `<tr><td>${esc(d.name)}</td><td>${esc(d.platform || '—')}</td><td>${esc(d.versionName || (d.versionCode ? String(d.versionCode) : '—'))}</td><td>${d.lastSeenAt ? dt(d.lastSeenAt) : '—'}</td>
      <td>${!d.versionCode ? '<span class="muted">Neznámá verze</span>' : newest && d.versionCode < newest.versionCode && (!newest.devices.length || newest.devices.includes(d.id)) ? '<span>Čeká aktualizace</span>' : '<span>Aktuální</span>'}</td></tr>`).join('') || '<tr><td colspan="5" class="muted">Žádné pokladny</td></tr>'}
    </tbody></table>
    <h2>Nahrát novou verzi</h2>
    <div class="card"><div class="toolbar">
      <label>Soubor APK<input type="file" id="up-file" accept=".apk,application/vnd.android.package-archive"></label>
      <label>Verze<input id="up-name" placeholder="1.0.123"></label>
    </div>
    <label>Co je nového<textarea id="up-notes" rows="3"></textarea></label>
    <p class="muted">Číslo verze musí být vyšší než u nainstalované aplikace (poslední číslo, např. 123 z 1.0.123). Verzi najdete v pokladně v Nastavení → Back office, nebo v názvu buildu na GitHubu.</p>
    <button class="primary" id="up-send">Nahrát</button> <span id="up-state" class="muted"></span></div>
    <h2>Nahrané verze</h2>
    <table><thead><tr><th>Verze</th><th>Velikost</th><th>Nahráno</th><th>Pro pokladny</th><th>Aktivní</th><th></th></tr></thead><tbody>
    ${v.releases.map((r) => `<tr data-id="${r.id}"><td><b>${esc(r.versionName)}</b><div class="muted">${esc(r.notes)}</div></td><td>${mb(r.size)}</td><td>${dt(r.createdAt)}</td>
      <td>${v.devices.map((d) => `<label class="inline"><input type="checkbox" class="rel-dev" value="${d.id}" ${r.devices.includes(d.id) ? 'checked' : ''}> ${esc(d.name)}</label>`).join(' ')}
        <div class="muted">${r.devices.length ? '' : '<span>Nic nezaškrtnuto = všechny pokladny</span>'}</div></td>
      <td><input type="checkbox" class="rel-active" ${r.active ? 'checked' : ''}></td>
      <td><button class="rel-save">Uložit</button> <button class="rel-del danger">Smazat</button></td></tr>`).join('') || '<tr><td colspan="6" class="muted">Zatím žádná verze</td></tr>'}
    </tbody></table>`;

  $('#up-file').onchange = (e) => {
    // A build from GitHub is often named like flos-1.0.123.apk: take the version from it.
    const f = e.target.files[0]; const m = f && f.name.match(/(\d+\.\d+\.\d+)/);
    if (m && !$('#up-name').value) $('#up-name').value = m[1];
  };
  $('#up-send').onclick = async () => {
    const f = $('#up-file').files[0];
    const name = $('#up-name').value.trim();
    if (!f) return toast(t('Vyberte soubor APK'));
    if (!/\d+$/.test(name)) return toast(t('Zadejte číslo verze (např. 1.0.123)'));
    $('#up-send').disabled = true; $('#up-state').textContent = t('Nahrávám…') + ' ' + mb(f.size);
    try {
      const res = await fetch('/api/v1/app-releases?platform=android&versionName=' + encodeURIComponent(name) + '&notes=' + encodeURIComponent($('#up-notes').value.slice(0, 2000)), {
        method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/octet-stream' }, body: f,
      });
      const text = await res.text(); let data = null; try { data = text ? JSON.parse(text) : null; } catch (e) {}
      if (!res.ok) throw new Error((data && data.error) || (res.status === 413 ? 'Soubor je příliš velký' : 'Chyba ' + res.status));
      toast(t('Verze nahrána')); route();
    } catch (e) { toast(t(e.message)); $('#up-send').disabled = false; $('#up-state').textContent = ''; }
  };
  main.querySelectorAll('tr[data-id]').forEach((row) => {
    const id = row.dataset.id;
    const r = v.releases.find((x) => String(x.id) === id);
    $('.rel-save', row).onclick = async () => {
      const devices = [...row.querySelectorAll('.rel-dev:checked')].map((c) => Number(c.value));
      try { await api('/app-releases/' + id, { method: 'PUT', body: { active: $('.rel-active', row).checked, devices, notes: r.notes } }); toast(t('Uloženo')); route(); } catch (e) { toast(t(e.message)); }
    };
    $('.rel-del', row).onclick = async () => {
      if (!confirm(t('Smazat tuto verzi?'))) return;
      try { await api('/app-releases/' + id, { method: 'DELETE' }); route(); } catch (e) { toast(t(e.message)); }
    };
  });
};

start();
