# ZDT-D: планы по будущим функциям маршрутизации

> Личная памятка/roadmap для репозитория ZDT-D.  
> Это **план будущих функций**, а не описание уже готовой реализации.  
> На момент написания **все пункты ниже имеют статус: не реализовано / planned**.

---

## 0. Общая цель

Цель будущих изменений — не переписывать архитектуру ZDT-D, а точечно усилить уже существующий runtime.

Текущая модель уже хорошо работает через:

- `iptables` / `ip6tables`;
- `NAT_DPI`;
- `MANGLE_APP`;
- scoped chains `ZDTN_*` и `ZDTM_*`;
- UID-based DNAT на локальные `t2s`-порты;
- NFQUEUE для `nfqws` / `nfqws2`;
- Android `netd` для VPN/TUN UID binding;
- `dnscrypt`;
- `blockedquic`;
- `proxyInfo`;
- `vpn_tether`;
- `runtime_refresh`;
- `traffic_total`.

Новые функции должны дополнять текущую структуру, а не ломать её.

Основные направления:

1. **Routing explain / trace** — объяснение, куда идёт трафик приложения или UID.
2. **Capabilities API / page** — проверка возможностей устройства.
3. **Captive portal workflow** — удобная авторизация в Wi-Fi сетях с captive portal.
4. **TPROXY mode** — experimental расширение текущего `iptables_port`.

---

## 1. Таблица будущих функций

| Функция | Кратко | Приоритет | Статус |
|---|---|---:|---|
| Routing explain / trace | Объяснить маршрут приложения/UID по текущим правилам | Высокий | Planned, 0% |
| Capabilities API / page | Показать, что поддерживает устройство | Высокий | Planned, 0% |
| Captive portal workflow | Уведомление и удобная авторизация в Wi-Fi сетях с login page | Высокий | Planned, 0% |
| TPROXY mode | Experimental transparent proxy mode в `iptables_port` | Средний/высокий | Planned, 0% |

---

# Часть I. Routing explain / trace

## 1.1. Что это такое

Нужна функция, которая объясняет, **куда сейчас должен идти трафик выбранного приложения или UID**.

Пример простого вывода:

```text
Package: com.google.android.youtube
UID: 10299

Найдено:
- mangle: UID 10299 TCP 80,443,8443 → NFQUEUE 200
- DNS: 53/853/5353 → dnscrypt 127.0.0.1:863
- blockedquic: UDP/443 блокируется
- NAT redirect для UID 10299 не найден

Итог:
TCP web traffic идёт через NFQUEUE.
QUIC блокируется.
DNS идёт через dnscrypt.
```

Это диагностическая функция. Она не должна применять или изменять правила.

---

## 1.2. Зачем это нужно

Сейчас для диагностики приходится вручную смотреть:

```sh
iptables -t nat -L -v -n
iptables -t mangle -L -v -n
iptables-save -t nat
iptables-save -t mangle
cmd package list packages -U
ss -lntup
ip rule
ip route table all
```

`routing explain` должен быстро ответить:

- какой UID у приложения;
- есть ли для него NAT redirect;
- есть ли для него NFQUEUE rule;
- блокируется ли QUIC;
- куда идёт DNS;
- есть ли VPN/netd binding;
- слушает ли локальный backend port;
- какие chain/rule/counter соответствуют этому UID.

---

## 1.3. Возможные API endpoints

```text
GET /api/routing/explain?package=com.google.android.youtube
GET /api/routing/explain?uid=10299
GET /api/routing/trace?package=com.google.android.youtube&proto=tcp&port=443
GET /api/routing/trace?uid=10299&proto=udp&port=443
```

Разница:

- `explain` — общая картина по приложению/UID;
- `trace` — объяснение конкретного трафика: protocol + destination port.

---

## 1.4. Пример ответа `/api/routing/explain`

```json
{
  "package": "com.google.android.youtube",
  "uid": 10299,
  "summary": "App traffic is matched by NFQUEUE rules; QUIC is blocked; DNS is redirected to dnscrypt.",
  "nat": [],
  "mangle": [
    {
      "table": "mangle",
      "chain": "ZDTM_49e603756a959953",
      "action": "NFQUEUE",
      "queue": 200,
      "proto": "tcp",
      "ports": ["80", "443", "8443"],
      "packets": 0,
      "bytes": 0
    }
  ],
  "dns": {
    "enabled": true,
    "target": "127.0.0.1:863",
    "owner": "dnscrypt"
  },
  "blockedquic": {
    "enabled": true,
    "udp443": "blocked"
  },
  "vpn_netd": null,
  "warnings": []
}
```

