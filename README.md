# bvb-selenium-tools

Standalone Maven project that contains all Selenium implementations moved out of `matrix-from-uploadtext`.

## Included classes

- `com.delgaz.matricecp.entities.matrix.BvbBonds`
- `com.delgaz.matricecp.entities.matrix.BvbBondsQuickDownloadCsvMain`

## Included scripts

- `scripts/setup-chromedriver.ps1`
- `scripts/setup-edgedriver.ps1`
- `scripts/setup-geckodriver.ps1`

## Requirements

- JDK 11+
- Maven 3.9+ (optional but recommended)
- Microsoft Edge / Chrome / Firefox, depending on selected workflow

## Quick run (PowerShell)

```powershell
Set-Location "C:\Cristian_Work_Java\Repozitories\MiniApps_matrix_Eon_GIT\matrix-from-uploadtext\bvb-selenium-tools"
mvn -DskipTests compile
```

Run `BvbBonds`:

```powershell
Set-Location "C:\Cristian_Work_Java\Repozitories\MiniApps_matrix_Eon_GIT\matrix-from-uploadtext\bvb-selenium-tools"
mvn exec:java "-Dexec.mainClass=com.delgaz.matricecp.entities.matrix.BvbBonds" "-Dbvb.browser=edge" "-Dbvb.keepOpen=true"
```

Run `BvbBondsQuickDownloadCsvMain`:

```powershell
Set-Location "C:\Cristian_Work_Java\Repozitories\MiniApps_matrix_Eon_GIT\matrix-from-uploadtext\bvb-selenium-tools"
mvn exec:java "-Dexec.mainClass=com.delgaz.matricecp.entities.matrix.BvbBondsQuickDownloadCsvMain" "-Dbvb.min.yield=7.00" "-Dbvb.open.ron=true" "-Dbvb.open.euro=true"
```

Run `UrlListOpener` (opens every URL from `url-lists/urls.txt` in its own tab):

```powershell
mvn exec:java "-Dexec.mainClass=com.delgaz.urlopener.helpers.UrlListOpener" "-Durls.browser=edge" "-Durls.file=url-lists/urls.txt"
```

- One URL per line; empty lines and lines starting with `#` are ignored.
- Options: `urls.file` / `URLS_FILE`, `urls.browser` / `URLS_BROWSER` (`edge`, `chrome`, `firefox`), `urls.keepOpen` / `URLS_KEEP_OPEN`, `urls.delayMs` / `URLS_DELAY_MS`.
- `urls.launcher=system` opens the URLs in your default browser (normal profile, no WebDriver) instead of Selenium.
- `urls.launcher=batch` opens each batch of `urls.batchSize` URLs (default 5) at once, as tabs in a new window of `urls.browser` (normal profile, no WebDriver), so the browser comes to front only once per batch. Use `urls.browserExe` if the browser isn't in its standard install location.
- `urls.delayMaxMs` makes the pause random between `urls.delayMs` and `urls.delayMaxMs`; `urls.batchSize=N` pauses automatically after every N tabs for a random time between `urls.batchPauseMs` and `urls.batchPauseMaxMs` (default 60000).

Run `MomondoMultiCityUrlGenerator` (generates every Momondo 4-flight multi-city combination on the allowed weekdays `FLIGHT_DAYS`, writes it to `url-lists/{month}_{itinerary}_momondo-multicity-urls_{timestamp}.txt`, then does what `AFTER_GENERATE` says: `READ_PRICES` (default) runs `FlightPriceScraper` on those URLs, `OPEN_IN_BROWSER` only opens them in the browser, `NOTHING` only writes the file). All the rules (routes, dates, allowed weekdays, distances in days) are constants at the top of the class:

```powershell
mvn exec:java "-Dexec.mainClass=com.delgaz.urlopener.MomondoMultiCityUrlGenerator" "-Durls.browser=edge"
```

Run `MomondoDailyRoundTripUrlGenerator` (simple round trip to a single destination `ROUTE`, departure on every day of the period, return after `TRIP_MIN_DAYS..TRIP_MAX_DAYS` days; writes to `url-lists/{month}_{route}_momondo-dus-intors-zilnic-urls_{timestamp}.txt`, then does what `AFTER_GENERATE` says, same as above):

```powershell
mvn exec:java "-Dexec.mainClass=com.delgaz.urlopener.MomondoDailyRoundTripUrlGenerator" "-Durls.browser=edge"
```

Run `FlightPriceScraper` (loads every URL from `MomondoMultiCityUrlGenerator` in a Selenium Edge window, reads the first price, checks after the page finished loading whether it is the lowest price on the page, and writes the prices + URL to `url-lists/{month}_{itinerary}_momondo-multicity-preturi_{timestamp}.xlsx`, rewritten after each page). If no price is found, the program pauses: solve the verification manually in that window and press Enter in the console (`s` + Enter skips the page). The Edge profile is kept in `.browser-profile/momondo`. Selectors, timeouts and pauses are constants at the top of the class.

```powershell
mvn exec:java "-Dexec.mainClass=com.delgaz.urlopener.helpers.FlightPriceScraper"
```

By default it reads the multi-city URLs (`MomondoMultiCityUrlGenerator`). To read the daily round-trip URLs (`MomondoDailyRoundTripUrlGenerator`) instead, pass `-Dmomondo.search=DAILY_ROUND_TRIP`; the Excel file is then `url-lists/{month}_{route}_momondo-dus-intors-zilnic-preturi_{timestamp}.xlsx`, with the columns `Plecare` / `Intoarcere` instead of the 4 legs:

```powershell
mvn exec:java "-Dexec.mainClass=com.delgaz.urlopener.helpers.FlightPriceScraper" "-Dmomondo.search=DAILY_ROUND_TRIP"
```

## Driver setup scripts

```powershell
Set-Location "C:\Cristian_Work_Java\Repozitories\MiniApps_matrix_Eon_GIT\matrix-from-uploadtext\bvb-selenium-tools"
powershell -ExecutionPolicy Bypass -File ".\scripts\setup-edgedriver.ps1"
powershell -ExecutionPolicy Bypass -File ".\scripts\setup-chromedriver.ps1"
powershell -ExecutionPolicy Bypass -File ".\scripts\setup-geckodriver.ps1"
```

## Notes

- `BvbBondsQuickDownloadCsvMain` opens RON tabs first, then EURO tabs.
- Tabs with page YTM under the configured threshold are closed automatically.
- Use `-Dbvb.open.ron=false` or `-Dbvb.open.euro=false` to skip opening a list.

