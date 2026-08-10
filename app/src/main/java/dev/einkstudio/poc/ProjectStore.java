package dev.einkstudio.poc;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ProjectStore {
    private static final String TAG = "EinkStudioStore";
    private static final String FILE_NAME = "canvas.json";
    private final File directory;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    ProjectStore(Context context) {
        directory = new File(context.getFilesDir(), "projects/default");
    }

    InkDocument load() {
        File file = new File(directory, FILE_NAME);
        if (!file.isFile()) return new InkDocument();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            return InkDocument.fromJson(new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8)));
        } catch (Exception error) {
            Log.e(TAG, "Unable to load project", error);
            return new InkDocument();
        }
    }

    void save(InkDocument document) {
        InkDocument snapshot = document.copy();
        writer.execute(() -> writeSnapshot(snapshot));
    }

    void close() {
        writer.shutdown();
    }

    private void writeSnapshot(InkDocument snapshot) {
        File temporary = new File(directory, FILE_NAME + ".tmp");
        File target = new File(directory, FILE_NAME);
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("Unable to create project folder");
            }
            byte[] bytes = (snapshot.toJson().toString() + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("Unable to replace project");
            }
            if (!temporary.renameTo(target)) {
                throw new IllegalStateException("Unable to commit project");
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to save project", error);
        }
    }
}
