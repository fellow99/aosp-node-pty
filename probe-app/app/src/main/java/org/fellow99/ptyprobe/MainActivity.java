package org.fellow99.ptyprobe;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * pty 设备回程探针载体（specs/101-probe-app）。
 *
 * 职责：把「载荷就位 / 加载 / 链接 / 版本记录」判据落到真实设备，产出三态
 * （PASS/FAIL/BLOCKED）+ files/probe-report.json（FR-101-PROBE-007）。
 *
 * - PRB-01 载荷就位：nativeLibraryDir/libpty_node.so 存在 + ELF64/AArch64 + sha==lock。
 * - PRB-02 预载 libnode.so：System.loadLibrary("node") 成功=PASS；无匹配 runtime 注入=BLOCKED。
 * - PRB-03 加载 addon：System.loadLibrary("pty_node") 成功=DT_NEEDED libnode.so 可解析（链接成立）；
 *   失败如实记录原因（not found / NODE_MODULE_VERSION / undefined symbol）。
 * - PRB-04 版本记录（fact）：lockActive(26.13.0/147) vs artifact(26.13.0/147) 配对一致 + 实际 sha vs lock。
 * - PRB-05 spawn 回程：⬜ 后续批次（前置：匹配 runtime + node-pty JS），恒 BLOCKED 不执行。
 *
 * 配对事实常量来源：pty.lock.json（active）+ 相同 Release v26.13.0 元数据（以 26.13.0 为准）。
 */
public class MainActivity extends Activity {

    // ── 配对事实（spec FR-101-PROBE-005；配对版本基准 = 26.13.0：lock active 与打包 artifact 均 26.13.0/147）──
    private static final String LOCK_ACTIVE_NODE = "26.13.0";
    private static final int    LOCK_ACTIVE_ABI  = 147; // NODE_MODULE_VERSION
    private static final String ARTIFACT_NODE    = "26.13.0";
    private static final int    ARTIFACT_ABI     = 147; // NODE_MODULE_VERSION（与 lock active 一致）
    private static final String ARTIFACT_SHA256  = "7f514ee596399f13b5965a391fc9c3ed27b3236a7fbcee13d51983c1e3285220";
    private static final long   ARTIFACT_SIZE    = 71120L;
    private static final String LIB_NAME         = "pty_node"; // System.loadLibrary 名（= libpty_node.so）
    private static final String SO_FILENAME      = "lib" + LIB_NAME + ".so";
    private static final String NODE_LIB         = "node";     // 预载引擎（消费方注入时）

    private TextView logView;
    private String nativeDir;
    private final StringBuilder uiLog = new StringBuilder();
    private final List<JSONObject> report = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nativeDir = getApplicationInfo().nativeLibraryDir;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Button run = new Button(this);
        run.setText("RUN ALL PROBES");
        run.setOnClickListener(v -> runAll());
        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setTextSize(11f);
        int pad = (int) (getResources().getDisplayMetrics().density * 8);
        logView.setPadding(pad, pad, pad, pad);
        scroll.addView(logView);
        root.addView(run, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        log("pty-probe-app ready\nnativeDir=" + nativeDir
                + "\n配对事实：lockActive=" + LOCK_ACTIVE_NODE + "/abi" + LOCK_ACTIVE_ABI
                + "  实际so=" + ARTIFACT_NODE + "/abi" + ARTIFACT_ABI
                + "（配对基准 26.13.0：lock active 与 artifact 一致 ⇒ spawn 前置就绪）"
                + "\n点击 RUN ALL PROBES 执行");

        // FR-101-PROBE-008：无头自动化——am start … --ez autorun true 启动即自动跑批
        if (getIntent().getBooleanExtra("autorun", false)) {
            log("autorun=true：启动即自动跑批");
            runAll();
        }
    }

    private void log(String s) {
        runOnUiThread(() -> {
            uiLog.append(s).append('\n');
            logView.setText(uiLog.toString());
        });
    }

    // ── 探针声明 ─────────────────────────────────────────────────────────────

    interface Check {
        String check(V v) throws Exception; // null=PASS；"BLOCKED:…"=BLOCKED；"PASS-…"=fact PASS；其余=FAIL
    }

    static final class V {
        final JSONObject o = new JSONObject();
    }

    static final class Probe {
        final String id, title;
        final long timeoutMs;
        final V v = new V();
        final Check check;
        Probe(String id, String title, long timeoutMs, Check check) {
            this.id = id; this.title = title; this.timeoutMs = timeoutMs; this.check = check;
        }
    }

