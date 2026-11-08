package it.registrocimiteriale.app;

import android.app.Activity;
import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int RICHIESTA_FILE = 1;
    private WebView web;
    private ValueCallback<Uri[]> attesaFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new Chrome());
        web.addJavascriptInterface(new Ponte(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        }
    }

    private static final String CARTELLA = "Registro Cimiteriale";
    private static final String FILE_AUTO = "registro-cimiteriale-automatico.json";

    /** Scrive un file nella cartella Download/Registro Cimiteriale. Se sovrascrivi = true riusa sempre lo stesso file. */
    private boolean scriviInDownload(String nome, byte[] dati, String tipo, boolean sovrascrivi) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = null;
            SharedPreferences pref = getSharedPreferences("registro", MODE_PRIVATE);
            if (sovrascrivi) {
                String salvato = pref.getString("uri_" + nome, null);
                if (salvato != null) uri = Uri.parse(salvato);
                if (uri == null) uri = cerca(nome);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        if (os != null) { os.write(dati); return true; }
                    } catch (Exception e) {
                        uri = cerca(nome);
                        if (uri != null) {
                            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                                if (os != null) { os.write(dati); pref.edit().putString("uri_" + nome, uri.toString()).apply(); return true; }
                            } catch (Exception ignored) { }
                        }
                    }
                }
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, nome);
            cv.put(MediaStore.Downloads.MIME_TYPE, tipo);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + CARTELLA);
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return false;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(dati); }
            if (sovrascrivi) pref.edit().putString("uri_" + nome, uri.toString()).apply();
            return true;
        } else {
            File dir;
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), CARTELLA);
            } else {
                dir = new File(getExternalFilesDir(null), CARTELLA);
            }
            dir.mkdirs();
            try (FileOutputStream fo = new FileOutputStream(new File(dir, nome))) { fo.write(dati); }
            return true;
        }
    }

    /** Cerca un file creato da questa app nella cartella Download/Registro Cimiteriale. */
    private Uri cerca(String nome) {
        if (Build.VERSION.SDK_INT < 29) return null;
        String[] col = {MediaStore.Downloads._ID};
        String sel = MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] arg = {nome, Environment.DIRECTORY_DOWNLOADS + "/" + CARTELLA + "%"};
        try (Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, col, sel, arg, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
        } catch (Exception ignored) { }
        return null;
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (attesaFile != null) attesaFile.onReceiveValue(null);
            attesaFile = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(i, "Scegli la copia di sicurezza"), RICHIESTA_FILE);
            } catch (Exception e) {
                attesaFile = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == RICHIESTA_FILE && attesaFile != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            attesaFile.onReceiveValue(r);
            attesaFile = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.__indietro ? window.__indietro() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    /** Archivio salvato nella memoria interna dell'app, con copia di riserva. */
    private class Ponte {
        private File archivio() { return new File(getFilesDir(), "registro.json"); }
        private File riserva() { return new File(getFilesDir(), "registro.bak.json"); }

        @JavascriptInterface
        public String carica() {
            String t = leggi(archivio());
            if (t == null || t.isEmpty()) t = leggi(riserva());
            return t == null ? "" : t;
        }

        @JavascriptInterface
        public boolean salva(String json) {
            try {
                File a = archivio();
                if (a.exists()) {
                    String vecchio = leggi(a);
                    if (vecchio != null && !vecchio.isEmpty()) scrivi(riserva(), vecchio);
                }
                File tmp = new File(getFilesDir(), "registro.tmp");
                scrivi(tmp, json);
                return tmp.renameTo(a);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String salvaFile(String nome, String contenuto, String tipo) {
            try {
                if (!scriviInDownload(nome, contenuto.getBytes(StandardCharsets.UTF_8), tipo, false)) return "";
                avviso("Salvato in Download/" + CARTELLA + "/" + nome);
                return "ok";
            } catch (Exception e) {
                return "";
            }
        }

        /** Copia automatica: sempre lo stesso file, sovrascritto ad ogni salvataggio. */
        @JavascriptInterface
        public boolean salvaAutomatico(String contenuto) {
            try { return scriviInDownload(FILE_AUTO, contenuto.getBytes(StandardCharsets.UTF_8), "application/json", true); }
            catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String versione() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }

        private void avviso(String m) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
        }
    }

    private static String leggi(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void scrivi(File f, String t) throws Exception {
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(t.getBytes(StandardCharsets.UTF_8));
            fo.getFD().sync();
        }
    }
}
