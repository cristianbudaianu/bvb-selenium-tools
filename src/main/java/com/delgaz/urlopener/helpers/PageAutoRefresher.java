package com.delgaz.urlopener.helpers;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Asteapta pana cand o pagina de produs eMAG trece de pagina de asteptare "eMAG Black Friday" si te anunta
 * (sunet + notificare Windows), lasand browserul deschis pe produs ca sa poti comanda imediat.
 *
 * Cum lucreaza:
 *  - da refresh la pagina; daca titlul contine inca "eMAG Black Friday", e pagina de asteptare si continua;
 *  - daca titlul s-a schimbat (pagina produsului), suna alarma si se OPRESTE din refresh;
 *  - daca pare blocat (captcha, "access denied", "too many requests"), suna alt tip de alarma si da refresh doar
 *    rar (~3 min), pana rezolvi tu captcha sau trece blocarea; cand se deblocheaza suna a treia alarma
 *    (do-mi-sol), apoi continua cu intervalul dublat (backoff);
 *  - daca setezi ora de start (refresh.startAt), da refresh RAR (refresh.slowSeconds) pana cu refresh.leadMinutes
 *    inainte de ora, DES (refresh.seconds) in jurul orei, apoi iar rar. Asa faci putine cereri => risc mic de blocare.
 *
 * Configurare (System property / variabila de mediu):
 *  - refresh.url          / REFRESH_URL           : URL-ul (implicit televizorul LG de pe eMAG)
 *  - refresh.startAt      / REFRESH_START_AT      : ora de start a campaniei, HH:mm (ex. 07:00); fara ea, mereu des
 *  - refresh.leadMinutes  / REFRESH_LEAD_MINUTES  : cu cate minute inainte de start trece pe refresh des (implicit 10)
 *  - refresh.fastMinutes  / REFRESH_FAST_MINUTES  : cate minute dupa start ramane pe refresh des (implicit 30)
 *  - refresh.seconds      / REFRESH_SECONDS       : intervalul des, in secunde (implicit 15)
 *  - refresh.jitter       / REFRESH_JITTER        : variatie aleatoare +/- pentru intervalul des (implicit 5)
 *  - refresh.slowSeconds  / REFRESH_SLOW_SECONDS  : intervalul rar, in secunde (implicit 300; variatie +/- 25%)
 *  - refresh.waitingTitle / REFRESH_WAITING_TITLE : text din titlul paginii de asteptare (implicit "eMAG Black Friday")
 *  - refresh.browser      / REFRESH_BROWSER       : edge | chrome | firefox (implicit edge)
 *  - refresh.profileDir   / REFRESH_PROFILE_DIR   : profilul persistent al browserului (implicit .browser-profile/BROWSER)
 *
 * Primul argument din linia de comanda, daca exista, suprascrie URL-ul.
 */
public class PageAutoRefresher {

    private static final String DEFAULT_URL =
            "https://www.emag.ro/televizor-lg-led-138-cm-smart-4k-ultra-hd-clasa-f-model-2026-55nu850b3la/pd/DZN3GS2BM/"
                    + "?ref=bf26_ro_guide#specification-section";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_BACKOFF = 8;

    private static final String[] BLOCKED_MARKERS = {
            "captcha", "nu esti robot", "nu ești robot", "not a robot", "access denied", "acces interzis",
            "too many requests", "prea multe cereri", "just a moment", "attention required", "error 403", "error 429"
    };

    /** Cat de des (aprox., in secunde) verifica cu un refresh daca s-a ridicat o blocare fara captcha. */
    private static final long BLOCKED_CHECK_SECONDS = 180;

    private enum PageState { WAITING, READY, BLOCKED, UNKNOWN }

    private enum Sound { READY, BLOCKED, UNBLOCKED }

    private static TrayIcon trayIcon;