---

## 1.5. Пример ответа `/api/routing/trace`

```json
{
  "package": "com.google.android.youtube",
  "uid": 10299,
  "proto": "tcp",
  "port": 443,
  "steps": [
    {
      "table": "mangle",
      "chain": "OUTPUT",
      "rule": "-j MANGLE_APP",
      "matched": true
    },
    {
      "table": "mangle",
      "chain": "MANGLE_APP",
      "rule": "-o lo -j RETURN",
      "matched": false
    },
    {
      "table": "mangle",
      "chain": "ZDTM_49e603756a959953",
      "rule": "tcp dports 80,443,8443 uid 10299 -j NFQUEUE --queue-num 200",
      "matched": true
    }
  ],
  "final_action": "NFQUEUE 200"
}
```

---

## 1.6. Источники данных

Для реализации можно использовать:

```text
cmd package list packages -U
iptables-save -t nat
iptables-save -t mangle
ip6tables-save -t mangle
ss -lntup
ip rule
ip route table all
/data/adb/modules/ZDT-D/working_folder/runtime_refresh/routing.json
/data/adb/modules/ZDT-D/working_folder/vpn_netd/vpn_netd_applied.json
```

Код, который может быть полезен:

```text
src/traffic_total.rs
src/runtime_refresh.rs
src/android/pkg_uid.rs
src/vpn_netd.rs
src/blockedquic.rs
```

---

## 1.7. Минимальная реализация

Первый вариант может быть простым:

1. принять `package` или `uid`;
2. если передан `package`, найти UID;
3. распарсить `iptables-save -t nat`;
4. распарсить `iptables-save -t mangle`;
5. найти правила с `--uid-owner <uid>`;
6. определить `DNAT`, `NFQUEUE`, `DROP`, `REJECT`, `RETURN`;
7. проверить DNS redirect;
8. проверить `blockedquic`;
9. проверить `vpn_netd_applied.json`;
10. вернуть JSON и короткий summary.

Функция не обязана идеально симулировать весь netfilter. Её задача — объяснить ZDT-D маршруты понятным образом.

---

# Часть II. Capabilities API / page

## 2.1. Что это такое

Нужен endpoint/экран, который показывает, что поддерживает конкретное Android-устройство.

Устройства отличаются:

- на одном есть `NFQUEUE`, `MARK`, `TPROXY`, `multiport`, `ip6tables`;
- на другом часть targets/matches отсутствует;
- где-то `netd` работает иначе;
- где-то root shell ограничен;
- где-то SELinux мешает.

Capabilities API должен дать единый отчёт.

---

## 2.2. Зачем это нужно

### Для диагностики

Пользователь присылает отчёт, и сразу видно:

```text
NFQUEUE target отсутствует
TPROXY target отсутствует
ip6tables mangle не работает
ndc network users add не поддерживается
```

### Для UI

UI может скрывать неподдерживаемые функции.

### Для TPROXY

TPROXY mode нельзя включать без проверки:

- `TPROXY` target;
- `MARK` target;
- `ip rule`;
- custom route table;
- mangle table;
- owner match.

---

## 2.3. Возможный API

```text
GET /api/system/capabilities
POST /api/system/capabilities/refresh
```

`GET` возвращает последний отчёт.  
`refresh` пересканирует устройство.

---

## 2.4. Пример ответа

```json
{
  "iptables": {
    "available": true,
    "variant": "unknown",
    "nat_output": true,
    "mangle_output": true,
    "owner_match": true,
    "multiport_v4": true,
    "multiport_v6": false
  },
  "ip6tables": {
    "available": true,
    "mangle_output": true,
    "nat_output": false
  },
  "targets": {
    "DNAT": true,
    "REDIRECT": true,
    "NFQUEUE": true,
    "MARK": true,
    "TPROXY": false
  },
  "routing": {
    "ip_rule": true,
    "ip_route": true,
    "fwmark_rules": true,
    "custom_tables": true
  },
  "android": {
    "netd": true,
    "ndc": true,
    "ndc_network_users": true,
    "package_uid_lookup": true,
    "selinux": "enforcing"
  },
  "binaries": {
    "dnscrypt": true,
    "t2s": true,
    "sing-box": true,
    "tun2socks": true,
    "openvpn": true,
    "amneziawg": true,
    "mihomo": true,
    "mieru": true
  },
  "warnings": [
    "ip6tables multiport is not available",
    "TPROXY target is not available"
  ]
}
```

---

## 2.5. Что проверять

### iptables

```text
iptables exists
iptables -t nat -L OUTPUT
iptables -t mangle -L OUTPUT
iptables-save works
owner match works
multiport match works
```

