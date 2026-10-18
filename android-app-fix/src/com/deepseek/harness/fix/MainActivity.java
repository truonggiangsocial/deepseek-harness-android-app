package com.deepseek.harness.fix;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** DSH 修复工具：检测/修复 DeepSeek Harness 运行环境（独立工具，不碰主 App 内部数据） */
public class MainActivity extends Activity {
    private static final String MAIN_PKG = "com.deepseek.harness";
    private static final String EXT_ROOT = "DeepSeekHarness";
    private static final String BUILTIN_REVISION = "20260820024026"; // 主 App v1.4.0 内置版本标记

    private TextView log;
    private Button btnFix;

    private static class Item {
        String name, path, keyword;
        Item(String n, String p, String k) { name = n; path = p; keyword = k; }
    }
    private final List<Item> items = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        File root = new File(Environment.getExternalStorageDirectory(), EXT_ROOT);
        File ds = new File(root, "dshroot");
        String base = ds + "/lib/node_modules/@deepseek-ai/dsh/";
        String nbase = ds + "/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/";
        items.add(new Item("Kernel entry bin.js", base + "lib/bin.js", null));
        items.add(new Item("Sidebar layout client.js", nbase + "dsh-client-ui-layout/lib/client.js", "gridColumn"));
        items.add(new Item("Plugin button cordis client.js", nbase + "dsh-client-ui-cordis/lib/client.js", "header.utilities"));
        items.add(new Item("Shizuku privilege fallback", nbase + "dsh-tool-shizuku/lib/index.js", "privilegedAvailable"));
        items.add(new Item("Android privilege fallback", nbase + "dsh-tool-android/lib/index.js", "privilegedAvailable"));
        items.add(new Item("AI notification tool", nbase + "dsh-tool-shizuku/lib/index.js", "android_notify"));
        items.add(new Item("Mobile styles mobile.css", nbase + "dsh-web-frontend/dist/mobile.css", null));
        items.add(new Item("Mobile script mobile.js", nbase + "dsh-web-frontend/dist/mobile.js", null));
        items.add(new Item("Frontend entry index.html", nbase + "dsh-web-frontend/dist/index.html", null));
        items.add(new Item("Completion marker .complete", ds + "/.complete", null));
        items.add(new Item("Version marker REVISION", ds + "/REVISION", null));
        setContentView(buildUi());
        log(hasStoragePermission()
                ? "✅ 存储权限：已授予，可以检测"
                : "⚠️ Storage access not granted. Grant access below or all checks will fail.");
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("DeepSeek Harness Repair Tool");
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Check and repair the DSH runtime, including external dshroot integrity, patch loading, media protection, architecture, and engine files.\nClose the main app before starting a repair.");
        sub.setTextSize(13);
        sub.setPadding(0, 8, 0, 16);
        col.addView(sub);

        log = new TextView(this);
        log.setTextSize(13);
        log.setPadding(0, 8, 0, 16);
        col.addView(log);

