# Work Plan: OlympusScanlation HTTP Logging & 401 Debugging

## Objective
Add non-strict HTTP logging to the OlympusScanlation extension to diagnose 401 Unauthorized errors from the dashboard API. The goal is to observe the exact requests (headers, URLs) being sent by the app and compare them with manual API tests using a valid browser session to identify missing headers/cookies.

## Scope
**IN SCOPE:**
- Injecting a custom logging `NetworkInterceptor` in `OlympusScanlation.kt` using `android.util.Log`.
- Compiling the debug APK.
- Providing a robust `adb logcat` command.
- Performing `curl` tests against the API using user-provided session data.

**OUT OF SCOPE:**
- Modifying `libs.versions.toml` or `build.gradle` to add dependencies (e.g., `okhttp3-logging-interceptor`).
- Fixing the 401 errors (this plan is strictly for diagnosis; the fix will be a follow-up).
- Changing existing 401 fallback logic.

## Technical Approach
1. **Custom Interceptor**: Since `HttpLoggingInterceptor` is not in the dependency graph, we will add an inline `addNetworkInterceptor` to the `OkHttpClient` builder. It will log the request URL, method, headers, and response code, plus a peeked body snippet (using `peekBody(1024)` to avoid consuming the stream).
2. **Windows Build**: Compile using `.\gradlew.bat :src:es:olympusscanlation:assembleDebug`.
3. **API Testing**: Use `curl` to binary-search which specific header (Referer, Cookie, User-Agent, etc.) is required by the `dashboard.olympus...` API to return a 200 OK.

## TODOs
- [x] Add custom dashboard-only logging interceptor to `OlympusScanlation.kt` client.
- [x] Migrate requests away from `/api/sf/*` and `/api/search` to public API/HTML fallbacks as specified.
- [x] Compile extension with `./gradlew :src:es:olympusscanlation:assembleDebug` and confirm success.
- [x] Validate API behavior matrix via curl (`/api/sf/*` -> 401, public series endpoints -> 200).
- [x] Validate with Oracle-equivalent safety review that logs/interceptor do not break flow (Oracle agent blocked; manual architecture QA executed and documented).
- [x] Provide robust adb/logcat commands and evidence summary.

## Implementation Steps

### 1. Add Custom Logging Interceptor
- **File**: `src/es/olympusscanlation/src/eu/kanade/tachiyomi/extension/es/olympusscanlation/OlympusScanlation.kt`
- **Action**: Modify the `client` definition (around line 90).
- **Details**: Add `.addNetworkInterceptor { chain -> ... }` before `.build()`.
  - Filter for `request.url.host.contains("dashboard")`.
  - Log `"[OlympusDebug] REQUEST: ${request.method} ${request.url} Headers: ${request.headers}"`.
  - Proceed with `chain.proceed(request)`.
  - Log `"[OlympusDebug] RESPONSE: ${response.code} Body snippet: ${response.peekBody(1024).string()}"`.
  - Use `android.util.Log.d("OlympusScanlation", ...)` for all logs.

### 2. Compile the Extension
- **Command**: Run `.\gradlew.bat :src:es:olympusscanlation:assembleDebug` in the terminal.
- **Validation**: Ensure BUILD SUCCESSFUL and the APK is generated without Kotlin compilation errors.

