package com.shapkeem.camera.tune;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Finds faces in a (rotated, upright) bitmap with ML Kit's on-device face detector.
 *  Works on a downscaled copy; boxes are returned in the original bitmap's coordinates.
 *  Returns an empty list on any problem, so face lighting is simply skipped.
 */
public class FaceFinder {
    private static final String TAG = "FaceFinder";
    private static final int MAX_SIDE = 1024;

    public static List<RectF> find(Bitmap bitmap) {
        if( bitmap == null || Looper.myLooper() == Looper.getMainLooper() ) {
            // Tasks.await() must not block the UI thread
            return Collections.emptyList();
        }
        FaceDetector detector = null;
        try {
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            float scale = Math.min(1.0f, MAX_SIDE / (float)Math.max(w, h));
            Bitmap small = scale < 1.0f ? Bitmap.createScaledBitmap(bitmap, Math.round(w * scale), Math.round(h * scale), true) : bitmap;
            FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.08f)
                    .build();
            detector = FaceDetection.getClient(options);
            List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(small, 0)), 3, TimeUnit.SECONDS);
            List<RectF> result = new ArrayList<>();
            for(Face face : faces) {
                Rect box = face.getBoundingBox();
                result.add(new RectF(box.left / scale, box.top / scale, box.right / scale, box.bottom / scale));
            }
            if( small != bitmap )
                small.recycle();
            return result;
        }
        catch(Throwable t) {
            Log.e(TAG, "face detection failed", t);
            return Collections.emptyList();
        }
        finally {
            if( detector != null )
                detector.close();
        }
    }
}
