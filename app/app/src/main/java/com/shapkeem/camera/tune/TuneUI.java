package com.shapkeem.camera.tune;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyApplicationInterface;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.preview.Preview;

/** Galaxy-camera-style layout on top of Open Camera's preview:
 *  top bar (settings, flash, timer, cover screen), zoom chips over the preview, a mode
 *  strip (야간 / 인물 / 사진 / 동영상 / 더보기) and a bottom bar (gallery, shutter, switch).
 *  Open Camera's own on-screen controls are hidden; every button calls the existing
 *  Open Camera action, so behaviour (saving, settings, tuning) is unchanged.
 *  Icons are Open Camera's bundled Material icons; nothing is taken from Samsung's app.
 */
public class TuneUI {
    private static final String TAG = "TuneUI";
    public static final String PREF_ENABLED = "preference_tune_galaxy_ui";

    private static final int[] HIDDEN_IDS = {
            R.id.take_photo, R.id.switch_camera, R.id.switch_multi_camera, R.id.switch_video,
            R.id.gallery, R.id.settings, R.id.popup, R.id.exposure, R.id.zoom_seekbar,
            R.id.cycle_flash, R.id.exposure_lock, R.id.white_balance_lock, R.id.cycle_raw,
            R.id.store_location, R.id.text_stamp, R.id.stamp, R.id.focus_peaking, R.id.auto_level,
            R.id.face_detection, R.id.audio_control, R.id.cycle_lock_orientation,
    };
    private static final int[] TIMER_VALUES = {0, 3, 10};
    private static final int ACCENT = 0xffffc533;

    private enum Mode { NIGHT, PORTRAIT, PHOTO, VIDEO, MORE }

    private final MainActivity activity;
    private final float density;
    private FrameLayout root;
    private ImageView flashButton;
    private TextView timerButton;
    private TextView coverButton;
    private LinearLayout zoomRow;
    private LinearLayout modeRow;
    private ImageView galleryThumb;
    private View shutterInner;
    private ImageButton switchButton;
    private Runnable coverToggle;

    public TuneUI(MainActivity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    public static boolean isEnabled(MainActivity activity) {
        return PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_ENABLED, true);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private Preview preview() {
        return activity.getPreview();
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(activity);
    }

    /** Builds the overlay. coverToggle runs the cover-screen preview toggle. */
    public void install(Runnable coverToggle) {
        this.coverToggle = coverToggle;
        quietPreviewOverlays();

        ViewGroup content = activity.findViewById(android.R.id.content);
        root = new FrameLayout(activity);
        content.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });

        root.addView(buildTopBar(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP));

        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(buildZoomRow(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        bottom.addView(buildModeRow(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        bottom.addView(buildControlRow(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Open Camera re-shows its controls on many state changes; keep them hidden
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                hideOpenCameraControls();
            }
        });
        hideOpenCameraControls();
        refresh();
    }

    /** Turns off Open Camera's on-preview text (time, free memory, ISO, camera id), once. */
    private void quietPreviewOverlays() {
        SharedPreferences p = prefs();
        if( p.getBoolean("tune_ui_quieted", false) )
            return;
        p.edit()
                .putBoolean(PreferenceKeys.ShowTimePreferenceKey, false)
                .putBoolean(PreferenceKeys.ShowFreeMemoryPreferenceKey, false)
                .putBoolean(PreferenceKeys.ShowISOPreferenceKey, false)
                .putBoolean(PreferenceKeys.ShowCameraIDPreferenceKey, false)
                .putBoolean(PreferenceKeys.ShowZoomSliderControlsPreferenceKey, false)
                .putBoolean("tune_ui_quieted", true)
                .apply();
    }

    private void hideOpenCameraControls() {
        for(int id : HIDDEN_IDS) {
            View v = activity.findViewById(id);
            if( v != null && v.getVisibility() != View.GONE )
                v.setVisibility(View.GONE);
        }
        // mirror Open Camera's gallery thumbnail into ours
        View gallery = activity.findViewById(R.id.gallery);
        if( gallery instanceof ImageView && galleryThumb != null ) {
            Drawable d = ((ImageView)gallery).getDrawable();
            if( d != null && galleryThumb.getDrawable() != d )
                galleryThumb.setImageDrawable(d);
        }
    }

    // ---- top bar ----

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(12), 0);
        bar.setBackgroundColor(Color.BLACK);

        ImageView settings = iconButton(R.drawable.settings);
        settings.setOnClickListener(v -> activity.clickedSettings(v));
        bar.addView(settings, weight());

        flashButton = iconButton(R.drawable.flash_off);
        flashButton.setOnClickListener(v -> {
            activity.clickedCycleFlash(v);
            refresh();
        });
        bar.addView(flashButton, weight());

        timerButton = textButton("타이머 끔");
        timerButton.setOnClickListener(v -> cycleTimer());
        bar.addView(timerButton, weight());

        coverButton = textButton("후면 화면");
        coverButton.setOnClickListener(v -> {
            if( coverToggle != null )
                coverToggle.run();
        });
        bar.addView(coverButton, weight());

        ImageView pro = iconButton(R.drawable.ic_more_horiz_white_48dp);
        pro.setOnClickListener(v -> activity.clickedPopupSettings(v));
        bar.addView(pro, weight());
        return bar;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
    }

