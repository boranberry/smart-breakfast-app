# Smart Office Breakfast Ordering System

A complete, self-contained project: Spring Boot REST API + MySQL, with a
vanilla JS frontend served directly by the backend. One container image,
one database, one command to run.

```
smart-breakfast-app/
├── docker-compose.yml       MySQL + backend, wired together
├── .env.example             Copy to .env and fill in secrets
└── backend/
    ├── Dockerfile
    ├── pom.xml
    ├── src/main/java/...    REST API (auth, rooms, orders, billing, scheduler)
    └── src/main/resources/
        ├── application.yml
        └── static/          Frontend (index.html, css/, js/) — served at "/"
```

The frontend and API are now a single deployable unit: Spring Boot serves
`index.html`, `css/`, and `js/` as static resources at the root path, and the
same origin handles `/api/**`. No CORS juggling, no separate frontend server,
no hardcoded API URL to keep in sync.

## Run it (Docker — recommended)

Requires Docker and Docker Compose. This handles the database for you: MySQL
runs in its own container with a persistent volume, and the backend waits
for it to be healthy before starting.

```bash
cp .env.example .env
# edit .env — at minimum set JWT_SECRET (openssl rand -base64 32) and DB_PASSWORD

docker compose up --build
```

Then open **http://localhost:8080** — that's the whole app, frontend and API
together. The first account you register becomes an admin automatically.

Useful commands:

```bash
docker compose up -d --build     # run in the background
docker compose logs -f backend   # tail backend logs
docker compose down              # stop everything, keep the database volume
docker compose down -v           # stop everything and wipe the database
```

## Run it without Docker

You'll need Java 17+, Maven, and a running MySQL (or PostgreSQL) server
yourself.

```bash
# 1. Create the database
mysql -u root -p -e "CREATE DATABASE breakfast_db;"

# 2. Configure connection + secret (or edit backend/src/main/resources/application.yml directly)
export DB_USERNAME=root
export DB_PASSWORD=your_password
export JWT_SECRET=$(openssl rand -base64 32)

# 3. Run
cd backend
mvn spring-boot:run
```

Open **http://localhost:8080** — same single-container experience, just
running the jar directly instead of via Docker.

To switch to PostgreSQL instead of MySQL, see the commented-out block in
`backend/src/main/resources/application.yml`.

## Database

Tables are created and kept up to date automatically by Hibernate
(`spring.jpa.hibernate.ddl-auto: update`) — there's no manual migration step.
Schema matches the original spec (`users`, `rooms`, `live_orders`) plus two
additive nullable columns on `rooms` (`total_delivery_fee`, `finalized_at`)
used to record what "Finalize the room" actually produced. See
`backend/README.md` for the full column-by-column notes and API reference.

In Docker, MySQL data persists in the `mysql_data` named volume across
restarts — `docker compose down` alone won't lose your data, only
`docker compose down -v` will.

## What's inside

- **Auth** — JWT-based, BCrypt-hashed passwords, first registrant becomes admin.
- **Rooms** — 60-minute countdown, auto-closed by a `@Scheduled` background job
  (checked every minute), plus a just-in-time expiry check on every write so
  nothing sneaks in between ticks.
- **Orders** — instant-save per item (no data loss on a closed tab), personal
  live cart, admin aggregation view for phoning in the order.
- **Billing** — exact split algorithm from the spec: unique participants →
  equal delivery share → per-user `foodSubtotal + deliveryShare`, finalizing
  and closing the room.
- **Frontend** — a "kitchen order ticket" themed SPA (dotted receipt lines,
  monospace pricing, perforated card edges) covering every view from the
  spec: Auth, Dashboard, Active Room, Admin Summary, Final Invoice.

Full API reference, design rationale, and known simplifications are in
`backend/README.md`.