### ip6tables

```text
ip6tables exists
ip6tables -t mangle -L OUTPUT
ip6tables-save works
multiport v6 works
```

### targets

```text
DNAT
REDIRECT
NFQUEUE
MARK
TPROXY
DROP
REJECT
RETURN
```

### routing

```text
ip rule show
ip route show table all
test fwmark rule add/delete
test custom table route add/delete
```

### Android/system

```text
cmd package list packages -U
su -lp 2000 -c 'cmd package list packages -U'
ndc network list
ndc network users add/remove syntax
settings get/put available
getenforce
```

### binaries

Проверить наличие нужных файлов:

```text
/data/adb/modules/ZDT-D/bin/dnscrypt
/data/adb/modules/ZDT-D/bin/t2s
/data/adb/modules/ZDT-D/bin/sing-box
/data/adb/modules/ZDT-D/bin/tun2socks
/data/adb/modules/ZDT-D/bin/openvpn
/data/adb/modules/ZDT-D/bin/amneziawg
/data/adb/modules/ZDT-D/bin/mihomo
/data/adb/modules/ZDT-D/bin/mieru
```

---

## 2.6. Безопасность проверок

Capabilities scan не должен ломать сеть.

Если создаётся test chain:

```text
создать → проверить → удалить
```

Если добавляется test `ip rule`:

```text
добавить → проверить → удалить
```

Если команда не сработала — записать warning, но не падать.

---

## 2.7. Где реализовывать

Возможные файлы:

```text
src/capabilities.rs
src/api.rs
src/iptables/caps.rs
```

Часть логики уже есть в:

```text
src/iptables/caps.rs
```

Можно либо расширить её, либо сделать общий `capabilities.rs`, который вызывает `iptables::caps` и другие модули.

---

## 2.8. Кэширование

Можно сохранять результат в:

```text
/data/adb/modules/ZDT-D/working_folder/capabilities/status.json
```

Пример:

```json
{
  "generated_at": 1234567890,
  "capabilities": {}
}
```

Обновлять:

- при старте daemon;
- по кнопке refresh;
- перед включением experimental TPROXY.

---

# Часть III. Captive portal workflow

## 3.1. Что это такое

Нужен удобный сценарий для Wi-Fi сетей, где требуется авторизация через captive portal.

Сейчас приходится делать вручную:

```text
1. Подключиться к Wi-Fi.
2. Понять, что сеть требует авторизацию.
3. Остановить ZDT-D.
4. Авторизоваться.
5. Запустить ZDT-D обратно.
```

Хочется:

```text
1. ZDT-D видит сеть с авторизацией.
2. ZDT-D показывает уведомление.
3. Пользователь нажимает “Авторизоваться”.
4. ZDT-D временно останавливает или ослабляет маршрутизацию.
5. Открывается captive portal.
6. После авторизации пользователь запускает ZDT-D обратно или модуль делает это сам.
```

---

## 3.2. Принцип реализации

Не делать слишком магическую автоматическую систему на первом этапе.

Лучше начать с управляемого сценария:

```text
обнаружить captive portal → показать уведомление → пользователь выбирает действие
```

Так безопаснее и понятнее.

---

## 3.3. Возможные режимы

```json
{
  "captive_portal": {
    "mode": "notify_only"
  }
}
```

Режимы:

| Mode | Описание |
|---|---|
| `off` | Ничего не делать |
| `notify_only` | Только уведомлять, что сеть требует авторизацию |
| `pause_for_login` | Уведомление с действием: временно остановить/ослабить ZDT-D и открыть авторизацию |
| `auto_open_portal` | Попытаться открыть captive portal автоматически |

---

## 3.4. Первый практичный вариант

На первом этапе:

```text
notify_only / pause_for_login
```

Поведение:

1. ZDT-D или Android app обнаруживает captive portal.
2. Появляется уведомление:

```text
ZDT-D: требуется авторизация в Wi-Fi сети

Похоже, текущая сеть требует вход через страницу авторизации.
Можно временно остановить маршрутизацию и открыть страницу входа.

[Авторизоваться] [Игнорировать]
```

3. Если пользователь нажал `Авторизоваться`:
   - ZDT-D временно останавливает службу или снимает маршрутизацию;
   - Android app открывает captive portal;
   - после авторизации пользователь запускает ZDT-D обратно или нажимает кнопку resume.

---

## 3.5. Как определить captive portal

### Способ A. Android connectivity state

Через `dumpsys connectivity` / `cmd connectivity` искать признаки:

```text
CAPTIVE_PORTAL
PARTIAL_CONNECTIVITY
VALIDATED=false
```

