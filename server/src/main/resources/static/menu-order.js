// Ordering from the table on the Flos QR menu. The page carries the table, its signed key and the
// dishes in <script id="flos-order">; this script keeps a small cart and sends the order to the
// back office, which hands it to the till. All text is set with textContent, never as HTML.
(function () {
  "use strict";
  var dataEl = document.getElementById("flos-order");
  if (!dataEl) return;
  var D = JSON.parse(dataEl.textContent);
  var cs = /^(cs|sk)/i.test(navigator.language || "");
  var T = cs ? {
    add: "Přidat", items: "položek", order: "Objednávka", send: "Odeslat objednávku", note: "Poznámka pro kuchyň (nepovinné)",
    orderNote: "Poznámka k objednávce (nepovinné)", empty: "Zatím nic nevybráno.", sent: "Objednávka odeslána. Obsluha ji za chvíli potvrdí.",
    accepted: "Obsluha objednávku přijala. Dobrou chuť!", rejected: "Obsluha objednávku nepřijala, zeptejte se prosím u obsluhy.",
    fail: "Objednávku se nepodařilo odeslat: ", close: "Zavřít", more: "Objednat další", any: "(libovolně)", total: "Celkem", sending: "Odesílám…"
  } : {
    add: "Add", items: "items", order: "Your order", send: "Send order", note: "Note for the kitchen (optional)",
    orderNote: "Note for the order (optional)", empty: "Nothing chosen yet.", sent: "Order sent. The staff will confirm it shortly.",
    accepted: "The staff accepted your order. Enjoy!", rejected: "The staff did not accept the order, please ask at the counter.",
    fail: "The order could not be sent: ", close: "Close", more: "Order more", any: "(any)", total: "Total", sending: "Sending…"
  };
  var store = "flos-cart-" + D.slug + "-" + D.table;
  var cart = [];
  try { cart = JSON.parse(sessionStorage.getItem(store) || "[]") || []; } catch (e) { cart = []; }
  cart = cart.filter(function (l) { return D.items[l.id]; });

  function save() { try { sessionStorage.setItem(store, JSON.stringify(cart)); } catch (e) {} }
  function money(h) { return (h / 100).toLocaleString("cs-CZ", { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + " Kč"; }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function unit(l) {
    var it = D.items[l.id], p = it.p;
    l.choices.forEach(function (c, gi) { c.forEach(function (ci) { p += it.g[gi].c[ci].p; }); });
    return Math.max(0, p);
  }
  function label(l) {
    var it = D.items[l.id], picked = [];
    l.choices.forEach(function (c, gi) { c.slice().sort().forEach(function (ci) { picked.push(it.g[gi].c[ci].n); }); });
    return it.n + (picked.length ? " (" + picked.join(", ") + ")" : "");
  }
  function add(line) {
    var same = cart.filter(function (l) { return l.id === line.id && JSON.stringify(l.choices) === JSON.stringify(line.choices) && l.note === line.note; })[0];
    if (same) same.qty = Math.min(30, same.qty + line.qty); else cart.push(line);
    save(); bar();
  }

  var cartEl = document.getElementById("cart"), sheet = document.getElementById("sheet");
  function bar() {
    var n = 0, sum = 0;
    cart.forEach(function (l) { n += l.qty; sum += l.qty * unit(l); });
    cartEl.textContent = "";
    if (!n) { cartEl.hidden = true; return; }
    cartEl.appendChild(el("span", "sum", n + " " + T.items + " · " + money(sum)));
    var b = el("button", null, T.order); b.type = "button"; b.onclick = review; cartEl.appendChild(b);
    cartEl.hidden = false;
  }
  function openSheet(build) {
    sheet.textContent = "";
    var box = el("div", "sheet"); build(box); sheet.appendChild(box); sheet.hidden = false;
    sheet.onclick = function (e) { if (e.target === sheet) closeSheet(); };
  }
  function closeSheet() { sheet.hidden = true; sheet.textContent = ""; }

  // A dish with variants or extras: choose, set how many, add a note.
  function pick(id) {
    var it = D.items[id];
    if (!it.g.length) { add({ id: Number(id), qty: 1, choices: [], note: "" }); return; }
    var qty = 1;
    openSheet(function (box) {
      box.appendChild(el("h3", null, it.n));
      var inputs = it.g.map(function (g, gi) {
        box.appendChild(el("div", "g", g.n + (g.m ? " " + T.any : "")));
        return g.c.map(function (c, ci) {
          var lab = el("label"), inp = document.createElement("input");
          inp.type = g.m ? "checkbox" : "radio"; inp.name = "g" + gi; inp.checked = !g.m && ci === 0;
          lab.appendChild(inp); lab.appendChild(el("span", null, c.n + (c.p ? "  " + (c.p > 0 ? "+" : "") + money(c.p) : "")));
          box.appendChild(lab); return inp;
        });
      });
      var note = document.createElement("input"); note.type = "text"; note.maxLength = 140; note.placeholder = T.note;
      var nw = el("div", null); nw.style.marginTop = "14px"; nw.appendChild(note); box.appendChild(nw);
      var row = el("div", "row"), q = el("span", "qty"), minus = el("button", null, "−"), num = el("strong", null, "1"), plus = el("button", null, "+");
      minus.type = plus.type = "button";
      minus.onclick = function () { qty = Math.max(1, qty - 1); num.textContent = qty; };
      plus.onclick = function () { qty = Math.min(30, qty + 1); num.textContent = qty; };
      q.appendChild(minus); q.appendChild(document.createTextNode(" ")); q.appendChild(num); q.appendChild(document.createTextNode(" ")); q.appendChild(plus);
      var ok = el("button", "main", T.add); ok.type = "button";
      ok.onclick = function () {
        var choices = inputs.map(function (g) { var c = []; g.forEach(function (inp, ci) { if (inp.checked) c.push(ci); }); return c; });
        add({ id: Number(id), qty: qty, choices: choices, note: note.value.trim().slice(0, 140) });
        closeSheet();
      };
      row.appendChild(q); row.appendChild(ok); box.appendChild(row);
    });
  }

  // The cart: remove lines, add a note, send.
  function review() {
    openSheet(function (box) {
      box.appendChild(el("h3", null, T.order + " · " + document.getElementById("table-banner").textContent));
      if (!cart.length) box.appendChild(el("p", null, T.empty));
      var sum = 0;
      cart.forEach(function (l, i) {
        var row = el("div", "line"), n = el("span", "n", l.qty + "× " + label(l) + (l.note ? " · " + l.note : ""));
        sum += l.qty * unit(l);
        var x = el("button", "x", "✕"); x.type = "button"; x.setAttribute("aria-label", "remove");
        x.onclick = function () { cart.splice(i, 1); save(); bar(); review(); };
        row.appendChild(n); row.appendChild(el("span", null, money(l.qty * unit(l)))); row.appendChild(x); box.appendChild(row);
      });
      box.appendChild(el("div", "row", T.total + ": " + money(sum)));
      var note = document.createElement("textarea"); note.rows = 2; note.maxLength = 140; note.placeholder = T.orderNote;
      var nw = el("div", null); nw.style.marginTop = "12px"; nw.appendChild(note); box.appendChild(nw);
      var msg = el("div", "msg"); msg.hidden = true;
      var row = el("div", "row"), close = el("button", "link", T.close), send = el("button", "main", T.send);
      close.type = send.type = "button"; close.onclick = closeSheet; send.disabled = !cart.length;
      send.onclick = function () {
        send.disabled = true; send.textContent = T.sending;
        var body = { table: D.table, key: D.key, note: note.value.trim().slice(0, 140),
          lines: cart.map(function (l) { return { id: l.id, qty: l.qty, choices: l.choices, note: l.note }; }) };
        fetch("/menu/" + encodeURIComponent(D.slug) + "/order", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) })
          .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
          .then(function (res) {
            if (!res.ok) throw new Error(res.j && res.j.error || "HTTP");
            cart = []; save(); bar(); done(res.j.id);
          })
          .catch(function (e) { msg.textContent = T.fail + e.message; msg.hidden = false; send.disabled = false; send.textContent = T.send; });
      };
      row.appendChild(close); row.appendChild(send); box.appendChild(row); box.appendChild(msg);
    });
  }

  // After sending: say so, then show when the staff accepts or declines it.
  function done(id) {
    var status;
    openSheet(function (box) {
      status = el("p", null, T.sent); box.appendChild(status);
      var again = el("button", "main", T.more); again.type = "button"; again.onclick = closeSheet; box.appendChild(again);
    });
    var tries = 0;
    var timer = setInterval(function () {
      if (++tries > 60) { clearInterval(timer); return; }
      fetch("/menu/" + encodeURIComponent(D.slug) + "/order/" + id + "?t=" + encodeURIComponent(D.table) + "&k=" + encodeURIComponent(D.key))
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          if (!j) return;
          if (j.status === "ACCEPTED") { status.textContent = T.accepted; clearInterval(timer); }
          if (j.status === "REJECTED") { status.textContent = T.rejected; clearInterval(timer); }
        })
        .catch(function () {});
    }, 5000);
  }

  Array.prototype.forEach.call(document.querySelectorAll("button.add"), function (b) {
    b.addEventListener("click", function () { pick(b.getAttribute("data-id")); });
  });
  bar();
})();
