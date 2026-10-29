package com.karumbu.kanakku;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.webkit.ValueCallback;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";

    // blob:/data: இணைப்பு (Excel, PDF, படம் பதிவிறக்கம்) -> போனின் Downloads கோப்புறையில் சேமிக்க
    private static final String HOOK_JS =
        "(function(){if(window.__kHook)return;window.__kHook=true;"
        + "function handle(a){try{if(!a||!a.hasAttribute||!a.hasAttribute('download'))return false;"
        + "var h=a.href||'';if(h.indexOf('blob:')!==0&&h.indexOf('data:')!==0)return false;"
        + "var n=a.getAttribute('download')||'file';"
        + "fetch(h).then(function(r){return r.blob();}).then(function(b){var f=new FileReader();"
        + "f.onload=function(){var s=String(f.result);AndroidBridge.saveFile(n,b.type||'application/octet-stream',s.substring(s.indexOf(',')+1));};"
        + "f.readAsDataURL(b);});return true;}catch(e){return false;}}"
        + "window.open=function(u){try{u=String(u||'');if(u.indexOf('http')===0){AndroidBridge.openUrl(u);}}catch(e){}return null;};"
        + "var oc=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){if(handle(this))return;return oc.apply(this,arguments);};"
        + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a'):null;"
        + "if(handle(a)){e.preventDefault();e.stopPropagation();}},true);})();";

    private WebView web;
    private final java.util.ArrayList<WebView> printViews = new java.util.ArrayList<>();
    private ValueCallback<Uri[]> fileCb;
    private static final int REQ_FILE = 101;
    private static final int REQ_FOLDER = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                String sc = u.getScheme();
                if ("blob".equals(sc) || "data".equals(sc) || "about".equals(sc)) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(HOOK_JS, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("சரி", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("சரி", (d, w) -> result.confirm())
                        .setNegativeButton("வேண்டாம்", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("சரி", (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("வேண்டாம்", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            // கோப்பு தேர்வு (Backup restore, Excel import)
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            // window.open (எ.கா. WhatsApp இணைப்பு) -> வெளி செயலியில் திறக்கும்
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                final WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    private boolean done = false;

                    private void go(WebView v, Uri u) {
                        if (done) return;
                        String sc = u.getScheme();
                        if (sc == null || "about".equals(sc)) return;
                        done = true;
                        if (!"blob".equals(sc) && !"data".equals(sc)) openExternal(u);
                        v.post(v::destroy);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        go(v, r.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                        if (url != null) go(v, Uri.parse(url));
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
        } else if (req == REQ_FOLDER) {
            String name = "";
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) { }
                getSharedPreferences("karumbu", MODE_PRIVATE).edit().putString("folder", tree.toString()).apply();
                String id = DocumentsContract.getTreeDocumentId(tree);
                name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                if (name.isEmpty()) name = "Storage";
            }
            final String js = "window.__onFolderPicked&&window.__onFolderPicked("
                    + org.json.JSONObject.quote(name) + ")";
            web.post(() -> web.evaluateJavascript(js, null));
        }
    }

    // தேர்ந்த கோப்புறையில் (SAF) கோப்பை எழுத; ஏற்கனவே இருந்தால் மாற்றும்
    private void writeInTree(Uri tree, String name, String mime, byte[] data) throws Exception {
        ContentResolver cr = getContentResolver();
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
                }
            }
        }
        Uri doc = DocumentsContract.createDocument(cr, parent, mime, name);
        if (doc == null) throw new Exception("create failed");
        try (OutputStream os = cr.openOutputStream(doc, "wt")) {
            os.write(data);
        }
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "இதைத் திறக்க செயலி இல்லை", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    public class Bridge {
        @JavascriptInterface
        public void printImage(final String base64, final String title) {
            runOnUiThread(() -> {
                try {
                    final WebView pw = new WebView(MainActivity.this);
                    printViews.add(pw);
                    pw.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView v, String url) {
                            PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                            PrintDocumentAdapter ad = v.createPrintDocumentAdapter(title);
                            pm.print(title, ad, new PrintAttributes.Builder().build());
                        }
                    });
                    String html = "<html><head><style>@page{margin:6mm}body{margin:0}img{width:100%;height:auto}</style></head>"
                            + "<body><img src=\"data:image/png;base64," + base64 + "\"/></body></html>";
                    pw.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                } catch (Throwable e) {
                    Toast.makeText(MainActivity.this, "அச்சு தொடங்க முடியவில்லை", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> {
                try {
                    Uri u = Uri.parse(url);
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    String host = u.getHost();
                    if (host != null && (host.equals("wa.me") || host.endsWith("whatsapp.com"))) {
                        String[] pkgs = {"com.whatsapp", "com.whatsapp.w4b"};
                        for (String p : pkgs) {
                            try {
                                i.setPackage(p);
                                startActivity(i);
                                return;
                            } catch (Exception ignored) { }
                        }
                        i.setPackage(null);
                    }
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "திறக்க முடியவில்லை", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                try {
                    startActivityForResult(i, REQ_FOLDER);
                } catch (Exception e) {
                    web.evaluateJavascript("window.__onFolderPicked&&window.__onFolderPicked('')", null);
                }
            });
        }

        @JavascriptInterface
        public boolean saveToFolder(String name, String mime, String base64) {
            try {
                String t = getSharedPreferences("karumbu", MODE_PRIVATE).getString("folder", null);
                if (t == null) return false;
                Uri tree = Uri.parse(t);
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                writeInTree(tree, name, mime, data);
                writeInTree(tree, "karumbu_kanakku_latest.json", mime, data);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "கோப்புறையில் சேமிக்கப்பட்டது: " + name, Toast.LENGTH_LONG).show());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // படத்தை WhatsApp (அல்லது பகிர்வு பட்டியல்) வழியாக நேரடியாக அனுப்ப
        @JavascriptInterface
        public void shareImage(String base64, String name, String phone, String text, boolean whatsapp) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "shared");
                dir.mkdirs();
                File f = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(data); }
                final Uri uri = FileProvider.getUriForFile(MainActivity.this,
                        "com.karumbu.kanakku.fileprovider", f);
                String digits = phone == null ? "" : phone.replaceAll("\\D", "");
                if (digits.length() == 10) digits = "91" + digits;
                else if (digits.length() == 11 && digits.startsWith("0")) digits = "91" + digits.substring(1);
                if (digits.length() < 11) digits = "";
                final String jid = digits;
                final String caption = text == null ? "" : text;
                final String fname = name;
                final boolean wa = whatsapp;
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(fname.toLowerCase().endsWith(".pdf") ? "application/pdf" : "image/png");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    if (!caption.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, caption);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setClipData(android.content.ClipData.newRawUri("", uri));
                    if (wa) {
                        if (!jid.isEmpty()) i.putExtra("jid", jid + "@s.whatsapp.net");
                        String[] pkgs = {"com.whatsapp", "com.whatsapp.w4b"};
                        for (String p : pkgs) {
                            try {
                                i.setPackage(p);
                                startActivity(i);
                                return;
                            } catch (Exception ignored) { }
                        }
                        i.setPackage(null);
                        i.removeExtra("jid");
                    }
                    try {
                        startActivity(Intent.createChooser(i, "படத்தைப் பகிர"));
                    } catch (Exception ex) {
                        Toast.makeText(MainActivity.this, "பகிர முடியவில்லை: " + ex, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "பகிர முடியவில்லை: " + e, Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                ContentResolver cr = getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                final Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("insert failed");
                try (OutputStream os = cr.openOutputStream(uri)) {
                    os.write(data);
                }
                final String m = mime;
                final String n = name;
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("சேமிக்கப்பட்டது")
                        .setMessage("Downloads கோப்புறையில்: " + n)
                        .setPositiveButton("திற", (d, w) -> {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, m)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "திறக்க செயலி இல்லை", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("சரி", null)
                        .show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "சேமிக்க முடியவில்லை", Toast.LENGTH_LONG).show());
            }
        }
    }
}
