package dev.einkstudio.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class InkDocument {
    static final int FORMAT_VERSION = 2;
    static final int LEGACY_FORMAT_VERSION = 1;

    enum Brush {
        PEN, PENCIL, MARKER;

        static Brush from(String value) {
            try {
                return value == null ? PEN : valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return PEN;
            }
        }
    }

    static final class Point {
        final float x;
        final float y;
        final float pressure;
        final long time;

        Point(float x, float y, float pressure, long time) {
            this.x = clamp01(x);
            this.y = clamp01(y);
            this.pressure = clamp01(pressure);
            this.time = time;
        }
    }

    static final class Stroke {
        final String id;
        final Brush brush;
        final float width;
        final int color;
        final List<Point> points;

        Stroke(Brush brush, float width, int color, List<Point> points) {
            this(UUID.randomUUID().toString(), brush, width, color, points);
        }

        Stroke(String id, Brush brush, float width, int color, List<Point> points) {
            this.id = id;
            this.brush = brush;
            this.width = width;
            this.color = color;
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
        }

        Stroke withPoints(List<Point> replacement) {
            return new Stroke(UUID.randomUUID().toString(), brush, width, color, replacement);
        }
    }

    static final class Layer {
        final String id;
        String name;
        boolean visible;
        final List<Stroke> strokes = new ArrayList<>();

        Layer(String id, String name, boolean visible) {
            this.id = id;
            this.name = name;
            this.visible = visible;
        }

        Layer copy() {
            Layer copy = new Layer(id, name, visible);
            copy.strokes.addAll(strokes);
            return copy;
        }
    }

    private final List<Layer> layers = new ArrayList<>();
    private String selectedLayerId;
    private long updatedAt = System.currentTimeMillis();

    InkDocument() {
        Layer layer = new Layer(UUID.randomUUID().toString(), "Layer 1", true);
        layers.add(layer);
        selectedLayerId = layer.id;
    }

    List<Layer> layers() {
        return Collections.unmodifiableList(layers);
    }

    Layer selectedLayer() {
        for (Layer layer : layers) if (layer.id.equals(selectedLayerId)) return layer;
        return layers.get(layers.size() - 1);
    }

    boolean isEmpty() {
        for (Layer layer : layers) if (!layer.strokes.isEmpty()) return false;
        return true;
    }

    boolean selectedLayerIsEmpty() {
        return selectedLayer().strokes.isEmpty();
    }

    void addStroke(Stroke stroke) {
        if (stroke.points.isEmpty()) return;
        selectedLayer().strokes.add(stroke);
        touch();
    }

    Layer addLayer() {
        Layer layer = new Layer(UUID.randomUUID().toString(), nextLayerName(), true);
        layers.add(layer);
        selectedLayerId = layer.id;
        touch();
        return layer;
    }

    boolean selectLayer(String id) {
        for (Layer layer : layers) {
            if (layer.id.equals(id)) {
                selectedLayerId = id;
                return true;
            }
        }
        return false;
    }

    void renameSelectedLayer(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || selectedLayer().name.equals(trimmed)) return;
        selectedLayer().name = trimmed;
        touch();
    }

    void toggleSelectedLayerVisibility() {
        selectedLayer().visible = !selectedLayer().visible;
        touch();
    }

    boolean moveSelectedLayer(int delta) {
        int current = indexOfSelectedLayer();
        int destination = Math.max(0, Math.min(layers.size() - 1, current + delta));
        if (current == destination) return false;
        Layer layer = layers.remove(current);
        layers.add(destination, layer);
        touch();
        return true;
    }

    boolean clearSelectedLayer() {
        Layer layer = selectedLayer();
        if (layer.strokes.isEmpty()) return false;
        layer.strokes.clear();
        touch();
        return true;
    }

    boolean deleteSelectedLayer() {
        if (layers.size() == 1) return clearSelectedLayer();
        int index = indexOfSelectedLayer();
        layers.remove(index);
        selectedLayerId = layers.get(Math.min(index, layers.size() - 1)).id;
        touch();
        return true;
    }

    boolean eraseAt(float normalizedX, float normalizedY, float radiusPixels, int width, int height) {
        Layer layer = selectedLayer();
        List<Stroke> replacement = new ArrayList<>();
        boolean changed = false;
        for (Stroke stroke : layer.strokes) {
            List<List<Point>> fragments = eraseStroke(stroke, normalizedX, normalizedY, radiusPixels, width, height);
            if (fragments.size() == 1 && fragments.get(0) == stroke.points) {
                replacement.add(stroke);
                continue;
            }
            changed = true;
            for (List<Point> fragment : fragments) {
                if (!fragment.isEmpty()) replacement.add(stroke.withPoints(fragment));
            }
        }
        if (changed) {
            layer.strokes.clear();
            layer.strokes.addAll(replacement);
            touch();
        }
        return changed;
    }

    InkDocument copy() {
        InkDocument copy = new InkDocument();
        copy.layers.clear();
        for (Layer layer : layers) copy.layers.add(layer.copy());
        copy.selectedLayerId = selectedLayerId;
        copy.updatedAt = updatedAt;
        return copy;
    }

    void replaceWith(InkDocument replacement) {
        layers.clear();
        for (Layer layer : replacement.layers) layers.add(layer.copy());
        selectedLayerId = replacement.selectedLayerId;
        touch();
    }

    JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", FORMAT_VERSION);
        root.put("updatedAt", updatedAt);
        root.put("selectedLayerId", selectedLayerId);
        JSONArray layerArray = new JSONArray();
        for (Layer layer : layers) {
            JSONObject layerJson = new JSONObject();
            layerJson.put("id", layer.id);
            layerJson.put("name", layer.name);
            layerJson.put("visible", layer.visible);
            JSONArray strokeArray = new JSONArray();
            for (Stroke stroke : layer.strokes) strokeArray.put(strokeToJson(stroke));
            layerJson.put("strokes", strokeArray);
            layerArray.put(layerJson);
        }
        root.put("layers", layerArray);
        return root;
    }

    static InkDocument fromJson(JSONObject root) throws JSONException {
        int version = root.optInt("version", -1);
        if (version == LEGACY_FORMAT_VERSION) return migrateV1(root);
        if (version != FORMAT_VERSION) throw new JSONException("Unsupported document version " + version);

        InkDocument document = new InkDocument();
        document.layers.clear();
        JSONArray layers = root.getJSONArray("layers");
        for (int index = 0; index < layers.length(); index++) {
            JSONObject layerJson = layers.getJSONObject(index);
            Layer layer = new Layer(
                    layerJson.optString("id", UUID.randomUUID().toString()),
                    layerJson.optString("name", "Layer " + (index + 1)),
                    layerJson.optBoolean("visible", true)
            );
            readStrokes(layerJson.optJSONArray("strokes"), layer.strokes);
            document.layers.add(layer);
        }
        if (document.layers.isEmpty()) document.layers.add(new Layer(UUID.randomUUID().toString(), "Layer 1", true));
        document.selectedLayerId = root.optString("selectedLayerId", document.layers.get(document.layers.size() - 1).id);
        if (document.indexOfSelectedLayer() < 0) document.selectedLayerId = document.layers.get(document.layers.size() - 1).id;
        document.updatedAt = root.optLong("updatedAt", System.currentTimeMillis());
        return document;
    }

    private static InkDocument migrateV1(JSONObject root) throws JSONException {
        InkDocument document = new InkDocument();
        document.layers.get(0).name = "Imported canvas";
        readStrokes(root.optJSONArray("strokes"), document.layers.get(0).strokes);
        document.updatedAt = root.optLong("updatedAt", System.currentTimeMillis());
        return document;
    }

    private static JSONObject strokeToJson(Stroke stroke) throws JSONException {
        JSONObject strokeJson = new JSONObject();
        strokeJson.put("id", stroke.id);
        strokeJson.put("brush", stroke.brush.name().toLowerCase());
        strokeJson.put("width", stroke.width);
        strokeJson.put("color", stroke.color);
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
        return strokeJson;
    }

    private static void readStrokes(JSONArray strokes, List<Stroke> destination) throws JSONException {
        if (strokes == null) return;
        for (int index = 0; index < strokes.length(); index++) {
            JSONObject strokeJson = strokes.getJSONObject(index);
            JSONArray pointArray = strokeJson.optJSONArray("points");
            if (pointArray == null) continue;
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
            if (!points.isEmpty()) {
                destination.add(new Stroke(
                        strokeJson.optString("id", UUID.randomUUID().toString()),
                        Brush.from(strokeJson.optString("brush", "pen")),
                        (float) strokeJson.optDouble("width", 5f),
                        strokeJson.optInt("color", 0xFF000000),
                        points
                ));
            }
        }
    }

    private static List<List<Point>> eraseStroke(Stroke stroke, float x, float y, float radius, int width, int height) {
        float targetX = x * width;
        float targetY = y * height;
        float radiusSquared = radius * radius;
        boolean hit = false;
        for (int index = 0; index < stroke.points.size(); index++) {
            Point point = stroke.points.get(index);
            if (distanceSquared(point.x * width, point.y * height, targetX, targetY) <= radiusSquared) {
                hit = true;
                break;
            }
            if (index > 0) {
                Point previous = stroke.points.get(index - 1);
                if (segmentDistanceSquared(previous.x * width, previous.y * height,
                        point.x * width, point.y * height, targetX, targetY) <= radiusSquared) {
                    hit = true;
                    break;
                }
            }
        }
        if (!hit) return Collections.singletonList(stroke.points);

        List<List<Point>> fragments = new ArrayList<>();
        List<Point> current = new ArrayList<>();
        Point previous = null;
        boolean previousInside = false;
        for (Point point : stroke.points) {
            boolean inside = distanceSquared(point.x * width, point.y * height, targetX, targetY) <= radiusSquared;
            if (previous != null && !previousInside && !inside && segmentDistanceSquared(
                    previous.x * width, previous.y * height, point.x * width, point.y * height,
                    targetX, targetY) <= radiusSquared) {
                if (!current.isEmpty()) fragments.add(current);
                current = new ArrayList<>();
            }
            if (inside) {
                if (!current.isEmpty()) fragments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(point);
            }
            previous = point;
            previousInside = inside;
        }
        if (!current.isEmpty()) fragments.add(current);
        return fragments;
    }

    private int indexOfSelectedLayer() {
        for (int index = 0; index < layers.size(); index++) {
            if (layers.get(index).id.equals(selectedLayerId)) return index;
        }
        return -1;
    }

    private String nextLayerName() {
        int number = layers.size() + 1;
        while (true) {
            String candidate = "Layer " + number;
            boolean exists = false;
            for (Layer layer : layers) if (layer.name.equals(candidate)) exists = true;
            if (!exists) return candidate;
            number++;
        }
    }

    private void touch() {
        updatedAt = System.currentTimeMillis();
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
