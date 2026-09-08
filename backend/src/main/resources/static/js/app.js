// app.js — Order Up: a small hash-routed SPA for the Smart Office Breakfast API.

const viewRoot = document.getElementById("view-root");
const headerRight = document.getElementById("header-right");
const bottomNav = document.getElementById("bottom-nav");
const modalRoot = document.getElementById("modal-root");
const toastEl = document.getElementById("toast");

let activeTimers = []; // interval ids to clear between route renders

/* ------------------------------------------------------------------ *
 * Small helpers
 * ------------------------------------------------------------------ */

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str == null ? "" : String(str);
  // textContent/innerHTML escapes & < >, but leaves quotes alone. Item names
  // are user-supplied and now travel through HTML attributes (data-item-name
  // on the receipt price fields), where a bare quote would break out of the
  // attribute — so escape those too.
  return div.innerHTML.replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function money(n) {
  const v = Number(n || 0);
  return "$" + v.toFixed(2);
}

// The room lifecycle as the UI names it. The two middle states are admin
// waypoints in the post-delivery workflow, so they are labelled by what is
// still owed rather than by the raw enum name.
const ROOM_STATUS = {
  OPEN: { label: "Open", cls: "badge-open" },
  CLOSED: { label: "Awaiting receipt", cls: "badge-closed" },
  PENDING_ADMIN_APPROVAL: { label: "Needs approval", cls: "badge-review" },
  APPROVED_AND_CLOSED: { label: "Approved", cls: "badge-approved" },
};

function statusMeta(status) {
  return ROOM_STATUS[status] || { label: String(status || "-"), cls: "badge-closed" };
}

// What a non-admin should understand is happening to their order right now.
function roomStateHint(room) {
  switch (room.status) {
    case "CLOSED":
      return "ordering closed — waiting for the receipt";
    case "PENDING_ADMIN_APPROVAL":
      return "receipt entered — awaiting admin approval";
    case "APPROVED_AND_CLOSED":
      return "approved — this is what you owe";
    default:
      return "this room is closed";
  }
}

function formatCountdown(totalSeconds) {
  const s = Math.max(0, Math.floor(totalSeconds));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return String(m).padStart(2, "0") + ":" + String(r).padStart(2, "0");
}

function toast(message, isError) {
  toastEl.textContent = message;
  toastEl.className = isError ? "show error" : "show";
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (toastEl.className = ""), 3200);
}

function clearTimers() {
  // activeTimers may hold either setInterval ids (numbers) or cleanup
  // objects of the shape { remove() {...} } for DOM elements (e.g. the FAB)
  // that need to be torn down between route renders.
  activeTimers.forEach((entry) => {
    if (typeof entry === "number") clearInterval(entry);
    else if (entry && typeof entry.remove === "function") entry.remove();
  });
  activeTimers = [];
}

function closeModal() {
  modalRoot.innerHTML = "";
}

function openModal(innerHtml, onMount) {
  modalRoot.innerHTML = `
    <div class="modal-backdrop" id="modal-backdrop">
      <div class="modal-sheet">${innerHtml}</div>
    </div>`;
  document.getElementById("modal-backdrop").addEventListener("click", (e) => {
    if (e.target.id === "modal-backdrop") closeModal();
  });
  if (onMount) onMount();
}

function requireAuthOrRedirect() {
  const session = Auth.get();
  if (!session) {
    navigate("#/auth");
    return null;
  }
  return session;
}

function navigate(hash) {
  if (location.hash === hash) {
    render();
  } else {
    location.hash = hash;
  }
}

/* ------------------------------------------------------------------ *
 * Header / nav chrome
 * ------------------------------------------------------------------ */

function renderChrome() {
  const session = Auth.get();

  if (!session) {
    headerRight.innerHTML = "";
    bottomNav.classList.add("hidden");
    return;
  }

  headerRight.innerHTML = `
    <span>${escapeHtml(session.name)}${session.role === "ADMIN" ? " · Admin" : ""}</span>
    <button class="icon-btn" id="logout-btn">Log out</button>
  `;
  document.getElementById("logout-btn").addEventListener("click", () => {
    Auth.clear();
    navigate("#/auth");
  });

  bottomNav.classList.remove("hidden");
  const path = location.hash.split("/")[1] || "dashboard";
  bottomNav.querySelectorAll("button").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.name === path);
  });
}

bottomNav.querySelectorAll("button").forEach((btn) => {
  btn.addEventListener("click", () => navigate(btn.dataset.route));
});

/* ------------------------------------------------------------------ *
 * Router
 * ------------------------------------------------------------------ */

async function render() {
  clearTimers();
  closeModal();
  renderChrome();

  const hash = location.hash || "#/dashboard";
  const session = Auth.get();

  if (hash.startsWith("#/auth")) {
    if (session) return navigate("#/dashboard");
    return renderAuthView();
  }

  if (!requireAuthOrRedirect()) return;

  const roomSummaryMatch = hash.match(/^#\/room\/(\d+)\/summary$/);
  const roomMatch = hash.match(/^#\/room\/(\d+)$/);

  if (hash.startsWith("#/dashboard")) return renderDashboardView();
  if (hash.startsWith("#/account")) return renderAccountView();
  if (roomSummaryMatch) return renderAdminSummaryView(Number(roomSummaryMatch[1]));
  if (roomMatch) return renderRoomView(Number(roomMatch[1]));

  return navigate("#/dashboard");
}

window.addEventListener("hashchange", render);
window.addEventListener("DOMContentLoaded", render);

/* ------------------------------------------------------------------ *
 * Auth view — Registration Page + Login Page (spec 4.A)
 * ------------------------------------------------------------------ */

function renderAuthView() {
  viewRoot.innerHTML = `
    <div class="auth-wrap">
      <div class="auth-hero">
        <div class="display">Breakfast, sorted<span style="color:var(--butter-500)">.</span></div>
        <p>Join a room, order what you want, split the bill down to the cent.</p>
      </div>

      <div class="tab-switch" role="tablist">
        <button data-tab="login" class="active">Log in</button>
        <button data-tab="signup">Sign up</button>
      </div>

      <div class="ticket" style="width:100%;max-width:360px">
        <form id="login-form" class="auth-form">
          <div class="field">
            <label for="login-phone">Phone number</label>
            <input id="login-phone" name="phone" type="tel" autocomplete="tel" required />
          </div>
          <div class="field">
            <label for="login-password">Password</label>
            <input id="login-password" name="password" type="password" autocomplete="current-password" required />
          </div>
          <div class="error-text" id="login-error" style="display:none"></div>
          <button class="btn btn-primary btn-block" type="submit">Log in</button>
        </form>

        <form id="signup-form" class="auth-form" style="display:none">
          <div class="field">
            <label for="signup-name">Full name</label>
            <input id="signup-name" name="name" type="text" autocomplete="name" required />
          </div>
          <div class="field">
            <label for="signup-phone">Phone number</label>
            <input id="signup-phone" name="phone" type="tel" autocomplete="tel" required />
          </div>
          <div class="field">
            <label for="signup-password">Password</label>
            <input id="signup-password" name="password" type="password" minlength="6" autocomplete="new-password" required />
            <div class="hint">At least 6 characters.</div>
          </div>
          <div class="error-text" id="signup-error" style="display:none"></div>
          <button class="btn btn-primary btn-block" type="submit">Create account</button>
        </form>
      </div>
    </div>
  `;

  const loginForm = document.getElementById("login-form");
  const signupForm = document.getElementById("signup-form");
  const tabs = document.querySelectorAll(".tab-switch button");

  tabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      tabs.forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      const isLogin = tab.dataset.tab === "login";
      loginForm.style.display = isLogin ? "block" : "none";
      signupForm.style.display = isLogin ? "none" : "block";
    });
  });

  loginForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const errEl = document.getElementById("login-error");
    errEl.style.display = "none";
    const submitBtn = loginForm.querySelector("button[type=submit]");
    submitBtn.disabled = true;
    try {
      const payload = {
        phone: loginForm.phone.value.trim(),
        password: loginForm.password.value,
      };
      const res = await Api.login(payload);
      Auth.save(res);
      toast(`Welcome back, ${res.name.split(" ")[0]}.`);
      navigate("#/dashboard");
    } catch (err) {
      errEl.textContent = err.message;
      errEl.style.display = "block";
    } finally {
      submitBtn.disabled = false;
    }
  });

  signupForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const errEl = document.getElementById("signup-error");
    errEl.style.display = "none";
    const submitBtn = signupForm.querySelector("button[type=submit]");
    submitBtn.disabled = true;
    try {
      const payload = {
        name: signupForm.name.value.trim(),
        phone: signupForm.phone.value.trim(),
        password: signupForm.password.value,
      };
      const res = await Api.register(payload);
      Auth.save(res);
      toast(
          res.role === "ADMIN"
              ? "Account created — you're the first user, so you're an admin."
              : `Welcome, ${res.name.split(" ")[0]}.`
      );
      navigate("#/dashboard");
    } catch (err) {
      errEl.textContent = err.fieldErrors
          ? Object.values(err.fieldErrors).join(" ")
          : err.message;
      errEl.style.display = "block";
    } finally {
      submitBtn.disabled = false;
    }
  });
}

