package org.fellow99.ptyprobe;

/**
 * libprobe_shim.so 的 Java 面（specs/101-probe-app, FR-101-PROBE-005）。
 *
 * 垫片由 NDK r28 交叉编译（probe_shim.c，不链接 libnode、按名 dlsym），
 * 二进制不入库（probe-app/.gitignore）。编译命令见 runtime 仓 docs/probe-shim-build.md。
 */
public final class ShimProbe {

    private static boolean loaded;
    private static String loadError;

    private ShimProbe() {
    }

    /**
     * 幂等加载 libprobe_shim.so；返回 null = 成功，否则错误文本。
     */
    public static synchronized String ensureLoaded() {
        if (loaded) {
            return null;
        }
        try {
            System.loadLibrary("probe_shim");
            loaded = true;
            loadError = null;
            return null;
        } catch (Throwable t) {
            loadError = t.getClass().getName() + ": " + t.getMessage();
            return loadError;
        }
    }

    /** 最后一次失败的 dlerror 文本（供排障）。 */
    public static native String getDlError();

    /**
     * PRB-05 进程内执行（FR-101-PROBE-005）：在独立 32MB 栈线程调用
     * node::Start({node, scriptPath}) 跑 node-pty 回程；stdout/stderr 重定向到
     * outFile；启动前 setenv PTY_DIR/PTY_OUT（消费方 bootstrap.js 读取）。
     * 返回 node::Start 退出码（负值 = 垫片错误，见 probe_shim.c）。
     */
    public static native int runNodeScript(String scriptPath, String outFile,
                                           String ptyDir, String ptyOut);
}
