package com.shapkeem.camera.tune;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only report of what this device's Camera2 HAL exposes to third-party apps.
 *  Nothing here opens a camera; it only queries characteristics.
 */
public class DiagnosticsActivity extends Activity {
    private TextView textView;
    private String report = "";

    // Samsung vendor tags seen in the stock camera app (see analysis/app-plan.md).
    // We try to read them as int[] (stream configurations / capabilities).
    private static final String [] SAMSUNG_INT_ARRAY_KEYS = {
            "samsung.android.scaler.availableHighresYuvStreamConfigurations",
            "samsung.android.scaler.availableHighresRawStreamConfigurations",
            "samsung.android.scaler.availableMidHighresYuvStreamConfigurations",
            "samsung.android.scaler.availableMidHighresRawStreamConfigurations",
            "samsung.android.scaler.availableExpertRawHighresRawStreamConfigurations",
            "samsung.android.scaler.availableExpertRawHighresYuvStreamConfigurations",
            "samsung.android.scaler.availableFusionHighresStreamConfigurations",
            "samsung.android.scaler.availableSuperResolutionRawStreamConfigurations",
            "samsung.android.scaler.availableRemosaicCropCapabilities",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button copyButton = new Button(this);
        copyButton.setText("복사");
        copyButton.setOnClickListener(v -> copyReport());
        Button shareButton = new Button(this);
        shareButton.setText("공유");
        shareButton.setOnClickListener(v -> shareReport());
        buttons.addView(copyButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(shareButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        ScrollView scrollView = new ScrollView(this);
        textView = new TextView(this);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextSize(11);
        textView.setTextIsSelectable(true);
        int pad = (int)(8 * getResources().getDisplayMetrics().density);
        textView.setPadding(pad, pad, pad, pad);
        textView.setText("진단 중...");
        scrollView.addView(textView);
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // targetSdk 36 is edge-to-edge: keep content clear of system bars
        root.setOnApplyWindowInsetsListener((View v, android.view.WindowInsets insets) -> {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });

        setContentView(root);
        setTitle("기기 기능 진단");

        new Thread(() -> {
            String result;
            try {
                result = buildReport();
            }
            catch(Throwable t) {
                result = "진단 실패: " + t;
            }
            final String text = result;
            runOnUiThread(() -> {
                report = text;
                textView.setText(text);
            });
        }).start();
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Tune Camera diagnostics", report));
        Toast.makeText(this, "복사했습니다", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Tune Camera diagnostics");
        intent.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(intent, "진단 결과 공유"));
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("== Device ==\n");
        sb.append("model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("build: ").append(Build.DISPLAY).append('\n');
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M )
            sb.append("security patch: ").append(Build.VERSION.SECURITY_PATCH).append('\n');

        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        String [] ids;
        try {
            ids = manager.getCameraIdList();
        }
        catch(CameraAccessException e) {
            return sb.append("getCameraIdList failed: ").append(e).toString();
        }
        sb.append("\n== Camera ids ==\n");
        sb.append("listed: ").append(Arrays.toString(ids)).append('\n');

        // Some vendors expose extra ids that are not in getCameraIdList(); probe 0..99.
        List<String> hidden = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>(Arrays.asList(ids));
        for(int i=0;i<100;i++) {
            String id = String.valueOf(i);
            if( listed.contains(id) )
                continue;
            try {
                manager.getCameraCharacteristics(id);
                hidden.add(id);
            }
            catch(Throwable ignored) {
            }
        }
        sb.append("unlisted but openable characteristics (0-99): ").append(hidden).append('\n');

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
            try {
                sb.append("concurrent camera ids: ").append(manager.getConcurrentCameraIds()).append('\n');
            }
            catch(Throwable t) {
                sb.append("concurrent camera ids: error ").append(t).append('\n');
            }
        }

        List<String> all = new ArrayList<>(listed);
        all.addAll(hidden);
        for(String id : all) {
            sb.append("\n========== camera ").append(id).append(listed.contains(id) ? "" : " (unlisted)").append(" ==========\n");
            try {
                describeCamera(sb, manager, id);
            }
            catch(Throwable t) {
                sb.append("error: ").append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private void describeCamera(StringBuilder sb, CameraManager manager, String id) throws CameraAccessException {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);

        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        sb.append("facing: ").append(facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_BACK ? "back" : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "external").append('\n');
        Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        sb.append("hardware level: ").append(hardwareLevelName(level)).append('\n');
        float [] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        sb.append("focal lengths: ").append(Arrays.toString(focal)).append('\n');
        SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        sb.append("sensor physical size: ").append(physical).append('\n');
        sb.append("pixel array: ").append(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)).append('\n');
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ) {
            sb.append("pixel array (max resolution): ").append(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)).append('\n');
        }
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
            sb.append("physical ids: ").append(c.getPhysicalCameraIds()).append('\n');
        }