/* ------------------------------------------------------------------ *
 * Account view
 * ------------------------------------------------------------------ */

function renderAccountView() {
  const session = Auth.get();
  viewRoot.innerHTML = `
    <div class="section-title">Account</div>
    <div class="ticket">
      <div class="ticket-title display">${escapeHtml(session.name)}</div>
      <div class="ticket-sub">${escapeHtml(session.phone)}</div>
      <div class="ticket-sub" style="margin-top:4px">
        <span class="badge ${session.role === "ADMIN" ? "badge-open" : "badge-closed"}">${session.role}</span>
      </div>
      ${
      session.role === "ADMIN"
          ? `<button class="btn btn-primary btn-block" id="manage-restaurants-btn" style="margin-top:10px">Manage restaurants</button>
             <button class="btn btn-primary btn-block" id="manage-users-btn" style="margin-top:10px">Manage users &amp; admins</button>`
          : ""
  }
      <button class="btn btn-outline btn-block" id="account-logout" style="margin-top:10px">Log out</button>
    </div>
    ${
      session.role === "ADMIN"
          ? `<div class="hint" style="padding:0 4px">As an admin, you can create rooms, close them early, view the live order summary, calculate the final bill, manage restaurant menus directly, and promote other users to admin.</div>`
          : ""
  }
    <div class="section-title" style="margin-top:22px">My rooms &amp; bills</div>
    <div id="my-rooms-section"><div class="spinner"></div></div>
  `;
  document.getElementById("account-logout").addEventListener("click", () => {
    Auth.clear();
    navigate("#/auth");
  });
  if (session.role === "ADMIN") {
    document.getElementById("manage-restaurants-btn").addEventListener("click", openManageRestaurantsModal);
    document.getElementById("manage-users-btn").addEventListener("click", openManageUsersModal);
  }
  renderMyRoomsSection();
}

/**
 * "My rooms & bills": every room this user has actually placed an order in,
 * most recent first (GET /api/rooms/mine) — not every room in the system,
 * and not OPEN-only. This is what lets a user get back to a room's split or
 * final bill (renderRoomView already fetches Api.getMyBill for
 * PENDING_ADMIN_APPROVAL / APPROVED_AND_CLOSED rooms) once it's no longer
 * OPEN and has disappeared off the shared dashboard — scoped to their own
 * orders only, so nobody sees anyone else's room history here.
 */
async function renderMyRoomsSection() {
  const container = document.getElementById("my-rooms-section");
  if (!container) return;

  let rooms;
  try {
    rooms = await Api.listMyRooms();
  } catch (err) {
    container.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  if (!rooms.length) {
    container.innerHTML = `
      <div class="empty-state">
        <span class="glyph">🧾</span>
        <div class="display">No rooms yet</div>
        <div>Rooms you place an order in will show up here, even after they close.</div>
      </div>`;
    return;
  }

  container.innerHTML = rooms.map(roomCardHtml).join("");

  container.querySelectorAll("[data-open-room]").forEach((btn) => {
    btn.addEventListener("click", () => navigate(`#/room/${btn.dataset.openRoom}`));
  });
  container.querySelectorAll(".room-card").forEach((card) => {
    const go = () => navigate(`#/room/${card.dataset.roomId}`);
    card.addEventListener("click", (e) => {
      if (e.target.closest("[data-open-room]")) return; // avoid double-navigate
      go();
    });
    card.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        go();
      }
    });
  });
}

/* ------------------------------------------------------------------ *
 * Admin: user role management (promote a regular user to admin, or
 * demote an admin back to a regular user). Backend endpoints already
 * existed (/api/admin/users/**) but had no frontend surface at all.
 * ------------------------------------------------------------------ */

async function openManageUsersModal() {
  openModal(`<h2 class="display">Users &amp; admins</h2><div id="users-body"><div class="spinner"></div></div>`, async () => {
    await renderUsersPanel();
  });
}

async function renderUsersPanel() {
  const body = document.getElementById("users-body");
  const session = Auth.get();
  let users;
  try {
    users = await Api.listUsers();
  } catch (err) {
    body.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  body.innerHTML = `
    <div class="hint" style="margin-bottom:12px">
      Promote a regular user to admin so they can create rooms and approve receipts too.
    </div>
    <div class="error-text" id="users-error" style="display:none"></div>
    ${users
      .map((u) => {
        const isSelf = u.id === session.userId;
        const isAdmin = u.role === "ADMIN";
        return `
      <div class="ticket" style="margin-bottom:8px;display:flex;align-items:center;justify-content:space-between;gap:10px">
        <div>
          <div class="ticket-title" style="font-size:15px">${escapeHtml(u.name)}${isSelf ? " (you)" : ""}</div>
          <div class="ticket-sub">${escapeHtml(u.phone)}</div>
        </div>
        <div style="display:flex;align-items:center;gap:8px">
          <span class="badge ${isAdmin ? "badge-open" : "badge-closed"}">${u.role}</span>
          ${
            isAdmin
                ? `<button class="btn btn-outline btn-sm" data-demote="${u.id}" ${isSelf ? "disabled title=\"You can't demote yourself\"" : ""}>Remove admin</button>`
                : `<button class="btn btn-primary btn-sm" data-promote="${u.id}">Make admin</button>`
        }
        </div>
      </div>`;
      })
      .join("")}

    <div class="ticket" style="margin-top:18px;border-color:var(--danger, #c0392b)">
      <div class="ticket-title" style="font-size:15px">Danger zone</div>
      <div class="ticket-sub" style="margin:4px 0 10px">
        Promote every other user to ADMIN in one go. This can't be undone with a single click afterwards — you'd have to demote each account one by one.
      </div>
      <button class="btn btn-danger btn-block" id="make-all-admin-btn">Make ALL users admin</button>
      <div id="make-all-admin-confirm" style="display:none;margin-top:12px">
        <div class="hint" style="margin-bottom:8px">
          This upgrades <strong>every user except you</strong> to ADMIN, immediately, for the whole app. Type <strong>CONFIRM</strong> below to proceed.
        </div>
        <input id="make-all-admin-input" type="text" placeholder="Type CONFIRM" autocomplete="off" style="margin-bottom:10px" />
        <div style="display:flex;gap:8px">
          <button class="btn btn-danger" id="make-all-admin-submit" disabled style="flex:1">Confirm &amp; promote all</button>
          <button class="btn btn-outline" id="make-all-admin-cancel" style="flex:1">Cancel</button>
        </div>
      </div>
    </div>
  `;

  const errEl = document.getElementById("users-error");
  const showErr = (msg) => {
    errEl.textContent = msg;
    errEl.style.display = "block";
  };

  body.querySelectorAll("[data-promote]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      errEl.style.display = "none";
      try {
        await Api.promoteUser(btn.dataset.promote);
        toast("User promoted to admin.");
        renderUsersPanel();
      } catch (err) {
        showErr(err.message);
      }
    });
  });

  body.querySelectorAll("[data-demote]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      errEl.style.display = "none";
      try {
        await Api.demoteUser(btn.dataset.demote);
        toast("Admin access removed.");
        renderUsersPanel();
      } catch (err) {
        showErr(err.message);
      }
    });
  });

  // "Make ALL users admin" — deliberately friction-heavy: reveal an inline
  // confirm panel first, and keep the final button disabled until the admin
  // has typed the exact word CONFIRM, so a stray click can't fire this.
  const makeAllBtn = document.getElementById("make-all-admin-btn");
  const confirmPanel = document.getElementById("make-all-admin-confirm");
  const confirmInput = document.getElementById("make-all-admin-input");
  const confirmSubmit = document.getElementById("make-all-admin-submit");
  const confirmCancel = document.getElementById("make-all-admin-cancel");

  makeAllBtn.addEventListener("click", () => {
    confirmPanel.style.display = "block";
    makeAllBtn.style.display = "none";
    confirmInput.value = "";
    confirmSubmit.disabled = true;
    confirmInput.focus();
  });

  confirmCancel.addEventListener("click", () => {
    confirmPanel.style.display = "none";
    makeAllBtn.style.display = "block";
  });

  confirmInput.addEventListener("input", () => {
    confirmSubmit.disabled = confirmInput.value.trim() !== "CONFIRM";
  });

  confirmSubmit.addEventListener("click", async () => {
    errEl.style.display = "none";
    confirmSubmit.disabled = true;
    confirmSubmit.textContent = "Promoting…";
    try {
      const result = await Api.makeAllUsersAdmin(confirmInput.value.trim());
      toast(result.message || `${result.promotedCount} user(s) promoted to admin.`);
      renderUsersPanel();
    } catch (err) {
      showErr(err.message);
      confirmSubmit.disabled = false;
      confirmSubmit.textContent = "Confirm & promote all";
    }
  });
}

