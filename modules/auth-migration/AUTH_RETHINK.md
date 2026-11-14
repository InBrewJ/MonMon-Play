# Stage 1: Auth Rethink & Lightweight OIDC Architecture

## 1. The Problem with Keycloak 12.0.2 on Greenstreet (4GB RPi 5)

| Metric | Keycloak 12.0.2 (Current) | Target OIDC Provider (Rauthy / Pocket ID) |
| :--- | :--- | :--- |
| **Runtime** | Java 11 / WildFly 21 JBoss | **Rust native binary** or **Go** |
| **RAM Footprint** | **~550 MB – 700 MB RSS** | **~25 MB – 35 MB RSS** |
| **Startup Time** | ~45 – 90 seconds | **< 100 milliseconds** |
| **Maintenance** | Deprecated 2021 release | Active, modern standards (Passkeys, WebAuthn, OAuth 2.1) |
| **Host Impact** | Starves PostgreSQL buffer cache & forces swapping | Negligible CPU/memory impact |

---

## 2. Open-Source OIDC Provider Evaluation for ARM64

### Option A: **Rauthy** (Recommended ★★★★★)
- **Repo**: `https://github.com/sebadob/rauthy`
- **Language**: **100% Rust**
- **RAM**: **~25 MB**
- **Why it fits MonMon**:
  1. Full OpenID Connect 1.0 Provider (RFC 6749, RFC 7636 PKCE, OpenID Discovery `.well-known/openid-configuration`).
  2. First-class Passkey / WebAuthn support, Argon2id passwords, and optional TOTP MFA.
  3. Uses PostgreSQL directly (can connect to `127.0.0.1:5433` on Greenstreet) or embedded SQLite/Hiqlite.
  4. Native multi-arch Docker image `sebadob/rauthy:latest` (ARM64 tested and optimized).
  5. Drops memory usage by **~650 MB** immediately.

### Option B: **Pocket ID** (Alternative ★★★★☆)
- **Repo**: `https://github.com/stonith404/pocket-id`
- **Language**: Go + Svelte
- **RAM**: **~20 MB**
- **Why it fits MonMon**:
  - Ultra-minimalist OIDC provider designed explicitly for home labs and small server setups.
  - Pure Passkey / WebAuthn authentication (no forgotten passwords).
  - Caveat: Requires every client device to support WebAuthn / Passkeys.

### Option C: **Keycloak 26 (Quarkus)** (Not Recommended)
- Still requires **~350–450 MB RAM** on Java 21. While better than Keycloak 12, running a full enterprise identity platform for a single personal finance instance is wasteful on a 4GB Pi.

---

## 3. Decoupling Session State: The Dual-Language Session Bridge

Currently, `MonMon-Play` uses `pac4j` which writes serialized Java objects into Play's cache. A Rust service cannot read Java serialization.

### The Unified Session Cookie Contract (`monmon_session`)

To enable the "Ship of Theseus" migration where routes move from Java to Rust incrementally:

1. **Format**: Compact Signed JWT (HMAC-SHA256 with `APPLICATION_SECRET` / `PLAY_SECRET_KEY` from `.env`).
2. **Payload**:
   ```json
   {
     "sub": "u-12345",
     "preferred_username": "inbrewj",
     "email": "jason@urawizard.com",
     "roles": ["ROLE_ADMIN"],
     "access_status": "APPROVED",
     "iat": 1794069500,
     "exp": 1795279100
   }
   ```
3. **Cookie Attributes**:
   - `Name`: `monmon_session`
   - `HttpOnly`: `true`
   - `SameSite`: `Lax`
   - `Secure`: `true` (in production)
   - `Path`: `/`

### Reading in Rust (`axum` middleware):
```rust
// In monmon-rs: Zero-allocation JWT decode using `jsonwebtoken`
let claims = decode::<SessionClaims>(
    cookie_value,
    &DecodingKey::from_secret(jwt_secret.as_bytes()),
    &Validation::new(Algorithm::HS256)
)?;
```

### Reading in Java (`MonMon-Play` filter / controller):
```java
// In MonMon-Play: Standard JJWT decode (already in build.sbt)
Claims claims = Jwts.parser()
    .setSigningKey(jwtSecret.getBytes(StandardCharsets.UTF_8))
    .parseClaimsJws(cookieValue)
    .getBody();
```

---

## 4. Rollout Steps for Stage 1

1. **Step 1**: Stand up `Rauthy` on Greenstreet on port `:8080` (or behind Nginx at `identity.dryark.uk`).
2. **Step 2**: Configure MonMon's OIDC client:
   - `OIDC_DISCOVERY_URI=https://identity.dryark.uk/.well-known/openid-configuration`
   - `OIDC_CLIENT_ID=monmon-web`
   - `OIDC_CLIENT_SECRET=<secret>`
3. **Step 3**: Introduce the signed `monmon_session` cookie alongside `pac4j` callback so both Java and Rust receive the shared session.
4. **Step 4**: Decommission the Keycloak 12 container (`docker stop keycloak && docker rm keycloak`), freeing ~600MB of RAM on Greenstreet.
