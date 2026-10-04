package com.shapkeem.camera.tune;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tune Camera look processing (stage 2 in analysis/app-plan.md).
 *
 *  Works on a bitmap decoded from a full-range (JFIF, BT.601) JPEG, in YCbCr:
 *  - luma: highlight roll-off brightness curve, then an S-shaped contrast curve
 *  - chroma: saturation scale with skin tones protected, and a warmth shift that
 *    grows with luma so blacks stay neutral
 *
 *  Off by default. Any failure leaves the image untouched.
 */
public class TuneProcessor {
    private static final String TAG = "TuneProcessor";

    public static final String PREF_ENABLED = "preference_tune_enabled";
    public static final String PREF_AB_DUMP = "preference_tune_ab_dump";
    public static final String PREF_PRESET = "preference_tune_preset";
    public static final String PREF_BRIGHTNESS = "preference_tune_brightness";
    public static final String PREF_CONTRAST = "preference_tune_contrast";
    public static final String PREF_WARMTH = "preference_tune_warmth";
    public static final String PREF_SATURATION = "preference_tune_saturation";

    private static final float SHOULDER = 4.0f;

    /** Look parameters. */
    public static class Look {
        public final String name;
        public final int brightness;
        public final int contrast;
        public final int warmth;
        public final int saturation;

        // brightness/contrast/saturation are percentages, warmth is -10..15
        public Look(String name, int brightness, int contrast, int warmth, int saturation) {
            this.name = name;
            this.brightness = brightness;
            this.contrast = contrast;
            this.warmth = warmth;
            this.saturation = saturation;
        }

        boolean changesChroma() {
            return warmth != 0 || saturation != 0;
        }

        @Override
        public String toString() {
            return name + " (밝기 " + brightness + "%, 대비 " + contrast + "%, 따뜻함 " + warmth + ", 채도 " + saturation + "%)";
        }
    }

    /** Brightness only, the original stage 2 prototype. */
    public static final Look LOOK_BRIGHTNESS = new Look("밝기만", 10, 0, 0, 0);
    /** iPhone-like rendering, fitted on a Flip7 vs iPhone 12 Pro selfie pair of the same
     *  person in the same room (analysis/look-iphone-fit.md): iPhone was brighter on
     *  faces, more contrasty, warmer (ivory walls) and more saturated than Samsung.
     */
    public static final Look LOOK_IPHONE = new Look("아이폰 느낌", 6, 20, 12, 18);

    public static class Result {
        public final Bitmap bitmap;
        public final long elapsedMs;
        public final float meanYBefore;
        public final float meanYAfter;

