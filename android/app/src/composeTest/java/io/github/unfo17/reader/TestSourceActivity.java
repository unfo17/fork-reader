package io.github.unfo17.reader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Produces VIEW grants from the test provider's UID; no production APK code is needed here. */
public class TestSourceActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String id = getIntent().getStringExtra("document");
            String operation = getIntent().getStringExtra("operation");
            Uri uri = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, id);
            if ("revoke".equals(operation)) { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); return; }
            if (!"regrant".equals(operation)) {
                File file = TestDocumentsProvider.file(this, id); file.getParentFile().mkdirs();
                try (FileOutputStream output = new FileOutputStream(file)) {
                    byte[] bytes = getIntent().getByteArrayExtra("bytes");
                    if (bytes == null) bytes = getIntent().getStringExtra("text").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    output.write(bytes);
                }
                getSharedPreferences("test-documents", 0).edit().putString(id + ".name", getIntent().getStringExtra("name")).putString(id + ".mime", getIntent().getStringExtra("mime")).putBoolean(id + ".delete", getIntent().getBooleanExtra("deletable", false)).commit();
            }
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (getIntent().getBooleanExtra("persistable", false)) flags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            if (getIntent().getBooleanExtra("writable", false)) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            Intent open = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(getIntent().getStringExtra("target"), getIntent().getStringExtra("target") + ".MainActivity")).setDataAndType(uri, getIntent().getStringExtra("mime")).addFlags(flags);
            open.setClipData(ClipData.newRawUri("Test book", uri));
            startActivity(open);
        } catch (IOException error) { throw new IllegalStateException(error); }
        finally { finish(); }
    }
}