    public static void main(String[] args) {

        String url = args.length > 0 && !args[0].isBlank()
                ? args[0].trim()
                : readSetting("refresh.url", "REFRESH_URL", DEFAULT_URL);
        long fastSeconds = parseLong(readSetting("refresh.seconds", "REFRESH_SECONDS", "15"), 15, 1);
        long fastJitter = Math.min(fastSeconds - 1, parseLong(readSetting("refresh.jitter", "REFRESH_JITTER", "5"), 5, 0));
        long slowSeconds = parseLong(readSetting("refresh.slowSeconds", "REFRESH_SLOW_SECONDS", "300"), 300, 1);
        long leadMinutes = parseLong(readSetting("refresh.leadMinutes", "REFRESH_LEAD_MINUTES", "10"), 10, 0);
        long fastMinutes = parseLong(readSetting("refresh.fastMinutes", "REFRESH_FAST_MINUTES", "30"), 30, 0);
        String waitingTitle = readSetting("refresh.waitingTitle", "REFRESH_WAITING_TITLE", "eMAG Black Friday")
                .trim().toLowerCase(Locale.ROOT);
        LocalDateTime startAt = parseStartAt(readSetting("refresh.startAt", "REFRESH_START_AT", ""), fastMinutes);

        if (startAt == null) {
            log("Fara ora de start: refresh des (" + fastSeconds + " +/- " + fastJitter + " s) tot timpul. "
                    + "Recomandat: -Drefresh.startAt=HH:mm, ca sa faci putine cereri pana aproape de start.");
        } else {
            log("Start campanie: " + startAt + ". Refresh rar (~" + slowSeconds + " s) pana la "
                    + startAt.minusMinutes(leadMinutes).toLocalTime() + ", apoi des (" + fastSeconds + " +/- "
                    + fastJitter + " s) pana la " + startAt.plusMinutes(fastMinutes).toLocalTime() + ".");
        }

        WebDriver driver = createDriver();
        try {
            driver.manage().window().maximize();
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));

            log("Deschid: " + url);
            log("Daca nu esti logat pe eMAG in fereastra asta, logheaza-te acum (profilul tine minte login-ul).");
            safely(() -> driver.get(url));

            int backoff = 1;
            long count = 0;
            while (!Thread.currentThread().isInterrupted()) {
                PageState state = detect(driver, waitingTitle);
                String title = safeTitle(driver);

                if (state == PageState.READY) {
                    log("PAGINA PRODUSULUI E DISPONIBILA: " + title);
                    bringToFront(driver);
                    alarm("Pagina produsului e disponibila! Comanda acum.", Sound.READY);
                    log("M-am oprit din refresh. Fereastra ramane deschisa; apasa Stop in IDE cand termini.");
                    waitForever();
                    return;
                }

                if (state == PageState.BLOCKED) {
                    backoff = Math.min(backoff * 2, MAX_BACKOFF);
                    log("POSIBIL BLOCAT (" + title + "). Daca vezi captcha, rezolv-o in fereastra. "
                            + "Altfel verific singur, rar (la ~" + (BLOCKED_CHECK_SECONDS / 60) + " min), daca s-a deblocat.");
                    bringToFront(driver);
                    alarm("eMAG pare sa te fi blocat (captcha?). Rezolva manual in fereastra.", Sound.BLOCKED);
                    waitUntilNotBlocked(driver, waitingTitle);
                    bringToFront(driver);
                    alarm("S-a deblocat! Pagina se poate accesa din nou: " + safeTitle(driver), Sound.UNBLOCKED);
                    log("Continui cu intervalul x" + backoff + ".");
                    continue;
                }

                boolean fast = isFastWindow(startAt, leadMinutes, fastMinutes);
                long baseMs = (fast ? fastSeconds : slowSeconds) * 1000L * backoff;
                long jitterMs = fast ? fastJitter * 1000L * backoff : baseMs / 4;
                long sleepMs = Math.max(1000, baseMs + ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1));

                log((state == PageState.WAITING ? "Inca pagina de asteptare" : "Stare neclara (" + title + ")")
                        + ". Urmatorul refresh in " + (sleepMs / 1000) + " s" + (fast ? " [des]" : " [rar]") + ".");
                Thread.sleep(sleepMs);

                // Din cand in cand, un mic scroll, ca un om care se uita pe pagina.
                if (ThreadLocalRandom.current().nextInt(3) == 0) {
                    int pixels = ThreadLocalRandom.current().nextInt(-300, 600);
                    safely(() -> ((JavascriptExecutor) driver).executeScript(
                            "window.scrollBy({top: arguments[0], behavior: 'smooth'});", pixels));
                }

