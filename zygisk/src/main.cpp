#include "zygisk.hpp"

using size_t = unsigned long;
using ssize_t = long;
using mode_t = unsigned int;
using off_t = long;
using socklen_t = unsigned int;
using uint8_t = unsigned char;
using uint16_t = unsigned short;
using uint32_t = unsigned int;
using int32_t = int;
using int64_t = long long;
using uintptr_t = unsigned long;

extern "C" {
struct sockaddr {
  unsigned short sa_family;
  char sa_data[14];
};
struct sockaddr_nl {
  unsigned short nl_family;
  unsigned short nl_pad;
  uint32_t nl_pid;
  uint32_t nl_groups;
};
struct ifaddrs {
  struct ifaddrs *ifa_next;
  char *ifa_name;
  unsigned int ifa_flags;
  struct sockaddr *ifa_addr;
  struct sockaddr *ifa_netmask;
  union {
    struct sockaddr *ifu_broadaddr;
    struct sockaddr *ifu_dstaddr;
  } ifa_ifu;
  void *ifa_data;
};
struct ifmap {
  unsigned long mem_start;
  unsigned long mem_end;
  unsigned short base_addr;
  unsigned char irq;
  unsigned char dma;
  unsigned char port;
};
struct ifreq {
  char ifr_name[16];
  union {
    struct sockaddr ifr_addr;
    struct sockaddr ifr_dstaddr;
    struct sockaddr ifr_broadaddr;
    struct sockaddr ifr_netmask;
    struct sockaddr ifr_hwaddr;
    short ifr_flags;
    int ifr_ifindex;
    int ifr_mtu;
    struct ifmap ifr_map;
    char ifr_slave[16];
    char ifr_newname[16];
    char *ifr_data;
  } ifr_ifru;
};
struct ifconf {
  int ifc_len;
  union {
    char *ifcu_buf;
    struct ifreq *ifcu_req;
  } ifc_ifcu;
};
struct iovec {
  void *iov_base;
  size_t iov_len;
};
struct msghdr {
  void *msg_name;
  socklen_t msg_namelen;
  struct iovec *msg_iov;
  size_t msg_iovlen;
  void *msg_control;
  size_t msg_controllen;
  int msg_flags;
};
struct nlmsghdr {
  uint32_t nlmsg_len;
  uint16_t nlmsg_type;
  uint16_t nlmsg_flags;
  uint32_t nlmsg_seq;
  uint32_t nlmsg_pid;
};
struct ifinfomsg {
  uint8_t ifi_family;
  uint8_t __ifi_pad;
  uint16_t ifi_type;
  int32_t ifi_index;
  uint32_t ifi_flags;
  uint32_t ifi_change;
};
struct ifaddrmsg {
  uint8_t ifa_family;
  uint8_t ifa_prefixlen;
  uint8_t ifa_flags;
  uint8_t ifa_scope;
  uint32_t ifa_index;
};
struct rtmsg {
  uint8_t rtm_family;
  uint8_t rtm_dst_len;
  uint8_t rtm_src_len;
  uint8_t rtm_tos;
  uint8_t rtm_table;
  uint8_t rtm_protocol;
  uint8_t rtm_scope;
  uint8_t rtm_type;
  uint32_t rtm_flags;
};
struct rtattr {
  uint16_t rta_len;
  uint16_t rta_type;
};
struct DIR;
struct FILE;
struct stat;
struct stat64;
struct statx;
struct dirent_like {
  unsigned long long d_ino;
  long long d_off;
  unsigned short d_reclen;
  unsigned char d_type;
  char d_name[256];
};

int open(const char *pathname, int flags, ...);
int openat(int dirfd, const char *pathname, int flags, ...);
int close(int fd);
ssize_t read(int fd, void *buf, size_t count);
ssize_t write(int fd, const void *buf, size_t count);
ssize_t readlink(const char *pathname, char *buf, size_t bufsiz);
ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
int access(const char *pathname, int mode);
int faccessat(int dirfd, const char *pathname, int mode, int flags);
int stat(const char *pathname, struct stat *buf);
int stat64(const char *pathname, struct stat64 *buf);
int lstat(const char *pathname, struct stat *buf);
int lstat64(const char *pathname, struct stat64 *buf);
int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
int fstatat64(int dirfd, const char *pathname, struct stat64 *buf, int flags);
int statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *buf);
off_t lseek(int fd, off_t offset, int whence);
int ioctl(int fd, unsigned long request, ...);
ssize_t recvmsg(int sockfd, struct msghdr *msg, int flags);
ssize_t recv(int sockfd, void *buf, size_t len, int flags);
ssize_t recvfrom(int sockfd, void *buf, size_t len, int flags, struct sockaddr *src_addr, socklen_t *addrlen);
ssize_t readv(int fd, const struct iovec *iov, int iovcnt);
__attribute__((weak)) int __android_log_print(int prio, const char *tag, const char *fmt, ...);
DIR *opendir(const char *name);
struct dirent_like *readdir(DIR *dirp);
struct dirent_like *readdir64(DIR *dirp);
int closedir(DIR *dirp);
FILE *fopen(const char *pathname, const char *mode);
FILE *fdopen(int fd, const char *mode);
int getsockname(int sockfd, struct sockaddr *addr, socklen_t *addrlen);
int getsockopt(int sockfd, int level, int optname, void *optval, socklen_t *optlen);
long syscall(long number, ...);
unsigned int if_nametoindex(const char *ifname);
char *if_indextoname(unsigned int ifindex, char *ifname);
int snprintf(char *str, size_t size, const char *format, ...);
size_t strlen(const char *s);
int strcmp(const char *s1, const char *s2);
int strncmp(const char *s1, const char *s2, size_t n);
char *strstr(const char *haystack, const char *needle);
char *strchr(const char *s, int c);
void *memcpy(void *dest, const void *src, size_t n);
void *memmove(void *dest, const void *src, size_t n);
void *memset(void *s, int c, size_t n);
long time(long *tloc);
int *__errno(void);
}

#ifndef O_RDONLY
#define O_RDONLY 0
#endif
#ifndef O_WRONLY
#define O_WRONLY 1
#endif
#ifndef O_RDWR
#define O_RDWR 2
#endif
#ifndef O_ACCMODE
#define O_ACCMODE 3
#endif
#ifndef O_CREAT
#define O_CREAT 0100
#endif
#ifndef O_TRUNC
#define O_TRUNC 01000
#endif
#ifndef O_CLOEXEC
#define O_CLOEXEC 02000000
#endif
#ifndef O_NONBLOCK
#define O_NONBLOCK 04000
#endif
#ifndef AT_FDCWD
#define AT_FDCWD -100
#endif
#ifndef SEEK_SET
#define SEEK_SET 0
#endif
#ifndef ENODEV
#define ENODEV 19
#endif
#ifndef ENOENT
#define ENOENT 2
#endif
#ifndef AF_NETLINK
#define AF_NETLINK 16
#endif
#ifndef SOL_SOCKET
#define SOL_SOCKET 1
#endif
#ifndef SO_DOMAIN
#define SO_DOMAIN 39
#endif
#ifndef __NR_inotify_init1
#define __NR_inotify_init1 26
#endif
#ifndef __NR_inotify_add_watch
#define __NR_inotify_add_watch 27
#endif
#ifndef __NR_memfd_create
#define __NR_memfd_create 279
#endif
#ifndef __NR_mprotect
#define __NR_mprotect 226
#endif
#ifndef __NR_mmap
#define __NR_mmap 222
#endif
#ifndef __NR_munmap
#define __NR_munmap 215
#endif
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MSG_TRUNC
#define MSG_TRUNC 0x20
#endif
#ifndef PROT_READ
#define PROT_READ 0x1
#endif
#ifndef PROT_WRITE
#define PROT_WRITE 0x2
#endif
#ifndef PROT_EXEC
#define PROT_EXEC 0x4
#endif
#ifndef MAP_PRIVATE
#define MAP_PRIVATE 0x02
#endif
#ifndef MAP_ANONYMOUS
#define MAP_ANONYMOUS 0x20
#endif

#define IN_ACCESS       0x00000001
#define IN_MODIFY       0x00000002
#define IN_ATTRIB       0x00000004
#define IN_CLOSE_WRITE  0x00000008
#define IN_MOVED_TO     0x00000080
#define IN_CREATE       0x00000100
#define IN_DELETE       0x00000200
#define IN_DELETE_SELF  0x00000400
#define IN_MOVE_SELF    0x00000800
#define ZDT_INOTIFY_MASK (IN_MODIFY | IN_ATTRIB | IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_DELETE | IN_DELETE_SELF | IN_MOVE_SELF)

#define SIOCGIFNAME   0x8910
#define SIOCGIFCONF   0x8912
#define SIOCGIFFLAGS  0x8913
#define SIOCGIFADDR   0x8915
#define SIOCGIFDSTADDR 0x8917
#define SIOCGIFBRDADDR 0x8919
#define SIOCGIFNETMASK 0x891B
#define SIOCGIFMTU    0x8921
#define SIOCGIFHWADDR 0x8927
#define SIOCGIFINDEX  0x8933

#define RTM_NEWLINK 16
#define RTM_NEWADDR 20
#define RTM_NEWROUTE 24
#define IFLA_IFNAME 3
#define RTA_IIF 3
#define RTA_OIF 4
#define NLMSG_ALIGNTO 4U
#define RTA_ALIGNTO 4U
#define NLMSG_ALIGN(len) (((len) + NLMSG_ALIGNTO - 1U) & ~(NLMSG_ALIGNTO - 1U))
#define RTA_ALIGN(len) (((len) + RTA_ALIGNTO - 1U) & ~(RTA_ALIGNTO - 1U))

#define va_list __builtin_va_list
#define va_start __builtin_va_start
#define va_arg __builtin_va_arg
#define va_end __builtin_va_end


// Minimal JNI access used only for reading AppSpecializeArgs::nice_name.
// Keep this local to avoid depending on the platform jni.h in lightweight builds.
using jsize = int;
struct JNIEnv { const void *functions; };

namespace {
constexpr int MAX_INTERFACES = 32;
constexpr int IFACE_LEN = 32;
constexpr int READ_LIMIT = 65536;
constexpr int MODULE_FD_NONE = -1;
constexpr int MAX_TRACKED_DIRS = 16;
constexpr int MAX_HIDDEN_INDICES = 32;
constexpr int MAX_LATE_PATCHED_LIBS = 256;
constexpr int INLINE_STUB_SIZE = 16;
constexpr int INLINE_TRAMPOLINE_SIZE = 64;
constexpr int INLINE_BRANCH_FOLLOW_LIMIT = 6;
constexpr int PAGE_SIZE = 4096;

using GetIfAddrsFn = int (*)(ifaddrs **);
using OpenFn = int (*)(const char *, int, ...);
using OpenAtFn = int (*)(int, const char *, int, ...);
using IoctlFn = int (*)(int, unsigned long, ...);
using RecvmsgFn = ssize_t (*)(int, msghdr *, int);
using RecvFn = ssize_t (*)(int, void *, size_t, int);
using RecvfromFn = ssize_t (*)(int, void *, size_t, int, sockaddr *, socklen_t *);
using ReadFn = ssize_t (*)(int, void *, size_t);
using ReadvFn = ssize_t (*)(int, const iovec *, int);
using OpendirFn = DIR *(*)(const char *);
using ReaddirFn = dirent_like *(*)(DIR *);
using ClosedirFn = int (*)(DIR *);
using FopenFn = FILE *(*)(const char *, const char *);
using IfNameToIndexFn = unsigned int (*)(const char *);
using IfIndexToNameFn = char *(*)(unsigned int, char *);
using AccessFn = int (*)(const char *, int);
using FaccessatFn = int (*)(int, const char *, int, int);
using StatFn = int (*)(const char *, struct stat *);
using Stat64Fn = int (*)(const char *, struct stat64 *);
using FstatatFn = int (*)(int, const char *, struct stat *, int);
using Fstatat64Fn = int (*)(int, const char *, struct stat64 *, int);
using StatxFn = int (*)(int, const char *, int, unsigned int, struct statx *);
using ReadlinkFn = ssize_t (*)(const char *, char *, size_t);
using ReadlinkatFn = ssize_t (*)(int, const char *, char *, size_t);
using DlopenFn = void *(*)(const char *, int);
using AndroidDlopenExtFn = void *(*)(const char *, int, const void *);
using RegisterNativesFn = int (*)(JNIEnv *, jobject, const JNINativeMethod *, int);

static GetIfAddrsFn orig_getifaddrs = nullptr;
static OpenFn orig_open = nullptr;
static OpenAtFn orig_openat = nullptr;
static IoctlFn orig_ioctl = nullptr;
static RecvmsgFn orig_recvmsg = nullptr;
static RecvFn orig_recv = nullptr;
static RecvfromFn orig_recvfrom = nullptr;
static ReadFn orig_read = nullptr;
static ReadvFn orig_readv = nullptr;
static OpendirFn orig_opendir = nullptr;
static ReaddirFn orig_readdir = nullptr;
static ReaddirFn orig_readdir64 = nullptr;
static ClosedirFn orig_closedir = nullptr;
static FopenFn orig_fopen = nullptr;
static FopenFn orig_fopen64 = nullptr;
static IfNameToIndexFn orig_if_nametoindex = nullptr;
static IfIndexToNameFn orig_if_indextoname = nullptr;
static AccessFn orig_access = nullptr;
static FaccessatFn orig_faccessat = nullptr;
static StatFn orig_stat = nullptr;
static Stat64Fn orig_stat64 = nullptr;
static StatFn orig_lstat = nullptr;
static Stat64Fn orig_lstat64 = nullptr;
static FstatatFn orig_fstatat = nullptr;
static Fstatat64Fn orig_fstatat64 = nullptr;
static StatxFn orig_statx = nullptr;
static ReadlinkFn orig_readlink = nullptr;
static ReadlinkatFn orig_readlinkat = nullptr;
static DlopenFn orig_dlopen = nullptr;
static AndroidDlopenExtFn orig_android_dlopen_ext = nullptr;
static RegisterNativesFn orig_RegisterNatives = nullptr;

static bool g_enabled = false;
static bool g_target = false;
static bool g_should_install_hooks = false;
static int g_module_fd = MODULE_FD_NONE;
static int g_uid = -1;
static char g_interfaces[MAX_INTERFACES][IFACE_LEN] = {};
static int g_interface_count = 0;
static unsigned int g_hidden_indices[MAX_HIDDEN_INDICES] = {};
static int g_hidden_index_count = 0;
static dev_t g_late_patched_devs[MAX_LATE_PATCHED_LIBS] = {};
static ino_t g_late_patched_inos[MAX_LATE_PATCHED_LIBS] = {};
static uintptr_t g_late_patched_bases[MAX_LATE_PATCHED_LIBS] = {};
static int g_late_patched_count = 0;
static int g_late_hook_passes = 0;
static int g_late_hook_patches = 0;
static int g_inline_hooks_installed = 0;
static int g_inline_hooks_failed = 0;
static int g_inline_symbols_found = 0;
static int g_inline_symbols_missing = 0;
static bool g_libc_inline_ready = false;
static bool g_libdl_inline_ready = false;
static bool g_jni_register_natives_hooked = false;
static DIR *g_tracked_dirs[MAX_TRACKED_DIRS] = {};
static int g_tracked_dir_count = 0;
static int g_maps_elf_count = 0;
static int g_registered_hooks = 0;
static int g_commit_ok = 0;
static int g_getifaddrs_hits = 0;
static int g_proc_hits = 0;
static int g_ioctl_hits = 0;
static int g_netlink_hits = 0;
static bool g_start_enabled = false;
static bool g_proxyinfo_enabled = false;
static bool g_feature_hide_getifaddrs = true;
static bool g_feature_hide_ifindex = true;
static bool g_feature_hide_ioctl = true;
static bool g_feature_hide_proc_net = true;
static bool g_feature_hide_sys_class_net = true;
static bool g_feature_hide_netlink_link = true;
static bool g_feature_hide_netlink_addr = true;
static bool g_feature_hide_netlink_route = true;
static bool g_feature_late_hooking = true;
static bool g_feature_libc_inline_hooking = true;
static bool g_feature_dynamic_refresh = false;
static bool g_feature_tunnel_name_fallback = true;
static bool g_uid_runtime_allowed = false;
static long g_last_reload_at = 0;
static int g_current_ttl = 2;
static bool g_runtime_loaded_once = false;
static int g_inotify_fd = -1;
static int g_inotify_watch_count = 0;
static bool g_inotify_dirty = false;
static long g_last_inotify_check_at = 0;
static char g_process_name[256] = {};
static bool g_is_child_zygote = false;
static bool g_isolated_uid = false;
static __thread int g_hook_depth = 0;
static __thread bool g_inside_getifaddrs = false;

constexpr int RUNTIME_TTL_FAST = 2;
constexpr int RUNTIME_TTL_NORMAL = 10;
constexpr int RUNTIME_TTL_MAX = 30;
constexpr int INOTIFY_DRAIN_INTERVAL = 1;
constexpr const char *ABS_TARGETS_PATH = "/data/adb/modules/ZDT-D/working_folder/proxyInfo/out_program";
constexpr const char *ABS_START_PATH = "/data/adb/modules/ZDT-D/setting/start.json";
constexpr const char *ABS_PROXYINFO_ENABLED_PATH = "/data/adb/modules/ZDT-D/working_folder/proxyInfo/enabled.json";
constexpr const char *ABS_APPLIED_PATH = "/data/adb/modules/ZDT-D/working_folder/vpn_netd/applied.json";
constexpr const char *ABS_SETTING_DIR = "/data/adb/modules/ZDT-D/setting";
constexpr const char *ABS_PROXYINFO_DIR = "/data/adb/modules/ZDT-D/working_folder/proxyInfo";
constexpr const char *ABS_VPN_NETD_DIR = "/data/adb/modules/ZDT-D/working_folder/vpn_netd";

int hooked_getifaddrs(ifaddrs **out);
int hooked_open(const char *pathname, int flags, ...);
int hooked_openat(int dirfd, const char *pathname, int flags, ...);
int hooked_ioctl(int fd, unsigned long request, ...);
ssize_t hooked_recvmsg(int sockfd, msghdr *msg, int flags);
ssize_t hooked_recv(int sockfd, void *buf, size_t len, int flags);
ssize_t hooked_recvfrom(int sockfd, void *buf, size_t len, int flags, sockaddr *src_addr, socklen_t *addrlen);
ssize_t hooked_read(int fd, void *buf, size_t count);
ssize_t hooked_readv(int fd, const iovec *iov, int iovcnt);
DIR *hooked_opendir(const char *name);
dirent_like *hooked_readdir(DIR *dirp);
dirent_like *hooked_readdir64(DIR *dirp);
int hooked_closedir(DIR *dirp);
FILE *hooked_fopen(const char *pathname, const char *mode);
FILE *hooked_fopen64(const char *pathname, const char *mode);
unsigned int hooked_if_nametoindex(const char *ifname);
char *hooked_if_indextoname(unsigned int ifindex, char *ifname);
int hooked_access(const char *pathname, int mode);
int hooked_faccessat(int dirfd, const char *pathname, int mode, int flags);
int hooked_stat(const char *pathname, struct stat *buf);
int hooked_stat64(const char *pathname, struct stat64 *buf);
int hooked_lstat(const char *pathname, struct stat *buf);
int hooked_lstat64(const char *pathname, struct stat64 *buf);
int hooked_fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
int hooked_fstatat64(int dirfd, const char *pathname, struct stat64 *buf, int flags);
int hooked_statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *buf);
ssize_t hooked_readlink(const char *pathname, char *buf, size_t bufsiz);
ssize_t hooked_readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
void *hooked_dlopen(const char *filename, int flags);
void *hooked_android_dlopen_ext(const char *filename, int flags, const void *extinfo);
int hooked_RegisterNatives(JNIEnv *env, jobject clazz, const JNINativeMethod *methods, int num_methods);
int hook_late_loaded_libraries();
bool install_jni_register_natives_hook(JNIEnv *env);
int install_libdl_inline_hooks();

