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
    throw new ApiError(message, res.status, data && data.fieldErrors);
  }

  return data;
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