    private ImageView iconButton(int res) {
        ImageView b = new ImageView(activity);
        b.setImageResource(res);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        b.setBackground(null);
        return b;
    }

    private TextView textButton(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void cycleTimer() {
        int current = 0;
        try {
            current = Integer.parseInt(prefs().getString(PreferenceKeys.TimerPreferenceKey, "0"));
        }
        catch(NumberFormatException ignored) {
        }
        int next = TIMER_VALUES[0];
        for(int i=0;i<TIMER_VALUES.length;i++) {
            if( TIMER_VALUES[i] == current ) {
                next = TIMER_VALUES[(i + 1) % TIMER_VALUES.length];
                break;
            }
        }
        prefs().edit().putString(PreferenceKeys.TimerPreferenceKey, String.valueOf(next)).apply();
        refresh();
    }

    // ---- zoom ----

    private View buildZoomRow() {
        zoomRow = new LinearLayout(activity);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        return zoomRow;
    }

    private void rebuildZoomChips() {
        zoomRow.removeAllViews();
        Preview preview = preview();
        if( !preview.supportsZoom() )
            return;
        float min = preview.getMinZoomRatio(), max = preview.getMaxZoomRatio();
        float current = preview.getZoomRatio();
        float[] targets = {0.6f, 1.0f, 2.0f, 10.0f};
        for(float target : targets) {
            if( target < min - 0.05f || target > max + 0.05f )
                continue;
            boolean selected = Math.abs(current - target) < 0.08f * target;
            TextView chip = new TextView(activity);
            String label = target < 1.0f ? ".6" : (int)target + (selected ? "x" : "");
            chip.setText(label);
            chip.setTextColor(selected ? ACCENT : Color.WHITE);
            chip.setTextSize(selected ? 13 : 12);
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x66000000);
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(selected ? 44 : 36), dp(selected ? 44 : 36));
            lp.leftMargin = lp.rightMargin = dp(6);
            chip.setOnClickListener(v -> {
                zoomToRatio(target);
                rebuildZoomChips();
            });
            zoomRow.addView(chip, lp);
        }
    }

    private void zoomToRatio(float target) {
        Preview preview = preview();
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for(int i=0;i<=preview.getMaxZoom();i++) {
            float diff = Math.abs(preview.getZoomRatio(i) - target);
            if( diff < bestDiff ) {
                bestDiff = diff;
                best = i;
            }
        }
        preview.zoomTo(best, true, true);
    }

    // ---- modes ----

    private View buildModeRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(Color.BLACK);
        modeRow = new LinearLayout(activity);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        scroll.addView(modeRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setFillViewport(true);
        addMode("야간", Mode.NIGHT);
        addMode("인물", Mode.PORTRAIT);
        addMode("사진", Mode.PHOTO);
        addMode("동영상", Mode.VIDEO);
        addMode("더보기", Mode.MORE);
        return scroll;
    }

    private void addMode(String label, Mode mode) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTag(mode);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        t.setOnClickListener(v -> selectMode(mode));
        modeRow.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private Mode currentMode() {
        if( preview().isVideo() )
            return Mode.VIDEO;
        MyApplicationInterface.PhotoMode m = activity.getApplicationInterface().getPhotoMode();
        if( m == MyApplicationInterface.PhotoMode.X_Night )
            return Mode.NIGHT;
        if( m == MyApplicationInterface.PhotoMode.X_Bokeh )
            return Mode.PORTRAIT;
        if( m == MyApplicationInterface.PhotoMode.Standard )
            return Mode.PHOTO;
        return Mode.MORE;
    }

    private void selectMode(Mode mode) {
        switch( mode ) {
            case NIGHT:
                setPhotoMode("preference_photo_mode_x_night", CameraExtensionCharacteristics.EXTENSION_NIGHT, "야간");
                break;
            case PORTRAIT:
                setPhotoMode("preference_photo_mode_x_bokeh", CameraExtensionCharacteristics.EXTENSION_BOKEH, "인물");
                break;
            case PHOTO:
                setPhotoMode("preference_photo_mode_std", -1, "사진");
                break;
            case VIDEO:
                if( !preview().isVideo() )
                    activity.clickedSwitchVideo(modeRow);
                break;
            case MORE:
                showMoreSheet();
                break;
        }
        modeRow.postDelayed(this::refresh, 300);
    }

    private void setPhotoMode(String value, int extension, String label) {
        if( extension >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !activity.supportsCameraExtension(extension) ) {
            preview().showToast(null, label + " 모드는 이 카메라에서 지원하지 않습니다", true);
            return;
        }
        if( preview().isVideo() )
            activity.clickedSwitchVideo(modeRow);
        prefs().edit().putString(PreferenceKeys.PhotoModePreferenceKey, value).apply();
        activity.getApplicationInterface().getDrawPreview().updateSettings();
        activity.updateForSettings(true, label, false, true);
    }

    private void showMoreSheet() {
        final String[] labels = {"프로 (수동 조절)", "얼굴 보정", "파노라마", "HDR (Open Camera)", "노이즈 감소", "연속 촬영", "설정"};
        new AlertDialog.Builder(activity)
                .setTitle("더보기")
                .setItems(labels, (dialog, which) -> {
                    switch( which ) {
                        case 0: activity.clickedPopupSettings(modeRow); break;
                        case 1: setPhotoMode("preference_photo_mode_x_beauty", CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH, "얼굴 보정"); break;
                        case 2: setPhotoMode("preference_photo_mode_panorama", -1, "파노라마"); break;
                        case 3: setPhotoMode("preference_photo_mode_hdr", -1, "HDR"); break;
                        case 4: setPhotoMode("preference_photo_mode_noise_reduction", -1, "노이즈 감소"); break;
                        case 5: setPhotoMode("preference_photo_mode_fast_burst", -1, "연속 촬영"); break;
                        case 6: activity.clickedSettings(modeRow); break;
                    }
                    modeRow.postDelayed(this::refresh, 300);
                })
                .show();
    }

    // ---- bottom controls ----

    private View buildControlRow() {
        FrameLayout row = new FrameLayout(activity);
        row.setBackgroundColor(Color.BLACK);

        galleryThumb = new ImageView(activity);
        galleryThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(0xff333333);
        galleryThumb.setBackground(thumbBg);
        galleryThumb.setClipToOutline(true);
        galleryThumb.setOnClickListener(v -> activity.clickedGallery(v));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER_VERTICAL | Gravity.START);
        glp.leftMargin = dp(36);
        row.addView(galleryThumb, glp);

        FrameLayout shutter = new FrameLayout(activity);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(4), Color.WHITE);
        shutter.setBackground(ring);
        shutterInner = new View(activity);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER);
        shutter.addView(shutterInner, ilp);
        shutter.setOnClickListener(v -> {
            activity.clickedTakePhoto(v);
            shutter.postDelayed(this::refresh, 300);
        });
        row.addView(shutter, new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER));

        switchButton = new ImageButton(activity);
        switchButton.setImageResource(R.drawable.switch_camera);
        switchButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        switchButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable swBg = new GradientDrawable();
        swBg.setShape(GradientDrawable.OVAL);
        swBg.setColor(0xff2a2a2a);
        switchButton.setBackground(swBg);
        switchButton.setOnClickListener(v -> {
            activity.clickedSwitchCamera(v);
            v.postDelayed(this::refresh, 600);
        });
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER_VERTICAL | Gravity.END);
        slp.rightMargin = dp(36);
        row.addView(switchButton, slp);
        return row;
    }

    // ---- state ----

    /** Updates labels and highlights from the current camera state. */
    public void refresh() {
        if( root == null )
            return;
        try {
            Mode mode = currentMode();
            for(int i=0;i<modeRow.getChildCount();i++) {
                TextView t = (TextView)modeRow.getChildAt(i);
                boolean selected = t.getTag() == mode;
                t.setTextColor(selected ? Color.WHITE : 0xffaaaaaa);
                t.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
                if( selected ) {
                    GradientDrawable pill = new GradientDrawable();
                    pill.setCornerRadius(dp(16));
                    pill.setColor(0xff3a3a3a);
                    t.setBackground(pill);
                }
                else {
                    t.setBackground(null);
                }
            }

            boolean video = preview().isVideo();
            boolean recording = preview().isVideoRecording();
            GradientDrawable inner = new GradientDrawable();
            inner.setShape(recording ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
            if( recording )
                inner.setCornerRadius(dp(6));
            inner.setColor(video ? 0xffe53935 : Color.WHITE);
            shutterInner.setBackground(inner);
            FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams)shutterInner.getLayoutParams();
            ilp.width = ilp.height = dp(recording ? 30 : 62);
            shutterInner.setLayoutParams(ilp);

            String flash = activity.getApplicationInterface().getFlashPref();
            int flashIcon = R.drawable.flash_off;
            if( "flash_auto".equals(flash) )
                flashIcon = R.drawable.flash_auto;
            else if( "flash_on".equals(flash) || "flash_torch".equals(flash) )
                flashIcon = R.drawable.flash_on;
            flashButton.setImageResource(flashIcon);
            flashButton.setVisibility(preview().supportsFlash() ? View.VISIBLE : View.INVISIBLE);

            String timer = prefs().getString(PreferenceKeys.TimerPreferenceKey, "0");
            timerButton.setText("0".equals(timer) ? "타이머 끔" : timer + "초");
            timerButton.setTextColor("0".equals(timer) ? Color.WHITE : ACCENT);

            rebuildZoomChips();
        }
        catch(Throwable t) {
            Log.e(TAG, "refresh failed", t);
        }
    }

    public void setCoverState(boolean supported, boolean active) {
        if( coverButton == null )
            return;
        coverButton.setVisibility(supported || active ? View.VISIBLE : View.INVISIBLE);
        coverButton.setText(active ? "후면 끄기" : "후면 화면");
        coverButton.setTextColor(active ? ACCENT : Color.WHITE);
    }
}