/* ------------------------------------------------------------------ *
 * Admin: manual restaurant/menu management (outside the receipt-
 * approval workflow). Fills the previously-missing UI for the
 * backend's /api/admin/restaurants endpoints.
 * ------------------------------------------------------------------ */

async function openManageRestaurantsModal() {
  openModal(`<h2 class="display">Restaurants</h2><div id="mgmt-body"><div class="spinner"></div></div>`, async () => {
    await renderRestaurantListPanel();
  });
}

async function renderRestaurantListPanel() {
  const body = document.getElementById("mgmt-body");
  let restaurants;
  try {
    restaurants = await Api.listRestaurants();
  } catch (err) {
    body.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  body.innerHTML = `
    <div class="hint" style="margin-bottom:10px">Add a new restaurant with a starting menu, or open one below to add items or fix prices.</div>
    <button class="btn btn-outline btn-block" id="mgmt-new-restaurant" style="margin-bottom:14px">+ New restaurant</button>
    ${
      restaurants.length === 0
          ? `<div class="hint">No restaurants yet.</div>`
          : restaurants
              .map(
                  (r) => `
        <div class="ticket" style="margin-bottom:10px;cursor:pointer" data-restaurant-id="${r.id}">
          <div class="ticket-title display">${escapeHtml(r.name)}</div>
          <div class="ticket-sub">${r.menuItemCount} menu item${r.menuItemCount === 1 ? "" : "s"}${r.phone ? " · " + escapeHtml(r.phone) : ""}</div>
        </div>`
              )
              .join("")
  }
  `;

  document.getElementById("mgmt-new-restaurant").addEventListener("click", renderNewRestaurantPanel);
  body.querySelectorAll("[data-restaurant-id]").forEach((el) => {
    el.addEventListener("click", () => renderRestaurantDetailPanel(Number(el.dataset.restaurantId)));
  });
}

function renderNewRestaurantPanel() {
  const body = document.getElementById("mgmt-body");
  body.innerHTML = `
    <button class="btn btn-outline btn-sm" id="mgmt-back" style="margin-bottom:12px">&larr; Back</button>
    <form id="new-restaurant-form">
      <div class="field">
        <label for="nr-name">Restaurant name</label>
        <input id="nr-name" name="name" type="text" required />
      </div>
      <div class="field">
        <label for="nr-phone">Phone (optional)</label>
        <input id="nr-phone" name="phone" type="tel" />
      </div>
      <div class="hint" style="margin-bottom:10px">Starting menu — at least one item.</div>
      <div id="nr-menu-rows"></div>
      <button class="btn btn-outline btn-sm" type="button" id="nr-add-row" style="margin-bottom:14px">+ Add item</button>
      <div class="error-text" id="nr-error" style="display:none"></div>
      <button class="btn btn-primary btn-block" type="submit">Create restaurant</button>
    </form>
  `;

  const rowsEl = document.getElementById("nr-menu-rows");
  function addRow() {
    const row = document.createElement("div");
    row.className = "field";
    row.style.display = "flex";
    row.style.gap = "8px";
    row.innerHTML = `
      <input type="text" placeholder="Item name" class="nr-item-name" style="flex:2" required />
      <input type="number" step="0.01" min="0" placeholder="Price" class="nr-item-price" style="flex:1" required />
    `;
    rowsEl.appendChild(row);
  }
  addRow();
  document.getElementById("nr-add-row").addEventListener("click", addRow);
  document.getElementById("mgmt-back").addEventListener("click", renderRestaurantListPanel);

  document.getElementById("new-restaurant-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const form = e.target;
    const errEl = document.getElementById("nr-error");
    errEl.style.display = "none";

    const names = [...rowsEl.querySelectorAll(".nr-item-name")].map((i) => i.value.trim());
    const prices = [...rowsEl.querySelectorAll(".nr-item-price")].map((i) => Number(i.value));
    const menu = names
        .map((name, i) => ({ name, verifiedPrice: prices[i] }))
        .filter((m) => m.name);

    if (menu.length === 0) {
      errEl.textContent = "Add at least one menu item.";
      errEl.style.display = "block";
      return;
    }

    try {
      await Api.createRestaurantWithMenu({
        name: form.elements.name.value.trim(),
        phone: form.elements.phone.value.trim(),
        menu,
      });
      toast("Restaurant created.");
      renderRestaurantListPanel();
    } catch (err) {
      errEl.textContent = err.message;
      errEl.style.display = "block";
    }
  });
}

