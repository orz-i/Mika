/*
 * evinject — evdev 层触摸注入
 *
 * 目的：绕开 AccessibilityService.dispatchGesture() 注入事件的结构特征。
 *   MotionEventInjector 用 MotionEvent.obtain(downTime, when, action, x, y, 0) 造事件，
 *   结果是 deviceId=0、toolType=TOOL_TYPE_UNKNOWN(0)、eventId=-1。
 *   走 evdev，内核分配真实 deviceId / eventId，InputReader 推导
 *   source=SOURCE_TOUCHSCREEN、toolType=TOOL_TYPE_FINGER，与真手指同源。
 *
 * 编译（NDK，arm64）：
 *   aarch64-linux-android30-clang -Os -s -o evinject evinject.c
 *
 * 用法：
 *   evinject -l                                    列出输入设备（管道分隔，便于解析）
 *   evinject -c DEV                                检查设备可写（成功 exit 0）
 *   evinject DEV tap    X Y DUR                    单击，DUR 毫秒
 *   evinject DEV swipe  X1 Y1 X2 Y2 DUR            直线滑动
 *   evinject DEV stroke N t1 x1 y1 ... tN xN yN    按给定时刻走任意点列
 *
 * 坐标是设备物理坐标（ABS_MT_POSITION 范围），不是屏幕像素。
 *
 * 能力自适应：ABS_MT_PRESSURE / ABS_MT_TOUCH_MAJOR 的 maximum 为 0 时（本机 touchpanel 就是），
 * 一律不发该 ABS。写了超范围的值内核会静默丢掉整个 DOWN 序列——write 返回成功、屏幕毫无反应，
 * 是最难排查的一类失败。
 */

#include <linux/input.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>
#include <dirent.h>
#include <sys/ioctl.h>

static int fd = -1;
static int cap_major_max = 0;   /* ABS_MT_TOUCH_MAJOR maximum, 0 = 不支持 */
static int cap_pressure_max = 0;/* ABS_MT_PRESSURE maximum,   0 = 不支持 */
static int cap_tracking_max = 0;/* ABS_MT_TRACKING_ID maximum, 0 = 未定义 */

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void msleep(long ms) {
    if (ms <= 0) return;
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static int emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short)type;
    ev.code = (unsigned short)code;
    ev.value = value;
    ssize_t n = write(fd, &ev, sizeof(ev));
    return n == (ssize_t)sizeof(ev) ? 0 : -1;
}

static void syn(void) { emit(EV_SYN, SYN_REPORT, 0); }

static int rand_range(int lo, int hi) {
    if (hi <= lo) return lo;
    return lo + (int)(random() % (long)(hi - lo + 1));
}

static int cap_max(int f, int axis) {
    struct input_absinfo ai;
    if (ioctl(f, EVIOCGABS(axis), &ai) != 0) return -1;
    return ai.maximum;
}

static int open_dev(const char *path) {
    fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", path, strerror(errno));
        return -1;
    }
    cap_major_max = cap_max(fd, ABS_MT_TOUCH_MAJOR);
    cap_pressure_max = cap_max(fd, ABS_MT_PRESSURE);
    cap_tracking_max = cap_max(fd, ABS_MT_TRACKING_ID);
    if (cap_major_max < 0) cap_major_max = 0;
    if (cap_pressure_max < 0) cap_pressure_max = 0;
    if (cap_tracking_max <= 0) cap_tracking_max = 0;
    return 0;
}

/* 按下。只发设备真正支持的 ABS。 */
static void touch_down(int x, int y, int tid, int pressure) {
    emit(EV_ABS, ABS_MT_SLOT, 0);
    emit(EV_ABS, ABS_MT_TRACKING_ID, tid);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    if (cap_major_max > 0) emit(EV_ABS, ABS_MT_TOUCH_MAJOR, rand_range(3, 6));
    if (cap_pressure_max > 0) emit(EV_ABS, ABS_MT_PRESSURE, pressure);
    emit(EV_KEY, BTN_TOUCH, 1);
    emit(EV_KEY, BTN_TOOL_FINGER, 1);
    syn();
}

static void touch_move(int x, int y) {
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    syn();
}

static void touch_up(void) {
    emit(EV_ABS, ABS_MT_SLOT, 0);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit(EV_KEY, BTN_TOOL_FINGER, 0);
    emit(EV_KEY, BTN_TOUCH, 0);
    syn();
}

static int new_tracking_id(void) {
    int hi = cap_tracking_max > 0 ? cap_tracking_max : 32767;
    if (hi > 32767) hi = 32767;
    if (hi < 100) hi = 100;
    return rand_range(1, hi);
}

/* 单击：按住期间插 1~3 次微移，避免零位移特征 */
static int do_tap(int x, int y, int duration) {
    int tid = new_tracking_id();
    int pressure = rand_range(58, 90);
    int jx = x + rand_range(-2, 2);
    int jy = y + rand_range(-2, 2);

    touch_down(jx, jy, tid, pressure);

    int total = duration > 0 ? duration : 90;
    int steps = rand_range(1, 3);
    int per = total / (steps + 1);
    if (per < 1) per = 1;
    for (int i = 1; i <= steps; i++) {
        msleep(per + rand_range(-3, 3));
        touch_move(jx + rand_range(-1, 1), jy + rand_range(-1, 1));
    }
    msleep(per);
    touch_up();
    return 0;
}

