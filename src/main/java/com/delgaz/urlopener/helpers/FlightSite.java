package com.delgaz.urlopener.helpers;

/**
 * Site-ul pe care se cauta zborurile.
 */
public enum  FlightSite {
    /** https://www.momondo.ro/flight-search/BUH-BJS/2027-02-03/... ; preturile se pot citi cu {@link FlightPriceScraper}. */
    MOMONDO,
    /** https://www.kiwi.com/en/multicity/results/bucharest-romania~beijing-china~2027-02-03/... ; doar deschidere in browser. */
    KIWI,
    /** https://www.skyscanner.ro/transport/d/buch/2027-02-01/bjsa/... ; preturile se pot citi cu {@link FlightPriceScraper}. */
    SKYSCANNER,
    /** https://www.trip.com/flights/showfarefirst?... ; preturile se pot citi cu {@link FlightPriceScraper}. */
    TRIP,
    /**
     * https://www.airchina.us/CAOnline/dyn/air/booking/deepLinkToFare?... ; doar multi-city, cu numere de zbor fixe;
     * preturile (totalul "Flight Total") se pot citi cu {@link FlightPriceScraper}.
     */
    AIRCHINA;

    /** true daca {@link FlightPriceScraper} stie sa citeasca preturile de pe acest site. */
    public boolean canReadPrices() {

        return this != KIWI;
    }
}
