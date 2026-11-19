package com.delgaz.urlopener.helpers;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Construieste URL-uri Trip.com (pagina de rezultate "showfarefirst"):
 *
 * dus-intors:
 *   https://www.trip.com/flights/showfarefirst?triptype=rt&dcity=bjs&acity=tyo&dcityid=1&acityid=228&ddate=2027-02-23&rdate=2027-02-28&...
 * multi-city:
 *   https://www.trip.com/flights/showfarefirst?triptype=mt&multdcity0=buh&multacity0=bjs&multdcityid0=674&multacityid0=1&multddate0=2027-02-10&...
 *
 * Nu folosim pagina "ShowFareNext" (alegerea zborului de intoarcere): are criteriaToken / shoppingid legate de
 * sesiunea in care a fost facuta cautarea.
 */
public final class TripUrls {

    static final String BASE_URL = "https://www.trip.com/flights/showfarefirst";
    static final String COMMON_QUERY = "&class=y&quantity=1&childqty=0&babyqty=0&searchboxarg=t&locale=en-XX";

    /**
     * Cod IATA oras -> id-ul orasului la Trip.com. Adauga aici orasele noi; id-ul il vezi in URL-ul Trip.com
     * (dcityid / acityid) dupa o cautare manuala.
     */
    static final Map<String, Integer> CITY_IDS = Map.of(
            "BUH", 674,
            "BJS", 1,
            "BKK", 359,
            "TYO", 228,
            "PEK", 1,
            "HND", 228
    );

    private TripUrls() {
    }

    /**
     * @param route       ex. "BJS-TYO" (dus); intoarcerea e pe ruta inversa
     * @param airlines    ex. "CA"; gol/null = toate companiile
     * @param nonstopOnly true = doar zboruri directe
     * @param currency    moneda preturilor, ex. "EUR", "USD"
     */
    public static String roundTrip(String route, LocalDate departure, LocalDate ret, String airlines, boolean nonstopOnly,
                                   String currency) {

        String[] airports = split(route);
        return BASE_URL + "?triptype=rt"
                + "&dcity=" + lower(airports[0]) + "&acity=" + lower(airports[1])
                + "&dcityid=" + cityId(airports[0]) + "&acityid=" + cityId(airports[1])
                + "&ddate=" + departure + "&rdate=" + ret
                + filters(airlines, nonstopOnly)
                + COMMON_QUERY + "&curr=" + currency.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * @param legs  rutele, ex. "BUH-BJS", "BJS-BKK", ...
     * @param dates    data fiecarui zbor, in aceeasi ordine
     * @param currency moneda preturilor, ex. "EUR", "USD"
     */
    public static String multiCity(String[] legs, LocalDate[] dates, String airlines, boolean nonstopOnly, String currency) {

        if (legs.length != dates.length) {
            throw new IllegalArgumentException("Numar diferit de rute si date: " + legs.length + " / " + dates.length);
        }

        StringBuilder url = new StringBuilder(BASE_URL).append("?triptype=mt");
        for (int i = 0; i < legs.length; i++) {
            String[] airports = split(legs[i]);
            url.append("&multdcity").append(i).append('=').append(lower(airports[0]))
                    .append("&multacity").append(i).append('=').append(lower(airports[1]))
                    .append("&multdcityid").append(i).append('=').append(cityId(airports[0]))
                    .append("&multacityid").append(i).append('=').append(cityId(airports[1]))
                    .append("&multddate").append(i).append('=').append(dates[i]);
        }
        return url.append(filters(airlines, nonstopOnly)).append(COMMON_QUERY)
                .append("&curr=").append(currency.trim().toUpperCase(Locale.ROOT)).toString();
    }

    private static String filters(String airlines, boolean nonstopOnly) {

        String query = "";
        if (airlines != null && !airlines.isBlank()) {
            query += "&airline=" + airlines.trim().toUpperCase(Locale.ROOT);
        }
        if (nonstopOnly) {
            query += "&nonstoponly=on";
        }
        return query;
    }

    private static String[] split(String route) {

        String[] airports = route.trim().split("-");
        if (airports.length != 2) {
            throw new IllegalArgumentException("Ruta invalida: " + route + " (astept forma BUH-BJS)");
        }
        return airports;
    }

    private static String lower(String code) {

        return code.trim().toLowerCase(Locale.ROOT);
    }

    private static int cityId(String code) {

        Integer id = CITY_IDS.get(code.trim().toUpperCase(Locale.ROOT));
        if (id == null) {
            throw new IllegalArgumentException("Nu stiu id-ul Trip.com pentru " + code
                    + " - adauga-l in TripUrls.CITY_IDS (ex. \"BJS\", 1).");
        }
        return id;
    }
}
