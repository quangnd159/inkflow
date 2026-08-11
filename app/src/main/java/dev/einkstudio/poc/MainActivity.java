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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private static final float[] WIDTHS = {2.5f, 5f, 9f};
    private static final int[] COLORS = {0xFF000000, 0xFF444444, 0xFF888888, 0xFFBBBBBB};
    private static final String[] COLOR_NAMES = {"Black", "Dark", "Gray", "Light"};
    private static final int MAX_HISTORY = 50;
    private static final float ERASER_RADIUS_DP = 22f;

    private enum Tool { PEN, PENCIL, MARKER, ERASER }

    private SurfaceView surface;
    private LinearLayout topBar;
    private LinearLayout toolbar;
    private TextView status;
    private TouchHelper touchHelper;
    private ProjectStore projectStore;
    private InkDocument document;
    private final Deque<InkDocument> undo = new ArrayDeque<>();
    private final Deque<InkDocument> redo = new ArrayDeque<>();
    private final List<Button> brushButtons = new ArrayList<>();
    private final List<Button> widthButtons = new ArrayList<>();
    private final List<Button> colorButtons = new ArrayList<>();
    private Button eraserButton;
    private Button undoButton;
    private Button redoButton;
    private Button layersButton;
    private Tool tool = Tool.PEN;
    private float strokeWidth = WIDTHS[1];
    private int strokeColor = COLORS[0];
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(18), dp(8), dp(18), dp(8));
        topBar.setBackgroundColor(0xFAFFFFFF);
        TextView title = new TextView(this);
        title.setText("Inky Sketch");
        title.setTextColor(Color.BLACK);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        status = new TextView(this);
        status.setTextColor(0xFF444444);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        topBar.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.TOP));

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setPadding(dp(6), dp(5), dp(6), dp(5));
        toolbar.setBackgroundColor(0xFAFFFFFF);

        LinearLayout tools = toolbarRow();
        brushButtons.add(addToolButton(tools, "Pen", () -> selectTool(Tool.PEN)));
        brushButtons.add(addToolButton(tools, "Pencil", () -> selectTool(Tool.PENCIL)));
        brushButtons.add(addToolButton(tools, "Marker", () -> selectTool(Tool.MARKER)));
        eraserButton = addToolButton(tools, "Erase", () -> selectTool(Tool.ERASER));
        addDivider(tools);
        for (int index = 0; index < WIDTHS.length; index++) {
            final float width = WIDTHS[index];
            Button button = addToolButton(tools, String.valueOf(index + 1), () -> selectWidth(width));
            button.getLayoutParams().width = dp(46);
            widthButtons.add(button);
        }

        LinearLayout actions = toolbarRow();
        for (int index = 0; index < COLORS.length; index++) {
            final int color = COLORS[index];
            Button button = addToolButton(actions, COLOR_NAMES[index], () -> selectColor(color));
            colorButtons.add(button);
        }
        addDivider(actions);
        undoButton = addToolButton(actions, "Undo", this::undo);
        redoButton = addToolButton(actions, "Redo", this::redo);
        layersButton = addToolButton(actions, "Layers", this::showLayersDialog);

        root.addView(toolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110), Gravity.BOTTOM));
        setContentView(root);
    }

    private LinearLayout toolbarRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        toolbar.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        return row;
    }

    private void bindSurface() {
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                renderDocument();
                surface.post(MainActivity.this::initializeRawInkIfReady);
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = width > 0 && height > 0;
                renderDocument();
                surface.post(MainActivity.this::initializeRawInkIfReady);
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
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
            touchHelper.setStrokeWidth(nativeStrokeWidth()).setLimitRect(limit, excluded).openRawDrawing();
            touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN);
            touchHelper.setRawDrawingRenderEnabled(true);
            applyNativeBrush();
            initialized = true;
            updateRawDrawingEnabled();
            updateStatus();
            Log.i(TAG, "BOOX raw ink ready");
        } catch (Throwable error) {
            Log.e(TAG, "BOOX raw ink unavailable", error);
            closeRawInk();
            status.setText("Raw ink unavailable");
        }
    }

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override public void onBeginRawDrawing(boolean stylus, TouchPoint point) {
            completedRawPoints = Collections.emptyList();
        }

        @Override public void onEndRawDrawing(boolean stylus, TouchPoint point) {
            if (completedRawPoints.isEmpty()) return;
            pushHistory();
            document.addStroke(new InkDocument.Stroke(selectedBrush(), strokeWidth, strokeColor, completedRawPoints));
            completedRawPoints = Collections.emptyList();
            projectStore.save(document);
            runOnUiThread(MainActivity.this::updateControls);
        }

        @Override public void onRawDrawingTouchPointMoveReceived(TouchPoint point) {
            // Latency contract: no allocation, persistence, repaint, or UI work here.
        }

        @Override public void onRawDrawingTouchPointListReceived(TouchPointList points) {
            completedRawPoints = normalize(points.getPoints());
        }

        @Override public void onBeginRawErasing(boolean stylus, TouchPoint point) {}

        @Override public void onEndRawErasing(boolean stylus, TouchPoint point) {
            renderDocument();
            runOnUiThread(MainActivity.this::updateControls);
        }

        @Override public void onRawErasingTouchPointMoveReceived(TouchPoint point) {
            // Onyx owns the live eraser preview; document work waits for the completed list.
        }

        @Override public void onRawErasingTouchPointListReceived(TouchPointList points) {
            List<InkDocument.Point> erasePoints = normalize(points.getPoints());
            if (erasePoints.isEmpty()) return;
            pushHistory();
            boolean changed = false;
            for (InkDocument.Point point : erasePoints) changed |= eraseAt(point.x, point.y);
            if (changed) projectStore.save(document);
            else undo.pollLast();
        }
    };

    private boolean handleSurfaceTouch(View view, MotionEvent event) {
        if (tool != Tool.ERASER || !surfaceReady || !document.selectedLayer().visible) return true;
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
                } else undo.pollLast();
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
            points.add(new InkDocument.Point(point.getX() / width, point.getY() / height, pressureOf(point), now));
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
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (InkDocument.Layer layer : document.layers()) {
                if (!layer.visible) continue;
                for (InkDocument.Stroke stroke : layer.strokes) drawStroke(canvas, paint, stroke);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to render canvas", error);
        } finally {
            if (canvas != null) surface.getHolder().unlockCanvasAndPost(canvas);
        }
    }

    private void drawStroke(Canvas canvas, Paint paint, InkDocument.Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(stroke.brush == InkDocument.Brush.MARKER ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
        InkDocument.Point first = stroke.points.get(0);
        if (stroke.points.size() == 1) {
            float pressure = adjustedPressure(first.pressure, stroke.brush);
            paint.setColor(renderColor(stroke.color, stroke.brush, pressure));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(first.x * canvas.getWidth(), first.y * canvas.getHeight(),
                    dp(stroke.width) * widthFactor(stroke.brush, pressure) / 2f, paint);
            return;
        }
        InkDocument.Point previous = first;
        for (int index = 1; index < stroke.points.size(); index++) {
            InkDocument.Point point = stroke.points.get(index);
            float pressure = adjustedPressure((previous.pressure + point.pressure) / 2f, stroke.brush);
            paint.setColor(renderColor(stroke.color, stroke.brush, pressure));
            paint.setStrokeWidth(dp(stroke.width) * widthFactor(stroke.brush, pressure));
            canvas.drawLine(previous.x * canvas.getWidth(), previous.y * canvas.getHeight(),
                    point.x * canvas.getWidth(), point.y * canvas.getHeight(), paint);
            previous = point;
        }
    }

    private float adjustedPressure(float pressure, InkDocument.Brush brush) {
        float normalized = pressure <= 0f ? 0.5f : pressure;
        if (brush == InkDocument.Brush.PENCIL) return 0.2f + normalized * 0.8f;
        if (brush == InkDocument.Brush.MARKER) return 0.55f + normalized * 0.45f;
        return normalized;
    }

    private float widthFactor(InkDocument.Brush brush, float pressure) {
        if (brush == InkDocument.Brush.PENCIL) return 0.45f + pressure * 0.65f;
        if (brush == InkDocument.Brush.MARKER) return 1.6f + pressure * 0.45f;
        return 0.5f + pressure * 0.8f;
    }

    private int renderColor(int color, InkDocument.Brush brush, float pressure) {
        float strength;
        if (brush == InkDocument.Brush.PENCIL) strength = 0.38f + pressure * 0.48f;
        else if (brush == InkDocument.Brush.MARKER) strength = 0.58f + pressure * 0.3f;
        else strength = 0.82f + pressure * 0.18f;
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.rgb(
                Math.round(255 - (255 - red) * strength),
                Math.round(255 - (255 - green) * strength),
                Math.round(255 - (255 - blue) * strength));
    }

    private void selectTool(Tool selected) {
        tool = selected;
        applyNativeBrush();
        updateRawDrawingEnabled();
        updateControls();
    }

    private void selectWidth(float width) {
        strokeWidth = width;
        if (tool == Tool.ERASER) tool = Tool.PEN;
        applyNativeBrush();
        updateRawDrawingEnabled();
        updateControls();
    }

    private void selectColor(int color) {
        strokeColor = color;
        if (tool == Tool.ERASER) tool = Tool.PEN;
        applyNativeBrush();
        updateRawDrawingEnabled();
        updateControls();
    }

    private void undo() {
        InkDocument previous = undo.pollLast();
        if (previous == null) return;
        redo.addLast(document.copy());
        document.replaceWith(previous);
        afterDocumentChange();
    }

    private void redo() {
        InkDocument next = redo.pollLast();
        if (next == null) return;
        undo.addLast(document.copy());
        document.replaceWith(next);
        afterDocumentChange();
    }

    private void pushHistory() {
        undo.addLast(document.copy());
        while (undo.size() > MAX_HISTORY) undo.pollFirst();
        redo.clear();
    }

    private void performLayerOperation(Runnable operation) {
        pushHistory();
        InkDocument before = undo.peekLast();
        operation.run();
        if (documentsEquivalent(before, document)) undo.pollLast();
        else afterDocumentChange();
    }

    private boolean documentsEquivalent(InkDocument first, InkDocument second) {
        try {
            return first.toJson().toString().equals(second.toJson().toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void afterDocumentChange() {
        renderDocument();
        projectStore.save(document);
        updateRawDrawingEnabled();
        updateControls();
    }

    private void showLayersDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(4));
        InkDocument.Layer selected = document.selectedLayer();
        for (int index = document.layers().size() - 1; index >= 0; index--) {
            InkDocument.Layer layer = document.layers().get(index);
            String prefix = layer.id.equals(selected.id) ? "▶ " : "   ";
            String eye = layer.visible ? "● " : "○ ";
            Button row = addToolButton(content, prefix + eye + layer.name + "  ·  " + layer.strokes.size(), () -> {
                document.selectLayer(layer.id);
                projectStore.save(document);
                updateRawDrawingEnabled();
                updateControls();
            });
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            styleSelected(row, layer.id.equals(selected.id));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Layers · top first")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .setNeutralButton("Add", null)
                .setNegativeButton("Edit", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                performLayerOperation(document::addLayer);
                dialog.dismiss();
                showLayersDialog();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                dialog.dismiss();
                showLayerActions();
            });
        });
        dialog.show();
    }

    private void showLayerActions() {
        InkDocument.Layer layer = document.selectedLayer();
        String visibility = layer.visible ? "Hide" : "Show";
        String[] actions = {"Rename", "Move up", "Move down", visibility, "Clear", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(layer.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showRenameDialog();
                    else if (which == 1) performLayerOperation(() -> document.moveSelectedLayer(1));
                    else if (which == 2) performLayerOperation(() -> document.moveSelectedLayer(-1));
                    else if (which == 3) performLayerOperation(document::toggleSelectedLayerVisibility);
                    else if (which == 4) confirmLayerClear();
                    else confirmLayerDelete();
                })
                .setNegativeButton("Back", (dialog, which) -> showLayersDialog())
                .show();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(document.selectedLayer().name);
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Rename layer")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", (dialog, which) ->
                        performLayerOperation(() -> document.renameSelectedLayer(input.getText().toString())))
                .show();
    }

    private void confirmLayerClear() {
        if (document.selectedLayerIsEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Clear " + document.selectedLayer().name + "?")
                .setMessage("Only this layer will be cleared. You can undo afterward.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> performLayerOperation(document::clearSelectedLayer))
                .show();
    }

    private void confirmLayerDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + document.selectedLayer().name + "?")
                .setMessage(document.layers().size() == 1
                        ? "The only layer will be cleared. You can undo afterward."
                        : "The layer and its marks will be removed. You can undo afterward.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> performLayerOperation(document::deleteSelectedLayer))
                .show();
    }

    private void updateControls() {
        Tool[] tools = {Tool.PEN, Tool.PENCIL, Tool.MARKER};
        for (int index = 0; index < brushButtons.size(); index++) styleSelected(brushButtons.get(index), tool == tools[index]);
        styleSelected(eraserButton, tool == Tool.ERASER);
        for (int index = 0; index < widthButtons.size(); index++) styleSelected(widthButtons.get(index), strokeWidth == WIDTHS[index]);
        for (int index = 0; index < colorButtons.size(); index++) styleColor(colorButtons.get(index), COLORS[index], strokeColor == COLORS[index]);
        undoButton.setEnabled(!undo.isEmpty());
        redoButton.setEnabled(!redo.isEmpty());
        styleSelected(undoButton, false);
        styleSelected(redoButton, false);
        styleSelected(layersButton, false);
        updateStatus();
    }

    private void updateStatus() {
        if (status == null || document == null) return;
        InkDocument.Layer layer = document.selectedLayer();
        status.setText(layer.name + (layer.visible ? " · saved locally" : " · hidden"));
    }

    private Button addToolButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));
        return button;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(0xFFBBBBBB);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(30));
        params.setMargins(dp(6), 0, dp(6), 0);
        parent.addView(divider, params);
    }

    private void styleSelected(Button button, boolean selected) {
        if (button == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(7));
        background.setColor(selected ? Color.BLACK : 0xFFF3F3F3);
        background.setStroke(dp(1), selected ? Color.BLACK : 0xFFCCCCCC);
        button.setTextColor(selected ? Color.WHITE : Color.BLACK);
        button.setBackground(background);
        button.setAlpha(button.isEnabled() ? 1f : 0.35f);
    }

    private void styleColor(Button button, int color, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(7));
        background.setColor(color);
        background.setStroke(dp(selected ? 3 : 1), selected ? Color.BLACK : 0xFF777777);
        button.setTextColor(Color.luminance(color) < 0.5f ? Color.WHITE : Color.BLACK);
        button.setBackground(background);
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

    private InkDocument.Brush selectedBrush() {
        if (tool == Tool.PENCIL) return InkDocument.Brush.PENCIL;
        if (tool == Tool.MARKER) return InkDocument.Brush.MARKER;
        return InkDocument.Brush.PEN;
    }

    private float nativeStrokeWidth() {
        float factor = tool == Tool.PENCIL ? 0.8f : tool == Tool.MARKER ? 1.8f : 1f;
        return dp(strokeWidth) * factor;
    }

    private void applyNativeBrush() {
        if (touchHelper == null) return;
        touchHelper.setStrokeWidth(nativeStrokeWidth());
        try {
            Method method = touchHelper.getClass().getMethod("setStrokeColor", int.class);
            method.invoke(touchHelper, strokeColor);
        } catch (ReflectiveOperationException ignored) {
            // Some BOOX SDK/firmware combinations render live raw ink in black only.
        }
    }

    private void updateRawDrawingEnabled() {
        if (touchHelper != null) {
            boolean drawingTool = tool != Tool.ERASER;
            touchHelper.setRawDrawingEnabled(resumed && drawingTool && document.selectedLayer().visible);
        }
    }

    private float pressureOf(TouchPoint point) {
        try {
            Method method = point.getClass().getMethod("getPressure");
            Object value = method.invoke(point);
            if (value instanceof Number) {
                float pressure = ((Number) value).floatValue();
                if (pressure > 1f) pressure /= 4096f;
                return Math.max(0f, Math.min(1f, pressure));
            }
        } catch (ReflectiveOperationException ignored) {
            // Older firmware does not expose pressure through the same accessor.
        }
        return 0.5f;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        enterImmersiveMode();
        if (touchHelper != null) updateRawDrawingEnabled();
        else if (surface != null) surface.post(this::initializeRawInkIfReady);
    }

    @Override protected void onPause() {
        resumed = false;
        if (touchHelper != null) touchHelper.setRawDrawingEnabled(false);
        projectStore.save(document);
        super.onPause();
    }

    @Override protected void onDestroy() {
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
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
