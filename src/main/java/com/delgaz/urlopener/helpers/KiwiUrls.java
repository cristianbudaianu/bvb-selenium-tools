package com.delgaz.urlopener.helpers;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Construieste URL-uri Kiwi.com multi-city, de forma:
 *
 *   https://www.kiwi.com/en/multicity/results/bucharest-romania~beijing-china~2027-02-03/beijing-china~bangkok-thailand~2027-02-06/.../
 *
 * Kiwi foloseste in URL numele orasului ("bucharest-romania"), nu codul IATA ("BUH"): traducerea e in CITY_SLUGS.
 */
public final class KiwiUrls {

    static final String BASE_URL = "https://www.kiwi.com/en/multicity/results/";

    /**
     * Cod IATA oras -> numele folosit de Kiwi in URL. Adauga aici orasele noi; numele corect il vezi in URL-ul
     * Kiwi dupa o cautare manuala.
     */
    static final Map<String, String> CITY_SLUGS = Map.of(
            "BUH", "bucharest-romania",
            "BJS", "beijing-china",
            "BKK", "bangkok-thailand"
    );

    private KiwiUrls() {
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
            url.append(slug(airports[0])).append('~').append(slug(airports[1])).append('~').append(dates[i]).append('/');
        }
        return url.toString();
    }

    private static String slug(String code) {

        String slug = CITY_SLUGS.get(code.trim().toUpperCase(Locale.ROOT));
        if (slug == null) {
            throw new IllegalArgumentException("Nu stiu numele Kiwi pentru " + code
                    + " - adauga-l in KiwiUrls.CITY_SLUGS (ex. \"BUH\", \"bucharest-romania\").");
        }
        return slug;
    }
}
