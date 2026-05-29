const VERSION: &str = env!("CARGO_PKG_VERSION");
const USER_AGENT: &str = "Mozilla/5.0 (Linux; Android 10; ZDT-D) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";
const DNS_TIMEOUT_MS: u64 = 5000;
const CONNECT_TIMEOUT_MS: u64 = 8000;
const READ_TIMEOUT_MS: u64 = 12000;
const TOTAL_TIMEOUT_MS: u64 = 60000;
const STALL_TIMEOUT_MS: u64 = 10000;
const TCP_PAYLOAD_KB: usize = 20;
const TCP_BLOCK_MIN_KB: usize = 12;
const TCP_BLOCK_MAX_KB: usize = 69;
const TCP_PAYLOAD_STEPS_KB: &[usize] = &[4, 8, 12, 16, 20, 32, 48, 64];
const TELEGRAM_MEDIA_URL: &str = "https://telegram.org/img/Telegram200million.png";
const TELEGRAM_MEDIA_LIMIT: usize = 31 * 1024 * 1024;
const TELEGRAM_UPLOAD_IP: &str = "149.154.167.220";
const TELEGRAM_UPLOAD_SIZE: usize = 10 * 1024 * 1024;

const DNS_CHECK_DOMAINS: &[&str] = &[
    "rutor.info",
    "flibusta.is",
    "clubtone.do.am",
    "rezka.ag",
    "shikimori.one",
    "www.fastmail.com",
];

const DNS_AVAILABILITY_DOMAINS: &[&str] = &["example.com", "vk.com", "ozon.ru", "habr.com", "mail.ru"];

const DNS_UDP_SERVERS: &[(&str, &str)] = &[
    ("8.8.8.8", "Google"),
    ("1.1.1.1", "Cloudflare"),
    ("9.9.9.9", "Quad9"),
    ("94.140.14.14", "AdGuard"),
    ("77.88.8.8", "Yandex"),
    ("223.5.5.5", "Alibaba"),
    ("208.67.222.222", "OpenDNS"),
    ("76.76.2.0", "ControlD"),
    ("185.228.168.9", "CleanBrowsing"),
    ("76.223.122.150", "NextDNS"),
    ("194.242.2.2", "Mullvad"),
];

const DNS_DOH_JSON_SERVERS: &[(&str, &str)] = &[
    ("https://8.8.8.8/resolve", "Google"),
    ("https://dns.google/resolve", "Google"),
    ("https://1.1.1.1/dns-query", "Cloudflare"),
    ("https://cloudflare-dns.com/dns-query", "Cloudflare"),
    ("https://one.one.one.one/dns-query", "Cloudflare one.one.one.one"),
    ("https://dns.adguard-dns.com/resolve", "AdGuard"),
    ("https://dns.alidns.com/resolve", "Alibaba"),
];

const DNS_DOH_WIRE_SERVERS: &[(&str, &str)] = &[
    ("https://dns.google/dns-query", "Google"),
    ("https://cloudflare-dns.com/dns-query", "Cloudflare"),
    ("https://1.1.1.1/dns-query", "Cloudflare IP"),
    ("https://dns.adguard-dns.com/dns-query", "AdGuard"),
    ("https://dns.quad9.net/dns-query", "Quad9"),
    ("https://doh.opendns.com/dns-query", "OpenDNS"),
    ("https://common.dot.dns.yandex.net/dns-query", "Yandex"),
    ("https://dns.nextdns.io/dns-query", "NextDNS"),
    ("https://doh.cleanbrowsing.org/doh/security-filter", "CleanBrowsing"),
    ("https://dns.sb/dns-query", "DNS.SB"),
    ("https://doh.dns.sb/dns-query", "DNS.SB alt"),
    ("https://doh.libredns.gr/dns-query", "LibreDNS"),
];

const TELEGRAM_DC_IPS: &[(&str, &str)] = &[
    ("149.154.175.50", "DC2"),
    ("149.154.167.51", "DC4"),
    ("149.154.175.100", "DC2 CDN"),
    ("149.154.167.91", "DC4 CDN"),
    ("91.108.56.130", "DC5"),
];

static DOMAINS_TXT: &str = include_str!("../resources/domains.txt");
static WHITELIST_SNI_TXT: &str = include_str!("../resources/whitelist_sni.txt");
static TCP16_JSON: &str = include_str!("../resources/tcp16.json");
