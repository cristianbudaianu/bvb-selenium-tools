package com.delgaz.matricecp.entities.matrix;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BvbBondsQuickDownloadCsvMain {

    private static final String DOWNLOAD_URL = "https://www.bvb.ro/FinancialInstruments/Markets/BondsListForDownload.ashx";
    private static final String DETAILS_URL_PREFIX = "https://www.bvb.ro/FinancialInstruments/Details/FinancialInstrumentsDetails.aspx?s=";
    private static final int MAX_TABS_PER_LIST = 120;
    private static final double DEFAULT_MIN_YIELD_RON = 7.00;
    private static final double DEFAULT_MIN_YIELD_EURO = 5.00;
    private static final Pattern NUMBERED_SUFFIX = Pattern.compile("^(.*) \\((\\d+)\\)$");

    private static final String[] BOND_TABLE_HEADERS = {
            "Nr.", "Simbol", "AproxYTM", "YeldToMaturity", "PRET-ASK", "PRET", "BID", "ASK", "BID_VOL", "ASK_VOL"
    };
    private static final int COL_NR = 0;
    private static final int COL_SYMBOL = 1;
    private static final int COL_APROX_YTM = 2;
    private static final int COL_YTM = 3;
    private static final int COL_PRICE_ASK_DIFF = 4;
    private static final int COL_PRICE = 5;
    private static final int COL_BID = 6;
    private static final int COL_ASK = 7;
    private static final int COL_BID_VOL = 8;
    private static final int COL_ASK_VOL = 9;
    private static final int SYMBOL_GROUP_THRESHOLD = 30;

    public static void main(String[] args) {

        Path downloadDir = resolveDownloadsDirectory();
        Path csvPath = downloadCsv(downloadDir);
        List<Map<String, String>> rows = readCsv(csvPath);
        double minYieldRon = resolveMinYield("bvb.min.yield.ron", "BVB_MIN_YIELD_RON", DEFAULT_MIN_YIELD_RON);
        double minYieldEuro = resolveMinYield("bvb.min.yield.euro", "BVB_MIN_YIELD_EURO", DEFAULT_MIN_YIELD_EURO);
        boolean listRon = isFlagEnabled("bvb.show.ron.list", "BVB_SHOW_RON_LIST");
        boolean listEuro = isFlagEnabled("bvb.show.euro.list", "BVB_SHOW_EURO_LIST");
        boolean openRonList = isFlagEnabled("bvb.open.ron", "BVB_OPEN_RON");
        boolean openEuroList = isFlagEnabled("bvb.open.euro", "BVB_OPEN_EURO");

        List<Map<String, String>> titluriDeStat = filterTitluriDeStat(rows);
        List<Map<String, String>> euro = filterBySymbolSuffix(titluriDeStat, true);
        List<Map<String, String>> ron = filterBySymbolSuffix(titluriDeStat, false);
        List<String> euroSymbols = limitSymbols(extractSymbols(euro), "euro");
        List<String> ronSymbols = limitSymbols(extractSymbols(ron), "ron");

        System.out.println("CSV descarcat: " + csvPath);
        System.out.println("Randuri CSV total: " + rows.size());
        System.out.println("Titluri de stat: " + titluriDeStat.size());
        System.out.println("Prag randament RON (YTM): >= " + String.format(Locale.ROOT, "%.2f", minYieldRon));
        System.out.println("Prag randament EURO (YTM): >= " + String.format(Locale.ROOT, "%.2f", minYieldEuro));
        System.out.println("Listare RON: " + listRon);
        System.out.println("Listare EURO: " + listEuro);
        System.out.println("Lista euro: " + euro.size());
        System.out.println("Lista ron: " + ron.size());
        System.out.println("Simboluri euro: " + euroSymbols.size());
        System.out.println("Simboluri ron: " + ronSymbols.size());
        System.out.println("Open RON list: " + openRonList);
        System.out.println("Open EURO list: " + openEuroList);

        String runTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        WebDriver ronDriver = listRon && openRonList ? openEdgeWindowForSymbols("ron", ronSymbols, minYieldRon, downloadDir, runTimestamp) : null;
        WebDriver euroDriver = listEuro && openEuroList ? openEdgeWindowForSymbols("euro", euroSymbols, minYieldEuro, downloadDir, runTimestamp) : null;

        if (!listRon) {
            System.out.println("Listarea ron este dezactivata prin flag.");
        }
        if (!listEuro) {
            System.out.println("Listarea euro este dezactivata prin flag.");
        }
        if (!openRonList) {
            System.out.println("Deschiderea listei ron este dezactivata prin flag.");
        }
        if (!openEuroList) {
            System.out.println("Deschiderea listei euro este dezactivata prin flag.");
        }

        keepDriversAlive(ronDriver, euroDriver);

        System.out.println("Gata.");
    }

    private static List<Map<String, String>> filterTitluriDeStat(List<Map<String, String>> rows) {

        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String tip = getColumnValue(row, "tip", "tip instrument", "categorie", "type");
            if (normalizeText(tip).equals(normalizeText("Titluri de stat"))) {
                result.add(row);
            }
        }

        return result;
    }

    private static List<Map<String, String>> filterBySymbolSuffix(List<Map<String, String>> rows, boolean euroList) {

        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String symbol = getColumnValue(row, "simbol", "symbol", "ticker", "cod");
            boolean endsWithE = normalizeText(symbol).endsWith("e");
            if (euroList == endsWithE) {
                result.add(row);
            }
        }

        return result;
    }

    private static double parseYieldPercent(String value) {

        if (value == null || value.isBlank()) {
            return Double.NEGATIVE_INFINITY;
        }

        String normalized = value
                .replace("%", "")
                .replace(" ", "")
                .replace(",", ".")
                .trim();

        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return Double.NEGATIVE_INFINITY;
        }
    }

    private static double resolveMinYield(String propertyName, String envName, double defaultValue) {

        String raw = resolveStringConfig(propertyName, envName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        String normalized = raw.replace(",", ".").trim();
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            System.out.println("Valoare invalida pentru prag randament: " + raw + ". Folosesc default " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean isFlagEnabled(String propertyName, String envName) {

        String raw = resolveStringConfig(propertyName, envName);
        if (raw == null || raw.isBlank()) {
            return true;
        }

        String normalized = raw.trim();
        return normalized.equalsIgnoreCase("true")
                || normalized.equalsIgnoreCase("1")
                || normalized.equalsIgnoreCase("yes");
    }

    private static String resolveStringConfig(String propertyName, String envName) {

        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        return value;
    }

    private static List<String> extractSymbols(List<Map<String, String>> rows) {

        Set<String> symbols = new LinkedHashSet<>();

        for (Map<String, String> row : rows) {
            String symbol = getColumnValue(row, "simbol", "symbol", "ticker", "cod").trim();
            if (!symbol.isEmpty()) {
                symbols.add(symbol);
            }
        }

        return new ArrayList<>(symbols);
    }

    private static List<String> limitSymbols(List<String> symbols, String listName) {

        if (symbols.size() <= MAX_TABS_PER_LIST) {
            return symbols;
        }

        System.out.println(
                "Lista " + listName + " are " + symbols.size() +
                        " simboluri; deschid doar primele " + MAX_TABS_PER_LIST + "."
        );

        return new ArrayList<>(symbols.subList(0, MAX_TABS_PER_LIST));
    }

    private static WebDriver openEdgeWindowForSymbols(
            String listName,
            List<String> symbols,
            double minYield,
            Path downloadDir,
            String runTimestamp
    ) {

        if (symbols.isEmpty()) {
            System.out.println("Lista " + listName + " nu are simboluri de deschis.");
            return null;
        }

        WebDriver driver = createEdgeDriver();
        driver.get("about:blank");
        String anchorHandle = driver.getWindowHandle();

        int keptTabs = 0;
        int closedTabs = 0;
        List<BondSnapshot> remainingBonds = new ArrayList<>();

        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            String detailsUrl = DETAILS_URL_PREFIX + symbol;

            driver.switchTo().newWindow(WindowType.TAB);
            openDetailsPageUntilSuccess(driver, detailsUrl, symbol, listName);

            waitForPageLoaded(driver);

            double pageYield = extractYieldFromPage(driver);
            String pagePrice = extractPriceFromPage(driver);
            String[] bidAsk = extractBidAskFromPage(driver);
            if (pageYield < minYield) {
                String ytmText = pageYield == Double.NEGATIVE_INFINITY ? "n/a" : String.format(Locale.ROOT, "%.2f", pageYield);
                System.out.println("[" + listName + "] " + (i + 1) + "/" + symbols.size() + " -> " + symbol + " | YTM=" + ytmText + " < " + String.format(Locale.ROOT, "%.2f", minYield) + " => inchid tab");
                driver.close();
                closedTabs++;
                driver.switchTo().window(anchorHandle);
                continue;
            }

            keptTabs++;
            System.out.println("[" + listName + "] " + (i + 1) + "/" + symbols.size() + " -> " + symbol + " | YTM=" + String.format(Locale.ROOT, "%.2f", pageYield) + " => pastrez tab");
            remainingBonds.add(new BondSnapshot(symbol, pageYield, pagePrice, bidAsk[0], bidAsk[1], bidAsk[2], bidAsk[3]));
            driver.switchTo().window(anchorHandle);
        }

        System.out.println("Lista " + listName + ": taburi pastrate=" + keptTabs + ", taburi inchise (YTM sub prag)=" + closedTabs);
        List<BondSnapshot> sortedBonds = sortBondsByYieldDescending(remainingBonds);
        printRemainingBonds(listName, sortedBonds);
        writeBondsToExcel(listName, sortedBonds, downloadDir, runTimestamp);
        return driver;
    }

    private static List<BondSnapshot> sortBondsByYieldDescending(List<BondSnapshot> bonds) {

        List<BondSnapshot> sorted = new ArrayList<>(bonds);
        sorted.sort(
                Comparator.comparingDouble(BondSnapshot::getYtm).reversed()
                        .thenComparing(BondSnapshot::getSymbol)
        );

        return sorted;
    }

    private static void printRemainingBonds(String listName, List<BondSnapshot> sortedBonds) {

        System.out.println("Lista " + listName + " ramasa, ordonata dupa YTM descrescator:");

        if (sortedBonds.isEmpty()) {
            System.out.println("  (niciun element)");
            return;
        }

        for (int i = 0; i < sortedBonds.size(); i++) {
            BondSnapshot bond = sortedBonds.get(i);
            System.out.println("  " + (i + 1) + ". " + bond.format());
        }
    }

    private static void writeBondsToExcel(String listName, List<BondSnapshot> sortedBonds, Path downloadDir, String runTimestamp) {

        String fileName = "BVB_" + listName + "_" + runTimestamp + ".xlsx";
        Path outputPath = downloadDir.resolve(fileName);

        BondGroups groups = partitionBonds(sortedBonds);
        groups.sortAllByAdjustedScoreDescending();

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(listName);
            BondTableStyles styles = new BondTableStyles(workbook);

            int nextRow = writeBondTable(
                    sheet, 0,
                    "Tabel 1: simbol pozitiile 2-3 <= " + SYMBOL_GROUP_THRESHOLD,
                    groups.standardGroup, styles
            );
            writeBondTable(
                    sheet, nextRow + 1,
                    "Tabel 2: simbol pozitiile 2-3 > " + SYMBOL_GROUP_THRESHOLD,
                    groups.highGroup, styles
            );

            for (int col = 0; col < BOND_TABLE_HEADERS.length; col++) {
                sheet.autoSizeColumn(col);
            }

            try (OutputStream outputStream = new FileOutputStream(outputPath.toFile())) {
                workbook.write(outputStream);
            }

            System.out.println("Fisier Excel salvat: " + outputPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Nu am putut scrie fisierul Excel pentru lista " + listName + ".", exception);
        }
    }

    private static BondGroups partitionBonds(List<BondSnapshot> sortedBonds) {

        BondGroups groups = new BondGroups();
        for (BondSnapshot bond : sortedBonds) {
            Integer secondThirdNumber = extractSecondThirdCharNumber(bond.symbol);
            boolean highSymbolGroup = secondThirdNumber != null && secondThirdNumber > SYMBOL_GROUP_THRESHOLD;

            if (highSymbolGroup) {
                groups.highGroup.add(bond);
            } else {
                groups.standardGroup.add(bond);
            }
        }

        return groups;
    }

    private static int writeBondTable(Sheet sheet, int startRow, String title, List<BondSnapshot> bonds, BondTableStyles styles) {

        Row titleRow = sheet.createRow(startRow);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(styles.titleStyle);

        int headerRowIndex = startRow + 1;
        Row headerRow = sheet.createRow(headerRowIndex);
        for (int col = 0; col < BOND_TABLE_HEADERS.length; col++) {
            Cell headerCell = headerRow.createCell(col);
            headerCell.setCellValue(BOND_TABLE_HEADERS[col]);
            headerCell.setCellStyle(styles.style(false, false));
        }

        int rowIndex = headerRowIndex + 1;
        for (int i = 0; i < bonds.size(); i++) {
            BondSnapshot bond = bonds.get(i);
            Row row = sheet.createRow(rowIndex);

            row.createCell(COL_NR).setCellValue(i + 1);
            row.createCell(COL_SYMBOL).setCellValue(bond.symbol);

            setNumericOrNA(
                    row.createCell(COL_APROX_YTM), computeAdjustedScore(bond),
                    styles.style(true, true), styles.style(false, true)
            );
            setNumericCell(row.createCell(COL_YTM), bond.ytm, styles.style(true, false));
            setNumericOrNA(
                    row.createCell(COL_PRICE_ASK_DIFF), subtractIfBothParseable(bond.price, bond.ask),
                    styles.style(true, false), styles.style(false, false)
            );

            setNumericOrNA(
                    row.createCell(COL_PRICE), parseBvbDecimal(bond.price),
                    styles.style(true, false), styles.style(false, false)
            );
            setNumericOrNA(
                    row.createCell(COL_BID), parseBvbDecimal(bond.bid),
                    styles.style(true, false), styles.style(false, false)
            );
            setNumericOrNA(
                    row.createCell(COL_ASK), parseBvbDecimal(bond.ask),
                    styles.style(true, false), styles.style(false, false)
            );
            setNumericOrNA(
                    row.createCell(COL_BID_VOL), parseBvbDecimal(bond.bidVol),
                    styles.style(false, false), styles.style(false, false)
            );
            setNumericOrNA(
                    row.createCell(COL_ASK_VOL), parseBvbDecimal(bond.askVol),
                    styles.style(false, false), styles.style(false, false)
            );

            CellStyle rowStyle = styles.style(false, false);
            row.getCell(COL_NR).setCellStyle(rowStyle);
            row.getCell(COL_SYMBOL).setCellStyle(rowStyle);

            rowIndex++;
        }

        if (bonds.isEmpty()) {
            sheet.createRow(rowIndex).createCell(0).setCellValue("(niciun element)");
            rowIndex++;
        }

        return rowIndex;
    }

    private static void setNumericCell(Cell cell, double value, CellStyle style) {

        cell.setCellValue(Math.round(value * 100.0) / 100.0);
        cell.setCellStyle(style);
    }

    private static void setNumericOrNA(Cell cell, Double value, CellStyle numericStyle, CellStyle naStyle) {

        if (value != null) {
            cell.setCellValue(Math.round(value * 100.0) / 100.0);
            cell.setCellStyle(numericStyle);
        } else {
            cell.setCellValue("n/a");
            cell.setCellStyle(naStyle);
        }
    }

    private static Integer extractSecondThirdCharNumber(String symbol) {

        if (symbol == null || symbol.length() < 3) {
            return null;
        }

        try {
            return Integer.parseInt(symbol.substring(1, 3));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class BondGroups {

        private final List<BondSnapshot> standardGroup = new ArrayList<>();
        private final List<BondSnapshot> highGroup = new ArrayList<>();

        private void sortAllByAdjustedScoreDescending() {

            Comparator<BondSnapshot> byScoreDescending = Comparator.comparingDouble(
                    (BondSnapshot bond) -> {
                        Double score = computeAdjustedScore(bond);
                        return score != null ? score : Double.NEGATIVE_INFINITY;
                    }
            ).reversed();

            standardGroup.sort(byScoreDescending);
            highGroup.sort(byScoreDescending);
        }
    }

    private static final class BondTableStyles {

        private final CellStyle titleStyle;
        private final CellStyle[] dataStyles = new CellStyle[4];

        private BondTableStyles(Workbook workbook) {

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);

            CellStyle title = workbook.createCellStyle();
            title.setFont(boldFont);
            this.titleStyle = title;

            short twoDecimalsFormat = workbook.getCreationHelper().createDataFormat().getFormat("0.00");

            for (int mask = 0; mask < dataStyles.length; mask++) {
                boolean twoDecimals = (mask & 2) != 0;
                boolean bold = (mask & 1) != 0;

                CellStyle style = workbook.createCellStyle();
                style.setAlignment(HorizontalAlignment.RIGHT);
                if (twoDecimals) {
                    style.setDataFormat(twoDecimalsFormat);
                }
                if (bold) {
                    style.setFont(boldFont);
                }

                dataStyles[mask] = style;
            }
        }

        private CellStyle style(boolean twoDecimals, boolean bold) {
            int mask = (twoDecimals ? 2 : 0) | (bold ? 1 : 0);
            return dataStyles[mask];
        }
    }

    private static double extractYieldFromPage(WebDriver driver) {

        List<By> yieldValueLocators = List.of(
                By.xpath("//td[contains(normalize-space(.),'Randament')]/following-sibling::td[1]"),
                By.xpath("//td[contains(normalize-space(.),'YTM')]/following-sibling::td[1]")
        );

        for (By locator : yieldValueLocators) {
            try {
                WebElement element = driver.findElement(locator);
                String rawValue = element.getText();
                double parsed = parseYieldPercent(rawValue);
                if (parsed != Double.NEGATIVE_INFINITY) {
                    return parsed;
                }
            } catch (NoSuchElementException ignored) {
                // Incercam urmatorul locator.
            }
        }

        return Double.NEGATIVE_INFINITY;
    }

    private static String extractPriceFromPage(WebDriver driver) {

        List<By> priceLocators = List.of(
                By.xpath("//*[contains(concat(' ',normalize-space(@class),' '),' value ')][parent::*[span[contains(@class,'date') and contains(@class,'small')]]]"),
                By.xpath("//span[contains(@class,'date') and contains(@class,'small')]/preceding-sibling::*[contains(concat(' ',normalize-space(@class),' '),' value ')][1]")
        );

        for (By locator : priceLocators) {
            List<WebElement> elements = driver.findElements(locator);
            for (WebElement element : elements) {
                String rawText = element.getText();
                if (rawText == null || rawText.isBlank()) {
                    continue;
                }

                String normalized = rawText.replace('\u00A0', ' ').trim();
                if (normalized.matches(".*\\d.*")) {
                    return normalized;
                }
            }
        }

        return "n/a";
    }

    private static String[] extractBidAskFromPage(WebDriver driver) {

        String[] bidAsk = splitBidAsk(extractLabeledTableValue(driver, "Bid / Ask"));
        String[] bidAskVol = splitBidAsk(extractLabeledTableValue(driver, "Bid / Ask Vol."));

        return new String[]{bidAsk[0], bidAsk[1], bidAskVol[0], bidAskVol[1]};
    }

    private static String extractLabeledTableValue(WebDriver driver, String label) {

        try {
            WebElement valueCell = driver.findElement(
                    By.xpath("//td[normalize-space(text())='" + label + "']/following-sibling::td[1]")
            );
            String rawText = valueCell.getText();
            return rawText == null ? "" : rawText.replace(' ', ' ').trim();
        } catch (NoSuchElementException ignored) {
            return "";
        }
    }

    private static String[] splitBidAsk(String rawValue) {

        if (rawValue == null || rawValue.isBlank()) {
            return new String[]{"n/a", "n/a"};
        }

        String[] parts = rawValue.split("/");
        if (parts.length != 2) {
            return new String[]{"n/a", "n/a"};
        }

        String first = parts[0].trim();
        String second = parts[1].trim();
        return new String[]{
                first.isEmpty() ? "n/a" : first,
                second.isEmpty() ? "n/a" : second
        };
    }

    private static Double computeAdjustedScore(BondSnapshot bond) {

        Double priceAskDiff = subtractIfBothParseable(bond.price, bond.ask);
        if (priceAskDiff == null) {
            return null;
        }

        return bond.ytm + (priceAskDiff / 2.0);
    }

    private static Double subtractIfBothParseable(String minuendText, String subtrahendText) {

        Double minuend = parseBvbDecimal(minuendText);
        Double subtrahend = parseBvbDecimal(subtrahendText);
        if (minuend == null || subtrahend == null) {
            return null;
        }

        return minuend - subtrahend;
    }

    private static Double parseBvbDecimal(String value) {

        if (value == null || value.isBlank() || value.equalsIgnoreCase("n/a")) {
            return null;
        }

        String normalized = value.replace(" ", "").replace(",", ".").trim();
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void openDetailsPageUntilSuccess(WebDriver driver, String detailsUrl, String symbol, String listName) {

        int attempt = 1;
        while (true) {
            try {
                driver.get(detailsUrl);
                return;
            } catch (RuntimeException exception) {
                System.out.println("[" + listName + "] " + symbol + " | incercare " + attempt + " esuata la incarcare pagina: " + exception.getMessage());
                attempt++;
            }
        }
    }

    private static WebDriver createEdgeDriver() {

        EdgeOptions options = new EdgeOptions();
        options.addArguments("--remote-allow-origins=*");
        options.setExperimentalOption("detach", true);

        WebDriver driver = new EdgeDriver(options);
        driver.manage().window().maximize();
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
        return driver;
    }

    private static void waitForPageLoaded(WebDriver driver) {

        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                Object readyState = ((JavascriptExecutor) driver).executeScript("return document.readyState");
                if ("complete".equals(String.valueOf(readyState))) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // Pagina este inca in tranzitie; continuam sa asteptam.
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void keepDriversAlive(WebDriver... drivers) {

        boolean hasAtLeastOneDriver = false;
        for (WebDriver driver : drivers) {
            if (driver != null) {
                hasAtLeastOneDriver = true;
                break;
            }
        }

        if (!hasAtLeastOneDriver) {
            return;
        }

        System.out.println("Ferestrele au ramas deschise. Apasa Stop in IDE cand vrei sa inchizi procesul Java.");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String getColumnValue(Map<String, String> row, String... candidateColumns) {

        for (String candidateColumn : candidateColumns) {
            String normalizedCandidate = normalizeText(candidateColumn);
            for (Map.Entry<String, String> entry : row.entrySet()) {
                if (normalizeText(entry.getKey()).equals(normalizedCandidate)) {
                    return entry.getValue();
                }
            }
        }

        return "";
    }

    private static final class BondSnapshot {

        private final String symbol;
        private final double ytm;
        private final String price;
        private final String bid;
        private final String ask;
        private final String bidVol;
        private final String askVol;

        private BondSnapshot(String symbol, double ytm, String price, String bid, String ask, String bidVol, String askVol) {
            this.symbol = symbol;
            this.ytm = ytm;
            this.price = price;
            this.bid = bid;
            this.ask = ask;
            this.bidVol = bidVol;
            this.askVol = askVol;
        }

        private String getSymbol() {
            return symbol;
        }

        private double getYtm() {
            return ytm;
        }

        private String format() {
            return symbol + " | YTM=" + String.format(Locale.ROOT, "%.2f", ytm)
                    + " | PRET=" + price
                    + " | BID=" + bid
                    + " | ASK=" + ask
                    + " | BID_VOL=" + bidVol
                    + " | ASK_VOL=" + askVol;
        }
    }

    private static String normalizeText(String value) {

        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('ă', 'a')
                .replace('â', 'a')
                .replace('î', 'i')
                .replace('ș', 's')
                .replace('ş', 's')
                .replace('ț', 't')
                .replace('ţ', 't');
    }

    private static Path resolveDownloadsDirectory() {

        try {
            Path dir = Path.of("C:\\Users\\C32232\\OneDrive - E.ON\\Downloads").toAbsolutePath();
            Files.createDirectories(dir);
            return dir;
        } catch (IOException exception) {
            throw new IllegalStateException("Nu pot crea folderul de download.", exception);
        }
    }

    private static Path downloadCsv(Path downloadDir) {

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(DOWNLOAD_URL))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .timeout(Duration.ofSeconds(90))
                .build();

        try {
            HttpResponse<InputStream> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Download-ul a esuat. HTTP status: " + response.statusCode()
                );
            }

            String fileName = resolveOutputFileName(response);
            Path output = createUniqueTargetPath(downloadDir, fileName);

            try (InputStream inputStream = response.body()) {
                Files.copy(inputStream, output);
            }

            return output;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Nu am putut descarca CSV-ul BVB.", exception);
        }
    }

    private static String resolveOutputFileName(HttpResponse<?> response) {

        String defaultName = "BVB_Obligatiuni_" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "_ro.csv";
        String disposition = response.headers().firstValue("content-disposition").orElse("");
        Matcher matcher = Pattern.compile("filename=\"?([^\";]+)\"?").matcher(disposition);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }

        return defaultName;
    }

    private static Path createUniqueTargetPath(Path downloadDir, String fileName) {

        String sanitizedName = fileName.replace('/', '_').replace('\\', '_');
        int extensionIndex = sanitizedName.lastIndexOf('.');
        String baseName = extensionIndex >= 0 ? sanitizedName.substring(0, extensionIndex) : sanitizedName;
        String extension = extensionIndex >= 0 ? sanitizedName.substring(extensionIndex) : "";

        Matcher matcher = NUMBERED_SUFFIX.matcher(baseName);
        String plainBaseName = matcher.matches() ? matcher.group(1) : baseName;

        Path directCandidate = downloadDir.resolve(sanitizedName);
        if (!Files.exists(directCandidate)) {
            return directCandidate;
        }

        int suffix = 1;
        while (true) {
            Path candidate = downloadDir.resolve(plainBaseName + " (" + suffix + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    private static List<Map<String, String>> readCsv(Path csvPath) {

        List<String> lines;
        try {
            lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Nu pot citi CSV-ul descarcat.", exception);
        }

        if (lines.isEmpty()) {
            return List.of();
        }

        String headerLine = removeBom(lines.get(0));
        char delimiter = detectDelimiter(headerLine);
        List<String> headers = parseCsvLine(headerLine, delimiter);

        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }

            List<String> values = parseCsvLine(line, delimiter);
            Map<String, String> row = new LinkedHashMap<>();

            for (int col = 0; col < headers.size(); col++) {
                String key = headers.get(col);
                String value = col < values.size() ? values.get(col) : "";
                row.put(key, value);
            }

            rows.add(row);
        }

        return rows;
    }

    private static String removeBom(String value) {

        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }

        return value;
    }

    private static char detectDelimiter(String headerLine) {

        int semicolons = countOccurrences(headerLine, ';');
        int commas = countOccurrences(headerLine, ',');
        return semicolons >= commas ? ';' : ',';
    }

    private static int countOccurrences(String value, char needle) {

        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == needle) {
                count++;
            }
        }

        return count;
    }

    private static List<String> parseCsvLine(String line, char delimiter) {

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                boolean escapedQuote = inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"';
                if (escapedQuote) {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == delimiter && !inQuotes) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        result.add(current.toString().trim());
        return result;
    }
}
