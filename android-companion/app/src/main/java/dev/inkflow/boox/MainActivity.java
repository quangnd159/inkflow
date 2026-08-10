package dev.inkflow.boox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "InkflowBoox";
    private static final int CHOOSE_VAULT_REQUEST = 41;
    private static final int SAVE_DELAY_MS = 420;
    private static final int MAX_HISTORY = 50;
    private static final float ERASER_RADIUS = 28f;
    private static final float[] WIDTHS = {3f, 5f, 8f};

    private enum Tool { PEN, ERASER }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final ArrayDeque<ArrayList<InkDocumentModel.Stroke>> undo = new ArrayDeque<>();
    private final ArrayDeque<ArrayList<InkDocumentModel.Stroke>> redo = new ArrayDeque<>();

    private VaultStore vaultStore;
    private SessionSpec session;
    private InkDocumentModel document;
    private FrameLayout root;
    private SurfaceView inkSurface;
    private LinearLayout header;
    private LinearLayout toolbar;
    private FrameLayout setupOverlay;
    private TextView titleView;
    private TextView statusView;
    private Button penButton;
    private Button eraserButton;
    private final ArrayList<Button> widthButtons = new ArrayList<>();
    private Button undoButton;
    private Button redoButton;
    private TouchHelper touchHelper;
    private boolean surfaceReady;
    private boolean initialized;
    private boolean resumed;
    private boolean eraserGestureChanged;
    private Tool tool = Tool.PEN;
    private float strokeWidth = 5f;
    private long changeGeneration;
    private final Runnable delayedSave = this::persistCurrentDocument;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        vaultStore = new VaultStore(this);
        enterImmersiveMode();
        buildInterface();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        SessionSpec parsed = SessionSpec.from(intent);
        if (parsed != null) session = parsed;
        if (!vaultStore.isConnected()) {
            showSetup("Choose your Obsidian vault once. Inkflow receives access only to that folder.", "Choose vault");
        } else if (session == null) {
            showSetup("Open a note in Obsidian, then choose “Open in BOOX native handwriting”.", "Change vault");
        } else {
            hideSetup();
            loadSession();
        }
    }

    private void buildInterface() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        inkSurface = new SurfaceView(this);
        inkSurface.setBackgroundColor(Color.WHITE);
        root.addView(inkSurface, matchParent());

        buildHeader();
        buildToolbar();
        setupSurface();
        setContentView(root);
    }

    private void buildHeader() {
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(8), dp(10), dp(8));
        header.setBackgroundColor(0xF7FFFFFF);

        Button back = textButton("‹", "Return to Obsidian");
        back.setTextSize(28);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, dp(8), 0);
        titleView = new TextView(this);
        titleView.setText("Inkflow");
        titleView.setTextColor(Color.BLACK);
        titleView.setTextSize(17);
        titleView.setSingleLine(true);
        statusView = new TextView(this);
        statusView.setText("Open from Obsidian");
        statusView.setTextColor(0xFF555555);
        statusView.setTextSize(12);
        labels.addView(titleView);
        labels.addView(statusView);
        header.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button delete = textButton("×", "Delete this handwriting");
        delete.setTextSize(26);
        delete.setOnClickListener(view -> confirmDelete());
        header.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(header, params);
    }

    private void buildToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setPadding(dp(8), dp(7), dp(8), dp(7));
        toolbar.setBackgroundColor(0xF7FFFFFF);

        penButton = textButton("✎", "Pen");
        eraserButton = textButton("⌫", "Stroke eraser");
        penButton.setOnClickListener(view -> selectTool(Tool.PEN));
        eraserButton.setOnClickListener(view -> selectTool(Tool.ERASER));
        toolbar.addView(penButton, toolLayout());
        toolbar.addView(eraserButton, toolLayout());
        addDivider(toolbar);

        for (int index = 0; index < WIDTHS.length; index++) {
            final float width = WIDTHS[index];
            Button button = textButton("━", new String[]{"Thin stroke", "Regular stroke", "Bold stroke"}[index]);
            button.setTextSize(12 + index * 5);
            button.setOnClickListener(view -> selectWidth(width));
            widthButtons.add(button);
            toolbar.addView(button, toolLayout());
        }
        addDivider(toolbar);

        undoButton = textButton("↶", "Undo");
        redoButton = textButton("↷", "Redo");
        undoButton.setTextSize(24);
        redoButton.setTextSize(24);
        undoButton.setOnClickListener(view -> undo());
        redoButton.setOnClickListener(view -> redo());
        toolbar.addView(undoButton, toolLayout());
        toolbar.addView(redoButton, toolLayout());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(toolbar, params);
        updateControls();
    }

    private void setupSurface() {
        inkSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                drawDocumentToSurface();
                inkSurface.post(MainActivity.this::initializeRawInkIfReady);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = width > 0 && height > 0;
                drawDocumentToSurface();
                inkSurface.post(MainActivity.this::initializeRawInkIfReady);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
            }
        });
        inkSurface.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> initializeRawInkIfReady());
        inkSurface.setOnTouchListener(this::onSurfaceTouch);
    }

    private void loadSession() {
        closeRawInk();
        document = null;
        undo.clear();
        redo.clear();
        titleView.setText(session.title);
        setStatus("Loading…");
        fileExecutor.execute(() -> {
            try {
                InkDocumentModel loaded = InkDocumentModel.parse(vaultStore.readText(session.sourcePath));
                mainHandler.post(() -> {
                    document = loaded;
                    drawDocumentToSurface();
                    initializeRawInkIfReady();
                    setStatus("Saved");
                    updateControls();
                });
            } catch (Throwable error) {
                Log.e(TAG, "Unable to load Inkflow document", error);
                mainHandler.post(() -> showSetup(
                        "Could not open this Inkflow file in the selected folder. Choose the vault root, not its attachment folder.\n\n" + concise(error),
                        "Choose vault"
                ));
            }
        });
    }

    private void initializeRawInkIfReady() {
        if (initialized || document == null || !surfaceReady || inkSurface.getWidth() == 0 || inkSurface.getHeight() == 0) return;
        try {
            touchHelper = TouchHelper.create(inkSurface, rawInputCallback);
            Rect limit = new Rect(0, 0, inkSurface.getWidth(), inkSurface.getHeight());
            ArrayList<Rect> excluded = new ArrayList<>();
            excluded.add(relativeRect(header));
            excluded.add(relativeRect(toolbar));
            touchHelper
                    .setStrokeWidth(strokeWidth * displayScale())
                    .setLimitRect(limit, excluded)
                    .openRawDrawing();
            touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN);
            touchHelper.setRawDrawingEnabled(resumed && tool == Tool.PEN);
            initialized = true;
            setStatus("Saved");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to initialize Onyx raw drawing", error);
            closeRawInk();
            setStatus("Raw ink unavailable: " + concise(error));
        }
    }

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override
        public void onBeginRawDrawing(boolean stylus, TouchPoint point) {
            mainHandler.post(() -> setStatus("Writing…"));
        }

        @Override
        public void onEndRawDrawing(boolean stylus, TouchPoint point) {
            mainHandler.post(() -> {
                drawDocumentToSurface();
                scheduleSave();
            });
        }

        @Override
        public void onRawDrawingTouchPointMoveReceived(TouchPoint point) {
            // Onyx owns the zero-latency display path; persistence is batched at pen-up.
        }

        @Override
        public void onRawDrawingTouchPointListReceived(TouchPointList pointList) {
            if (document == null || pointList == null || pointList.getPoints() == null) return;
            ArrayList<InkDocumentModel.Point> points = new ArrayList<>();
            long time = SystemClock.uptimeMillis();
            for (TouchPoint point : pointList.getPoints()) {
                InkDocumentModel.Point mapped = mapPoint(point.getX(), point.getY(), pressure(point), time++);
                if (mapped != null) points.add(mapped);
            }
            if (points.isEmpty()) return;
            synchronized (MainActivity.this) {
                pushHistory();
                document.strokes.add(new InkDocumentModel.Stroke(UUID.randomUUID().toString(), strokeWidth, points));
            }
        }

        @Override public void onBeginRawErasing(boolean stylus, TouchPoint point) {}
        @Override public void onEndRawErasing(boolean stylus, TouchPoint point) {}
        @Override public void onRawErasingTouchPointMoveReceived(TouchPoint point) {}
        @Override public void onRawErasingTouchPointListReceived(TouchPointList points) {}
    };

    private boolean onSurfaceTouch(View view, MotionEvent event) {
        if (tool != Tool.ERASER || document == null) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) eraserGestureChanged = false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            eraseAt(event.getX(), event.getY());
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (eraserGestureChanged) scheduleSave();
        }
        return true;
    }

    private void eraseAt(float viewX, float viewY) {
        InkDocumentModel.Point point = mapPoint(viewX, viewY, 0.5f, SystemClock.uptimeMillis());
        if (point == null) return;
        ArrayList<InkDocumentModel.Stroke> hits = new ArrayList<>();
        synchronized (this) {
            for (InkDocumentModel.Stroke stroke : document.strokes) {
                if (strokeHits(stroke, point, ERASER_RADIUS)) hits.add(stroke);
            }
            if (hits.isEmpty()) return;
            if (!eraserGestureChanged) pushHistory();
            document.strokes.removeAll(hits);
            eraserGestureChanged = true;
        }
        drawDocumentToSurface();
        updateControls();
    }

    private void selectTool(Tool selected) {
        tool = selected;
        if (touchHelper != null) touchHelper.setRawDrawingEnabled(resumed && tool == Tool.PEN);
        setStatus(selected == Tool.PEN ? "Pen" : "Stroke eraser");
        updateControls();
    }

    private void selectWidth(float width) {
        strokeWidth = width;
        selectTool(Tool.PEN);
        if (touchHelper != null) touchHelper.setStrokeWidth(strokeWidth * displayScale());
        updateControls();
    }

    private void undo() {
        if (document == null || undo.isEmpty()) return;
        redo.addLast(copyStrokes(document.strokes));
        document.strokes.clear();
        document.strokes.addAll(undo.removeLast());
        drawDocumentToSurface();
        scheduleSave();
        updateControls();
    }

    private void redo() {
        if (document == null || redo.isEmpty()) return;
        undo.addLast(copyStrokes(document.strokes));
        document.strokes.clear();
        document.strokes.addAll(redo.removeLast());
        drawDocumentToSurface();
        scheduleSave();
        updateControls();
    }

    private synchronized void pushHistory() {
        if (document == null) return;
        undo.addLast(copyStrokes(document.strokes));
        while (undo.size() > MAX_HISTORY) undo.removeFirst();
        redo.clear();
        mainHandler.post(this::updateControls);
    }

    private void scheduleSave() {
        changeGeneration++;
        setStatus("Saving…");
        mainHandler.removeCallbacks(delayedSave);
        mainHandler.postDelayed(delayedSave, SAVE_DELAY_MS);
    }

    private void persistCurrentDocument() {
        if (document == null || session == null) return;
        final long generation = changeGeneration;
        final InkDocumentModel snapshot;
        synchronized (this) {
            snapshot = document.copy();
        }
        fileExecutor.execute(() -> {
            android.graphics.Bitmap bitmap = null;
            try {
                vaultStore.writeText(session.sourcePath, snapshot.toJson());
                bitmap = snapshot.renderBitmap();
                vaultStore.writePng(session.snapshotPath, bitmap);
                mainHandler.post(() -> {
                    if (generation == changeGeneration) setStatus("Saved");
                });
            } catch (Throwable error) {
                Log.e(TAG, "Unable to save Inkflow document", error);
                mainHandler.post(() -> setStatus("Save failed — tap back after retrying"));
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
        });
    }

    private void confirmDelete() {
        if (session == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete handwriting?")
                .setMessage("The embed and private Inkflow files will be cleaned up when you return to Obsidian.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteHandwriting())
                .show();
    }

    private void deleteHandwriting() {
        mainHandler.removeCallbacks(delayedSave);
        setStatus("Deleting…");
        fileExecutor.execute(() -> {
            try {
                vaultStore.delete(session.snapshotPath);
                vaultStore.delete(session.sourcePath);
                mainHandler.post(this::finish);
            } catch (Throwable error) {
                Log.e(TAG, "Unable to delete handwriting", error);
                mainHandler.post(() -> setStatus("Delete failed: " + concise(error)));
            }
        });
    }

    private void drawDocumentToSurface() {
        if (!surfaceReady || inkSurface == null) return;
        Canvas canvas = null;
        boolean reenable = touchHelper != null && resumed && tool == Tool.PEN;
        try {
            if (touchHelper != null) touchHelper.setRawDrawingEnabled(false);
            canvas = inkSurface.getHolder().lockCanvas();
            if (canvas == null) return;
            if (document == null) {
                canvas.drawColor(Color.WHITE);
                return;
            }
            float scale = displayScale();
            float offsetX = (canvas.getWidth() - document.width * scale) / 2f;
            float offsetY = (canvas.getHeight() - document.height * scale) / 2f;
            synchronized (this) {
                document.draw(canvas, scale, offsetX, offsetY);
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to render document", error);
        } finally {
            if (canvas != null) inkSurface.getHolder().unlockCanvasAndPost(canvas);
            if (touchHelper != null) touchHelper.setRawDrawingEnabled(reenable);
        }
    }

    private float displayScale() {
        if (document == null || inkSurface == null || inkSurface.getWidth() == 0 || inkSurface.getHeight() == 0) return 1f;
        return Math.max(
                inkSurface.getWidth() / (float) document.width,
                inkSurface.getHeight() / (float) document.height
        );
    }

    private InkDocumentModel.Point mapPoint(float viewX, float viewY, float pressure, long time) {
        if (document == null) return null;
        float scale = displayScale();
        float offsetX = (inkSurface.getWidth() - document.width * scale) / 2f;
        float offsetY = (inkSurface.getHeight() - document.height * scale) / 2f;
        float x = (viewX - offsetX) / scale;
        float y = (viewY - offsetY) / scale;
        if (x < 0 || y < 0 || x > document.width || y > document.height) return null;
        return new InkDocumentModel.Point(x, y, pressure, time);
    }

    private static boolean strokeHits(InkDocumentModel.Stroke stroke, InkDocumentModel.Point point, float radius) {
        if (stroke.points.isEmpty()) return false;
        if (stroke.points.size() == 1) return distanceSquared(stroke.points.get(0), point) <= radius * radius;
        for (int index = 1; index < stroke.points.size(); index++) {
            if (segmentDistanceSquared(stroke.points.get(index - 1), stroke.points.get(index), point) <= radius * radius) return true;
        }
        return false;
    }

    private static float distanceSquared(InkDocumentModel.Point a, InkDocumentModel.Point b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private static float segmentDistanceSquared(InkDocumentModel.Point start, InkDocumentModel.Point end, InkDocumentModel.Point point) {
        float dx = end.x - start.x;
        float dy = end.y - start.y;
        if (dx == 0f && dy == 0f) return distanceSquared(start, point);
        float t = ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy);
        t = Math.max(0f, Math.min(1f, t));
        float projectedX = start.x + t * dx;
        float projectedY = start.y + t * dy;
        float pointDx = point.x - projectedX;
        float pointDy = point.y - projectedY;
        return pointDx * pointDx + pointDy * pointDy;
    }

    private static float pressure(TouchPoint point) {
        try {
            Method getter = point.getClass().getMethod("getPressure");
            Object value = getter.invoke(point);
            if (value instanceof Number) return Math.max(0f, Math.min(1f, ((Number) value).floatValue()));
        } catch (Throwable ignored) {
            // Older firmware omits pressure; a neutral value preserves a consistent stroke.
        }
        return 0.5f;
    }

    private void showSetup(String message, String action) {
        hideSetup();
        setupOverlay = new FrameLayout(this);
        setupOverlay.setBackgroundColor(Color.WHITE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(32), dp(32), dp(32), dp(32));
        TextView title = new TextView(this);
        title.setText("Inkflow BOOX");
        title.setTextColor(Color.BLACK);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        TextView body = new TextView(this);
        body.setText(message);
        body.setTextColor(0xFF444444);
        body.setTextSize(15);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(14), 0, dp(22));
        Button choose = textButton(action, action);
        choose.setOnClickListener(view -> chooseVault());
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(choose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        setupOverlay.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(setupOverlay, matchParent());
    }

    private void hideSetup() {
        if (setupOverlay != null) {
            root.removeView(setupOverlay);
            setupOverlay = null;
        }
    }

    private void chooseVault() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, CHOOSE_VAULT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CHOOSE_VAULT_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            vaultStore.connect(data.getData(), data.getFlags());
            handleIntent(getIntent());
        } catch (Throwable error) {
            showSetup("Android did not grant write access to that folder.\n\n" + concise(error), "Try again");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        enterImmersiveMode();
        if (touchHelper != null) touchHelper.setRawDrawingEnabled(tool == Tool.PEN);
        else if (inkSurface != null) inkSurface.post(this::initializeRawInkIfReady);
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(delayedSave);
        if (document != null && session != null) persistCurrentDocument();
        if (touchHelper != null) touchHelper.setRawDrawingEnabled(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        closeRawInk();
        fileExecutor.shutdown();
        super.onDestroy();
    }

    private void closeRawInk() {
        if (touchHelper == null) return;
        try {
            touchHelper.setRawDrawingEnabled(false);
            touchHelper.closeRawDrawing();
        } catch (Throwable error) {
            Log.w(TAG, "Unable to close raw drawing", error);
        } finally {
            touchHelper = null;
            initialized = false;
        }
    }

    private Rect relativeRect(View child) {
        int[] parentLocation = new int[2];
        int[] childLocation = new int[2];
        inkSurface.getLocationOnScreen(parentLocation);
        child.getLocationOnScreen(childLocation);
        return new Rect(
                childLocation[0] - parentLocation[0],
                childLocation[1] - parentLocation[1],
                childLocation[0] - parentLocation[0] + child.getWidth(),
                childLocation[1] - parentLocation[1] + child.getHeight()
        );
    }

    private void updateControls() {
        styleButton(penButton, tool == Tool.PEN);
        styleButton(eraserButton, tool == Tool.ERASER);
        for (int index = 0; index < widthButtons.size(); index++) {
            styleButton(widthButtons.get(index), strokeWidth == WIDTHS[index]);
        }
        if (undoButton != null) undoButton.setEnabled(!undo.isEmpty());
        if (redoButton != null) redoButton.setEnabled(!redo.isEmpty());
    }

    private void styleButton(Button button, boolean active) {
        if (button == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(active ? Color.BLACK : 0xFFF1F1F1);
        background.setStroke(dp(active ? 2 : 1), active ? Color.BLACK : 0xFFD6D6D6);
        button.setBackground(background);
        button.setTextColor(active ? Color.WHITE : Color.BLACK);
        button.setAlpha(button.isEnabled() ? 1f : 0.35f);
    }

    private Button textButton(String text, String description) {
        Button button = new Button(this);
        button.setText(text);
        button.setContentDescription(description);
        button.setAllCaps(false);
        button.setTextColor(Color.BLACK);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        styleButton(button, false);
        return button;
    }

    private LinearLayout.LayoutParams toolLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(50), dp(48));
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFD0D0D0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(30));
        params.setMargins(dp(7), 0, dp(7), 0);
        parent.addView(divider, params);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void setStatus(String status) {
        if (statusView != null) statusView.setText(status);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    private static ArrayList<InkDocumentModel.Stroke> copyStrokes(List<InkDocumentModel.Stroke> source) {
        ArrayList<InkDocumentModel.Stroke> copy = new ArrayList<>();
        for (InkDocumentModel.Stroke stroke : source) copy.add(stroke.copy());
        return copy;
    }

    private static String concise(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : " — " + message);
    }

    private static final class SessionSpec {
        final String title;
        final String sourcePath;
        final String snapshotPath;

        private SessionSpec(String title, String sourcePath, String snapshotPath) {
            this.title = title;
            this.sourcePath = sourcePath;
            this.snapshotPath = snapshotPath;
        }

        static SessionSpec from(Intent intent) {
            Uri uri = intent == null ? null : intent.getData();
            if (uri == null || !"inkflow-boox".equals(uri.getScheme()) || !"open".equals(uri.getHost())) return null;
            String title = uri.getQueryParameter("title");
            String source = uri.getQueryParameter("source");
            String snapshot = uri.getQueryParameter("snapshot");
            if (!safe(source, ".ink.json") || !safe(snapshot, ".png")) return null;
            return new SessionSpec(title == null || title.isEmpty() ? "Handwriting" : title, source, snapshot);
        }

        private static boolean safe(String path, String extension) {
            return path != null && path.endsWith(extension) && !path.startsWith("/") && !path.contains("..") && !path.contains("\\");
        }
    }
}
