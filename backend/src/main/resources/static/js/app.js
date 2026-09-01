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
      <button class="btn btn-outline btn-block" id="account-logout" style="margin-top:16px">Log out</button>
    </div>
    ${
      session.role === "ADMIN"
        ? `<div class="hint" style="padding:0 4px">As an admin, you can create rooms, close them early, view the live order summary, and calculate the final bill.</div>`
        : ""
    }
  `;
  document.getElementById("account-logout").addEventListener("click", () => {
    Auth.clear();
    navigate("#/auth");
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
    renderReceiptTab(roomId, cart, subtotal, isOpen);
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

function renderReceiptTab(roomId, cart, subtotal, isOpen) {
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
      <div class="hint" style="margin-top:8px">
        ${
          allVerified
            ? "Priced from the receipt. Delivery is split evenly on top."
            : "Prices aren't final yet — they're set from the paper receipt after the food arrives."
        }
      </div>
    </div>
  `;

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

  let room, summary;
  try {
    [room, summary] = await Promise.all([Api.getRoom(roomId), Api.getRoomSummary(roomId)]);
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

  renderAdminStage(roomId, room, summary);
}

function renderAdminStage(roomId, room, summary) {
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
      return renderReceiptEntryForm(roomId, room, summary);
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
      const value = priceOf(item);
      return `
      <div class="receipt-entry-row">
        <label for="rprice-${i}">
          <span class="qty mono">${item.totalQuantity}×</span>
          ${escapeHtml(item.itemName)}
        </label>
        <input id="rprice-${i}" type="number" min="0" step="0.25" required
               class="receipt-price-input"
               data-item-name="${escapeHtml(item.itemName)}"
               value="${value == null ? "" : value}"
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

function renderReceiptEntryForm(roomId, room, summary) {
  const el = document.getElementById("admin-stage");

  el.innerHTML = `
    <div class="section-title">Enter the receipt</div>
    <div class="ticket">
      <div class="hint" style="margin-bottom:12px">
        The food has arrived. Type the real price per unit from the paper receipt —
        these replace whatever people guessed while ordering.
      </div>
      <form id="receipt-form">
        ${receiptItemRowsHtml(summary, (item) => item.verifiedUnitPrice)}

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
        ${receiptItemRowsHtml(summary, (item) => item.verifiedUnitPrice)}

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
