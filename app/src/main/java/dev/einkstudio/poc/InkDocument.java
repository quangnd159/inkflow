package dev.einkstudio.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class InkDocument {
    static final int FORMAT_VERSION = 1;

    static final class Point {
        final float x;
        final float y;
        final float pressure;
        final long time;

        Point(float x, float y, float pressure, long time) {
            this.x = clamp01(x);
            this.y = clamp01(y);
            this.pressure = Math.max(0f, Math.min(1f, pressure));
            this.time = time;
        }
    }

    static final class Stroke {
        final String id;
        final float width;
        final List<Point> points;

        Stroke(float width, List<Point> points) {
            this(UUID.randomUUID().toString(), width, points);
        }

        Stroke(String id, float width, List<Point> points) {
            this.id = id;
            this.width = width;
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private long updatedAt = System.currentTimeMillis();

    List<Stroke> strokes() {
        return Collections.unmodifiableList(strokes);
    }

    boolean isEmpty() {
        return strokes.isEmpty();
    }

    void add(Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        strokes.add(stroke);
        touch();
    }

    void clear() {
        if (strokes.isEmpty()) return;
        strokes.clear();
        touch();
    }

    void replaceWith(List<Stroke> replacement) {
        strokes.clear();
        strokes.addAll(replacement);
        touch();
    }

    List<Stroke> snapshot() {
        return new ArrayList<>(strokes);
    }

    boolean eraseAt(float normalizedX, float normalizedY, float radiusPixels, int width, int height) {
        float radiusSquared = radiusPixels * radiusPixels;
        boolean changed = strokes.removeIf(stroke -> strokeHits(stroke, normalizedX, normalizedY, radiusSquared, width, height));
        if (changed) touch();
        return changed;
    }

    InkDocument copy() {
        InkDocument copy = new InkDocument();
        copy.strokes.addAll(strokes);
        copy.updatedAt = updatedAt;
        return copy;
    }

    JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", FORMAT_VERSION);
        root.put("updatedAt", updatedAt);
        JSONArray strokeArray = new JSONArray();
        for (Stroke stroke : strokes) {
            JSONObject strokeJson = new JSONObject();
            strokeJson.put("id", stroke.id);
            strokeJson.put("width", stroke.width);
            JSONArray points = new JSONArray();
            for (Point point : stroke.points) {
                JSONArray tuple = new JSONArray();
                tuple.put(point.x);
                tuple.put(point.y);
                tuple.put(point.pressure);
                tuple.put(point.time);
                points.put(tuple);
            }
            strokeJson.put("points", points);
            strokeArray.put(strokeJson);
        }
        root.put("strokes", strokeArray);
        return root;
    }

    static InkDocument fromJson(JSONObject root) throws JSONException {
        if (root.optInt("version", -1) != FORMAT_VERSION) {
            throw new JSONException("Unsupported document version");
        }
        InkDocument document = new InkDocument();
        document.updatedAt = root.optLong("updatedAt", System.currentTimeMillis());
        JSONArray strokes = root.getJSONArray("strokes");
        for (int index = 0; index < strokes.length(); index++) {
            JSONObject strokeJson = strokes.getJSONObject(index);
            JSONArray pointArray = strokeJson.getJSONArray("points");
            List<Point> points = new ArrayList<>(pointArray.length());
            for (int pointIndex = 0; pointIndex < pointArray.length(); pointIndex++) {
                JSONArray tuple = pointArray.getJSONArray(pointIndex);
                points.add(new Point(
                        (float) tuple.getDouble(0),
                        (float) tuple.getDouble(1),
                        (float) tuple.optDouble(2, 0.5),
                        tuple.optLong(3, 0L)
                ));
            }
            document.strokes.add(new Stroke(
                    strokeJson.optString("id", UUID.randomUUID().toString()),
                    (float) strokeJson.optDouble("width", 5f),
                    points
            ));
        }
        return document;
    }

    private void touch() {
        updatedAt = System.currentTimeMillis();
    }

    private static boolean strokeHits(Stroke stroke, float x, float y, float radiusSquared, int width, int height) {
        Point previous = null;
        for (Point point : stroke.points) {
            float px = point.x * width;
            float py = point.y * height;
            float targetX = x * width;
            float targetY = y * height;
            if (previous == null) {
                if (distanceSquared(px, py, targetX, targetY) <= radiusSquared) return true;
            } else if (segmentDistanceSquared(
                    previous.x * width,
                    previous.y * height,
                    px,
                    py,
                    targetX,
                    targetY
            ) <= radiusSquared) {
                return true;
            }
            previous = point;
        }
        return false;
    }

    private static float segmentDistanceSquared(float ax, float ay, float bx, float by, float px, float py) {
        float dx = bx - ax;
        float dy = by - ay;
        if (dx == 0f && dy == 0f) return distanceSquared(ax, ay, px, py);
        float t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0f, Math.min(1f, t));
        return distanceSquared(ax + t * dx, ay + t * dy, px, py);
    }

    private static float distanceSquared(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
