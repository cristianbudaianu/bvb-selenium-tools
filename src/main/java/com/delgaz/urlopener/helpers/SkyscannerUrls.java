package com.delgaz.urlopener.helpers;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Construieste URL-uri Skyscanner multi-city, de forma (fiecare zbor = plecare/data/destinatie):
 *
 *   https://www.skyscanner.ro/transport/d/buch/2027-02-01/bjsa/bjsa/2027-02-09/bkkt/bkkt/2027-02-14/bjsa/bjsa/2027-02-24/buch/?adultsv2=1&cabinclass=economy
 *
 * Skyscanner foloseste in URL coduri proprii pentru orase ("buch", "bjsa"), nu codul IATA ("BUH", "BJS"):
 * traducerea e in CITY_CODES.
 */
public final class SkyscannerUrls {

    static final String BASE_URL = "https://www.skyscanner.ro/transport/d/";
    static final String QUERY = "?adultsv2=1&cabinclass=economy&childrenv2=";

    /**
     * Cod IATA oras -> codul folosit de Skyscanner in URL. Adauga aici orasele noi; codul corect il vezi in
     * URL-ul Skyscanner dupa o cautare manuala.
     */
    static final Map<String, String> CITY_CODES = Map.of(
            "BUH", "buch",
            "BJS", "bjsa",
            "BKK", "bkkt"
    );

    private SkyscannerUrls() {
    }

    /**
     * Dus-intors: https://www.skyscanner.ro/transport/flights/bjsa/buch/270301/270305/?adultsv2=1&cabinclass=economy&childrenv2=&rtn=1
     * (datele in formatul yyMMdd).
     *
     * @param route ex. "BJS-BUH" (dus); intoarcerea e pe ruta inversa
     */
    public static String roundTrip(String route, LocalDate departure, LocalDate ret) {

        String[] airports = route.trim().split("-");
        if (airports.length != 2) {
            throw new IllegalArgumentException("Ruta invalida: " + route + " (astept forma BUH-BJS)");
        }
        java.time.format.DateTimeFormatter yyMMdd = java.time.format.DateTimeFormatter.ofPattern("yyMMdd");
        return "https://www.skyscanner.ro/transport/flights/"
                + code(airports[0]) + "/" + code(airports[1]) + "/"
                + departure.format(yyMMdd) + "/" + ret.format(yyMMdd) + "/"
                + QUERY + "&rtn=1";
    }

    /**
     * @param legs  rutele, ex. "BUH-BJS", "BJS-BKK", ...
     * @param dates data fiecarui zbor, in aceeasi ordine
     */
    public static String multiCity(String[] legs, LocalDate[] dates) {

        if (legs.length != dates.length) {
            throw new IllegalArgumentException("Numar diferit de rute si date: " + legs.length + " / " + dates.length);
        }

        StringBuilder url = new StringBuilder(BASE_URL);
        for (int i = 0; i < legs.length; i++) {
            String[] airports = legs[i].trim().split("-");
            if (airports.length != 2) {
                throw new IllegalArgumentException("Ruta invalida: " + legs[i] + " (astept forma BUH-BJS)");
            }
            url.append(code(airports[0])).append('/').append(dates[i]).append('/').append(code(airports[1])).append('/');
        }
        return url.append(QUERY).toString();
    }

    private static String code(String iata) {

        String code = CITY_CODES.get(iata.trim().toUpperCase(Locale.ROOT));
        if (code == null) {
            throw new IllegalArgumentException("Nu stiu codul Skyscanner pentru " + iata
                    + " - adauga-l in SkyscannerUrls.CITY_CODES (ex. \"BUH\", \"buch\").");
        }
        return code;
    }
}
