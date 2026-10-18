package com.zip2apk.builder;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

public class MainActivity extends Activity {
    private static final int REQ_ZIP = 1;

    private EditText nameEt, pkgEt;
    private TextView fileTv, statusTv;
    private Button buildBtn, installBtn;
    private Uri zipUri;
    private File apk;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int p = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        TextView title = new TextView(this);
        title.setText("ZipToApk");
        title.setTextSize(26);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("เลือกไฟล์ .zip ของเว็บ (ต้องมี index.html) แล้วแปลงเป็น APK");
        hint.setPadding(0, dp(4), 0, dp(16));
        root.addView(hint);

        Button pick = new Button(this);
        pick.setText("1) เลือกไฟล์ .zip");
        pick.setOnClickListener(v -> pickZip());
        root.addView(pick);

        fileTv = new TextView(this);
        fileTv.setText("ยังไม่ได้เลือกไฟล์");
        fileTv.setPadding(0, dp(4), 0, dp(12));
        root.addView(fileTv);

        nameEt = new EditText(this);
        nameEt.setHint("ชื่อแอป");
        nameEt.setSingleLine(true);
        root.addView(nameEt);

        pkgEt = new EditText(this);
        pkgEt.setHint("Package name");
        pkgEt.setSingleLine(true);
        pkgEt.setText("com.zip2apk.app" + Long.toString(System.currentTimeMillis(), 36));
        root.addView(pkgEt);

        buildBtn = new Button(this);
        buildBtn.setText("2) สร้าง APK");
        buildBtn.setOnClickListener(v -> startBuild());
        root.addView(buildBtn);

        statusTv = new TextView(this);
        statusTv.setPadding(0, dp(12), 0, dp(12));
        root.addView(statusTv);

        installBtn = new Button(this);
        installBtn.setText("3) ติดตั้ง");
        installBtn.setVisibility(View.GONE);
        installBtn.setOnClickListener(v -> install());
        root.addView(installBtn);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(i, REQ_ZIP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ZIP && res == RESULT_OK && data != null && data.getData() != null) {
            zipUri = data.getData();
            String name = displayName(zipUri);
            fileTv.setText(name);
            if (nameEt.getText().length() == 0) {
                nameEt.setText(name.replaceAll("(?i)\\.zip$", ""));
            }
        }
    }

    private void startBuild() {
        if (zipUri == null) {
            Toast.makeText(this, "เลือกไฟล์ .zip ก่อน", Toast.LENGTH_SHORT).show();
            return;
        }
        String n = nameEt.getText().toString().trim();
        final String name = n.isEmpty() ? "WebApp" : n;
        final String pkg = pkgEt.getText().toString().trim();
        buildBtn.setEnabled(false);
        installBtn.setVisibility(View.GONE);
        statusTv.setText("กำลังสร้าง…");

        new Thread(() -> {
            try {
                File out = ApkBuilder.build(this, zipUri, name, pkg);
                ApkBuilder.saveToDownloads(this, out);
                runOnUiThread(() -> {
                    apk = out;
                    statusTv.setText("สร้างสำเร็จ — บันทึกไว้ที่ Downloads/" + out.getName());
                    installBtn.setVisibility(View.VISIBLE);
                    buildBtn.setEnabled(true);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    statusTv.setText("ผิดพลาด: " + t);
                    buildBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private void install() {
        if (apk == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "อนุญาต \"ติดตั้งแอปที่ไม่รู้จัก\" ให้ ZipToApk แล้วกดอีกครั้ง",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fp", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return "project.zip";
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
