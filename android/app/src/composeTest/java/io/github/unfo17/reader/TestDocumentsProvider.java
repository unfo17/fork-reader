package io.github.unfo17.reader;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;

/** Test APK only: independent UID, protected by the actual SAF permission. */
public class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "io.github.unfo17.reader.test.documents";
    public static File file(Context context, String id) {
        if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid test document");
        return new File(context.getFilesDir(), "test-documents/" + id);
    }
    @Override public boolean onCreate() { return true; }
    private Map<String, Object> values(String id) throws FileNotFoundException {
        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        if (id.equals("root")) {
            values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Test books");
            values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            values.put(DocumentsContract.Document.COLUMN_FLAGS, 0);
            return values;
        }
        File file = file(getContext(), id);
        if (!file.isFile()) throw new FileNotFoundException("Test document no longer exists");
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("test-documents", 0);
        values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, prefs.getString(id + ".name", id));
        values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, prefs.getString(id + ".mime", "text/plain"));
        values.put(DocumentsContract.Document.COLUMN_SIZE, file.length());
        values.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
        values.put(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_WRITE | (prefs.getBoolean(id + ".delete", false) ? DocumentsContract.Document.FLAG_SUPPORTS_DELETE : 0));
        return values;
    }
    private MatrixCursor documents(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS});
    }
    private void add(MatrixCursor cursor, Map<String, Object> values) {
        Object[] row = new Object[cursor.getColumnCount()];
        for (int i = 0; i < row.length; i++) row[i] = values.get(cursor.getColumnName(i));
        cursor.addRow(row);
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS});
        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Root.COLUMN_ROOT_ID, "root"); values.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root"); values.put(DocumentsContract.Root.COLUMN_TITLE, "Test books"); values.put(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY);
        add(cursor, values); return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException { MatrixCursor cursor = documents(projection); add(cursor, values(id)); return cursor; }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = documents(projection);
        if (parent.equals("root")) {
            File[] files = new File(getContext().getFilesDir(), "test-documents").listFiles();
            if (files != null) for (File file : files) if (file.isFile()) add(cursor, values(file.getName()));
        }
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException { return ParcelFileDescriptor.open(file(getContext(), id), ParcelFileDescriptor.parseMode(mode)); }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!getContext().getSharedPreferences("test-documents", 0).getBoolean(id + ".delete", false) || !file(getContext(), id).delete()) throw new FileNotFoundException("Test provider refuses deletion");
        getContext().revokeUriPermission(DocumentsContract.buildDocumentUri(AUTHORITY, id), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("root") && file(getContext(), id).isFile(); }
}
