package dev.inkflow.boox;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;

public final class MainActivity extends Activity {
    private static final String TAG = "InkflowBooxLab";
    private static final float STROKE_WIDTH = 3.0f;

    private SurfaceView inkSurface;
    private TextView status;
    private TouchHelper touchHelper;
    private boolean surfaceReady;
    private boolean initialized;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enterImmersiveMode();
        buildUi();
        prepareSurface();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        inkSurface = new SurfaceView(this);
        inkSurface.setBackgroundColor(Color.WHITE);
        root.addView(inkSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        status = new TextView(this);
        status.setText("Preparing BOOX raw ink…");
        status.setTextColor(Color.BLACK);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xFFF1F1F1);
        int horizontal = dp(16);
        int vertical = dp(10);
        status.setPadding(horizontal, vertical, horizontal, vertical);

        LinearLayout hud = new LinearLayout(this);
        hud.setGravity(Gravity.CENTER_HORIZONTAL);
        hud.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        hudParams.topMargin = dp(18);
        root.addView(hud, hudParams);
        setContentView(root);
    }

    private void prepareSurface() {
        inkSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                drawPaper(holder);
                inkSurface.post(MainActivity.this::initializeRawInkIfReady);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = width > 0 && height > 0;
                drawPaper(holder);
                inkSurface.post(MainActivity.this::initializeRawInkIfReady);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
            }
        });

        inkSurface.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> initializeRawInkIfReady());
        inkSurface.setOnTouchListener((view, event) -> true);
    }

    private void initializeRawInkIfReady() {
        if (initialized || !surfaceReady || inkSurface.getWidth() == 0 || inkSurface.getHeight() == 0) {
            return;
        }

        try {
            touchHelper = TouchHelper.create(inkSurface, rawInputCallback);
            Rect limit = new Rect();
            inkSurface.getLocalVisibleRect(limit);
            touchHelper
                    .setStrokeWidth(STROKE_WIDTH)
                    .setLimitRect(limit)
                    .openRawDrawing();
            touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN);
            touchHelper.setRawDrawingEnabled(resumed);
            initialized = true;
            setStatus("Raw ink ready");
            Log.i(TAG, "Onyx raw drawing initialized");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to initialize Onyx raw drawing", error);
            closeRawInk();
            setStatus("Raw ink unavailable: " + concise(error));
        }
    }

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override
        public void onBeginRawDrawing(boolean stylus, TouchPoint point) {
            runOnUiThread(() -> setStatus("Raw ink active — write anywhere"));
        }

        @Override
        public void onEndRawDrawing(boolean stylus, TouchPoint point) {
            runOnUiThread(() -> setStatus("Raw ink ready"));
        }

        @Override
        public void onRawDrawingTouchPointMoveReceived(TouchPoint point) {
            // The Onyx renderer draws the live stroke; avoid work on this hot path.
        }

        @Override
        public void onRawDrawingTouchPointListReceived(TouchPointList points) {
            // This latency probe intentionally does not persist strokes.
        }

        @Override
        public void onBeginRawErasing(boolean stylus, TouchPoint point) {}

        @Override
        public void onEndRawErasing(boolean stylus, TouchPoint point) {}

        @Override
        public void onRawErasingTouchPointMoveReceived(TouchPoint point) {}

        @Override
        public void onRawErasingTouchPointListReceived(TouchPointList points) {}
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        enterImmersiveMode();
        if (touchHelper != null) {
            touchHelper.setRawDrawingEnabled(true);
        } else if (inkSurface != null) {
            inkSurface.post(this::initializeRawInkIfReady);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (touchHelper != null) {
            touchHelper.setRawDrawingEnabled(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeRawInk();
        super.onDestroy();
    }

    private void closeRawInk() {
        if (touchHelper == null) return;
        try {
            touchHelper.setRawDrawingEnabled(false);
            touchHelper.closeRawDrawing();
        } catch (Throwable error) {
            Log.w(TAG, "Unable to close Onyx raw drawing cleanly", error);
        } finally {
            touchHelper = null;
            initialized = false;
        }
    }

    private void drawPaper(SurfaceHolder holder) {
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) return;
            canvas.drawColor(Color.WHITE);
            Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
            dots.setColor(0xFFD7D7D7);
            float spacing = getResources().getDisplayMetrics().density * 28f;
            float radius = Math.max(1f, getResources().getDisplayMetrics().density);
            for (float y = spacing; y < canvas.getHeight(); y += spacing) {
                for (float x = spacing; x < canvas.getWidth(); x += spacing) {
                    canvas.drawCircle(x, y, radius, dots);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to prepare drawing surface", error);
        } finally {
            if (canvas != null) holder.unlockCanvasAndPost(canvas);
        }
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String concise(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : " — " + message);
    }
}