async function renderRestaurantDetailPanel(restaurantId) {
  const body = document.getElementById("mgmt-body");
  body.innerHTML = `<div class="spinner"></div>`;

  let restaurant;
  try {
    restaurant = await Api.getRestaurant(restaurantId);
  } catch (err) {
    body.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  body.innerHTML = `
    <button class="btn btn-outline btn-sm" id="mgmt-back" style="margin-bottom:12px">&larr; Back</button>
    <div class="ticket-title display">${escapeHtml(restaurant.name)}</div>
    <div class="field" style="margin-top:10px">
      <label for="rd-phone">Phone</label>
      <div style="display:flex;gap:8px">
        <input id="rd-phone" type="tel" value="${escapeHtml(restaurant.phone || "")}" style="flex:1" />
        <button class="btn btn-outline btn-sm" id="rd-save-phone">Save</button>
      </div>
    </div>
    <div class="section-title" style="margin-top:16px">Menu</div>
    <div id="rd-menu-list">
      ${
      restaurant.menu.length === 0
          ? `<div class="hint">No menu items yet — add the first one below.</div>`
          : restaurant.menu
              .map(
                  (m) => `
        <div class="field" style="display:flex;gap:8px;align-items:center" data-item-id="${m.id}">
          <span style="flex:2">${escapeHtml(m.name)}</span>
          <input type="number" step="0.01" min="0" class="rd-price-input" value="${m.verifiedPrice}" style="flex:1" />
          <button class="btn btn-outline btn-sm rd-save-item">Save</button>
          <button class="btn btn-danger btn-sm rd-delete-item">Delete</button>
        </div>`
              )
              .join("")
  }
    </div>
    <div class="section-title" style="margin-top:16px">Add item</div>
    <div class="field" style="display:flex;gap:8px">
      <input type="text" id="rd-new-name" placeholder="Item name" style="flex:2" />
      <input type="number" step="0.01" min="0" id="rd-new-price" placeholder="Price" style="flex:1" />
      <button class="btn btn-primary btn-sm" id="rd-add-item">Add</button>
    </div>
    <div class="error-text" id="rd-error" style="display:none"></div>
  `;

  const errEl = document.getElementById("rd-error");
  const showErr = (msg) => {
    errEl.textContent = msg;
    errEl.style.display = "block";
  };

  document.getElementById("mgmt-back").addEventListener("click", renderRestaurantListPanel);

  document.getElementById("rd-save-phone").addEventListener("click", async () => {
    try {
      await Api.updateRestaurant(restaurantId, { phone: document.getElementById("rd-phone").value.trim() });
      toast("Phone updated.");
    } catch (err) {
      showErr(err.message);
    }
  });

  document.getElementById("rd-add-item").addEventListener("click", async () => {
    const name = document.getElementById("rd-new-name").value.trim();
    const price = Number(document.getElementById("rd-new-price").value);
    if (!name || Number.isNaN(price)) {
      showErr("Enter an item name and a valid price.");
      return;
    }
    try {
      await Api.upsertMenuItem(restaurantId, { name, price });
      toast(`${name} saved.`);
      renderRestaurantDetailPanel(restaurantId);
    } catch (err) {
      showErr(err.message);
    }
  });

  document.querySelectorAll("#rd-menu-list [data-item-id]").forEach((row) => {
    const itemId = Number(row.dataset.itemId);
    const itemName = row.querySelector("span").textContent;

    row.querySelector(".rd-save-item").addEventListener("click", async () => {
      const price = Number(row.querySelector(".rd-price-input").value);
      if (Number.isNaN(price)) {
        showErr("Enter a valid price.");
        return;
      }
      try {
        await Api.upsertMenuItem(restaurantId, { name: itemName, price });
        toast(`${itemName} updated.`);
      } catch (err) {
        showErr(err.message);
      }
    });

    row.querySelector(".rd-delete-item").addEventListener("click", async () => {
      try {
        await Api.deleteMenuItem(restaurantId, itemId);
        toast(`${itemName} removed.`);
        renderRestaurantDetailPanel(restaurantId);
      } catch (err) {
        showErr(err.message);
      }
    });
  });
}

/* ------------------------------------------------------------------ *
 * Dashboard view — active OPEN rooms + admin "new room" (spec 4.B/4.C)
 * ------------------------------------------------------------------ */

function roomCardHtml(room) {
  const isOpen = room.status === "OPEN";
  const urgent = isOpen && room.secondsRemaining < 300; // last 5 minutes
  const meta = statusMeta(room.status);
  const badgeClass = isOpen && urgent ? "badge-urgent" : meta.cls;

  // A verified menu is the visible payoff of a past approval, so say so on the
  // card: users know before joining whether prices will be filled in for them.
  const menuNote = room.menuItemCount
      ? `${room.menuItemCount} priced item${room.menuItemCount === 1 ? "" : "s"} on file`
      : "";

  return `
    <div class="ticket room-card" data-room-id="${room.id}" role="button" tabindex="0">
      <div class="ticket-head">
        <div>
          <div class="ticket-title display">${escapeHtml(room.restaurantName)}</div>
          ${room.restaurantPhone ? `<div class="ticket-sub">${escapeHtml(room.restaurantPhone)}</div>` : ""}
        </div>
        <span class="badge ${badgeClass}">${escapeHtml(meta.label)}</span>
      </div>
      ${room.description ? `<div class="ticket-sub">${escapeHtml(room.description)}</div>` : ""}
      ${menuNote ? `<div class="ticket-sub">🧾 ${menuNote}</div>` : ""}
      <div class="ticket-timer ${urgent ? "urgent" : ""}" data-timer data-remaining="${room.secondsRemaining}">
        <span>⏱</span>
        <span class="mono timer-value">${isOpen ? formatCountdown(room.secondsRemaining) : meta.label.toLowerCase()}</span>
        <span>${isOpen ? "left to order" : ""}</span>
      </div>
      <div class="ticket-actions">
        <button class="btn btn-primary btn-block" data-open-room="${room.id}">
          ${isOpen ? "Join room" : "View room"}
        </button>
      </div>
    </div>
  `;
}

/**
 * Admin-only strip on the dashboard: rooms whose food has arrived but whose
 * receipt has not been entered or approved yet. Without this the workflow has
 * no inbox and a delivered order can quietly go unbilled.
 */
async function renderAdminWorklist() {
  const container = document.getElementById("admin-worklist");
  if (!container) return;

  let rooms;
  try {
    rooms = await Api.listUnapprovedRooms();
  } catch (err) {
    return; // non-fatal: the dashboard's main job is still done
  }

  if (!rooms.length) {
    container.innerHTML = "";
    return;
  }

  container.innerHTML = `
    <div class="section-title">Needs your attention</div>
    ${rooms
      .map((room) => {
        const meta = statusMeta(room.status);
        const cta = room.status === "PENDING_ADMIN_APPROVAL" ? "Review &amp; approve" : "Enter receipt";
        return `
        <div class="ticket">
          <div class="ticket-head">
            <div>
              <div class="ticket-title display">${escapeHtml(room.restaurantName)}</div>
              <div class="ticket-sub">${
            room.status === "PENDING_ADMIN_APPROVAL"
                ? `split ready · delivery ${money(room.totalDeliveryFee)}`
                : "waiting for the paper receipt"
        }</div>
            </div>
            <span class="badge ${meta.cls}">${escapeHtml(meta.label)}</span>
          </div>
          <div class="ticket-actions">
            <button class="btn btn-primary btn-block" data-approve-room="${room.id}">${cta}</button>
          </div>
        </div>`;
      })
      .join("")}
  `;

  container.querySelectorAll("[data-approve-room]").forEach((btn) => {
    btn.addEventListener("click", () => navigate(`#/room/${btn.dataset.approveRoom}/summary`));
  });
}

async function renderDashboardView() {
  const session = Auth.get();
  viewRoot.innerHTML = `<div class="spinner"></div>`;

  let rooms;
  try {
    rooms = await Api.listOpenRooms();
  } catch (err) {
    viewRoot.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  viewRoot.innerHTML = `
    <div id="admin-worklist"></div>
    <div class="section-title">Today's rooms</div>
    ${
      rooms.length
          ? `<div id="rooms-list">${rooms.map(roomCardHtml).join("")}</div>`
          : `<div class="empty-state">
             <span class="glyph">🥐</span>
             <div class="display">No open rooms right now</div>
             <div>${session.role === "ADMIN" ? "Start one with the button below." : "Check back once an admin opens one."}</div>
           </div>`
  }
  `;

  document.querySelectorAll("[data-open-room]").forEach((btn) => {
    btn.addEventListener("click", () => navigate(`#/room/${btn.dataset.openRoom}`));
  });

  // Let the whole card act as a tap target too, not just the button inside it.
  document.querySelectorAll(".room-card").forEach((card) => {
    const go = () => navigate(`#/room/${card.dataset.roomId}`);
    card.addEventListener("click", (e) => {
      if (e.target.closest("[data-open-room]")) return; // avoid double-navigate
      go();
    });
    card.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        go();
      }
    });
  });

  // live per-second countdown, purely client-side between polls
  const timerId = setInterval(() => {
    document.querySelectorAll("[data-timer]").forEach((el) => {
      let remaining = Number(el.dataset.remaining);
      if (remaining <= 0) return;
      remaining -= 1;
      el.dataset.remaining = remaining;
      const valueEl = el.querySelector(".timer-value");
      if (valueEl) valueEl.textContent = formatCountdown(remaining);
      if (remaining <= 0) {
        render(); // refresh from server (via the router, so cleanup runs) once a room expires
      } else if (remaining < 300) {
        el.classList.add("urgent");
      }
    });
  }, 1000);
  activeTimers.push(timerId);

  if (session.role === "ADMIN") {
    renderAdminWorklist();

    const fab = document.createElement("button");
    fab.className = "fab";
    fab.id = "new-room-fab";
    fab.setAttribute("aria-label", "Create new room");
    fab.textContent = "+";
    document.getElementById("app").appendChild(fab);
    fab.addEventListener("click", openCreateRoomModal);
    activeTimers.push({ remove: () => fab.remove() });
  }
}