int *errno_ptr() { return (&__errno) ? __errno() : nullptr; }
void set_errno_value(int value) { int *e = errno_ptr(); if (e) *e = value; }

bool is_space(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

void trim(char *s) {
  if (!s) return;
  char *start = s;
  while (*start && is_space(*start)) start++;
  if (start != s) {
    char *d = s;
    while ((*d++ = *start++)) {}
  }
  int n = static_cast<int>(strlen(s));
  while (n > 0 && is_space(s[n - 1])) s[--n] = 0;
}

bool streq(const char *a, const char *b) { return a && b && strcmp(a, b) == 0; }
bool starts_with(const char *s, const char *prefix) {
  if (!s || !prefix) return false;
  while (*prefix) {
    if (*s++ != *prefix++) return false;
  }
  return true;
}

constexpr int JNI_GET_STRING_UTF_CHARS_INDEX = 169;
constexpr int JNI_RELEASE_STRING_UTF_CHARS_INDEX = 170;
using GetStringUtfCharsFn = const char *(*)(JNIEnv *, jstring, jboolean *);
using ReleaseStringUtfCharsFn = void (*)(JNIEnv *, jstring, const char *);

bool copy_jstring_utf(JNIEnv *env, jstring value, char *out, int cap) {
  if (!out || cap <= 1) return false;
  out[0] = 0;
  if (!env || !value || !env->functions) return false;
  void *const *table = reinterpret_cast<void *const *>(const_cast<void *>(env->functions));
  if (!table) return false;
  auto get_chars = reinterpret_cast<GetStringUtfCharsFn>(table[JNI_GET_STRING_UTF_CHARS_INDEX]);
  auto release_chars = reinterpret_cast<ReleaseStringUtfCharsFn>(table[JNI_RELEASE_STRING_UTF_CHARS_INDEX]);
  if (!get_chars || !release_chars) return false;
  jboolean is_copy = 0;
  const char *utf = get_chars(env, value, &is_copy);
  if (!utf) return false;
  int n = 0;
  while (utf[n] && n < cap - 1) {
    out[n] = utf[n];
    n++;
  }
  out[n] = 0;
  release_chars(env, value, utf);
  return n > 0;
}

bool is_forbidden_process_name(const char *process_name) {
  if (!process_name || !*process_name) return false;
  if (streq(process_name, "com.android.zdtd.service") || starts_with(process_name, "com.android.zdtd.service:")) return true;
  if (strstr(process_name, "webview_zygote") != nullptr) return true;
  if (strstr(process_name, "app_zygote") != nullptr) return true;
  if (strstr(process_name, "sandboxed_process") != nullptr) return true;
  if (strstr(process_name, "renderer") != nullptr) return true;
  if (strstr(process_name, "com.android.chrome") != nullptr) return true;
  if (strstr(process_name, "com.google.android.webview") != nullptr) return true;
  if (strstr(process_name, "com.android.webview") != nullptr) return true;
  if (strstr(process_name, "com.google.android.trichromelibrary") != nullptr) return true;
  if (strstr(process_name, "org.chromium") != nullptr) return true;
  return false;
}

bool is_android_isolated_uid(int uid) {
  // Android isolated processes, including many WebView/renderer processes, are assigned
  // UIDs from the AID_ISOLATED range. They are not regular app package processes and
  // should never receive ZDT-D interface-hiding hooks.
  return uid >= 99000 && uid <= 99999;
}

bool is_child_zygote_args(const zygisk::AppSpecializeArgs *args) {
  return args && args->is_child_zygote && *(args->is_child_zygote);
}

int read_file_at(int dirfd, const char *path, char *buf, int cap) {
  if (!path || !buf || cap <= 1) return -1;
  int fd = -1;
  if (orig_openat) fd = orig_openat(dirfd, path, O_RDONLY | O_CLOEXEC);
  else fd = openat(dirfd, path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  int total = 0;
  while (total < cap - 1) {
    ssize_t n = read(fd, buf + total, static_cast<size_t>(cap - 1 - total));
    if (n <= 0) break;
    total += static_cast<int>(n);
  }
  close(fd);
  buf[total] = 0;
  return total;
}

int read_file_module_or_abs(int dirfd, const char *rel_path, const char *abs_path, char *buf, int cap) {
  int n = -1;
  if (dirfd >= 0 && rel_path) n = read_file_at(dirfd, rel_path, buf, cap);
  if (n > 0) return n;
  if (abs_path) n = read_file_at(AT_FDCWD, abs_path, buf, cap);
  return n;
}

void close_module_fd_if_open() {
  if (g_module_fd >= 0) {
    close(g_module_fd);
    g_module_fd = MODULE_FD_NONE;
  }
}

void close_runtime_watcher() {
  if (g_inotify_fd >= 0) {
    close(g_inotify_fd);
    g_inotify_fd = -1;
  }
  g_inotify_watch_count = 0;
  g_inotify_dirty = false;
  g_last_inotify_check_at = 0;
}

void add_runtime_watch(const char *path) {
  if (g_inotify_fd < 0 || !path || !*path) return;
  long wd = syscall(__NR_inotify_add_watch, g_inotify_fd, path, ZDT_INOTIFY_MASK);
  if (wd >= 0) g_inotify_watch_count++;
}

void init_runtime_watcher() {
  if (g_inotify_fd >= 0) return;
  long fd = syscall(__NR_inotify_init1, O_CLOEXEC | O_NONBLOCK);
  if (fd < 0) return;
  g_inotify_fd = static_cast<int>(fd);

  // Watch both files and parent directories. Parent directory watches catch
  // atomic tmp+rename updates, while file watches catch direct writes. All
  // watched paths are existing ZDT-D runtime paths; Zygisk remains read-only.
  add_runtime_watch(ABS_SETTING_DIR);
  add_runtime_watch(ABS_PROXYINFO_DIR);
  add_runtime_watch(ABS_VPN_NETD_DIR);
  add_runtime_watch(ABS_START_PATH);
  add_runtime_watch(ABS_PROXYINFO_ENABLED_PATH);
  add_runtime_watch(ABS_TARGETS_PATH);
  add_runtime_watch(ABS_APPLIED_PATH);

  if (g_inotify_watch_count <= 0) close_runtime_watcher();
}

bool drain_runtime_watcher(bool force) {
  if (g_inotify_fd < 0) return false;
  long now = time(nullptr);
  if (!force && g_last_inotify_check_at > 0 && (now - g_last_inotify_check_at) < INOTIFY_DRAIN_INTERVAL) {
    return g_inotify_dirty;
  }
  g_last_inotify_check_at = now;

  char buf[1024];
  bool saw_event = false;
  for (int i = 0; i < 8; ++i) {
    ssize_t n = read(g_inotify_fd, buf, sizeof(buf));
    if (n <= 0) break;
    saw_event = true;
    if (n < static_cast<ssize_t>(sizeof(buf))) break;
  }
  if (saw_event) g_inotify_dirty = true;
  return g_inotify_dirty;
}

bool enter_hook_guard() {
  if (g_hook_depth > 0) return false;
  g_hook_depth++;
  return true;
}

void leave_hook_guard() {
  if (g_hook_depth > 0) g_hook_depth--;
}

bool parse_positive_int(const char *s, int *out) {
  if (!s || !*s || !out) return false;
  long value = 0;
  for (int i = 0; s[i]; ++i) {
    if (s[i] < '0' || s[i] > '9') return false;
    value = value * 10 + static_cast<long>(s[i] - '0');
    if (value > 2147483647L) return false;
  }
  if (value <= 0) return false;
  *out = static_cast<int>(value);
  return true;
}

bool uid_in_out_program(const char *raw, int uid) {
  if (!raw || uid <= 0) return false;
  const char *p = raw;
  while (*p) {
    const char *line = p;
    const char *end = strchr(line, '\n');
    int len = end ? static_cast<int>(end - line) : static_cast<int>(strlen(line));
    if (len > 0 && len < 256) {
      char tmp[256];
      for (int i = 0; i < len; ++i) tmp[i] = line[i];
      tmp[len] = 0;
      trim(tmp);
      if (tmp[0] && tmp[0] != '#') {
        char *eq = strchr(tmp, '=');
        if (eq) {
          char *digits = eq + 1;
          trim(digits);
          int parsed_uid = -1;
          if (parse_positive_int(digits, &parsed_uid) && parsed_uid == uid) return true;
        }
      }
    }
    if (!end) break;
    p = end + 1;
  }
  return false;
}


void add_interface_name(const char *start, int len);
void parse_applied_interfaces_json(const char *json);
void refresh_hidden_indices();
bool json_string_value_after_key(const char *json, const char *key, char *out, int out_cap, const char **end_out);
void copy_cstr(char *dst, int cap, const char *src);

bool json_enabled_value_true(const char *json) {
  if (!json) return false;
  const char *p = strstr(json, "\"enabled\"");
  if (!p) return false;
  const char *colon = strchr(p, ':');
  if (!colon) return false;
  const char *q = colon + 1;
  while (*q && is_space(*q)) q++;
  return strncmp(q, "true", 4) == 0;
}

int feature_flags_mask() {
  int mask = 0;
  if (g_feature_hide_getifaddrs) mask |= 1 << 0;
  if (g_feature_hide_ifindex) mask |= 1 << 1;
  if (g_feature_hide_ioctl) mask |= 1 << 2;
  if (g_feature_hide_proc_net) mask |= 1 << 3;
  if (g_feature_hide_sys_class_net) mask |= 1 << 4;
  if (g_feature_hide_netlink_link) mask |= 1 << 5;
  if (g_feature_hide_netlink_addr) mask |= 1 << 6;
  if (g_feature_hide_netlink_route) mask |= 1 << 7;
  if (g_feature_late_hooking) mask |= 1 << 8;
  if (g_feature_libc_inline_hooking) mask |= 1 << 9;
  if (g_feature_dynamic_refresh) mask |= 1 << 11;
  if (g_feature_tunnel_name_fallback) mask |= 1 << 12;
  return mask;
}

void reset_feature_flags_to_legacy_defaults() {
  // Built-in strong background mode. No per-Zygisk settings file is used.
  // proxyInfo/enabled.json remains compatible with the rest of ZDT-D and is
  // still used only for its original enabled=true/false switch.
  g_feature_hide_getifaddrs = true;
  g_feature_hide_ifindex = true;
  g_feature_hide_ioctl = true;
  g_feature_hide_proc_net = true;
  g_feature_hide_sys_class_net = true;
  g_feature_hide_netlink_link = true;
  g_feature_hide_netlink_addr = true;
  g_feature_hide_netlink_route = true;
  g_feature_late_hooking = true;
  g_feature_libc_inline_hooking = true;
  g_feature_dynamic_refresh = false;
  g_feature_tunnel_name_fallback = true;
}

void parse_runtime_feature_flags_json(const char *json) {
  // Keep working_folder/proxyInfo/enabled.json compatible with the existing
  // project logic. Zygisk does not read extra options from this file.
  (void)json;
}

bool any_netlink_feature_enabled() {
  return g_feature_hide_netlink_link || g_feature_hide_netlink_addr || g_feature_hide_netlink_route;
}

bool interfaces_equal_snapshot(int old_count, char old_names[MAX_INTERFACES][IFACE_LEN]) {
  if (old_count != g_interface_count) return false;
  for (int i = 0; i < old_count; ++i) {
    bool found = false;
    for (int j = 0; j < g_interface_count; ++j) {
      if (streq(old_names[i], g_interfaces[j])) { found = true; break; }
    }
    if (!found) return false;
  }
  return true;
}

void update_adaptive_ttl(bool changed) {
  if (changed || g_current_ttl < RUNTIME_TTL_FAST) {
    g_current_ttl = RUNTIME_TTL_FAST;
    return;
  }
  if (g_current_ttl < RUNTIME_TTL_NORMAL) g_current_ttl = RUNTIME_TTL_NORMAL;
  else g_current_ttl = RUNTIME_TTL_MAX;
}

bool reload_runtime_state(bool force, const char *phase) {
  if (!g_target) {
    g_enabled = false;
    return false;
  }

  if (g_feature_dynamic_refresh) {
    if (!force && drain_runtime_watcher(false)) force = true;
  }

  long now = time(nullptr);
  if (!force && g_runtime_loaded_once && g_last_reload_at > 0 && (now - g_last_reload_at) < g_current_ttl) {
    return g_enabled;
  }

  bool old_enabled = g_enabled;
  bool old_start = g_start_enabled;
  bool old_proxy = g_proxyinfo_enabled;
  bool old_uid_allowed = g_uid_runtime_allowed;
  int old_features = feature_flags_mask();
  int old_count = g_interface_count;
  char old_names[MAX_INTERFACES][IFACE_LEN];
  for (int i = 0; i < MAX_INTERFACES; ++i) {
    for (int j = 0; j < IFACE_LEN; ++j) old_names[i][j] = g_interfaces[i][j];
  }

  static char targets[READ_LIMIT];
  static char start_json[4096];
  static char proxy_json[4096];
  static char applied_json[READ_LIMIT];

  int target_len = read_file_module_or_abs(g_module_fd, "working_folder/proxyInfo/out_program", ABS_TARGETS_PATH, targets, READ_LIMIT);
  int start_len = read_file_module_or_abs(g_module_fd, "setting/start.json", ABS_START_PATH, start_json, sizeof(start_json));
  int proxy_len = read_file_module_or_abs(g_module_fd, "working_folder/proxyInfo/enabled.json", ABS_PROXYINFO_ENABLED_PATH, proxy_json, sizeof(proxy_json));
  int applied_len = read_file_module_or_abs(g_module_fd, "working_folder/vpn_netd/applied.json", ABS_APPLIED_PATH, applied_json, READ_LIMIT);

  // If the app namespace cannot see /data/adb/modules after the module fd was
  // closed, keep the last good in-memory state instead of disabling protection.
  // The first load still happens through getModuleDir() before the fd is closed.
  if (target_len > 0) g_uid_runtime_allowed = uid_in_out_program(targets, g_uid);
  else g_uid_runtime_allowed = old_uid_allowed;

  if (start_len > 0) g_start_enabled = json_enabled_value_true(start_json);
  else g_start_enabled = old_start;

  if (proxy_len > 0) {
    g_proxyinfo_enabled = json_enabled_value_true(proxy_json);
    parse_runtime_feature_flags_json(proxy_json);
  } else {
    g_proxyinfo_enabled = old_proxy;
  }

  if (!g_runtime_loaded_once) {
    reset_feature_flags_to_legacy_defaults();
  }

  if (applied_len > 0) {
    parse_applied_interfaces_json(applied_json);
  } else if (g_runtime_loaded_once) {
    g_interface_count = old_count;
    for (int i = 0; i < MAX_INTERFACES; ++i) {
      for (int j = 0; j < IFACE_LEN; ++j) g_interfaces[i][j] = old_names[i][j];
    }
  } else {
    parse_applied_interfaces_json(nullptr);
  }

  g_enabled = g_uid_runtime_allowed && g_start_enabled && g_proxyinfo_enabled && (g_interface_count > 0 || g_feature_tunnel_name_fallback);
  if (g_feature_dynamic_refresh) init_runtime_watcher();
  else close_runtime_watcher();

  bool changed = old_enabled != g_enabled || old_start != g_start_enabled || old_proxy != g_proxyinfo_enabled || old_uid_allowed != g_uid_runtime_allowed || old_features != feature_flags_mask() || !interfaces_equal_snapshot(old_count, old_names);
  update_adaptive_ttl(changed);
  g_last_reload_at = now;
  g_runtime_loaded_once = true;
  g_inotify_dirty = false;
  if (orig_if_nametoindex) refresh_hidden_indices();
  (void)force;
  (void)phase;
  return g_enabled;
}

bool runtime_hiding_enabled() {
  return reload_runtime_state(false, nullptr);
}

bool json_string_value_after_key(const char *json, const char *key, char *out, int out_cap, const char **end_out) {
  if (end_out) *end_out = nullptr;
  if (!json || !key || !out || out_cap <= 1) return false;
  char pattern[96];
  snprintf(pattern, sizeof(pattern), "\"%s\"", key);
  const char *p = strstr(json, pattern);
  if (!p) return false;
  const char *colon = strchr(p, ':');
  if (!colon) return false;
  const char *q = colon + 1;
  while (*q && is_space(*q)) q++;
  if (*q != '\"') return false;
  q++;
  int n = 0;
  while (*q && *q != '\"' && n < out_cap - 1) {
    if (*q == '\\' && q[1]) q++;
    out[n++] = *q++;
  }
  out[n] = 0;
  if (*q != '\"') return false;
  if (end_out) *end_out = q + 1;
  return n > 0;
}

bool owner_program_is_vpn(const char *owner) {
  return streq(owner, "openvpn") ||
         streq(owner, "tun2socks") ||
         streq(owner, "tun2proxy") ||
         streq(owner, "myvpn") ||
         streq(owner, "mihomo") ||
         streq(owner, "amneziawg") ||
         streq(owner, "mieru");
}

bool json_key_is_interface_name_key(const char *key) {
  return streq(key, "tun") || streq(key, "interface") || streq(key, "ifname") || streq(key, "name");
}

void parse_interface_keys_in_range(const char *start, const char *end, bool allow_name_key) {
  if (!start || !end || start >= end) return;
  const char *p = start;
  while (p < end && g_interface_count < MAX_INTERFACES) {
    const char *quote = strchr(p, '"');
    if (!quote || quote >= end) break;
    const char *key_start = quote + 1;
    const char *key_end = strchr(key_start, '"');
    if (!key_end || key_end >= end) break;
    int key_len = static_cast<int>(key_end - key_start);
    if (key_len > 0 && key_len < 32) {
      char key[32];
      for (int i = 0; i < key_len; ++i) key[i] = key_start[i];
      key[key_len] = 0;
      bool accepted_key = json_key_is_interface_name_key(key) && (allow_name_key || !streq(key, "name"));
      if (accepted_key) {
        const char *colon = strchr(key_end + 1, ':');
        if (colon && colon < end) {
          const char *value = colon + 1;
          while (value < end && is_space(*value)) value++;
          if (value < end && *value == '"') {
            value++;
            const char *value_end = value;
            while (value_end < end && *value_end && *value_end != '"') {
              if (*value_end == '\\' && value_end + 1 < end) value_end++;
              value_end++;
            }
            if (value_end <= end) add_interface_name(value, static_cast<int>(value_end - value));
            p = value_end < end ? value_end + 1 : end;
            continue;
          }
        }
      }
    }
    p = key_end + 1;
  }
}

void parse_applied_interfaces_json(const char *json) {
  g_interface_count = 0;
  if (!json) return;
  // Dynamic extension path: any valid interface field written by the daemon into
  // applied.json is accepted. owner_program is now compatibility metadata only,
  // so future components do not require a Zygisk native-code change.
  parse_interface_keys_in_range(json, json + strlen(json), false);
  if (g_interface_count > 0) return;

  // Legacy fallback for older applied.json layouts that grouped a tun field under
  // known owner_program objects.
  const char *p = json;
  while ((p = strstr(p, "\"owner_program\"")) != nullptr && g_interface_count < MAX_INTERFACES) {
    char owner[48];
    const char *after_owner = nullptr;
    if (!json_string_value_after_key(p, "owner_program", owner, sizeof(owner), &after_owner)) {
      p += 15;
      continue;
    }
    if (!owner_program_is_vpn(owner)) {
      p = after_owner ? after_owner : p + 15;
      continue;
    }

    const char *next_owner = strstr(after_owner ? after_owner : p + 15, "\"owner_program\"");
    const char *tun_key = strstr(after_owner ? after_owner : p + 15, "\"tun\"");
    if (tun_key && (!next_owner || tun_key < next_owner)) {
      char tun[IFACE_LEN];
      const char *after_tun = nullptr;
      if (json_string_value_after_key(tun_key, "tun", tun, sizeof(tun), &after_tun)) {
        add_interface_name(tun, static_cast<int>(strlen(tun)));
        p = after_tun ? after_tun : tun_key + 5;
        continue;
      }
    }
    p = after_owner ? after_owner : p + 15;
  }
}

bool safe_iface_char(char c) {
  return (c >= 'a' && c <= 'z') ||
         (c >= 'A' && c <= 'Z') ||
         (c >= '0' && c <= '9') ||
         c == '_' || c == '-' || c == '.' || c == ':';
}

void add_interface_name(const char *start, int len) {
  if (!start || len <= 0 || len >= IFACE_LEN || g_interface_count >= MAX_INTERFACES) return;
  char name[IFACE_LEN];
  for (int i = 0; i < len; ++i) {
    char c = start[i];
    if (!safe_iface_char(c)) return;
    if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    name[i] = c;
  }
  name[len] = 0;
  if (streq(name, "0.0.0.0")) return;
  for (int i = 0; i < g_interface_count; ++i) if (streq(g_interfaces[i], name)) return;
  for (int i = 0; i <= len; ++i) g_interfaces[g_interface_count][i] = name[i];
  g_interface_count++;
}

bool all_digits_after_prefix(const char *s, int prefix_len) {
  if (!s) return false;
  int i = prefix_len;
  if (!s[i]) return false;
  for (; s[i]; ++i) {
    if (s[i] < '0' || s[i] > '9') return false;
  }
  return true;
}

bool contains_ascii(const char *s, const char *needle) {
  return s && needle && strstr(s, needle) != nullptr;
}

bool looks_like_tunnel_interface_name(const char *name) {
  if (!name || !*name) return false;
  char clean[IFACE_LEN];
  int i = 0;
  for (; name[i] && i < IFACE_LEN - 1; ++i) {
    char c = name[i];
    if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    if (!safe_iface_char(c)) return false;
    clean[i] = c;
  }
  clean[i] = 0;
  if (!clean[0]) return false;

  // Fallback is only used inside already selected target UIDs. It covers the
  // common tunnel families in case applied.json is stale, incomplete, or uses a
  // field name unknown to this native layer. Do not match normal mobile/Wi-Fi
  // interfaces such as wlan0, rmnet_data0, ccmni0, eth0, lo.
  if (starts_with(clean, "tun")) return true;      // tun0, tun1, tunl0
  if (starts_with(clean, "tap")) return true;
  if (starts_with(clean, "wg")) return true;       // wg0
  if (starts_with(clean, "awg")) return true;      // awg0
  if (starts_with(clean, "utun")) return true;
  if (starts_with(clean, "ppp")) return true;
  if (starts_with(clean, "ipsec")) return true;
  if (starts_with(clean, "xfrm")) return true;
  if (starts_with(clean, "l2tp")) return true;
  if (starts_with(clean, "gre")) return true;
  if (starts_with(clean, "amneziawg")) return true;
  if (contains_ascii(clean, "vpn")) return true;
  if (all_digits_after_prefix(clean, 2) && clean[0] == 'i' && clean[1] == 'f') return true; // if1, if27
  return false;
}

bool is_hidden_interface(const char *name) {
  reload_runtime_state(false, nullptr);
  if (!g_enabled || !name || !*name) return false;
  char clean[IFACE_LEN];
  int i = 0;
  for (; name[i] && i < IFACE_LEN - 1; ++i) {
    char c = name[i];
    if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    clean[i] = c;
  }
  clean[i] = 0;
  for (int idx = 0; idx < g_interface_count; ++idx) if (streq(clean, g_interfaces[idx])) return true;
  if (g_feature_tunnel_name_fallback && looks_like_tunnel_interface_name(clean)) return true;
  return false;
}

bool is_hidden_index(unsigned int idx) {
  reload_runtime_state(false, nullptr);
  if (!g_enabled || idx == 0) return false;
  for (int i = 0; i < g_hidden_index_count; ++i) if (g_hidden_indices[i] == idx) return true;
  if (g_feature_tunnel_name_fallback && orig_if_indextoname) {
    char ifname[IFACE_LEN];
    memset(ifname, 0, sizeof(ifname));
    if (orig_if_indextoname(idx, ifname) && looks_like_tunnel_interface_name(ifname)) return true;
  }
  return false;
}

void refresh_hidden_indices() {
  g_hidden_index_count = 0;
  for (int i = 0; i < g_interface_count && g_hidden_index_count < MAX_HIDDEN_INDICES; ++i) {
    unsigned int idx = orig_if_nametoindex ? orig_if_nametoindex(g_interfaces[i]) : if_nametoindex(g_interfaces[i]);
    if (idx == 0) continue;
    bool exists = false;
    for (int j = 0; j < g_hidden_index_count; ++j) if (g_hidden_indices[j] == idx) exists = true;
    if (!exists) g_hidden_indices[g_hidden_index_count++] = idx;
  }
}

unsigned long long parse_hex_u64(const char *s, const char **end_out) {
  unsigned long long value = 0;
  const char *p = s;
  while (*p) {
    char c = *p;
    int v = -1;
    if (c >= '0' && c <= '9') v = c - '0';
    else if (c >= 'a' && c <= 'f') v = c - 'a' + 10;
    else if (c >= 'A' && c <= 'F') v = c - 'A' + 10;
    else break;
    value = (value << 4) | static_cast<unsigned long long>(v);
    p++;
  }
  if (end_out) *end_out = p;
  return value;
}

unsigned long long parse_dec_u64(const char *s, const char **end_out) {
  unsigned long long value = 0;
  const char *p = s;
  while (*p >= '0' && *p <= '9') {
    value = value * 10ULL + static_cast<unsigned long long>(*p - '0');
    p++;
  }
  if (end_out) *end_out = p;
  return value;
}

dev_t make_dev_id(unsigned long long major, unsigned long long minor) {
  return static_cast<dev_t>(((major & 0xfffULL) << 8) | (minor & 0xffULL) | ((minor & ~0xffULL) << 12));
}

const char *skip_token(const char *p) {
  while (*p && !is_space(*p)) p++;
  while (*p && is_space(*p)) p++;
  return p;
}

bool seen_dev_inode(dev_t *devs, ino_t *inos, int count, dev_t dev, ino_t ino) {
  for (int i = 0; i < count; ++i) if (devs[i] == dev && inos[i] == ino) return true;
  return false;
}

enum ProcKind { PROC_NONE = 0, PROC_DEV, PROC_ROUTE, PROC_IF_INET6, PROC_IPV6_ROUTE };

ProcKind classify_proc_path(const char *path) {
  if (!path) return PROC_NONE;
  if (streq(path, "/proc/net/dev") || streq(path, "/proc/self/net/dev")) return PROC_DEV;
  if (streq(path, "/proc/net/route") || streq(path, "/proc/self/net/route")) return PROC_ROUTE;
  if (streq(path, "/proc/net/if_inet6") || streq(path, "/proc/self/net/if_inet6")) return PROC_IF_INET6;
  if (streq(path, "/proc/net/ipv6_route") || streq(path, "/proc/self/net/ipv6_route")) return PROC_IPV6_ROUTE;
  return PROC_NONE;
}

ProcKind classify_proc_relative_name(const char *path) {
  if (!path || strchr(path, '/')) return PROC_NONE;
  if (streq(path, "dev")) return PROC_DEV;
  if (streq(path, "route")) return PROC_ROUTE;
  if (streq(path, "if_inet6")) return PROC_IF_INET6;
  if (streq(path, "ipv6_route")) return PROC_IPV6_ROUTE;
  return PROC_NONE;
}

bool fd_points_to_path(int fd, const char *target_a, const char *target_b) {
  if (fd < 0) return false;
  char link_path[64];
  snprintf(link_path, sizeof(link_path), "/proc/self/fd/%d", fd);
  char resolved[256];
  ssize_t n = readlink(link_path, resolved, sizeof(resolved) - 1);
  if (n <= 0) return false;
  resolved[n] = 0;
  return streq(resolved, target_a) || (target_b && streq(resolved, target_b));
}

bool fd_is_proc_net_dir(int fd) {
  return fd_points_to_path(fd, "/proc/net", "/proc/self/net");
}

bool line_has_hidden_dev_iface(const char *line, int len) {
  int start = 0;
  while (start < len && is_space(line[start])) start++;
  int end = start;
  while (end < len && line[end] != ':' && !is_space(line[end])) end++;
  if (end <= start || end - start >= IFACE_LEN) return false;
  char name[IFACE_LEN];
  for (int i = start; i < end; ++i) name[i - start] = line[i];
  name[end - start] = 0;
  return is_hidden_interface(name);
}

bool line_has_hidden_route_iface(const char *line, int len) {
  int start = 0;
  while (start < len && is_space(line[start])) start++;
  int end = start;
  while (end < len && !is_space(line[end])) end++;
  if (end <= start || end - start >= IFACE_LEN) return false;
  char name[IFACE_LEN];
  for (int i = start; i < end; ++i) name[i - start] = line[i];
  name[end - start] = 0;
  return is_hidden_interface(name);
}

bool line_has_hidden_ifinet6_iface(const char *line, int len) {
  int end = len;
  while (end > 0 && is_space(line[end - 1])) end--;
  int start = end;
  while (start > 0 && !is_space(line[start - 1])) start--;
  if (end <= start || end - start >= IFACE_LEN) return false;
  char name[IFACE_LEN];
  for (int i = start; i < end; ++i) name[i - start] = line[i];
  name[end - start] = 0;
  return is_hidden_interface(name);
}

int filter_proc_text(const char *in, int in_len, char *out, int out_cap, ProcKind kind) {
  if (!in || !out || out_cap <= 1) return 0;
  int written = 0;
  int pos = 0;
  int line_no = 0;
  while (pos < in_len && written < out_cap - 1) {
    int start = pos;
    while (pos < in_len && in[pos] != '\n') pos++;
    int end = pos;
    if (pos < in_len && in[pos] == '\n') pos++;
    int line_len = end - start;
    bool hide = false;
    if (kind == PROC_DEV) hide = line_no >= 2 && line_has_hidden_dev_iface(in + start, line_len);
    else if (kind == PROC_ROUTE) hide = line_no >= 1 && line_has_hidden_route_iface(in + start, line_len);
    else if (kind == PROC_IF_INET6 || kind == PROC_IPV6_ROUTE) hide = line_has_hidden_ifinet6_iface(in + start, line_len);
    if (!hide) {
      int copy_len = pos - start;
      if (written + copy_len > out_cap - 1) copy_len = out_cap - 1 - written;
      if (copy_len > 0) {
        memcpy(out + written, in + start, static_cast<size_t>(copy_len));
        written += copy_len;
      }
    }
    line_no++;
  }
  out[written] = 0;
  return written;
}

int make_memfd_from_text(const char *name, const char *text, int len) {
  if (!name || !text || len < 0) return -1;
  int fd = static_cast<int>(syscall(__NR_memfd_create, name, MFD_CLOEXEC));
  if (fd < 0) return -1;
  int written = 0;
  while (written < len) {
    ssize_t n = write(fd, text + written, static_cast<size_t>(len - written));
    if (n <= 0) { close(fd); return -1; }
    written += static_cast<int>(n);
  }
  lseek(fd, 0, SEEK_SET);
  return fd;
}

int make_filtered_proc_fd_at(int dirfd, const char *path, ProcKind kind) {
  if (!runtime_hiding_enabled() || !g_feature_hide_proc_net || !orig_openat || kind == PROC_NONE) return -1;
  int src = orig_openat(dirfd, path, O_RDONLY | O_CLOEXEC);
  if (src < 0) return -1;
  char raw[READ_LIMIT];
  int total = 0;
  while (total < READ_LIMIT - 1) {
    ssize_t n = read(src, raw + total, static_cast<size_t>(READ_LIMIT - 1 - total));
    if (n <= 0) break;
    total += static_cast<int>(n);
  }
  close(src);
  raw[total] = 0;
  char filtered[READ_LIMIT];
  int filtered_len = filter_proc_text(raw, total, filtered, READ_LIMIT, kind);
  const char *kind_name = kind == PROC_DEV ? "dev" : (kind == PROC_ROUTE ? "route" : (kind == PROC_IPV6_ROUTE ? "ipv6route" : "ifinet6"));
  char memfd_name[64];
  snprintf(memfd_name, sizeof(memfd_name), "zdt-d-zygisk-%s", kind_name);
  return make_memfd_from_text(memfd_name, filtered, filtered_len);
}

int make_filtered_proc_fd(const char *path, ProcKind kind) {
  return make_filtered_proc_fd_at(AT_FDCWD, path, kind);
}

bool sys_class_net_path(const char *path) {
  return streq(path, "/sys/class/net") || streq(path, "/sys/class/net/");
}

bool fopen_mode_is_read_only(const char *mode) {
  if (!mode || mode[0] != 'r') return false;
  return strchr(mode, '+') == nullptr;
}

bool path_is_hidden_sys_class_net_member(const char *path) {
  if (!path || !runtime_hiding_enabled() || !g_feature_hide_sys_class_net) return false;
  constexpr const char *prefix = "/sys/class/net/";
  if (!starts_with(path, prefix)) return false;
  const char *name = path + strlen(prefix);
  if (!*name) return false;
  char iface[IFACE_LEN];
  int i = 0;
  while (name[i] && name[i] != '/' && i < IFACE_LEN - 1) {
    iface[i] = name[i];
    i++;
  }
  iface[i] = 0;
  return is_hidden_interface(iface);
}

bool relative_path_is_hidden_sys_class_net_member(int dirfd, const char *path) {
  if (!path || path[0] == '/' || !runtime_hiding_enabled() || !g_feature_hide_sys_class_net) return false;
  if (fd_points_to_path(dirfd, "/sys/class/net", nullptr)) {
    char iface[IFACE_LEN];
    int i = 0;
    while (path[i] && path[i] != '/' && i < IFACE_LEN - 1) { iface[i] = path[i]; i++; }
    iface[i] = 0;
    return is_hidden_interface(iface);
  }
  for (int i = 0; i < g_interface_count; ++i) {
    char hidden_dir[128];
    snprintf(hidden_dir, sizeof(hidden_dir), "/sys/class/net/%s", g_interfaces[i]);
    if (fd_points_to_path(dirfd, hidden_dir, nullptr)) return true;
  }
  return false;
}


bool should_hide_sys_class_net_path_at(int dirfd, const char *path) {
  if (!path || !runtime_hiding_enabled()) return false;
  if (path[0] == '/') return path_is_hidden_sys_class_net_member(path);
  if (dirfd == AT_FDCWD) return false;
  return relative_path_is_hidden_sys_class_net_member(dirfd, path);
}

void track_dir(DIR *dir) {
  if (!dir) return;
  for (int i = 0; i < g_tracked_dir_count; ++i) if (g_tracked_dirs[i] == dir) return;
  if (g_tracked_dir_count < MAX_TRACKED_DIRS) g_tracked_dirs[g_tracked_dir_count++] = dir;
}

bool is_tracked_dir(DIR *dir) {
  for (int i = 0; i < g_tracked_dir_count; ++i) if (g_tracked_dirs[i] == dir) return true;
  return false;
}

void untrack_dir(DIR *dir) {
  for (int i = 0; i < g_tracked_dir_count; ++i) {
    if (g_tracked_dirs[i] == dir) {
      for (int j = i; j + 1 < g_tracked_dir_count; ++j) g_tracked_dirs[j] = g_tracked_dirs[j + 1];
      g_tracked_dirs[--g_tracked_dir_count] = nullptr;
      return;
    }
  }
}

void filter_ifconf(ifconf *conf) {
  if (!conf || !conf->ifc_ifcu.ifcu_req || conf->ifc_len <= 0) return;
  int count = conf->ifc_len / static_cast<int>(sizeof(ifreq));
  ifreq *req = conf->ifc_ifcu.ifcu_req;
  int keep = 0;
  for (int i = 0; i < count; ++i) {
    if (!is_hidden_interface(req[i].ifr_name)) {
      if (keep != i) memcpy(&req[keep], &req[i], sizeof(ifreq));
      keep++;
    }
  }
  conf->ifc_len = keep * static_cast<int>(sizeof(ifreq));
}

bool request_uses_ifr_name(unsigned long request) {
  switch (request) {
    case SIOCGIFFLAGS:
    case SIOCGIFADDR:
    case SIOCGIFDSTADDR:
    case SIOCGIFBRDADDR:
    case SIOCGIFNETMASK:
    case SIOCGIFMTU:
    case SIOCGIFHWADDR:
    case SIOCGIFINDEX:
      return true;
    default:
      return false;
  }
}

bool rtattr_int_matches_hidden_index(rtattr *attr) {
  if (!attr || attr->rta_len < sizeof(rtattr) + sizeof(int)) return false;
  int value = 0;
  memcpy(&value, reinterpret_cast<char *>(attr) + sizeof(rtattr), sizeof(value));
  return value > 0 && is_hidden_index(static_cast<unsigned int>(value));
}

bool nlmsg_route_has_hidden_oif(nlmsghdr *hdr, char *base, int total) {
  if (!hdr || !base || total < static_cast<int>(sizeof(nlmsghdr) + sizeof(rtmsg))) return false;
  int offset = static_cast<int>(sizeof(nlmsghdr) + sizeof(rtmsg));
  while (offset + static_cast<int>(sizeof(rtattr)) <= total) {
    rtattr *attr = reinterpret_cast<rtattr *>(base + offset);
    if (attr->rta_len < sizeof(rtattr) || offset + attr->rta_len > total) break;
    if ((attr->rta_type == RTA_OIF || attr->rta_type == RTA_IIF) && rtattr_int_matches_hidden_index(attr)) {
      return true;
    }
    offset += static_cast<int>(RTA_ALIGN(attr->rta_len));
  }
  return false;
}

bool nlmsg_has_hidden_ifname(nlmsghdr *hdr) {
  if (!hdr || hdr->nlmsg_len < sizeof(nlmsghdr)) return false;
  char *base = reinterpret_cast<char *>(hdr);
  int total = static_cast<int>(hdr->nlmsg_len);

  if (hdr->nlmsg_type == RTM_NEWADDR) {
    if (!g_feature_hide_netlink_addr) return false;
    if (total < static_cast<int>(sizeof(nlmsghdr) + sizeof(ifaddrmsg))) return false;
    ifaddrmsg *addr = reinterpret_cast<ifaddrmsg *>(base + sizeof(nlmsghdr));
    return is_hidden_index(addr->ifa_index);
  }

  if (hdr->nlmsg_type == RTM_NEWROUTE) {
    if (!g_feature_hide_netlink_route) return false;
    return nlmsg_route_has_hidden_oif(hdr, base, total);
  }

  if (hdr->nlmsg_type != RTM_NEWLINK) return false;
  if (!g_feature_hide_netlink_link) return false;
  if (total < static_cast<int>(sizeof(nlmsghdr) + sizeof(ifinfomsg))) return false;
  ifinfomsg *link = reinterpret_cast<ifinfomsg *>(base + sizeof(nlmsghdr));
  if (is_hidden_index(static_cast<unsigned int>(link->ifi_index))) return true;
  int offset = static_cast<int>(sizeof(nlmsghdr) + sizeof(ifinfomsg));
  while (offset + static_cast<int>(sizeof(rtattr)) <= total) {
    rtattr *attr = reinterpret_cast<rtattr *>(base + offset);
    if (attr->rta_len < sizeof(rtattr) || offset + attr->rta_len > total) break;
    if (attr->rta_type == IFLA_IFNAME) {
      const char *name = base + offset + sizeof(rtattr);
      int max_len = static_cast<int>(attr->rta_len - sizeof(rtattr));
      char tmp[IFACE_LEN];
      int i = 0;
      while (i < max_len && i < IFACE_LEN - 1 && name[i]) { tmp[i] = name[i]; i++; }
      tmp[i] = 0;
      if (is_hidden_interface(tmp)) return true;
    }
    offset += static_cast<int>(RTA_ALIGN(attr->rta_len));
  }
  return false;
}

bool is_netlink_socket_fd(int fd) {
  int domain = 0;
  socklen_t domain_len = static_cast<socklen_t>(sizeof(domain));
  if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &domain_len) == 0 &&
      domain_len >= static_cast<socklen_t>(sizeof(domain))) {
    return domain == AF_NETLINK;
  }

  // Fallback for unusual libc/kernel combinations where SO_DOMAIN is not
  // available. Keep it read-only and conservative: if we cannot prove NETLINK,
  // recv/recvmsg payloads must pass through untouched.
  sockaddr_nl addr;
  memset(&addr, 0, sizeof(addr));
  socklen_t len = static_cast<socklen_t>(sizeof(addr));
  if (getsockname(fd, reinterpret_cast<sockaddr *>(&addr), &len) != 0) return false;
  return len >= static_cast<socklen_t>(sizeof(unsigned short)) && addr.nl_family == AF_NETLINK;
}