Плюс: системная информация.  
Минус: формат зависит от версии Android.

### Способ B. HTTP probe

Проверить:

```text
http://connectivitycheck.gstatic.com/generate_204
```

Ожидаемый ответ:

```text
HTTP 204
```

Если пришло:

```text
HTTP 200
HTTP 302
HTML login page
```

значит, вероятно, сеть требует авторизацию.

### Способ C. Android app side detection

Если в Android app уже есть код для captive portal, daemon может быть только control plane.

Пример будущих endpoints:

```text
/api/captive-portal/detected
/api/captive-portal/pause-for-login
/api/captive-portal/resume
```

---

## 3.6. Что значит “временно остановить/ослабить”

Нужно выбрать стратегию.

### Вариант A. Полная остановка runtime

```text
stop_full()
open captive portal
start_full() после авторизации
```

Плюсы:

- просто;
- надёжно;
- меньше риска оставить плохие правила.

Минусы:

- дольше;
- останавливаются все процессы;
- пользователь может забыть включить обратно.

### Вариант B. Pause routing

```text
оставить daemon/API
снять NAT_DPI/MANGLE_APP/VPN bindings
после авторизации вернуть routing
```

Плюсы:

- быстрее;
- можно сделать отдельную кнопку `Pause routing` / `Resume routing`.

Минусы:

- нужна отдельная логика state;
- важно аккуратно восстановить правила;
- сложнее cleanup.

### Вариант C. Temporary captive bypass

```text
оставить ZDT-D включенным
добавить временный bypass для captive portal трафика
```

Плюсы:

- красиво;
- меньше перезапусков.

Минусы:

- сложнее;
- не всегда сработает;
- нужно точно понимать, какой трафик пропускать.

---

## 3.7. Рекомендуемый порядок

Сначала:

```text
notification + ручной/полуавтоматический stop/start
```

Потом:

```text
pause routing / resume routing
```

И только если понадобится:

```text
temporary captive bypass
```

---

## 3.8. Возможные API endpoints

```text
GET /api/captive-portal/status
POST /api/captive-portal/check
POST /api/captive-portal/pause-for-login
POST /api/captive-portal/resume-after-login
POST /api/captive-portal/open
```

Пример статуса:

```json
{
  "enabled": true,
  "state": "captive",
  "network": {
    "type": "wifi",
    "ssid": "Airport_Free_WiFi"
  },
  "last_probe": {
    "url": "http://connectivitycheck.gstatic.com/generate_204",
    "status": 302,
    "result": "captive_portal"
  },
  "recommended_action": "pause_for_login"
}
```

---

## 3.9. Уведомления

Минимальные кнопки:

```text
[Авторизоваться]
[Игнорировать]
```

Позже можно добавить:

```text
[Остановить ZDT-D]
[Открыть портал]
[Запустить обратно]
```

Открытие portal лучше делать на стороне Android app, потому что daemon — root/backend часть, а UI/Intent логичнее держать в app.

---

## 3.10. Важный момент про Android captive settings

Сейчас runtime меняет настройки captive portal:

```sh
settings put global captive_portal_detection_enabled 0
settings put global captive_portal_server localhost
settings put global captive_portal_mode 0
```

Для smart workflow нужно отдельно решить:

- когда отключать системную проверку;
- когда не отключать;
- когда временно вернуть default behavior;
- не мешают ли эти настройки системному уведомлению авторизации.

---

# Часть IV. TPROXY mode

## 4.1. Что это такое

TPROXY — experimental режим прозрачного проксирования.

Текущий стабильный путь:

```text
app UID → nat OUTPUT → NAT_DPI → ZDTN_* → DNAT to 127.0.0.1:t2s_port
```

Будущий experimental путь:

```text
app UID → mangle/TPROXY/MARK → local transparent listener
```

TPROXY должен быть отдельным режимом, а не заменой текущего NAT/DNAT.

---

## 4.2. Где реализовывать

Основной файл:

```text
src/iptables/iptables_port.rs
```

Проверки:

```text
src/iptables/caps.rs
src/capabilities.rs
```

Опционально можно добавить:

```text
src/iptables/tproxy.rs
```

---

## 4.3. Возможная настройка

Вариант через `DpiTunnelOptions`:

```rust
pub struct DpiTunnelOptions {
    pub port_preference: u8,
    pub dpi_ports: String,
    pub route_mode: String, // "nat" | "tproxy"
}
```

Или через enum:

```rust
pub enum RouteMode {
    Nat,
    Tproxy,
}
```

Для совместимости default должен быть:

```text
route_mode = nat
```

---