function openCreateRoomModal() {
  openModal(
      `
    <h2 class="display">New room</h2>
    <form id="create-room-form">
      <div class="field">
        <label for="room-restaurant">Restaurant name</label>
        <input id="room-restaurant" name="restaurantName" type="text" required />
      </div>
      <div class="field">
        <label for="room-phone">Restaurant phone</label>
        <input id="room-phone" name="restaurantPhone" type="tel" />
      </div>
      <div class="field">
        <label for="room-desc">Description (optional)</label>
        <input id="room-desc" name="description" type="text" placeholder="e.g. delivery closes at 10am" />
      </div>
      <div class="hint" style="margin-bottom:14px">Rooms stay open for 60 minutes, then close automatically.</div>
      <div class="error-text" id="create-room-error" style="display:none"></div>
      <button class="btn btn-primary btn-block" type="submit">Open room</button>
    </form>
  `,
      () => {
        document.getElementById("create-room-form").addEventListener("submit", async (e) => {
          e.preventDefault();
          const form = e.target;
          const submitBtn = form.querySelector("button[type=submit]");
          submitBtn.disabled = true;
          const errEl = document.getElementById("create-room-error");
          errEl.style.display = "none";
          try {
            const room = await Api.createRoom({
              restaurantName: form.elements.restaurantName.value.trim(),
              restaurantPhone: form.elements.restaurantPhone.value.trim(),
              description: form.elements.description.value.trim(),
            });
            closeModal();
            toast(`${room.restaurantName} room is open.`);
            navigate(`#/room/${room.id}`);
          } catch (err) {
            errEl.textContent = err.message;
            errEl.style.display = "block";
            submitBtn.disabled = false;
          }
        });
      }
  );
}

/* ------------------------------------------------------------------ *
 * Active Room view — menu grid + custom item + live personal receipt
 * (spec 4.B, section 6 "Active Room View")
 * ------------------------------------------------------------------ */

let roomViewTab = "menu"; // persists across re-renders within a room session

async function renderRoomView(roomId) {
  const session = Auth.get();
  viewRoot.innerHTML = `<div class="spinner"></div>`;

  let room, cart, menu;
  try {
    // The menu comes from approved receipts, so it may legitimately be empty
    // for a restaurant nobody has ordered from yet - hence the [] fallback
    // rather than failing the whole view.
    [room, cart, menu] = await Promise.all([
      Api.getRoom(roomId),
      Api.getMyCart(roomId),
      Api.getRoomMenu(roomId).catch(() => []),
    ]);
  } catch (err) {
    viewRoot.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  // Bug fix: once the admin has entered the receipt (PENDING_ADMIN_APPROVAL)
  // or approved it (APPROVED_AND_CLOSED), the user's own final amount —
  // food subtotal + their share of delivery — lives on the split, not on the
  // cart. Previously nothing here ever fetched it, so this screen kept
  // showing only the unverified cart subtotal forever, with no delivery
  // share and no final total. Fetched only in these two statuses: earlier
  // than that there's no split yet, and the request would just fail.
  let myBill = null;
  if (room.status === "PENDING_ADMIN_APPROVAL" || room.status === "APPROVED_AND_CLOSED") {
    try {
      myBill = await Api.getMyBill(roomId);
    } catch (err) {
      // No orders in this room (e.g. an admin who never ordered) — fine,
      // just nothing to show; any other failure surfaces at the section itself.
      myBill = null;
    }
  }

  const isOpen = room.status === "OPEN";
  const meta = statusMeta(room.status);
  const subtotal = cart.reduce((sum, item) => sum + item.lineTotal, 0);

  viewRoot.innerHTML = `
    <div class="ticket" style="margin-bottom:18px">
      <div class="ticket-head">
        <div>
          <div class="ticket-title display">${escapeHtml(room.restaurantName)}</div>
          ${room.restaurantPhone ? `<div class="ticket-sub">📞 ${escapeHtml(room.restaurantPhone)}</div>` : ""}
        </div>
        <span class="badge ${meta.cls}">${escapeHtml(meta.label)}</span>
      </div>
      ${room.description ? `<div class="ticket-sub">${escapeHtml(room.description)}</div>` : ""}
      <div class="ticket-timer" data-room-timer data-remaining="${room.secondsRemaining}">
        <span>⏱</span>
        <span class="mono timer-value">${isOpen ? formatCountdown(room.secondsRemaining) : meta.label.toLowerCase()}</span>
        <span>${isOpen ? "until this room closes" : roomStateHint(room)}</span>
      </div>
      ${
      session.role === "ADMIN"
          ? `<div class="ticket-actions">
               <button class="btn btn-outline btn-sm" id="view-summary-btn">
                 ${room.status === "PENDING_ADMIN_APPROVAL" ? "Review &amp; approve" : "View summary"}
               </button>
               ${isOpen ? `<button class="btn btn-danger btn-sm" id="close-room-btn">Close room now</button>` : ""}
             </div>`
          : ""
  }
    </div>

    <div class="view-tabs" role="tablist">
      <button data-view-tab="menu" class="${roomViewTab === "menu" ? "active" : ""}">Menu</button>
      <button data-view-tab="receipt" class="${roomViewTab === "receipt" ? "active" : ""}">
        My receipt${cart.length ? ` (${cart.length})` : ""}
      </button>
    </div>

    <div id="room-tab-content"></div>
  `;

  document.getElementById("view-summary-btn")?.addEventListener("click", () =>
      navigate(`#/room/${roomId}/summary`)
  );
  document.getElementById("close-room-btn")?.addEventListener("click", async () => {
    if (!confirm("Close this room now? People won't be able to add more orders.")) return;
    try {
      await Api.closeRoom(roomId);
      toast("Room closed.");
      render();
    } catch (err) {
      toast(err.message, true);
    }
  });

  document.querySelectorAll("[data-view-tab]").forEach((btn) => {
    btn.addEventListener("click", () => {
      roomViewTab = btn.dataset.viewTab;
      renderRoomView(roomId);
    });
  });

  if (roomViewTab === "menu") {
    renderMenuTab(roomId, isOpen, menu);
  } else {
    renderReceiptTab(roomId, cart, subtotal, isOpen, myBill);
  }

  if (isOpen) {
    const timerId = setInterval(() => {
      const el = document.querySelector("[data-room-timer]");
      if (!el) return;
      let remaining = Number(el.dataset.remaining) - 1;
      el.dataset.remaining = remaining;
      if (remaining <= 0) {
        toast("This room just closed.");
        render();
        return;
      }
      el.querySelector(".timer-value").textContent = formatCountdown(remaining);
    }, 1000);
    activeTimers.push(timerId);
  }
}

/**
 * The menu grid is now the restaurant's verified menu: every price shown here
 * came off a paper receipt that an admin approved. Until a restaurant has had
 * one approval there is nothing to show, and users order by name alone -
 * which is fine, because the real prices arrive with the food.
 */
function renderMenuTab(roomId, isOpen, menu) {
  const container = document.getElementById("room-tab-content");
  const hasMenu = Array.isArray(menu) && menu.length > 0;

  container.innerHTML = `
    ${
      hasMenu
          ? `<div class="section-title">Verified menu</div>
           <div class="hint" style="margin:-6px 4px 10px">
             Prices confirmed from previous receipts. Tap to add.
           </div>
           <div class="menu-grid">
             ${menu
              .map(
                  (item) => `
               <button class="menu-chip" data-menu-name="${escapeHtml(item.name)}"
                       data-menu-price="${item.verifiedPrice}" ${isOpen ? "" : "disabled"}>
                 <span class="name">${escapeHtml(item.name)}</span>
                 <span class="price mono">${money(item.verifiedPrice)}</span>
               </button>`
              )
              .join("")}
           </div>`
          : `<div class="ticket" style="margin-bottom:16px">
             <div class="ticket-sub">
               No verified prices for this restaurant yet. Order by name — the real
               prices get filled in from the receipt once the food arrives, and
               they'll be saved here for next time.
             </div>
           </div>`
  }

    <div class="section-title">${hasMenu ? "Something else" : "Add an item"}</div>
    <div class="custom-item-form">
      <form id="add-item-form">
        <div class="field">
          <label for="item-name">Item</label>
          <input id="item-name" name="itemName" type="text" placeholder="e.g. Cheese Manakish" required ${
      isOpen ? "" : "disabled"
  } />
        </div>
        <div class="field-row">
          <div class="field">
            <label for="item-price">Price <span class="hint">(optional)</span></label>
            <input id="item-price" name="price" type="number" min="0" step="0.25"
                   placeholder="don't know yet" ${isOpen ? "" : "disabled"} />
          </div>
          <div class="field" style="max-width:110px">
            <label for="item-qty">Qty</label>
            <input id="item-qty" name="quantity" type="number" min="1" step="1" value="1" required ${
      isOpen ? "" : "disabled"
  } />
          </div>
        </div>
        <div class="hint" style="margin:-4px 0 12px">
          Leave the price blank if you're not sure — the receipt decides.
        </div>
        <div class="error-text" id="add-item-error" style="display:none"></div>
        <button class="btn btn-primary btn-block" type="submit" ${isOpen ? "" : "disabled"}>
          ${isOpen ? "Add to my order" : "Room is closed"}
        </button>
      </form>
    </div>
  `;

  document.querySelectorAll("[data-menu-name]").forEach((chip) => {
    chip.addEventListener("click", () => {
      document.getElementById("item-name").value = chip.dataset.menuName;
      document.getElementById("item-price").value = chip.dataset.menuPrice;
      document.getElementById("item-name").focus();
    });
  });

  document.getElementById("add-item-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const form = e.target;
    const submitBtn = form.querySelector("button[type=submit]");
    const errEl = document.getElementById("add-item-error");
    errEl.style.display = "none";
    submitBtn.disabled = true;

    // An empty price field is sent as null, not 0: "unknown" and "free" are
    // different claims, and only the receipt gets to settle it.
    const rawPrice = form.elements.price.value.trim();

    try {
      await Api.addOrder(roomId, {
        itemName: form.elements.itemName.value.trim(),
        price: rawPrice === "" ? null : Number(rawPrice),
        quantity: Number(form.elements.quantity.value) || 1,
      });
      toast(`Added ${form.elements.itemName.value.trim()} to your order.`);
      roomViewTab = "receipt";
      renderRoomView(roomId);
    } catch (err) {
      errEl.textContent = err.message;
      errEl.style.display = "block";
      submitBtn.disabled = false;
    }
  });
}

