package dev.einkstudio.poc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class InkDocumentTest {
    @Test
    public void segmentEraserSplitsInsteadOfDeletingStroke() {
        InkDocument document = new InkDocument();
        document.addStroke(new InkDocument.Stroke(
                InkDocument.Brush.PEN,
                5f,
                0xFF000000,
                Arrays.asList(point(0.1f, 0.5f), point(0.4f, 0.5f), point(0.6f, 0.5f), point(0.9f, 0.5f))
        ));

        assertTrue(document.eraseAt(0.5f, 0.5f, 30f, 1000, 1000));
        assertEquals(2, document.selectedLayer().strokes.size());
        assertEquals(2, document.selectedLayer().strokes.get(0).points.size());
        assertEquals(2, document.selectedLayer().strokes.get(1).points.size());
    }

    @Test
    public void layersCopyAndRestoreAsIndependentHistorySnapshots() {
        InkDocument document = new InkDocument();
        document.renameSelectedLayer("Sketch");
        InkDocument before = document.copy();

        document.addLayer();
        document.renameSelectedLayer("Ink");
        document.toggleSelectedLayerVisibility();
        assertEquals(2, document.layers().size());
        assertFalse(document.selectedLayer().visible);

        document.replaceWith(before);
        assertEquals(1, document.layers().size());
        assertEquals("Sketch", document.selectedLayer().name);
        assertTrue(document.selectedLayer().visible);
    }

    @Test
    public void legacyFlatCanvasMigratesToLayeredVersion() throws Exception {
        JSONObject legacy = new JSONObject();
        legacy.put("version", 1);
        JSONObject stroke = new JSONObject();
        stroke.put("width", 5);
        stroke.put("points", new JSONArray().put(new JSONArray().put(0.25).put(0.75).put(0.6).put(1)));
        legacy.put("strokes", new JSONArray().put(stroke));

        InkDocument migrated = InkDocument.fromJson(legacy);

        assertEquals("Imported canvas", migrated.selectedLayer().name);
        assertEquals(1, migrated.selectedLayer().strokes.size());
        assertEquals(InkDocument.Brush.PEN, migrated.selectedLayer().strokes.get(0).brush);
        assertEquals(InkDocument.FORMAT_VERSION, migrated.toJson().getInt("version"));
    }

    private static InkDocument.Point point(float x, float y) {
        return new InkDocument.Point(x, y, 0.5f, 1L);
    }
}
