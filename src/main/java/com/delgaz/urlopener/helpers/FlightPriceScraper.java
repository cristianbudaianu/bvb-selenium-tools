package com.delgaz.urlopener.helpers;

import com.delgaz.urlopener.MomondoDailyRoundTripUrlGenerator;
import com.delgaz.urlopener.MomondoMultiCityUrlGenerator;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deschide pe rand, intr-o fereastra Selenium (Edge), URL-urile generate de {@link MomondoMultiCityUrlGenerator}
 * sau de {@link MomondoDailyRoundTripUrlGenerator} (vezi {@link Search}), citeste primul pret din lista de rezultate, verifica (dupa incarcarea completa) daca e cel mai mic pret de pe
 * pagina si scrie preturile + URL-ul intr-un fisier Excel.
 *
 * Fisierul Excel e rescris dupa fiecare pagina, deci rezultatele partiale raman salvate daca opresti rularea.
 * Daca nu gaseste pretul: daca pagina e captcha, programul se opreste pana rezolvi verificarea manual si apesi
 * Enter in consola; altfel noteaza "Fara rezultate" si trece la urmatoarea pagina.
 */
public class FlightPriceScraper {

    // ---------------------------------------------------------------------
    // Citirea pretului
    // ---------------------------------------------------------------------
    /**
     * Elementele cu preturi din lista de rezultate, pe fiecare site (primul = primul rezultat); primul selector
     * care gaseste ceva castiga.
     */
    static final List<By> MOMONDO_PRICE_SELECTORS = List.of(
            By.cssSelector(".Oihj-bottom-booking .e2GB-price-text"),
            By.cssSelector(".e2GB-price-text")
    );
    /**
     * Skyscanner: {@code <span class="BpkText_bpk-text__MzMxN BpkText_bpk-text--heading-3__ZTA4N"
     * data-backpack-ds-component="Text">808 €</span>}. Sufixele claselor (__MzMxN, __ZTA4N) se schimba la
     * actualizarile site-ului, de aceea cautam doar dupa partea stabila a numelui.
     */
    static final List<By> SKYSCANNER_PRICE_SELECTORS = List.of(
            By.cssSelector("span[data-backpack-ds-component='Text'][class*='bpk-text--heading-3']")
    );

    /**
     * Trip.com: {@code <span data-price="359" data-testid="flight_price_1-0" class="f-20 ThemeColor1
     * o-price-flight_b825 no-cursor_1b76">US$359</span>}. Cautam dupa data-testid (stabil), nu dupa clase
     * (sufixele _b825 se schimba); asa nu luam nici preturile din calendarul de sus.
     */
    static final List<By> TRIP_PRICE_SELECTORS = List.of(
            By.cssSelector("[data-testid^='flight_price_']")
    );

    /**
     * Air China (pagina deepLinkToFare), totalul pentru tot itinerariul:
     * {@code <div class="ca-v4-flight-total"> ... <div class="... ca-r-fare-details-summary__right"><span>$ 859.10</span></div>}.
     */
    static final List<By> AIRCHINA_PRICE_SELECTORS = List.of(
            By.cssSelector(".ca-v4-flight-total .ca-r-fare-details-summary__right span")
    );

    static List<By> priceSelectors(FlightSite site) {

        return switch (site) {
            case MOMONDO -> MOMONDO_PRICE_SELECTORS;
            case SKYSCANNER -> SKYSCANNER_PRICE_SELECTORS;
            case TRIP -> TRIP_PRICE_SELECTORS;
            case AIRCHINA -> AIRCHINA_PRICE_SELECTORS;
            case KIWI -> throw new IllegalArgumentException("Citirea preturilor nu e implementata pentru " + site + ".");
        };
    }

    /** Un text e considerat pret doar daca are cifre si o moneda (ex. "808 €", "1.234 lei"). */
    private static final Pattern PRICE_TEXT = Pattern.compile(".*\\d.*(€|\\$|£|lei|RON|EUR|USD).*|.*(€|\\$|£|EUR|USD).*\\d.*",
            Pattern.CASE_INSENSITIVE);

