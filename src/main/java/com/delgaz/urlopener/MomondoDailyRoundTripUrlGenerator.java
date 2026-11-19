package com.delgaz.urlopener;

import com.delgaz.urlopener.helpers.AfterGenerate;
import com.delgaz.urlopener.helpers.FlightSite;
import com.delgaz.urlopener.helpers.FlightPriceScraper;
import com.delgaz.urlopener.helpers.MomondoQuery;
import com.delgaz.urlopener.helpers.SkyscannerUrls;
import com.delgaz.urlopener.helpers.TripUrls;
import com.delgaz.urlopener.helpers.UrlListOpener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Genereaza URL-uri Momondo dus-intors simplu, pentru o singura destinatie, cu plecare in zilele DEPARTURE_DAYS
 * (sau in fiecare zi, daca DEPARTURE_DAYS e gol) din perioada DEPARTURE_FROM..DEPARTURE_TO:
 *
 *   https://www.momondo.ro/flight-search/IAS-FNC/{plecare}/{intoarcere}?sort=price_a
 *
 * le scrie in OUTPUT_FILE si apoi, dupa AFTER_GENERATE, citeste preturile cu {@link FlightPriceScraper}
 * sau le deschide in browser prin {@link UrlListOpener#openUrls(List)}.
 *
 * Pentru itinerariul cu 4 zboruri in anumite zile ale saptamanii vezi {@link MomondoMultiCityUrlGenerator}.
 */
public class MomondoDailyRoundTripUrlGenerator {

    // ---------------------------------------------------------------------
    // Ruta (dus); intoarcerea este automat pe ruta inversa
    // ---------------------------------------------------------------------
        public static final String ROUTE = "BUH-BJS";

    // ---------------------------------------------------------------------
    // Perioada in care poate fi plecarea, inclusiv capetele (filtrata pe DEPARTURE_DAYS)
    // ---------------------------------------------------------------------
    static final LocalDate DEPARTURE_FROM = LocalDate.of(2027, 2, 1);
    static final LocalDate DEPARTURE_TO = LocalDate.of(2027, 2, 28);

    // ---------------------------------------------------------------------
    // Zilele saptamanii in care poate fi plecarea.
    // Pentru plecare in orice zi: comenteaza linia activa si decomenteaza "Set.of()" (set gol = toate zilele).
    // ---------------------------------------------------------------------
    static final Set<DayOfWeek> DEPARTURE_DAYS = Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY);
    //static final Set<DayOfWeek> DEPARTURE_DAYS = Set.of();

    // ---------------------------------------------------------------------
    // Durata calatoriei (in zile): intoarcerea = plecare + TRIP_MIN_DAYS..TRIP_MAX_DAYS
    // 2 = 3 zile calendaristice inclusiv capetele (ex. 2027-04-11 -> 2027-04-13).
    // ---------------------------------------------------------------------
    static final int TRIP_MIN_DAYS = 21;
    static final int TRIP_MAX_DAYS = 21;

    // ---------------------------------------------------------------------
    // URL si iesire
    // ---------------------------------------------------------------------
    /**
     * Site-ul pe care se cauta: MOMONDO, SKYSCANNER sau TRIP (KIWI nu e implementat la dus-intors).
     * Filtrele: MOMONDO - AIRLINES, NONSTOP_ONLY, SORT; TRIP - AIRLINES, NONSTOP_ONLY; SKYSCANNER - niciunul.
     * Preturile se citesc automat pe MOMONDO, SKYSCANNER si TRIP.
     */
    static final FlightSite SITE = FlightSite.MOMONDO;

    static final String BASE_URL = "https://www.momondo.ro/flight-search/";

    /** Compania (companiile) aeriana: "CA" = Air China; mai multe: "CA,MU"; "" = toate companiile. */
    static final String AIRLINES = "";
    //static final String AIRLINES = "CA";
    /** Moneda preturilor pe TRIP: "EUR", "USD" ... */
    static final String CURRENCY = "EUR";
    //static final String CURRENCY = "USD";
    /** true = doar zboruri directe. */
    static final boolean NONSTOP_ONLY = true;
    /** Sortarea rezultatelor: "bestflight_a" (cel mai bun) sau "price_a" (cel mai ieftin). */
    static final String SORT = "bestflight_a";

    /** ex. ?fs=airlines%3DCA%2CMULT%3Bstops%3D0&sort=bestflight_a */
    static final String QUERY = MomondoQuery.build(SORT, AIRLINES, NONSTOP_ONLY);

    /** Luna (lunile) cautata, pentru numele fisierelor: 2027-02, sau 2027-01_2027-03 daca perioada are mai multe luni. */
    static final String MONTH_LABEL = YearMonth.from(DEPARTURE_FROM).equals(YearMonth.from(DEPARTURE_TO))
            ? YearMonth.from(DEPARTURE_FROM).toString()
            : YearMonth.from(DEPARTURE_FROM) + "_" + YearMonth.from(DEPARTURE_TO);

    static final Path OUTPUT_FILE = newOutputFile(SITE.name().toLowerCase(java.util.Locale.ROOT) + "-dus-intors-zilnic-urls", "txt");

    /**
     * Ce se intampla dupa generare: READ_PRICES = citeste preturile in Excel (Selenium Edge),
     * OPEN_IN_BROWSER = doar deschide URL-urile in browser, NOTHING = doar fisierul .txt.
     */
    static final AfterGenerate AFTER_GENERATE = AfterGenerate.READ_PRICES;

    /**
     * Fisier nou pentru fiecare rulare, ca sa nu suprascriem niciodata rezultatele vechi:
     * url-lists/{luna}_{ruta}_{prefix}_{data-ora rularii}.{extensie}; daca exista deja, adauga _2, _3 ...
     */
    public static Path newOutputFile(String prefix, String extension) {

        String base = "url-lists/" + MONTH_LABEL + "_" + ROUTE.trim() + "_" + prefix + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = Path.of(base + "." + extension);
        for (int i = 2; Files.exists(file); i++) {
            file = Path.of(base + "_" + i + "." + extension);
        }
        return file;
    }

    public static void main(String[] args) throws IOException {

        List<String> urls = generateUrls();

        if (urls.isEmpty()) {
            throw new IllegalStateException("Nicio combinatie nu respecta conditiile.");
        }

        writeUrls(urls);
        System.out.println("Combinatii generate: " + urls.size() + " -> " + OUTPUT_FILE.toAbsolutePath());

        AfterGenerate afterGenerate = AFTER_GENERATE;
        if (afterGenerate == AfterGenerate.READ_PRICES && !SITE.canReadPrices()) {
            System.out.println("Citirea preturilor nu e implementata pe " + SITE + "; deschid URL-urile in browser.");
            afterGenerate = AfterGenerate.OPEN_IN_BROWSER;
        }

        switch (afterGenerate) {
            case READ_PRICES -> FlightPriceScraper.readPrices(FlightPriceScraper.Search.DAILY_ROUND_TRIP, SITE, urls);
            case OPEN_IN_BROWSER -> UrlListOpener.openUrls(urls);
            case NOTHING -> { }
        }
    }

    /** Plecare in zilele DEPARTURE_DAYS din perioada (toate zilele daca e gol), intoarcere dupa TRIP_MIN_DAYS..TRIP_MAX_DAYS zile. */
    public static List<String> generateUrls() {

        List<String> urls = new ArrayList<>();

        for (LocalDate departure = DEPARTURE_FROM; !departure.isAfter(DEPARTURE_TO); departure = departure.plusDays(1)) {
            if (!DEPARTURE_DAYS.isEmpty() && !DEPARTURE_DAYS.contains(departure.getDayOfWeek())) {
                continue;
            }
            for (int tripDays = TRIP_MIN_DAYS; tripDays <= TRIP_MAX_DAYS; tripDays++) {
                urls.add(buildUrl(departure, departure.plusDays(tripDays)));
            }
        }

        return urls;
    }

    static String buildUrl(LocalDate departure, LocalDate ret) {

        return switch (SITE) {
            // LocalDate.toString() produce formatul ISO yyyy-MM-dd, exact ce asteapta Momondo.
            case MOMONDO -> BASE_URL + ROUTE.trim() + "/" + departure + "/" + ret + QUERY;
            case SKYSCANNER -> SkyscannerUrls.roundTrip(ROUTE, departure, ret);
            case TRIP -> TripUrls.roundTrip(ROUTE, departure, ret, AIRLINES, NONSTOP_ONLY, CURRENCY);
            case KIWI, AIRCHINA -> throw new IllegalStateException(SITE + " nu e implementat la dus-intors.");
        };
    }

    private static void writeUrls(List<String> urls) throws IOException {

        Path parent = OUTPUT_FILE.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(OUTPUT_FILE, urls, StandardCharsets.UTF_8);
    }
}
