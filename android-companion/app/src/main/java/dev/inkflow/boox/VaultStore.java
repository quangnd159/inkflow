package dev.inkflow.boox;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class VaultStore {
    private static final String PREFERENCES = "inkflow_boox";
    private static final String TREE_URI = "vault_tree_uri";
    private final Context context;
    private final SharedPreferences preferences;

    VaultStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    boolean isConnected() {
        return getTreeUri() != null;
    }

    void connect(Uri uri, int flags) {
        int persistable = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        context.getContentResolver().takePersistableUriPermission(uri, persistable);
        preferences.edit().putString(TREE_URI, uri.toString()).apply();
    }

    String readText(String relativePath) throws IOException {
        DocumentFile file = resolve(relativePath, false, null);
        if (file == null || !file.isFile()) throw new IOException("Ink source was not found in the selected vault");
        try (InputStream input = context.getContentResolver().openInputStream(file.getUri())) {
            if (input == null) throw new IOException("Unable to open ink source");
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append('\n');
            return content.toString();
        }
    }

    void writeText(String relativePath, String content) throws IOException {
        DocumentFile file = resolve(relativePath, true, "application/json");
        if (file == null) throw new IOException("Unable to create ink source");
        try (OutputStream output = context.getContentResolver().openOutputStream(file.getUri(), "wt")) {
            if (output == null) throw new IOException("Unable to write ink source");
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    void writePng(String relativePath, Bitmap bitmap) throws IOException {
        DocumentFile file = resolve(relativePath, true, "image/png");
        if (file == null) throw new IOException("Unable to create PNG snapshot");
        try (OutputStream output = context.getContentResolver().openOutputStream(file.getUri(), "wt")) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Unable to encode PNG snapshot");
            }
            output.flush();
        }
    }

    void delete(String relativePath) throws IOException {
        DocumentFile file = resolve(relativePath, false, null);
        if (file != null && file.exists() && !file.delete()) throw new IOException("Unable to delete " + relativePath);
    }

    private Uri getTreeUri() {
        String stored = preferences.getString(TREE_URI, null);
        if (stored == null) return null;
        try {
            return Uri.parse(stored);
        } catch (RuntimeException error) {
            preferences.edit().remove(TREE_URI).apply();
            return null;
        }
    }

    private DocumentFile resolve(String relativePath, boolean create, String mimeType) throws IOException {
        if (!isSafePath(relativePath)) throw new IOException("Unsafe vault path");
        Uri treeUri = getTreeUri();
        if (treeUri == null) throw new IOException("Choose the Obsidian vault folder first");
        DocumentFile current = DocumentFile.fromTreeUri(context, treeUri);
        if (current == null) throw new IOException("The selected vault is unavailable");
        String[] parts = relativePath.split("/");
        for (int index = 0; index < parts.length - 1; index++) {
            DocumentFile next = current.findFile(parts[index]);
            if (next == null && create) next = current.createDirectory(parts[index]);
            if (next == null || !next.isDirectory()) return null;
            current = next;
        }
        String name = parts[parts.length - 1];
        DocumentFile file = current.findFile(name);
        if (file == null && create) file = current.createFile(mimeType == null ? "application/octet-stream" : mimeType, name);
        return file;
    }

    private static boolean isSafePath(String path) {
        return path != null && !path.isEmpty() && !path.startsWith("/") && !path.contains("..") && !path.contains("\\");
    }
}