    /** Cat asteptam primul pret dupa deschiderea paginii. */
    static final Duration FIRST_PRICE_TIMEOUT = Duration.ofSeconds(60);
    /**
     * Pagina e considerata incarcata cand lista de preturi ramane neschimbata atat timp
     * (Momondo incarca rezultatele progresiv).
     */
    static final Duration PRICE_STABLE_FOR = Duration.ofSeconds(10);
    /** Cat asteptam in total ca preturile sa se stabilizeze. */
    static final Duration PRICE_STABLE_TIMEOUT = Duration.ofSeconds(90);

    // ---------------------------------------------------------------------
    // Detectarea captcha (doar atunci oprim rularea si asteptam Enter)
    // ---------------------------------------------------------------------
    /** Pagina e captcha daca adresa ei contine unul dintre acestea... */
    static final List<String> CAPTCHA_URL_MARKERS = List.of("captcha", "sherlock", "security");
    /** ...sau daca sursa paginii contine unul dintre acestea. Nu folosi "robot": orice pagina are meta "robots". */
    static final List<String> CAPTCHA_PAGE_MARKERS = List.of("captcha");

    // ---------------------------------------------------------------------
    // Ritm
    // ---------------------------------------------------------------------
    /** Pauza aleatoare intre pagini, in secunde. */
    static final int PAUSE_BETWEEN_PAGES_MIN_SECONDS = 5;
    static final int PAUSE_BETWEEN_PAGES_MAX_SECONDS = 10;

    // ---------------------------------------------------------------------
    // Ce URL-uri citim
    // ---------------------------------------------------------------------
    /** De unde vin URL-urile; fiecare cautare are propriul fisier Excel si propriile coloane cu date. */
    public enum Search {
        /** {@link MomondoMultiCityUrlGenerator}: 4 zboruri, in zilele din FLIGHT_DAYS. */
        MULTI_CITY,
        /** {@link MomondoDailyRoundTripUrlGenerator}: dus-intors, o destinatie, plecare in fiecare zi. */
        DAILY_ROUND_TRIP;

        List<String> generateUrls() {

            return switch (this) {
                case MULTI_CITY -> MomondoMultiCityUrlGenerator.generateUrls();
                case DAILY_ROUND_TRIP -> MomondoDailyRoundTripUrlGenerator.generateUrls();
            };
        }

        /** Fisier nou la fiecare rulare (perioada + data-ora); in timpul rularii e rescris dupa fiecare pagina. */
        Path newExcelFile(FlightSite site) {

            String prefix = site.name().toLowerCase(Locale.ROOT);
            return switch (this) {
                case MULTI_CITY -> MomondoMultiCityUrlGenerator.newOutputFile(prefix + "-multicity-preturi", "xlsx");
                case DAILY_ROUND_TRIP -> MomondoDailyRoundTripUrlGenerator.newOutputFile(prefix + "-dus-intors-zilnic-preturi", "xlsx");
            };
        }

        /** Antetul coloanelor cu date: cate o coloana pentru fiecare zbor din URL. */
        List<String> dateHeaders() {

            return switch (this) {
                case MULTI_CITY -> List.of(MomondoMultiCityUrlGenerator.LEG1, MomondoMultiCityUrlGenerator.LEG2,
                        MomondoMultiCityUrlGenerator.LEG3, MomondoMultiCityUrlGenerator.LEG4);
                case DAILY_ROUND_TRIP -> List.of("Plecare " + MomondoDailyRoundTripUrlGenerator.ROUTE.trim(),
                        "Intoarcere " + reverseRoute(MomondoDailyRoundTripUrlGenerator.ROUTE));
            };
        }

        /**
         * Rand liber intre grupurile cu aceeasi data de plecare - doar la multi-city; la dus-intors zilnic
         * fiecare rand are alta data de plecare.
         */
        boolean groupByDeparture() {

            return this == MULTI_CITY;
        }
    }

    /**
     * Cautarea folosita cand pornesti direct aceasta clasa; se poate schimba din linia de comanda:
     * -Dmomondo.search=DAILY_ROUND_TRIP (sau MULTI_CITY) si -Dmomondo.site=SKYSCANNER (sau MOMONDO).
     * Mai simplu: ruleaza generatorul dorit cu AFTER_GENERATE = READ_PRICES.
     */
    static final Search DEFAULT_SEARCH = Search.valueOf(
            System.getProperty("momondo.search", Search.MULTI_CITY.name()).trim().toUpperCase(Locale.ROOT));
    static final FlightSite DEFAULT_SITE = FlightSite.valueOf(
            System.getProperty("momondo.site", FlightSite.MOMONDO.name()).trim().toUpperCase(Locale.ROOT));