/* 直线滑动，缓动曲线：起步慢、中间快、收尾慢 */
static int do_swipe(int x1, int y1, int x2, int y2, int duration) {
    int tid = new_tracking_id();
    int pressure = rand_range(55, 85);
    int steps = duration / 12;
    if (steps < 4) steps = 4;
    if (steps > 40) steps = 40;

    touch_down(x1, y1, tid, pressure);
    for (int i = 1; i <= steps; i++) {
        double p = (double)i / steps;
        double e = p < 0.5 ? 2 * p * p : 1 - 2 * (1 - p) * (1 - p);
        int cx = x1 + (int)((x2 - x1) * e) + rand_range(-1, 1);
        int cy = y1 + (int)((y2 - y1) * e) + rand_range(-1, 1);
        msleep(duration / steps + rand_range(-2, 2));
        touch_move(cx, cy);
    }
    msleep(rand_range(15, 40));
    touch_up();
    return 0;
}

/*
 * 按给定时刻走点列：t1 恒为 0（按下），最后一个点即抬起时刻与位置。
 * 与 Mika 的 GesturePacing 配合，可保留它的加速/减速与弧线，同时换成内核事件。
 */
static int do_stroke(int n, char **xs, char **ys, char **ts) {
    if (n < 2) return 2;
    int tid = new_tracking_id();
    int pressure = rand_range(58, 88);

    long *t = calloc((size_t)n, sizeof(long));
    int *px = calloc((size_t)n, sizeof(int));
    int *py = calloc((size_t)n, sizeof(int));
    if (!t || !px || !py) { free(t); free(px); free(py); return 3; }
    for (int i = 0; i < n; i++) {
        t[i] = atol(ts[i]);
        px[i] = atoi(xs[i]);
        py[i] = atoi(ys[i]);
    }

    long base = now_ms();
    touch_down(px[0], py[0], tid, pressure);
    for (int i = 1; i < n; i++) {
        long wait = base + t[i] - now_ms();
        if (wait > 0) msleep(wait);
        if (i == n - 1) touch_up();
        else touch_move(px[i], py[i]);
    }
    free(t); free(px); free(py);
    return 0;
}

/* 管道分隔，便于 Kotlin 侧解析 */
static void list_devices(void) {
    DIR *d = opendir("/dev/input");
    if (!d) { perror("opendir /dev/input"); return; }
    struct dirent *e;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int f = open(path, O_RDONLY | O_CLOEXEC);
        if (f < 0) continue;
        char name[128] = {0};
        ioctl(f, EVIOCGNAME(sizeof(name) - 1), name);
        /* 名字里的 '|' 会破坏格式 */
        for (char *p = name; *p; p++) if (*p == '|' || *p == '\n') *p = ' ';
        int xmax = cap_max(f, ABS_MT_POSITION_X);
        int ymax = cap_max(f, ABS_MT_POSITION_Y);
        unsigned long keybits[16];
        memset(keybits, 0, sizeof(keybits));
        int has_btn = 0;
        if (ioctl(f, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) >= 0) {
            has_btn = (keybits[BTN_TOUCH / (int)(8 * sizeof(long))] >> (BTN_TOUCH % (int)(8 * sizeof(long)))) & 1UL;
        }
        printf("%s|%s|%d|%d|%d\n", path, name, xmax, ymax, has_btn);
        close(f);
    }
    closedir(d);
}

int main(int argc, char **argv) {
    srandom((unsigned)(now_ms() ^ (long)getpid()));

    if (argc >= 2 && strcmp(argv[1], "-l") == 0) {
        list_devices();
        return 0;
    }
    if (argc >= 3 && strcmp(argv[1], "-c") == 0) {
        if (open_dev(argv[2]) != 0) return 1;
        close(fd);
        return 0;
    }
    if (argc < 3) {
        fprintf(stderr,
            "usage:\n"
            "  %s -l\n"
            "  %s -c DEV\n"
            "  %s DEV tap    X Y DUR\n"
            "  %s DEV swipe  X1 Y1 X2 Y2 DUR\n"
            "  %s DEV stroke N t1 x1 y1 ... tN xN yN\n",
            argv[0], argv[0], argv[0], argv[0], argv[0]);
        return 2;
    }

    const char *dev = argv[1];
    const char *cmd = argv[2];
    if (open_dev(dev) != 0) return 1;

    int rc;
    if (strcmp(cmd, "tap") == 0 && argc >= 5) {
        rc = do_tap(atoi(argv[3]), atoi(argv[4]), argc >= 6 ? atoi(argv[5]) : 0);
    } else if (strcmp(cmd, "swipe") == 0 && argc >= 7) {
        rc = do_swipe(atoi(argv[3]), atoi(argv[4]), atoi(argv[5]), atoi(argv[6]), atoi(argv[7]));
    } else if (strcmp(cmd, "stroke") == 0 && argc >= 6) {
        int n = atoi(argv[3]);
        if (n < 2 || argc < 4 + n * 3) { fprintf(stderr, "bad stroke args\n"); rc = 2; }
        else {
            /* 参数排布是 t x y 三元组 */
            char **ts = calloc((size_t)n, sizeof(char *));
            char **xs = calloc((size_t)n, sizeof(char *));
            char **ys = calloc((size_t)n, sizeof(char *));
            for (int i = 0; i < n; i++) {
                ts[i] = argv[4 + i * 3];
                xs[i] = argv[5 + i * 3];
                ys[i] = argv[6 + i * 3];
            }
            rc = do_stroke(n, xs, ys, ts);
            free(ts); free(xs); free(ys);
        }
    } else {
        fprintf(stderr, "bad command\n");
        rc = 2;
    }
    close(fd);
    return rc;
}
