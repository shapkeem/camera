package com.shapkeem.camera.tune;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;

import androidx.core.util.Consumer;
import androidx.window.area.WindowAreaCapability;
import androidx.window.area.WindowAreaController;
import androidx.window.area.WindowAreaInfo;
import androidx.window.area.WindowAreaPresentationSessionCallback;
import androidx.window.area.WindowAreaSession;
import androidx.window.area.WindowAreaSessionCallback;
import androidx.window.area.WindowAreaSessionPresenter;
import androidx.window.java.area.WindowAreaControllerCallbackAdapter;

import java.util.List;
import java.util.concurrent.Executor;

/** Shows the camera preview on the Flip's cover (rear) screen while the phone is unfolded,
 *  for rear-camera selfies. Uses Jetpack WindowManager's rear display APIs:
 *  - dual screen (OPERATION_PRESENT_ON_AREA): main screen keeps the camera UI, the cover
 *    screen shows a mirrored copy of the preview
 *  - rear display mode (OPERATION_TRANSFER_ACTIVITY_TO_AREA): the whole app moves to the
 *    cover screen, used when dual screen isn't offered
 *  What the device supports is only known at runtime; statusText() reports it.
 */
public class RearDisplay {
    private static final String TAG = "RearDisplay";
    private static final long FRAME_INTERVAL_MS = 66;
    private static final int FRAME_MAX_SIDE = 720;

    public interface Listener {
        void onRearDisplayChanged();
    }

    public interface PreviewSource {
        View getPreviewView();
    }

    private final Activity activity;
    private final PreviewSource previewSource;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor executor = handler::post;
    private WindowAreaControllerCallbackAdapter controller;
    private WindowAreaInfo rearInfo;
    private WindowAreaCapability.Status presentStatus;
    private WindowAreaCapability.Status transferStatus;
    private WindowAreaSession session;
    private boolean sessionIsPresentation;
    private ImageView mirrorView;
    private Bitmap lastFrame;

