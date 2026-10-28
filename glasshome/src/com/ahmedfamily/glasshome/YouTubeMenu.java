package com.ahmedfamily.glasshome;

/**
 * Tells, from a screen picture, whether YouTube's side menu is open on "Library" (the step before walking up to
 * Home). Measured on this TV (28 Oct 2026, 15 screenshots after "Library link, then Left twice"):
 *   menu open    : the Library bar (x=300, y=506 on 1920x1080) is white, about 241; the area at x=300, y=160 is dark, 15
 *   stacked page : (after a video YouTube may open Library as a page with a back arrow) the bar spot is 3, the other 98
 * No Android code, so it is tested on the PC.
 */
final class YouTubeMenu {
    static final int BAR_X = 300, BAR_Y = 506, TOP_X = 300, TOP_Y = 160, RADIUS = 14;

    private YouTubeMenu() { }

    /** bar, top: average brightness (0-255) around those two points. */
    static boolean libraryMenuOpen(int bar, int top) {
        return bar > 180 && top < 50;
    }
}
