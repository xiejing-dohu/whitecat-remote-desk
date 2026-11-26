package dev.whitecat.desk;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

final class Appearance {
    static final int[] FONT_PERCENTAGES = {90, 100, 115, 130};

    enum Background {
        OBSIDIAN("obsidian", "曜石", R.style.ObsidianTheme, R.color.orca_panel, R.color.orca_ink),
        OCEAN("ocean", "深海", R.style.OceanTheme, R.color.ocean_canvas, R.color.orca_ink),
        PAPER("paper", "暖纸", R.style.PaperTheme, R.color.paper_canvas, R.color.paper_ink);

        final String key, label;
        final int style, swatch, swatchText;
        Background(String key, String label, int style, int swatch, int swatchText) {
            this.key = key;
            this.label = label;
            this.style = style;
            this.swatch = swatch;
            this.swatchText = swatchText;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }

    static int fontPercent(Context context) {
        int stored = preferences(context).getInt("font_percent", 100);
        for (int value : FONT_PERCENTAGES) {
            if (stored == value) return value;
        }
        return 100;
    }

    static Background background(Context context) {
        String stored = preferences(context).getString("background", "");
        for (Background choice : Background.values()) {
            if (choice.key.equals(stored)) return choice;
        }
        return Background.OBSIDIAN;
    }

    static Context context(Context base) {
        int percent = fontPercent(base);
        if (percent == 100) return base;
        Configuration override = new Configuration();
        override.fontScale = base.getResources().getConfiguration().fontScale * percent / 100f;
        ContextThemeWrapper wrapper = new ContextThemeWrapper(base, 0);
        wrapper.applyOverrideConfiguration(override);
        return wrapper;
    }

    static int theme(Context context) {
        return background(context).style;
    }

    static int color(Context context, int attribute) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)
                || value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) {
            throw new IllegalStateException("Missing theme color " + context.getResources().getResourceName(attribute));
        }
        return value.data;
    }

    static void apply(ChatActivity a) {
        a.paper = color(a, android.R.attr.windowBackground);
        a.ink = color(a, R.attr.deskInk);
        a.muted = color(a, R.attr.deskMuted);
        a.tile = color(a, R.attr.deskTile);
        a.green = color(a, R.attr.deskSuccess);
        a.surface = color(a, R.attr.deskSurface);
        a.rail = color(a, R.attr.deskRail);
        a.edge = color(a, R.attr.deskEdge);
        a.secondary = color(a, R.attr.deskSecondary);
        a.blue = color(a, R.attr.deskAction);
        a.amber = color(a, R.attr.deskWarning);
        a.failure = color(a, R.attr.deskFailure);
        applySystemBars(a);
    }

    @SuppressWarnings("deprecation")
    static void applySystemBars(Activity activity) {
        Window window = activity.getWindow();
        int canvas = color(activity, android.R.attr.windowBackground);
        window.setStatusBarColor(canvas);
        window.setNavigationBarColor(canvas);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        window.getDecorView(); // API 30 PhoneWindow creates its insets controller with the decor.
        boolean light = background(activity) == Background.PAPER;
        if (Build.VERSION.SDK_INT >= 30) {
            int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            window.getInsetsController().setSystemBarsAppearance(light ? flags : 0, flags);
        } else {
            View decor = window.getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int current = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(light ? current | flags : current & ~flags);
        }
    }

    static void select(ChatActivity activity, int percent, Background background) {
        if (fontPercent(activity) == percent && background(activity) == background) return;
        preferences(activity).edit().putInt("font_percent", percent).putString("background", background.key).apply();
        activity.recreate();
    }
}
