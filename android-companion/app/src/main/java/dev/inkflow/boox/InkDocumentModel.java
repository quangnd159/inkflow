package dev.inkflow.boox;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class InkDocumentModel {
    static final int DOCUMENT_VERSION = 1;
    static final int DEFAULT_WIDTH = 1400;
    static final int DEFAULT_HEIGHT = 1800;

    final int width;
    final int height;
    final String createdAt;
    String updatedAt;
    final ArrayList<Stroke> strokes;

    private InkDocumentModel(int width, int height, String createdAt, String updatedAt, ArrayList<Stroke> strokes) {
        this.width = width;
        this.height = height;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.strokes = strokes;
    }

    static InkDocumentModel empty() {
        String now = isoNow();
        return new InkDocumentModel(DEFAULT_WIDTH, DEFAULT_HEIGHT, now, now, new ArrayList<>());
    }

    static InkDocumentModel parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("version", -1) != DOCUMENT_VERSION) throw new JSONException("Unsupported Inkflow document");
        ArrayList<Stroke> strokes = new ArrayList<>();
        JSONArray sourceStrokes = root.getJSONArray("strokes");
        for (int index = 0; index < sourceStrokes.length(); index++) {
            JSONObject sourceStroke = sourceStrokes.getJSONObject(index);
            ArrayList<Point> points = new ArrayList<>();
            JSONArray sourcePoints = sourceStroke.getJSONArray("points");
            for (int pointIndex = 0; pointIndex < sourcePoints.length(); pointIndex++) {
                JSONObject point = sourcePoints.getJSONObject(pointIndex);
                points.add(new Point(
                        (float) point.getDouble("x"),
                        (float) point.getDouble("y"),
                        (float) point.optDouble("pressure", 0.5),
                        point.optLong("time", 0L)
                ));
            }
            strokes.add(new Stroke(
                    sourceStroke.optString("id", UUID.randomUUID().toString()),
                    (float) sourceStroke.optDouble("width", 5.0),
                    points
            ));
        }
        return new InkDocumentModel(
                root.optInt("width", DEFAULT_WIDTH),
                root.optInt("height", DEFAULT_HEIGHT),
                root.optString("createdAt", isoNow()),
                root.optString("updatedAt", isoNow()),
                strokes
        );
    }

    String toJson() throws JSONException {
        updatedAt = isoNow();
        JSONObject root = new JSONObject();
        root.put("version", DOCUMENT_VERSION);
        root.put("width", width);
        root.put("height", height);
        root.put("pageStyle", "dots");
        root.put("createdAt", createdAt);
        root.put("updatedAt", updatedAt);
        JSONArray outputStrokes = new JSONArray();
        for (Stroke stroke : strokes) {
            JSONObject outputStroke = new JSONObject();
            outputStroke.put("id", stroke.id);
            outputStroke.put("color", "auto");
            outputStroke.put("width", stroke.width);
            JSONArray outputPoints = new JSONArray();
            for (Point point : stroke.points) {
                JSONObject outputPoint = new JSONObject();
                outputPoint.put("x", point.x);
                outputPoint.put("y", point.y);
                outputPoint.put("pressure", point.pressure);
                outputPoint.put("time", point.time);
                outputPoints.put(outputPoint);
            }
            outputStroke.put("points", outputPoints);
            outputStrokes.put(outputStroke);
        }
        root.put("strokes", outputStrokes);
        return root.toString() + "\n";
    }

    InkDocumentModel copy() {
        ArrayList<Stroke> copied = new ArrayList<>();
        for (Stroke stroke : strokes) copied.add(stroke.copy());
        return new InkDocumentModel(width, height, createdAt, updatedAt, copied);
    }

    Bitmap renderBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        draw(canvas, 1f, 0f, 0f);
        return bitmap;
    }

    void draw(Canvas canvas, float scale, float offsetX, float offsetY) {
        canvas.drawColor(Color.WHITE);
        Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
        dots.setColor(0xFFDDE1E5);
        for (float y = 48; y < height; y += 48) {
            for (float x = 48; x < width; x += 48) {
                canvas.drawCircle(offsetX + x * scale, offsetY + y * scale, Math.max(1f, 1.7f * scale), dots);
            }
        }
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(Color.BLACK);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        for (Stroke stroke : strokes) {
            if (stroke.points.isEmpty()) continue;
            Point first = stroke.points.get(0);
            ink.setStyle(Paint.Style.FILL);
            canvas.drawCircle(offsetX + first.x * scale, offsetY + first.y * scale,
                    pressureWidth(stroke.width, first.pressure) * scale / 2f, ink);
            ink.setStyle(Paint.Style.STROKE);
            for (int index = 1; index < stroke.points.size(); index++) {
                Point start = stroke.points.get(index - 1);
                Point end = stroke.points.get(index);
                ink.setStrokeWidth(pressureWidth(stroke.width, (start.pressure + end.pressure) / 2f) * scale);
                canvas.drawLine(
                        offsetX + start.x * scale,
                        offsetY + start.y * scale,
                        offsetX + end.x * scale,
                        offsetY + end.y * scale,
                        ink
                );
            }
        }
    }

    private static float pressureWidth(float width, float pressure) {
        return width * (0.45f + clamp(pressure, 0f, 1f) * 0.9f);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String isoNow() {
        return java.time.Instant.now().toString();
    }

    static final class Point {
        final float x;
        final float y;
        final float pressure;
        final long time;

        Point(float x, float y, float pressure, long time) {
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.time = time;
        }
    }

    static final class Stroke {
        final String id;
        final float width;
        final ArrayList<Point> points;

        Stroke(String id, float width, List<Point> points) {
            this.id = id;
            this.width = width;
            this.points = new ArrayList<>(points);
        }

        Stroke copy() {
            return new Stroke(id, width, points);
        }
    }
}
