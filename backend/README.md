# Smart Office Breakfast Ordering System — Backend

A Spring Boot 3 / Java 17 REST API implementing the full spec: room-based group
orders with a live countdown, real-time order persistence, and automatic
delivery-fee bill splitting.

> **This backend also serves the frontend** (see `src/main/resources/static/`)
> and is meant to be run as part of the combined project — see the
> **[root README](../README.md)** for the one-command Docker setup and the
> full run/database instructions. Everything below is backend-specific
> reference (API endpoints, entities, design decisions).

## Tech Stack

- Java 17, Spring Boot 3.2.5
- Spring Web, Spring Data JPA, Spring Security (stateless JWT)
- MySQL or PostgreSQL (both drivers included — pick one in `application.yml`)
- Lombok
- `@Scheduled` background job for room auto-expiration

## Project Structure

```
src/main/java/com/smartoffice/breakfast/
├── entity/       User, Room, LiveOrder, Restaurant, MenuItem
│                 + Role/RoomStatus enums
├── repository/   Spring Data JPA repositories
├── dto/          Request/response payloads
├── security/     JWT util, filter, UserDetails wiring
├── config/       SecurityConfig (CORS, stateless auth, role rules)
├── service/      AuthService, RoomService, OrderService, BillingService,
│                 RestaurantService, AdminApprovalService
├── scheduler/    RoomExpirationScheduler (@Scheduled, every minute)
├── controller/   REST endpoints, incl. AdminApprovalController
└── exception/    Custom exceptions + @RestControllerAdvice

src/main/resources/
├── application.yml
└── static/       Frontend — index.html, css/, js/ (served at "/")

src/test/
├── java/...      ApprovalWorkflowIntegrationTest (end-to-end workflow)
└── resources/    application-test.yml (in-memory H2)
```

The frontend lives at `src/main/resources/static/` and is served by Spring
Boot's default static-resource handling — there's no separate frontend
process or build step. `SecurityConfig` permits `/`, `/index.html`,
`/css/**`, and `/js/**` alongside the public `/api/auth/**` endpoints; every
other `/api/**` route requires a bearer token. See
`src/main/resources/static/js/app.js` for the SPA's router and views, and
its inline comments for the design rationale (the "order ticket" visual
concept — dotted receipt lines, monospace pricing, perforated card edges).

## Getting Started

See the **[root README](../README.md)** for the recommended Docker Compose
setup (handles the database for you). The condensed manual version:

### 1. Database

Create a MySQL database (or switch to PostgreSQL in `application.yml`):

```sql
CREATE DATABASE breakfast_db;
```

Tables are auto-created/updated by Hibernate (`ddl-auto: update`) on first run,
matching the schema from the spec plus two additive nullable columns on
`rooms` (`total_delivery_fee`, `finalized_at`) used to record the finalized bill.

### 2. Configure environment variables (recommended for production)

```bash
export DB_USERNAME=root
export DB_PASSWORD=your_password
export JWT_SECRET=$(openssl rand -base64 32)
```

Defaults in `application.yml` work out of the box for local dev.

### 3. Run

```bash
mvn spring-boot:run
```

The API — and the frontend it serves — starts on `http://localhost:8080`.

## Authentication

JWT-based, stateless. The **first user ever registered becomes ADMIN**
automatically; everyone after that registers as a regular `USER`. (In a real
deployment you'd instead seed an admin or add an invite-code/promotion flow —
this rule just guarantees the system is usable out of the box.)

Send the token on every subsequent request:

```
Authorization: Bearer <token>
```

## API Reference

### Auth (public)

| Method | Endpoint             | Body                              |
|--------|-----------------------|------------------------------------|
| POST   | `/api/auth/register`  | `{ name, phone, password }`        |
| POST   | `/api/auth/login`     | `{ phone, password }`              |

Both return `{ token, userId, name, phone, role }`.

### Rooms

| Method | Endpoint                     | Role  | Description |
|--------|-------------------------------|-------|--------------|
| POST   | `/api/rooms`                  | ADMIN | Create a room (starts a 60-min countdown) |
| GET    | `/api/rooms`                  | Any   | List OPEN rooms (`?status=all` for every room) |
| GET    | `/api/rooms/{roomId}`         | Any   | Room details incl. `secondsRemaining` |
| POST   | `/api/rooms/{roomId}/close`   | ADMIN | Manually close a room early |

`POST /api/rooms` body: `{ restaurantName, restaurantPhone, description, restaurantId? }`

Pass `restaurantId` to reopen for a restaurant that already exists; otherwise
the restaurant is looked up by name and created if it is new. Either way the
room is linked to a `Restaurant`, which is what lets it serve that restaurant's
verified menu and receive new prices on approval.

### Orders (live cart)

