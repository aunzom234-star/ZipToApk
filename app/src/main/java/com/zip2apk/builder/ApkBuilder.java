package com.zip2apk.builder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.android.apksig.ApkSigner;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Turns a zipped web project (with index.html) into a signed, installable APK. */
public final class ApkBuilder {
    private static final String TEMPLATE_PKG = "com.zip2apk.generated";
    private static final String TEMPLATE_LABEL = "ZIP2APK_APP_NAME";
    private static final String KEY_ALIAS = "zip2apk";
    private static final char[] KEY_PASS = "zip2apk".toCharArray();

    private ApkBuilder() { }

    public static File build(Context ctx, Uri zipUri, String appName, String pkg) throws Exception {
        if (!pkg.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")) {
            throw new IOException("Package name ไม่ถูกต้อง (เช่น com.example.myapp)");
        }

        File work = new File(ctx.getCacheDir(), "work");
        deleteRecursive(work);
        work.mkdirs();
        File tpl = new File(work, "template.apk");
        File input = new File(work, "input.zip");
        File unsigned = new File(work, "unsigned.apk");

        try (InputStream in = ctx.getAssets().open("template.apk")) {
            copy(in, tpl);
        } catch (IOException e) {
            throw new IOException("ไม่พบ template.apk ใน assets (ต้อง build ผ่าน workflow ก่อน)");
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(zipUri)) {
            if (in == null) throw new IOException("เปิดไฟล์ zip ไม่ได้");
            copy(in, input);
        }

        // ---- 1) assemble unsigned APK: template + patched manifest + user's files in assets/www/
        Map<String, String> repl = new HashMap<>();
        repl.put(TEMPLATE_PKG, pkg);
        repl.put(TEMPLATE_LABEL, appName);

        String root = findRoot(input);
        int files = 0;
        try (ZipFile tz = new ZipFile(tpl);
             ZipFile uz = new ZipFile(input);
             ZipOutputStream out = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(unsigned)))) {

            for (Enumeration<? extends ZipEntry> e = tz.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                String name = en.getName();
                if (en.isDirectory() || name.startsWith("META-INF/") || name.startsWith("assets/www/")) {
                    continue;
                }
                if (name.equals("AndroidManifest.xml")) {
                    byte[] data = AxmlPatcher.replaceStrings(readAll(tz.getInputStream(en)), repl);
                    out.putNextEntry(new ZipEntry(name));
                    out.write(data);
                    out.closeEntry();
                } else {
                    copyEntry(tz, en, out);
                }
            }

            Set<String> seen = new HashSet<>();
            for (Enumeration<? extends ZipEntry> e = uz.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                String n = clean(en.getName());
                if (n == null || en.isDirectory() || !n.startsWith(root)) continue;
                String rel = n.substring(root.length());
                if (rel.isEmpty() || rel.endsWith(".DS_Store") || !seen.add(rel)) continue;
                out.putNextEntry(new ZipEntry("assets/www/" + rel));
                try (InputStream in = uz.getInputStream(en)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                out.closeEntry();
                files++;
            }
        }
        if (files == 0) throw new IOException("ไม่มีไฟล์ในโปรเจกต์");

        // ---- 2) sign (v1 + v2) with the bundled key
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = ctx.getAssets().open("zip2apk.p12")) {
            ks.load(in, KEY_PASS);
        }
        PrivateKey key = (PrivateKey) ks.getKey(KEY_ALIAS, KEY_PASS);
        X509Certificate cert = (X509Certificate) ks.getCertificate(KEY_ALIAS);

        File outDir = new File(ctx.getCacheDir(), "apks");
        outDir.mkdirs();
        File signed = new File(outDir, safeName(appName) + ".apk");
        if (signed.exists()) signed.delete();

        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(
                KEY_ALIAS, key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(21)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign();

        deleteRecursive(work);
        return signed;
    }

    /** Copies the APK into the public Downloads folder. */
    public static Uri saveToDownloads(Context ctx, File apk) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, apk.getName());
        v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("บันทึกลง Downloads ไม่ได้");
        try (OutputStream os = cr.openOutputStream(uri); InputStream is = new FileInputStream(apk)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
        }
        return uri;
    }

    // ------------------------------------------------------------------ helpers
    /** Directory prefix of the shallowest index.html (so a wrapping folder in the zip is stripped). */
    private static String findRoot(File zip) throws IOException {
        String best = null;
        try (ZipFile z = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                String n = clean(en.getName());
                if (n == null || en.isDirectory()) continue;
                if (n.equals("index.html") || n.endsWith("/index.html")) {
                    String dir = n.substring(0, n.length() - "index.html".length());
                    if (best == null || dir.length() < best.length()) best = dir;
                }
            }
        }
        if (best == null) throw new IOException("ไม่พบ index.html ในไฟล์ zip");
        return best;
    }

    /** Normalises an entry name; returns null for unsafe / junk entries. */
    private static String clean(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.startsWith("__MACOSX/")) return null;
        for (String seg : n.split("/")) {
            if (seg.equals("..")) return null;
        }
        return n;
    }

    private static void copyEntry(ZipFile zf, ZipEntry en, ZipOutputStream out) throws IOException {
        ZipEntry ne = new ZipEntry(en.getName());
        if (en.getMethod() == ZipEntry.STORED) {
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(en.getSize());
            ne.setCompressedSize(en.getSize());
            ne.setCrc(en.getCrc());
        }
        out.putNextEntry(ne);
        try (InputStream in = zf.getInputStream(en)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        out.closeEntry();
    }

    private static String safeName(String s) {
        String n = s.replaceAll("[^A-Za-z0-9_\\-]+", "_");
        return n.isEmpty() || n.matches("_+") ? "app" : n;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = i.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    private static void copy(InputStream in, File dst) throws IOException {
        try (OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        f.delete();
    }
}
