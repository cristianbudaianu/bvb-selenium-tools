package com.delgaz.urlopener.helpers;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deschide intr-un browser (cate un tab per URL) o lista de URL-uri citita dintr-un fisier text.
 *
 * Configurare (System property / variabila de mediu):
 *  - urls.file       / URLS_FILE         : fisierul cu URL-uri (implicit url-lists/urls.txt)
 *  - urls.launcher   / URLS_LAUNCHER     : selenium | system | batch (implicit selenium). "system" deschide URL-urile
 *                                          in browserul tau implicit, cu profilul obisnuit, fara WebDriver; "batch"
 *                                          deschide fiecare lot dintr-o data, intr-o fereastra noua (implicit 5 / lot)
 *  - urls.browser    / URLS_BROWSER      : edge | chrome | firefox (implicit edge; pentru selenium si batch)
 *  - urls.browserExe / URLS_BROWSER_EXE  : calea completa la browser, daca nu e in locatia standard (doar batch)
 *  - urls.keepOpen   / URLS_KEEP_OPEN    : true | false (implicit true; doar pentru selenium)
 *  - urls.delayMs    / URLS_DELAY_MS     : pauza minima intre tab-uri, in ms (implicit 200)
 *  - urls.delayMaxMs / URLS_DELAY_MAX_MS : pauza maxima; pauza reala e aleatoare intre min si max (implicit = min)
 *  - urls.batchSize  / URLS_BATCH_SIZE   : cate tab-uri are un lot (implicit 0 = fara loturi)
 *  - urls.batchPauseMs    / URLS_BATCH_PAUSE_MS     : pauza minima intre loturi, in ms (implicit 60000)
 *  - urls.batchPauseMaxMs / URLS_BATCH_PAUSE_MAX_MS : pauza maxima intre loturi; aleatoare intre min si max
 *
 * Primul argument din linia de comanda, daca exista, suprascrie fisierul cu URL-uri.
 */
public class UrlListOpener {

    private static final String DEFAULT_URLS_FILE = "url-lists/urls.txt";
    private static final int DEFAULT_BATCH_SIZE = 5;

    public static void main(String[] args) throws IOException {

        Path urlsFile = resolveUrlsFile(args);
        List<String> urls = readUrls(urlsFile);

        if (urls.isEmpty()) {
            throw new IllegalStateException("Nu exista URL-uri in fisierul: " + urlsFile.toAbsolutePath());
        }

        System.out.println("Fisier URL-uri: " + urlsFile.toAbsolutePath());

        openUrls(urls);
    }

    /**
     * Deschide fiecare URL intr-un tab nou, folosind setarile urls.launcher / urls.browser / urls.keepOpen /
     * urls.delayMs / urls.delayMaxMs / urls.batchSize.
     */
    public static void openUrls(List<String> urls) {

        System.out.println("Numar URL-uri: " + urls.size());

        Pacing pacing = Pacing.fromSettings();

        String launcher = readSetting("urls.launcher", "URLS_LAUNCHER", "selenium").trim().toLowerCase(Locale.ROOT);
        if (launcher.equals("batch")) {
            openInBatches(urls, pacing);
        } else if (launcher.equals("system")) {
            openInSystemBrowser(urls, pacing);
        } else {
            openWithSelenium(urls, pacing);
        }
    }

    /**
     * Deschide URL-urile in browserul implicit al sistemului (profilul tau obisnuit, fara WebDriver).
     */
    private static void openInSystemBrowser(List<String> urls, Pacing pacing) {

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IllegalStateException("Sistemul nu permite deschiderea browserului implicit.");
        }

        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            System.out.println("Deschid (" + (i + 1) + "/" + urls.size() + "): " + url);

            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (IOException | IllegalArgumentException exception) {
                System.out.println("Nu s-a putut deschide " + url + ": " + exception.getMessage());
            }

