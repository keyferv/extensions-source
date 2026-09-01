## 2026-04-25T22:08:00Z Task: init
Initialized notepad file for accumulated learnings.

## 2026-04-25T22:30:00Z Task: Add Custom Logging Interceptor
- Successfully implemented custom logging interceptor in OlympusScanlation.kt
- Interceptor logs request method/URL/headers and response code + peekBody(1024) snippet
- Logging is conditional: only triggers when request host contains "dashboard"
- Uses Log.d("OlympusScanlation", "[OlympusDebug] ...") format as specified
- Interceptor is observational only: chain.proceed(request) called exactly once, no request/response mutation
- Existing rateLimitHost calls and fallback behavior preserved
- Kotlin code follows existing style patterns in the file

## 2026-04-25T22:56:00Z Task: Endpoint migration + validation
- `/api/public-series` was invalid (404); corrected strategy to HTML-first for popular/latest and `/api/series` for search/details.
- Verified status matrix: dashboard `/api/sf/home` and `/api/sf/new-chapters` => 401, public `/api/series` endpoints => 200, dashboard chapter list => 200.
- Recommended Windows live capture command:
  `adb logcat -v time OlympusScanlation:D OkHttp:D *:S | findstr /I /R "OlympusDebug HTTP issue 401 Missing signature headers"`