        btnFix = new Button(this);
        btnFix.setText("Check and repair");
        btnFix.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runFix(); }
        });
        col.addView(btnFix);

        Button btnPerm = new Button(this);
        btnPerm.setText("Grant all files access");
        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                    catch (Exception e2) {}
                }
            }
        });
        col.addView(btnPerm);

        Button btnMain = new Button(this);
        btnMain.setText("Open main app (restart after repair)");
        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(getPackageManager().getLaunchIntentForPackage(MAIN_PKG)); }
                catch (Exception e) { log("Main app is not installed"); }
            }
        });
        col.addView(btnMain);

        sv.addView(col);
        return sv;
    }

    @Override protected void onResume() {
        super.onResume();
        if (log != null) {
            log.setText(hasStoragePermission()
                    ? "✅ Storage access granted. Tap Check and repair to continue."
                    : "⚠️ Storage access not granted. Grant access to continue.");
        }
    }

    private void runFix() {
        if (!hasStoragePermission()) {
            log.setText("❌ All files access is required to run checks.\n\nTap Grant all files access below, then tap Check and repair.");
            return;
        }
        btnFix.setEnabled(false);
        log.setText("");
        new Thread(new Runnable() {
            @Override public void run() {
                final String out = doFix();
                runOnUiThread(new Runnable() {
                    @Override public void run() { log.setText(out); btnFix.setEnabled(true); }
                });
            }
        }).start();
    }

    private String doFix() {
        StringBuilder sb = new StringBuilder();
        checkArch(sb);
        checkMainApp(sb);
        checkEngineSync(sb);
        File root = new File(Environment.getExternalStorageDirectory(), EXT_ROOT);
        File ds = new File(root, "dshroot");
        int missing = 0, fixed = 0;
        for (Item it : items) {
            File f = new File(it.path);
            boolean ok = f.exists() && f.length() > 0;
            if (ok && it.keyword != null && it.path.endsWith(".js")) {
                ok = readContains(f, it.keyword);
            }
            if (ok) {
                sb.append("✅ ").append(it.name).append("\n");
            } else {
                missing++;
                sb.append("❌ ").append(it.name).append(" missing or damaged");
                if (it.path.endsWith(".js") || it.path.endsWith(".css") || it.path.endsWith(".html")) {
                    if (restoreAsset(it)) { fixed++; sb.append(" → repaired ✅"); }
                    else sb.append(" → repair failed ❌");
                }
                sb.append("\n");
            }
        }
        File nomedia = new File(root, ".nomedia");
        if (nomedia.exists()) {
            sb.append("✅ Media protection .nomedia (present; an empty file is expected)\n");
        } else {
            try { nomedia.createNewFile(); sb.append("✅ Media protection .nomedia missing → created\n"); fixed++; }
            catch (Exception e) { sb.append("❌ Could not create .nomedia\n"); }
        }
        File rev = new File(ds, "REVISION");
        String curRev = readText(rev);
        if (curRev == null || !curRev.equals(BUILTIN_REVISION)) {
                        sb.append("ℹ️ Version marker (").append(curRev == null ? "none" : curRev)
                            .append(") differs from bundled v1.3.3 (").append(BUILTIN_REVISION).append(")\n")
                            .append("  · This is normal if the main app has been upgraded; you can ignore it.\n")
                            .append("  · If startup fails or patches seem inactive, close and restart the main app to sync files.\n");
        }
        sb.append("\nMissing: ").append(missing).append(" · Repaired: ").append(fixed).append("\n");
        if (missing > 0 && fixed > 0) sb.append("⚠️ Close and restart the main app to complete synchronization.\n");
        return sb.toString();
    }

    private void checkArch(StringBuilder sb) {
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "?";
        sb.append("Architecture: ").append(abi);
        if (abi.startsWith("armeabi-v7a") || abi.startsWith("armeabi")) {
            sb.append(" ⚠️ 32-bit device; the arm64 Node engine cannot run on this device.\n");
        } else {
            sb.append(" ✅\n");
        }
    }

    private void checkMainApp(StringBuilder sb) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(MAIN_PKG, 0);
            sb.append("Main app: v").append(pi.versionName).append(" (versionCode ").append(pi.versionCode).append(")\n");
            if (pi.versionCode < 5) sb.append("⚠️ This version is outdated. Upgrade to v1.3.3 or later.\n");
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("Main app: not installed ❌\n");
        }
    }

    private void checkEngineSync(StringBuilder sb) {
        boolean ok = false;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", 3080), 1500);
            s.close();
            ok = true;
        } catch (Exception e) { ok = false; }
        sb.append(ok ? "Engine (3080): running ✅\n"
                 : "Engine (3080): not running or not responding\n→ Possible causes: engine crash, port conflict, or first-time extraction is incomplete.\n");
    }

    private boolean restoreAsset(Item it) {
        String assetName = null;
        if (it.path.endsWith("layout/client.js")) assetName = "fix-res/layout-client.js";
        else if (it.path.endsWith("cordis/client.js")) assetName = "fix-res/cordis-client.js";
        else if (it.path.endsWith("mobile.css")) assetName = "fix-res/mobile.css";
        else if (it.path.endsWith("mobile.js")) assetName = "fix-res/mobile.js";
        else if (it.path.endsWith("index.html")) assetName = "fix-res/index.html";
        else if (it.path.endsWith("dsh-tool-shizuku/lib/index.js")) assetName = "fix-res/shizuku-index.js";
        else if (it.path.endsWith("dsh-tool-android/lib/index.js")) assetName = "fix-res/android-index.js";
        if (assetName == null) return false;
        try {
            File target = new File(it.path);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream in = getAssets().open(assetName);
            FileOutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean readContains(File f, String kw) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int n = in.read(buf); in.close();
            return new String(buf, 0, n, "UTF-8").contains(kw);
        } catch (Exception e) { return false; }
    }

    private String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[128];
            int n = in.read(buf); in.close();
            return new String(buf, 0, n, "UTF-8").trim();
        } catch (Exception e) { return null; }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void log(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { if (log != null) log.append(s + "\n"); }
        });
    }
}