        int [] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        sb.append("capabilities: ");
        if( caps != null ) {
            for(int cap : caps)
                sb.append(capabilityName(cap)).append(' ');
        }
        sb.append('\n');

        int [] stab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        sb.append("video stabilization modes: ").append(Arrays.toString(stab)).append(" (0=off 1=on 2=preview)\n");
        int [] ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        sb.append("OIS modes: ").append(Arrays.toString(ois)).append('\n');
        Range<Float> zoom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
        sb.append("zoom ratio range: ").append(zoom).append('\n');

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        sb.append("-- output formats (max size) --\n");
        describeFormats(sb, map);
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ) {
            StreamConfigurationMap maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            sb.append("-- maximum resolution mode formats --\n");
            if( maxMap == null )
                sb.append("  (none)\n");
            else
                describeFormats(sb, maxMap);
        }

        if( map != null ) {
            Size [] hs = map.getHighSpeedVideoSizes();
            sb.append("-- high speed video --\n");
            if( hs == null || hs.length == 0 )
                sb.append("  (none)\n");
            else {
                for(Size s : hs)
                    sb.append("  ").append(s).append(' ').append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(s))).append('\n');
            }
        }

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ) {
            DynamicRangeProfiles profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
            sb.append("dynamic range profiles: ").append(profiles == null ? "none" : profiles.getSupportedProfiles()).append(" (1=SDR 2=HLG10 4=HDR10 8=HDR10+ ...)\n");
        }

        describeExtensions(sb, manager, id);
        describeVendorTags(sb, c);
    }

    private void describeFormats(StringBuilder sb, StreamConfigurationMap map) {
        if( map == null ) {
            sb.append("  (no stream map)\n");
            return;
        }
        int [] formats = map.getOutputFormats();
        for(int format : formats) {
            Size [] sizes = map.getOutputSizes(format);
            Size [] high = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? map.getHighResolutionOutputSizes(format) : null;
            sb.append("  ").append(formatName(format)).append(": max ").append(maxSize(sizes));
            if( high != null && high.length > 0 )
                sb.append(", high-res (slow) max ").append(maxSize(high));
            sb.append(" [").append(sizes == null ? 0 : sizes.length).append(" sizes]\n");
        }
    }

    private void describeExtensions(StringBuilder sb, CameraManager manager, String id) {
        sb.append("-- camera extensions --\n");
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.S ) {
            sb.append("  requires Android 12\n");
            return;
        }
        try {
            CameraExtensionCharacteristics ext = manager.getCameraExtensionCharacteristics(id);
            List<Integer> supported = ext.getSupportedExtensions();
            if( supported.isEmpty() ) {
                sb.append("  (none)\n");
                return;
            }
            for(int e : supported) {
                sb.append("  [").append(extensionName(e)).append("]\n");
                int [] formats = {ImageFormat.JPEG, ImageFormat.YUV_420_888, ImageFormat.JPEG_R, ImageFormat.HEIC, ImageFormat.YCBCR_P010};
                for(int f : formats) {
                    try {
                        List<Size> sizes = ext.getExtensionSupportedSizes(e, f);
                        if( !sizes.isEmpty() )
                            sb.append("    ").append(formatName(f)).append(": max ").append(maxSize(sizes.toArray(new Size[0]))).append(" [").append(sizes.size()).append(" sizes]\n");
                    }
                    catch(Throwable ignored) {
                    }
                }
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ) {
                    try {
                        List<Size> jpeg = ext.getExtensionSupportedSizes(e, ImageFormat.JPEG);
                        if( !jpeg.isEmpty() ) {
                            Size big = maxSizeObj(jpeg.toArray(new Size[0]));
                            Range<Long> latency = ext.getEstimatedCaptureLatencyRangeMillis(e, big, ImageFormat.JPEG);
                            sb.append("    estimated latency @").append(big).append(": ").append(latency == null ? "unknown" : latency + " ms").append('\n');
                        }
                    }
                    catch(Throwable t) {
                        sb.append("    latency: error ").append(t).append('\n');
                    }
                    try {
                        sb.append("    request keys: ").append(keyNames(ext.getAvailableCaptureRequestKeys(e))).append('\n');
                    }
                    catch(Throwable t) {
                        sb.append("    request keys: error ").append(t).append('\n');
                    }
                }
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ) {
                    try {
                        sb.append("    postview: ").append(ext.isPostviewAvailable(e))
                                .append(", progress callback: ").append(ext.isCaptureProcessProgressAvailable(e)).append('\n');
                    }
                    catch(Throwable t) {
                        sb.append("    postview: error ").append(t).append('\n');
                    }
                }
            }
        }
        catch(Throwable t) {
            sb.append("  error: ").append(t).append('\n');
        }
    }

    private void describeVendorTags(StringBuilder sb, CameraCharacteristics c) {
        sb.append("-- vendor tags (samsung.*) --\n");
        List<String> charKeys = vendorNames(c.getKeys());
        List<String> reqKeys = vendorNames(c.getAvailableCaptureRequestKeys());
        List<String> resKeys = vendorNames(c.getAvailableCaptureResultKeys());
        sb.append("  characteristics: ").append(charKeys.size()).append('\n');
        for(String k : charKeys)
            sb.append("    ").append(k).append('\n');
        sb.append("  request keys: ").append(reqKeys.size()).append('\n');
        for(String k : reqKeys)
            sb.append("    ").append(k).append('\n');
        sb.append("  result keys: ").append(resKeys.size()).append('\n');
        for(String k : resKeys)
            sb.append("    ").append(k).append('\n');

        sb.append("  high-res candidates (read as int[]):\n");
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ) {
            sb.append("    requires Android 10\n");
            return;
        }
        for(String name : SAMSUNG_INT_ARRAY_KEYS) {
            sb.append("    ").append(name.substring(name.lastIndexOf('.') + 1)).append(": ");
            try {
                int [] value = c.get(new CameraCharacteristics.Key<>(name, int[].class));
                if( value == null )
                    sb.append("null\n");
                else
                    sb.append("len ").append(value.length).append(' ').append(abbreviate(value)).append('\n');
            }
            catch(Throwable t) {
                sb.append("error ").append(t.getClass().getSimpleName()).append('\n');
            }
        }
    }

    private static List<String> vendorNames(List<?> keys) {
        List<String> names = new ArrayList<>();
        if( keys == null )
            return names;
        for(Object key : keys) {
            String name;
            if( key instanceof CameraCharacteristics.Key )
                name = ((CameraCharacteristics.Key<?>)key).getName();
            else if( key instanceof CaptureRequest.Key )
                name = ((CaptureRequest.Key<?>)key).getName();
            else if( key instanceof CaptureResult.Key )
                name = ((CaptureResult.Key<?>)key).getName();
            else
                name = String.valueOf(key);
            if( !name.startsWith("android.") )
                names.add(name);
        }
        java.util.Collections.sort(names);
        return names;
    }

    private static String keyNames(Set<CaptureRequest.Key> keys) {
        List<String> names = new ArrayList<>();
        for(CaptureRequest.Key<?> key : keys)
            names.add(key.getName().replace("android.", ""));
        java.util.Collections.sort(names);
        return names.toString();
    }

    private static String abbreviate(int [] value) {
        int n = Math.min(value.length, 40);
        StringBuilder sb = new StringBuilder("[");
        for(int i=0;i<n;i++) {
            if( i > 0 )
                sb.append(", ");
            sb.append(value[i]);
        }
        if( value.length > n )
            sb.append(", ...");
        return sb.append(']').toString();
    }

    private static Size maxSizeObj(Size [] sizes) {
        Size best = null;
        if( sizes != null ) {
            for(Size s : sizes) {
                if( best == null || (long)s.getWidth()*s.getHeight() > (long)best.getWidth()*best.getHeight() )
                    best = s;
            }
        }
        return best;
    }

    private static String maxSize(Size [] sizes) {
        Size best = maxSizeObj(sizes);
        if( best == null )
            return "-";
        double mp = (double)best.getWidth()*best.getHeight()/1.0e6;
        return best + String.format(java.util.Locale.US, " (%.1fMP)", mp);
    }

    private static String formatName(int format) {
        switch( format ) {
            case ImageFormat.JPEG: return "JPEG";
            case ImageFormat.YUV_420_888: return "YUV_420_888";
            case ImageFormat.RAW_SENSOR: return "RAW_SENSOR";
            case ImageFormat.RAW10: return "RAW10";
            case ImageFormat.RAW12: return "RAW12";
            case ImageFormat.RAW_PRIVATE: return "RAW_PRIVATE";
            case ImageFormat.PRIVATE: return "PRIVATE";
            case ImageFormat.HEIC: return "HEIC";
            case ImageFormat.YCBCR_P010: return "YCBCR_P010";
            case ImageFormat.JPEG_R: return "JPEG_R (Ultra HDR)";
            case ImageFormat.DEPTH16: return "DEPTH16";
            case ImageFormat.DEPTH_JPEG: return "DEPTH_JPEG";
            case ImageFormat.NV21: return "NV21";
            case ImageFormat.Y8: return "Y8";
            case 0x1006: return "HEIC_ULTRAHDR";
            default: return "format 0x" + Integer.toHexString(format);
        }
    }

    private static String extensionName(int extension) {
        switch( extension ) {
            case CameraExtensionCharacteristics.EXTENSION_AUTOMATIC: return "AUTO";
            case CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH: return "FACE_RETOUCH";
            case CameraExtensionCharacteristics.EXTENSION_BOKEH: return "BOKEH";
            case CameraExtensionCharacteristics.EXTENSION_HDR: return "HDR";
            case CameraExtensionCharacteristics.EXTENSION_NIGHT: return "NIGHT";
            default: return "extension " + extension;
        }
    }

    private static String hardwareLevelName(Integer level) {
        if( level == null )
            return "?";
        switch( level ) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return "level " + level;
        }
    }

    private static String capabilityName(int cap) {
        switch( cap ) {
            case 0: return "BACKWARD_COMPATIBLE";
            case 1: return "MANUAL_SENSOR";
            case 2: return "MANUAL_POST_PROCESSING";
            case 3: return "RAW";
            case 4: return "PRIVATE_REPROCESSING";
            case 5: return "READ_SENSOR_SETTINGS";
            case 6: return "BURST_CAPTURE";
            case 7: return "YUV_REPROCESSING";
            case 8: return "DEPTH_OUTPUT";
            case 9: return "CONSTRAINED_HIGH_SPEED_VIDEO";
            case 10: return "MOTION_TRACKING";
            case 11: return "LOGICAL_MULTI_CAMERA";
            case 12: return "MONOCHROME";
            case 13: return "SECURE_IMAGE_DATA";
            case 14: return "SYSTEM_CAMERA";
            case 15: return "OFFLINE_PROCESSING";
            case 16: return "ULTRA_HIGH_RESOLUTION_SENSOR";
            case 17: return "REMOSAIC_REPROCESSING";
            case 18: return "DYNAMIC_RANGE_TEN_BIT";
            case 19: return "STREAM_USE_CASE";
            case 20: return "COLOR_SPACE_PROFILES";
            default: return "cap" + cap;
        }
    }
}