function renderReceiptTab(roomId, cart, subtotal, isOpen, myBill) {
  const container = document.getElementById("room-tab-content");

  if (!cart.length) {
    container.innerHTML = `
      <div class="empty-state">
        <span class="glyph">🧾</span>
        <div class="display">Nothing ordered yet</div>
        <div>Add something from the Menu tab.</div>
      </div>`;
    return;
  }

  const allVerified = cart.every((item) => item.verifiedPrice != null);

  // Defensive: session.userId and any id coming back from the API should
  // both already be numbers, but comparing via String() costs nothing and
  // guards against exactly the kind of "403/blank screen for no visible
  // reason" bug that a stray string-vs-number id mismatch causes elsewhere
  // in this app (see the JWT/401 fix). Not currently needed for myBill
  // itself, since /bill/me is already scoped server-side to the caller —
  // but kept here as the one place in this view that reasons about "whose
  // number is this".
  const billIsMine = myBill != null;

  container.innerHTML = `
    <div class="ticket">
      <div class="ticket-title" style="font-size:15px;text-transform:uppercase;letter-spacing:0.06em;color:var(--ink-600)">
        ${allVerified ? "Your final receipt" : "Your live receipt"}
      </div>
      ${cart
      .map((item) => {
        // An unverified line is shown as "—" rather than $0.00: a price
        // nobody has confirmed is not the same as an item that costs nothing.
        const verified = item.verifiedPrice != null;
        const shown = verified || item.priceAtOrder ? money(item.lineTotal) : "—";
        return `
        <div class="leader-row">
          <span class="leader-label">${escapeHtml(item.itemName)} <span class="qty">×${item.quantity}</span></span>
          <span class="leader-fill"></span>
          <span class="leader-value ${verified ? "" : "unverified"}">${shown}</span>
          ${isOpen ? `<button class="btn btn-sm btn-outline" data-remove-order="${item.id}" style="margin-left:6px">✕</button>` : ""}
        </div>`;
      })
      .join("")}
      <div class="leader-total">
        <span>${allVerified ? "Subtotal" : "Estimated subtotal"}</span>
        <span>${money(subtotal)}</span>
      </div>
      ${
      billIsMine
          ? `
      <div class="leader-row" style="margin-top:6px">
        <span class="leader-label">Delivery share</span>
        <span class="leader-fill"></span>
        <span class="leader-value">${money(myBill.deliveryShare)}</span>
      </div>
      <div class="leader-total" style="margin-top:6px;font-weight:700;font-size:17px">
        <span>Your total to pay</span>
        <span>${money(myBill.finalTotal)}</span>
      </div>`
          : ""
  }
      <div class="hint" style="margin-top:8px">
        ${
      billIsMine
          ? myBill.roomStatus === "APPROVED_AND_CLOSED"
              ? "Final — the admin has approved this receipt."
              : "The admin has entered the receipt. This total may still change slightly until they approve it."
          : allVerified
              ? "Priced from the receipt. Delivery is split evenly on top."
              : "Prices aren't final yet — they're set from the paper receipt after the food arrives."
  }
      </div>
      ${
      billIsMine
          ? `<button class="btn btn-outline btn-block" id="view-full-split-btn" style="margin-top:12px">
               See everyone's split
             </button>`
          : ""
  }
    </div>
  `;

  document.getElementById("view-full-split-btn")?.addEventListener("click", () => openFullSplitModal(roomId));

  document.querySelectorAll("[data-remove-order]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      try {
        await Api.deleteOrder(roomId, btn.dataset.removeOrder);
        renderRoomView(roomId);
      } catch (err) {
        toast(err.message, true);
      }
    });
  });
}

/**
 * "See everyone's split" — the full per-participant breakdown for a regular
 * user, not just their own line. The backend already scopes this to callers
 * who themselves have orders in the room (see BillingController#getFullBill),
 * so the only client-side job left is figuring out *which* row is "you" to
 * highlight it.
 *
 * userId here always comes back as a JSON number, and session.userId is a
 * number too, so `===` would already work today — but ids that travel through
 * localStorage/JSON round-trips are exactly the kind of value that silently
 * turns into a string after some future refactor (e.g. a route param, or a
 * value read off a form field). Comparing via String(...) on both sides costs
 * nothing and removes that whole failure mode, so it's used here regardless.
 */
async function openFullSplitModal(roomId) {
  openModal(`<h2 class="display">Full split</h2><div id="split-body"><div class="spinner"></div></div>`, async () => {
    const body = document.getElementById("split-body");
    const session = Auth.get();

    let bill;
    try {
      bill = await Api.getRoomBill(roomId);
    } catch (err) {
      body.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
      return;
    }

    body.innerHTML = `
      <div class="ticket-sub" style="margin-bottom:12px">
        ${escapeHtml(bill.restaurantName)} · ${bill.participantCount} participant${bill.participantCount === 1 ? "" : "s"}
        · delivery ${money(bill.deliverySharePerPerson)} each
      </div>
      ${bill.breakdown
        .map((row) => {
          // The string-based comparison the task asked for: robust to a
          // userId that's a number on one side and a string on the other.
          const isMe = String(row.userId) === String(session.userId);
          return `
        <div class="leader-row" style="${isMe ? "font-weight:700" : ""}">
          <span class="leader-label">${escapeHtml(row.userName)}${isMe ? " (you)" : ""}</span>
          <span class="leader-fill"></span>
          <span class="leader-value">${money(row.finalTotal)}</span>
        </div>`;
        })
        .join("")}
      <div class="leader-total" style="margin-top:10px">
        <span>Grand total</span>
        <span>${money(bill.grandTotal)}</span>
      </div>
    `;
  });
}

/* ------------------------------------------------------------------ *
 * Admin Summary View + Final Invoice View (spec 4.C, section 6)
 * ------------------------------------------------------------------ */

/**
 * Admin view for a room after ordering has stopped. It renders one of three
 * stages, chosen by the room's status, so the admin is only ever shown the one
 * action the workflow actually allows next:
 *
 *   CLOSED                 -> enter the paper receipt
 *   PENDING_ADMIN_APPROVAL -> review the split and approve
 *   APPROVED_AND_CLOSED    -> read-only final invoice
 *
 * The whole panel is admin-only. The server enforces that too (hasRole('ADMIN')
 * on /api/admin/**); this check exists so a non-admin never sees controls that
 * would only fail for them.
 */
