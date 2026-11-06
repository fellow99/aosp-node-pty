/*
 * probe_shim.c —— pty 探针 PRB-05 进程内引导垫片（specs/101-probe-app, FR-101-PROBE-005）
 *
 * 只做一件事：在独立 32MB 栈线程上调用 node::Start(argv={node, scriptPath})，
 * stdio 重定向到 outFile，把消费方 node-pty JS 封装（assets 物化目录）跑起来。
 *
 * 关键决策（复刻 aosp-node-runtime 探针垫片纪律）：
 *  1) 不链接 libnode —— 符号一律按名 dlsym 解析（加载期由 System.loadLibrary("node")
 *     把 libnode.so 映射进进程后，dlsym(RTLD_DEFAULT,...) 命中全局符号表）。
 *  2) node::Start 每进程一次：g_dispatched 守卫，二次调用返回 -5。
 *  3) argv 是连续内存，argc 不含尾部 NULL 哨兵（node 的硬性要求）。
 *  4) 无 Android logging、零额外链接（dlopen/dlsym 在 bionic libc，API 21+ 无需 -ldl）。
 */

#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>

#define NODE_START_SYM "_ZN4node5StartEiPPc"

static char g_err[512] = {0};
static volatile int g_dispatched = 0;
static int g_run_rc = -1;
static char *g_run_script = NULL;

typedef int (*node_start_fn)(int, char **);

static void *run_node_thread(void *arg) {
    node_start_fn node_start = (node_start_fn)arg;
    static char *args[2];  /* 单次调用，静态即可 */
    args[0] = "node";
    args[1] = g_run_script;
    g_run_rc = node_start(2, args);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_org_fellow99_ptyprobe_ShimProbe_runNodeScript(JNIEnv *env, jclass clazz,
                                                     jstring jScript, jstring jOut,
                                                     jstring jPtyDir, jstring jPtyOut) {
    if (g_dispatched) {
        snprintf(g_err, sizeof(g_err), "node::Start 每进程一次（已调用过）");
        return -5;
    }
    const char *script = (*env)->GetStringUTFChars(env, jScript, NULL);
    const char *out = (*env)->GetStringUTFChars(env, jOut, NULL);
    const char *ptyDir = (*env)->GetStringUTFChars(env, jPtyDir, NULL);
    const char *ptyOut = (*env)->GetStringUTFChars(env, jPtyOut, NULL);
    if (!script || !out || !ptyDir || !ptyOut) return -2;

    /* 消费方视角 env：node::Start 启动时从 environ 快照 process.env，
     * 必须在 node 初始化前 setenv（bootstrap.js 依赖 PTY_DIR/PTY_OUT）。 */
    setenv("PTY_DIR", ptyDir, 1);
    setenv("PTY_OUT", ptyOut, 1);

    void *h = dlopen("libnode.so", RTLD_NOW | RTLD_GLOBAL);
    void *start = dlsym(h ? h : RTLD_DEFAULT, NODE_START_SYM);
    if (!start) {
        const char *de = dlerror();
        snprintf(g_err, sizeof(g_err), "dlsym(%s) 失败: %s", NODE_START_SYM, de ? de : "no detail");
        (*env)->ReleaseStringUTFChars(env, jScript, script);
        (*env)->ReleaseStringUTFChars(env, jOut, out);
        (*env)->ReleaseStringUTFChars(env, jPtyDir, ptyDir);
        (*env)->ReleaseStringUTFChars(env, jPtyOut, ptyOut);
        return -3;
    }

    int fd = open(out, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        snprintf(g_err, sizeof(g_err), "open(%s) 失败: %s", out, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jScript, script);
        (*env)->ReleaseStringUTFChars(env, jOut, out);
        (*env)->ReleaseStringUTFChars(env, jPtyDir, ptyDir);
        (*env)->ReleaseStringUTFChars(env, jPtyOut, ptyOut);
        return -4;
    }
    dup2(fd, 1);
    dup2(fd, 2);
    close(fd);
    fflush(NULL);

    g_run_script = strdup(script);
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 32 * 1024 * 1024);
    pthread_t tid;
    if (pthread_create(&tid, &attr, run_node_thread, start) != 0) {
        pthread_attr_destroy(&attr);
        snprintf(g_err, sizeof(g_err), "pthread_create 失败: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jScript, script);
        (*env)->ReleaseStringUTFChars(env, jOut, out);
        (*env)->ReleaseStringUTFChars(env, jPtyDir, ptyDir);
        (*env)->ReleaseStringUTFChars(env, jPtyOut, ptyOut);
        return -6;
    }
    pthread_attr_destroy(&attr);
    void *ignored;
    pthread_join(tid, &ignored);
    g_dispatched = 1;

    free(g_run_script);
    (*env)->ReleaseStringUTFChars(env, jScript, script);
    (*env)->ReleaseStringUTFChars(env, jOut, out);
    (*env)->ReleaseStringUTFChars(env, jPtyDir, ptyDir);
    (*env)->ReleaseStringUTFChars(env, jPtyOut, ptyOut);
    return g_run_rc;
}

JNIEXPORT jstring JNICALL
Java_org_fellow99_ptyprobe_ShimProbe_getDlError(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, g_err[0] ? g_err : "no dlerror captured");
}
