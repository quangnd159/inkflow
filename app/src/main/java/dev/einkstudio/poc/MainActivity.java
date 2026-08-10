package dev.einkstudio.poc;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public final class MainActivity extends Activity {
    private static final String TAG = "EinkStudio";
    private static final float[] WIDTHS = {3f, 5f, 8f};
    private static final int MAX_HISTORY = 50;
    private static final float ERASER_RADIUS_DP = 28f;

    private enum Tool { PEN, ERASER }

    private SurfaceView surface;
    private LinearLayout topBar;
    private LinearLayout toolbar;
    private TextView status;
    private TouchHelper touchHelper;
    private ProjectStore projectStore;
    private InkDocument document;
    private final Deque<List<InkDocument.Stroke>> undo = new ArrayDeque<>();
    private final Deque<List<InkDocument.Stroke>> redo = new ArrayDeque<>();
    private final List<Button> widthButtons = new ArrayList<>();
    private Button penButton;
    private Button eraserButton;
    private Button undoButton;
    private Button redoButton;
    private Tool tool = Tool.PEN;
    private float strokeWidth = WIDTHS[1];
    private List<InkDocument.Point> completedRawPoints = Collections.emptyList();
    private boolean surfaceReady;
    private boolean initialized;
    private boolean resumed;
    private boolean eraserGestureChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enterImmersiveMode();
        projectStore = new ProjectStore(this);
        document = projectStore.load();
        buildInterface();
        bindSurface();
        updateControls();
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        surface = new SurfaceView(this);
        surface.setBackgroundColor(Color.WHITE);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(18), dp(10), dp(18), dp(10));
        topBar.setBackgroundColor(0xF7FFFFFF);
        TextView title = new TextView(this);
        title.setText("Eink Studio");
        title.setTextColor(Color.BLACK);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        status = new TextView(this);
        status.setText("Preparing ink…");
        status.setTextColor(0xFF555555);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        topBar.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64),
                Gravity.TOP
        ));

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        toolbar.setBackgroundColor(0xF7FFFFFF);

        penButton = addToolButton(toolbar, "Pen", () -> selectTool(Tool.PEN));
        eraserButton = addToolButton(toolbar, "Erase", () -> selectTool(Tool.ERASER));
        addDivider(toolbar);
        for (int index = 0; index < WIDTHS.length; index++) {
            final float width = WIDTHS[index];
            Button button = addWidthButton(toolbar, index + 1, () -> selectWidth(width));
            widthButtons.add(button);
        }
        addDivider(toolbar);
        undoButton = addToolButton(toolbar, "Undo", this::undo);
        redoButton = addToolButton(toolbar, "Redo", this::redo);
        addToolButton(toolbar, "Clear", this::confirmClear);

        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72),
                Gravity.BOTTOM
        );
        root.addView(toolbar, toolbarParams);
        setContentView(root);
    }

    private void bindSurface() {
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                renderDocument();
                surface.post(MainActivity.this::initializeRawInkIfReady);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = width > 0 && height > 0;
                renderDocument();
                surface.post(MainActivity.this::initializeRawInkIfReady);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
            }
        });
        surface.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> initializeRawInkIfReady());
        surface.setOnTouchListener(this::handleSurfaceTouch);
    }

    private void initializeRawInkIfReady() {
        if (initialized || !surfaceReady || surface.getWidth() == 0 || surface.getHeight() == 0) return;
        try {
            touchHelper = TouchHelper.create(surface, rawInputCallback);
            Rect limit = new Rect();
            surface.getLocalVisibleRect(limit);
            List<Rect> excluded = new ArrayList<>();
            excluded.add(relativeRect(surface, topBar));
            excluded.add(relativeRect(surface, toolbar));
            touchHelper
                    .setStrokeWidth(nativeStrokeWidth())
                    .setLimitRect(limit, excluded)
                    .openRawDrawing();
            touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN);
            touchHelper.setRawDrawingRenderEnabled(true);
            touchHelper.setRawDrawingEnabled(resumed && tool == Tool.PEN);
            initialized = true;
            status.setText("Autosaved locally");
            Log.i(TAG, "BOOX raw ink ready");
        } catch (Throwable error) {
            Log.e(TAG, "BOOX raw ink unavailable", error);
            closeRawInk();
            status.setText("Raw ink unavailable");
        }
    }

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override
        public void onBeginRawDrawing(boolean stylus, TouchPoint point) {
            completedRawPoints = Collections.emptyList();
        }

        @Override
        public void onEndRawDrawing(boolean stylus, TouchPoint point) {
            if (completedRawPoints.isEmpty()) return;
            pushHistory();
            document.add(new InkDocument.Stroke(strokeWidth, completedRawPoints));
            completedRawPoints = Collections.emptyList();
            projectStore.save(document);
            runOnUiThread(MainActivity.this::updateControls);
        }

        @Override
        public void onRawDrawingTouchPointMoveReceived(TouchPoint point) {
            // Never allocate or redraw on the latency-critical live path.
        }

        @Override
        public void onRawDrawingTouchPointListReceived(TouchPointList points) {
            completedRawPoints = normalize(points.getPoints());
        }

        @Override
        public void onBeginRawErasing(boolean stylus, TouchPoint point) {}

        @Override
        public void onEndRawErasing(boolean stylus, TouchPoint point) {
            renderDocument();
        }

        @Override
        public void onRawErasingTouchPointMoveReceived(TouchPoint point) {}

        @Override
        public void onRawErasingTouchPointListReceived(TouchPointList points) {
            List<InkDocument.Point> erasePoints = normalize(points.getPoints());
            if (erasePoints.isEmpty()) return;
            pushHistory();
            boolean changed = false;
            for (InkDocument.Point point : erasePoints) {
                changed |= document.eraseAt(
                        point.x,
                        point.y,
                        dp(ERASER_RADIUS_DP),
                        surface.getWidth(),
                        surface.getHeight()
                );
            }
            if (changed) projectStore.save(document);
            else undo.pollLast();
        }
    };

    private boolean handleSurfaceTouch(View view, MotionEvent event) {
        if (tool != Tool.ERASER || !surfaceReady) return true;
        float x = event.getX() / Math.max(1f, surface.getWidth());
        float y = event.getY() / Math.max(1f, surface.getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pushHistory();
                eraserGestureChanged = eraseAt(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                eraserGestureChanged |= eraseAt(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (eraserGestureChanged) {
                    renderDocument();
                    projectStore.save(document);
                } else {
                    undo.pollLast();
                }
                updateControls();
                return true;
            default:
                return true;
        }
    }

    private boolean eraseAt(float x, float y) {
        return document.eraseAt(x, y, dp(ERASER_RADIUS_DP), surface.getWidth(), surface.getHeight());
    }

    private List<InkDocument.Point> normalize(List<TouchPoint> rawPoints) {
        if (rawPoints == null || rawPoints.isEmpty()) return Collections.emptyList();
        int width = Math.max(1, surface.getWidth());
        int height = Math.max(1, surface.getHeight());
        long now = SystemClock.uptimeMillis();
        List<InkDocument.Point> points = new ArrayList<>(rawPoints.size());
        for (TouchPoint point : rawPoints) {
            points.add(new InkDocument.Point(
                    point.getX() / width,
                    point.getY() / height,
                    pressureOf(point),
                    now
            ));
        }
        return points;
    }

    private void renderDocument() {
        if (!surfaceReady || surface.getHolder() == null) return;
        Canvas canvas = null;
        try {
            canvas = surface.getHolder().lockCanvas();
            if (canvas == null) return;
            canvas.drawColor(Color.WHITE);
            drawDots(canvas);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            for (InkDocument.Stroke stroke : document.strokes()) drawStroke(canvas, paint, stroke);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to render canvas", error);
        } finally {
            if (canvas != null) surface.getHolder().unlockCanvasAndPost(canvas);
        }
    }

    private void drawDots(Canvas canvas) {
        Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
        dots.setColor(0xFFD5D5D5);
        dots.setStyle(Paint.Style.FILL);
        float spacing = dp(28);
        float radius = Math.max(1f, getResources().getDisplayMetrics().density);
        for (float y = spacing; y < canvas.getHeight(); y += spacing) {
            for (float x = spacing; x < canvas.getWidth(); x += spacing) {
                canvas.drawCircle(x, y, radius, dots);
            }
        }
    }

    private void drawStroke(Canvas canvas, Paint paint, InkDocument.Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        InkDocument.Point first = stroke.points.get(0);
        if (stroke.points.size() == 1) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(first.x * canvas.getWidth(), first.y * canvas.getHeight(), dp(stroke.width) / 2f, paint);
            paint.setStyle(Paint.Style.STROKE);
            return;
        }
        InkDocument.Point previous = first;
        for (int index = 1; index < stroke.points.size(); index++) {
            InkDocument.Point point = stroke.points.get(index);
            float pressure = (previous.pressure + point.pressure) / 2f;
            paint.setStrokeWidth(dp(stroke.width) * (0.55f + pressure * 0.7f));
            canvas.drawLine(
                    previous.x * canvas.getWidth(),
                    previous.y * canvas.getHeight(),
                    point.x * canvas.getWidth(),
                    point.y * canvas.getHeight(),
                    paint
            );
            previous = point;
        }
    }

    private void selectTool(Tool selected) {
        tool = selected;
        if (touchHelper != null) touchHelper.setRawDrawingEnabled(resumed && tool == Tool.PEN);
        updateControls();
    }

    private void selectWidth(float width) {
        strokeWidth = width;
        tool = Tool.PEN;
        if (touchHelper != null) {
            touchHelper.setStrokeWidth(nativeStrokeWidth());
            touchHelper.setRawDrawingEnabled(resumed);
        }
        updateControls();
    }

    private void undo() {
        List<InkDocument.Stroke> previous = undo.pollLast();
        if (previous == null) return;
        redo.addLast(document.snapshot());
        document.replaceWith(previous);
        renderDocument();
        projectStore.save(document);
        updateControls();
    }

    private void redo() {
        List<InkDocument.Stroke> next = redo.pollLast();
        if (next == null) return;
        undo.addLast(document.snapshot());
        document.replaceWith(next);
        renderDocument();
        projectStore.save(document);
        updateControls();
    }

    private void confirmClear() {
        if (document.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Clear canvas?")
                .setMessage("The current drawing will be cleared. You can undo immediately afterward.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    pushHistory();
                    document.clear();
                    renderDocument();
                    projectStore.save(document);
                    updateControls();
                })
                .show();
    }

    private void pushHistory() {
        undo.addLast(document.snapshot());
        while (undo.size() > MAX_HISTORY) undo.pollFirst();
        redo.clear();
    }

    private void updateControls() {
        styleSelected(penButton, tool == Tool.PEN);
        styleSelected(eraserButton, tool == Tool.ERASER);
        for (int index = 0; index < widthButtons.size(); index++) {
            styleSelected(widthButtons.get(index), strokeWidth == WIDTHS[index]);
        }
        undoButton.setEnabled(!undo.isEmpty());
        redoButton.setEnabled(!redo.isEmpty());
    }

    private Button addToolButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        return button;
    }

    private Button addWidthButton(LinearLayout parent, int label, Runnable action) {
        Button button = addToolButton(parent, String.valueOf(label), action);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        params.width = dp(48);
        button.setLayoutParams(params);
        return button;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(30));
        params.setMargins(dp(7), 0, dp(7), 0);
        parent.addView(divider, params);
    }

    private void styleSelected(Button button, boolean selected) {
        if (button == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(selected ? Color.BLACK : 0xFFF0F0F0);
        background.setStroke(dp(1), selected ? Color.BLACK : 0xFFD0D0D0);
        button.setTextColor(selected ? Color.WHITE : Color.BLACK);
        button.setBackground(background);
        button.setAlpha(button.isEnabled() ? 1f : 0.4f);
    }

    private Rect relativeRect(View parent, View child) {
        int[] parentLocation = new int[2];
        int[] childLocation = new int[2];
        parent.getLocationOnScreen(parentLocation);
        child.getLocationOnScreen(childLocation);
        Rect rect = new Rect();
        child.getLocalVisibleRect(rect);
        rect.offset(childLocation[0] - parentLocation[0], childLocation[1] - parentLocation[1]);
        return rect;
    }

    private float nativeStrokeWidth() {
        return dp(strokeWidth);
    }

    private float pressureOf(TouchPoint point) {
        try {
            Method method = point.getClass().getMethod("getPressure");
            Object value = method.invoke(point);
            if (value instanceof Number) return Math.max(0f, Math.min(1f, ((Number) value).floatValue()));
        } catch (ReflectiveOperationException ignored) {
            // Older firmware does not expose pressure through the same accessor.
        }
        return 0.5f;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        enterImmersiveMode();
        if (touchHelper != null) touchHelper.setRawDrawingEnabled(tool == Tool.PEN);
        else if (surface != null) surface.post(this::initializeRawInkIfReady);
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (touchHelper != null) touchHelper.setRawDrawingEnabled(false);
        projectStore.save(document);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeRawInk();
        projectStore.close();
        super.onDestroy();
    }

    private void closeRawInk() {
        if (touchHelper == null) return;
        try {
            touchHelper.setRawDrawingEnabled(false);
            touchHelper.closeRawDrawing();
        } catch (Throwable error) {
            Log.w(TAG, "Unable to close raw ink", error);
        } finally {
            touchHelper = null;
            initialized = false;
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
