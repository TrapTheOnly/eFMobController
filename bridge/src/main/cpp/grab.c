#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <string.h>
#include <unistd.h>

#include <jni.h>

static int ufd = -1;
static int held;

static int emit(int type, int code, int value) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = type;
    event.code = code;
    event.value = value;
    if (write(ufd, &event, sizeof(event)) != (ssize_t) sizeof(event)) {
        return -errno;
    }
    return 0;
}

static int setup_abs(int code, int min, int max, int flat, int fuzz) {
    struct uinput_abs_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.code = code;
    setup.absinfo.minimum = min;
    setup.absinfo.maximum = max;
    setup.absinfo.flat = flat;
    setup.absinfo.fuzz = fuzz;
    if (ioctl(ufd, UI_ABS_SETUP, &setup) < 0) {
        return -errno;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_padlayer_bridge_BridgeMain_nativeGrabFd(JNIEnv* env, jclass clazz, jint fd) {
    int on = 1;
    (void) env;
    (void) clazz;
    if (ioctl(fd, EVIOCGRAB, on) < 0) {
        return -errno;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_padlayer_bridge_BridgeMain_nativeOpenVirtual(JNIEnv* env, jclass clazz) {
    static const int keys[] = {
            BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH, BTN_TL, BTN_TR, BTN_START, BTN_THUMBL};
    static const int axes[] = {
            ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y};
    struct uinput_setup setup;
    unsigned i;
    int rc;
    (void) env;
    (void) clazz;
    ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ufd < 0) {
        return -errno;
    }
    if (ioctl(ufd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(ufd, UI_SET_EVBIT, EV_ABS) < 0) {
        rc = -errno;
        close(ufd);
        ufd = -1;
        return rc;
    }
    for (i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
        if (ioctl(ufd, UI_SET_KEYBIT, keys[i]) < 0) {
            rc = -errno;
            close(ufd);
            ufd = -1;
            return rc;
        }
    }
    for (i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) {
        if (ioctl(ufd, UI_SET_ABSBIT, axes[i]) < 0) {
            rc = -errno;
            close(ufd);
            ufd = -1;
            return rc;
        }
    }
    rc = setup_abs(ABS_X, 0, 65535, 4095, 255);
    if (rc == 0) rc = setup_abs(ABS_Y, 0, 65535, 4095, 255);
    if (rc == 0) rc = setup_abs(ABS_Z, 0, 65535, 4095, 255);
    if (rc == 0) rc = setup_abs(ABS_RZ, 0, 65535, 4095, 255);
    if (rc == 0) rc = setup_abs(ABS_GAS, 0, 1023, 63, 3);
    if (rc == 0) rc = setup_abs(ABS_BRAKE, 0, 1023, 63, 3);
    if (rc == 0) rc = setup_abs(ABS_HAT0X, -1, 1, 0, 0);
    if (rc == 0) rc = setup_abs(ABS_HAT0Y, -1, 1, 0, 0);
    if (rc != 0) {
        close(ufd);
        ufd = -1;
        return rc;
    }
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_BLUETOOTH;
    setup.id.vendor = 0x045e;
    setup.id.product = 0x0b13;
    setup.id.version = 0x0101;
    strncpy(setup.name, "Padlayer Xbox", UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(ufd, UI_DEV_SETUP, &setup) < 0 || ioctl(ufd, UI_DEV_CREATE) < 0) {
        rc = -errno;
        close(ufd);
        ufd = -1;
        return rc;
    }
    held = 0;
    return 0;
}

static int stick_raw(float value) {
    if (value < -1.f) value = -1.f;
    if (value > 1.f) value = 1.f;
    return (int) ((value + 1.f) * 0.5f * 65535.f);
}

static int trigger_raw(float value) {
    if (value < 0.f) value = 0.f;
    if (value > 1.f) value = 1.f;
    return (int) (value * 1023.f);
}

JNIEXPORT jint JNICALL
Java_com_padlayer_bridge_BridgeMain_nativePush(JNIEnv* env, jclass clazz,
        jfloat lx, jfloat ly, jfloat rx, jfloat ry, jfloat lt, jfloat rt,
        jint hatX, jint hatY, jint buttons) {
    static const int keys[] = {
            BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH, BTN_TL, BTN_TR, BTN_START, BTN_THUMBL};
    int index;
    int rc;
    (void) env;
    (void) clazz;
    if (ufd < 0) {
        return -1;
    }
    for (index = 0; index < 8; index++) {
        int mask = 1 << index;
        int down = (buttons & mask) != 0;
        int was = (held & mask) != 0;
        if (down == was) {
            continue;
        }
        rc = emit(EV_KEY, keys[index], down ? 1 : 0);
        if (rc != 0) {
            return rc;
        }
    }
    held = buttons;
    rc = emit(EV_ABS, ABS_X, stick_raw(lx));
    if (rc == 0) rc = emit(EV_ABS, ABS_Y, stick_raw(-ly));
    if (rc == 0) rc = emit(EV_ABS, ABS_Z, stick_raw(rx));
    if (rc == 0) rc = emit(EV_ABS, ABS_RZ, stick_raw(-ry));
    if (rc == 0) rc = emit(EV_ABS, ABS_GAS, trigger_raw(rt));
    if (rc == 0) rc = emit(EV_ABS, ABS_BRAKE, trigger_raw(lt));
    if (rc == 0) rc = emit(EV_ABS, ABS_HAT0X, hatX);
    if (rc == 0) rc = emit(EV_ABS, ABS_HAT0Y, -hatY);
    if (rc == 0) rc = emit(EV_SYN, SYN_REPORT, 0);
    return rc;
}