                count++;
                safely(() -> driver.navigate().refresh());
                log("Refresh #" + count);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            driver.quit();
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        }
    }

    // ---------------------------------------------------------------- detectie

    private static PageState detect(WebDriver driver, String waitingTitle) {

        String title = safeTitle(driver).toLowerCase(Locale.ROOT);
        String visibleText = visibleText(driver);

        for (String marker : BLOCKED_MARKERS) {
            if (title.contains(marker) || visibleText.contains(marker)) {
                return PageState.BLOCKED;
            }
        }
        if (title.isBlank() || title.equals("(titlu indisponibil)")) {
            return PageState.UNKNOWN;
        }
        if (title.contains(waitingTitle)) {
            return PageState.WAITING;
        }
        return PageState.READY;
    }

    /** Primele ~3000 de caractere din textul VIZIBIL al paginii (nu din scripturi), lowercase. */
    private static String visibleText(WebDriver driver) {
        try {
            Object text = ((JavascriptExecutor) driver).executeScript(
                    "return document.body ? document.body.innerText.substring(0, 3000) : '';");
            return text == null ? "" : text.toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    /**
     * Asteapta pana nu mai pare blocat. La fiecare 5 s citeste pagina deja incarcata (fara cereri catre site), ca sa
     * observe imediat daca ai rezolvat captcha. Rar (~BLOCKED_CHECK_SECONDS, +/- 25%) da si un refresh, pentru
     * blocarile fara captcha (ex. "too many requests") care trec singure dupa un timp.
     */
    private static void waitUntilNotBlocked(WebDriver driver, String waitingTitle) throws InterruptedException {
        long nextRefreshAt = nextBlockedCheck();
        while (detect(driver, waitingTitle) == PageState.BLOCKED) {
            Thread.sleep(5000);
            if (System.currentTimeMillis() >= nextRefreshAt) {
                log("Verific daca s-a deblocat...");
                safely(() -> driver.navigate().refresh());
                nextRefreshAt = nextBlockedCheck();
            }
        }
    }

    private static long nextBlockedCheck() {
        long baseMs = BLOCKED_CHECK_SECONDS * 1000L;
        long jitterMs = baseMs / 4;
        return System.currentTimeMillis() + baseMs + ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1);
    }

    // ---------------------------------------------------------------- program orar

    /** Ora de start de azi; daca fereastra de refresh des a trecut deja, presupunem ca e maine. */
    private static LocalDateTime parseStartAt(String value, long fastMinutes) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDateTime start = LocalDate.now().atTime(LocalTime.parse(value.trim()));
            if (LocalDateTime.now().isAfter(start.plusMinutes(fastMinutes))) {
                start = start.plusDays(1);
            }
            return start;
        } catch (RuntimeException exception) {
            log("Ora de start invalida: " + value + " (format HH:mm). O ignor.");
            return null;
        }
    }

    private static boolean isFastWindow(LocalDateTime startAt, long leadMinutes, long fastMinutes) {
        if (startAt == null) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(startAt.minusMinutes(leadMinutes)) && !now.isAfter(startAt.plusMinutes(fastMinutes));
    }

    // ---------------------------------------------------------------- alarma

    /**
     * Notificare Windows + sunet, diferit pe situatie:
     *  - READY     (a aparut produsul): doua tonuri care urca, de 6 ori;
     *  - BLOCKED   (captcha / blocat) : ton grav, lung, de 6 ori;
     *  - UNBLOCKED (s-a deblocat)     : trei tonuri scurte care urca (do-mi-sol), de 3 ori.
     */
    private static void alarm(String message, Sound sound) {
        log("*** " + message + " ***");
        showNotification(message, sound != Sound.BLOCKED);
        switch (sound) {
            case READY:
                for (int i = 0; i < 6; i++) {
                    playTone(880, 250);
                    playTone(1320, 350);
                    sleepQuietly(250);
                }
                break;
            case BLOCKED:
                for (int i = 0; i < 6; i++) {
                    playTone(330, 500);
                    sleepQuietly(250);
                }
                break;
            case UNBLOCKED:
            default:
                for (int i = 0; i < 3; i++) {
                    playTone(523, 150);
                    playTone(659, 150);
                    playTone(784, 300);
                    sleepQuietly(400);
                }
                break;
        }
    }

    private static void showNotification(String message, boolean ready) {
        try {
            if (!SystemTray.isSupported()) {
                return;
            }
            if (trayIcon == null) {
                BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
                trayIcon = new TrayIcon(image, "PageAutoRefresher");
                trayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(trayIcon);
            }
            trayIcon.displayMessage("eMAG", message, ready ? TrayIcon.MessageType.INFO : TrayIcon.MessageType.WARNING);
        } catch (Exception exception) {
            log("Nu am putut afisa notificarea: " + exception.getMessage());
        }
    }

    private static void playTone(int hz, int millis) {
        float sampleRate = 44100f;
        try (SourceDataLine line = AudioSystem.getSourceDataLine(new AudioFormat(sampleRate, 8, 1, true, false))) {
            line.open();
            line.start();
            byte[] buffer = new byte[(int) (sampleRate * millis / 1000)];
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = (byte) (Math.sin(2 * Math.PI * i * hz / sampleRate) * 100);
            }
            line.write(buffer, 0, buffer.length);
            line.drain();
        } catch (Exception exception) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    // ---------------------------------------------------------------- utilitare

    private static void bringToFront(WebDriver driver) {
        safely(() -> {
            driver.switchTo().window(driver.getWindowHandle());
            driver.manage().window().maximize();
        });
    }

    private static void waitForever() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(1000);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log("Eroare: " + exception.getMessage());
        }
    }

    private static String safeTitle(WebDriver driver) {
        try {
            String title = driver.getTitle();
            return title == null ? "" : title;
        } catch (RuntimeException exception) {
            return "(titlu indisponibil)";
        }
    }

    private static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + message);
    }

    private static long parseLong(String value, long defaultValue, long min) {
        try {
            return Math.max(min, Long.parseLong(value.trim()));
        } catch (NumberFormatException exception) {
            log("Valoare invalida: " + value + "; folosesc " + defaultValue + ".");
            return defaultValue;
        }
    }

    private static String readSetting(String propertyName, String envName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    // ---------------------------------------------------------------- browser

    private static WebDriver createDriver() {

        String browser = readSetting("refresh.browser", "REFRESH_BROWSER", "edge").trim().toLowerCase(Locale.ROOT);
        log("Browser folosit: " + browser);

        switch (browser) {
            case "firefox":
            case "mozilla":
                useLocalDriverIfPresent("webdriver.gecko.driver", ".drivers/geckodriver/geckodriver.exe");
                FirefoxOptions firefox = new FirefoxOptions();
                firefox.addArguments("-profile", profileDir("firefox").toString());
                return new FirefoxDriver(firefox);
            case "chrome":
                useLocalDriverIfPresent("webdriver.chrome.driver", ".drivers/chromedriver-win64/chromedriver.exe");
                return new ChromeDriver(humanLike(new ChromeOptions(), "chrome"));
            case "edge":
            default:
                useLocalDriverIfPresent("webdriver.edge.driver", ".drivers/edgedriver_win64/msedgedriver.exe");
                return new EdgeDriver(humanLike(new EdgeOptions(), "edge"));
        }
    }

    /**
     * Face browserul pornit de Selenium sa arate ca unul obisnuit:
     *  - profil persistent (cookie-uri, login, consimtamant cookie pastrate intre rulari, ca un vizitator care revine);
     *  - fara bara "browserul e controlat de software automat" si fara navigator.webdriver = true.
     */
    private static <T extends ChromiumOptions<?>> T humanLike(T options, String browser) {
        options.addArguments(
                "--remote-allow-origins=*",
                "--user-data-dir=" + profileDir(browser),
                "--disable-blink-features=AutomationControlled",
                "--lang=ro-RO");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);
        return options;
    }

    private static Path profileDir(String browser) {
        Path dir = Path.of(readSetting("refresh.profileDir", "REFRESH_PROFILE_DIR",
                ".browser-profile/" + browser)).toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            log("Nu am putut crea profilul " + dir + ": " + exception.getMessage());
        }
        return dir;
    }

    /** Foloseste driverul local din proiect daca exista; altfel Selenium Manager il gaseste/descarca. */
    private static void useLocalDriverIfPresent(String propertyName, String relativePath) {
        String configured = System.getProperty(propertyName);
        if (configured != null && !configured.isBlank()) {
            return;
        }
        Path candidate = Path.of("").toAbsolutePath().resolve(relativePath);
        if (Files.isRegularFile(candidate)) {
            System.setProperty(propertyName, candidate.toString());
        }
    }
}
