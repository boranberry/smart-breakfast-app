// api.js — thin wrapper around the Smart Office Breakfast REST API.
//
// Defaults to a same-origin relative path ("/api") because the frontend is
// served directly by the Spring Boot backend (see backend's
// src/main/resources/static). Override window.BREAKFAST_API_BASE before this
// script loads if you're serving the frontend separately from the API.

const API_BASE = window.BREAKFAST_API_BASE || "/api";

const Auth = {
  KEY: "breakfast.session",

  save(session) {
    localStorage.setItem(Auth.KEY, JSON.stringify(session));
  },

  get() {
    const raw = localStorage.getItem(Auth.KEY);
    return raw ? JSON.parse(raw) : null;
  },

  clear() {
    localStorage.removeItem(Auth.KEY);
  },

  isAdmin() {
    const s = Auth.get();
    return !!s && s.role === "ADMIN";
  },
};

class ApiError extends Error {
  constructor(message, status, fieldErrors) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors || null;
  }
}

async function request(method, path, body, opts = {}) {
  const headers = { "Content-Type": "application/json" };
  const session = Auth.get();
  if (session && !opts.noAuth) {
    headers["Authorization"] = "Bearer " + session.token;
  }

  let res;
  try {
    res = await fetch(API_BASE + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch (networkErr) {
    throw new ApiError(
        "Can't reach the server. Check that the backend is running and API_BASE is correct.",
        0
    );
  }

  if (res.status === 204) return null;

  let data = null;
  const text = await res.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (e) {
      data = null;
    }
  }

  if (!res.ok) {
    if (res.status === 401) {
      // Backend now returns a clean 401 (via SecurityConfig's AuthenticationEntryPoint)
      // for a missing/expired/invalid token, distinct from 403 (logged in but not
      // allowed). Clear the stale session and bounce to login immediately rather
      // than leaving the user stuck on a screen that will keep failing the same way.
      Auth.clear();
      if (location.hash !== "#/auth") {
        location.hash = "#/auth";
      }
    }
    const message = (data && data.message) || `Request failed (${res.status})`;

    // Generic, page-wide feedback for the three error classes almost every
    // screen in the app can hit: 400 (bad request / a business-rule
    // validation failed, e.g. splitting an already-approved room), 403
    // (logged in but not allowed to do this, e.g. no orders of your own in
    // this room), and 409 (conflict, e.g. the room's status changed under
    // you). This runs for EVERY call site automatically, so a screen can no
    // longer fail silently on one of these codes just because a particular
    // catch block forgot to surface the message. Individual call sites can
    // still catch the thrown ApiError below to do extra, situation-specific
    // handling (inline field errors, re-enabling a button, etc.) on top of
    // this notification - the two aren't mutually exclusive.
    if (!opts.silent && (res.status === 400 || res.status === 403 || res.status === 409)) {
      notifyError(message);
    }

    throw new ApiError(message, res.status, data && data.fieldErrors);
  }

  return data;
}

// Self-contained so this fires even if app.js's own toast() isn't available
// yet for some reason (e.g. api.js reused on a page without it). Prefers
// app.js's existing #toast element/styling when present, for a consistent
// look with the rest of the UI, and otherwise falls back to a plain alert.
function notifyError(message) {
  const el = document.getElementById("toast");
  if (el) {
    el.textContent = message;
    el.className = "show error";
    clearTimeout(notifyError._t);
    notifyError._t = setTimeout(() => (el.className = ""), 3200);
  } else if (typeof window.alert === "function") {
    window.alert(message);
  }
}

const Api = {
  // --- Auth ---
  register: (payload) => request("POST", "/auth/register", payload, { noAuth: true }),
  login: (payload) => request("POST", "/auth/login", payload, { noAuth: true }),

  // --- Rooms ---
  listOpenRooms: () => request("GET", "/rooms"),
  listAllRooms: () => request("GET", "/rooms?status=all"),
  listMyRooms: () => request("GET", "/rooms/mine"),
  getRoom: (roomId) => request("GET", `/rooms/${roomId}`),
  createRoom: (payload) => request("POST", "/rooms", payload),
  closeRoom: (roomId) => request("POST", `/rooms/${roomId}/close`),

  // --- Orders ---
  addOrder: (roomId, payload) => request("POST", `/rooms/${roomId}/orders`, payload),
  deleteOrder: (roomId, orderId) => request("DELETE", `/rooms/${roomId}/orders/${orderId}`),
  getMyCart: (roomId) => request("GET", `/rooms/${roomId}/orders/me`),
  getRoomSummary: (roomId) => request("GET", `/rooms/${roomId}/orders/summary`),
  getMyBill: (roomId) => request("GET", `/rooms/${roomId}/bill/me`),
  getRoomBill: (roomId) => request("GET", `/rooms/${roomId}/bill`),

  // --- Restaurants & verified menus ---
  // Prices here have survived an admin approval, so they can be trusted to
  // pre-fill an order. An empty menu just means this restaurant has not had a
  // receipt approved yet.
  listRestaurants: () => request("GET", "/restaurants"),
  getRestaurant: (restaurantId) => request("GET", `/restaurants/${restaurantId}`),
  getRestaurantMenu: (restaurantId) => request("GET", `/restaurants/${restaurantId}/menu`),
  getRoomMenu: (roomId) => request("GET", `/rooms/${roomId}/menu`),

  // --- Restaurant management (ADMIN) ---
  // Manual restaurant/menu editing outside the receipt-approval workflow.
  createRestaurantWithMenu: (payload) => request("POST", "/admin/restaurants", payload),
  updateRestaurant: (restaurantId, payload) => request("PUT", `/admin/restaurants/${restaurantId}`, payload),
  upsertMenuItem: (restaurantId, payload) => request("POST", `/admin/restaurants/${restaurantId}/menu`, payload),
  deleteMenuItem: (restaurantId, menuItemId) =>
      request("DELETE", `/admin/restaurants/${restaurantId}/menu/${menuItemId}`),

  // --- User management (ADMIN) ---
  listUsers: () => request("GET", "/admin/users"),
  promoteUser: (userId) => request("POST", `/admin/users/${userId}/promote`),
  demoteUser: (userId) => request("POST", `/admin/users/${userId}/demote`),
  makeAllUsersAdmin: (confirmation) => request("POST", "/admin/users/make-all-admin", { confirmation }),

  // --- Billing / post-delivery receipt entry (ADMIN) ---
  // payload: { items: [{ name, verifiedPrice }], totalDelivery, receiptTotal }
  enterReceipt: (roomId, payload) => request("POST", `/rooms/${roomId}/receipt`, payload),

  calculateBill: (roomId, totalDelivery) =>
      request("POST", `/rooms/${roomId}/calculate-bill?totalDelivery=${encodeURIComponent(totalDelivery)}`),

  // --- Admin approval (ADMIN) ---
  listPendingApproval: () => request("GET", "/admin/rooms/pending-approval"),
  listUnapprovedRooms: () => request("GET", "/admin/rooms/unapproved"),

  previewBill: (roomId, totalDelivery) =>
      request(
          "GET",
          `/admin/rooms/${roomId}/bill-preview` +
          (totalDelivery == null ? "" : `?totalDelivery=${encodeURIComponent(totalDelivery)}`)
      ),

  // payload: { items: [{ name, verifiedPrice }], totalDelivery, receiptTotal, saveToMenu }
  approveReceipt: (roomId, payload) => request("POST", `/admin/rooms/${roomId}/approve`, payload),
};