        Result(Bitmap bitmap, long elapsedMs, float meanYBefore, float meanYAfter) {
            this.bitmap = bitmap;
            this.elapsedMs = elapsedMs;
            this.meanYBefore = meanYBefore;
            this.meanYAfter = meanYAfter;
        }
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, false);
    }

    public static boolean isAbDumpEnabled(Context context) {
        return prefs(context).getBoolean(PREF_AB_DUMP, false);
    }

    public static Look lookFromPrefs(Context context) {
        SharedPreferences prefs = prefs(context);
        String preset = prefs.getString(PREF_PRESET, "iphone");
        switch( preset ) {
            case "brightness":
                return LOOK_BRIGHTNESS;
            case "custom":
                return new Look("사용자 설정",
                        intPref(prefs, PREF_BRIGHTNESS, 0),
                        intPref(prefs, PREF_CONTRAST, 0),
                        intPref(prefs, PREF_WARMTH, 0),
                        intPref(prefs, PREF_SATURATION, 0));
            default:
                return LOOK_IPHONE;
        }
    }

    private static int intPref(SharedPreferences prefs, String key, int def) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(def)));
        }
        catch(NumberFormatException e) {
            return def;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** Tone curve LUT: y = g*x / (1 + (g-1)*x^p) on x in [0,1].
     *  Behaves like a plain gain g in shadows and mid-tones, then rolls off so that
     *  1 maps to 1: highlights are compressed instead of clipping to white.
     *  Monotonic as long as p < g/(g-1). For g < 1 it darkens with the same shape.
     */
    static int [] buildCurveLut(float gain, float shoulder) {
        int [] lut = new int[256];
        for(int i=0;i<256;i++) {
            double x = i / 255.0;
            double y = gain * x / (1.0 + (gain - 1.0) * Math.pow(x, shoulder));
            lut[i] = Math.min(255, Math.max(0, (int)Math.round(y * 255.0)));
        }
        return lut;
    }

    /** Luma LUT for a look: brightness roll-off curve, then contrast
     *  y = x - c*sin(2*pi*x)/(2*pi), which darkens below mid-grey and brightens above
     *  while keeping 0 and 1 fixed (monotonic for |c| < 1).
     */
    static int [] buildLumaLut(Look look) {
        int [] bright = buildCurveLut(1.0f + look.brightness / 100.0f, SHOULDER);
        double c = Math.max(-0.9, Math.min(0.9, look.contrast / 100.0));
        int [] lut = new int[256];
        for(int i=0;i<256;i++) {
            double x = bright[i] / 255.0;
            double y = x - c * Math.sin(2.0 * Math.PI * x) / (2.0 * Math.PI);
            lut[i] = Math.min(255, Math.max(0, (int)Math.round(y * 255.0)));
        }
        return lut;
    }

    /** How much a chroma value looks like skin, 0..1. Skin sits in a compact cluster
     *  around Cb -20, Cr +22 (full-range, centred on 0) across skin tones.
     */
    static float skinWeight(float cb, float cr) {
        float dcb = (cb + 20.0f) / 18.0f;
        float dcr = (cr - 22.0f) / 16.0f;
        float d = (float)Math.sqrt(dcb * dcb + dcr * dcr);
        return d >= 1.0f ? 0.0f : 1.0f - d;
    }

    /** Applies the look to one ARGB pixel. */
    static int applyPixel(int c, Look look, int [] lumaLut) {
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;
        // BT.601 full-range luma, fixed point (coefficients sum to 65536)
        int luma = (19595 * r + 38470 * g + 7471 * b + 32768) >> 16;
        int newLuma = lumaLut[luma];
        if( !look.changesChroma() ) {
            // chroma unchanged: same as adding the luma delta to R, G and B
            int delta = newLuma - luma;
            return (c & 0xff000000) | (clamp(r + delta) << 16) | (clamp(g + delta) << 8) | clamp(b + delta);
        }
        float cb = -0.168736f * r - 0.331264f * g + 0.5f * b;
        float cr = 0.5f * r - 0.418688f * g - 0.081312f * b;
        float sat = 1.0f + look.saturation / 100.0f;
        // keep skin closer to the original: strongly when desaturating (skin going
        // grey looks ill), lightly when saturating
        float w = skinWeight(cb, cr);
        sat = sat + (1.0f - sat) * (sat < 1.0f ? 0.7f : 0.2f) * w;
        cb *= sat;
        cr *= sat;
        // warmth: mostly along blue -> yellow (like colour temperature) with a little
        // red, scaled by brightness so shadows stay neutral
        float shift = look.warmth * 0.8f * (newLuma / 255.0f);
        cb -= shift;
        cr += 0.4f * shift;
        float yy = newLuma;
        int nr = Math.round(yy + 1.402f * cr);
        int ng = Math.round(yy - 0.344136f * cb - 0.714136f * cr);
        int nb = Math.round(yy + 1.772f * cb);
        return (c & 0xff000000) | (clamp(nr) << 16) | (clamp(ng) << 8) | clamp(nb);
    }

    /** Applies the look in place. Returns null if the bitmap can't be processed,
     *  in which case the caller keeps the original.
     */
    public static Result process(Bitmap bitmap, Look look) {
        if( bitmap == null || !bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.ARGB_8888 ) {
            Log.w(TAG, "skip: bitmap " + (bitmap == null ? "null" : bitmap.getConfig() + " mutable=" + bitmap.isMutable()));
            return null;
        }
        long start = System.currentTimeMillis();
        int [] lumaLut = buildLumaLut(look);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int [] row = new int[width];
        long sumBefore = 0, sumAfter = 0;
        for(int y=0;y<height;y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for(int x=0;x<width;x++) {
                int c = row[x];
                sumBefore += luma(c);
                int out = applyPixel(c, look, lumaLut);
                sumAfter += luma(out);
                row[x] = out;
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
        long elapsed = System.currentTimeMillis() - start;
        double n = (double)width * height;
        Result result = new Result(bitmap, elapsed, (float)(sumBefore / n), (float)(sumAfter / n));
        Log.d(TAG, "processed " + width + "x" + height + " look " + look + " in " + elapsed + "ms, mean Y " + result.meanYBefore + " -> " + result.meanYAfter);
        return result;
    }

    private static int luma(int c) {
        return (19595 * ((c >> 16) & 0xff) + 38470 * ((c >> 8) & 0xff) + 7471 * (c & 0xff) + 32768) >> 16;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** Saves the untouched camera JPEG to Pictures/TuneCamera/AB so it can be compared
     *  with the processed file saved by the normal path. Android 10+ only.
     */
    public static void dumpOriginal(Context context, byte [] jpeg) {
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || jpeg == null )
            return;
        String name = "TUNE_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + "_A_original.jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TuneCamera/AB");
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if( uri == null )
                return;
            try( OutputStream out = resolver.openOutputStream(uri) ) {
                if( out != null )
                    out.write(jpeg);
            }
            Log.d(TAG, "A/B original saved: " + name);
        }
        catch(Exception e) {
            Log.e(TAG, "A/B dump failed", e);
            if( uri != null ) {
                try {
                    resolver.delete(uri, null, null);
                }
                catch(Exception ignored) {
                }
            }
        }
    }
}
