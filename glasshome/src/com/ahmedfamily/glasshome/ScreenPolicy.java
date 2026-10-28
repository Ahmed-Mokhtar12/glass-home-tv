package com.ahmedfamily.glasshome;

/**
 * Decides when a picture of the TV screen goes to the AI with your words. Pictures are never saved.
 *   never      : no pictures
 *   keywords   : only when the words refer to the screen ("the second one", "this")
 *   media_apps : also for any AI request while YouTube, or one of Lionz TV's known screens, is in front
 * Settings and the Voice control screen are never sent. No Android code, so it is tested on the PC.
 */
final class ScreenPolicy {
    static final String NEVER = "never", KEYWORDS = "keywords", MEDIA_APPS = "media_apps";
    static final String DEFAULT = MEDIA_APPS;

    private static final String YOUTUBE = "com.google.android.youtube.tv", LIONZ = "com.ipltv.ipltvbo19";
    // Lionz TV 4.0 screens measured on this TV; any other Lionz screen (a login, an account page) falls back to keywords
    private static final String[] LIONZ_SCREENS = { "NewDashboardActivity", "NSTIJKPlayerSkyTvActivity",
        "SeriesAllDataSingleActivity", "VodAllDataSingleActivity", "SearchActivity", "Player", "Exo" };

    private ScreenPolicy() { }

    static boolean wantsPicture(String mode, String frontPackage, String frontActivity, String words) {
        String pkg = frontPackage == null ? "" : frontPackage, cls = frontActivity == null ? "" : frontActivity;
        if (NEVER.equals(mode) || isPrivate(pkg, cls)) return false;
        if (FastPath.needsScreen(words == null ? "" : words)) return true;
        if (!MEDIA_APPS.equals(mode)) return false;
        if (YOUTUBE.equals(pkg)) return true;
        if (LIONZ.equals(pkg)) for (String s : LIONZ_SCREENS) if (cls.contains(s)) return true;
        return false;
    }

    /** Screens that must never be sent: settings apps and Glass Home's own Voice control screen. */
    static boolean isPrivate(String pkg, String cls) {
        return pkg.toLowerCase(java.util.Locale.ROOT).contains("settings") || cls.contains("VoiceActivity");
    }

    static String normalise(String mode) {
        return NEVER.equals(mode) || KEYWORDS.equals(mode) || MEDIA_APPS.equals(mode) ? mode : DEFAULT;
    }
}