bool looks_like_complete_netlink_buffer(const char *buf, ssize_t len) {
  if (!buf || len < static_cast<ssize_t>(sizeof(nlmsghdr))) return false;
  int pos = 0;
  int total = static_cast<int>(len);
  bool saw_message = false;
  while (pos + static_cast<int>(sizeof(nlmsghdr)) <= total) {
    const nlmsghdr *hdr = reinterpret_cast<const nlmsghdr *>(buf + pos);
    if (hdr->nlmsg_len < sizeof(nlmsghdr)) return false;
    if (pos + static_cast<int>(hdr->nlmsg_len) > total) return false;
    int aligned = static_cast<int>(NLMSG_ALIGN(hdr->nlmsg_len));
    if (aligned <= 0 || pos + aligned > total) aligned = total - pos;
    saw_message = true;
    pos += aligned;
  }
  return saw_message && pos == total;
}

ssize_t filter_netlink_buffer(char *buf, ssize_t len) {
  if (!buf || len <= 0) return len;
  int pos = 0;
  int out = 0;
  int total = static_cast<int>(len);
  while (pos + static_cast<int>(sizeof(nlmsghdr)) <= total) {
    nlmsghdr *hdr = reinterpret_cast<nlmsghdr *>(buf + pos);
    if (hdr->nlmsg_len < sizeof(nlmsghdr) || pos + static_cast<int>(hdr->nlmsg_len) > total) break;
    int aligned = static_cast<int>(NLMSG_ALIGN(hdr->nlmsg_len));
    if (pos + aligned > total) aligned = total - pos;
    bool hide = nlmsg_has_hidden_ifname(hdr);
    if (!hide) {
      if (out != pos) memmove(buf + out, buf + pos, static_cast<size_t>(aligned));
      out += aligned;
    }
    pos += aligned;
  }
  if (pos < total && out != pos) {
    memmove(buf + out, buf + pos, static_cast<size_t>(total - pos));
    out += total - pos;
  } else if (pos < total) {
    out += total - pos;
  }
  return out;
}