            if (!pauseAfter(i, urls.size(), pacing)) {
                break;
            }
        }

        System.out.println("Gata.");
    }

    /**
     * Deschide fiecare lot de batchSize URL-uri dintr-o data, ca tab-uri intr-o fereastra noua a browserului
     * (profilul tau obisnuit, fara WebDriver). Browserul vine in fata o singura data pe lot, nu la fiecare tab.
     * Intre loturi asteapta pauza de lot (urls.batchPauseMs / urls.batchPauseMaxMs).
     */
    private static void openInBatches(List<String> urls, Pacing pacing) {

        int batchSize = pacing.batchSize() > 0 ? pacing.batchSize() : DEFAULT_BATCH_SIZE;
        List<String> browserCommand = resolveBrowserCommand();
        System.out.println("Browser: " + browserCommand.get(0) + " | loturi de " + batchSize);

        for (int start = 0; start < urls.size(); start += batchSize) {
            int end = Math.min(start + batchSize, urls.size());
            List<String> batch = urls.subList(start, end);

            System.out.println("Deschid lotul " + (start + 1) + "-" + end + " din " + urls.size() + ":");
            batch.forEach(url -> System.out.println("  " + url));

            List<String> command = new ArrayList<>(browserCommand);
            command.addAll(batch);
            try {
                new ProcessBuilder(command).start();
            } catch (IOException exception) {
                throw new IllegalStateException("Nu am putut porni browserul: " + browserCommand.get(0), exception);
            }

            if (end == urls.size()) {
                break;
            }

            long sleepMs = randomBetween(pacing.batchPauseMs(), pacing.batchPauseMaxMs());
            System.out.println("Urmatorul lot porneste in " + (sleepMs / 1000) + " s.");
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Gata.");
    }

    /**
     * Executabilul browserului pentru modul batch: urls.browserExe daca e setat, altfel cautat dupa urls.browser
     * (edge | chrome | firefox) in locatiile standard de instalare.
     */
    private static List<String> resolveBrowserCommand() {

        String browser = readSetting("urls.browser", "URLS_BROWSER", "edge").trim().toLowerCase(Locale.ROOT);
        boolean firefox = browser.equals("firefox") || browser.equals("mozilla");
        String newWindowFlag = firefox ? "-new-window" : "--new-window";

        String explicitExe = readSetting("urls.browserExe", "URLS_BROWSER_EXE", "");
        if (!explicitExe.isBlank()) {
            return List.of(explicitExe.trim(), newWindowFlag);
        }

        String[] relativePaths;
        switch (browser) {
            case "chrome":
                relativePaths = new String[]{"Google\\Chrome\\Application\\chrome.exe"};
                break;
            case "firefox":
            case "mozilla":
                relativePaths = new String[]{"Mozilla Firefox\\firefox.exe"};
                break;
            case "edge":
            default:
                relativePaths = new String[]{"Microsoft\\Edge\\Application\\msedge.exe"};
                break;
        }

        String[] roots = {System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")};
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            for (String relativePath : relativePaths) {
                Path candidate = Path.of(root, relativePath);
                if (Files.isRegularFile(candidate)) {
                    return List.of(candidate.toString(), newWindowFlag);
                }
            }
        }

        throw new IllegalStateException("Nu am gasit browserul '" + browser + "'. Seteaza urls.browserExe cu calea completa.");
    }

    private static void openWithSelenium(List<String> urls, Pacing pacing) {

        boolean keepBrowserOpen = !"false".equalsIgnoreCase(readSetting("urls.keepOpen", "URLS_KEEP_OPEN", "true"));

        WebDriver driver = createDriver(keepBrowserOpen);

        try {
            driver.manage().window().maximize();
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));

            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                System.out.println("Deschid (" + (i + 1) + "/" + urls.size() + "): " + url);

                // Primul URL foloseste tab-ul deja deschis de browser.
                if (i > 0) {
                    driver.switchTo().newWindow(WindowType.TAB);
                }

                try {
                    driver.get(url);
                } catch (RuntimeException exception) {
                    System.out.println("Nu s-a putut incarca " + url + ": " + exception.getMessage());
                }

                if (!pauseAfter(i, urls.size(), pacing)) {
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

    /**
     * Pauzele dintre tab-uri (aleatoare intre min si max) si dintre loturi (dupa fiecare batchSize tab-uri).
     */
    private record Pacing(long delayMs, long delayMaxMs, int batchSize, long batchPauseMs, long batchPauseMaxMs) {

        static Pacing fromSettings() {

            long delayMs = parseDelay(readSetting("urls.delayMs", "URLS_DELAY_MS", "200"), 200);
            long delayMaxMs = parseDelay(readSetting("urls.delayMaxMs", "URLS_DELAY_MAX_MS", String.valueOf(delayMs)), delayMs);
            int batchSize = (int) parseDelay(readSetting("urls.batchSize", "URLS_BATCH_SIZE", "0"), 0);
            long batchPauseMs = parseDelay(readSetting("urls.batchPauseMs", "URLS_BATCH_PAUSE_MS", "60000"), 60000);
            long batchPauseMaxMs = parseDelay(readSetting("urls.batchPauseMaxMs", "URLS_BATCH_PAUSE_MAX_MS", String.valueOf(batchPauseMs)), batchPauseMs);

            return new Pacing(delayMs, Math.max(delayMs, delayMaxMs), batchSize, batchPauseMs, Math.max(batchPauseMs, batchPauseMaxMs));
        }
    }

    /**
     * Pauza dupa URL-ul cu indexul dat: la finalul fiecarui lot (batchSize > 0) pauza de lot, altfel pauza
     * dintre tab-uri. Intoarce false daca thread-ul a fost intrerupt (Stop in IDE).
     */
    private static boolean pauseAfter(int index, int total, Pacing pacing) {

        if (index == total - 1) {
            return true;
        }

        long sleepMs;
        if (pacing.batchSize() > 0 && (index + 1) % pacing.batchSize() == 0) {
            sleepMs = randomBetween(pacing.batchPauseMs(), pacing.batchPauseMaxMs());
            System.out.println("Lot terminat (" + (index + 1) + "/" + total + "). "
                    + "Urmatorul lot porneste in " + (sleepMs / 1000) + " s.");
        } else {
            sleepMs = randomBetween(pacing.delayMs(), pacing.delayMaxMs());
        }

        try {
            Thread.sleep(sleepMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long randomBetween(long min, long max) {

        return max > min ? min + ThreadLocalRandom.current().nextLong(max - min + 1) : min;
    }

    private static Path resolveUrlsFile(String[] args) {

        if (args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0].trim());
        }

        return Path.of(readSetting("urls.file", "URLS_FILE", DEFAULT_URLS_FILE));
    }

    /**
     * Citeste URL-urile: o linie = un URL. Liniile goale si cele care incep cu # sunt ignorate.
     * Duplicatele sunt eliminate, ordinea se pastreaza.
     */
    private static List<String> readUrls(Path urlsFile) throws IOException {

        if (!Files.isRegularFile(urlsFile)) {
            throw new IllegalStateException("Fisierul cu URL-uri nu exista: " + urlsFile.toAbsolutePath());
        }

        Set<String> urls = new LinkedHashSet<>();
        for (String line : Files.readAllLines(urlsFile, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("﻿")) {
                trimmed = trimmed.substring(1);
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (!trimmed.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
                trimmed = "https://" + trimmed;
            }
            urls.add(trimmed);
        }

        return new ArrayList<>(urls);
    }

    private static long parseDelay(String value, long defaultValue) {

        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException exception) {
            System.out.println("Valoare invalida: " + value + "; folosesc " + defaultValue + ".");
            return defaultValue;
        }
    }

    private static String readSetting(String propertyName, String envName, String defaultValue) {

        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        if (value == null || value.isBlank()) {
            value = defaultValue;
        }

        return value;
    }

    private static WebDriver createDriver(boolean keepBrowserOpen) {

        String browser = readSetting("urls.browser", "URLS_BROWSER", "edge");
        String normalized = browser.trim().toLowerCase(Locale.ROOT);
        System.out.println("Browser folosit: " + normalized);

        switch (normalized) {
            case "firefox":
            case "mozilla":
                configureLocalDriver("webdriver.gecko.driver", new String[]{
                        ".drivers/geckodriver/geckodriver.exe",
                        ".drivers/geckodriver.exe",
                        "drivers/geckodriver.exe"
                });
                return new FirefoxDriver();
            case "chrome":
                configureLocalDriver("webdriver.chrome.driver", new String[]{
                        ".drivers/chromedriver-win64/chromedriver.exe",
                        ".drivers/chromedriver.exe",
                        "drivers/chromedriver.exe"
                });
                return new ChromeDriver(applyCommonOptions(new ChromeOptions(), keepBrowserOpen));
            case "edge":
            default:
                configureLocalDriver("webdriver.edge.driver", new String[]{
                        ".drivers/edgedriver_win64/msedgedriver.exe",
                        ".drivers/msedgedriver.exe",
                        "drivers/msedgedriver.exe"
                });
                return new EdgeDriver(applyCommonOptions(new EdgeOptions(), keepBrowserOpen));
        }
    }

    private static <T extends ChromiumOptions<?>> T applyCommonOptions(T options, boolean keepBrowserOpen) {

        // Workaround for DevTools websocket 403 on older Selenium clients.
        options.addArguments("--remote-allow-origins=*");
        if (keepBrowserOpen) {
            options.setExperimentalOption("detach", true);
        }

        return options;
    }

    /**
     * Daca driverul nu e deja configurat si exista unul local in proiect (descarcat cu scripts/setup-*.ps1),
     * il folosim. Altfel lasam Selenium Manager sa gaseasca/descarce driverul potrivit.
     */
    private static void configureLocalDriver(String propertyName, String[] localCandidates) {

        String configured = System.getProperty(propertyName);
        if (configured != null && !configured.isBlank()) {
            return;
        }

        Path workingDirectory = Path.of("").toAbsolutePath();
        for (String localCandidate : localCandidates) {
            try {
                Path candidate = workingDirectory.resolve(localCandidate);
                if (Files.isRegularFile(candidate)) {
                    System.setProperty(propertyName, candidate.toString());
                    return;
                }
            } catch (InvalidPathException ignored) {
                // Continuam cu urmatorul candidat.
            }
        }
    }
}
