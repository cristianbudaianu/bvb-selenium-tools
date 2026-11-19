package com.delgaz.urlopener.helpers;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Construieste link-uri Air China direct la tarif ("deepLinkToFare"), pentru zboruri exacte (numar de zbor fix):
 *
 *   https://www.airchina.us/CAOnline/dyn/air/booking/deepLinkToFare?SITE=B000CA00&PAGE=FARE&...&TRIP_TYPE=M
 *       &B_LOCATION_1=OTP&E_LOCATION_1=PEK&B_DATE_1=202703150000&FLIGHT_NUMBER_1_1=914&AIRLINE_1_1=CA&...
 *
 * Fara parametrii de urmarire Kayak (cid, kayakclickid). Zborul trebuie sa opereze in ziua respectiva,
 * altfel pagina nu gaseste tariful.
 */
public final class AirChinaUrls {

    static final String BASE_URL = "https://www.airchina.us/CAOnline/dyn/air/booking/deepLinkToFare"
            + "?SITE=B000CA00&PAGE=FARE&LANGUAGE=GB&COUNTRY_SITE=US&TRIP_TYPE=M&FARE_COMMAND_ID=1";
    static final String END_QUERY = "&CABIN=E&TRAVELLER_TYPE_1=ADT&IS_PRIMARY_TRAVELLER_1=Y#!";

    /** 2027-03-15 -> 202703150000 (data + ora 00:00). */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd'0000'");

    /**
     * Cod oras (folosit in LEG1..LEG4) -> aeroportul Air China. Adauga aici orasele noi.
     * Codurile de aeroport (OTP, PEK ...) trec neschimbate.
     */
    static final Map<String, String> AIRPORTS = Map.of(
            "BUH", "OTP",
            "BJS", "PEK",
            "TYO", "HND"
    );

    private AirChinaUrls() {
    }

    /**
     * @param legs          rutele, ex. "BUH-BJS", "BJS-BKK", ...
     * @param dates         data fiecarui zbor, in aceeasi ordine
     * @param airline       compania, ex. "CA"
     * @param flightNumbers numarul de zbor pentru fiecare ruta, in aceeasi ordine, ex. "914", "979", "960", "913"
     */
    public static String multiCity(String[] legs, LocalDate[] dates, String airline, String[] flightNumbers) {

        if (legs.length != dates.length || legs.length != flightNumbers.length) {
            throw new IllegalArgumentException("Numar diferit de rute / date / numere de zbor: "
                    + legs.length + " / " + dates.length + " / " + flightNumbers.length);
        }

        StringBuilder url = new StringBuilder(BASE_URL);
        for (int i = 0; i < legs.length; i++) {
            String[] airports = legs[i].trim().split("-");
            if (airports.length != 2) {
                throw new IllegalArgumentException("Ruta invalida: " + legs[i] + " (astept forma BUH-BJS)");
            }
            int n = i + 1;
            url.append("&B_LOCATION_").append(n).append('=').append(airport(airports[0]))
                    .append("&E_LOCATION_").append(n).append('=').append(airport(airports[1]))
                    .append("&B_DATE_").append(n).append('=').append(dates[i].format(DATE));
        }
        for (int i = 0; i < legs.length; i++) {
            int n = i + 1;
            url.append("&FLIGHT_NUMBER_").append(n).append("_1=").append(flightNumbers[i].trim())
                    .append("&AIRLINE_").append(n).append("_1=").append(airline.trim().toUpperCase(Locale.ROOT));
        }
        return url.append(END_QUERY).toString();
    }

    private static String airport(String code) {

        String upper = code.trim().toUpperCase(Locale.ROOT);
        return AIRPORTS.getOrDefault(upper, upper);
    }
}
