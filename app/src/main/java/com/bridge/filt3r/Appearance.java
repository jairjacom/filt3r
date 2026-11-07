package com.bridge.filt3r;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.res.Configuration;

import com.google.android.material.color.ColorResourcesOverride;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColorUtilitiesHelper;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.SchemeExpressive;
import com.google.android.material.color.utilities.SchemeTonalSpot;
import com.google.android.material.color.utilities.SchemeVibrant;

import java.util.Map;

/**
 * Applies the look chosen in Settings to an activity: light/dark mode, a generated colour palette
 * (or the wallpaper's, for Material You) and the pure black canvas. Call {@link #apply} in
 * onCreate, before the views are built. The default scheme leaves the fixed filt3r palette alone.
 */
final class Appearance {

    private Appearance() {
    }

    // The colour utilities are marked as library-internal by Material; they are stable code, the
    // same that DynamicColors uses for the wallpaper palette.
    @SuppressLint("RestrictedApi")
    static void apply(Activity activity, Settings settings) {
        // Same value as the running one: nothing happens. A new value makes the system restart the activity.
        activity.getSystemService(UiModeManager.class).setApplicationNightMode(settings.themeMode());
        boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        String scheme = settings.colorScheme();
        if (Settings.SCHEME_DYNAMIC.equals(scheme) && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivityIfAvailable(activity);
            activity.getTheme().applyStyle(R.style.ThemeOverlay_Filt3r_Generated, true);
        } else if (!Settings.SCHEME_DEFAULT.equals(scheme) && !Settings.SCHEME_DYNAMIC.equals(scheme)) {
            Hct seed = Hct.fromInt(settings.seedColor());
            DynamicScheme generated;
            if (Settings.SCHEME_VIBRANT.equals(scheme)) {
                generated = new SchemeVibrant(seed, dark, 0.0);
            } else if (Settings.SCHEME_EXPRESSIVE.equals(scheme)) {
                generated = new SchemeExpressive(seed, dark, 0.0);
            } else {
                generated = new SchemeTonalSpot(seed, dark, 0.0);
            }
            Map<Integer, Integer> colors = MaterialColorUtilitiesHelper.createColorResourcesIdsToColorValues(generated);
            // This also applies ThemeOverlay.Material3.PersonalizedColors, which reads the colours just set.
            ColorResourcesOverride.getInstance().applyIfPossible(activity, colors);
            activity.getTheme().applyStyle(R.style.ThemeOverlay_Filt3r_Generated, true);
        } else if (Settings.SCHEME_DEFAULT.equals(scheme)) {
            // The fixed filt3r palette sets only the red primary; give its containers a red tint too.
            activity.getTheme().applyStyle(R.style.ThemeOverlay_Filt3r_DefaultContainers, true);
        }
        if (dark && settings.pureBlack()) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_Filt3r_PureBlack, true);
        }
        if (!Effects.on(activity, Effects.Effect.EXPRESSIVE_BUTTONS)) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_Filt3r_ClassicButtons, true);
        }
    }
}