    private final Consumer<List<WindowAreaInfo>> infoListener = infos -> {
        rearInfo = null;
        presentStatus = null;
        transferStatus = null;
        for(WindowAreaInfo info : infos) {
            if( info.getType() == WindowAreaInfo.Type.TYPE_REAR_FACING ) {
                rearInfo = info;
                WindowAreaCapability present = info.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA);
                WindowAreaCapability transfer = info.getCapability(WindowAreaCapability.Operation.OPERATION_TRANSFER_ACTIVITY_TO_AREA);
                presentStatus = present != null ? present.getStatus() : null;
                transferStatus = transfer != null ? transfer.getStatus() : null;
            }
        }
        Log.d(TAG, "window areas: " + statusText());
        RearDisplay.this.listener.onRearDisplayChanged();
    };

    public RearDisplay(Activity activity, PreviewSource previewSource, Listener listener) {
        this.activity = activity;
        this.previewSource = previewSource;
        this.listener = listener;
    }

    public void start() {
        try {
            if( controller == null )
                controller = new WindowAreaControllerCallbackAdapter(WindowAreaController.getOrCreate());
            controller.addWindowAreaInfoListListener(executor, infoListener);
        }
        catch(Throwable t) {
            Log.e(TAG, "window area API not available", t);
            controller = null;
        }
    }

    /** Stops listening. A dual-screen presentation is closed (it mirrors this activity's
     *  preview); a rear display mode session is left alone since moving the activity to the
     *  cover screen itself pauses and resumes it.
     */
    public void stop() {
        if( session != null && sessionIsPresentation )
            close();
        if( controller != null ) {
            try {
                controller.removeWindowAreaInfoListListener(infoListener);
            }
            catch(Throwable t) {
                Log.e(TAG, "remove listener failed", t);
            }
        }
    }

    private static boolean usable(WindowAreaCapability.Status status) {
        return status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ||
                status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE;
    }

    public boolean isSupported() {
        return rearInfo != null && (usable(presentStatus) || usable(transferStatus));
    }

    public boolean isActive() {
        return session != null;
    }

    public String statusText() {
        if( controller == null )
            return "후면 화면 API 없음";
        if( rearInfo == null )
            return "후면 화면 정보 없음";
        return "듀얼 스크린 " + name(presentStatus) + " / 후면 디스플레이 모드 " + name(transferStatus);
    }

    private static String name(WindowAreaCapability.Status status) {
        if( status == null )
            return "-";
        if( status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE )
            return "가능";
        if( status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE )
            return "사용 중";
        if( status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE )
            return "지금은 불가 (폰을 펼쳐야 함)";
        return "미지원";
    }

    /** Starts or stops showing the preview on the cover screen. Returns a message for the user. */
    public String toggle() {
        if( session != null ) {
            close();
            return "후면 화면 미리보기 끔";
        }
        if( controller == null || rearInfo == null )
            return "이 기기에서는 후면 화면을 쓸 수 없습니다 (" + statusText() + ")";
        Binder token = rearInfo.getToken();
        try {
            if( usable(presentStatus) ) {
                controller.presentContentOnWindowArea(token, activity, executor, new WindowAreaPresentationSessionCallback() {
                    @Override
                    public void onSessionStarted(WindowAreaSessionPresenter presenter) {
                        session = presenter;
                        sessionIsPresentation = true;
                        mirrorView = new ImageView(presenter.getContext());
                        mirrorView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        mirrorView.setScaleX(-1.0f); // mirror, like looking into a mirror
                        mirrorView.setBackgroundColor(0xff000000);
                        presenter.setContentView(mirrorView);
                        handler.post(frameLoop);
                        listener.onRearDisplayChanged();
                    }

                    @Override
                    public void onSessionEnded(Throwable t) {
                        if( t != null )
                            Log.e(TAG, "presentation ended with error", t);
                        endedCleanup();
                    }

                    @Override
                    public void onContainerVisibilityChanged(boolean visible) {
                        Log.d(TAG, "cover screen visible: " + visible);
                    }
                });
                return "후면 화면에 미리보기 표시";
            }
            if( usable(transferStatus) ) {
                controller.transferActivityToWindowArea(token, activity, executor, new WindowAreaSessionCallback() {
                    @Override
                    public void onSessionStarted(WindowAreaSession s) {
                        session = s;
                        sessionIsPresentation = false;
                        listener.onRearDisplayChanged();
                    }

                    @Override
                    public void onSessionEnded(Throwable t) {
                        if( t != null )
                            Log.e(TAG, "rear display mode ended with error", t);
                        endedCleanup();
                    }
                });
                return "앱을 후면 화면으로 옮깁니다 (펼친 채로 사용)";
            }
        }
        catch(Throwable t) {
            Log.e(TAG, "starting rear display failed", t);
            return "후면 화면 시작 실패: " + t.getMessage();
        }
        return "지금은 후면 화면을 쓸 수 없습니다 (" + statusText() + ")";
    }

    private void close() {
        WindowAreaSession s = session;
        if( s != null ) {
            try {
                s.close();
            }
            catch(Throwable t) {
                Log.e(TAG, "close failed", t);
            }
        }
        endedCleanup();
    }

    private void endedCleanup() {
        handler.removeCallbacks(frameLoop);
        session = null;
        mirrorView = null;
        if( lastFrame != null ) {
            lastFrame.recycle();
            lastFrame = null;
        }
        listener.onRearDisplayChanged();
    }

    /** Copies the on-screen camera preview to the cover screen at about 15 fps. */
    private final Runnable frameLoop = new Runnable() {
        @Override
        public void run() {
            if( session == null || mirrorView == null )
                return;
            try {
                View view = previewSource.getPreviewView();
                if( view instanceof TextureView && ((TextureView)view).isAvailable() && view.getWidth() > 0 ) {
                    float scale = Math.min(1.0f, FRAME_MAX_SIDE / (float)Math.max(view.getWidth(), view.getHeight()));
                    Bitmap frame = ((TextureView)view).getBitmap(Math.round(view.getWidth() * scale), Math.round(view.getHeight() * scale));
                    if( frame != null ) {
                        mirrorView.setImageBitmap(frame);
                        if( lastFrame != null )
                            lastFrame.recycle();
                        lastFrame = frame;
                    }
                }
            }
            catch(Throwable t) {
                Log.e(TAG, "frame copy failed", t);
            }
            handler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };
}