| Method | Endpoint                                  | Role  | Description |
|--------|---------------------------------------------|-------|--------------|
| POST   | `/api/rooms/{roomId}/orders`                | Any   | Add an item — fires instantly, per spec 5.A |
| DELETE | `/api/rooms/{roomId}/orders/{orderId}`      | Any   | Remove your own cart line |
| GET    | `/api/rooms/{roomId}/orders/me`             | Any   | Your personal live cart |
| GET    | `/api/rooms/{roomId}/orders/summary`        | ADMIN | Aggregated item counts + full order list |

`POST .../orders` body: `{ itemName, price?, quantity }` (quantity defaults to 1).

**`price` is optional and untrusted.** While a room is OPEN nobody is expected
to know what anything costs — the frontend pre-fills it from the verified menu
where one exists and leaves it blank otherwise. Whatever arrives here is
overwritten by the paper receipt after delivery, so it only ever drives the
running estimate a user sees in their own cart.

Order responses carry both `priceAtOrder` (the guess) and `verifiedPrice` (the
receipt figure, `null` until the receipt is entered); `lineTotal` prefers the
verified price.

Orders are rejected with `409 Conflict` once a room leaves `OPEN` (manually
closed, auto-expired, or further along the approval workflow).

### Billing — post-delivery receipt entry

| Method | Endpoint                                                | Role  |
|--------|---------------------------------------------------------|-------|
| POST   | `/api/rooms/{roomId}/receipt`                           | ADMIN |
| POST   | `/api/rooms/{roomId}/calculate-bill?totalDelivery=45.0` | ADMIN |

Once the food has been delivered and the paper receipt is in hand, the admin
posts the real per-item prices. These overwrite whatever users guessed while
the room was open, and the bill is split from them.

`POST /api/rooms/{roomId}/receipt` body:

```json
{
  "items": [
    { "name": "Falafel Sandwich", "verifiedPrice": 4.50 },
    { "name": "Tea",              "verifiedPrice": 1.00 }
  ],
  "totalDelivery": 9.00,
  "receiptTotal": 23.50
}
```

Item names are matched to orders case-insensitively and trimmed. The response
carries the draft split plus `unpricedItems` (ordered items the submission did
not cover) and `reconciliationDelta` (`receiptTotal` minus the computed total,
so a transcription slip shows up before approval).

`calculate-bill` is the shortcut for rooms where the prices users entered
already match the receipt: it takes only the delivery fee.

**Neither endpoint closes the room.** Both land in `PENDING_ADMIN_APPROVAL` —
approval is always a separate, explicit step.

The split algorithm is unchanged in shape:
1. Fetch all `live_orders` for the room.
2. `N` = number of unique participants.
3. `deliveryShare = totalDelivery / N`.
4. Per user: `foodSubtotal = Σ(verified_price × quantity)`.
5. Per user: `finalTotal = foodSubtotal + deliveryShare`.

`pricesVerified` on the response is `false` while any line is still an
ordering-time guess — treat such a split as a preview, not as money owed.

### Admin approval (all ADMIN)

| Method | Endpoint                                    | Description |
|--------|---------------------------------------------|-------------|
| GET    | `/api/admin/rooms/pending-approval`         | The approval queue |
| GET    | `/api/admin/rooms/unapproved`               | Closed **or** pending — everything still owed |
| GET    | `/api/admin/rooms/{roomId}/bill-preview`    | Read-only split; does not advance the room |
| POST   | `/api/admin/rooms/{roomId}/approve`         | Approve the receipt |

`POST /api/admin/rooms/{roomId}/approve` body:

```json
{
  "items": [
    { "name": "Falafel Sandwich", "verifiedPrice": 5.00 },
    { "name": "Tea",              "verifiedPrice": 1.00 }
  ],
  "totalDelivery": 9.00,
  "saveToMenu": true
}
```

Prices are still editable here on purpose — approval is the last point at which
a transcription error can be caught. In one transaction, approving:

1. confirms the final bill split from the receipt-verified prices,
2. extracts the ordered item names and their verified prices,
3. creates or updates the `Restaurant` and `MenuItem` rows,
4. moves the room to `APPROVED_AND_CLOSED`.

Approval is **refused** if any ordered item is missing from `items` — the
response names the offending items. `totalDelivery` may be omitted to reuse the
fee from receipt entry. `saveToMenu: false` approves a one-off order without
touching the menu. An `APPROVED_AND_CLOSED` room is frozen: further receipt
entry or approval returns `400`.

The response reports the locked-in `bill` plus `createdMenuItems` and
`updatedMenuItems`, so the UI can show exactly what the approval changed.

### Restaurants & verified menus (any authenticated user)

| Method | Endpoint                                 | Description |
|--------|------------------------------------------|-------------|
| GET    | `/api/restaurants`                       | All restaurants with their menus |
| GET    | `/api/restaurants/{id}`                  | One restaurant + menu |
| GET    | `/api/restaurants/{id}/menu`             | Just the verified menu |
| GET    | `/api/rooms/{roomId}/menu`               | Menu for the room's restaurant |