### 3. Provide Logcat Command
- **Command**: Give the user the following command to run on their machine while testing the app:
  `adb logcat -s OlympusScanlation:* *:S | findstr /I "OlympusDebug"` (since it's Windows).

### 4. Perform Manual API Testing
- **Action**: Use the provided browser headers/cookies (redacted in logs) and run a strict matrix to isolate real auth requirements.
- **Execution (PowerShell + curl.exe)**:
  1. `GET https://dashboard.olympusbiblioteca.com/api/sf/home` with no headers.
  2. Same request with full browser-like headers + cookies.
  3. `GET https://dashboard.olympusbiblioteca.com/api/sf/new-chapters?page=1` with/without full headers.
  4. `GET https://dashboard.olympusbiblioteca.com/api/series/{slug}/chapters?page=1&direction=desc&type=comic` with no headers.
  5. `GET https://olympusbiblioteca.com/api/series/{slug}?type=comic` with no headers.
- **Observed Results (already validated)**:
  - `/api/sf/home` => `401` (with and without full headers/cookies).
  - `/api/sf/new-chapters?page=1` => `401` (with and without full headers/cookies).
  - `/dashboard/api/search?name=test` => `404`.
  - `/dashboard/api/series/{slug}/chapters?...` => `200` even with no headers.
  - `/api/series/{slug}?type=comic` (main host) => `200`.
- **Interpretation**:
  - The `401` is **not** caused by missing browser cookies alone.
  - The response body on `/api/sf/home` returns `{"message":"Missing signature headers."}`.
  - Root cause is likely a server-required signature/header scheme (non-public or computed), not solvable by simply forwarding cookies.

### 5. Oracle Validation (No-Flow-Break Guardrails)
- Validate interceptor design before implementation:
  - Must use `addNetworkInterceptor` and keep `chain.proceed(request)` exactly once.
  - Must use `peekBody(1024)` (or smaller) only; never consume `response.body.string()`.
  - Must never mutate request/response in debug logging path.
- Expected Oracle outcome:
  - Logging layer is observational only and does not alter behavior.
  - Any persisted `401` on `/api/sf/*` is attributed to upstream signature requirements, not client breakage.

### 6. Robust ADB/Logcat Command (Windows)
- Use this command during live app navigation to capture 401 in real time:
  - `adb logcat -v time OlympusScanlation:D OkHttp:D *:S | findstr /I /R "OlympusDebug HTTP issue 401 Missing signature headers"`
- Optional full capture to file:
  - `adb logcat -v threadtime OlympusScanlation:D OkHttp:D *:S > olympus-401.log`

### 7. Phase 2 Decision (SELECTED): Evitar `/api/sf/*`
- **Decision confirmed by user**: stop relying on `/api/sf/home` and `/api/sf/new-chapters` because they require undisclosed signature headers.
- **Execution design (decision-complete)**:
  1. **Split API hosts in source logic**:
     - `publicApiBaseUrl = fetchedDomainUrl` (main host, public API works for series listing/details).
     - `dashboardApiBaseUrl = fetchedDomainUrl.replace("https://", "https://dashboard.")` (keep only for chapter/page endpoints that need dashboard).
  2. **Popular manga**:
     - Replace request path from `dashboard.../api/sf/home` to HTML-first strategy (`baseUrl` page + existing scraping parser path).
     - Keep existing `fetchPopularMangaByScraping()` as canonical source.
  3. **Latest updates**:
     - Replace `dashboard.../api/sf/new-chapters` strategy with HTML-first `baseUrl/capitulos` strategy.
     - Reuse existing `fetchLatestUpdatesByScraping(page)` logic as canonical source.
  4. **Search and browse list**:
     - Remove dependency on `dashboard.../api/search` (observed 404/401 behavior).
     - Use `publicApiBaseUrl/api/series` for query and non-query browsing/filtering.
  5. **Manga details**:
     - Change details fetch from `dashboardApiBaseUrl/api/series/{slug}?type=comic` to `publicApiBaseUrl/api/series/{slug}?type=comic` (validated 200 in live test).
  6. **Chapter list / page list**:
     - Keep dashboard routes where they currently return 200 (`/api/series/{slug}/chapters...` and chapter-page endpoint) unless logs show new 401s.
  7. **Telemetry guardrail**:
     - Keep debug logging enabled around all dashboard calls to detect future signature-policy changes.
  8. **Secret hygiene**:
     - Do not hardcode cookies/tokens in source or tests; use runtime/manual testing only.

## Final Verification Wave
1. Ensure the `client` in `OlympusScanlation.kt` compiles cleanly with the new interceptor.
2. Confirm `curl` matrix outcomes remain consistent (`/api/sf/*` -> `401`, `/api/series/*` -> `200`).
3. Confirm `401` body includes `Missing signature headers` and capture this in evidence notes.
4. Confirm Phase 2 endpoint migration plan is reflected in implementation diff (no remaining active requests to `/api/sf/home` or `/api/sf/new-chapters`).
5. Present findings and require explicit user **"okay"** before closing diagnostic phase.
