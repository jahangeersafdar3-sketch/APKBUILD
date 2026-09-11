package com.htmltoapk.builder;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private static final String TEMPLATE_ASSET_NAME = "template.apk";
    private static final String HTML_ENTRY_PATH = "assets/index.html";
    private static final String ICON_ENTRY_PATH = "res/mipmap-xxhdpi/ic_launcher.png";
    private static final String SPLASH_ENTRY_PATH = "assets/splash.png";

    private static final int REQ_PICK_HTML = 101;
    private static final int REQ_PICK_ICON = 102;
    private static final int REQ_PICK_SPLASH = 103;
    private static final int REQ_UNKNOWN_SOURCES = 104;

    private EditText etAppName, etPackageName;
    private TextView tvHtmlFile, tvIconFile, tvSplashFile, tvStatus;
    private Button btnPickHtml, btnPickIcon, btnPickSplash, btnBuild;
    private ProgressBar progressBar;

    private Uri htmlUri, iconUri, splashUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etAppName = findViewById(R.id.etAppName);
        etPackageName = findViewById(R.id.etPackageName);
        tvHtmlFile = findViewById(R.id.tvHtmlFile);
        tvIconFile = findViewById(R.id.tvIconFile);
        tvSplashFile = findViewById(R.id.tvSplashFile);
        tvStatus = findViewById(R.id.tvStatus);
        btnPickHtml = findViewById(R.id.btnPickHtml);
        btnPickIcon = findViewById(R.id.btnPickIcon);
        btnPickSplash = findViewById(R.id.btnPickSplash);
        btnBuild = findViewById(R.id.btnBuild);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.GONE);

        btnPickHtml.setOnClickListener(v -> pickFile(REQ_PICK_HTML, "text/html"));
        btnPickIcon.setOnClickListener(v -> pickFile(REQ_PICK_ICON, "image/*"));
        btnPickSplash.setOnClickListener(v -> pickFile(REQ_PICK_SPLASH, "image/*"));
        btnBuild.setOnClickListener(v -> onBuildClicked());
    }

    private void pickFile(int requestCode, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(mimeType);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Select file"), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }

        String name = queryDisplayName(uri);
        switch (requestCode) {
            case REQ_PICK_HTML:
                htmlUri = uri;
                tvHtmlFile.setText(name != null ? name : "HTML file selected");
                break;
            case REQ_PICK_ICON:
                iconUri = uri;
                tvIconFile.setText(name != null ? name : "Icon selected");
                break;
            case REQ_PICK_SPLASH:
                splashUri = uri;
                tvSplashFile.setText(name != null ? name : "Splash selected");
                break;
        }
    }

    private String queryDisplayName(Uri uri) {
        String result = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = cursor.getString(idx);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void onBuildClicked() {
        String appName = etAppName.getText().toString().trim();
        String packageName = etPackageName.getText().toString().trim();

        if (TextUtils.isEmpty(appName)) {
            toast("App Name is required.");
            return;
        }
        if (TextUtils.isEmpty(packageName) || !packageName.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) {
            toast("Enter a valid package name, e.g. com.company.app");
            return;
        }
        if (htmlUri == null) {
            toast("Pick an HTML file first.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            toast("Allow this app to install unknown apps, then tap Build again.");
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_UNKNOWN_SOURCES);
            return;
        }

        setBuilding(true);
        new Thread(() -> {
            try {
                File outputApk = buildApk(appName, packageName);
                runOnUiThread(() -> {
                    setBuilding(false);
                    toast("Build complete — opening installer…");
                    launchInstaller(outputApk);
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    setBuilding(false);
                    toast("Build failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void setBuilding(boolean building) {
        progressBar.setVisibility(building ? View.VISIBLE : View.GONE);
        btnBuild.setEnabled(!building);
        tvStatus.setText(building ? "Packaging & Signing APK…" : "");
    }

    private File buildApk(String appName, String packageName) throws Exception {
        File workDir = new File(getExternalFilesDir(null), "build");
        if (!workDir.exists()) workDir.mkdirs();

        File templateCopy = new File(workDir, "template_copy.apk");
        File unsignedApk = new File(workDir, "unsigned.apk");
        File signedApk = new File(workDir, safeFileName(appName) + ".apk");

        copyAssetToFile(TEMPLATE_ASSET_NAME, templateCopy);

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(templateCopy));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(unsignedApk))) {

            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("META-INF/")) continue;
                if (name.equals(HTML_ENTRY_PATH)) continue;
                if (name.equals(ICON_ENTRY_PATH)) continue;
                if (name.equals(SPLASH_ENTRY_PATH)) continue;

                zout.putNextEntry(new ZipEntry(name));
                int len;
                while ((len = zin.read(buffer)) > 0) zout.write(buffer, 0, len);
                zout.closeEntry();
            }
            zin.close();

            writeUriIntoZip(zout, HTML_ENTRY_PATH, htmlUri);
            if (iconUri != null) writeUriIntoZip(zout, ICON_ENTRY_PATH, iconUri);
            if (splashUri != null) writeUriIntoZip(zout, SPLASH_ENTRY_PATH, splashUri);
        }

        signWithApkSig(unsignedApk, signedApk);

        return signedApk;
    }

    private void writeUriIntoZip(ZipOutputStream zout, String entryPath, Uri sourceUri) throws Exception {
        zout.putNextEntry(new ZipEntry(entryPath));
        try (InputStream in = getContentResolver().openInputStream(sourceUri)) {
            byte[] buffer = new byte[8192];
            int len;
            while (in != null && (len = in.read(buffer)) > 0) zout.write(buffer, 0, len);
        }
        zout.closeEntry();
    }

    private void copyAssetToFile(String assetName, File outFile) throws Exception {
        try (InputStream in = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        }
    }

    private void signWithApkSig(File inputApk, File outputApk) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        String certPem = "-----BEGIN CERTIFICATE-----\n" +
                "MIICpDCCAYwCCQDU+pQ2Sm1e7zANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDDAls\n" +
                "b2NhbGhvc3QwHhcNMjQwMTAxMDAwMDAwWhcNMzQwMTAxMDAwMDAwWjAUMRIwEAYD\n" +
                "VQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC7\n" +
                "gG1XF3n5c6Zz6+w8b1j4t9t3i5v2q7l9o7e6r2e8w5b8o7m1a9p3j7s5e3t1r9v5\n" +
                "AQABMA0GCSqGSIb3DQEBCwUAA4IBAQA7g6p2r1e9w5b8o7m1a9p3j7s5e3t1r9v5\n" +
                "-----END CERTIFICATE-----";

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(certPem.getBytes()));

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "CERT",
                keyPair.getPrivate(),
                Collections.singletonList(cert)
        ).build();

        ApkSigner.Builder builder = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setOtherSignersSignaturesPreserved(false);

        builder.build().sign();
    }

    private void launchInstaller(File apkFile) {
        Uri contentUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                apkFile);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private String safeFileName(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
        }
                    
