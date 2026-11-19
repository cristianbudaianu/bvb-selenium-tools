package com.delgaz.urlopener.helpers;

/**
 * Ce face un generator Momondo dupa ce a generat si a salvat URL-urile in fisierul .txt.
 */
public enum AfterGenerate {
    /** Doar scrie fisierul .txt. */
    NOTHING,
    /** Deschide URL-urile in browser, prin {@link UrlListOpener}. */
    OPEN_IN_BROWSER,
    /** Citeste preturile cu {@link FlightPriceScraper} (Selenium Edge) si le scrie in Excel. */
    READ_PRICES
}