async function renderAdminSummaryView(roomId) {
  const session = Auth.get();
  if (session.role !== "ADMIN") {
    toast("Only admins can review and approve receipts.", true);
    return navigate(`#/room/${roomId}`);
  }

  viewRoot.innerHTML = `<div class="spinner"></div>`;

  let room, summary, savedMenu;
  try {
    // savedMenu = prices already verified for this restaurant from a past
    // approved room. Fetched alongside the summary (not just when the CLOSED
    // stage renders) so a brand-new item added mid-order doesn't need a
    // second round trip to detect it has no saved price yet.
    [room, summary, savedMenu] = await Promise.all([
      Api.getRoom(roomId),
      Api.getRoomSummary(roomId),
      Api.getRoomMenu(roomId).catch(() => []),
    ]);
  } catch (err) {
    viewRoot.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  const meta = statusMeta(room.status);

  viewRoot.innerHTML = `
    <div class="section-title">
      ${escapeHtml(room.restaurantName)} — order summary
      <span class="badge ${meta.cls}" style="margin-left:8px">${escapeHtml(meta.label)}</span>
    </div>

    <div class="ticket">
      ${
      summary.aggregatedItems.length
          ? summary.aggregatedItems
              .map(
                  (item) => `
            <div class="summary-row">
              <div style="display:flex;align-items:center">
                <span class="summary-count mono">${item.totalQuantity}×</span>
                <span>${escapeHtml(item.itemName)}</span>
              </div>
              <span class="mono ${item.verifiedUnitPrice == null ? "unverified" : ""}">
                ${item.verifiedUnitPrice == null ? "unpriced" : money(item.totalPrice)}
              </span>
            </div>`
              )
              .join("")
          : `<div class="empty-state" style="padding:24px"><span class="glyph">📋</span>No orders yet.</div>`
  }
      <div class="leader-total">
        <span>${summary.pricesVerified ? "Food total" : "Food total (unconfirmed)"}</span>
        <span>${money(summary.foodTotal)}</span>
      </div>
      <div class="hint" style="margin-top:8px">
        ${summary.participantCount} participant${summary.participantCount === 1 ? "" : "s"} —
        ${
      room.status === "OPEN"
          ? "call this list in to the restaurant."
          : "this is the list the receipt should match."
  }
      </div>
    </div>

    <div id="admin-stage"></div>
  `;

  renderAdminStage(roomId, room, summary, savedMenu);
}

function renderAdminStage(roomId, room, summary, savedMenu) {
  const el = document.getElementById("admin-stage");

  if (!summary.participantCount) {
    el.innerHTML = `<div class="hint" style="padding:0 4px">Nothing to bill — this room has no orders.</div>`;
    return;
  }

  switch (room.status) {
    case "OPEN":
      el.innerHTML = `
        <div class="hint" style="padding:0 4px">
          Close the room first. The receipt can only be entered once the food has
          been ordered and delivered.
        </div>`;
      return;
    case "CLOSED":
      return renderReceiptEntryForm(roomId, room, summary, savedMenu);
    case "PENDING_ADMIN_APPROVAL":
      return renderApprovalPanel(roomId, room, summary);
    case "APPROVED_AND_CLOSED":
      return renderApprovedSummary(roomId, room);
    default:
      el.innerHTML = "";
  }
}

/**
 * One distinct ordered item name per row, each with a price field. This is the
 * transcription step: the admin copies the paper receipt in, and those numbers
 * — not what anyone guessed while ordering — become the bill.
 */
function receiptItemRowsHtml(summary, priceOf) {
  return summary.aggregatedItems
      .map((item, i) => {
        const info = priceOf(item);
        const value = info && info.price != null ? info.price : "";
        return `
      <div class="receipt-entry-row">
        <label for="rprice-${i}">
          <span class="qty mono">${item.totalQuantity}×</span>
          ${escapeHtml(item.itemName)}
          ${
            info && info.fromMenu
                ? `<span class="hint" style="margin-left:6px">(from saved menu)</span>`
                : `<span class="hint" style="margin-left:6px;color:var(--butter-500)">(new item — enter price)</span>`
        }
        </label>
        <input id="rprice-${i}" type="number" min="0" step="0.25" required
               class="receipt-price-input"
               data-item-name="${escapeHtml(item.itemName)}"
               value="${value === "" ? "" : value}"
               placeholder="unit price" />
      </div>`;
      })
      .join("");
}

/** Live "×qty = line total" echo, so a mistyped price is obvious immediately. */
function wireReceiptTotals(summary, deliveryInputId, totalOutId) {
  const recompute = () => {
    let food = 0;
    document.querySelectorAll(".receipt-price-input").forEach((input, i) => {
      const qty = summary.aggregatedItems[i].totalQuantity;
      const price = Number(input.value);
      if (input.value !== "" && !Number.isNaN(price)) food += price * qty;
    });
    const delivery = Number(document.getElementById(deliveryInputId)?.value) || 0;
    const out = document.getElementById(totalOutId);
    if (out) out.textContent = money(food + delivery);
  };

  document.querySelectorAll(".receipt-price-input").forEach((input) => {
    input.addEventListener("input", recompute);
  });
  document.getElementById(deliveryInputId)?.addEventListener("input", recompute);
  recompute();
}

/** Reads the price fields back out as the API's { name, verifiedPrice } items. */
function collectReceiptItems() {
  return Array.from(document.querySelectorAll(".receipt-price-input")).map((input) => ({
    name: input.dataset.itemName,
    verifiedPrice: Number(input.value),
  }));
}

/* --- Stage 1: CLOSED — enter the paper receipt --------------------------- */

/**
 * Prefills each row from the restaurant's saved, receipt-approved menu
 * (matched case-insensitively by name, same normalization the backend uses)
 * so the admin isn't retyping prices that are already known. An item with no
 * match — one that has never survived an approval before — is left blank on
 * purpose: that's the admin's cue it's new and needs a real price typed in,
 * exactly like the receipt-entry step always required. Every field, prefilled
 * or not, stays a normal editable input.
 */
function buildSavedPriceLookup(savedMenu) {
  const map = new Map();
  (savedMenu || []).forEach((item) => {
    if (item.name && item.verifiedPrice != null) {
      map.set(item.name.trim().toLowerCase(), item.verifiedPrice);
    }
  });
  return (item) => {
    // A price already present on this order line (e.g. a correction pass
    // after a first receipt submission) always wins over the saved menu.
    if (item.verifiedUnitPrice != null) {
      return { price: item.verifiedUnitPrice, fromMenu: true };
    }
    const saved = map.get(String(item.itemName || "").trim().toLowerCase());
    return saved != null ? { price: saved, fromMenu: true } : { price: null, fromMenu: false };
  };
}

function renderReceiptEntryForm(roomId, room, summary, savedMenu) {
  const el = document.getElementById("admin-stage");
  const priceOf = buildSavedPriceLookup(savedMenu);

  el.innerHTML = `
    <div class="section-title">Enter the receipt</div>
    <div class="ticket">
      <div class="hint" style="margin-bottom:12px">
        Prices already on ${escapeHtml(room.restaurantName)}'s saved menu are filled in for
        you — check them against the paper receipt and correct anything that's changed.
        New items (marked below) have no saved price yet, so type those in from the receipt.
      </div>
      <form id="receipt-form">
        ${receiptItemRowsHtml(summary, priceOf)}

        <div class="field" style="margin-top:14px">
          <label for="receipt-delivery">Total delivery fee</label>
          <input id="receipt-delivery" name="totalDelivery" type="number" min="0" step="0.25" required
                 value="${room.totalDeliveryFee == null ? "" : room.totalDeliveryFee}" />
          <div class="hint">Split evenly across ${summary.participantCount} participant${
      summary.participantCount === 1 ? "" : "s"
  }.</div>
        </div>

        <div class="field">
          <label for="receipt-total">Receipt grand total <span class="hint">(optional)</span></label>
          <input id="receipt-total" name="receiptTotal" type="number" min="0" step="0.25"
                 placeholder="to cross-check" />
          <div class="hint">If you enter it, we'll flag any mismatch before you approve.</div>
        </div>

        <div class="leader-total">
          <span>Computed total</span>
          <span id="receipt-computed">$0.00</span>
        </div>

        <div class="error-text" id="receipt-error" style="display:none"></div>
        <button class="btn btn-primary btn-block" type="submit" style="margin-top:12px">
          Split the bill
        </button>
        <div class="hint" style="margin-top:8px;text-align:center">
          This only prepares the split — nothing is saved to the menu until you approve.
        </div>
      </form>
    </div>
  `;

  wireReceiptTotals(summary, "receipt-delivery", "receipt-computed");

  document.getElementById("receipt-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const form = e.target;
    const submitBtn = form.querySelector("button[type=submit]");
    const errEl = document.getElementById("receipt-error");
    errEl.style.display = "none";
    submitBtn.disabled = true;

    const receiptTotalRaw = form.elements.receiptTotal.value.trim();

    try {
      await Api.enterReceipt(roomId, {
        items: collectReceiptItems(),
        totalDelivery: Number(form.elements.totalDelivery.value),
        receiptTotal: receiptTotalRaw === "" ? null : Number(receiptTotalRaw),
      });
      toast("Bill split. Review and approve to finish.");
      renderAdminSummaryView(roomId); // re-enters at the approval stage
    } catch (err) {
      errEl.textContent = err.message;
      errEl.style.display = "block";
      submitBtn.disabled = false;
    }
  });
}

