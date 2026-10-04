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

/** First tuning prototype (stage 2 in analysis/app-plan.md).
 *
 *  Scales luma by GAIN with chroma unchanged. The bitmap is decoded from a full-range
 *  (JFIF, BT.601) JPEG, so changing Y while keeping Cb/Cr fixed is the same as adding
 *  the same delta to R, G and B.
 *
 *  Off by default. Any failure leaves the image untouched.
 */
public class TuneProcessor {
    private static final String TAG = "TuneProcessor";

    public static final String PREF_ENABLED = "preference_tune_enabled";
    public static final String PREF_AB_DUMP = "preference_tune_ab_dump";

    private static final float GAIN = 1.10f;
    private static final int [] LUT = buildLut(GAIN);

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

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** Full-range luma LUT: out = round(in * gain), clamped to [0, 255]. */
    static int [] buildLut(float gain) {
        int [] lut = new int[256];
        for(int i=0;i<256;i++) {
            lut[i] = Math.min(255, Math.max(0, Math.round(i * gain)));
        }
        return lut;
    }

    /** Applies the luma LUT in place. Returns null if the bitmap can't be processed,
     *  in which case the caller keeps the original.
     */
    public static Result process(Bitmap bitmap) {
        if( bitmap == null || !bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.ARGB_8888 ) {
            Log.w(TAG, "skip: bitmap " + (bitmap == null ? "null" : bitmap.getConfig() + " mutable=" + bitmap.isMutable()));
            return null;
        }
        long start = System.currentTimeMillis();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int [] row = new int[width];
        long sumBefore = 0, sumAfter = 0;
        for(int y=0;y<height;y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for(int x=0;x<width;x++) {
                int c = row[x];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                // BT.601 full-range luma, fixed point (coefficients sum to 65536)
                int luma = (19595 * r + 38470 * g + 7471 * b + 32768) >> 16;
                int delta = LUT[luma] - luma;
                r = clamp(r + delta);
                g = clamp(g + delta);
                b = clamp(b + delta);
                sumBefore += luma;
                sumAfter += (19595 * r + 38470 * g + 7471 * b + 32768) >> 16;
                row[x] = (c & 0xff000000) | (r << 16) | (g << 8) | b;
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
        long elapsed = System.currentTimeMillis() - start;
        double n = (double)width * height;
        Result result = new Result(bitmap, elapsed, (float)(sumBefore / n), (float)(sumAfter / n));
        Log.d(TAG, "processed " + width + "x" + height + " in " + elapsed + "ms, mean Y " + result.meanYBefore + " -> " + result.meanYAfter);
        return result;
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
