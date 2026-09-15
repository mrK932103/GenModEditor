package com.nosirov.bigtool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin, reliable file-transport layer for the Generals Mod Editor WebView.
 *
 * Every save is a SINGLE synchronous write of the COMPLETE final byte buffer
 * (openOutputStream -> write(all bytes) -> close) done entirely on the Java side.
 * This avoids the failure mode of the browser File System Access API, where
 * createWritable() truncates the target file immediately and an unrelated JS
 * error occurring before the final write+close can leave a 0-byte file.
 *
 * All file content crosses the JS bridge as base64 in a single call.
 */
public class MainActivity extends Activity {

    private static final int REQ_OPEN_BIG = 1;
    private static final int REQ_CREATE_BIG = 2;
    private static final int REQ_OPEN_TEXT = 3;
    private static final int REQ_CREATE_TEXT = 4;
    private static final int REQ_ADD_FILES = 5;
    private static final int REQ_ADD_FOLDER = 6;
    private static final int REQ_EXTRACT_ONE = 7;
    private static final int REQ_EXTRACT_ALL_DEST = 8;

    private static final long MAX_RECOMMENDED_BYTES = 180L * 1024 * 1024;

    private WebView webView;
    private Uri currentBigUri;
    private Uri currentTextUri;
    private String pendingTextKind = "";
    private byte[] pendingSaveBytes;
    private String pendingBigManifest;
    private String pendingBigSaveAsName;
    private List<PendingItem> pendingExtractAll;
    private boolean pageReady = false;

    private static class BigEntry {
        String name;
        long offset;
        long size;
        BigEntry(String n, long o, long s) { name=n; offset=o; size=s; }
    }
    private List<BigEntry> currentBigIndex = new ArrayList<>();
    private String currentBigMagic = "BIGF";