/* --- Stage 2: PENDING_ADMIN_APPROVAL — review and approve ---------------- */

/**
 * The approval panel. Prices stay editable here on purpose: this is the last
 * point at which a transcription error can be caught, and approving is what
 * writes these numbers into the restaurant's permanent menu.
 */
async function renderApprovalPanel(roomId, room, summary) {
  const el = document.getElementById("admin-stage");
  el.innerHTML = `<div class="spinner"></div>`;

  let bill;
  try {
    bill = await Api.previewBill(roomId, room.totalDeliveryFee);
  } catch (err) {
    el.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  const mismatch =
      room.receiptTotal != null && Math.abs(room.receiptTotal - bill.grandTotal) >= 0.01;

  el.innerHTML = `
    <div class="section-title">Review &amp; approve</div>

    <div class="ticket">
      <div class="ticket-title" style="font-size:15px;text-transform:uppercase;letter-spacing:0.06em;color:var(--ink-600)">
        Verified receipt items
      </div>
      <div class="hint" style="margin:6px 0 12px">
        Approving saves these prices to <strong>${escapeHtml(room.restaurantName)}</strong>'s
        menu, so future rooms can order them pre-priced. Correct anything that's off first.
      </div>

      <form id="approve-form">
        ${receiptItemRowsHtml(summary, (item) => ({ price: item.verifiedUnitPrice, fromMenu: true }))}

        <div class="field" style="margin-top:14px">
          <label for="approve-delivery">Total delivery fee</label>
          <input id="approve-delivery" name="totalDelivery" type="number" min="0" step="0.25" required
                 value="${room.totalDeliveryFee == null ? "" : room.totalDeliveryFee}" />
        </div>

        <div class="leader-total">
          <span>Computed total</span>
          <span id="approve-computed">$0.00</span>
        </div>

        ${
      mismatch
          ? `<div class="error-text" style="display:block;margin-top:10px">
                 Receipt says ${money(room.receiptTotal)} but the items add up to
                 ${money(bill.grandTotal)} — check the prices before approving.
               </div>`
          : ""
  }

        <label class="checkbox-row" style="margin-top:12px">
          <input type="checkbox" id="save-to-menu" checked />
          <span>Save these prices to the restaurant's menu</span>
        </label>
        <div class="hint">Uncheck for a one-off order you don't want on the menu.</div>

        <div class="error-text" id="approve-error" style="display:none"></div>
        <button class="btn btn-primary btn-block" type="submit" style="margin-top:14px">
          Approve &amp; close room
        </button>
      </form>
    </div>

    <div class="section-title">Split as it stands</div>
    ${billBreakdownHtml(bill)}
  `;

  wireReceiptTotals(summary, "approve-delivery", "approve-computed");

  document.getElementById("approve-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const form = e.target;
    const submitBtn = form.querySelector("button[type=submit]");
    const errEl = document.getElementById("approve-error");
    errEl.style.display = "none";

    if (
        !confirm(
            "Approve this receipt? The splits are locked in and the prices are saved to the menu."
        )
    )
      return;

    submitBtn.disabled = true;
    try {
      const result = await Api.approveReceipt(roomId, {
        items: collectReceiptItems(),
        totalDelivery: Number(form.elements.totalDelivery.value),
        saveToMenu: document.getElementById("save-to-menu").checked,
      });
      toast("Approved. Menu updated.");
      renderApprovalResult(roomId, result);
    } catch (err) {
      errEl.textContent = err.message;
      errEl.style.display = "block";
      submitBtn.disabled = false;
    }
  });
}

/* --- Stage 3: APPROVED_AND_CLOSED — read-only ---------------------------- */

async function renderApprovedSummary(roomId, room) {
  const el = document.getElementById("admin-stage");
  el.innerHTML = `<div class="spinner"></div>`;

  let bill;
  try {
    bill = await Api.previewBill(roomId, room.totalDeliveryFee);
  } catch (err) {
    el.innerHTML = `<div class="error-text">${escapeHtml(err.message)}</div>`;
    return;
  }

  el.innerHTML = `
    <div class="section-title">Final invoice</div>
    ${billBreakdownHtml(bill, true)}
    <div class="hint" style="padding:0 4px">
      Approved${room.approvedByName ? ` by ${escapeHtml(room.approvedByName)}` : ""}. These
      prices are now on ${escapeHtml(room.restaurantName)}'s menu.
    </div>
  `;
}

/* --- Shared: what each person owes --------------------------------------- */

function billBreakdownHtml(bill, finalized) {
  return `
    <div class="ticket">
      ${finalized ? `<span class="stamp">Approved</span>` : ""}
      <div class="ticket-sub">
        ${bill.participantCount} participant${bill.participantCount === 1 ? "" : "s"} ·
        delivery ${money(bill.totalDelivery)} split evenly
      </div>

      ${bill.breakdown
      .map(
          (u) => `
        <div class="leader-row" style="display:block;padding:10px 0">
          <div class="leader-row" style="border:none;padding:0">
            <span class="leader-label" style="font-weight:700">${escapeHtml(u.userName)}</span>
            <span class="leader-fill"></span>
            <span class="leader-value">${money(u.finalTotal)}</span>
          </div>
          <div class="hint" style="margin-top:2px">
            food ${money(u.foodSubtotal)} + delivery ${money(u.deliveryShare)}
          </div>
        </div>`
      )
      .join("")}

      <div class="leader-total">
        <span>Grand total</span>
        <span>${money(bill.grandTotal)}</span>
      </div>
      <div class="hint" style="margin-top:6px">
        Food ${money(bill.totalFoodCost)} + delivery ${money(bill.totalDelivery)}
      </div>
    </div>
  `;
}

/** What the approval actually changed — including the menu it just wrote. */
function renderApprovalResult(roomId, result) {
  const menuChanges = [
    ...(result.createdMenuItems || []).map((m) => ({ ...m, verb: "added" })),
    ...(result.updatedMenuItems || []).map((m) => ({ ...m, verb: "repriced" })),
  ];

  viewRoot.innerHTML = `
    <div class="section-title">Approved</div>
    ${billBreakdownHtml(result.bill, true)}

    <div class="section-title">Saved to ${escapeHtml(result.restaurantName)}'s menu</div>
    <div class="ticket">
      ${
      menuChanges.length
          ? menuChanges
              .map(
                  (m) => `
          <div class="summary-row">
            <div style="display:flex;align-items:center;gap:8px">
              <span>${escapeHtml(m.name)}</span>
              <span class="badge ${m.verb === "added" ? "badge-open" : "badge-review"}">${m.verb}</span>
            </div>
            <span class="mono">${money(m.verifiedPrice)}</span>
          </div>`
              )
              .join("")
          : `<div class="hint">No menu changes — every price already matched what was on file.</div>`
  }
      <div class="hint" style="margin-top:10px">
        Next time someone opens a room for ${escapeHtml(result.restaurantName)}, these prices
        are filled in automatically.
      </div>
    </div>

    <button class="btn btn-outline btn-block" id="back-to-dashboard">Back to rooms</button>
  `;

  document.getElementById("back-to-dashboard").addEventListener("click", () => navigate("#/dashboard"));
}