ssize_t filter_received_netlink_payload(int sockfd, void *data, ssize_t rc, size_t capacity, int msg_flags) {
  if (rc <= 0 || !runtime_hiding_enabled() || !any_netlink_feature_enabled() || !data || capacity == 0) return rc;
  if ((msg_flags & MSG_TRUNC) != 0) return rc;
  if (rc > static_cast<ssize_t>(capacity)) return rc;
  if (!is_netlink_socket_fd(sockfd)) return rc;
  char *buf = reinterpret_cast<char *>(data);
  if (!looks_like_complete_netlink_buffer(buf, rc)) return rc;
  ssize_t filtered = filter_netlink_buffer(buf, rc);
  if (filtered != rc) g_netlink_hits++;
  return filtered;
}

int hooked_getifaddrs(ifaddrs **out) {
  if (!orig_getifaddrs) return -1;
  if (!enter_hook_guard()) return orig_getifaddrs(out);
  g_inside_getifaddrs = true;
  int rc = orig_getifaddrs(out);
  g_inside_getifaddrs = false;
  leave_hook_guard();
  if (rc != 0 || !out || !*out || !runtime_hiding_enabled() || !g_feature_hide_getifaddrs) return rc;
  g_getifaddrs_hits++;
  ifaddrs *head = *out;
  ifaddrs *prev = nullptr;
  ifaddrs *cur = head;
  while (cur) {
    ifaddrs *next = cur->ifa_next;
    if (is_hidden_interface(cur->ifa_name)) {
      if (prev) prev->ifa_next = next;
      else head = next;
    } else {
      prev = cur;
    }
    cur = next;
  }
  *out = head;
  return rc;
}

int hooked_open(const char *pathname, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
  if (g_hook_depth == 0 && runtime_hiding_enabled()) {
    if (path_is_hidden_sys_class_net_member(pathname)) {
      set_errno_value(ENOENT);
      return -1;
    }
    if ((flags & O_ACCMODE) == O_RDONLY) {
      ProcKind kind = classify_proc_path(pathname);
      if (kind != PROC_NONE) {
        int fd = make_filtered_proc_fd(pathname, kind);
        if (fd >= 0) { g_proc_hits++; return fd; }
      }
    }
  }
  if (!orig_open) return -1;
  if (!enter_hook_guard()) {
    if (flags & O_CREAT) return orig_open(pathname, flags, mode);
    return orig_open(pathname, flags);
  }
  int rc = (flags & O_CREAT) ? orig_open(pathname, flags, mode) : orig_open(pathname, flags);
  leave_hook_guard();
  return rc;
}

int hooked_openat(int dirfd, const char *pathname, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
  if (g_hook_depth == 0 && runtime_hiding_enabled()) {
    if ((dirfd == AT_FDCWD && path_is_hidden_sys_class_net_member(pathname)) ||
        (dirfd != AT_FDCWD && relative_path_is_hidden_sys_class_net_member(dirfd, pathname))) {
      set_errno_value(ENOENT);
      return -1;
    }
    if ((flags & O_ACCMODE) == O_RDONLY) {
      ProcKind kind = PROC_NONE;
      int open_dirfd = AT_FDCWD;
      const char *open_path = pathname;
      if (dirfd == AT_FDCWD) {
        kind = classify_proc_path(pathname);
      } else {
        kind = classify_proc_relative_name(pathname);
        if (kind != PROC_NONE && fd_is_proc_net_dir(dirfd)) open_dirfd = dirfd;
        else kind = PROC_NONE;
      }
      if (kind != PROC_NONE) {
        int fd = make_filtered_proc_fd_at(open_dirfd, open_path, kind);
        if (fd >= 0) { g_proc_hits++; return fd; }
      }
    }
  }
  if (!orig_openat) return -1;
  if (!enter_hook_guard()) {
    if (flags & O_CREAT) return orig_openat(dirfd, pathname, flags, mode);
    return orig_openat(dirfd, pathname, flags);
  }
  int rc = (flags & O_CREAT) ? orig_openat(dirfd, pathname, flags, mode) : orig_openat(dirfd, pathname, flags);
  leave_hook_guard();
  return rc;
}

int hooked_ioctl(int fd, unsigned long request, ...) {
  va_list ap;
  va_start(ap, request);
  void *arg = va_arg(ap, void *);
  va_end(ap);
  if (!orig_ioctl) return -1;

  // The real getifaddrs() may call ioctl(SIOCGIFFLAGS/...) internally while
  // building its list. Filtering those internal probes can break libc's own
  // construction and make callers hang or receive a failed getifaddrs result.
  // The getifaddrs hook filters the final list after the real call succeeds,
  // so internal ioctls must pass through unchanged.
  if (g_inside_getifaddrs) return orig_ioctl(fd, request, arg);

  bool hiding = runtime_hiding_enabled() && g_feature_hide_ioctl;
  if (hiding && arg && request != SIOCGIFCONF) {
    ifreq *ifr = reinterpret_cast<ifreq *>(arg);
    if (request == SIOCGIFNAME) {
      if (is_hidden_index(static_cast<unsigned int>(ifr->ifr_ifru.ifr_ifindex))) {
        g_ioctl_hits++;
        set_errno_value(ENODEV);
        return -1;
      }
    } else if (request_uses_ifr_name(request) && is_hidden_interface(ifr->ifr_name)) {
      g_ioctl_hits++;
      set_errno_value(ENODEV);
      return -1;
    }
  }

  if (!enter_hook_guard()) return orig_ioctl(fd, request, arg);
  int rc = orig_ioctl(fd, request, arg);
  leave_hook_guard();
  if (!hiding || !arg || rc != 0) return rc;
  if (request == SIOCGIFCONF) {
    g_ioctl_hits++;
    filter_ifconf(reinterpret_cast<ifconf *>(arg));
    return rc;
  }
  if (request == SIOCGIFNAME) {
    ifreq *ifr = reinterpret_cast<ifreq *>(arg);
    if (is_hidden_interface(ifr->ifr_name)) {
      memset(ifr->ifr_name, 0, sizeof(ifr->ifr_name));
      g_ioctl_hits++;
      set_errno_value(ENODEV);
      return -1;
    }
  }
  return rc;
}