    private static class PendingItem {
        String relPath; byte[] data;
        PendingItem(String p, byte[] d) { relPath = p; data = d; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) { result.confirm(); }
                    })
                    .setCancelable(false)
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { result.cancel(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) { result.cancel(); }
                    })
                    .setCancelable(false)
                    .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                if (defaultValue != null) { input.setText(defaultValue); input.setSelection(defaultValue.length()); }
                FrameLayout container = new FrameLayout(MainActivity.this);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                lp.setMargins(pad, pad / 2, pad, 0);
                input.setLayoutParams(lp);
                container.addView(input);
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { result.confirm(input.getText().toString()); }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { result.cancel(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) { result.cancel(); }
                    })
                    .setCancelable(false)
                    .show();
                return true;
            }
        });
        setContentView(webView);
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                handleViewIntent(getIntent());
            }
        });
        webView.loadUrl("file:///android_asset/big_archive_tool.html");
    }

    private void run(final String js) {
        runOnUiThread(new Runnable() { @Override public void run() { webView.evaluateJavascript(js, null); } });
    }

    private static String jsString(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        b.append('\'');
        return b.toString();
    }

    private static String jsonStr(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) continue;
            else b.append(c);
        }
        b.append('"');
        return b.toString();
    }

    private byte[] readAll(Uri uri) throws Exception {
        ContentResolver cr = getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new Exception("openInputStream returned null");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private void writeAll(Uri uri, byte[] data) throws Exception {
        ContentResolver cr = getContentResolver();
        OutputStream out = cr.openOutputStream(uri, "wt");
        if (out == null) throw new Exception("openOutputStream returned null");
        out.write(data);
        out.flush();
        out.close();
    }

    private String nameFromUri(Uri uri) {
        String name = uri.getLastPathSegment();
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx);
                c.close();
            }
        } catch (Exception ignored) { }
        return name != null ? name : "file";
    }

    private void recurseAdd(Uri treeUri, String docId, String relPath, List<PendingItem> out, int[] guard) throws Exception {
        if (guard[0] > 4000) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Cursor c = getContentResolver().query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        }, null, null, null);
        if (c == null) return;
        while (c.moveToNext()) {
            String childId = c.getString(0);
            String childName = c.getString(1);
            String mime = c.getString(2);
            String childRel = relPath.isEmpty() ? childName : relPath + "/" + childName;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                guard[0]++;
                recurseAdd(treeUri, childId, childRel, out, guard);
            } else {
                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                try { out.add(new PendingItem(childRel, readAll(fileUri))); guard[0]++; } catch (Exception ignored) { }
            }
            if (guard[0] > 4000) break;
        }
        c.close();
    }

    private String findOrCreateDir(Uri treeUri, String parentDocId, String name) throws Exception {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        Cursor c = getContentResolver().query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        }, null, null, null);
        if (c != null) {
            while (c.moveToNext()) {
                if (name.equals(c.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    String id = c.getString(0); c.close(); return id;
                }
            }
            c.close();
        }
        Uri created = DocumentsContract.createDocument(getContentResolver(),
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId),
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        return DocumentsContract.getDocumentId(created);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pageReady) handleViewIntent(intent);
    }

    private int readU32BE(byte[] h, int p) {
        return ((h[p]&255)<<24)|((h[p+1]&255)<<16)|((h[p+2]&255)<<8)|(h[p+3]&255);
    }

    private int readU32LE(byte[] h, int p) {
        return (h[p]&255)|((h[p+1]&255)<<8)|((h[p+2]&255)<<16)|((h[p+3]&255)<<24);
    }

    private byte[] readExactly(InputStream in, int len) throws Exception {
        byte[] b = new byte[len]; int p=0;
        while (p<len) { int n=in.read(b,p,len-p); if(n<0) throw new Exception("Неожиданный конец файла"); p+=n; }
        return b;
    }

    private long skipFully(InputStream in, long n) throws Exception {
        long left=n;
        while(left>0){ long k=in.skip(left); if(k>0){left-=k;continue;} int x=in.read(); if(x<0) throw new Exception("Не удалось перейти к данным BIG"); left--; }
        return n;
    }

    private List<BigEntry> readBigIndex(Uri uri) throws Exception {
        currentBigIndex.clear();
        ContentResolver cr=getContentResolver();
        InputStream in=cr.openInputStream(uri); if(in==null) throw new Exception("Не удалось открыть BIG");
        try {
            byte[] h=readExactly(in,16);
            currentBigMagic=new String(h,0,4,"US-ASCII");
            if(!"BIGF".equals(currentBigMagic)&&!"BIG4".equals(currentBigMagic)) throw new Exception("Это не BIGF/BIG4 архив");
            // BIGF/BIG4: archive size is little-endian; count and index offset are big-endian.
            long headerTotal=readU32LE(h,4)&0xffffffffL;
            long count=readU32BE(h,8)&0xffffffffL;
            long dataStart=readU32BE(h,12)&0xffffffffL;
            long actualTotal=headerTotal;
            try {
                ParcelFileDescriptor sf=cr.openFileDescriptor(uri,"r");
                if(sf!=null){ actualTotal=sf.getStatSize()>0?sf.getStatSize():headerTotal; sf.close(); }
            } catch(Exception ignored) {}
            if(count>200000 || dataStart<16 || dataStart>actualTotal) throw new Exception("Повреждённый индекс BIG");
            for(int i=0;i<count;i++){
                byte[] eh=readExactly(in,8);
                long off=readU32BE(eh,0)&0xffffffffL, len=readU32BE(eh,4)&0xffffffffL;
                ByteArrayOutputStream nb=new ByteArrayOutputStream();
                int c; while((c=in.read())>0) nb.write(c); if(c<0) throw new Exception("Повреждённое имя файла");
                String name=new String(nb.toByteArray(),"UTF-8").replace('\\','/');
                if(off<0 || len<0 || off>actualTotal || len>actualTotal-off) throw new Exception("Данные файла выходят за пределы архива");
                currentBigIndex.add(new BigEntry(name,off,len));
            }
            return currentBigIndex;
        } finally { in.close(); }
    }

    private String bigIndexJson(List<BigEntry> list) {
        StringBuilder j=new StringBuilder("[");
        for(int i=0;i<list.size();i++){
            if(i>0)j.append(','); BigEntry e=list.get(i);
            j.append("{\"name\":").append(jsonStr(e.name)).append(",\"offset\":").append(e.offset)
             .append(",\"size\":").append(e.size).append(",\"index\":").append(i).append('}');
        }
        return j.append(']').toString();
    }

    private InputStream openBigRange(long offset) throws Exception {
        ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(currentBigUri,"r");
        if(pfd==null) throw new Exception("Не удалось открыть исходный BIG");
        try {
            FileInputStream fis=new FileInputStream(pfd.getFileDescriptor());
            try {
                fis.getChannel().position(offset);
                pfd=null;
                return fis;
            } catch(Exception seekFail) {
                try { fis.close(); } catch(Exception ignored) {}
                InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                pfd=null;
                skipFully(in,offset);
                return in;
            }
        } finally { if(pfd!=null) pfd.close(); }
    }

    private byte[] readBigEntryBytes(int index) throws Exception {
        if(index<0||index>=currentBigIndex.size()) throw new Exception("Неверный индекс BIG");
        BigEntry e=currentBigIndex.get(index);
        if(e.size>256L*1024*1024) throw new Exception("Файл внутри BIG слишком большой для редактора WebView ("+(e.size/1024/1024)+" МБ)");
        InputStream in=openBigRange(e.offset);
        try { return readExactly(in,(int)e.size); } finally { in.close(); }
    }

    private void copyBigRange(OutputStream out, int index) throws Exception {
        BigEntry e=currentBigIndex.get(index);
        InputStream in=openBigRange(e.offset);
        try {
            byte[] buf=new byte[1024*1024]; long left=e.size;
            while(left>0){ int want=(int)Math.min(buf.length,left); int n=in.read(buf,0,want); if(n<0) throw new Exception("Неожиданный конец BIG"); out.write(buf,0,n); left-=n; }
        } finally { in.close(); }
    }

    private byte[] b64(String s) { return Base64.decode(s,Base64.DEFAULT); }

    private void saveBigStreaming(Uri outUri, org.json.JSONArray arr) throws Exception {
        int count=arr.length();
        long idx=16;
        for(int i=0;i<count;i++){
            org.json.JSONObject o=arr.getJSONObject(i);
            byte[] name=o.getString("name").replace('/','\\').getBytes("UTF-8");
            idx += 8 + name.length + 1;
        }
        long total=idx;
        for(int i=0;i<count;i++) total += arr.getJSONObject(i).getLong("size");
        if(total>0xffffffffL) throw new Exception("BIG больше 4 ГБ не поддерживается форматом BIG");

        File tmp=File.createTempFile("genmod_", ".big.tmp", getCacheDir());
        try {
            OutputStream out=new FileOutputStream(tmp);
            try {
                byte[] h=new byte[16]; byte[] mg=currentBigMagic.getBytes("US-ASCII"); System.arraycopy(mg,0,h,0,4);
                putU32LE(h,4,total); putU32BE(h,8,count); putU32BE(h,12,idx); out.write(h);
                long off=idx;
                for(int i=0;i<count;i++){
                    org.json.JSONObject o=arr.getJSONObject(i); byte[] name=o.getString("name").replace('/','\\').getBytes("UTF-8");
                    putU32BE(h,0,off); putU32BE(h,4,o.getLong("size")); out.write(h,0,8); out.write(name); out.write(0); off+=o.getLong("size");
                }
                for(int i=0;i<count;i++){
                    org.json.JSONObject o=arr.getJSONObject(i); long size=o.getLong("size");
                    if(o.optBoolean("modified",false) || o.optInt("index",-1)<0){
                        byte[] d=b64(o.optString("b64","")); if(d.length!=size) throw new Exception("Размер изменённого файла не совпадает"); out.write(d);
                    } else copyBigRange(out,o.getInt("index"));
                }
                out.flush();
            } finally { out.close(); }

            ContentResolver cr=getContentResolver();
            OutputStream dest=cr.openOutputStream(outUri,"wt");
            if(dest==null) throw new Exception("Не удалось открыть файл для записи");
            try {
                InputStream src=new FileInputStream(tmp);
                try {
                    byte[] buf=new byte[1024*1024]; int n;
                    while((n=src.read(buf))!=-1) dest.write(buf,0,n);
                    dest.flush();
                } finally { src.close(); }
            } finally { dest.close(); }
        } finally {
            if(!tmp.delete()) tmp.deleteOnExit();
        }
    }

    private static void putU32BE(byte[] b,int p,long v){ b[p]=(byte)(v>>>24);b[p+1]=(byte)(v>>>16);b[p+2]=(byte)(v>>>8);b[p+3]=(byte)v; }
    private static void putU32LE(byte[] b,int p,long v){ b[p]=(byte)v;b[p+1]=(byte)(v>>>8);b[p+2]=(byte)(v>>>16);b[p+3]=(byte)(v>>>24); }

    /** Handles the app being opened via "Open with..." on a .big/.csf/.str/.ini file. */
    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        final Uri uri = intent.getData();
        if (uri == null) return;
        try {
            String name = nameFromUri(uri);
            String lower = name.toLowerCase();
            if (lower.endsWith(".big")) {
                currentBigUri = uri;
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch(Exception ignored) {}
                List<BigEntry> ix=readBigIndex(uri);
                run("window.onBigIndexOpened&&window.onBigIndexOpened("+bigIndexJson(ix)+","+jsString(name)+")");
            } else if (lower.endsWith(".csf") || lower.endsWith(".str")) {
                byte[] bytes = readAll(uri);
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                currentTextUri = uri;
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) {}
                run("window.onTextOpened&&window.onTextOpened('" + b64 + "'," + jsString(name) + ",'csfstr')");
            } else if (lower.endsWith(".ini")) {
                byte[] bytes = readAll(uri);
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                currentTextUri = uri;
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) {}
                run("window.onTextOpened&&window.onTextOpened('" + b64 + "'," + jsString(name) + ",'ini')");
            }
        } catch (Exception e) {
            run("window.toast&&window.toast(" + jsString("Не удалось открыть файл: " + e.getMessage()) + ")");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_CREATE_BIG || requestCode == REQ_CREATE_TEXT || requestCode == REQ_EXTRACT_ONE) {
                run("window.onSaveResult&&window.onSaveResult(false,'Отменено')");
            }
            return;
        }
        try {
            switch (requestCode) {
                case REQ_OPEN_BIG: {
                    Uri uri = data.getData();
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    currentBigUri = uri;
                    List<BigEntry> ix=readBigIndex(uri);
                    run("window.onBigIndexOpened&&window.onBigIndexOpened("+bigIndexJson(ix)+","+jsString(nameFromUri(uri))+")");
                    break;
                }
                case REQ_CREATE_BIG: {
                    Uri uri = data.getData();
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    currentBigUri = uri;
                    if(pendingBigManifest!=null){
                        saveBigStreaming(uri,new org.json.JSONArray(pendingBigManifest));
                        pendingBigManifest=null; pendingBigSaveAsName=null;
                    } else { writeAll(uri, pendingSaveBytes); pendingSaveBytes=null; }
                    run("window.onSaveResult&&window.onSaveResult(true," + jsString("BIG сохранён: " + nameFromUri(uri)) + ")");
                    break;
                }
                case REQ_OPEN_TEXT: {
                    Uri uri = data.getData();
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    currentTextUri = uri;
                    byte[] bytes = readAll(uri);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    run("window.onTextOpened&&window.onTextOpened('" + b64 + "'," + jsString(nameFromUri(uri)) + "," + jsString(pendingTextKind) + ")");
                    break;
                }
                case REQ_CREATE_TEXT: {
                    Uri uri = data.getData();
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    currentTextUri = uri;
                    writeAll(uri, pendingSaveBytes);
                    pendingSaveBytes = null;
                    run("window.onTextSaveResult&&window.onTextSaveResult(true," + jsString("Сохранено: " + nameFromUri(uri)) + ")");
                    break;
                }
                case REQ_ADD_FILES: {
                    List<PendingItem> items = new ArrayList<>();
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        for (int i = 0; i < n; i++) {
                            Uri u = data.getClipData().getItemAt(i).getUri();
                            items.add(new PendingItem(nameFromUri(u), readAll(u)));
                        }
                    } else if (data.getData() != null) {
                        Uri u = data.getData();
                        items.add(new PendingItem(nameFromUri(u), readAll(u)));
                    }
                    run("window.onFilesAdded&&window.onFilesAdded(" + toJsonArray(items) + ")");
                    break;
                }
                case REQ_ADD_FOLDER: {
                    Uri treeUri = data.getData();
                    getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
                    List<PendingItem> items = new ArrayList<>();
                    recurseAdd(treeUri, rootDocId, "", items, new int[]{0});
                    run("window.onFolderAdded&&window.onFolderAdded(" + toJsonArray(items) + ")");
                    break;
                }
                case REQ_EXTRACT_ONE: {
                    Uri uri = data.getData();
                    writeAll(uri, pendingSaveBytes);
                    pendingSaveBytes = null;
                    run("window.onExtractResult&&window.onExtractResult(true," + jsString("Извлечено: " + nameFromUri(uri)) + ")");
                    break;
                }
                case REQ_EXTRACT_ALL_DEST: {
                    Uri treeUri = data.getData();
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
                    int written = 0;
                    if (pendingExtractAll != null) {
                        for (PendingItem it : pendingExtractAll) {
                            String[] parts = it.relPath.replace('\\', '/').split("/");
                            String parentDocId = rootDocId;
                            for (int i = 0; i < parts.length - 1; i++) parentDocId = findOrCreateDir(treeUri, parentDocId, parts[i]);
                            Uri fileUri = DocumentsContract.createDocument(getContentResolver(),
                                    DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId),
                                    "application/octet-stream", parts[parts.length - 1]);
                            if (fileUri != null) { writeAll(fileUri, it.data); written++; }
                        }
                    }
                    pendingExtractAll = null;
                    run("window.onExtractAllResult&&window.onExtractAllResult(true," + jsString("Извлечено файлов: " + written) + ")");
                    break;
                }
            }
        } catch (Exception e) {
            String msg = "Ошибка: " + e.getMessage();
            run("window.onSaveResult&&window.onSaveResult(false," + jsString(msg) + ");" +
                "window.onTextSaveResult&&window.onTextSaveResult(false," + jsString(msg) + ");" +
                "window.toast&&window.toast(" + jsString(msg) + ")");
        }
    }

    private String toJsonArray(List<PendingItem> items) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) json.append(",");
            PendingItem it = items.get(i);
            json.append("{\"name\":").append(jsonStr(it.relPath)).append(",\"b64\":\"")
                    .append(Base64.encodeToString(it.data, Base64.NO_WRAP)).append("\"}");
        }
        json.append("]");
        return json.toString();
    }

    public class Bridge {

        @JavascriptInterface
        public String readBigEntry(int index) {
            try { return Base64.encodeToString(readBigEntryBytes(index), Base64.NO_WRAP); }
            catch(Exception e){ return "__ERROR__"+Base64.encodeToString(String.valueOf(e.getMessage()).getBytes(),Base64.NO_WRAP); }
        }

        @JavascriptInterface
        public void saveBigStreaming(String manifestJson, boolean saveAs, String suggestedName) {
            try {
                org.json.JSONArray arr=new org.json.JSONArray(manifestJson);
                if(saveAs || currentBigUri==null){
                    pendingBigManifest=manifestJson; pendingBigSaveAsName=suggestedName;
                    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/octet-stream"); i.putExtra(Intent.EXTRA_TITLE,suggestedName);
                    startActivityForResult(i,REQ_CREATE_BIG);
                } else {
                    MainActivity.this.saveBigStreaming(currentBigUri,arr);
                    run("window.onSaveResult&&window.onSaveResult(true,'BIG сохранён')");
                }
            } catch(Exception e){ run("window.onSaveResult&&window.onSaveResult(false,"+jsString("Ошибка сохранения: "+e.getMessage())+")"); }
        }

        @JavascriptInterface
        public void openBig() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(Intent.createChooser(i, "Открыть BIG"), REQ_OPEN_BIG);
        }

        @JavascriptInterface
        public void save(String base64) {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            if (currentBigUri == null) { saveAs(base64, "archive.big"); return; }
            try {
                writeAll(currentBigUri, bytes);
                run("window.onSaveResult&&window.onSaveResult(true,'BIG сохранён')");
            } catch (Exception e) {
                run("window.onSaveResult&&window.onSaveResult(false," + jsString("Ошибка сохранения: " + e.getMessage()) + ")");
            }
        }

        @JavascriptInterface
        public void saveAs(String base64, String suggestedName) {
            pendingSaveBytes = Base64.decode(base64, Base64.DEFAULT);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, suggestedName);
            startActivityForResult(i, REQ_CREATE_BIG);
        }

        @JavascriptInterface
        public void pickText(String kind) {
            pendingTextKind = kind;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(Intent.createChooser(i, "Открыть файл"), REQ_OPEN_TEXT);
        }

        @JavascriptInterface
        public void saveText(String base64) {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            if (currentTextUri == null) {
                pendingSaveBytes = bytes;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_TITLE, "file.txt");
                startActivityForResult(i, REQ_CREATE_TEXT);
                return;
            }
            try {
                writeAll(currentTextUri, bytes);
                run("window.onTextSaveResult&&window.onTextSaveResult(true,'Сохранено в исходный файл')");
            } catch (Exception e) {
                run("window.onTextSaveResult&&window.onTextSaveResult(false," + jsString("Ошибка: " + e.getMessage()) + ")");
            }
        }

        @JavascriptInterface
        public void addFiles() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "Добавить файлы"), REQ_ADD_FILES);
        }

        @JavascriptInterface
        public void addFolder() {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_ADD_FOLDER);
        }

        @JavascriptInterface
        public void extract(String base64, String suggestedName) {
            pendingSaveBytes = Base64.decode(base64, Base64.DEFAULT);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, suggestedName);
            startActivityForResult(i, REQ_EXTRACT_ONE);
        }

        /** payloadJson: [{"name":"Data/Foo.ini","b64":"..."}, ...] */
        @JavascriptInterface
        public void extractAll(String payloadJson) {
            try {
                pendingExtractAll = new ArrayList<>();
                org.json.JSONArray arr = new org.json.JSONArray(payloadJson);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    pendingExtractAll.add(new PendingItem(o.getString("name"), Base64.decode(o.getString("b64"), Base64.DEFAULT)));
                }
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXTRACT_ALL_DEST);
            } catch (Exception e) {
                run("window.toast&&window.toast(" + jsString("Ошибка: " + e.getMessage()) + ")");
            }
        }
    }
}
