package com.delgaz.urlopener;

import com.delgaz.urlopener.helpers.AfterGenerate;
import com.delgaz.urlopener.helpers.AirChinaUrls;
import com.delgaz.urlopener.helpers.FlightSite;
import com.delgaz.urlopener.helpers.KiwiUrls;
import com.delgaz.urlopener.helpers.SkyscannerUrls;
import com.delgaz.urlopener.helpers.TripUrls;
import com.delgaz.urlopener.helpers.FlightPriceScraper;
import com.delgaz.urlopener.helpers.MomondoQuery;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Genereaza toate combinatiile de URL-uri Momondo multi-city de forma:
 *
 *   https://www.momondo.ro/flight-search/BUH-BJS/{plecare}/BJS-BKK/{dus2}/BKK-BJS/{intors2}/BJS-BUH/{intoarcere}/?sort=...
 *
 * le scrie in OUTPUT_FILE si apoi, dupa AFTER_GENERATE, citeste preturile cu {@link FlightPriceScraper}
 * sau le deschide in browser prin {@link UrlListOpener#openUrls(List)}.
 *
 * Toate regulile sunt variabilele de mai jos - modifica-le pentru a schimba algoritmul.
 *
 * Pentru dus-intors simplu, cu plecare in fiecare zi, vezi {@link MomondoDailyRoundTripUrlGenerator}.
 */
public class MomondoMultiCityUrlGenerator {

    // ---------------------------------------------------------------------
    // Rute
    // ---------------------------------------------------------------------
    /** Zborul 1: plecarea din tara. */
    public static final String LEG1 = "BUH-BJS";
    /** Zborul 2: din primul oras spre al doilea. */
    public static final String LEG2 = "BJS-BKK";
    /** Zborul 3: intoarcerea in primul oras. */
    public static final String LEG3 = "BKK-BJS";
    /** Zborul 4: intoarcerea acasa. */
    public static final String LEG4 = "BJS-BUH";

    // ---------------------------------------------------------------------
    // Perioada in care poate fi plecarea (zborul 1), inclusiv capetele
    // ---------------------------------------------------------------------

    static final LocalDate DEPARTURE_FROM = LocalDate.of(2027, 3, 1);
    static final LocalDate DEPARTURE_TO = LocalDate.of(2027, 3, 31);

    /** Zilele saptamanii in care exista zboruri (folosite pentru plecare - zborul 1 - si intoarcere - zborul 4). */
    static final List<DayOfWeek> FLIGHT_DAYS = List.of(
            DayOfWeek.MONDAY,
            //DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            //DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
            //,DayOfWeek.SATURDAY,
            //DayOfWeek.SUNDAY
    );

    //martie
    //static final LocalDate DEPARTURE_FROM = LocalDate.of(2027, 3, 1);
   //static final LocalDate DEPARTURE_TO = LocalDate.of(2027, 3, 31);

    // ---------------------------------------------------------------------
    // Zilele permise pentru plecare (zborul 1) si intoarcere acasa (zborul 4)
    // ---------------------------------------------------------------------
    static final Set<DayOfWeek> DEPARTURE_DAYS = EnumSet.copyOf(FLIGHT_DAYS);
    static final Set<DayOfWeek> RETURN_DAYS = EnumSet.copyOf(FLIGHT_DAYS);
    //static final Set<DayOfWeek> DEPARTURE_DAYS =
    //        EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
    //static final Set<DayOfWeek> RETURN_DAYS =
    //        EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);

    // ---------------------------------------------------------------------
    // Distante (in zile)
    // ---------------------------------------------------------------------
    // Nopti = diferenta dintre datele zborurilor (ex. zborul 1 pe 01.03, zborul 2 pe 10.03 -> 9 nopti).
    //
    //   Beijing (prima sedere):  8 sau 9 nopti        zborul 1 -> zborul 2
    //   Bangkok:                 diferenta pana la 21 zborul 2 -> zborul 3
    //   Beijing (a doua sedere): 2 sau 3 nopti        zborul 3 -> zborul 4
    //   Beijing in total:        11 nopti  => 8 + 3 sau 9 + 2  (deci Bangkok = 21 - 11 = 10 nopti)
    //
    // Plecarea si intoarcerea acasa sunt in aceeasi zi a saptamanii (21 de zile), din FLIGHT_DAYS.

    /**
     * Zborul 4 fata de zborul 1.
     * 6 = 7 zile calendaristice inclusiv capetele (ex. 2027-04-11 -> 2027-04-17).
     * Pentru 3 saptamani foloseste 21.
     */
    static final int TRIP_MIN_DAYS = 21;
    static final int TRIP_MAX_DAYS = 21;

    /**
     * true = pe langa intoarcerea dupa TRIP_MIN_DAYS..TRIP_MAX_DAYS zile, cauta si intoarcerea in prima zi din
     * FLIGHT_DAYS (RETURN_DAYS) dinainte de expirarea celor TRIP_MIN_DAYS zile.
     * Ex. plecare luni 2027-02-01, 21 zile -> intoarcere luni 2027-02-22 si vineri 2027-02-19.
     * false = intoarcerea exact dupa 21 de zile, in aceeasi zi a saptamanii cu plecarea.
     */
    static final boolean ALSO_RETURN_ON_PREVIOUS_FLIGHT_DAY = false;

    /** Nopti in Beijing la prima sedere: zborul 2 la cate zile DUPA zborul 1. */
    static final int LEG2_AFTER_LEG1_MIN_DAYS = 8;
    static final int LEG2_AFTER_LEG1_MAX_DAYS = 9;

    /** Nopti in Beijing la a doua sedere: zborul 3 la cate zile INAINTE de zborul 4. */
    static final int LEG3_BEFORE_LEG4_MIN_DAYS = 2;
    static final int LEG3_BEFORE_LEG4_MAX_DAYS = 3;

    /** Nopti in Beijing in total: (zborul 1 -> zborul 2) + (zborul 3 -> zborul 4) trebuie sa fie exact atat. */
    static final int BEIJING_TOTAL_NIGHTS = 11;

    /** Minim de zile intre zborul 2 si zborul 3 (sederea in al doilea oras). */
    static final int MIN_DAYS_BETWEEN_LEG2_AND_LEG3 = 1;

    // ---------------------------------------------------------------------
    // URL si iesire
    // ---------------------------------------------------------------------
    //TRIP.com
    //https://www.trip.com/flights/showfarefirst?multdcity0=buh&multacity0=bjs&multdcityid0=674&multacityid0=1&multddate0=2027-02-10&multdcity1=bjs&multacity1=bkk&multdcityid1=1&multacityid1=359&multddate1=2027-02-12&multdcity2=bkk&multacity2=bjs&multdcityid2=359&multacityid2=1&multddate2=2027-03-01&multdcity3=bjs&multacity3=buh&multdcityid3=1&multacityid3=674&multddate3=2027-03-03&triptype=mt&class=y&lowpricesource=searchform&quantity=1&searchboxarg=t&nonstoponly=on&locale=en-XX&curr=USD
    /**
     * Site-ul pe care se cauta: MOMONDO, KIWI, SKYSCANNER, TRIP sau AIRCHINA.
     * La KIWI / SKYSCANNER filtrele AIRLINES / NONSTOP_ONLY / SORT nu se aplica; la TRIP se aplica AIRLINES si
     * NONSTOP_ONLY; la AIRCHINA se folosesc AIRCHINA_FLIGHT_NUMBERS (zboruri exacte).
     * Preturile se citesc automat pe MOMONDO, SKYSCANNER si TRIP; la KIWI / AIRCHINA, READ_PRICES deschide
     * URL-urile in browser.
     */
    static final FlightSite SITE = FlightSite.AIRCHINA;

    /**
     * Doar pentru SITE = AIRCHINA: numarul de zbor Air China pentru fiecare ruta, in ordinea LEG1..LEG4.
     * BUH-BJS = CA914, BJS-BKK = CA979, BKK-BJS = CA960, BJS-BUH = CA913.
     * Zborul trebuie sa opereze in ziua generata, altfel pagina Air China nu gaseste tariful.
     */
    static final String[] AIRCHINA_FLIGHT_NUMBERS = {"914", "979", "960", "913"};

    static final String BASE_URL = "https://www.momondo.ro/flight-search/";

    /** Compania (companiile) aeriana: "CA" = Air China; mai multe: "CA,MU"; "" = toate companiile. */
    static final String AIRLINES = "CA";
    /** true = doar zboruri directe. */
    static final boolean NONSTOP_ONLY = true;
    /** Sortarea rezultatelor: "bestflight_a" (cel mai bun) sau "price_a" (cel mai ieftin). */
    static final String SORT = "price_a";
    //static final String SORT = "bestflight_a";

    /** ex. ?fs=airlines%3DCA%2CMULT%3Bstops%3D0&sort=bestflight_a */
    static final String QUERY = MomondoQuery.build(SORT, AIRLINES, NONSTOP_ONLY);

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Luna (lunile) cautata, pentru numele fisierelor: 2027-02, sau 2027-01_2027-03 daca perioada are mai multe luni. */
    static final String MONTH_LABEL = YearMonth.from(DEPARTURE_FROM).equals(YearMonth.from(DEPARTURE_TO))
            ? YearMonth.from(DEPARTURE_FROM).toString()
            : YearMonth.from(DEPARTURE_FROM) + "_" + YearMonth.from(DEPARTURE_TO);

    /** Itinerariul, pentru numele fisierelor: BUH-BJS + BJS-HAN + HAN-BJS + BJS-BUH -> BUH-BJS-HAN-BJS-BUH. */
    static final String ITINERARY_LABEL = buildItineraryLabel(LEG1, LEG2, LEG3, LEG4);

    static final Path OUTPUT_FILE = newOutputFile(SITE.name().toLowerCase(java.util.Locale.ROOT) + "-multicity-urls", "txt");

    /**
     * Fisier nou pentru fiecare rulare, ca sa nu suprascriem niciodata rezultatele vechi:
     * url-lists/{luna}_{itinerariu}_{prefix}_{data-ora rularii}.{extensie}; daca exista deja, adauga _2, _3 ...
     */
    public static Path newOutputFile(String prefix, String extension) {

        String base = "url-lists/" + MONTH_LABEL + "_" + ITINERARY_LABEL + "_" + prefix + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = Path.of(base + "." + extension);
        for (int i = 2; Files.exists(file); i++) {
            file = Path.of(base + "_" + i + "." + extension);
        }
        return file;
    }

    /** Lipeste rutele, fara sa repete aeroportul comun: A-B + B-C -> A-B-C. */
    private static String buildItineraryLabel(String... legs) {

        List<String> airports = new ArrayList<>();
        for (String leg : legs) {
            if (isBlank(leg)) {
                continue;
            }
            for (String airport : leg.trim().split("-")) {
                if (airports.isEmpty() || !airports.get(airports.size() - 1).equals(airport)) {
                    airports.add(airport);
                }
            }
        }
        return String.join("-", airports);
    }

    /**
     * Ce se intampla dupa generare: READ_PRICES = citeste preturile in Excel (Selenium Edge),
     * OPEN_IN_BROWSER = doar deschide URL-urile in browser, NOTHING = doar fisierul .txt.
     */
    static final AfterGenerate AFTER_GENERATE = AfterGenerate.READ_PRICES;

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
            case READ_PRICES -> FlightPriceScraper.readPrices(FlightPriceScraper.Search.MULTI_CITY, SITE, urls);
            case OPEN_IN_BROWSER -> UrlListOpener.openUrls(urls);
            case NOTHING -> { }
        }
    }

    public static List<String> generateUrls() {

        List<String> urls = new ArrayList<>();

        for (LocalDate leg1 = DEPARTURE_FROM; !leg1.isAfter(DEPARTURE_TO); leg1 = leg1.plusDays(1)) {
            if (!DEPARTURE_DAYS.contains(leg1.getDayOfWeek())) {
                continue;
            }

            for (LocalDate leg4 : returnDates(leg1)) {

                for (int after = LEG2_AFTER_LEG1_MIN_DAYS; after <= LEG2_AFTER_LEG1_MAX_DAYS; after++) {
                    LocalDate leg2 = leg1.plusDays(after);

                    for (int before = LEG3_BEFORE_LEG4_MIN_DAYS; before <= LEG3_BEFORE_LEG4_MAX_DAYS; before++) {
                        if (after + before != BEIJING_TOTAL_NIGHTS) {
                            continue;
                        }

                        LocalDate leg3 = leg4.minusDays(before);

                        if (leg2.plusDays(MIN_DAYS_BETWEEN_LEG2_AND_LEG3).isAfter(leg3)) {
                            continue;
                        }

                        urls.add(buildUrl(leg1, leg2, leg3, leg4));
                    }
                }
            }
        }

        return urls;
    }

    /**
     * Datele posibile pentru zborul 4, in ordine cronologica:
     * - plecare + TRIP_MIN_DAYS..TRIP_MAX_DAYS zile, daca sunt in RETURN_DAYS;
     * - daca ALSO_RETURN_ON_PREVIOUS_FLIGHT_DAY: prima zi din RETURN_DAYS dinainte de plecare + TRIP_MIN_DAYS.
     */
    static SortedSet<LocalDate> returnDates(LocalDate leg1) {

        SortedSet<LocalDate> dates = new TreeSet<>();

        for (int tripDays = TRIP_MIN_DAYS; tripDays <= TRIP_MAX_DAYS; tripDays++) {
            LocalDate leg4 = leg1.plusDays(tripDays);
            if (RETURN_DAYS.contains(leg4.getDayOfWeek())) {
                dates.add(leg4);
            }
        }

        if (ALSO_RETURN_ON_PREVIOUS_FLIGHT_DAY) {
            LocalDate limit = leg1.plusDays(TRIP_MIN_DAYS);
            for (LocalDate day = limit.minusDays(1); day.isAfter(leg1); day = day.minusDays(1)) {
                if (RETURN_DAYS.contains(day.getDayOfWeek())) {
                    dates.add(day);
                    break;
                }
            }
        }

        return dates;
    }

    static String buildUrl(LocalDate leg1, LocalDate leg2, LocalDate leg3, LocalDate leg4) {

        String[] legs = {LEG1, LEG2, LEG3, LEG4};
        LocalDate[] dates = {leg1, leg2, leg3, leg4};
        if (SITE == FlightSite.KIWI) {
            return KiwiUrls.multiCity(legs, dates);
        }
        if (SITE == FlightSite.SKYSCANNER) {
            return SkyscannerUrls.multiCity(legs, dates);
        }
        if (SITE == FlightSite.TRIP) {
            return TripUrls.multiCity(legs, dates, AIRLINES, NONSTOP_ONLY, "USD");
        }
        if (SITE == FlightSite.AIRCHINA) {
            return AirChinaUrls.multiCity(legs, dates, "CA", AIRCHINA_FLIGHT_NUMBERS);
        }

        // LocalDate.toString() produce formatul ISO yyyy-MM-dd, exact ce asteapta Momondo.
        return BASE_URL
                + LEG1 + "/" + leg1 + "/"
                + LEG2 + "/" + leg2 + "/"
                + LEG3 + "/" + leg3 + "/"
                + LEG4 + "/" + leg4 + "/"
                + QUERY;
    }

    private static void writeUrls(List<String> urls) throws IOException {

        Path parent = OUTPUT_FILE.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(OUTPUT_FILE, urls, StandardCharsets.UTF_8);
    }
}
