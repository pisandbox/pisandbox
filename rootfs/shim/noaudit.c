/* libpisandbox-noaudit.so — preloaded in the guest via LD_PRELOAD (set by the app for
 * the session; PRoot masks /etc/ld.so.preload with /dev/null).
 * Under PRoot on Android there is no kernel audit subsystem the app may talk to
 * (SELinux denies NETLINK_AUDIT; on desktop hosts it returns EPERM), so sudo/su
 * print "unable to send audit message" on every call. Make libaudit's
 * audit_open() report "audit not configured" (EPROTONOSUPPORT), which sudo and
 * PAM treat as normal and stay quiet about.
 * Built freestanding (no libc headers) so it can be cross-compiled with the
 * Android NDK's clang for aarch64-linux-gnu: see rootfs/build-rootfs.sh. */
extern int *__errno_location(void);
#define EPROTONOSUPPORT 93
__attribute__((visibility("default"))) int audit_open(void) {
    *__errno_location() = EPROTONOSUPPORT;
    return -1;
}