Every price here came off a receipt an admin approved, which is what makes it
safe to pre-fill an order with. A restaurant that has never had a receipt
approved returns an empty list — the UI then lets users order by name alone.

Restaurants are created implicitly: opening a room for a name that does not
exist yet materialises the row (matched case-insensitively, so "Abu Ali" and
"abu ali" stay one restaurant). `POST /api/rooms` also accepts `restaurantId`
to reopen for a known restaurant.

## Room Lifecycle

```
OPEN ──(admin closes / 60-min timer)──▶ CLOSED
     food is ordered and delivered; the paper receipt arrives
CLOSED ──(POST .../receipt)──▶ PENDING_ADMIN_APPROVAL
     bill split from the receipt prices, awaiting a final admin check
PENDING_ADMIN_APPROVAL ──(POST /api/admin/.../approve)──▶ APPROVED_AND_CLOSED
     splits locked in; verified prices written to the restaurant's menu
```

- Every room gets exactly 60 minutes from `created_at` (configurable via
  `app.room.duration-minutes`).
- `RoomExpirationScheduler` runs every 60 seconds (`app.room.scheduler.fixed-rate-ms`)
  and flips any overdue `OPEN` room to `CLOSED`.
- As a safety net, `RoomService.getOpenRoomOrThrow` also re-checks expiry
  on every write (add/delete order) so a room can't be written to in the
  small gap before the next scheduler tick.

## Why approval is a separate step

Prices are unknown at order time and only become real when the receipt arrives.
The menu is therefore built *backwards* from confirmed spend rather than
maintained by hand, and one human check stands between a transcribed receipt
and a permanent price. Nothing a user types during an open room can ever reach
the menu on its own.

## Notes / Deliberate Additions Beyond the Original Spec

- `LiveOrder.priceAtOrder` is now nullable and explicitly untrusted; the new
  `verifiedPrice` column holds the receipt figure. `effectiveLineTotal()`
  prefers the verified price and falls back to the guess.
  The service writes `0.0` rather than `null` for an omitted price, so an
  existing MySQL schema with `price_at_order NOT NULL` keeps working under
  `ddl-auto: update` (which does not relax column nullability).
- Nullable columns added to `rooms`: `total_delivery_fee`, `receipt_total`,
  `finalized_at`, `approved_at`, `approved_by`, `restaurant_id`.
- A `DELETE` order-line endpoint was added since the spec's UI implies a
  "live receipt" a user can review.
- Passwords are BCrypt-hashed; the raw schema's `VARCHAR(255) password`
  column is sized to fit BCrypt hashes.
- **Bug fix (pre-existing):** `JwtUtil.getSigningKey()` caught
  `IllegalArgumentException` around `Decoders.BASE64.decode`, but jjwt wraps
  that in its own `DecodingException`. Any non-base64 secret — including this
  app's shipped default, which contains `-` — escaped and made every request
  fail with a 500. Now catches `RuntimeException`.

## Testing Quickly with curl

```bash
# Register (first user -> ADMIN)
curl -X POST localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"name":"Admin One","phone":"0100000001","password":"secret123"}'

# Login
curl -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"phone":"0100000001","password":"secret123"}'

# Create a room (use token from above)
curl -X POST localhost:8080/api/rooms -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"restaurantName":"Falafel House","restaurantPhone":"0111234567"}'

# Add an order — no price needed, the receipt decides
curl -X POST localhost:8080/api/rooms/1/orders -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"itemName":"Falafel Sandwich","quantity":2}'

# The verified menu for this room's restaurant (empty until an approval)
curl localhost:8080/api/rooms/1/menu -H "Authorization: Bearer <TOKEN>"

# Close the room, then enter the paper receipt once the food arrives
curl -X POST localhost:8080/api/rooms/1/close -H "Authorization: Bearer <TOKEN>"

curl -X POST localhost:8080/api/rooms/1/receipt -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"items":[{"name":"Falafel Sandwich","verifiedPrice":25}],
       "totalDelivery":45,"receiptTotal":95}'

# Review the queue and the split before committing
curl localhost:8080/api/admin/rooms/pending-approval -H "Authorization: Bearer <TOKEN>"
curl localhost:8080/api/admin/rooms/1/bill-preview -H "Authorization: Bearer <TOKEN>"

# Approve: locks the splits and writes the verified prices to the menu
curl -X POST localhost:8080/api/admin/rooms/1/approve -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"items":[{"name":"Falafel Sandwich","verifiedPrice":25}],"totalDelivery":45}'

# The menu now carries a verified price for next time
curl localhost:8080/api/rooms/1/menu -H "Authorization: Bearer <TOKEN>"
```

## Tests

`ApprovalWorkflowIntegrationTest` walks the whole flow against an in-memory H2
database: unpriced ordering, the role checks on every admin endpoint, receipt
entry, the refusal to approve an item the receipt did not price, approval, the
frozen approved room, and a second room picking up the saved menu.

```bash
mvn test
```