    private File soFile() {
        return new File(nativeDir, SO_FILENAME);
    }

    private File nodeLibFile() {
        return new File(nativeDir, "lib" + NODE_LIB + ".so");
    }

    private List<Probe> probes() {
        List<Probe> l = new ArrayList<>();

        // PRB-01 载荷就位
        l.add(new Probe("PRB-01", "载荷就位（nativeLibraryDir/libpty_node.so，arm64）", 10_000, v -> {
            File f = soFile();
            v.o.put("path", f.getAbsolutePath());
            v.o.put("exists", f.isFile());
            if (!f.isFile()) return "libpty_node.so 不在 nativeLibraryDir";
            long size = f.length();
            v.o.put("size", size);
            if (size <= 0) return "size<=0: " + size;
            String arch = elfInfo(f);
            v.o.put("elf", arch);
            if (!arch.contains("class64=true") || !arch.contains("e_machine=183")) {
                return "ELF 非 AArch64： " + arch;
            }
            String sha = sha256Hex(f);
            v.o.put("sha256", sha);
            v.o.put("abis", String.join(",", Build.SUPPORTED_ABIS));
            boolean shaOk = ARTIFACT_SHA256.equalsIgnoreCase(sha);
            v.o.put("shaMatchesLock", shaOk);
            if (!shaOk) return "sha != lock(" + ARTIFACT_SHA256 + ")：" + sha;
            if (size != ARTIFACT_SIZE) return "size != lock(" + ARTIFACT_SIZE + ")：" + size;
            return null;
        }));

        // PRB-02 预载 libnode.so（消费方注入匹配 runtime 时 System.loadLibrary("node") 应成功）
        l.add(new Probe("PRB-02", "预载 libnode.so（System.loadLibrary(\"node\")）", 10_000, v -> {
            File nf = nodeLibFile();
            v.o.put("libnodePresent", nf.isFile());
            if (!nf.isFile()) {
                return "BLOCKED:无匹配 runtime 注入（libnode.so 未随 APK/未预载，DT_NEEDED 无法解析）";
            }
            try {
                System.loadLibrary(NODE_LIB);
                return null; // 预载成功 → 兼容性 PASS
            } catch (Throwable e) {
                return "FAIL:预载失败 " + e.getClass().getSimpleName() + ": " + msg(e);
            }
        }));

        // PRB-03 加载 addon：System.loadLibrary("pty_node") 成功 = DT_NEEDED libnode.so 可解析（链接成立）
        l.add(new Probe("PRB-03", "加载 addon（System.loadLibrary(\"pty_node\")，链接 DT_NEEDED libnode.so）",
                15_000, v -> {
            try {
                System.loadLibrary(LIB_NAME);
                v.o.put("loaded", true);
                return null; // 成功即链接成立
            } catch (Throwable e) {
                String m = msg(e);
                v.o.put("loaded", false);
                v.o.put("error", e.getClass().getSimpleName() + ": " + m);
                String lo = m.toLowerCase(Locale.ROOT);
                v.o.put("libnodePresent", nodeLibFile().isFile());
                if (lo.contains("not found") || lo.contains("libnode") || lo.contains("dlopen failed")) {
                    return "BLOCKED:无匹配 runtime 注入，" + e.getClass().getSimpleName() + ": " + m
                            + "（DT_NEEDED libnode.so 无法解析——加载需进程内预载同版本 libnode.so）";
                }
                if (lo.contains("node_module_version") || lo.contains("undefined symbol")
                        || lo.contains("napi_") || lo.contains("version")) {
                    return "FAIL:加载失败（版本/符号问题）：" + m + "（NODE_MODULE_VERSION: lockActive="
                            + LOCK_ACTIVE_ABI + " vs .so=" + ARTIFACT_ABI + "）";
                }
                return "FAIL:加载失败 " + e.getClass().getSimpleName() + ": " + m;
            }
        }));

        // PRB-04 版本配对记录（fact）：只记录，不断言正误
        l.add(new Probe("PRB-04", "版本记录（lock active vs 实际 .so，fact）", 10_000, v -> {
            v.o.put("lockActive", LOCK_ACTIVE_NODE + " (NODE_MODULE_VERSION=" + LOCK_ACTIVE_ABI + ")");
            v.o.put("artifact", ARTIFACT_NODE + " (NODE_MODULE_VERSION=" + ARTIFACT_ABI + ")");
            v.o.put("pairingMatched", LOCK_ACTIVE_ABI == ARTIFACT_ABI);
            v.o.put("conclusion", LOCK_ACTIVE_ABI == ARTIFACT_ABI
                    ? "配对版本一致，可跑 spawn 回程（依赖消费方 node-pty JS）"
                    : "NODE_MODULE_VERSION 不匹配（" + LOCK_ACTIVE_ABI + " vs " + ARTIFACT_ABI
                        + "）⇒ device require('node-pty') 会不匹配，spawn 回程 ⬜ 后续批次（本案配对 26.13.0 一致）");
            File f = soFile();
            String sha = f.isFile() ? sha256Hex(f) : "";
            v.o.put("actualSha256", sha);
            v.o.put("shaMatchesLockRecord", !sha.isEmpty() && ARTIFACT_SHA256.equalsIgnoreCase(sha));
            v.o.put("size", f.isFile() ? f.length() : -1);
            return "PASS-FACT|active=" + LOCK_ACTIVE_NODE + "(abi" + LOCK_ACTIVE_ABI + ") "
                    + "artifact=" + ARTIFACT_NODE + "(abi" + ARTIFACT_ABI + ") "
                    + "shaMatchLock=" + v.o.opt("shaMatchesLockRecord");
        }));

        // PRB-05 spawn 回程：进程内 node::Start(bootstrap.js) 跑消费方 node-pty JS 封装，
        // 物化 assets/node-pty → filesDir/node-pty（含 lib/ + package.json），并把
        // libpty_node.so 复制为 build/Release/pty.node（node-pty 的 loadNativeModule 查找路径）；
        // shim 启动前 setenv PTY_DIR/PTY_OUT；bootstrap 把 spawn 结果写入 PTY_OUT 供回读。
        l.add(new Probe("PRB-05", "spawn 回程（node-pty JS → sh -c 'echo PTY_ROUNDTRIP_OK'）", 90_000, v -> {
            String shimErr = ShimProbe.ensureLoaded();
            if (shimErr != null) return "FAIL:shim 加载失败 " + shimErr;
            try {
                File ptyRoot = new File(getFilesDir(), "node-pty");
                copyAssetTree(getAssets(), "node-pty", ptyRoot);
                File br = new File(ptyRoot, "build" + File.separator + "Release");
                if (!br.isDirectory() && !br.mkdirs()) return "FAIL:mkdir " + br;
                File ptyNode = new File(br, "pty.node");
                copyFile(soFile(), ptyNode);
                v.o.put("ptyRoot", ptyRoot.getAbsolutePath());
                v.o.put("ptyNodeSize", ptyNode.length());
                v.o.put("nativePtyNodeSize", soFile().length());
                File out = new File(getFilesDir(), "prb05-out.txt");
                File boot = new File(ptyRoot, "bootstrap.js");
                if (!boot.isFile()) return "FAIL:bootstrap.js 未物化";
                v.o.put("bootstrap", boot.getAbsolutePath());
                int rc = ShimProbe.runNodeScript(boot.getAbsolutePath(), out.getAbsolutePath(),
                        ptyRoot.getAbsolutePath(), out.getAbsolutePath());
                v.o.put("nodeStartRc", rc);
                if (rc != 0) return "FAIL:node::Start 退出码 " + rc;
                String content = out.isFile() ? new String(readAll(out), StandardCharsets.UTF_8) : "";
                v.o.put("roundtripOut", content);
                if (content.contains("PTY_ROUNDTRIP_OK")) {
                    return "PASS-FACT|" + truncate(content.replace('\n', ' '), 180);
                }
                return "FAIL:PTY_OUT 无 PTY_ROUNDTRIP_OK：" + truncate(content, 300);
            } catch (Throwable e) {
                return "FAIL:thrown " + e.getClass().getSimpleName() + ": " + msg(e);
            }
        }));

        return l;
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private static String msg(Throwable e) {
        return e.getMessage() == null ? "(no message)" : e.getMessage();
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    /** 读 ELF 头前 20 字节，返回描述串：magic/class64/le/e_machine(AArch64=183)。 */
    private static String elfInfo(File f) throws Exception {
        byte[] h = new byte[20];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(h);
            if (n < 20) return "too-small(" + n + ")";
        }
        boolean magic = h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        int cls = h[4] & 0xff;      // 2 = ELFCLASS64
        int data = h[5] & 0xff;     // 1 = little-endian
        int machine = (h[19] & 0xff) << 8 | (h[18] & 0xff); // LE uint16 @ offset 18
        return "magic=" + magic + " class64=" + (cls == 2) + " le=" + (data == 1)
                + " e_machine=" + machine;
    }

    /** 单探针超时执行：worker 线程 + join(timeoutMs)；异常/超时隔离为 FAIL。 */
    private String runProbe(Probe p) {
        AtomicReference<String> verdict = new AtomicReference<>("FAIL:no-verdict");
        Thread w = new Thread(() -> {
            try {
                verdict.set(p.check.check(p.v));
            } catch (Throwable e) {
                try {
                    p.v.o.put("thrown", e.getClass().getSimpleName() + ": " + msg(e));
                } catch (org.json.JSONException ignored) {
                }
                verdict.set("FAIL:thrown " + e.getClass().getSimpleName() + ": " + msg(e));
            }
        });
        w.setDaemon(true);
        w.start();
        try {
            w.join(p.timeoutMs);
        } catch (InterruptedException ignored) {
        }
        if (w.isAlive()) {
            w.interrupt();
            return "FAIL:timeout(>" + p.timeoutMs + "ms)";
        }
        return verdict.get();
    }

    // ── 主流程 ───────────────────────────────────────────────────────────────

    private void runAll() {
        new Thread(() -> {
            try {
                report.clear();
                int pass = 0, fail = 0, blocked = 0;
                for (Probe p : probes()) {
                    long t0 = System.currentTimeMillis();
                    String verdict = runProbe(p);
                    long ms = System.currentTimeMillis() - t0;
                    String tag = verdict == null ? "PASS"
                            : verdict.startsWith("BLOCKED") ? "BLOCKED"
                            : verdict.startsWith("PASS-") ? "PASS"
                            : "FAIL";
                    if ("PASS".equals(tag)) pass++;
                    else if ("BLOCKED".equals(tag)) blocked++;
                    else fail++;
                    JSONObject j = new JSONObject();
                    j.put("id", p.id).put("title", p.title).put("verdict", tag)
                            .put("detail", verdict == null ? "" : verdict)
                            .put("ms", ms);
                    if (p.v.o.length() > 0) j.put("values", p.v.o);
                    report.add(j);
                    log(String.format(Locale.ROOT, "%-8s %-8s %s%s", p.id, tag, p.title,
                            verdict == null ? "" : "  | " + truncate(verdict, 200)));
                }
                log(String.format(Locale.ROOT, "── 汇总：PASS=%d FAIL=%d BLOCKED=%d ──", pass, fail, blocked));

                JSONObject out = new JSONObject();
                out.put("runAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT)
                        .format(new Date()));
                out.put("device", Build.MODEL + " " + Build.VERSION.RELEASE
                        + " abi=" + String.join(",", Build.SUPPORTED_ABIS));
                out.put("abi", "arm64-v8a");
                out.put("expectedPairing", new JSONObject()
                        .put("lockActive", new JSONObject()
                                .put("node", LOCK_ACTIVE_NODE).put("nodeAbi", LOCK_ACTIVE_ABI))
                        .put("artifact", new JSONObject()
                                .put("node", ARTIFACT_NODE).put("nodeAbi", ARTIFACT_ABI)
                                .put("sha256", ARTIFACT_SHA256).put("size", ARTIFACT_SIZE)));
                out.put("probes", new JSONArray(report));
                out.put("spawnRoundtrip", "IMPLEMENTED: 进程内 node::Start 跑消费方 node-pty JS（物化 assets + build/Release/pty.node）");
                File f = new File(getFilesDir(), "probe-report.json");
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                log("报告：" + f.getAbsolutePath() + "（adb run-as pull 取回）");
            } catch (Exception e) {
                log("FATAL " + e);
            }
        }).start();
    }


    /** 递归物化 assets 子树到目标目录（空目录/叶子文件用 IO 流复制）。 */
    private static void copyAssetTree(android.content.res.AssetManager am, String prefix, File dest)
            throws Exception {
        String[] kids = am.list(prefix);
        if (kids == null || kids.length == 0) {
            try (InputStream in = am.open(prefix);
                 OutputStream out = new FileOutputStream(dest)) {
                copyStream(in, out);
            }
            return;
        }
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new java.io.IOException("mkdir failed: " + dest);
        }
        for (String k : kids) {
            copyAssetTree(am, prefix + "/" + k, new File(dest, k));
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            copyStream(in, bos);
            return bos.toByteArray();
        }
    }
    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