ssize_t hooked_recvmsg(int sockfd, msghdr *msg, int flags) {
  if (!orig_recvmsg) return -1;
  if (!enter_hook_guard()) return orig_recvmsg(sockfd, msg, flags);
  ssize_t rc = orig_recvmsg(sockfd, msg, flags);
  leave_hook_guard();
  if (!msg || !msg->msg_iov || msg->msg_iovlen < 1) return rc;
  iovec *iov = &msg->msg_iov[0];
  return filter_received_netlink_payload(sockfd, iov ? iov->iov_base : nullptr, rc, iov ? iov->iov_len : 0, msg->msg_flags);
}

ssize_t hooked_recv(int sockfd, void *buf, size_t len, int flags) {
  if (!orig_recv) return -1;
  if (!enter_hook_guard()) return orig_recv(sockfd, buf, len, flags);
  ssize_t rc = orig_recv(sockfd, buf, len, flags);
  leave_hook_guard();
  return filter_received_netlink_payload(sockfd, buf, rc, len, flags);
}

ssize_t hooked_recvfrom(int sockfd, void *buf, size_t len, int flags, sockaddr *src_addr, socklen_t *addrlen) {
  if (!orig_recvfrom) return -1;
  if (!enter_hook_guard()) return orig_recvfrom(sockfd, buf, len, flags, src_addr, addrlen);
  ssize_t rc = orig_recvfrom(sockfd, buf, len, flags, src_addr, addrlen);
  leave_hook_guard();
  return filter_received_netlink_payload(sockfd, buf, rc, len, flags);
}

ssize_t hooked_read(int fd, void *buf, size_t count) {
  if (!orig_read) return -1;
  if (!enter_hook_guard()) return orig_read(fd, buf, count);
  ssize_t rc = orig_read(fd, buf, count);
  leave_hook_guard();
  return filter_received_netlink_payload(fd, buf, rc, count, 0);
}

ssize_t hooked_readv(int fd, const iovec *iov, int iovcnt) {
  if (!orig_readv) return -1;
  if (!enter_hook_guard()) return orig_readv(fd, iov, iovcnt);
  ssize_t rc = orig_readv(fd, iov, iovcnt);
  leave_hook_guard();
  if (rc <= 0 || !iov || iovcnt <= 0) return rc;
  if (static_cast<size_t>(rc) <= iov[0].iov_len) {
    return filter_received_netlink_payload(fd, iov[0].iov_base, rc, iov[0].iov_len, 0);
  }
  return rc;
}

DIR *hooked_opendir(const char *name) {
  if (runtime_hiding_enabled() && path_is_hidden_sys_class_net_member(name)) {
    set_errno_value(ENOENT);
    return nullptr;
  }
  if (!orig_opendir) return nullptr;
  if (!enter_hook_guard()) return orig_opendir(name);
  DIR *dir = orig_opendir(name);
  leave_hook_guard();
  if (runtime_hiding_enabled() && dir && sys_class_net_path(name)) track_dir(dir);
  return dir;
}

dirent_like *hooked_readdir(DIR *dirp) {
  if (!orig_readdir) return nullptr;
  dirent_like *ent = nullptr;
  do {
    if (!enter_hook_guard()) return orig_readdir(dirp);
    ent = orig_readdir(dirp);
    leave_hook_guard();
  } while (ent && is_tracked_dir(dirp) && is_hidden_interface(ent->d_name));
  return ent;
}

dirent_like *hooked_readdir64(DIR *dirp) {
  if (!orig_readdir64) return nullptr;
  dirent_like *ent = nullptr;
  do {
    if (!enter_hook_guard()) return orig_readdir64(dirp);
    ent = orig_readdir64(dirp);
    leave_hook_guard();
  } while (ent && is_tracked_dir(dirp) && is_hidden_interface(ent->d_name));
  return ent;
}

int hooked_closedir(DIR *dirp) {
  untrack_dir(dirp);
  if (!orig_closedir) return -1;
  if (!enter_hook_guard()) return orig_closedir(dirp);
  int rc = orig_closedir(dirp);
  leave_hook_guard();
  return rc;
}

FILE *open_filtered_proc_file_as_stream(const char *pathname, const char *mode) {
  if (!fopen_mode_is_read_only(mode)) return nullptr;
  ProcKind kind = classify_proc_path(pathname);
  if (kind == PROC_NONE) return nullptr;
  int fd = make_filtered_proc_fd(pathname, kind);
  if (fd < 0) return nullptr;
  FILE *fp = fdopen(fd, "r");
  if (!fp) close(fd);
  return fp;
}

FILE *hooked_fopen_common(FopenFn original, const char *pathname, const char *mode) {
  if (g_hook_depth == 0 && runtime_hiding_enabled()) {
    if (path_is_hidden_sys_class_net_member(pathname)) {
      set_errno_value(ENOENT);
      return nullptr;
    }
    FILE *filtered = open_filtered_proc_file_as_stream(pathname, mode);
    if (filtered) { g_proc_hits++; return filtered; }
  }
  if (!original) return nullptr;
  if (!enter_hook_guard()) return original(pathname, mode);
  FILE *rc = original(pathname, mode);
  leave_hook_guard();
  return rc;
}

FILE *hooked_fopen(const char *pathname, const char *mode) {
  return hooked_fopen_common(orig_fopen, pathname, mode);
}

FILE *hooked_fopen64(const char *pathname, const char *mode) {
  return hooked_fopen_common(orig_fopen64 ? orig_fopen64 : orig_fopen, pathname, mode);
}

unsigned int hooked_if_nametoindex(const char *ifname) {
  if (runtime_hiding_enabled() && g_feature_hide_ifindex && is_hidden_interface(ifname)) { set_errno_value(ENODEV); return 0; }
  if (!orig_if_nametoindex) return 0;
  if (!enter_hook_guard()) return orig_if_nametoindex(ifname);
  unsigned int rc = orig_if_nametoindex(ifname);
  leave_hook_guard();
  return rc;
}

char *hooked_if_indextoname(unsigned int ifindex, char *ifname) {
  if (runtime_hiding_enabled() && g_feature_hide_ifindex && is_hidden_index(ifindex)) {
    if (ifname) ifname[0] = 0;
    set_errno_value(ENODEV);
    return nullptr;
  }
  if (!orig_if_indextoname) return nullptr;
  if (!enter_hook_guard()) return orig_if_indextoname(ifindex, ifname);
  char *rc = orig_if_indextoname(ifindex, ifname);
  leave_hook_guard();
  if (rc && g_feature_hide_ifindex && is_hidden_interface(rc)) {
    if (ifname) ifname[0] = 0;
    set_errno_value(ENODEV);
    return nullptr;
  }
  return rc;
}


