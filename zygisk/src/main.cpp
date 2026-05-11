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
using uintptr_t = unsigned long;

extern "C" {
struct sockaddr {
  unsigned short sa_family;
  char sa_data[14];
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
struct rtattr {
  uint16_t rta_len;
  uint16_t rta_type;
};
struct DIR;
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
off_t lseek(int fd, off_t offset, int whence);
int ioctl(int fd, unsigned long request, ...);
ssize_t recvmsg(int sockfd, struct msghdr *msg, int flags);
DIR *opendir(const char *name);
struct dirent_like *readdir(DIR *dirp);
struct dirent_like *readdir64(DIR *dirp);
int closedir(DIR *dirp);
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
#define IFLA_IFNAME 3
#define NLMSG_ALIGNTO 4U
#define RTA_ALIGNTO 4U
#define NLMSG_ALIGN(len) (((len) + NLMSG_ALIGNTO - 1U) & ~(NLMSG_ALIGNTO - 1U))
#define RTA_ALIGN(len) (((len) + RTA_ALIGNTO - 1U) & ~(RTA_ALIGNTO - 1U))

#define va_list __builtin_va_list
#define va_start __builtin_va_start
#define va_arg __builtin_va_arg
#define va_end __builtin_va_end

namespace {
constexpr int MAX_INTERFACES = 32;
constexpr int IFACE_LEN = 32;
constexpr int READ_LIMIT = 65536;
constexpr int MODULE_FD_NONE = -1;
constexpr int MAX_TRACKED_DIRS = 16;
constexpr int MAX_HIDDEN_INDICES = 32;

using GetIfAddrsFn = int (*)(ifaddrs **);
using OpenFn = int (*)(const char *, int, ...);
using OpenAtFn = int (*)(int, const char *, int, ...);
using IoctlFn = int (*)(int, unsigned long, ...);
using RecvmsgFn = ssize_t (*)(int, msghdr *, int);
using OpendirFn = DIR *(*)(const char *);
using ReaddirFn = dirent_like *(*)(DIR *);
using ClosedirFn = int (*)(DIR *);
using IfNameToIndexFn = unsigned int (*)(const char *);
using IfIndexToNameFn = char *(*)(unsigned int, char *);

static GetIfAddrsFn orig_getifaddrs = nullptr;
static OpenFn orig_open = nullptr;
static OpenAtFn orig_openat = nullptr;
static IoctlFn orig_ioctl = nullptr;
static RecvmsgFn orig_recvmsg = nullptr;
static OpendirFn orig_opendir = nullptr;
static ReaddirFn orig_readdir = nullptr;
static ReaddirFn orig_readdir64 = nullptr;
static ClosedirFn orig_closedir = nullptr;
static IfNameToIndexFn orig_if_nametoindex = nullptr;
static IfIndexToNameFn orig_if_indextoname = nullptr;

static bool g_enabled = false;
static bool g_target = false;
static bool g_should_install_hooks = false;
static int g_module_fd = MODULE_FD_NONE;
static int g_uid = -1;
static char g_interfaces[MAX_INTERFACES][IFACE_LEN] = {};
static int g_interface_count = 0;
static unsigned int g_hidden_indices[MAX_HIDDEN_INDICES] = {};
static int g_hidden_index_count = 0;
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
static long g_last_reload_at = 0;
static int g_current_ttl = 30;
static bool g_runtime_loaded_once = false;

constexpr int RUNTIME_TTL_MIN = 30;
constexpr int RUNTIME_TTL_MAX = 400;
constexpr const char *ABS_TARGETS_PATH = "/data/adb/modules/ZDT-D/working_folder/proxyInfo/out_program";
constexpr const char *ABS_START_PATH = "/data/adb/modules/ZDT-D/setting/start.json";
constexpr const char *ABS_PROXYINFO_ENABLED_PATH = "/data/adb/modules/ZDT-D/working_folder/proxyInfo/enabled.json";
constexpr const char *ABS_APPLIED_PATH = "/data/adb/modules/ZDT-D/working_folder/vpn_netd/applied.json";
constexpr const char *ABS_STATUS_PATH = "/data/adb/modules/ZDT-D/working_folder/zygisk_status.json";

int hooked_getifaddrs(ifaddrs **out);
int hooked_open(const char *pathname, int flags, ...);
int hooked_openat(int dirfd, const char *pathname, int flags, ...);
int hooked_ioctl(int fd, unsigned long request, ...);
ssize_t hooked_recvmsg(int sockfd, msghdr *msg, int flags);
DIR *hooked_opendir(const char *name);
dirent_like *hooked_readdir(DIR *dirp);
dirent_like *hooked_readdir64(DIR *dirp);
int hooked_closedir(DIR *dirp);
unsigned int hooked_if_nametoindex(const char *ifname);
char *hooked_if_indextoname(unsigned int ifindex, char *ifname);

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

void write_status_file(int dirfd, const char *path, const char *body, int len) {
  if (!path || !body || len <= 0) return;
  int fd = openat(dirfd, path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (fd < 0) return;
  write(fd, body, static_cast<size_t>(len));
  close(fd);
}

void write_status_at(int dirfd, const char *phase, int uid, bool target, bool active, int iface_count, int hook_count) {
  char body[1536];
  long now = time(nullptr);
  int n = snprintf(
      body,
      sizeof(body),
      "{\"phase\":\"%s\",\"mode\":\"safe_selective_plt_dynamic\",\"uid\":%d,\"target\":%s,\"active\":%s,\"start_enabled\":%s,\"proxyinfo_enabled\":%s,\"interfaces\":%d,\"hooks\":%d,\"maps_elf\":%d,\"commit_ok\":%s,\"ttl_sec\":%d,\"hits\":{\"getifaddrs\":%d,\"proc\":%d,\"ioctl\":%d,\"netlink\":%d},\"updated_at\":%ld}\n",
      phase ? phase : "unknown",
      uid,
      target ? "true" : "false",
      active ? "true" : "false",
      g_start_enabled ? "true" : "false",
      g_proxyinfo_enabled ? "true" : "false",
      iface_count,
      hook_count,
      g_maps_elf_count,
      g_commit_ok ? "true" : "false",
      g_current_ttl,
      g_getifaddrs_hits,
      g_proc_hits,
      g_ioctl_hits,
      g_netlink_hits,
      now);
  if (n <= 0) return;

  if (dirfd >= 0) {
    write_status_file(dirfd, "working_folder/zygisk_status.json", body, n);
    if (uid > 0) {
      char rel[96];
      int rn = snprintf(rel, sizeof(rel), "working_folder/zygisk_status_%d.json", uid);
      if (rn > 0 && rn < static_cast<int>(sizeof(rel))) write_status_file(dirfd, rel, body, n);
    }
  }

  write_status_file(AT_FDCWD, ABS_STATUS_PATH, body, n);
  if (uid > 0) {
    char abs[160];
    int an = snprintf(abs, sizeof(abs), "/data/adb/modules/ZDT-D/working_folder/zygisk_status_%d.json", uid);
    if (an > 0 && an < static_cast<int>(sizeof(abs))) write_status_file(AT_FDCWD, abs, body, n);
  }
}

int read_file_module_or_abs(int dirfd, const char *rel_path, const char *abs_path, char *buf, int cap) {
  int n = -1;
  if (dirfd >= 0 && rel_path) n = read_file_at(dirfd, rel_path, buf, cap);
  if (n > 0) return n;
  if (abs_path) n = read_file_at(AT_FDCWD, abs_path, buf, cap);
  return n;
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
          *eq = 0;
          char *digits = eq + 1;
          trim(tmp);
          trim(digits);
          int parsed_uid = -1;
          if (tmp[0] && parse_positive_int(digits, &parsed_uid) && parsed_uid == uid) return true;
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
  if (changed || g_current_ttl < RUNTIME_TTL_MIN) {
    g_current_ttl = RUNTIME_TTL_MIN;
    return;
  }
  if (g_current_ttl < 60) g_current_ttl = 60;
  else if (g_current_ttl < 120) g_current_ttl = 120;
  else if (g_current_ttl < 240) g_current_ttl = 240;
  else g_current_ttl = RUNTIME_TTL_MAX;
}

bool reload_runtime_state(bool force, const char *phase) {
  if (!g_target) {
    g_enabled = false;
    return false;
  }
  long now = time(nullptr);
  if (!force && g_runtime_loaded_once && g_last_reload_at > 0 && (now - g_last_reload_at) < g_current_ttl) {
    return g_enabled;
  }

  bool old_enabled = g_enabled;
  bool old_start = g_start_enabled;
  bool old_proxy = g_proxyinfo_enabled;
  int old_count = g_interface_count;
  char old_names[MAX_INTERFACES][IFACE_LEN];
  for (int i = 0; i < MAX_INTERFACES; ++i) {
    for (int j = 0; j < IFACE_LEN; ++j) old_names[i][j] = g_interfaces[i][j];
  }

  static char start_json[4096];
  static char proxy_json[4096];
  static char applied_json[READ_LIMIT];

  int start_len = read_file_module_or_abs(g_module_fd, "setting/start.json", ABS_START_PATH, start_json, sizeof(start_json));
  int proxy_len = read_file_module_or_abs(g_module_fd, "working_folder/proxyInfo/enabled.json", ABS_PROXYINFO_ENABLED_PATH, proxy_json, sizeof(proxy_json));
  int applied_len = read_file_module_or_abs(g_module_fd, "working_folder/vpn_netd/applied.json", ABS_APPLIED_PATH, applied_json, READ_LIMIT);

  g_start_enabled = start_len > 0 && json_enabled_value_true(start_json);
  g_proxyinfo_enabled = proxy_len > 0 && json_enabled_value_true(proxy_json);
  parse_applied_interfaces_json(applied_len > 0 ? applied_json : nullptr);
  g_enabled = g_start_enabled && g_proxyinfo_enabled && g_interface_count > 0;

  bool changed = old_enabled != g_enabled || old_start != g_start_enabled || old_proxy != g_proxyinfo_enabled || !interfaces_equal_snapshot(old_count, old_names);
  update_adaptive_ttl(changed);
  g_last_reload_at = now;
  g_runtime_loaded_once = true;
  if (orig_if_nametoindex) refresh_hidden_indices();

  if (force || changed) {
    const char *st = g_enabled ? "runtime_enabled" : "runtime_disabled";
    write_status_at(g_module_fd, phase ? phase : st, g_uid, g_target, g_enabled, g_interface_count, g_registered_hooks);
  }
  return g_enabled;
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
         streq(owner, "amneziawg");
}

void parse_applied_interfaces_json(const char *json) {
  g_interface_count = 0;
  if (!json) return;
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
  return false;
}

bool is_hidden_index(unsigned int idx) {
  reload_runtime_state(false, nullptr);
  if (!g_enabled || idx == 0) return false;
  for (int i = 0; i < g_hidden_index_count; ++i) if (g_hidden_indices[i] == idx) return true;
  return false;
}

void refresh_hidden_indices() {
  g_hidden_index_count = 0;
  if (!orig_if_nametoindex) return;
  for (int i = 0; i < g_interface_count && g_hidden_index_count < MAX_HIDDEN_INDICES; ++i) {
    unsigned int idx = orig_if_nametoindex(g_interfaces[i]);
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

enum ProcKind { PROC_NONE = 0, PROC_DEV, PROC_ROUTE, PROC_IF_INET6 };

ProcKind classify_proc_path(const char *path) {
  if (!path) return PROC_NONE;
  if (streq(path, "/proc/net/dev") || streq(path, "/proc/self/net/dev")) return PROC_DEV;
  if (streq(path, "/proc/net/route") || streq(path, "/proc/self/net/route")) return PROC_ROUTE;
  if (streq(path, "/proc/net/if_inet6") || streq(path, "/proc/self/net/if_inet6")) return PROC_IF_INET6;
  return PROC_NONE;
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
    else if (kind == PROC_IF_INET6) hide = line_has_hidden_ifinet6_iface(in + start, line_len);
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

int make_filtered_proc_fd(const char *path, ProcKind kind) {
  if (!g_enabled || !orig_openat || g_module_fd < 0 || kind == PROC_NONE) return -1;
  int src = orig_openat(AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
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
  char tmp_path[96];
  const char *kind_name = kind == PROC_DEV ? "dev" : (kind == PROC_ROUTE ? "route" : "ifinet6");
  snprintf(tmp_path, sizeof(tmp_path), "working_folder/zygisk_%s_%d.tmp", kind_name, g_uid);
  int fd = orig_openat(g_module_fd, tmp_path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
  if (fd < 0) return -1;
  if (filtered_len > 0) write(fd, filtered, static_cast<size_t>(filtered_len));
  lseek(fd, 0, SEEK_SET);
  return fd;
}

bool sys_class_net_path(const char *path) {
  return streq(path, "/sys/class/net") || streq(path, "/sys/class/net/");
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

bool nlmsg_has_hidden_ifname(nlmsghdr *hdr) {
  if (!hdr || hdr->nlmsg_len < sizeof(nlmsghdr) + sizeof(ifinfomsg)) return false;
  if (hdr->nlmsg_type != RTM_NEWLINK) return false;
  char *base = reinterpret_cast<char *>(hdr);
  int offset = static_cast<int>(sizeof(nlmsghdr) + sizeof(ifinfomsg));
  int total = static_cast<int>(hdr->nlmsg_len);
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

int hooked_getifaddrs(ifaddrs **out) {
  g_getifaddrs_hits++;
  if (!orig_getifaddrs) return -1;
  int rc = orig_getifaddrs(out);
  if (rc != 0 || !out || !*out || !g_enabled) return rc;
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
  ProcKind kind = ((flags & O_ACCMODE) == O_RDONLY) ? classify_proc_path(pathname) : PROC_NONE;
  if (kind != PROC_NONE) {
    int fd = make_filtered_proc_fd(pathname, kind);
    if (fd >= 0) { g_proc_hits++; return fd; }
  }
  if (!orig_open) return -1;
  if (flags & O_CREAT) return orig_open(pathname, flags, mode);
  return orig_open(pathname, flags);
}

int hooked_openat(int dirfd, const char *pathname, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
  ProcKind kind = (dirfd == AT_FDCWD && ((flags & O_ACCMODE) == O_RDONLY)) ? classify_proc_path(pathname) : PROC_NONE;
  if (kind != PROC_NONE) {
    int fd = make_filtered_proc_fd(pathname, kind);
    if (fd >= 0) { g_proc_hits++; return fd; }
  }
  if (!orig_openat) return -1;
  if (flags & O_CREAT) return orig_openat(dirfd, pathname, flags, mode);
  return orig_openat(dirfd, pathname, flags);
}

int hooked_ioctl(int fd, unsigned long request, ...) {
  va_list ap;
  va_start(ap, request);
  void *arg = va_arg(ap, void *);
  va_end(ap);
  if (!orig_ioctl) return -1;
  if (!g_enabled || !arg) return orig_ioctl(fd, request, arg);
  if (request == SIOCGIFCONF) {
    g_ioctl_hits++;
    int rc = orig_ioctl(fd, request, arg);
    if (rc == 0) filter_ifconf(reinterpret_cast<ifconf *>(arg));
    return rc;
  }
  if (request_uses_ifr_name(request)) {
    g_ioctl_hits++;
    ifreq *ifr = reinterpret_cast<ifreq *>(arg);
    if (is_hidden_interface(ifr->ifr_name)) { set_errno_value(ENODEV); return -1; }
  }
  if (request == SIOCGIFNAME) {
    int rc = orig_ioctl(fd, request, arg);
    if (rc == 0) {
      ifreq *ifr = reinterpret_cast<ifreq *>(arg);
      if (is_hidden_interface(ifr->ifr_name)) { set_errno_value(ENODEV); return -1; }
    }
    return rc;
  }
  return orig_ioctl(fd, request, arg);
}

ssize_t hooked_recvmsg(int sockfd, msghdr *msg, int flags) {
  if (!orig_recvmsg) return -1;
  ssize_t rc = orig_recvmsg(sockfd, msg, flags);
  if (rc <= 0 || !g_enabled || !msg || !msg->msg_iov || msg->msg_iovlen <= 0) return rc;
  iovec *iov = &msg->msg_iov[0];
  if (!iov->iov_base || iov->iov_len == 0) return rc;
  ssize_t max = rc < static_cast<ssize_t>(iov->iov_len) ? rc : static_cast<ssize_t>(iov->iov_len);
  g_netlink_hits++;
  return filter_netlink_buffer(reinterpret_cast<char *>(iov->iov_base), max);
}

DIR *hooked_opendir(const char *name) {
  if (!orig_opendir) return nullptr;
  DIR *dir = orig_opendir(name);
  if (g_enabled && dir && sys_class_net_path(name)) track_dir(dir);
  return dir;
}

dirent_like *hooked_readdir(DIR *dirp) {
  if (!orig_readdir) return nullptr;
  dirent_like *ent = nullptr;
  do { ent = orig_readdir(dirp); } while (ent && is_tracked_dir(dirp) && is_hidden_interface(ent->d_name));
  return ent;
}

dirent_like *hooked_readdir64(DIR *dirp) {
  if (!orig_readdir64) return nullptr;
  dirent_like *ent = nullptr;
  do { ent = orig_readdir64(dirp); } while (ent && is_tracked_dir(dirp) && is_hidden_interface(ent->d_name));
  return ent;
}

int hooked_closedir(DIR *dirp) {
  untrack_dir(dirp);
  return orig_closedir ? orig_closedir(dirp) : -1;
}

unsigned int hooked_if_nametoindex(const char *ifname) {
  if (g_enabled && is_hidden_interface(ifname)) { set_errno_value(ENODEV); return 0; }
  return orig_if_nametoindex ? orig_if_nametoindex(ifname) : 0;
}

char *hooked_if_indextoname(unsigned int ifindex, char *ifname) {
  if (g_enabled && is_hidden_index(ifindex)) { set_errno_value(ENODEV); return nullptr; }
  char *rc = orig_if_indextoname ? orig_if_indextoname(ifindex, ifname) : nullptr;
  if (rc && is_hidden_interface(rc)) { set_errno_value(ENODEV); return nullptr; }
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
         strstr(path, "/libnetd_client.so") != nullptr;
}

bool is_app_owned_library_path(const char *path) {
  if (!path || !str_ends_with(path, ".so")) return false;
  return strstr(path, "/data/app/") != nullptr ||
         strstr(path, "/data/user/") != nullptr ||
         strstr(path, "/mnt/expand/") != nullptr;
}

bool should_register_hooks_for_path(const char *path) {
  if (!path || !*path) return false;
  if (strstr(path, "/ZDT-D/zygisk/") != nullptr) return false;
  if (strstr(path, "/libc.so") != nullptr) return false;
  if (strstr(path, "/libdl.so") != nullptr) return false;
  if (strstr(path, "/linker") != nullptr) return false;
  if (is_app_owned_library_path(path)) return true;
  return contains_framework_hook_target(path);
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
            api->pltHookRegister(dev, ino, "getifaddrs", reinterpret_cast<void *>(hooked_getifaddrs), reinterpret_cast<void **>(&orig_getifaddrs)); registered++;
            api->pltHookRegister(dev, ino, "open", reinterpret_cast<void *>(hooked_open), reinterpret_cast<void **>(&orig_open)); registered++;
            api->pltHookRegister(dev, ino, "openat", reinterpret_cast<void *>(hooked_openat), reinterpret_cast<void **>(&orig_openat)); registered++;
            api->pltHookRegister(dev, ino, "if_nametoindex", reinterpret_cast<void *>(hooked_if_nametoindex), reinterpret_cast<void **>(&orig_if_nametoindex)); registered++;
            api->pltHookRegister(dev, ino, "if_indextoname", reinterpret_cast<void *>(hooked_if_indextoname), reinterpret_cast<void **>(&orig_if_indextoname)); registered++;
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
  void onLoad(zygisk::Api *api, JNIEnv *) override { api_ = api; }

  void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
    const int uid = args ? args->uid : -1;
    g_uid = uid;
    g_enabled = false;
    g_target = false;
    g_should_install_hooks = false;
    g_interface_count = 0;
    g_hidden_index_count = 0;
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
    g_last_reload_at = 0;
    g_current_ttl = RUNTIME_TTL_MIN;
    g_runtime_loaded_once = false;

    write_status_at(MODULE_FD_NONE, "pre_app_entered", uid, false, false, 0, 0);

    g_module_fd = api_ ? api_->getModuleDir() : MODULE_FD_NONE;
    if (g_module_fd >= 0) {
      if (api_) api_->exemptFd(g_module_fd);
      write_status_at(g_module_fd, "module_dir_ok", uid, false, false, 0, 0);
    } else {
      write_status_at(MODULE_FD_NONE, "module_dir_failed", uid, false, false, 0, 0);
    }

    static char targets[READ_LIMIT];
    int target_len = read_file_module_or_abs(
        g_module_fd,
        "working_folder/proxyInfo/out_program",
        ABS_TARGETS_PATH,
        targets,
        READ_LIMIT);
    g_target = target_len > 0 && uid_in_out_program(targets, uid);
    if (!g_target) {
      write_status_at(g_module_fd, target_len > 0 ? "target_missed" : "target_file_missing", uid, false, false, 0, 0);
      if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
      return;
    }

    write_status_at(g_module_fd, "target_matched", uid, true, false, 0, 0);

    g_should_install_hooks = true;
    reload_runtime_state(true, "runtime_initial_load");
  }

  void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
    if (!g_target || !g_should_install_hooks) return;
    reload_runtime_state(false, nullptr);
    write_status_at(g_module_fd, "post_specialize_entered", g_uid, true, g_enabled, g_interface_count, 0);
    int registered = register_hooks_from_maps(api_);
    bool committed = api_ && registered > 0 && api_->pltHookCommit();
    g_commit_ok = committed ? 1 : 0;
    if (committed) refresh_hidden_indices();
    write_status_at(g_module_fd, committed ? "hooks_ready" : "hook_failed", g_uid, true, g_enabled, g_interface_count, registered);
  }


private:
  zygisk::Api *api_ = nullptr;
};
} // namespace

REGISTER_ZYGISK_MODULE(ZdtdZygiskModule)