    // ---------------------------------------------------------------------
    // Browser
    // ---------------------------------------------------------------------
    /** Profil Edge separat, pastrat intre rulari (cookie-uri, verificari deja rezolvate). */
    static final Path BROWSER_PROFILE_DIR = Path.of(".browser-profile/momondo");

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static String[] buildHeaders(List<String> dateHeaders) {

        List<String> headers = new ArrayList<>();
        headers.add("Nr");
        headers.addAll(dateHeaders);
        headers.addAll(List.of("Primul pret", "Cel mai mic pret", "Primul = minim", "Nr preturi", "Moneda",
                "Observatii", "Citit la", "URL"));
        return headers.toArray(new String[0]);
    }

    /** IAS-FNC -> FNC-IAS. */
    private static String reverseRoute(String route) {

        String[] airports = route.trim().split("-");
        return airports.length == 2 ? airports[1] + "-" + airports[0] : route.trim();
    }

    private static final Scanner CONSOLE = new Scanner(System.in, StandardCharsets.UTF_8);

    /** firstPrice / minPrice sunt null daca pagina a fost sarita fara pret. */
    record PriceResult(String url, Long firstPrice, Long minPrice, int priceCount, String currency, String note,
                       LocalDateTime readAt) {

        static PriceResult skipped(String url, String note) {
            return new PriceResult(url, null, null, 0, "", note, LocalDateTime.now());
        }
    }

    public static void main(String[] args) throws IOException {

        List<String> urls = DEFAULT_SEARCH.generateUrls();
        if (urls.isEmpty()) {
            throw new IllegalStateException("Nicio combinatie nu respecta conditiile.");
        }
        readPrices(DEFAULT_SEARCH, DEFAULT_SITE, urls);
    }

    /**
     * Citeste preturile pentru URL-urile date si le scrie in Excel. Apelata din main-ul acestei clase sau direct
     * din generatoare ({@link MomondoMultiCityUrlGenerator}, {@link MomondoDailyRoundTripUrlGenerator}).
     */
    public static void readPrices(Search search, FlightSite site, List<String> urls) throws IOException {

        List<By> selectors = priceSelectors(site);
        Path excelFile = search.newExcelFile(site);
        System.out.println("Cautare: " + search + " pe " + site + ", URL-uri de citit: " + urls.size());

        List<PriceResult> results = new ArrayList<>();
        WebDriver driver = createDriver();

        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(90));

            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                System.out.println("(" + (i + 1) + "/" + urls.size() + ") " + url);

                PriceResult result = readPrice(driver, url, selectors);
                results.add(result);
                System.out.println("    -> " + (result.firstPrice() == null
                        ? result.note()
                        : "primul pret " + result.firstPrice() + " " + result.currency()
                        + ", cel mai mic " + result.minPrice() + " " + result.currency()
                        + " (din " + result.priceCount() + " preturi)"
                        + (result.note().isEmpty() ? "" : " - " + result.note())));

                writeExcel(search, excelFile, results);