int hooked_access(const char *pathname, int mode) {
  if (g_hook_depth == 0 && path_is_hidden_sys_class_net_member(pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_access) return -1;
  if (!enter_hook_guard()) return orig_access(pathname, mode);
  int rc = orig_access(pathname, mode);
  leave_hook_guard();
  return rc;
}

int hooked_faccessat(int dirfd, const char *pathname, int mode, int flags) {
  if (g_hook_depth == 0 && should_hide_sys_class_net_path_at(dirfd, pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_faccessat) return -1;
  if (!enter_hook_guard()) return orig_faccessat(dirfd, pathname, mode, flags);
  int rc = orig_faccessat(dirfd, pathname, mode, flags);
  leave_hook_guard();
  return rc;
}

int hooked_stat(const char *pathname, struct stat *buf) {
  if (g_hook_depth == 0 && path_is_hidden_sys_class_net_member(pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_stat) return -1;
  if (!enter_hook_guard()) return orig_stat(pathname, buf);
  int rc = orig_stat(pathname, buf);
  leave_hook_guard();
  return rc;
}

int hooked_stat64(const char *pathname, struct stat64 *buf) {
  if (g_hook_depth == 0 && path_is_hidden_sys_class_net_member(pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_stat64) return -1;
  if (!enter_hook_guard()) return orig_stat64(pathname, buf);
  int rc = orig_stat64(pathname, buf);
  leave_hook_guard();
  return rc;
}

int hooked_lstat(const char *pathname, struct stat *buf) {
  if (g_hook_depth == 0 && path_is_hidden_sys_class_net_member(pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_lstat) return -1;
  if (!enter_hook_guard()) return orig_lstat(pathname, buf);
  int rc = orig_lstat(pathname, buf);
  leave_hook_guard();
  return rc;
}

int hooked_lstat64(const char *pathname, struct stat64 *buf) {
  if (g_hook_depth == 0 && path_is_hidden_sys_class_net_member(pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_lstat64) return -1;
  if (!enter_hook_guard()) return orig_lstat64(pathname, buf);
  int rc = orig_lstat64(pathname, buf);
  leave_hook_guard();
  return rc;
}

int hooked_fstatat(int dirfd, const char *pathname, struct stat *buf, int flags) {
  if (g_hook_depth == 0 && should_hide_sys_class_net_path_at(dirfd, pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_fstatat) return -1;
  if (!enter_hook_guard()) return orig_fstatat(dirfd, pathname, buf, flags);
  int rc = orig_fstatat(dirfd, pathname, buf, flags);
  leave_hook_guard();
  return rc;
}

int hooked_fstatat64(int dirfd, const char *pathname, struct stat64 *buf, int flags) {
  if (g_hook_depth == 0 && should_hide_sys_class_net_path_at(dirfd, pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_fstatat64) return -1;
  if (!enter_hook_guard()) return orig_fstatat64(dirfd, pathname, buf, flags);
  int rc = orig_fstatat64(dirfd, pathname, buf, flags);
  leave_hook_guard();
  return rc;
}

int hooked_statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *buf) {
  if (g_hook_depth == 0 && should_hide_sys_class_net_path_at(dirfd, pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_statx) return -1;
  if (!enter_hook_guard()) return orig_statx(dirfd, pathname, flags, mask, buf);
  int rc = orig_statx(dirfd, pathname, flags, mask, buf);
  leave_hook_guard();
  return rc;
}

ssize_t hooked_readlink(const char *pathname, char *buf, size_t bufsiz) {
  if (g_hook_depth == 0 && path_is_hidden_sys_class_net_member(pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_readlink) return -1;
  if (!enter_hook_guard()) return orig_readlink(pathname, buf, bufsiz);
  ssize_t rc = orig_readlink(pathname, buf, bufsiz);
  leave_hook_guard();
  return rc;
}

ssize_t hooked_readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
  if (g_hook_depth == 0 && should_hide_sys_class_net_path_at(dirfd, pathname)) {
    set_errno_value(ENOENT);
    return -1;
  }
  if (!orig_readlinkat) return -1;
  if (!enter_hook_guard()) return orig_readlinkat(dirfd, pathname, buf, bufsiz);
  ssize_t rc = orig_readlinkat(dirfd, pathname, buf, bufsiz);
  leave_hook_guard();
  return rc;
}

bool str_ends_with(const char *s, const char *suffix) {
  if (!s || !suffix) return false;
  size_t ls = strlen(s);
  size_t lf = strlen(suffix);
  return ls >= lf && strcmp(s + ls - lf, suffix) == 0;
}

bool contains_framework_hook_target(const char *path) {
  if (!path) return false;
  return strstr(path, "/libjavacore.so") != nullptr ||
         strstr(path, "/libopenjdk") != nullptr ||
         strstr(path, "/libnativehelper.so") != nullptr ||
         strstr(path, "/libnativeloader.so") != nullptr ||
         strstr(path, "/libandroid_runtime.so") != nullptr ||
         strstr(path, "/libnetd_client.so") != nullptr;
}

bool is_app_owned_library_path(const char *path) {
  if (!path || !str_ends_with(path, ".so")) return false;
  return strstr(path, "/data/app/") != nullptr ||
         strstr(path, "/data/user/") != nullptr ||
         strstr(path, "/mnt/expand/") != nullptr;
}

bool is_app_owned_native_container_path(const char *path) {
  if (!path) return false;
  if (is_app_owned_library_path(path)) return true;
  // Modern Android apps commonly use uncompressed native libraries loaded
  // directly from base.apk/split APK mappings (useLegacyPackaging=false).
  // In /proc/self/maps these mappings are shown as the APK path, not as the
  // .so name, and their file offset is usually non-zero. They still begin
  // with a valid ELF header at the mapped start address, so the PLT/GOT
  // patcher must consider app-owned APK mappings too.
  bool app_path = strstr(path, "/data/app/") != nullptr || strstr(path, "/mnt/expand/") != nullptr;
  bool apk_path = strstr(path, ".apk") != nullptr;
  return app_path && apk_path;
}

bool is_chromium_webview_library_path(const char *path) {
  if (!path) return false;
  return strstr(path, "libwebviewchromium") != nullptr ||
         strstr(path, "libmonochrome") != nullptr ||
         strstr(path, "libchromium") != nullptr ||
         strstr(path, "trichrome") != nullptr ||
         strstr(path, "Trichrome") != nullptr ||
         strstr(path, "chromium") != nullptr ||
         strstr(path, "Chromium") != nullptr ||
         strstr(path, "webview") != nullptr ||
         strstr(path, "WebView") != nullptr ||
         strstr(path, "chrome") != nullptr ||
         strstr(path, "Chrome") != nullptr;
}

bool should_register_hooks_for_path(const char *path) {
  if (!path || !*path) return false;
  if (strstr(path, "/ZDT-D/zygisk/") != nullptr) return false;
  if (is_chromium_webview_library_path(path)) return false;
  if (strstr(path, "/libc.so") != nullptr) return false;
  if (strstr(path, "/libdl.so") != nullptr) return false;
  if (strstr(path, "/linker") != nullptr) return false;

  // Stable path: do not rewrite app-owned GOT/PLT. Native libraries loaded
  // by the app are covered by libc-level inline hooks below. Avoiding direct
  // app-library patching reduces splash-screen anti-tamper crashes.
  if (is_app_owned_library_path(path)) return false;

  return contains_framework_hook_target(path);
}

bool should_late_patch_hooks_for_path(const char *path) {
  if (!path || !*path) return false;
  if (strstr(path, "/ZDT-D/zygisk/") != nullptr) return false;
  if (is_chromium_webview_library_path(path)) return false;
  if (strstr(path, "/libc.so") != nullptr) return false;
  if (strstr(path, "/libdl.so") != nullptr) return false;
  if (strstr(path, "/linker") != nullptr) return false;
  // App native code can be mapped either as a real .so or directly from base.apk.
  // Late app-owned PLT/GOT patching is the visibility fallback for JNI libraries
  // loaded after specialize.
  if (is_app_owned_native_container_path(path)) return g_feature_late_hooking;
  return contains_framework_hook_target(path);
}


// Minimal in-process PLT/GOT patcher used only after late System.loadLibrary()/dlopen().
// Zygisk pltHookRegister is used during specialize for already mapped libraries;
// this code covers app-owned native libraries that appear later and therefore
// cannot be registered during the initial /proc/self/maps pass.
using Elf64_Addr = unsigned long long;
using Elf64_Off = unsigned long long;
using Elf64_Xword = unsigned long long;
using Elf64_Sxword = long long;
using Elf64_Word = unsigned int;
using Elf64_Half = unsigned short;

struct Elf64_Ehdr {
  unsigned char e_ident[16];
  Elf64_Half e_type;
  Elf64_Half e_machine;
  Elf64_Word e_version;
  Elf64_Addr e_entry;
  Elf64_Off e_phoff;
  Elf64_Off e_shoff;
  Elf64_Word e_flags;
  Elf64_Half e_ehsize;
  Elf64_Half e_phentsize;
  Elf64_Half e_phnum;
  Elf64_Half e_shentsize;
  Elf64_Half e_shnum;
  Elf64_Half e_shstrndx;
};

struct Elf64_Phdr {
  Elf64_Word p_type;
  Elf64_Word p_flags;
  Elf64_Off p_offset;
  Elf64_Addr p_vaddr;
  Elf64_Addr p_paddr;
  Elf64_Xword p_filesz;
  Elf64_Xword p_memsz;
  Elf64_Xword p_align;
};

struct Elf64_Dyn {
  Elf64_Sxword d_tag;
  union {
    Elf64_Xword d_val;
    Elf64_Addr d_ptr;
  } d_un;
};

struct Elf64_Sym {
  Elf64_Word st_name;
  unsigned char st_info;
  unsigned char st_other;
  Elf64_Half st_shndx;
  Elf64_Addr st_value;
  Elf64_Xword st_size;
};

struct Elf64_Rela {
  Elf64_Addr r_offset;
  Elf64_Xword r_info;
  Elf64_Sxword r_addend;
};

#define PT_LOAD 1
#define PT_DYNAMIC 2
#define DT_NULL 0
#define DT_HASH 4
#define DT_PLTRELSZ 2
#define DT_STRTAB 5
#define DT_SYMTAB 6
#define DT_STRSZ 10
#define DT_RELA 7
#define DT_RELASZ 8
#define DT_RELAENT 9
#define DT_SYMENT 11
#define DT_PLTREL 20
#define DT_JMPREL 23
#define DT_GNU_HASH 0x6ffffef5
#define R_AARCH64_ABS64 257
#define R_AARCH64_GLOB_DAT 1025
#define R_AARCH64_JUMP_SLOT 1026
#define ELF64_R_SYM(info) ((info) >> 32)
#define ELF64_R_TYPE(info) ((unsigned int)(info))

struct MapsRecord {
  uintptr_t start;
  uintptr_t end;
  unsigned long long offset;
  dev_t dev;
  ino_t ino;
  char perms[5];
  char path[512];
};

void copy_cstr(char *dst, int cap, const char *src) {
  if (!dst || cap <= 0) return;
  int i = 0;
  if (src) {
    while (src[i] && i < cap - 1) { dst[i] = src[i]; i++; }
  }
  dst[i] = 0;
}

bool parse_maps_record_line(const char *line, int len, MapsRecord *out) {
  if (!line || len <= 0 || !out) return false;
  char tmp[1024];
  if (len >= static_cast<int>(sizeof(tmp))) return false;
  for (int i = 0; i < len; ++i) tmp[i] = line[i];
  tmp[len] = 0;

  const char *p = tmp;
  const char *after = nullptr;
  unsigned long long start = parse_hex_u64(p, &after);
  if (!after || *after != '-') return false;
  unsigned long long end = parse_hex_u64(after + 1, &after);
  if (!after || !is_space(*after)) return false;
  while (*after && is_space(*after)) after++;
  if (strlen(after) < 4) return false;
  out->perms[0] = after[0]; out->perms[1] = after[1]; out->perms[2] = after[2]; out->perms[3] = after[3]; out->perms[4] = 0;
  p = skip_token(after);
  unsigned long long offset = parse_hex_u64(p, &after);
  if (!after || !is_space(*after)) return false;
  while (*after && is_space(*after)) after++;
  unsigned long long major = parse_hex_u64(after, &after);
  if (!after || *after != ':') return false;
  unsigned long long minor = parse_hex_u64(after + 1, &after);
  if (!after || !is_space(*after)) return false;
  while (*after && is_space(*after)) after++;
  unsigned long long inode = parse_dec_u64(after, &after);
  while (*after && is_space(*after)) after++;
  if (*after != '/') return false;

  out->start = static_cast<uintptr_t>(start);
  out->end = static_cast<uintptr_t>(end);
  out->offset = offset;
  out->dev = make_dev_id(major, minor);
  out->ino = static_cast<ino_t>(inode);
  copy_cstr(out->path, sizeof(out->path), after);
  char *deleted = strstr(out->path, " (deleted)");
  if (deleted) *deleted = 0;
  trim(out->path);
  return out->start > 0 && out->end > out->start && out->ino > 0 && out->path[0] == '/';
}

bool late_patch_already_seen(dev_t dev, ino_t ino, uintptr_t base) {
  for (int i = 0; i < g_late_patched_count; ++i) {
    if (g_late_patched_devs[i] == dev && g_late_patched_inos[i] == ino && g_late_patched_bases[i] == base) return true;
  }
  return false;
}

void remember_late_patch(dev_t dev, ino_t ino, uintptr_t base) {
  if (late_patch_already_seen(dev, ino, base)) return;
  if (g_late_patched_count >= MAX_LATE_PATCHED_LIBS) return;
  g_late_patched_devs[g_late_patched_count] = dev;
  g_late_patched_inos[g_late_patched_count] = ino;
  g_late_patched_bases[g_late_patched_count] = base;
  g_late_patched_count++;
}

uintptr_t normalize_dyn_ptr(uintptr_t base, Elf64_Addr ptr) {
  if (ptr == 0) return 0;
  uintptr_t value = static_cast<uintptr_t>(ptr);
  if (value < base) return base + value;
  return value;
}

bool valid_elf_header(uintptr_t base) {
  if (!base) return false;
  Elf64_Ehdr *eh = reinterpret_cast<Elf64_Ehdr *>(base);
  return eh->e_ident[0] == 0x7f && eh->e_ident[1] == 'E' && eh->e_ident[2] == 'L' && eh->e_ident[3] == 'F' &&
         eh->e_ident[4] == 2 && eh->e_phoff > 0 && eh->e_phnum > 0 && eh->e_phnum < 128 &&
         eh->e_phentsize == sizeof(Elf64_Phdr);
}

int page_prot_from_maps_perms(const char *perms) {
  int prot = 0;
  if (perms && perms[0] == 'r') prot |= PROT_READ;
  if (perms && perms[1] == 'w') prot |= PROT_WRITE;
  if (perms && perms[2] == 'x') prot |= PROT_EXEC;
  if (prot == 0) prot = PROT_READ;
  return prot;
}

int protection_for_address(uintptr_t addr, int fallback) {
  char maps[READ_LIMIT];
  int n = read_file_at(AT_FDCWD, "/proc/self/maps", maps, READ_LIMIT);
  if (n <= 0) return fallback;
  const char *p = maps;
  while (*p) {
    const char *line = p;
    const char *end = strchr(line, '\n');
    int len = end ? static_cast<int>(end - line) : static_cast<int>(strlen(line));
    MapsRecord rec;
    if (parse_maps_record_line(line, len, &rec)) {
      if (addr >= rec.start && addr < rec.end) return page_prot_from_maps_perms(rec.perms);
    }
    if (!end) break;
    p = end + 1;
  }
  return fallback;
}

bool make_page_writable(uintptr_t addr, int *old_prot) {
  uintptr_t page = addr & ~(static_cast<uintptr_t>(PAGE_SIZE - 1));
  int prot = protection_for_address(addr, PROT_READ);
  if (old_prot) *old_prot = prot;
  int new_prot = prot | PROT_READ | PROT_WRITE;
  long rc = syscall(__NR_mprotect, reinterpret_cast<void *>(page), static_cast<size_t>(PAGE_SIZE), new_prot);
  return rc == 0;
}

void restore_page_prot(uintptr_t addr, int old_prot) {
  uintptr_t page = addr & ~(static_cast<uintptr_t>(PAGE_SIZE - 1));
  syscall(__NR_mprotect, reinterpret_cast<void *>(page), static_cast<size_t>(PAGE_SIZE), old_prot);
}

struct HookSpec {
  const char *symbol;
  void *hook;
  void **orig;
};

bool hook_symbol_enabled(const char *name) {
  if (!name) return false;
  if (streq(name, "getifaddrs")) return g_feature_hide_getifaddrs;
  if (streq(name, "if_nametoindex") || streq(name, "if_indextoname")) return g_feature_hide_ifindex;
  if (streq(name, "ioctl")) return g_feature_hide_ioctl;
  if (streq(name, "recv") || streq(name, "recvmsg") || streq(name, "recvfrom") ||
      streq(name, "read") || streq(name, "readv")) return any_netlink_feature_enabled();
  if (streq(name, "open") || streq(name, "openat")) return g_feature_hide_proc_net || g_feature_hide_sys_class_net;
  if (streq(name, "fopen") || streq(name, "fopen64")) return g_feature_hide_proc_net;
  if (streq(name, "opendir") || streq(name, "readdir") || streq(name, "readdir64") || streq(name, "closedir")) return g_feature_hide_sys_class_net;
  if (streq(name, "access") || streq(name, "faccessat") ||
      streq(name, "stat") || streq(name, "stat64") ||
      streq(name, "lstat") || streq(name, "lstat64") ||
      streq(name, "fstatat") || streq(name, "fstatat64") ||
      streq(name, "statx") || streq(name, "readlink") ||
      streq(name, "readlinkat")) return g_feature_hide_sys_class_net;
  if (streq(name, "dlopen") || streq(name, "android_dlopen_ext")) return g_feature_late_hooking;
  return false;
}

// Core libc symbols must not be registered through Zygisk PLT fallback after
// an inline libc hook has already installed its trampoline into the same
// orig_* slot. Zygisk's PLT API may overwrite the original slot on commit; if
// that happens, the hook starts calling an app/framework PLT original instead
// of the libc trampoline and late JNI code can bypass or recurse. Inline libc
// hooks are the primary path; PLT fallback only covers symbols that inline did
// not install, plus non-inline sysfs helpers such as opendir/readdir/stat.
bool symbol_has_inline_libc_trampoline(const char *name) {
  if (!name || !g_libc_inline_ready) return false;
  if (streq(name, "getifaddrs")) return orig_getifaddrs != nullptr;
  if (streq(name, "ioctl")) return orig_ioctl != nullptr;
  if (streq(name, "open")) return orig_open != nullptr;
  if (streq(name, "openat")) return orig_openat != nullptr;
  if (streq(name, "fopen")) return orig_fopen != nullptr;
  if (streq(name, "fopen64")) return orig_fopen64 != nullptr;
  if (streq(name, "recvmsg")) return orig_recvmsg != nullptr;
  if (streq(name, "recv")) return orig_recv != nullptr;
  if (streq(name, "recvfrom")) return orig_recvfrom != nullptr;
  if (streq(name, "read")) return orig_read != nullptr;
  if (streq(name, "readv")) return orig_readv != nullptr;
  if (streq(name, "if_nametoindex")) return orig_if_nametoindex != nullptr;
  if (streq(name, "if_indextoname")) return orig_if_indextoname != nullptr;
  if (streq(name, "dlopen")) return g_libdl_inline_ready && orig_dlopen != nullptr;
  if (streq(name, "android_dlopen_ext")) return g_libdl_inline_ready && orig_android_dlopen_ext != nullptr;
  return false;
}

bool should_register_plt_symbol(const char *name) {
  return hook_symbol_enabled(name) && !symbol_has_inline_libc_trampoline(name);
}

HookSpec *hook_spec_for_symbol(const char *name) {
  static HookSpec specs[] = {
      {"getifaddrs", reinterpret_cast<void *>(hooked_getifaddrs), reinterpret_cast<void **>(&orig_getifaddrs)},
      {"open", reinterpret_cast<void *>(hooked_open), reinterpret_cast<void **>(&orig_open)},
      {"openat", reinterpret_cast<void *>(hooked_openat), reinterpret_cast<void **>(&orig_openat)},
      {"ioctl", reinterpret_cast<void *>(hooked_ioctl), reinterpret_cast<void **>(&orig_ioctl)},
      {"recvmsg", reinterpret_cast<void *>(hooked_recvmsg), reinterpret_cast<void **>(&orig_recvmsg)},
      {"recv", reinterpret_cast<void *>(hooked_recv), reinterpret_cast<void **>(&orig_recv)},
      {"recvfrom", reinterpret_cast<void *>(hooked_recvfrom), reinterpret_cast<void **>(&orig_recvfrom)},
      {"read", reinterpret_cast<void *>(hooked_read), reinterpret_cast<void **>(&orig_read)},
      {"readv", reinterpret_cast<void *>(hooked_readv), reinterpret_cast<void **>(&orig_readv)},
      {"opendir", reinterpret_cast<void *>(hooked_opendir), reinterpret_cast<void **>(&orig_opendir)},
      {"readdir", reinterpret_cast<void *>(hooked_readdir), reinterpret_cast<void **>(&orig_readdir)},
      {"readdir64", reinterpret_cast<void *>(hooked_readdir64), reinterpret_cast<void **>(&orig_readdir64)},
      {"closedir", reinterpret_cast<void *>(hooked_closedir), reinterpret_cast<void **>(&orig_closedir)},
      {"fopen", reinterpret_cast<void *>(hooked_fopen), reinterpret_cast<void **>(&orig_fopen)},
      {"fopen64", reinterpret_cast<void *>(hooked_fopen64), reinterpret_cast<void **>(&orig_fopen64)},
      {"if_nametoindex", reinterpret_cast<void *>(hooked_if_nametoindex), reinterpret_cast<void **>(&orig_if_nametoindex)},
      {"if_indextoname", reinterpret_cast<void *>(hooked_if_indextoname), reinterpret_cast<void **>(&orig_if_indextoname)},
      {"access", reinterpret_cast<void *>(hooked_access), reinterpret_cast<void **>(&orig_access)},
      {"faccessat", reinterpret_cast<void *>(hooked_faccessat), reinterpret_cast<void **>(&orig_faccessat)},
      {"stat", reinterpret_cast<void *>(hooked_stat), reinterpret_cast<void **>(&orig_stat)},
      {"stat64", reinterpret_cast<void *>(hooked_stat64), reinterpret_cast<void **>(&orig_stat64)},
      {"lstat", reinterpret_cast<void *>(hooked_lstat), reinterpret_cast<void **>(&orig_lstat)},
      {"lstat64", reinterpret_cast<void *>(hooked_lstat64), reinterpret_cast<void **>(&orig_lstat64)},
      {"fstatat", reinterpret_cast<void *>(hooked_fstatat), reinterpret_cast<void **>(&orig_fstatat)},
      {"fstatat64", reinterpret_cast<void *>(hooked_fstatat64), reinterpret_cast<void **>(&orig_fstatat64)},
      {"statx", reinterpret_cast<void *>(hooked_statx), reinterpret_cast<void **>(&orig_statx)},
      {"readlink", reinterpret_cast<void *>(hooked_readlink), reinterpret_cast<void **>(&orig_readlink)},
      {"readlinkat", reinterpret_cast<void *>(hooked_readlinkat), reinterpret_cast<void **>(&orig_readlinkat)},
      {"dlopen", reinterpret_cast<void *>(hooked_dlopen), reinterpret_cast<void **>(&orig_dlopen)},
      {"android_dlopen_ext", reinterpret_cast<void *>(hooked_android_dlopen_ext), reinterpret_cast<void **>(&orig_android_dlopen_ext)},
  };
  for (unsigned int i = 0; i < sizeof(specs) / sizeof(specs[0]); ++i) {
    if (streq(name, specs[i].symbol)) return hook_symbol_enabled(name) ? &specs[i] : nullptr;
  }
  return nullptr;
}

bool relocation_type_is_hookable(unsigned int type) {
  return type == R_AARCH64_JUMP_SLOT || type == R_AARCH64_GLOB_DAT || type == R_AARCH64_ABS64;
}

int patch_rela_table(uintptr_t base, Elf64_Rela *rela, size_t rela_count, Elf64_Sym *symtab, const char *strtab) {
  if (!base || !rela || !symtab || !strtab) return 0;
  int patched = 0;
  for (size_t i = 0; i < rela_count; ++i) {
    unsigned int type = ELF64_R_TYPE(rela[i].r_info);
    if (!relocation_type_is_hookable(type)) continue;
    unsigned long sym_index = static_cast<unsigned long>(ELF64_R_SYM(rela[i].r_info));
    const char *name = strtab + symtab[sym_index].st_name;
    HookSpec *spec = hook_spec_for_symbol(name);
    if (!spec || !spec->hook) continue;
    uintptr_t slot_addr = normalize_dyn_ptr(base, rela[i].r_offset);
    if (!slot_addr) continue;
    void **slot = reinterpret_cast<void **>(slot_addr);
    void *current = *slot;
    if (current == spec->hook) continue;
    if (spec->orig && !*spec->orig && current && current != spec->hook) *spec->orig = current;
    int old_prot = PROT_READ;
    if (!make_page_writable(slot_addr, &old_prot)) continue;
    *slot = spec->hook;
    restore_page_prot(slot_addr, old_prot);
    patched++;
  }
  return patched;
}

int internal_patch_plt_for_library(uintptr_t base) {
  if (!valid_elf_header(base)) return 0;
  Elf64_Ehdr *eh = reinterpret_cast<Elf64_Ehdr *>(base);
  Elf64_Phdr *ph = reinterpret_cast<Elf64_Phdr *>(base + static_cast<uintptr_t>(eh->e_phoff));
  Elf64_Dyn *dyn = nullptr;
  size_t dyn_count = 0;
  for (int i = 0; i < eh->e_phnum; ++i) {
    if (ph[i].p_type == PT_DYNAMIC) {
      dyn = reinterpret_cast<Elf64_Dyn *>(base + static_cast<uintptr_t>(ph[i].p_vaddr));
      dyn_count = static_cast<size_t>(ph[i].p_memsz / sizeof(Elf64_Dyn));
      break;
    }
  }
  if (!dyn || dyn_count == 0) return 0;

  uintptr_t strtab_addr = 0;
  uintptr_t symtab_addr = 0;
  uintptr_t rela_addr = 0;
  size_t rela_sz = 0;
  size_t rela_ent = sizeof(Elf64_Rela);
  uintptr_t jmprel_addr = 0;
  size_t pltrel_sz = 0;
  int pltrel_type = 0;

  for (size_t i = 0; i < dyn_count; ++i) {
    if (dyn[i].d_tag == DT_NULL) break;
    switch (dyn[i].d_tag) {
      case DT_STRTAB: strtab_addr = normalize_dyn_ptr(base, dyn[i].d_un.d_ptr); break;
      case DT_SYMTAB: symtab_addr = normalize_dyn_ptr(base, dyn[i].d_un.d_ptr); break;
      case DT_RELA: rela_addr = normalize_dyn_ptr(base, dyn[i].d_un.d_ptr); break;
      case DT_RELASZ: rela_sz = static_cast<size_t>(dyn[i].d_un.d_val); break;
      case DT_RELAENT: if (dyn[i].d_un.d_val) rela_ent = static_cast<size_t>(dyn[i].d_un.d_val); break;
      case DT_JMPREL: jmprel_addr = normalize_dyn_ptr(base, dyn[i].d_un.d_ptr); break;
      case DT_PLTRELSZ: pltrel_sz = static_cast<size_t>(dyn[i].d_un.d_val); break;
      case DT_PLTREL: pltrel_type = static_cast<int>(dyn[i].d_un.d_val); break;
      default: break;
    }
  }

  if (!strtab_addr || !symtab_addr || rela_ent != sizeof(Elf64_Rela)) return 0;
  const char *strtab = reinterpret_cast<const char *>(strtab_addr);
  Elf64_Sym *symtab = reinterpret_cast<Elf64_Sym *>(symtab_addr);
  int patched = 0;

  if (jmprel_addr && pltrel_sz > 0 && (pltrel_type == DT_RELA || pltrel_type == 0)) {
    patched += patch_rela_table(base, reinterpret_cast<Elf64_Rela *>(jmprel_addr), pltrel_sz / sizeof(Elf64_Rela), symtab, strtab);
  }
  if (rela_addr && rela_sz > 0) {
    patched += patch_rela_table(base, reinterpret_cast<Elf64_Rela *>(rela_addr), rela_sz / sizeof(Elf64_Rela), symtab, strtab);
  }
  return patched;
}


uintptr_t find_loaded_library_base(const char *soname) {
  if (!soname || !*soname) return 0;
  char maps[READ_LIMIT];
  int n = read_file_at(AT_FDCWD, "/proc/self/maps", maps, READ_LIMIT);
  if (n <= 0) return 0;
  const char *p = maps;
  while (*p) {
    const char *line = p;
    const char *end = strchr(line, '\n');
    int len = end ? static_cast<int>(end - line) : static_cast<int>(strlen(line));
    MapsRecord rec;
    if (parse_maps_record_line(line, len, &rec)) {
      if (rec.offset == 0 && str_ends_with(rec.path, soname)) {
        return rec.start - static_cast<uintptr_t>(rec.offset);
      }
    }
    if (!end) break;
    p = end + 1;
  }
  return 0;
}

size_t dynsym_count_from_sysv_hash(uintptr_t hash_addr) {
  if (!hash_addr) return 0;
  const uint32_t *hash = reinterpret_cast<const uint32_t *>(hash_addr);
  uint32_t nbucket = hash[0];
  uint32_t nchain = hash[1];
  if (nbucket == 0 || nchain == 0 || nchain > 65536U) return 0;
  return static_cast<size_t>(nchain);
}

size_t dynsym_count_from_gnu_hash(uintptr_t hash_addr) {
  if (!hash_addr) return 0;
  const uint32_t *header = reinterpret_cast<const uint32_t *>(hash_addr);
  uint32_t nbuckets = header[0];
  uint32_t symoffset = header[1];
  uint32_t bloom_size = header[2];
  if (nbuckets == 0 || bloom_size == 0 || nbuckets > 65536U || bloom_size > 65536U) return 0;
  const uintptr_t *bloom = reinterpret_cast<const uintptr_t *>(header + 4);
  const uint32_t *buckets = reinterpret_cast<const uint32_t *>(bloom + bloom_size);
  const uint32_t *chains = buckets + nbuckets;
  uint32_t max_sym = 0;
  for (uint32_t i = 0; i < nbuckets; ++i) {
    if (buckets[i] > max_sym) max_sym = buckets[i];
  }
  if (max_sym < symoffset) return static_cast<size_t>(symoffset);
  uint32_t idx = max_sym;
  for (uint32_t guard = 0; guard < 65536U; ++guard, ++idx) {
    uint32_t chain = chains[idx - symoffset];
    if (chain & 1U) return static_cast<size_t>(idx + 1U);
  }
  return 0;
}

uintptr_t find_exported_symbol(uintptr_t base, const char *symbol) {
  if (!base || !symbol || !valid_elf_header(base)) return 0;
  Elf64_Ehdr *eh = reinterpret_cast<Elf64_Ehdr *>(base);
  Elf64_Phdr *ph = reinterpret_cast<Elf64_Phdr *>(base + static_cast<uintptr_t>(eh->e_phoff));
  Elf64_Dyn *dyn = nullptr;
  size_t dyn_count = 0;
  for (int i = 0; i < eh->e_phnum; ++i) {
    if (ph[i].p_type == PT_DYNAMIC) {
      dyn = reinterpret_cast<Elf64_Dyn *>(base + static_cast<uintptr_t>(ph[i].p_vaddr));
      dyn_count = static_cast<size_t>(ph[i].p_memsz / sizeof(Elf64_Dyn));
      break;
    }
  }
  if (!dyn || dyn_count == 0) return 0;

  uintptr_t strtab_addr = 0;
  uintptr_t symtab_addr = 0;
  size_t strtab_size = 0;
  uintptr_t sysv_hash_addr = 0;
  uintptr_t gnu_hash_addr = 0;
  size_t sym_ent = sizeof(Elf64_Sym);

  for (size_t i = 0; i < dyn_count; ++i) {
    if (dyn[i].d_tag == DT_NULL) break;
    switch (dyn[i].d_tag) {
      case DT_STRTAB: strtab_addr = normalize_dyn_ptr(base, dyn[i].d_un.d_ptr); break;
      case DT_SYMTAB: symtab_addr = normalize_dyn_ptr(base, dyn[i].d_un.d_ptr); break;
      case DT_STRSZ: strtab_size = static_cast<size_t>(dyn[i].d_un.d_val); break;
      case DT_SYMENT: if (dyn[i].d_un.d_val) sym_ent = static_cast<size_t>(dyn[i].d_un.d_val); break;
      case DT_HASH: sysv_hash_addr = normalize_dyn_ptr(base, dyn[i].d_un.d_ptr); break;
      case DT_GNU_HASH: gnu_hash_addr = normalize_dyn_ptr(base, dyn[i].d_un.d_ptr); break;
      default: break;
    }
  }

  if (!strtab_addr || !symtab_addr || sym_ent != sizeof(Elf64_Sym)) return 0;
  size_t sym_count = dynsym_count_from_sysv_hash(sysv_hash_addr);
  if (sym_count == 0) sym_count = dynsym_count_from_gnu_hash(gnu_hash_addr);
  if (sym_count == 0 || sym_count > 65536U) return 0;

  const char *strtab = reinterpret_cast<const char *>(strtab_addr);
  Elf64_Sym *symtab = reinterpret_cast<Elf64_Sym *>(symtab_addr);
  for (size_t i = 0; i < sym_count; ++i) {
    if (symtab[i].st_name == 0 || symtab[i].st_value == 0) continue;
    if (strtab_size > 0 && symtab[i].st_name >= strtab_size) continue;
    const char *name = strtab + symtab[i].st_name;
    if (streq(name, symbol)) return normalize_dyn_ptr(base, symtab[i].st_value);
  }
  return 0;
}

void clear_code_cache(uintptr_t start, size_t len) {
  if (!start || len == 0) return;
#if defined(__aarch64__)
  unsigned long ctr = 0;
  asm volatile("mrs %0, ctr_el0" : "=r"(ctr));
  size_t dline = static_cast<size_t>(4UL << ((ctr >> 16) & 0xFU));
  size_t iline = static_cast<size_t>(4UL << (ctr & 0xFU));
  if (dline == 0) dline = 64;
  if (iline == 0) iline = 64;
  uintptr_t end = start + len;
  uintptr_t p = start & ~(static_cast<uintptr_t>(dline - 1));
  for (; p < end; p += dline) {
    asm volatile("dc cvau, %0" :: "r"(p) : "memory");
  }
  asm volatile("dsb ish" ::: "memory");
  p = start & ~(static_cast<uintptr_t>(iline - 1));
  for (; p < end; p += iline) {
    asm volatile("ic ivau, %0" :: "r"(p) : "memory");
  }
  asm volatile("dsb ish" ::: "memory");
  asm volatile("isb" ::: "memory");
#else
  (void)start;
  (void)len;
#endif
}

void write_abs_jump_aarch64(uintptr_t where, void *target) {
  uint32_t *insn = reinterpret_cast<uint32_t *>(where);
  // ldr x16, #8 ; br x16 ; .quad target
  insn[0] = 0x58000050U;
  insn[1] = 0xd61f0200U;
  *reinterpret_cast<uintptr_t *>(where + 8) = reinterpret_cast<uintptr_t>(target);
}

bool aarch64_is_bti(uint32_t insn) {
  // Common BTI landing pads emitted by Android toolchains. Keeping the first
  // BTI instruction in place avoids BTI faults on indirect calls into libc.
  return insn == 0xd503245fU || insn == 0xd503241fU || insn == 0xd503249fU || insn == 0xd50324dfU;
}

bool aarch64_is_uncond_b(uint32_t insn) {
  return (insn & 0xfc000000U) == 0x14000000U;
}

uintptr_t aarch64_branch_imm_target(uintptr_t pc, uint32_t insn) {
  int32_t imm26 = static_cast<int32_t>(insn & 0x03ffffffU);
  if (imm26 & 0x02000000) imm26 |= static_cast<int32_t>(0xfc000000U);
  return pc + (static_cast<int64_t>(imm26) << 2);
}

uintptr_t resolve_aarch64_branch_target(uintptr_t target) {
  uintptr_t cur = target;
  for (int i = 0; i < INLINE_BRANCH_FOLLOW_LIMIT; ++i) {
    if (!cur || (cur & 0x3U) != 0) break;
    uint32_t insn = *reinterpret_cast<uint32_t *>(cur);
    if (!aarch64_is_uncond_b(insn)) break;
    uintptr_t next = aarch64_branch_imm_target(cur, insn);
    if (!next || next == cur) break;
    cur = next;
  }
  return cur;
}

bool aarch64_is_pc_relative_or_control(uint32_t insn) {
  // B/BL immediate.
  if ((insn & 0x7c000000U) == 0x14000000U) return true;
  // B.cond.
  if ((insn & 0xff000010U) == 0x54000000U) return true;
  // CBZ/CBNZ.
  if ((insn & 0x7e000000U) == 0x34000000U) return true;
  // TBZ/TBNZ.
  if ((insn & 0x7e000000U) == 0x36000000U) return true;
  // BR/BLR/RET family.
  if ((insn & 0xfffffc1fU) == 0xd61f0000U) return true;
  if ((insn & 0xfffffc1fU) == 0xd63f0000U) return true;
  if ((insn & 0xfffffc1fU) == 0xd65f0000U) return true;
  // ADR/ADRP.
  if ((insn & 0x9f000000U) == 0x10000000U) return true;
  if ((insn & 0x9f000000U) == 0x90000000U) return true;
  // LDR/PRFM literal. These embed PC-relative offsets.
  if ((insn & 0x3b000000U) == 0x18000000U) return true;
  return false;
}

bool aarch64_prologue_can_be_copied(uintptr_t target, size_t copy_size) {
  if (!target || (target & 0x3U) != 0 || copy_size == 0 || (copy_size & 0x3U) != 0) return false;
  uint32_t *insn = reinterpret_cast<uint32_t *>(target);
  size_t count = copy_size / 4;
  for (size_t i = 0; i < count; ++i) {
    if (i == 0 && aarch64_is_bti(insn[i])) continue;
    if (aarch64_is_pc_relative_or_control(insn[i])) return false;
  }
  return true;
}

void *alloc_trampoline_page() {
  long rc = syscall(__NR_mmap,
                    nullptr,
                    static_cast<size_t>(PAGE_SIZE),
                    PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS,
                    -1,
                    0);
  if (rc <= 0 || rc == -1) return nullptr;
  return reinterpret_cast<void *>(static_cast<uintptr_t>(rc));
}

bool make_trampoline_executable(void *page) {
  if (!page) return false;
  return syscall(__NR_mprotect, page, static_cast<size_t>(PAGE_SIZE), PROT_READ | PROT_EXEC) == 0;
}

bool make_range_writable(uintptr_t addr, size_t len, int *old_prot) {
  if (!addr || len == 0) return false;
  uintptr_t start = addr & ~(static_cast<uintptr_t>(PAGE_SIZE - 1));
  uintptr_t end = (addr + len + PAGE_SIZE - 1) & ~(static_cast<uintptr_t>(PAGE_SIZE - 1));
  int prot = protection_for_address(addr, PROT_READ | PROT_EXEC);
  if (old_prot) *old_prot = prot;
  int new_prot = prot | PROT_READ | PROT_WRITE | PROT_EXEC;
  long rc = syscall(__NR_mprotect, reinterpret_cast<void *>(start), static_cast<size_t>(end - start), new_prot);
  return rc == 0;
}

void restore_range_prot(uintptr_t addr, size_t len, int old_prot) {
  if (!addr || len == 0) return;
  uintptr_t start = addr & ~(static_cast<uintptr_t>(PAGE_SIZE - 1));
  uintptr_t end = (addr + len + PAGE_SIZE - 1) & ~(static_cast<uintptr_t>(PAGE_SIZE - 1));
  syscall(__NR_mprotect, reinterpret_cast<void *>(start), static_cast<size_t>(end - start), old_prot);
}

bool install_inline_hook_at(uintptr_t target, void *replacement, void **orig_slot) {
  if (!target || !replacement || !orig_slot) return false;
  if ((target & 0x3U) != 0) return false;

  uintptr_t resolved = resolve_aarch64_branch_target(target);
  if (!resolved || (resolved & 0x3U) != 0) return false;
  target = resolved;

  uint32_t first = *reinterpret_cast<uint32_t *>(target);
  size_t patch_offset = aarch64_is_bti(first) ? 4U : 0U;
  size_t copy_size = patch_offset + INLINE_STUB_SIZE;
  size_t trampoline_size = copy_size + INLINE_STUB_SIZE;
  if (trampoline_size > INLINE_TRAMPOLINE_SIZE) return false;
  if (!aarch64_prologue_can_be_copied(target, copy_size)) return false;

  void *trampoline = alloc_trampoline_page();
  if (!trampoline) return false;

  memcpy(trampoline, reinterpret_cast<void *>(target), copy_size);
  write_abs_jump_aarch64(reinterpret_cast<uintptr_t>(trampoline) + copy_size,
                         reinterpret_cast<void *>(target + copy_size));
  clear_code_cache(reinterpret_cast<uintptr_t>(trampoline), trampoline_size);
  if (!make_trampoline_executable(trampoline)) {
    syscall(__NR_munmap, trampoline, static_cast<size_t>(PAGE_SIZE));
    return false;
  }

  int old_prot = PROT_READ | PROT_EXEC;
  if (!make_range_writable(target + patch_offset, INLINE_STUB_SIZE, &old_prot)) {
    syscall(__NR_munmap, trampoline, static_cast<size_t>(PAGE_SIZE));
    return false;
  }
  write_abs_jump_aarch64(target + patch_offset, replacement);
  clear_code_cache(target, copy_size);
  restore_range_prot(target + patch_offset, INLINE_STUB_SIZE, old_prot);

  *orig_slot = trampoline;
  return true;
}

bool install_libc_inline_symbol(uintptr_t libc_base, const char *symbol, void *hook, void **orig_slot) {
  if (!hook_symbol_enabled(symbol)) return false;
  if (!libc_base || !symbol || !hook || !orig_slot) return false;
  if (*orig_slot) return true;
  uintptr_t target = find_exported_symbol(libc_base, symbol);
  if (!target) {
    g_inline_symbols_missing++;
    return false;
  }
  g_inline_symbols_found++;
  bool ok = install_inline_hook_at(target, hook, orig_slot);
  if (!ok) {
    g_inline_hooks_failed++;
  }
  return ok;
}

int install_libc_inline_hooks() {
  g_inline_hooks_installed = 0;
  g_inline_hooks_failed = 0;
  g_inline_symbols_found = 0;
  g_inline_symbols_missing = 0;
  g_libc_inline_ready = false;
  if (!g_target || !g_feature_libc_inline_hooking || !runtime_hiding_enabled()) return 0;
  uintptr_t libc_base = find_loaded_library_base("/libc.so");
  if (!libc_base) {
    return 0;
  }

#define ZDT_INLINE_HOOK(sym, hook_fn, orig_slot) \
  do { \
    if (install_libc_inline_symbol(libc_base, sym, reinterpret_cast<void *>(hook_fn), reinterpret_cast<void **>(orig_slot))) { \
      g_inline_hooks_installed++; \
    } \
  } while (0)

  ZDT_INLINE_HOOK("getifaddrs", hooked_getifaddrs, &orig_getifaddrs);
  ZDT_INLINE_HOOK("ioctl", hooked_ioctl, &orig_ioctl);
  ZDT_INLINE_HOOK("open", hooked_open, &orig_open);
  ZDT_INLINE_HOOK("openat", hooked_openat, &orig_openat);
  ZDT_INLINE_HOOK("fopen", hooked_fopen, &orig_fopen);
  ZDT_INLINE_HOOK("fopen64", hooked_fopen64, &orig_fopen64);
  ZDT_INLINE_HOOK("recvmsg", hooked_recvmsg, &orig_recvmsg);
  ZDT_INLINE_HOOK("recv", hooked_recv, &orig_recv);
  ZDT_INLINE_HOOK("recvfrom", hooked_recvfrom, &orig_recvfrom);
  ZDT_INLINE_HOOK("read", hooked_read, &orig_read);
  ZDT_INLINE_HOOK("readv", hooked_readv, &orig_readv);
  ZDT_INLINE_HOOK("if_nametoindex", hooked_if_nametoindex, &orig_if_nametoindex);
  ZDT_INLINE_HOOK("if_indextoname", hooked_if_indextoname, &orig_if_indextoname);

#undef ZDT_INLINE_HOOK

  g_libc_inline_ready = g_inline_hooks_installed > 0;
  return g_inline_hooks_installed;
}

uintptr_t late_patch_base_for_record(const MapsRecord &rec) {
  if (!rec.path[0]) return 0;
  if (strstr(rec.path, "/ZDT-D/zygisk/") != nullptr) return 0;
  if (is_chromium_webview_library_path(rec.path)) return 0;
  if (strstr(rec.path, "/libc.so") != nullptr || strstr(rec.path, "/libdl.so") != nullptr || strstr(rec.path, "/linker") != nullptr) return 0;

  // Normal extracted .so: first mapping usually has offset 0 and a valid ELF
  // header at rec.start. Embedded APK native libs also have a valid ELF header
  // at rec.start, but rec.offset is the offset inside base.apk, so the old
  // offset==0 filter skipped them completely.
  if ((is_app_owned_native_container_path(rec.path) || contains_framework_hook_target(rec.path)) &&
      rec.perms[0] == 'r' && valid_elf_header(rec.start)) {
    return rec.start;
  }

  return 0;
}


int install_libdl_inline_hooks() {
  if (!g_target || !g_feature_late_hooking || !runtime_hiding_enabled()) return 0;
  g_libdl_inline_ready = false;
  int installed = 0;
  uintptr_t libdl_base = find_loaded_library_base("/libdl.so");
  if (!libdl_base) return 0;

  // Hook the loader entry points below app/framework PLT level. System.loadLibrary()
  // may go through runtime/native-loader paths that never hit the framework PLT
  // entries registered during postAppSpecialize. Hooking libdl keeps the trigger
  // in one stable place and lets us rescan maps after every successful load.
  if (install_libc_inline_symbol(libdl_base, "dlopen", reinterpret_cast<void *>(hooked_dlopen), reinterpret_cast<void **>(&orig_dlopen))) installed++;
  if (install_libc_inline_symbol(libdl_base, "android_dlopen_ext", reinterpret_cast<void *>(hooked_android_dlopen_ext), reinterpret_cast<void **>(&orig_android_dlopen_ext))) installed++;

  g_libdl_inline_ready = installed > 0;
  return installed;
}

constexpr int JNI_REGISTER_NATIVES_INDEX = 215;

int hooked_RegisterNatives(JNIEnv *env, jobject clazz, const JNINativeMethod *methods, int num_methods) {
  int rc = 0;
  if (!orig_RegisterNatives) return -1;
  if (!enter_hook_guard()) return orig_RegisterNatives(env, clazz, methods, num_methods);
  rc = orig_RegisterNatives(env, clazz, methods, num_methods);
  leave_hook_guard();

  // JNI libraries often call RegisterNatives from JNI_OnLoad immediately after
  // System.loadLibrary(). If dlopen/android_dlopen_ext was not intercepted on a
  // particular Android build, this callback is still an early trigger before the
  // app invokes the newly registered native methods.
  if (g_target && g_commit_ok && runtime_hiding_enabled() && g_feature_late_hooking) {
    hook_late_loaded_libraries();
  }
  return rc;
}

bool install_jni_register_natives_hook(JNIEnv *env) {
  if (!env || !env->functions || !g_target || !g_feature_late_hooking || g_jni_register_natives_hooked) return false;
  void **table = reinterpret_cast<void **>(const_cast<void *>(env->functions));
  if (!table) return false;
  void *current = table[JNI_REGISTER_NATIVES_INDEX];
  if (!current || current == reinterpret_cast<void *>(hooked_RegisterNatives)) {
    g_jni_register_natives_hooked = current == reinterpret_cast<void *>(hooked_RegisterNatives);
    return g_jni_register_natives_hooked;
  }

  uintptr_t slot_addr = reinterpret_cast<uintptr_t>(&table[JNI_REGISTER_NATIVES_INDEX]);
  int old_prot = PROT_READ;
  if (!make_page_writable(slot_addr, &old_prot)) return false;
  orig_RegisterNatives = reinterpret_cast<RegisterNativesFn>(current);
  table[JNI_REGISTER_NATIVES_INDEX] = reinterpret_cast<void *>(hooked_RegisterNatives);
  restore_page_prot(slot_addr, old_prot);
  g_jni_register_natives_hooked = true;
  return true;
}

int hook_late_loaded_libraries() {
  if (!g_target || !g_commit_ok || !runtime_hiding_enabled()) return 0;
  if (g_hook_depth > 0) return 0;
  g_late_hook_passes++;
  char maps[READ_LIMIT];
  int n = read_file_at(AT_FDCWD, "/proc/self/maps", maps, READ_LIMIT);
  if (n <= 0) return 0;
  int patched_total = 0;
  const char *p = maps;
  while (*p) {
    const char *line = p;
    const char *end = strchr(line, '\n');
    int len = end ? static_cast<int>(end - line) : static_cast<int>(strlen(line));
    MapsRecord rec;
    if (parse_maps_record_line(line, len, &rec)) {
      if (should_late_patch_hooks_for_path(rec.path)) {
        uintptr_t base = late_patch_base_for_record(rec);
        if (base && !late_patch_already_seen(rec.dev, rec.ino, base)) {
          int patched = internal_patch_plt_for_library(base);
          remember_late_patch(rec.dev, rec.ino, base);
          if (patched > 0) {
            patched_total += patched;
            g_late_hook_patches += patched;
          }
        }
      }
    }
    if (!end) break;
    p = end + 1;
  }
  return patched_total;
}

void *hooked_dlopen(const char *filename, int flags) {
  if (!orig_dlopen) return nullptr;
  if (!enter_hook_guard()) return orig_dlopen(filename, flags);
  void *result = orig_dlopen(filename, flags);
  leave_hook_guard();
  if (result && g_target && g_commit_ok && runtime_hiding_enabled() && g_feature_late_hooking) hook_late_loaded_libraries();
  return result;
}

void *hooked_android_dlopen_ext(const char *filename, int flags, const void *extinfo) {
  if (!orig_android_dlopen_ext) return nullptr;
  if (!enter_hook_guard()) return orig_android_dlopen_ext(filename, flags, extinfo);
  void *result = orig_android_dlopen_ext(filename, flags, extinfo);
  leave_hook_guard();
  if (result && g_target && g_commit_ok && runtime_hiding_enabled() && g_feature_late_hooking) hook_late_loaded_libraries();
  return result;
}

int register_hooks_from_maps(zygisk::Api *api) {
  g_maps_elf_count = 0;
  g_registered_hooks = 0;
  g_commit_ok = 0;
  if (!api) return 0;
  char maps[READ_LIMIT];
  int n = read_file_at(AT_FDCWD, "/proc/self/maps", maps, READ_LIMIT);
  if (n <= 0) return 0;
  dev_t devs[256];
  ino_t inos[256];
  int seen = 0;
  int registered = 0;
  const char *p = maps;
  while (*p) {
    const char *line = p;
    const char *end = strchr(line, '\n');
    int len = end ? static_cast<int>(end - line) : static_cast<int>(strlen(line));
    if (len > 0 && len < 1024) {
      char tmp[1024];
      for (int i = 0; i < len; ++i) tmp[i] = line[i];
      tmp[len] = 0;
      const char *q = tmp;
      q = skip_token(q);
      const char *perms = q;
      bool executable = perms[0] && perms[2] == 'x';
      q = skip_token(q);
      q = skip_token(q);
      const char *after_major = nullptr;
      unsigned long long major = parse_hex_u64(q, &after_major);
      if (after_major && *after_major == ':') {
        const char *after_minor = nullptr;
        unsigned long long minor = parse_hex_u64(after_major + 1, &after_minor);
        q = after_minor;
        while (*q && is_space(*q)) q++;
        const char *after_inode = nullptr;
        unsigned long long inode = parse_dec_u64(q, &after_inode);
        q = after_inode;
        while (*q && is_space(*q)) q++;
        bool has_path = *q == '/';
        bool hook_path = has_path && should_register_hooks_for_path(q);
        if (executable && hook_path && inode > 0 && seen < 256) {
          dev_t dev = make_dev_id(major, minor);
          ino_t ino = static_cast<ino_t>(inode);
          if (!seen_dev_inode(devs, inos, seen, dev, ino)) {
            devs[seen] = dev; inos[seen] = ino; seen++;
            g_maps_elf_count = seen;
#define ZDT_REGISTER_PLT(symbol_name, hook_fn, orig_slot) \
            do { \
              if (should_register_plt_symbol(symbol_name)) { \
                api->pltHookRegister(dev, ino, symbol_name, reinterpret_cast<void *>(hook_fn), reinterpret_cast<void **>(orig_slot)); \
                registered++; \
              } \
            } while (0)
            ZDT_REGISTER_PLT("getifaddrs", hooked_getifaddrs, &orig_getifaddrs);
            ZDT_REGISTER_PLT("open", hooked_open, &orig_open);
            ZDT_REGISTER_PLT("openat", hooked_openat, &orig_openat);
            ZDT_REGISTER_PLT("ioctl", hooked_ioctl, &orig_ioctl);
            ZDT_REGISTER_PLT("recvmsg", hooked_recvmsg, &orig_recvmsg);
            ZDT_REGISTER_PLT("recv", hooked_recv, &orig_recv);
            ZDT_REGISTER_PLT("recvfrom", hooked_recvfrom, &orig_recvfrom);
            ZDT_REGISTER_PLT("read", hooked_read, &orig_read);
            ZDT_REGISTER_PLT("readv", hooked_readv, &orig_readv);
            ZDT_REGISTER_PLT("opendir", hooked_opendir, &orig_opendir);
            ZDT_REGISTER_PLT("readdir", hooked_readdir, &orig_readdir);
            ZDT_REGISTER_PLT("readdir64", hooked_readdir64, &orig_readdir64);
            ZDT_REGISTER_PLT("closedir", hooked_closedir, &orig_closedir);
            ZDT_REGISTER_PLT("fopen", hooked_fopen, &orig_fopen);
            ZDT_REGISTER_PLT("fopen64", hooked_fopen64, &orig_fopen64);
            ZDT_REGISTER_PLT("if_nametoindex", hooked_if_nametoindex, &orig_if_nametoindex);
            ZDT_REGISTER_PLT("if_indextoname", hooked_if_indextoname, &orig_if_indextoname);
            ZDT_REGISTER_PLT("access", hooked_access, &orig_access);
            ZDT_REGISTER_PLT("faccessat", hooked_faccessat, &orig_faccessat);
            ZDT_REGISTER_PLT("stat", hooked_stat, &orig_stat);
            ZDT_REGISTER_PLT("stat64", hooked_stat64, &orig_stat64);
            ZDT_REGISTER_PLT("lstat", hooked_lstat, &orig_lstat);
            ZDT_REGISTER_PLT("lstat64", hooked_lstat64, &orig_lstat64);
            ZDT_REGISTER_PLT("fstatat", hooked_fstatat, &orig_fstatat);
            ZDT_REGISTER_PLT("fstatat64", hooked_fstatat64, &orig_fstatat64);
            ZDT_REGISTER_PLT("statx", hooked_statx, &orig_statx);
            ZDT_REGISTER_PLT("readlink", hooked_readlink, &orig_readlink);
            ZDT_REGISTER_PLT("readlinkat", hooked_readlinkat, &orig_readlinkat);
            ZDT_REGISTER_PLT("dlopen", hooked_dlopen, &orig_dlopen);
            ZDT_REGISTER_PLT("android_dlopen_ext", hooked_android_dlopen_ext, &orig_android_dlopen_ext);
#undef ZDT_REGISTER_PLT
          }
        }
      }
    }
    if (!end) break;
    p = end + 1;
  }
  g_registered_hooks = registered;
  return registered;
}

class ZdtdZygiskModule : public zygisk::ModuleBase {
public:
  void onLoad(zygisk::Api *api, JNIEnv *env) override { api_ = api; env_ = env; }

  void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
    const int uid = args ? args->uid : -1;
    g_uid = uid;
    g_enabled = false;
    g_target = false;
    g_should_install_hooks = false;
    g_interface_count = 0;
    g_hidden_index_count = 0;
    g_late_patched_count = 0;
    g_late_hook_passes = 0;
    g_late_hook_patches = 0;
    g_inline_hooks_installed = 0;
    g_inline_hooks_failed = 0;
    g_inline_symbols_found = 0;
    g_inline_symbols_missing = 0;
    g_libc_inline_ready = false;
    g_libdl_inline_ready = false;
    g_jni_register_natives_hooked = false;
    g_tracked_dir_count = 0;
    g_maps_elf_count = 0;
    g_registered_hooks = 0;
    g_commit_ok = 0;
    g_getifaddrs_hits = 0;
    g_proc_hits = 0;
    g_ioctl_hits = 0;
    g_netlink_hits = 0;
    g_start_enabled = false;
    g_proxyinfo_enabled = false;
    g_uid_runtime_allowed = false;
    reset_feature_flags_to_legacy_defaults();
    g_last_reload_at = 0;
    g_current_ttl = RUNTIME_TTL_FAST;
    g_runtime_loaded_once = false;
    close_runtime_watcher();
    g_process_name[0] = 0;
    g_is_child_zygote = false;
    g_isolated_uid = false;
    close_module_fd_if_open();

    copy_jstring_utf(env_, args ? args->nice_name : nullptr, g_process_name, sizeof(g_process_name));
    g_is_child_zygote = is_child_zygote_args(args);
    g_isolated_uid = is_android_isolated_uid(uid);
    if (g_is_child_zygote || g_isolated_uid || (g_process_name[0] && is_forbidden_process_name(g_process_name))) {
      if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
      return;
    }

    // Use the module directory fd only for confirmed target processes, because
    // absolute /data/adb/modules/... paths may be unavailable inside app namespaces.
    // The fd is not exempted before target confirmation, so non-target processes
    // do not keep a module fd open.
    g_module_fd = api_ ? api_->getModuleDir() : MODULE_FD_NONE;

    static char targets[READ_LIMIT];
    int target_len = read_file_module_or_abs(
        g_module_fd,
        "working_folder/proxyInfo/out_program",
        ABS_TARGETS_PATH,
        targets,
        READ_LIMIT);
    g_target = target_len > 0 && uid_in_out_program(targets, uid);
    if (!g_target) {
      close_module_fd_if_open();
      if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
      return;
    }

    g_should_install_hooks = true;
    reload_runtime_state(true, "runtime_initial_load");

    // Do not keep an fd pointing into /data/adb/modules in the app process.
    // Some protected apps scan /proc/self/fd during splash-screen checks.
    close_module_fd_if_open();
  }

  void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
    if (!g_target || !g_should_install_hooks) return;
    reload_runtime_state(false, nullptr);

    // Primary path first: install libc-level inline hooks before any PLT
    // fallback. The orig_* slots must contain libc trampolines for hooks such
    // as getifaddrs/ioctl/openat/recv; registering PLT hooks first can fill the
    // same slots and prevent the libc hook from being installed at all.
    int inline_hooks = install_libc_inline_hooks();

    // Loader trigger path: hook libdl directly, below app/framework PLT. This is
    // used only as a rescan trigger for newly loaded JNI libraries; app-owned
    // libraries are still patched in-memory only after they are mapped.
    int libdl_hooks = install_libdl_inline_hooks();

    // JNI fallback trigger: if a build routes System.loadLibrary through a path
    // we do not intercept, RegisterNatives in JNI_OnLoad still gives us a chance
    // to rescan and patch the newly loaded library before its native methods run.
    bool jni_hook = install_jni_register_natives_hook(env_);

    // Secondary path: framework-only PLT fallback. Core libc/libdl symbols already
    // covered by inline hooks are skipped inside register_hooks_from_maps() so
    // their orig_* trampoline slots are not overwritten by Zygisk's PLT API.
    int registered = register_hooks_from_maps(api_);
    bool committed = api_ && registered > 0 && api_->pltHookCommit();

    g_commit_ok = (committed || inline_hooks > 0 || libdl_hooks > 0 || jni_hook || g_feature_late_hooking) ? 1 : 0;
    if (g_commit_ok) {
      refresh_hidden_indices();
      if (runtime_hiding_enabled() && g_feature_late_hooking) hook_late_loaded_libraries();
    }
  }


private:
  zygisk::Api *api_ = nullptr;
  JNIEnv *env_ = nullptr;
};
} // namespace

REGISTER_ZYGISK_MODULE(ZdtdZygiskModule)
