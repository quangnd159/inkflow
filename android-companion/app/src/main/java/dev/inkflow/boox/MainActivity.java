package dev.inkflow.boox;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class MainActivity extends Activity {
    private static final String TAG = "InkflowBooxLab";
    private InkSurface inkSurface;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enterImmersiveMode();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        inkSurface = new InkSurface(this);
        root.addView(inkSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        status = new TextView(this);
        status.setText("Starting BOOX raw ink…");
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

        inkSurface.post(this::startRawInk);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (inkSurface != null && inkSurface.getTouchHelper() != null) {
            invokeOptional(inkSurface.getTouchHelper(), "setRawDrawingEnabled", new Class<?>[]{boolean.class}, true);
        }
    }

    @Override
    protected void onPause() {
        if (inkSurface != null && inkSurface.getTouchHelper() != null) {
            invokeOptional(inkSurface.getTouchHelper(), "setRawDrawingEnabled", new Class<?>[]{boolean.class}, false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (inkSurface != null && inkSurface.getTouchHelper() != null) {
            invokeOptional(inkSurface.getTouchHelper(), "closeRawDrawing", new Class<?>[0]);
        }
        super.onDestroy();
    }

    private void startRawInk() {
        try {
            Class<?> callbackClass = Class.forName("com.onyx.android.sdk.pen.RawInputCallback");
            Object callback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("onBeginRawDrawing")) {
                            runOnUiThread(() -> setStatus("Raw ink active — write anywhere"));
                        } else if (name.equals("onEndRawDrawing")) {
                            runOnUiThread(() -> setStatus("Raw ink ready"));
                        }
                        return primitiveDefault(method.getReturnType());
                    }
            );

            Class<?> helperClass = Class.forName("com.onyx.android.sdk.pen.TouchHelper");
            Method create = helperClass.getMethod("create", View.class, callbackClass);
            Object helper = create.invoke(null, inkSurface, callback);
            inkSurface.setTouchHelper(helper);

            invokeOptional(helper, "setStrokeWidth", new Class<?>[]{float.class}, 3.0f);
            invokeRequired(helper, "openRawDrawing", new Class<?>[0]);
            invokeRequired(helper, "setRawDrawingEnabled", new Class<?>[]{boolean.class}, true);
            setStatus("Raw ink ready");
            Log.i(TAG, "Onyx raw drawing initialized");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to initialize Onyx raw drawing", error);
            setStatus("Raw ink unavailable: " + concise(error));
        }
    }

    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return 0d;
    }

    private static void invokeRequired(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        target.getClass().getMethod(name, types).invoke(target, args);
    }

    private static void invokeOptional(Object target, String name, Class<?>[] types, Object... args) {
        try {
            target.getClass().getMethod(name, types).invoke(target, args);
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "Optional Onyx API unavailable: " + name, error);
        }
    }

    private static String concise(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : " — " + message);
    }

    private static final class InkSurface extends SurfaceView {
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Object touchHelper;

        InkSurface(Activity activity) {
            super(activity);
            setWillNotDraw(false);
            setBackgroundColor(Color.WHITE);
            dotPaint.setColor(0xFFD7D7D7);
            dotPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.WHITE);
            float spacing = getResources().getDisplayMetrics().density * 28f;
            float radius = Math.max(1f, getResources().getDisplayMetrics().density);
            for (float y = spacing; y < getHeight(); y += spacing) {
                for (float x = spacing; x < getWidth(); x += spacing) {
                    canvas.drawCircle(x, y, radius, dotPaint);
                }
            }
        }

        Object getTouchHelper() {
            return touchHelper;
        }

        void setTouchHelper(Object helper) {
            touchHelper = helper;
        }
    }
}
