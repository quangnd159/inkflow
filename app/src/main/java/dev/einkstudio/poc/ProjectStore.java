package dev.einkstudio.poc;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class ProjectStore {
    private static final String TAG = "EinkStudioStore";
    private static final String FILE_NAME = "canvas.json";
    private static final String BACKUP_NAME = "canvas.json.bak";
    private final File directory;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    ProjectStore(Context context) {
        directory = new File(context.getFilesDir(), "projects/default");
    }

    InkDocument load() {
        File file = new File(directory, FILE_NAME);
        InkDocument loaded = read(file);
        if (loaded != null) return loaded;
        loaded = read(new File(directory, BACKUP_NAME));
        if (loaded != null) {
            save(loaded);
            return loaded;
        }
        return new InkDocument();
    }

    private InkDocument read(File file) {
        if (!file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            JSONObject json = new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
            InkDocument document = InkDocument.fromJson(json);
            if (json.optInt("version", -1) == InkDocument.LEGACY_FORMAT_VERSION) save(document);
            return document;
        } catch (Exception error) {
            Log.e(TAG, "Unable to load " + file.getName(), error);
            return null;
        }
    }

    void save(InkDocument document) {
        InkDocument snapshot = document.copy();
        writer.execute(() -> writeSnapshot(snapshot));
    }

    void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Autosave writer did not finish before shutdown");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeSnapshot(InkDocument snapshot) {
        File temporary = new File(directory, FILE_NAME + ".new");
        File target = new File(directory, FILE_NAME);
        File backup = new File(directory, BACKUP_NAME);
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
            if (target.isFile()) {
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to save project", error);
        }
    }
}
