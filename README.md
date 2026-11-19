# Finance Tracker (Catatan Keuangan) — Android, IDR

Offline personal finance tracker for Android 8.0+ (API 26). Native Kotlin, SQLite, MVVM, no internet permission, no backend.
UI language: English or Bahasa Indonesia, chosen in Settings (default: follow the phone).

## Changelog

**1.1.0** (versionCode 2) — installs over 1.0.0, keeps all data (no database change)
- Fingerprint lock: Settings → Security → Fingerprint lock. Fallback = app PIN if set, otherwise the phone's screen lock
  (PIN/pattern/password). Android 9+ uses the system fingerprint dialog; Android 8.x uses the sensor directly on the lock screen.
  Turning it off requires fingerprint or the fallback. If the phone later has neither fingerprint nor screen lock, the lock
  turns itself off rather than locking you out of your own data.
- Language setting: Settings → Appearance → Language (Follow phone / English / Bahasa Indonesia). Applies to every screen,
  system date/time pickers, notifications and Excel/CSV exports; saved across restarts. Default wallet names
  ("Cash"/"Tunai" etc.) switch too unless you renamed them. The launcher label still follows the phone language (Android limitation).
- Show/hide amounts: eye icon next to the dashboard balance. Hidden masks every amount on every screen (balances,
  income, expense, budgets, goals, history, reports, chart labels) as `Rp••••••`. Session-only: resets to visible when the
  app is restarted. Edit forms and exports always show real values.

**1.0.0** — first release

## Features → spec mapping

| Spec | Where |
|---|---|
| Income & expense input, edit, delete | `screens/TxEditActivity.kt` (+ button in the bottom bar) |
| Monthly report with charts, highest/lowest expenses | `screens/ReportsTab.kt` (daily bars, 6-month income vs expense, category ranking, largest/smallest expenses) |
| IDR default currency | `util/Core.kt` `Money` (stored as whole rupiah in `Long`, shown as `Rp1.250.000`) |
| Predefined + custom categories | `data/Models.kt` `Defaults`, `screens/Manage.kt` `CategoriesActivity` (delete reassigns existing transactions) |
| Monthly budget per category | `BudgetsActivity`; dashboard card shows budget, used, % |
| Remaining balance | Dashboard: total balance across wallets and "this month income - expense" |
| History filters (date range, category, type) + search | `screens/HistoryTab.kt` (also wallet filter) |
| Recurring transactions | `data/Recurring.kt` + `RecurringActivity`; auto-recorded on app start and by a background job every 6 h |
| Multiple wallets, transfers | `WalletsActivity`; transfers are type `TRANSFER` and never count as income/expense |
| Savings goals | `GoalsActivity` (contributions, withdrawals, monthly amount needed for a target date) |
| Month-over-month trend, top 5 highest / lowest categories | Reports tab |
| Calendar view | `CalendarActivity` |
| Export Excel / CSV | `data/Export.kt` — real .xlsx (dates, Rp number format, frozen header, autofilter, summary sheet) and UTF-8 CSV |
| Backup & restore | `data/Files.kt` `Backup` — one .zip with the database + receipt photos, validated before restoring |
| Dark mode | Settings → Theme (follow phone / light / dark) |
| PIN / fingerprint lock | `util/Auth.kt`, Settings → Security; fingerprint with PIN or phone screen-lock fallback, PBKDF2-hashed PIN, lockout after 5 wrong tries, screenshots blocked while any lock is on |
| Language | `util/Core.kt` `L` + `util/Auth.kt` `Lang`; all text goes through `tr(en, id)` |
| Show / hide amounts | `util/Core.kt` `Privacy` — masking happens in `Money.fmt/compact`, so every screen follows it |
| Receipt photos | Camera or gallery, downscaled to 1600 px JPEG, stored privately, included in backups |
| Financial insights | `data/Insights.kt` — rule-based (savings rate, pace projection, category spikes, budgets, weekend share, small purchases) |
| Reminders | Daily "record your spending" reminder, budget alerts (threshold + exceeded), upcoming recurring payments |

## Stack actually used (and why it differs from the brief)

The build sandbox could not download Jetpack Compose, Room, Vico, WorkManager or DataStore (Google Maven and Maven Central were blocked).
Rather than ship untested code, the app uses only the Android framework, so it could be compiled, packaged and signed here:

| Brief | Used | Note |
|---|---|---|
| Jetpack Compose | Framework Views built in Kotlin (`ui/Ui.kt` helpers) | No XML layouts |
| Room | `SQLiteOpenHelper` + hand-written SQL (`data/Repo.kt`) | Same SQLite file format |
| Vico | Custom Canvas charts (`ui/Charts.kt`) | |
| WorkManager | `JobScheduler` + `AlarmManager` (`util/System.kt`) | WorkManager wraps these on API 23+ |
| DataStore | `SharedPreferences` | |
| MVVM | Minimal `ViewModel` / `Live` in `ui/Base.kt`; screens observe state, `Repo` is the single data source | |

Result: a 275 KB APK (a Compose + Room app is typically 5–10 MB).

## Build

Requirements: JDK 17+, `kotlinc` 2.x, `aapt`, `dalvik-exchange` (dx), `proguard`, `zipalign`, `apksigner`, and an API 34 `android.jar`.
On Ubuntu 24.04: `apt install aapt apksigner zipalign dalvik-exchange proguard-cli` + Kotlin compiler zip from GitHub.

```
ANDROID_JAR=/path/to/android-34.jar ./build.sh
```

Output: `build/FinanceTracker-<version>.apk`.

## Signing key — important

`keystore/release.jks` + `keystore/keystore.properties` are the app's signing key. **Keep them private and don't lose them**:
Android only installs an update over the existing app if it is signed with the same key. A differently signed build
forces an uninstall, which deletes all data on the phone (back up first).

## Tests

- `test/LogicTests.kt` — money formatting/parsing, recurring date maths (month-end, leap years, catch-up, end dates),
  CSV escaping and formula-injection guard, XLSX generation, insights. Run on the JVM against the compiled classes.
- `test/api_check.py` — scans `classes.dex` for framework APIs missing on API 26 (only biometrics are flagged, and they are behind SDK checks).
- Generated .xlsx files were validated with openpyxl and LibreOffice.

Not done: running the app on a device or emulator (not available in the build environment).
If something crashes, the next launch shows the error with a "Copy details" button.

## Project layout

```
src/com/fintrack/idr/
  App.kt                 Application: init, schedules background work
  data/                  Db (schema), Repo (all queries), Recurring, Insights, Export, Files (receipts, backup)
  ui/                    Theme (palette), Ui (view helpers, dialogs), Icons, Charts, Base (ViewModel/Live/BaseActivity), Rows
  screens/               MainActivity + tabs (Home, History, Reports, More), TxEdit, Manage (wallets, categories,
                         budgets, goals, recurring), Other (calendar, settings, lock, receipt viewer), Flows (export, backup)
  util/                  Core (language, money, dates, prefs, PIN hashing, crash log), System (notifications, jobs, lock, file provider)
res/                     themes (light/night), launcher + notification icons, app name (en / in)
```
