package com.delgaz.matricecp.entities.matrix;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class BvbBonds {

    public static void main(String[] args) {

        boolean keepBrowserOpen = isKeepBrowserOpenEnabled();
        WebDriver driver = createDriver(keepBrowserOpen);
driver.hashCode();
        try {
            driver.manage().window().maximize();
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));

            WebDriverWait wait =
                    new WebDriverWait(driver, Duration.ofSeconds(30));

            String bondsPageUrl = "https://www.bvb.ro/FinancialInstruments/Markets/Bonds";

            By symbolLinks =
                    By.cssSelector("#gv tbody tr td:first-child a");

            List<String> urls = loadBondUrls(driver, wait, bondsPageUrl, symbolLinks);

            if (urls.isEmpty()) {
                throw new IllegalStateException("Nu s-au gasit linkuri de obligatiuni pe pagina BVB.");
            }

            System.out.println(
                    "Număr obligațiuni: " + urls.size()
            );

            for (String url : urls) {

                System.out.println("Deschid: " + url);

                driver.switchTo().newWindow(WindowType.TAB);
                driver.get(url);

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            System.out.println("Gata.");
        } finally {
            if (keepBrowserOpen) {
                System.out.println("KeepOpen activ: browserul ramane deschis. Apasa Stop in IDE cand vrei sa inchizi.");
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }

            driver.quit();
        }
    }

    private static boolean isKeepBrowserOpenEnabled() {

        String flag = System.getProperty("bvb.keepOpen");
        if (flag == null || flag.isBlank()) {
            flag = System.getenv("BVB_KEEP_OPEN");
        }

        if (flag == null || flag.isBlank()) {
            return true;
        }

        return !flag.equalsIgnoreCase("false");
    }

    private static List<String> loadBondUrls(
            WebDriver driver,
            WebDriverWait wait,
            String bondsPageUrl,
            By symbolLinks
    ) {

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                driver.get(bondsPageUrl);

                applyRequestedFilters(driver, wait);

                wait.until(ExpectedConditions.presenceOfElementLocated(symbolLinks));

                return collectBondUrlsFromAllPages(driver, wait, symbolLinks);
            } catch (TimeoutException timeoutException) {
                System.out.println("Retry " + attempt + "/3: pagina BVB nu a raspuns in timp util.");
            }
        }

        return List.of();
    }

    private static void applyRequestedFilters(WebDriver driver, WebDriverWait wait) {

        // Selectam moneda RON (ca in UI).
        clickIfPresent(
                wait,
                By.xpath("//label[contains(@class,'btn') and normalize-space()='RON']")
        );

        // Selectam categoria dorita.
        selectIfPresent(
                driver,
                wait,
                By.xpath("//label[contains(.,'Categoria')]/following::select[1]"),
                "Titluri de stat"
        );

        // Setam numarul de inregistrari pe pagina la 20.
        selectIfPresent(
                driver,
                wait,
                By.cssSelector("select[name='gv_length']"),
                "20"
        );

        waitForRowsReady(wait);
    }

    private static void waitForRowsReady(WebDriverWait wait) {

        wait.until(driver -> {
            List<WebElement> rows = driver.findElements(By.cssSelector("#gv tbody tr"));
            return !rows.isEmpty();
        });
    }

    private static List<String> collectBondUrlsFromAllPages(WebDriver driver, WebDriverWait wait, By symbolLinks) {

        Set<String> urls = new LinkedHashSet<>();
        int pageNumber = 1;

        while (true) {
            wait.until(ExpectedConditions.presenceOfElementLocated(symbolLinks));

            int beforeCount = urls.size();
            urls.addAll(
                    driver.findElements(symbolLinks)
                            .stream()
                            .map(element -> element.getAttribute("href"))
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(url -> !url.isEmpty())
                            .collect(Collectors.toList())
            );
            int added = urls.size() - beforeCount;
            System.out.println("Pagina " + pageNumber + ": +" + added + " link-uri unice");

            if (!goToNextPage(driver, wait, symbolLinks)) {
                break;
            }

            pageNumber++;
        }

        return new ArrayList<>(urls);
    }

    private static boolean goToNextPage(WebDriver driver, WebDriverWait wait, By symbolLinks) {

        WebElement nextButton = findNextPageButton(driver);
        if (nextButton == null) {
            return false;
        }

        int previousPageNumber = getCurrentPageNumber(driver);
        String previousFirstHref = getFirstHref(driver, symbolLinks);

        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", nextButton);
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", nextButton);

        wait.until(d -> {
            int currentPageNumber = getCurrentPageNumber(d);
            String currentFirstHref = getFirstHref(d, symbolLinks);
            boolean pageChanged = currentPageNumber > previousPageNumber;
            boolean firstHrefChanged = currentFirstHref != null && !currentFirstHref.equals(previousFirstHref);
            return pageChanged || firstHrefChanged;
        });

        return true;
    }

    private static WebElement findNextPageButton(WebDriver driver) {

        List<By> locators = List.of(
                By.cssSelector("#gv_paginate a.paginate_button.next"),
                By.xpath("//div[@id='gv_paginate']//a[contains(@class,'paginate_button') and normalize-space()='Inainte']")
        );

        for (By locator : locators) {
            List<WebElement> buttons = driver.findElements(locator);
            for (WebElement button : buttons) {
                String classes = String.valueOf(button.getAttribute("class"));
                String ariaDisabled = String.valueOf(button.getAttribute("aria-disabled"));
                if (!classes.contains("disabled") && !"true".equalsIgnoreCase(ariaDisabled)) {
                    return button;
                }
            }
        }

        return null;
    }

    private static int getCurrentPageNumber(WebDriver driver) {

        List<WebElement> currentButtons = driver.findElements(
                By.cssSelector("#gv_paginate a.paginate_button.current")
        );

        if (currentButtons.isEmpty()) {
            return -1;
        }

        String text = currentButtons.get(0).getText().trim();
        if (!text.matches("\\d+")) {
            return -1;
        }

        return Integer.parseInt(text);
    }

    private static String getFirstHref(WebDriver driver, By symbolLinks) {

        List<WebElement> links = driver.findElements(symbolLinks);
        if (links.isEmpty()) {
            return null;
        }

        return links.get(0).getAttribute("href");
    }

    private static void clickIfPresent(WebDriverWait wait, By locator) {

        try {
            WebElement element = wait.until(ExpectedConditions.elementToBeClickable(locator));
            element.click();
        } catch (TimeoutException ignored) {
            System.out.println("Filtru indisponibil: " + locator);
        }
    }

    private static void selectIfPresent(WebDriver driver, WebDriverWait wait, By locator, String visibleText) {

        try {
            WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
            Select select = new Select(element);
            select.selectByVisibleText(visibleText);
        } catch (TimeoutException ignored) {
            System.out.println("Filtru indisponibil: " + locator);
        } catch (RuntimeException ignored) {
            System.out.println("Optiunea nu a putut fi selectata: " + visibleText);
        }
    }

    private static WebDriver createDriver(boolean keepBrowserOpen) {

        String browser = System.getProperty("bvb.browser");
        if (browser == null || browser.isBlank()) {
            browser = System.getenv("BVB_BROWSER");
        }
        if (browser == null || browser.isBlank()) {
            browser = "edge";
        }

        String normalized = browser.trim().toLowerCase(Locale.ROOT);
        System.out.println("Browser folosit: " + normalized);

        switch (normalized) {
            case "firefox":
            case "mozilla":
                ensureDriverConfigured(
                        "webdriver.gecko.driver",
                        new String[]{"WEBDRIVER_GECKO_DRIVER", "GECKODRIVER_PATH"},
                        "geckodriver",
                        new String[]{
                                ".drivers/geckodriver/geckodriver.exe",
                                ".drivers/geckodriver.exe",
                                "drivers/geckodriver.exe"
                        }
                );
                return new FirefoxDriver();
            case "chrome":
                ensureDriverConfigured(
                        "webdriver.chrome.driver",
                        new String[]{"WEBDRIVER_CHROME_DRIVER", "CHROMEDRIVER_PATH"},
                        "chromedriver",
                        new String[]{
                                ".drivers/chromedriver-win64/chromedriver.exe",
                                ".drivers/chromedriver.exe",
                                "drivers/chromedriver.exe"
                        }
                );
                ChromeOptions chromeOptions = new ChromeOptions();
                // Workaround for DevTools websocket 403 on older Selenium clients.
                chromeOptions.addArguments("--remote-allow-origins=*");
                if (keepBrowserOpen) {
                    chromeOptions.setExperimentalOption("detach", true);
                }
                applyCleanProfileArguments(chromeOptions, "chrome");
                return new ChromeDriver(chromeOptions);
            case "edge":
            default:
                ensureDriverConfigured(
                        "webdriver.edge.driver",
                        new String[]{"WEBDRIVER_EDGE_DRIVER", "EDGEDRIVER_PATH"},
                        "msedgedriver",
                        new String[]{
                                ".drivers/edgedriver_win64/msedgedriver.exe",
                                ".drivers/msedgedriver.exe",
                                "drivers/msedgedriver.exe"
                        }
                );
                EdgeOptions edgeOptions = new EdgeOptions();
                // Workaround for DevTools websocket 403 on older Selenium clients.
                edgeOptions.addArguments("--remote-allow-origins=*");
                if (keepBrowserOpen) {
                    edgeOptions.setExperimentalOption("detach", true);
                }
                applyCleanProfileArguments(edgeOptions, "edge");
                return new EdgeDriver(edgeOptions);
        }
    }

    private static void applyCleanProfileArguments(org.openqa.selenium.chromium.ChromiumOptions<?> options, String browser) {

        if (!isFlagEnabled("bvb.cleanProfile", "BVB_CLEAN_PROFILE")) {
            return;
        }

        // Folosim director unic per rulare ca sa evitam lock/crash pe profil.
        Path tempProfile;
        try {
            tempProfile = Files.createTempDirectory("bvb-" + browser + "-profile-");
        } catch (IOException exception) {
            System.out.println("Nu am putut crea profil temporar; continuam cu profilul implicit.");
            return;
        }

        options.addArguments("--user-data-dir=" + tempProfile.toAbsolutePath());
        options.addArguments("--disk-cache-size=0");
        options.addArguments("--media-cache-size=0");
        options.addArguments("--disable-application-cache");
    }

    private static boolean isFlagEnabled(String propertyName, String envName) {

        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }

        return value != null && value.equalsIgnoreCase("true");
    }

    private static void ensureDriverConfigured(
            String propertyName,
            String[] envVarNames,
            String pathCommand,
            String[] localCandidates
    ) {

        String propertyValue = resolveExistingFile(System.getProperty(propertyName));
        if (propertyValue != null) {
            System.setProperty(propertyName, propertyValue);
            return;
        }

        for (String envVarName : envVarNames) {
            String envValue = resolveExistingFile(System.getenv(envVarName));
            if (envValue != null) {
                System.setProperty(propertyName, envValue);
                return;
            }
        }

        String pathValue = findExecutableInPath(pathCommand);
        if (pathValue != null) {
            System.setProperty(propertyName, pathValue);
            return;
        }

        String localValue = findProjectLocalExecutable(localCandidates);
        if (localValue != null) {
            System.setProperty(propertyName, localValue);
            return;
        }

        List<String> expected = new ArrayList<>();
        expected.add(propertyName);
        for (String envVarName : envVarNames) {
            expected.add(envVarName);
        }

        throw new IllegalStateException(
                "Lipseste driverul browserului. Configureaza una dintre: " +
                        String.join(", ", expected) +
                        "."
        );
    }

    private static String resolveExistingFile(String candidate) {

        if (candidate == null || candidate.isBlank()) {
            return null;
        }

        try {
            Path path = Path.of(candidate.trim());
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return path.toAbsolutePath().toString();
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static String findExecutableInPath(String executableName) {

        ProcessBuilder processBuilder = new ProcessBuilder("where", executableName);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String resolved = resolveExistingFile(line);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        return null;
    }

    private static String findProjectLocalExecutable(String[] localCandidates) {

        Path workingDirectory = Path.of("").toAbsolutePath();

        for (String localCandidate : localCandidates) {
            String resolved = resolveExistingFile(
                    workingDirectory.resolve(localCandidate).toString()
            );
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
    }
}
