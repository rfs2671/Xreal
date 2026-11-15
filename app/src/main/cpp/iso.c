// Isochronous IN streaming over usbdevfs for the diagnostic's UVC reader.
// Android's Java USB API has no isochronous transfers, so the URBs are submitted
// directly on the file descriptor of an already-opened UsbDeviceConnection.
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

typedef struct {
    int fd;
    int n_urbs;
    int pkts;
    int pkt_size;
    struct usbdevfs_urb **urbs;
} iso_t;

static size_t urb_size(int pkts) {
    return sizeof(struct usbdevfs_urb) + (size_t)pkts * sizeof(struct usbdevfs_iso_packet_desc);
}

static void prime(iso_t *s, struct usbdevfs_urb *u) {
    for (int i = 0; i < s->pkts; i++) {
        u->iso_frame_desc[i].length = (unsigned)s->pkt_size;
        u->iso_frame_desc[i].actual_length = 0;
        u->iso_frame_desc[i].status = 0;
    }
    u->actual_length = 0;
    u->error_count = 0;
    u->status = 0;
}

static void destroy(iso_t *s) {
    if (!s) return;
    if (s->urbs) {
        for (int i = 0; i < s->n_urbs; i++) {
            if (s->urbs[i]) { free(s->urbs[i]->buffer); free(s->urbs[i]); }
        }
        free(s->urbs);
    }
    free(s);
}

// Returns a handle, or -errno.
JNIEXPORT jlong JNICALL Java_dev_xrealdiag_usb_IsoNative_start(
        JNIEnv *env, jclass cls, jint fd, jint endpoint, jint pkt_size, jint pkts, jint n_urbs) {
    (void)env; (void)cls;
    if (pkt_size <= 0 || pkts <= 0 || n_urbs <= 0) return -EINVAL;
    iso_t *s = calloc(1, sizeof(*s));
    if (!s) return -ENOMEM;
    s->fd = fd; s->n_urbs = n_urbs; s->pkts = pkts; s->pkt_size = pkt_size;
    s->urbs = calloc((size_t)n_urbs, sizeof(*s->urbs));
    if (!s->urbs) { destroy(s); return -ENOMEM; }
    for (int i = 0; i < n_urbs; i++) {
        struct usbdevfs_urb *u = calloc(1, urb_size(pkts));
        if (!u) { destroy(s); return -ENOMEM; }
        s->urbs[i] = u;
        u->buffer = malloc((size_t)pkts * (size_t)pkt_size);
        if (!u->buffer) { destroy(s); return -ENOMEM; }
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char)endpoint;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer_length = pkts * pkt_size;
        u->number_of_packets = pkts;
        prime(s, u);
    }
    for (int i = 0; i < n_urbs; i++) {
        if (ioctl(fd, USBDEVFS_SUBMITURB, s->urbs[i]) < 0) {
            int e = errno;
            for (int j = 0; j < i; j++) ioctl(fd, USBDEVFS_DISCARDURB, s->urbs[j]);
            // Discarded URBs must be reaped before their memory is released.
            for (int j = 0; j < i; j++) {
                struct pollfd pfd = {.fd = fd, .events = POLLOUT};
                void *r = NULL;
                if (poll(&pfd, 1, 100) <= 0 || ioctl(fd, USBDEVFS_REAPURBNDELAY, &r) < 0) break;
            }
            destroy(s);
            return -e;
        }
    }
    return (jlong)(intptr_t)s;
}

// Reaps one URB. Packet payloads are concatenated into `out`; meta[0] = packet count,
// meta[1] = packets with a non-zero status, meta[2..] = each packet's length.
// Returns bytes copied, 0 on timeout, or -errno.
JNIEXPORT jint JNICALL Java_dev_xrealdiag_usb_IsoNative_reap(
        JNIEnv *env, jclass cls, jlong handle, jobject out, jintArray meta, jint timeout_ms) {
    (void)cls;
    iso_t *s = (iso_t *)(intptr_t)handle;
    if (!s) return -EINVAL;
    uint8_t *dst = (*env)->GetDirectBufferAddress(env, out);
    jlong cap = (*env)->GetDirectBufferCapacity(env, out);
    if (!dst || cap < (jlong)s->pkts * s->pkt_size) return -EINVAL;
    if ((*env)->GetArrayLength(env, meta) < s->pkts + 2) return -EINVAL;

    struct pollfd pfd = {.fd = s->fd, .events = POLLOUT};
    int pr = poll(&pfd, 1, timeout_ms);
    if (pr < 0) return errno == EINTR ? 0 : -errno;
    if (pr == 0) return 0;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
        if (!(pfd.revents & POLLOUT)) return -ENODEV;
    }
    struct usbdevfs_urb *u = NULL;
    if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) < 0) return errno == EAGAIN ? 0 : -errno;
    if (!u) return 0;

    jint m[2] = {0, 0};
    jint *lens = (*env)->GetIntArrayElements(env, meta, NULL);
    if (!lens) return -ENOMEM;
    int total = 0, count = 0, bad = 0;
    const uint8_t *src = u->buffer;
    for (int i = 0; i < u->number_of_packets; i++) {
        const struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[i];
        if (d->status != 0) bad++;
        else if (d->actual_length > 0 && d->actual_length <= (unsigned)s->pkt_size) {
            memcpy(dst + total, src + (size_t)i * s->pkt_size, d->actual_length);
            lens[2 + count++] = (jint)d->actual_length;
            total += (int)d->actual_length;
        }
    }
    m[0] = count; m[1] = bad;
    lens[0] = m[0]; lens[1] = m[1];
    (*env)->ReleaseIntArrayElements(env, meta, lens, 0);

    prime(s, u);
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    return total;
}

JNIEXPORT void JNICALL Java_dev_xrealdiag_usb_IsoNative_stop(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    iso_t *s = (iso_t *)(intptr_t)handle;
    if (!s) return;
    for (int i = 0; i < s->n_urbs; i++) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    // Reap whatever the kernel still holds so it does not write into freed memory.
    for (int i = 0; i < s->n_urbs * 2; i++) {
        struct pollfd pfd = {.fd = s->fd, .events = POLLOUT};
        if (poll(&pfd, 1, 100) <= 0) break;
        void *r = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &r) < 0) break;
    }
    destroy(s);
}