                if (i < urls.size() - 1 && !sleepSeconds(randomBetween(PAUSE_BETWEEN_PAGES_MIN_SECONDS, PAUSE_BETWEEN_PAGES_MAX_SECONDS))) {
                    break;
                }
            }
        } finally {
            driver.quit();
        }

        System.out.println("Gata. Rezultate: " + excelFile.toAbsolutePath());
    }

    static PriceResult readPrice(WebDriver driver, String url, List<By> selectors) {

        loadPage(driver, url);

        // Fara pret: daca e captcha ne oprim pana o rezolvi si apesi Enter; altfel nu exista zboruri, mergem mai departe.
        while (waitForPriceText(driver, selectors, FIRST_PRICE_TIMEOUT) == null) {
            if (!looksLikeCaptcha(driver)) {
                return PriceResult.skipped(url, "Fara rezultate");
            }

            System.out.println("    Site-ul cere verificare (captcha). Rezolv-o in fereastra Selenium, apoi:");
            System.out.println("      Enter      = reincearca pagina");
            System.out.println("      s + Enter  = sari peste pagina");
            String answer = CONSOLE.hasNextLine() ? CONSOLE.nextLine().trim() : "s";
            if (answer.equalsIgnoreCase("s")) {
                return PriceResult.skipped(url, "Sarit manual - captcha");
            }

            // Dupa verificare Momondo nu revine mereu la cautare; daca tot nu e pret, reincarcam.
            if (currentPriceText(driver, selectors) == null) {
                loadPage(driver, url);
            }
        }

        // Asteptam incarcarea completa, apoi comparam primul pret cu toate preturile de pe pagina.
        List<String> texts = waitForStablePrices(driver, selectors);
        if (texts.isEmpty()) {
            return PriceResult.skipped(url, "Preturile au disparut dupa incarcare");
        }

        Long firstPrice = parsePrice(texts.get(0));
        Long minPrice = texts.stream()
                .map(FlightPriceScraper::parsePrice)
                .filter(Objects::nonNull)
                .min(Long::compare)
                .orElse(firstPrice);

        String note = firstPrice != null && firstPrice.equals(minPrice)
                ? ""
                : "Primul pret NU e cel mai mic de pe pagina";
        return new PriceResult(url, firstPrice, minPrice, texts.size(), parseCurrency(texts.get(0)), note, LocalDateTime.now());
    }

    private static boolean looksLikeCaptcha(WebDriver driver) {

        try {
            String currentUrl = driver.getCurrentUrl().toLowerCase(Locale.ROOT);
            for (String marker : CAPTCHA_URL_MARKERS) {
                if (currentUrl.contains(marker)) {
                    return true;
                }
            }
            String source = driver.getPageSource().toLowerCase(Locale.ROOT);
            for (String marker : CAPTCHA_PAGE_MARKERS) {
                if (source.contains(marker)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void loadPage(WebDriver driver, String url) {

        try {
            driver.get(url);
        } catch (RuntimeException exception) {
            System.out.println("    Eroare la incarcare: " + exception.getMessage());
        }
    }

    /** Asteapta pana apare primul pret si il intoarce, sau null la timeout. */
    private static String waitForPriceText(WebDriver driver, List<By> selectors, Duration timeout) {

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String text = currentPriceText(driver, selectors);
            if (text != null) {
                return text;
            }
            if (!sleepSeconds(1)) {
                return null;
            }
        }
        return null;
    }

    /**
     * Momondo actualizeaza rezultatele cateva zeci de secunde; asteptam ca lista tuturor preturilor sa nu se mai
     * schimbe timp de PRICE_STABLE_FOR si o intoarcem (primul element = primul rezultat).
     */
    private static List<String> waitForStablePrices(WebDriver driver, List<By> selectors) {

        List<String> last = allPriceTexts(driver, selectors);
        long stableSince = System.nanoTime();
        long deadline = System.nanoTime() + PRICE_STABLE_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            if (System.nanoTime() - stableSince >= PRICE_STABLE_FOR.toNanos()) {
                break;
            }
            if (!sleepSeconds(1)) {
                break;
            }
            List<String> current = allPriceTexts(driver, selectors);
            if (!current.equals(last)) {
                last = current;
                stableSince = System.nanoTime();
            }
        }

        return last;
    }

    /** Toate preturile (text) din rezultate, in ordinea de pe pagina, de la primul selector care gaseste ceva. */
    private static List<String> allPriceTexts(WebDriver driver, List<By> selectors) {

        for (By selector : selectors) {
            List<String> texts = new ArrayList<>();
            try {
                for (WebElement element : driver.findElements(selector)) {
                    String text = element.getText().replace(' ', ' ').strip();
                    if (PRICE_TEXT.matcher(text).matches()) {
                        texts.add(text);
                    }
                }
            } catch (RuntimeException ignored) {
                // Pagina se re-randeaza; incercam din nou la urmatorul pas.
                continue;
            }
            if (!texts.isEmpty()) {
                return texts;
            }
        }
        return List.of();
    }

    private static String currentPriceText(WebDriver driver, List<By> selectors) {

        List<String> texts = allPriceTexts(driver, selectors);
        return texts.isEmpty() ? null : texts.get(0);
    }

    /**
     * "933 €" -> 933; "1.234 €" / "1,234 €" / "1 234 €" -> 1234;
     * cu zecimale (exact 2 cifre dupa ultimul punct/virgula): "$ 859.10" -> 859, "1.234,56 €" -> 1235 (rotunjit).
     */
    static Long parsePrice(String text) {

        Matcher decimals = DECIMAL_PRICE.matcher(text);
        if (decimals.find()) {
            String whole = decimals.group(1).replaceAll("[^0-9]", "");
            if (!whole.isEmpty()) {
                long value = Long.parseLong(whole);
                return Integer.parseInt(decimals.group(2)) >= 50 ? value + 1 : value;
            }
        }

        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static String parseCurrency(String text) {

        return text.replaceAll("[0-9.,\\s]", "").strip();
    }

    private static WebDriver createDriver() throws IOException {

        Files.createDirectories(BROWSER_PROFILE_DIR);
        closeBrowsersUsingProfile(BROWSER_PROFILE_DIR);

        EdgeOptions options = new EdgeOptions();
        // Workaround for DevTools websocket 403 on older Selenium clients.
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--user-data-dir=" + BROWSER_PROFILE_DIR.toAbsolutePath());

        // Selenium Manager gaseste/descarca singur msedgedriver.
        return new EdgeDriver(options);
    }

    /**
     * Inchide Edge-ul ramas deschis pe profilul Selenium (ex. rulare oprita din IDE inainte de driver.quit()).
     * Altfel Edge refuza al doilea start pe acelasi profil: "Microsoft Edge failed to start: crashed /
     * DevToolsActivePort file doesn't exist". Edge-ul tau normal (alt profil) nu e atins.
     */
    static void closeBrowsersUsingProfile(Path profileDir) {

        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }

        String profile = profileDir.toAbsolutePath().normalize().toString().replace("'", "''");
        // 1) Edge pornit pe profilul Selenium; 2) msedgedriver.exe orfani (procesul Java care i-a pornit nu mai exista).
        String script = "$ProgressPreference = 'SilentlyContinue'\n"
                + "$p = '" + profile + "'\n"
                + "$all = @(Get-CimInstance Win32_Process)\n"
                + "$alive = @{}; foreach ($x in $all) { $alive[[int]$x.ProcessId] = $true }\n"
                + "$edge = @($all | Where-Object { $_.Name -eq 'msedge.exe' -and $_.CommandLine -and "
                + "$_.CommandLine.IndexOf($p, [StringComparison]::OrdinalIgnoreCase) -ge 0 })\n"
                + "$drivers = @($all | Where-Object { $_.Name -eq 'msedgedriver.exe' -and -not $alive[[int]$_.ParentProcessId] })\n"
                + "foreach ($x in @($edge) + @($drivers)) { Stop-Process -Id $x.ProcessId -Force -ErrorAction SilentlyContinue }\n"
                + "Write-Output \"$($edge.Count) $($drivers.Count)\"\n";
        // -EncodedCommand (UTF-16LE Base64): ProcessBuilder nu escapeaza ghilimelele din argumente pe Windows.
        String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            process.waitFor();

            // Ultima linie de forma "<nr msedge> <nr msedgedriver>".
            String[] lines = output.split("\\R");
            String[] counts = lines[lines.length - 1].strip().split("\\s+");
            if (counts.length == 2 && counts[0].matches("\\d+") && counts[1].matches("\\d+")) {
                if (!counts[0].equals("0") || !counts[1].equals("0")) {
                    System.out.println("Am inchis browserul ramas deschis pe profilul Selenium: "
                            + counts[0] + " procese msedge, " + counts[1] + " msedgedriver orfani.");
                    sleepSeconds(2); // Edge elibereaza lock-ul profilului
                }
            } else if (!output.isEmpty()) {
                System.out.println("Verificarea browserelor deschise a esuat: " + output);
            }
        } catch (IOException exception) {
            System.out.println("Nu am putut verifica browserele deschise: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeExcel(Search search, Path excelFile, List<PriceResult> results) {

        List<String> dateHeaders = search.dateHeaders();
        String[] headers = buildHeaders(dateHeaders);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Preturi");
            CreationHelper helper = workbook.getCreationHelper();

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(boldFont);

            Font linkFont = workbook.createFont();
            linkFont.setUnderline(Font.U_SINGLE);
            linkFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.BLUE.getIndex());
            CellStyle linkStyle = workbook.createCellStyle();
            linkStyle.setFont(linkFont);

            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(headerStyle);
            }

            DateTimeFormatter readAtFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            int rowIndex = 0;
            String previousDeparture = null;
            for (int r = 0; r < results.size(); r++) {
                PriceResult result = results.get(r);
                List<String> dates = extractDates(result.url());

                // Rand liber intre grupurile cu aceeasi data de plecare (zborul 1).
                String departure = dates.isEmpty() ? "" : dates.get(0);
                if (search.groupByDeparture() && previousDeparture != null && !departure.equals(previousDeparture)) {
                    rowIndex++;
                }
                previousDeparture = departure;

                Row row = sheet.createRow(++rowIndex);

                int c = 0;
                row.createCell(c++).setCellValue(r + 1);

                for (int d = 0; d < dateHeaders.size(); d++) {
                    row.createCell(c++).setCellValue(d < dates.size() ? dates.get(d) : "");
                }

                Cell firstPriceCell = row.createCell(c++);
                Cell minPriceCell = row.createCell(c++);
                Cell isMinCell = row.createCell(c++);
                Cell priceCountCell = row.createCell(c++);
                if (result.firstPrice() != null) {
                    firstPriceCell.setCellValue(result.firstPrice());
                    minPriceCell.setCellValue(result.minPrice());
                    isMinCell.setCellValue(result.firstPrice().equals(result.minPrice()) ? "DA" : "NU");
                    priceCountCell.setCellValue(result.priceCount());
                }
                row.createCell(c++).setCellValue(result.currency());
                row.createCell(c++).setCellValue(result.note());
                row.createCell(c++).setCellValue(result.readAt().format(readAtFormat));

                Cell urlCell = row.createCell(c);
                urlCell.setCellValue(result.url());
                Hyperlink link = helper.createHyperlink(HyperlinkType.URL);
                link.setAddress(result.url());
                urlCell.setHyperlink(link);
                urlCell.setCellStyle(linkStyle);
            }

            sheet.setAutoFilter(new CellRangeAddress(0, rowIndex, 0, headers.length - 1));
            sheet.createFreezePane(0, 1);
            for (int c = 0; c < headers.length - 1; c++) {
                sheet.autoSizeColumn(c);
            }

            Path parent = excelFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(excelFile)) {
                workbook.write(out);
            }
        } catch (IOException exception) {
            // De obicei: fisierul e deschis in Excel. Continuam; se rescrie la urmatoarea pagina.
            System.out.println("    Nu am putut scrie " + excelFile + " (e deschis in Excel?): " + exception.getMessage());
        }
    }

    private static List<String> extractDates(String url) {

        List<String> dates = new ArrayList<>();
        Matcher matcher = DATE_PATTERN.matcher(url);
        while (matcher.find()) {
            dates.add(matcher.group());
        }

        // Skyscanner dus-intors: .../transport/flights/bjsa/buch/270301/270305/ (yyMMdd) -> 2027-03-01, 2027-03-05.
        if (dates.isEmpty()) {
            Matcher shortDate = SHORT_DATE_PATTERN.matcher(url);
            while (shortDate.find()) {
                dates.add("20" + shortDate.group(1) + "-" + shortDate.group(2) + "-" + shortDate.group(3));
            }
        }

        // Air China: ...&B_DATE_1=202703150000&... -> 2027-03-15.
        if (dates.isEmpty()) {
            Matcher airChinaDate = AIRCHINA_DATE_PATTERN.matcher(url);
            while (airChinaDate.find()) {
                dates.add(airChinaDate.group(1) + "-" + airChinaDate.group(2) + "-" + airChinaDate.group(3));
            }
        }
        return dates;
    }

    private static final Pattern SHORT_DATE_PATTERN = Pattern.compile("/(\\d{2})(\\d{2})(\\d{2})(?=/)");
    /** Air China: B_DATE_1=202703150000 -> 2027-03-15. */
    private static final Pattern AIRCHINA_DATE_PATTERN = Pattern.compile("B_DATE_\\d+=(\\d{4})(\\d{2})(\\d{2})\\d{4}");
    /** Suma cu exact 2 zecimale: partea intreaga (cu separatori de mii) + zecimalele. */
    private static final Pattern DECIMAL_PRICE = Pattern.compile("(\\d[\\d.,\\s]*?)[.,](\\d{2})(?!\\d)");

    private static int randomBetween(int min, int max) {

        return max > min ? min + ThreadLocalRandom.current().nextInt(max - min + 1) : min;
    }

    /** Intoarce false daca thread-ul a fost intrerupt (Stop in IDE). */
    private static boolean sleepSeconds(long seconds) {

        try {
            Thread.sleep(seconds * 1000);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
