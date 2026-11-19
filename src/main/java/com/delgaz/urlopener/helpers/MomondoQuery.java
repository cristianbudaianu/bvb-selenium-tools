package com.delgaz.urlopener.helpers;

import java.util.ArrayList;
import java.util.List;

/**
 * Construieste partea de dupa "?" din URL-urile Momondo, de forma:
 *
 *   ?fs=airlines%3DCA%2CMULT%3Bstops%3D0&sort=price_a
 *
 * (decodat: fs=airlines=CA,MULT;stops=0 &amp; sort=price_a).
 */
public final class MomondoQuery {

    private MomondoQuery() {
    }

    /**
     * @param sort        ex. "price_a" (cel mai ieftin) sau "bestflight_a" (cel mai bun)
     * @param airlines    codurile companiilor separate prin virgula, ex. "CA" sau "CA,MU"; gol/null = toate companiile
     * @param nonstopOnly true = doar zboruri directe
     */
    public static String build(String sort, String airlines, boolean nonstopOnly) {

        List<String> filters = new ArrayList<>();

        if (airlines != null && !airlines.isBlank()) {
            List<String> codes = new ArrayList<>();
            for (String code : airlines.split(",")) {
                if (!code.isBlank()) {
                    codes.add(code.trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
            // MULT = permite si itinerariile combinate cu mai multe companii (cum pune si Momondo in filtru).
            codes.add("MULT");
            filters.add("airlines%3D" + String.join("%2C", codes));
        }

        if (nonstopOnly) {
            filters.add("stops%3D0");
        }

        String query = filters.isEmpty() ? "" : "fs=" + String.join("%3B", filters) + "&";
        return "?" + query + "sort=" + sort;
    }
}
