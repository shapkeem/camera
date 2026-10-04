package com.shapkeem.camera.tune;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.preference.PreferenceManager;
import android.view.Display;

/** Galaxy Z Flip cover screen (FlexWindow) support: when the app runs on the small,
 *  near-square cover screen, the rear cameras face the user, so we switch to them for
 *  rear-camera selfies with a live preview.
 */
public class CoverScreen {
    public static final String PREF_REAR_SELFIE = "preference_tune_cover_rear_selfie";

    public static boolean isRearSelfieEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_REAR_SELFIE, true);
    }

    /** True when the activity is shown on a secondary display, or in a near-square small
     *  window (the Flip7 cover screen is about 948x1048; the main screen is 21:9).
     */
    public static boolean isCoverScreen(Activity activity) {
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.R )
            return false;
        Display display = activity.getDisplay();
        if( display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY )
            return true;
        Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
        int w = bounds.width(), h = bounds.height();
        if( w <= 0 || h <= 0 )
            return false;
        float ratio = Math.max(w, h) / (float)Math.min(w, h);
        int smallestDp = activity.getResources().getConfiguration().smallestScreenWidthDp;
        return ratio < 1.35f && smallestDp < 480;
    }
}
