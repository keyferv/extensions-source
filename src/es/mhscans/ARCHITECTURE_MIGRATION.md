# MHScans Architecture & Migration Analysis

## 1. Context & Motivation
MHScans migrated from WordPress/Madara to a custom stack built with Astro (SSR + client hydration).
Old WordPress routes (`/series/{slug}/` and `/series/{slug}/capitulo-{n}/`) are not redirected by the server (they return HTTP 404).

## 2. Source Identity & ID Preservation
- **Source Name:** `MHScans`
- **Language:** `es`
- **Source ID:** `3253304651550496369L`
- **Requirement:** Migrating from `Madara()` to `KeiSource()` without changing `name` or `lang` in `build.gradle.kts` ensures KSP generates the exact same 64-bit source ID, keeping all library entries linked to this extension.

## 3. URL Architecture & Normalization

### Manga URLs
- **Old format:** `/series/{slug}/` (e.g., `/series/mis-discipulos-son-todos-grandes-villanos/`)
- **New format:**
  - Migrated titles: `/comics/mhscans-{slug}` (the site prepended `mhscans-` to all pre-migration slugs).
  - New titles created after migration: `/comics/{slug}`.
- **Backward Compatibility Mapping:**
  ```kotlin
  fun normalizeMangaUrl(rawUrl: String): String {
      val path = rawUrl.removePrefix(baseUrl).trimStart('/')
      if (path.startsWith("series/")) {
          val slug = path.removePrefix("series/").trim('/')
          return if (slug.startsWith("mhscans-")) "/comics/$slug" else "/comics/mhscans-$slug"
      }
      return if (path.startsWith("/")) path else "/$path"
  }
  ```
  `mangaDetailsRequest`, `chapterListRequest`, and `getMangaUrl` run this normalization so users with existing library items do not receive 404 errors.

### Chapter URLs
- **Old format:** `/series/{slug}/capitulo-{num}/`
- **New format:** `/leer/{chapterId}` (e.g., `/leer/ZNzCmoVRMbmw7RBmHWuYp`)
- **Old URL fallback in Reader:**
  If `chapter.url` matches an old `/series/` pattern, `fetchPageList` queries `/comics/{slug}/chapters?q={num}`, extracts the matching `/leer/{id}` link, and fetches its images seamlessly.

## 4. Endpoints & DOM Selectors

### A. Popular & Latest Manga
- **Popular:** `GET /library?sort=views&page={page}`
- **Latest:** `GET /library?sort=updated&page={page}`
- **Item selector:** `a.comic-card`
  - URL: `attr("abs:href")` -> `/comics/...`
  - Title: `img.comic-cover__img[alt]` or `p.line-clamp-2`
  - Cover: `img.comic-cover__img[src]` or `img.comic-cover__img[data-src]`
- **Pagination:** 24 items per page. Check if cards are present.

### B. Search & Filtering
- **Query Search:** `GET /search?q={query}&page={page}`
- **Filtered Search:** `GET /library?sort={sort}&status={status}&type={type}&genre={genre}&page={page}`
  - Sorts: `views`, `updated`, `rating`, `title`
  - Statuses: `Ongoing`, `Completed`, `Cancelled`, `Hiatus`
  - Types: `Manga`, `Manhwa`, `Manhua`, `Doujinshi`, `Novel`, `Other`
- **Results selector:** identical `a.comic-card` selector.

### C. Manga Details
- **Page:** `GET /comics/{slug}`
- **Title:** `h1`
- **Cover:** `.comic-hero img.comic-cover__img` or `.comic-cover img`
- **Description:** `#comic-description`
- **Status:** `.comic-meta__status`
  - "En emisión" -> `SManga.ONGOING`
  - "Finalizado" -> `SManga.COMPLETED`
  - "Hiatus" -> `SManga.ON_HIATUS`
  - "Cancelado" -> `SManga.CANCELLED`
- **Genres:** `.comic-chips li.comic-chip` plus `.comic-meta > span` tags
- **Author/Artist/Group:** `.flex:has(img[alt]) a[href^='/groups/']`

### D. Chapter List & Pagination
- **Endpoint:** `GET /comics/{slug}/chapters?page={page}`
- **Headers:**
  - `X-Total`: Total chapter count
  - `X-Page`: Current page number
  - `X-Pages`: Total page count
- **Ordering:**
  - Default (descending: latest to earliest).
- **Chapter Link Selector:**
  - Valid chapters: `a[href^='/leer/']`
  - Paywalled / coin chapters: `a[href^='/auth/login']` (omitted from reader list)
- **Data Attributes:**
  - Chapter number: `attr("data-chapter-num")` (exact decimal, e.g., `376`, `0.1`)
  - Chapter name: `attr("data-chapter-label")` (e.g., `Capítulo 376`)
  - Upload date: relative text string (e.g., `Hace 4 días`, `Hace 1 sem`) parsed to epoch millis.

### E. Page List & Reader
- **Page:** `GET /leer/{chapterId}`
- **Image selector:** `img.page-img`
- **Attributes:** `data-src` (lazy-loaded) or `src`
- **CDN:** Cloudflare-backed image servers (e.g., `https://257-2.mhscans.com/...`), HTTP 200 with standard headers.

## 5. Reading Progress Preservation (Tachiyomi/Mihon Mechanism)
Tachiyomi's `SyncChaptersWithSource.kt` handles chapter list synchronizations:
1. When chapter URLs change, the old database entries are identified as `removedChapters`.
2. Tachiyomi inspects `removedChapters` where `read == true` and collects `deletedReadChapterNumbers`.
3. If new incoming chapters have a matching `chapter_number` (recognized via `chapter.chapter_number`), Tachiyomi automatically copies `read = true` and `bookmark = true` to the new chapter entries.
4. Therefore, extracting `chapter_number` accurately using `data-chapter-num` guarantees that users' reading progress is preserved across the migration.