## 4.4. Пример будущего profile setting

```json
{
  "route_mode": "tproxy",
  "tproxy_port": 12360,
  "tproxy_mark": "0x7d1001",
  "tproxy_table": 7101,
  "tproxy_fallback": "fail"
}
```

---

## 4.5. Проверки поддержки

Перед включением TPROXY нужно проверить:

```text
iptables mangle available
owner match available
TPROXY target available
MARK target available
ip rule available
ip route custom table works
```

Если проверка не прошла:

- не применять TPROXY;
- показать warning;
- не ломать текущий NAT mode.

---

## 4.6. Минимальный первый вариант

Сначала только:

```text
IPv4
TCP
selected UID
selected ports или all ports
```

UDP и IPv6 лучше не включать в первый вариант.

---

## 4.7. Возможная структура chain

Не ломать `NAT_DPI`.

Для TPROXY использовать mangle path:

```text
MANGLE_APP
  ├─ base RETURN guards
  ├─ ZDTT_<hash>   # условная scoped TPROXY chain
  └─ RETURN
```

Можно выбрать другой prefix, но важно, чтобы было понятно, что chain относится к TPROXY.

---

## 4.8. Fallback policy

Нужно заранее решить поведение:

```json
{
  "tproxy_fallback": "fail"
}
```

Варианты:

| Mode | Поведение |
|---|---|
| `fail` | Не запускать routing, если TPROXY недоступен |
| `nat` | Перейти на текущий NAT/DNAT режим |
| `skip` | Запустить backend, но не применять TPROXY routing |

Для безопасности default лучше:

```text
fail
```

---

## 4.9. Статус experimental

TPROXY должен быть:

```text
disabled by default
помечен как experimental
доступен только если capabilities показывают поддержку
с понятным warning в UI
```

---

# Часть V. Порядок внедрения

## Milestone 1. Capabilities API

Первым делом добавить проверку возможностей устройства.

Причины:

- нужно для TPROXY;
- полезно для диагностики;
- почти не влияет на runtime;
- можно безопасно внедрять.

Минимум:

```text
GET /api/system/capabilities
iptables/ip6tables checks
targets/matches checks
routing checks
binaries check
android/netd check
```

---

## Milestone 2. Routing explain / trace

Вторым добавить объяснение маршрутов.

Причины:

- не меняет правила;
- помогает отлаживать текущую маршрутизацию;
- пригодится при тестировании TPROXY;
- полезно для пользователей и bug reports.

Минимум:

```text
GET /api/routing/explain?package=...
GET /api/routing/explain?uid=...
```

---

## Milestone 3. Captive portal workflow

Третьим добавить сценарий авторизации в Wi-Fi сетях.

Причины:

- реальная проблема использования;
- сейчас приходится вручную останавливать службу;
- можно начать с уведомления и кнопки.

Минимум:

```text
обнаружить captive portal
показать notification
кнопка “Авторизоваться”
временная остановка/ослабление
открытие portal через Android app
```

---

## Milestone 4. TPROXY experimental

После capabilities и explain можно добавлять TPROXY.

Причины делать позже:

- сложнее;
- зависит от поддержки ядра/system;
- нужен аккуратный cleanup;
- нужен понятный warning.

Минимум:

```text
IPv4 TCP only
selected UID
selected ports
route_mode=tproxy
fallback if unsupported
```

---

# Часть VI. Статус реализации

| Функция | Статус | Готовность |
|---|---|---:|
| Capabilities API / page | Не реализовано | 0% |
| Routing explain / trace | Не реализовано | 0% |
| Captive portal workflow | Не реализовано | 0% |
| TPROXY mode | Не реализовано | 0% |

---

# Часть VII. Напоминания для разработки

- Не ломать текущий `NAT_DPI` / `MANGLE_APP` runtime.
- Новые функции должны быть optional.
- Старые профили должны работать без миграций или с минимальной backward-compatible миграцией.
- Capabilities checks должны быть безопасными и чистить за собой test rules.
- TPROXY добавлять только после проверки поддержки.
- TPROXY не должен быть включён по умолчанию.
- Captive portal workflow лучше начинать с уведомления и пользовательского действия.
- Routing explain сначала можно сделать простым: package/UID → parse current rules → summary.

---

## Финальная формулировка

```text
ZDT-D остаётся на текущей стабильной архитектуре.
Будущие функции должны улучшить диагностику, совместимость и удобство использования:

1. capabilities — понять возможности устройства;
2. routing explain — понять путь приложения;
3. captive portal workflow — удобно авторизоваться в Wi-Fi сетях;
4. TPROXY — experimental advanced routing mode.
```
