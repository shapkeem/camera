package com.shapkeem.camera.tune;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Tune Camera look processing (stage 2 in analysis/app-plan.md).
 *
 *  Works on a bitmap decoded from a full-range (JFIF, BT.601) JPEG, in YCbCr:
 *  - texture: reduces the finest detail band of luma (Samsung's strong sharpening)
 *  - luma: highlight roll-off brightness curve, then an S-shaped contrast curve
 *  - faces: brightens detected faces towards a target level with a feathered mask
 *  - chroma: saturation scale (separate amount for skin tones), and a warmth shift
 *    that grows with luma so blacks stay neutral
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
    public static final String PREF_SKIN = "preference_tune_skin";
    public static final String PREF_SOFTEN = "preference_tune_soften";
    public static final String PREF_FACE = "preference_tune_face";

    private static final float SHOULDER = 4.0f;
    /** Faces darker than this luma (0..255) are brightened towards it. */
    static final float FACE_TARGET_LUMA = 135.0f;
    static final float FACE_MAX_GAIN = 1.15f;

    /** Look parameters. */
    public static class Look {
        public final String name;
        public final int brightness; // %
        public final int contrast; // %
        public final int warmth; // -10..15
        public final int saturation; // %, non-skin colours
        public final int skinSaturation; // %, skin tones
        public final int soften; // %, how much of the finest luma detail band to remove
        public final boolean faceLight;
        public final int skinHue; // degrees, + turns skin from red towards yellow

        public Look(String name, int brightness, int contrast, int warmth, int saturation) {
            this(name, brightness, contrast, warmth, saturation, saturation, 0, false);
        }

        public Look(String name, int brightness, int contrast, int warmth, int saturation,
                    int skinSaturation, int soften, boolean faceLight) {
            this(name, brightness, contrast, warmth, saturation, skinSaturation, soften, faceLight, 0);
        }

        public Look(String name, int brightness, int contrast, int warmth, int saturation,
                    int skinSaturation, int soften, boolean faceLight, int skinHue) {
            this.name = name;
            this.skinHue = skinHue;
            this.brightness = brightness;
            this.contrast = contrast;
            this.warmth = warmth;
            this.saturation = saturation;
            this.skinSaturation = skinSaturation;
            this.soften = soften;
            this.faceLight = faceLight;
        }

        boolean changesChroma() {
            return warmth != 0 || saturation != 0 || skinSaturation != 0 || skinHue != 0;
        }

        @Override
        public String toString() {
            return name + " (밝기 " + brightness + "%, 대비 " + contrast + "%, 따뜻함 " + warmth +
                    ", 채도 " + saturation + "%, 피부 " + skinSaturation + "% " + skinHue + "°, 질감 -" + soften + "%, 얼굴 " + (faceLight ? "on" : "off") + ")";
        }
    }

    /** Brightness only, the original stage 2 prototype. */
    public static final Look LOOK_BRIGHTNESS = new Look("밝기만", 10, 0, 0, 0);
    /** iPhone 12 Pro-like rendering for the Galaxy Z Flip7 (analysis/look-iphone-fit.md).
     *  Warmth from GSMArena studio shots (iPhone 12 Pro vs Flip5 / S24) and Flip7 walls.
     *  Saturation, skin, face and texture from Flip7 vs iPhone 12 Pro pairs of the same
     *  person and room: unlike Flip5/S24 in the studio, Flip7 renders clothing and skin
     *  less saturated than iPhone, so saturation goes up here.
     */
    public static final Look LOOK_IPHONE = new Look("아이폰 느낌", 0, 0, 10, 20, 13, 45, true, 5);

    /** Food: richer, warmer colour and a little extra contrast, no skin or face handling. */
    public static final Look LOOK_FOOD = new Look("음식", 3, 8, 8, 30, 30, 20, false, 0);
    public static final String PREF_FOOD_MODE = "preference_tune_food_mode";

    public static class Result {
        public final Bitmap bitmap;
        public final long elapsedMs;
        public final float meanYBefore;
        public final float meanYAfter;
        public final int faces;

        Result(Bitmap bitmap, long elapsedMs, float meanYBefore, float meanYAfter, int faces) {
            this.bitmap = bitmap;
            this.elapsedMs = elapsedMs;
            this.meanYBefore = meanYBefore;
            this.meanYAfter = meanYAfter;
            this.faces = faces;
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
        if( prefs.getBoolean(PREF_FOOD_MODE, false) )
            return LOOK_FOOD;
        String preset = prefs.getString(PREF_PRESET, "iphone");
        switch( preset ) {
            case "brightness":
                return LOOK_BRIGHTNESS;
            case "custom":
                int saturation = intPref(prefs, PREF_SATURATION, 0);
                return new Look("사용자 설정",
                        intPref(prefs, PREF_BRIGHTNESS, 0),
                        intPref(prefs, PREF_CONTRAST, 0),
                        intPref(prefs, PREF_WARMTH, 0),
                        saturation,
                        saturation + intPref(prefs, PREF_SKIN, 0),
                        intPref(prefs, PREF_SOFTEN, 0),
                        prefs.getBoolean(PREF_FACE, false));
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

    /** Applies the colour part of the look to one ARGB pixel. srcLuma is the luma the
     *  chroma is measured against (the pixel's own luma), newLuma the processed luma.
     */
    static int applyPixel(int c, Look look, int newLuma) {
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;
        if( !look.changesChroma() ) {
            // chroma unchanged: same as adding the luma delta to R, G and B
            int delta = newLuma - luma(c);
            return (c & 0xff000000) | (clamp(r + delta) << 16) | (clamp(g + delta) << 8) | clamp(b + delta);
        }
        float cb = -0.168736f * r - 0.331264f * g + 0.5f * b;
        float cr = 0.5f * r - 0.418688f * g - 0.081312f * b;
        float w = skinWeight(cb, cr);
        float sat = (1.0f + look.saturation / 100.0f) * (1.0f - w) + (1.0f + look.skinSaturation / 100.0f) * w;
        cb *= sat;
        cr *= sat;
        if( look.skinHue != 0 && w > 0.0f ) {
            // rotate skin chroma: positive angle moves red-orange skin towards yellow
            double a = Math.toRadians(look.skinHue * w);
            float cos = (float)Math.cos(a), sin = (float)Math.sin(a);
            float ncb = cb * cos - cr * sin;
            float ncr = cb * sin + cr * cos;
            cb = ncb;
            cr = ncr;
        }
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

    /** Convenience for tests: full look on a single pixel without texture or faces. */
    static int applyPixel(int c, Look look, int [] lumaLut) {
        return applyPixel(c, look, lumaLut[luma(c)]);
    }

    /** 1D Gaussian kernel for sigma 1.0, radius 2 (sums to 256). */
    private static final int [] BLUR_KERNEL = {14, 61, 106, 61, 14};

    /** Separable 5-tap blur of a width x height luma plane (values 0..255). */
    static byte [] blurLuma(byte [] src, int width, int height) {
        byte [] tmp = new byte[src.length];
        byte [] dst = new byte[src.length];
        for(int y=0;y<height;y++) {
            int row = y * width;
            for(int x=0;x<width;x++) {
                int acc = 0;
                for(int k=-2;k<=2;k++) {
                    int xx = Math.min(width - 1, Math.max(0, x + k));
                    acc += BLUR_KERNEL[k + 2] * (src[row + xx] & 0xff);
                }
                tmp[row + x] = (byte)((acc + 128) >> 8);
            }
        }
        for(int y=0;y<height;y++) {
            for(int x=0;x<width;x++) {
                int acc = 0;
                for(int k=-2;k<=2;k++) {
                    int yy = Math.min(height - 1, Math.max(0, y + k));
                    acc += BLUR_KERNEL[k + 2] * (tmp[yy * width + x] & 0xff);
                }
                dst[y * width + x] = (byte)((acc + 128) >> 8);
            }
        }
        return dst;
    }

    /** Softened luma: keeps (1 - soften) of the detail finer than the blur. */
    static int softenLuma(int luma, int blurred, int soften) {
        return clamp(blurred + Math.round((luma - blurred) * (100 - soften) / 100.0f));
    }

    /** Ellipse around a detected face, in image pixels, with the gain to apply. */
    static class FaceRegion {
        final float cx, cy, rx, ry, gain;

        FaceRegion(float cx, float cy, float rx, float ry, float gain) {
            this.cx = cx;
            this.cy = cy;
            this.rx = rx;
            this.ry = ry;
            this.gain = gain;
        }

        /** 1 inside the face ellipse, fading smoothly to 0 at 1.6x its size. */
        float weight(float x, float y) {
            float dx = (x - cx) / rx;
            float dy = (y - cy) / ry;
            float d = (float)Math.sqrt(dx * dx + dy * dy);
            if( d <= 1.0f )
                return 1.0f;
            if( d >= 1.6f )
                return 0.0f;
            float t = (1.6f - d) / 0.6f;
            return t * t * (3.0f - 2.0f * t);
        }
    }

    /** Gain that brings a face of the given mean luma towards FACE_TARGET_LUMA; never darkens. */
    static float faceGain(float meanLuma) {
        if( meanLuma <= 1.0f )
            return 1.0f;
        return Math.max(1.0f, Math.min(FACE_MAX_GAIN, FACE_TARGET_LUMA / meanLuma));
    }

    interface Band {
        void run(int y0, int y1);
    }

    /** Runs band.run over [0, height) split into one band per core and waits for all. */
    static void parallelRows(int height, Band band) {
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        if( threads == 1 || height < 64 ) {
            band.run(0, height);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            int step = (height + threads - 1) / threads;
            for(int y0=0;y0<height;y0+=step) {
                final int a = y0, b = Math.min(height, y0 + step);
                jobs.add(pool.submit(() -> band.run(a, b)));
            }
            for(Future<?> job : jobs)
                job.get();
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            pool.shutdown();
        }
    }

    /** Same as blurLuma, spread over all cores. */
    static byte [] blurLumaParallel(byte [] src, int width, int height) {
        byte [] tmp = new byte[src.length];
        byte [] dst = new byte[src.length];
        parallelRows(height, (y0, y1) -> {
            for(int y=y0;y<y1;y++) {
                int row = y * width;
                for(int x=0;x<width;x++) {
                    int acc = 0;
                    for(int k=-2;k<=2;k++) {
                        int xx = Math.min(width - 1, Math.max(0, x + k));
                        acc += BLUR_KERNEL[k + 2] * (src[row + xx] & 0xff);
                    }
                    tmp[row + x] = (byte)((acc + 128) >> 8);
                }
            }
        });
        parallelRows(height, (y0, y1) -> {
            for(int y=y0;y<y1;y++) {
                for(int x=0;x<width;x++) {
                    int acc = 0;
                    for(int k=-2;k<=2;k++) {
                        int yy = Math.min(height - 1, Math.max(0, y + k));
                        acc += BLUR_KERNEL[k + 2] * (tmp[yy * width + x] & 0xff);
                    }
                    dst[y * width + x] = (byte)((acc + 128) >> 8);
                }
            }
        });
        return dst;
    }

    /** Applies the look in place. faces are face bounding boxes in bitmap coordinates
     *  (may be empty). Returns null if the bitmap can't be processed, in which case the
     *  caller keeps the original.
     */
    public static Result process(Bitmap bitmap, Look look, List<RectF> faces) {
        final List<RectF> f = faces;
        return process(bitmap, look, () -> f);
    }

    /** As above, but faces are found by faceSource on another thread while the luma plane
     *  and blur are computed, so face detection costs no extra time.
     */
    public static Result process(Bitmap bitmap, Look look, Callable<List<RectF>> faceSource) {
        if( bitmap == null || !bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.ARGB_8888 ) {
            Log.w(TAG, "skip: bitmap " + (bitmap == null ? "null" : bitmap.getConfig() + " mutable=" + bitmap.isMutable()));
            return null;
        }
        long start = System.currentTimeMillis();
        ExecutorService faceExecutor = null;
        Future<List<RectF>> faceJob = null;
        if( look.faceLight && faceSource != null ) {
            faceExecutor = Executors.newSingleThreadExecutor();
            faceJob = faceExecutor.submit(faceSource);
        }
        final int [] lumaLut = buildLumaLut(look);
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();

        // pass 1: luma plane
        final byte [] lumaPlane = new byte[width * height];
        final long [] sumBefore = new long[1];
        parallelRows(height, (y0, y1) -> {
            int [] row = new int[width];
            long sum = 0;
            for(int y=y0;y<y1;y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                for(int x=0;x<width;x++) {
                    int l = luma(row[x]);
                    lumaPlane[y * width + x] = (byte)l;
                    sum += l;
                }
            }
            synchronized(sumBefore) {
                sumBefore[0] += sum;
            }
        });
        final byte [] blurred = look.soften > 0 ? blurLumaParallel(lumaPlane, width, height) : null;

        List<RectF> faces = Collections.emptyList();
        if( faceJob != null ) {
            try {
                List<RectF> found = faceJob.get();
                if( found != null )
                    faces = found;
            }
            catch(Exception e) {
                Log.e(TAG, "face detection failed", e);
            }
            faceExecutor.shutdown();
        }

        // faces: measure processed luma inside each face, work out the gain
        final FaceRegion [] regions = new FaceRegion[faces.size()];
        for(int i=0;i<faces.size();i++) {
            RectF face = faces.get(i);
            float cx = face.centerX(), cy = face.centerY();
            float rx = face.width() * 0.5f, ry = face.height() * 0.6f;
            long sum = 0;
            int n = 0;
            int x0 = Math.max(0, (int)(cx - rx * 0.7f)), x1 = Math.min(width - 1, (int)(cx + rx * 0.7f));
            int y0 = Math.max(0, (int)(cy - ry * 0.7f)), y1 = Math.min(height - 1, (int)(cy + ry * 0.7f));
            for(int y=y0;y<=y1;y+=2) {
                for(int x=x0;x<=x1;x+=2) {
                    int l = lumaPlane[y * width + x] & 0xff;
                    if( blurred != null )
                        l = softenLuma(l, blurred[y * width + x] & 0xff, look.soften);
                    sum += lumaLut[l];
                    n++;
                }
            }
            float mean = n > 0 ? (float)sum / n : 0.0f;
            regions[i] = new FaceRegion(cx, cy, rx, ry, faceGain(mean));
            Log.d(TAG, "face " + face + " mean luma " + mean + " gain " + regions[i].gain);
        }

        // pass 2: texture, tone, faces, colour
        final long [] sumAfter = new long[1];
        parallelRows(height, (yStart, yEnd) -> {
            int [] row = new int[width];
            long sum = 0;
            for(int y=yStart;y<yEnd;y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                for(int x=0;x<width;x++) {
                    int idx = y * width + x;
                    int l = lumaPlane[idx] & 0xff;
                    if( blurred != null )
                        l = softenLuma(l, blurred[idx] & 0xff, look.soften);
                    float newLuma = lumaLut[l];
                    for(FaceRegion region : regions) {
                        if( region.gain > 1.0f ) {
                            float w = region.weight(x, y);
                            if( w > 0.0f )
                                newLuma = Math.min(255.0f, newLuma * (1.0f + (region.gain - 1.0f) * w));
                        }
                    }
                    int out = applyPixel(row[x], look, Math.round(newLuma));
                    sum += luma(out);
                    row[x] = out;
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1);
            }
            synchronized(sumAfter) {
                sumAfter[0] += sum;
            }
        });
        long elapsed = System.currentTimeMillis() - start;
        double n = (double)width * height;
        Result result = new Result(bitmap, elapsed, (float)(sumBefore[0] / n), (float)(sumAfter[0] / n), regions.length);
        Log.d(TAG, "processed " + width + "x" + height + " look " + look + " faces " + regions.length + " in " + elapsed + "ms, mean Y " + result.meanYBefore + " -> " + result.meanYAfter);
        return result;
    }

    static int luma(int c) {
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
