
---

## 1) Из чего вообще состоит “стратегия”

Стратегия в твоём случае — это три слоя:

1. **Фильтр трафика** — какой трафик вообще рассматривать (например, TCP порты 80/443).
2. **Списки целей и исключений** — кого обрабатывать и кого не трогать.
3. **Действие (правило обработки)** — что именно делать с подходящими пакетами (в твоём случае это подключаемые Lua-функции через `--lua-desync=...`).

---

## 2) Что означает `N` в примерах

`N` — это просто **число**, которое ты подставляешь сам.

* `repeats=6` → 6 повторов
* `tcp_seq=2` → сдвиг на 2
* `wsize=1024` → значение 1024
* `increment=2` → добавить 2 байта и т.д.

То есть `N` — “место, где нужно написать конкретное число”.

---

## 3) Про файлы списков: что есть и зачем

По твоей команде видно такие типы файлов:

### A) `--ipset=...` (список IP/сетей, *которые можно обрабатывать*)

Это **цели по IP**. Можно указать несколько файлов `--ipset=...`, они суммируются.

* `ipset.txt`
* `custom.txt`

Проще говоря: **“вот эти IP/подсети попадают под обработку”**.

### B) `--hostlist-exclude=...` (список доменов, *которые не трогать*)

Это **исключения по доменам**.

* `exclude.txt`

Проще говоря: **“эти домены не трогать, даже если что-то совпало”**.

### C) `--ipset-exclude=...` (список IP/сетей, *которые не трогать*)

Это **исключения по IP**.

* `exclude.txt` (но это отдельный файл по смыслу, даже если путь одинаковый)

Проще говоря: **“эти IP/подсети не трогать”**.

✅ Да, твоя мысль про “список целей + список исключений” — верная.
⚠️ Только уточнение: в твоём примере **цели заданы по IP**, а домены пока используются **только как исключения** (то есть “кого не трогать”).

---

## 4) Как логично собрать стратегию с нуля

### Шаг 1 — ограничь “зону воздействия” фильтром

Начни с самого узкого:

* только нужные порты (`--filter-tcp=...`)
* если есть другие фильтры в твоей программе — тоже сужай (чем меньше затрагиваешь, тем меньше поломок).

### Шаг 2 — собери “цели” (ipset)

Смысл: сначала добавляешь **минимальный набор** IP/подсетей, где обработка вообще нужна.

Практика для обычного пользователя:

* держи “основной список” (ipset.txt) и “ручной/экспериментальный” (custom.txt)
* в `custom.txt` добавляй то, что тестируешь, чтобы потом легко убрать.

### Шаг 3 — обязательно сделай исключения

Исключения — это твой “предохранитель”.

* `hostlist-exclude` — для доменов, где ломается логин/банк/оплата/работа.
* `ipset-exclude` — если сервис имеет фиксированные IP или ты поймал проблемный диапазон.

Правило хорошего тона: **если что-то важное ломается — сначала добавь в exclude**, а не “усложняй обработку”.

### Шаг 4 — подключи “действие” (lua-desync) очень аккуратно

`--lua-desync=<func>:arg1=...:arg2=...`

Общее понимание:

* `<func>` — имя функции (например, `fake`, `send`, `pktmod` и т.д.)
* дальше идут **аргументы** как `ключ=значение`, разделённые двоеточиями `:`
* не все функции требуют аргументы, но некоторые требуют обязательно (например, `fake` требует `blob=...`)

Важно: любые такие правила могут:

* повысить нагрузку,
* сломать часть соединений,
* создать “неочевидные” баги.

Поэтому подключай **по одному правилу** и тестируй, а не всё сразу.

---

## 5) Как вести файлы списков, чтобы не запутаться

### Для ipset (IP/сети)

Обычно туда кладут:

* одиночные IP
* подсети (CIDR)

Советы:

* подписывай комментариями (если формат позволяет) или веди рядом `.md`/заметки
* не смешивай “основное” и “экспериментальное” в одном файле

### Для hostlist-exclude (домены)

Обычно туда:

* домены (и иногда маски/поддомены — зависит от формата вашей программы)

Советы:

* добавляй сначала “виновный домен”, потом при необходимости расширяй
* храни отдельный блок “важные сервисы” (банк, почта, работа)

---

## 6) Как тестировать и не сломать интернет

1. Начинай с **узкого фильтра** и **маленького ipset**.
2. Меняй **только одну вещь за раз**.
3. Если что-то ломается — первым делом:

   * добавь домен в `hostlist-exclude`
   * или IP/подсеть в `ipset-exclude`
4. Держи “кнопку отката”:

   * резервные копии файлов списков
   * возможность быстро отключить правило `--lua-desync=...`

---

## 7) Мини-шпаргалка по твоей строке (на человеческом)

* `--filter-tcp=80,443` → “смотрим только веб-трафик”
* `--ipset=...ipset.txt` + `--ipset=...custom.txt` → “вот IP/сети, где вообще применяем обработку”
* `--hostlist-exclude=...exclude.txt` → “вот домены, которые не трогаем”
* `--ipset-exclude=...exclude.txt` → “вот IP/сети, которые не трогаем”
* `--lua-desync=fake:...` → “к подходящему трафику применяем действие fake с параметрами”

---









---

## drop

```text
--lua-desync=drop:dir=<in|out|any>:payload=<type1,type2>
--lua-desync=drop:dir=any:payload=all
```

## send

```text
--lua-desync=send:dir=<in|out|any>
--lua-desync=send:dir=out:ip_id=<seq|rnd|zero|none>:repeats=<N>
```

## pktmod

```text
--lua-desync=pktmod:dir=<in|out|any>
--lua-desync=pktmod:dir=out:tcp_seq=<N>:tcp_ack=<N>
```

## http_domcase

```text
--lua-desync=http_domcase:dir=<in|out>
--lua-desync=http_domcase:dir=out
```

## http_hostcase

```text
--lua-desync=http_hostcase:dir=<in|out>
--lua-desync=http_hostcase:dir=out:spell=<4chars>
```

## http_methodeol

```text
--lua-desync=http_methodeol:dir=<in|out>
--lua-desync=http_methodeol:dir=out
```

## http_unixeol

```text
--lua-desync=http_unixeol:dir=<in|out>
--lua-desync=http_unixeol:dir=out
```

## synack_split

```text
--lua-desync=synack_split:mode=<syn|synack|acksyn>
--lua-desync=synack_split:mode=synack:repeats=<N>
```

## synack

```text
--lua-desync=synack
--lua-desync=synack:repeats=<N>
```

## wsize

```text
--lua-desync=wsize:wsize=<N>
--lua-desync=wsize:wsize=<N>:scale=<N>
```

## wssize

```text
--lua-desync=wssize:dir=<in|out>:wsize=<N>:scale=<N>
--lua-desync=wssize:dir=out:wsize=<N>:forced_cutoff=<type1,type2>
```

## tls_client_hello_clone  (тут `blob=` обязателен)

```text
--lua-desync=tls_client_hello_clone:blob=<name>
--lua-desync=tls_client_hello_clone:blob=<name>:fallback=<other_blob>:sni_del_ext
```

## syndata

```text
--lua-desync=syndata:blob=<blobname>
--lua-desync=syndata:blob=<blobname>:tls_mod=<list>:repeats=<N>
```

## rst

```text
--lua-desync=rst:dir=<in|out|any>:payload=<type1,type2>
--lua-desync=rst:dir=out:payload=all:rstack
```

## fake  (тут `blob=` обязателен)

```text
--lua-desync=fake:blob=<blobname>:repeats=<N>
--lua-desync=fake:blob=<blobname>:optional:tcp_seq=<N>:tls_mod=<list>
```

## multisplit

```text
--lua-desync=multisplit:pos=<marker_list>
--lua-desync=multisplit:pos=<marker_list>:seqovl=<N>:seqovl_pattern=<blob>
```

## multidisorder

```text
--lua-desync=multidisorder:pos=<marker_list>
--lua-desync=multidisorder:pos=<marker_list>:seqovl=<marker_or_pos>:seqovl_pattern=<blob>
```

## multidisorder_legacy

```text
--lua-desync=multidisorder_legacy:pos=<marker_list>
--lua-desync=multidisorder_legacy:pos=<marker_list>:seqovl=<marker_or_pos>:seqovl_pattern=<blob>
```

## hostfakesplit  (как минимум нужен `host=...`)

```text
--lua-desync=hostfakesplit:host=<template>
--lua-desync=hostfakesplit:host=<template>:midhost=<marker>:disorder_after=<marker>:nodrop
```

## fakedsplit

```text
--lua-desync=fakedsplit:pos=<marker>
--lua-desync=fakedsplit:pos=<marker>:pattern=<blob>:seqovl=<N>:nodrop
```

## fakeddisorder

```text
--lua-desync=fakeddisorder:pos=<marker>
--lua-desync=fakeddisorder:pos=<marker>:pattern=<blob>:seqovl=<marker_or_pos>:nodrop
```

## tcpseg  (`pos=` обязателен)

```text
--lua-desync=tcpseg:pos=<range_marker1,range_marker2>
--lua-desync=tcpseg:pos=<range_marker1,range_marker2>:seqovl=<N>:seqovl_pattern=<blob>
```

## oob

```text
--lua-desync=oob
--lua-desync=oob:char=<1byte>:urp=<b|e|marker_or_pos>
```

## udplen

```text
--lua-desync=udplen:increment=<N>
--lua-desync=udplen:min=<N>:max=<N>:increment=<N>:pattern=<blob>:pattern_offset=<N>
```

## dht_dn

```text
--lua-desync=dht_dn:dn=<N>
--lua-desync=dht_dn:dn=3
```

---










---

## Общие параметры, которые встречаются много где

* `dir=in|out|any` — направление пакетов:

  * `out` — исходящие (от тебя наружу)
  * `in` — входящие (к тебе)
  * `any` — любые
* `payload=...` — какие типы полезной нагрузки (L7) разрешены для обработки. В коде часто есть проверка `payload_check(...)`.

  * `all` обычно означает “любая/вся”
  * конкретные значения зависят от того, что nfqws2 распознал (например, `http_req`, `tls_client_hello`, `dht` и т.п.)
* `repeats=N` — сколько раз отправлять сформированный пакет (повторы rawsend).
* `blob=<name>` — имя бинарного “куска данных” (payload), который берётся из blob-хранилища (загружается через `--blob`, либо готовится другим lua-действием и сохраняется в `desync.<name>`).
* `<marker>` / `<marker_list>` — “позиционные маркеры” для поиска места в данных (например, числом или по именованным маркерам вроде `host`, `endhost`, `midsld` и т.д. — зависит от того, что понимают `resolve_pos/resolve_multi_pos`).
* `nodrop` — “не дропать оригинальный пакет” после отправки своих сегментов/фейков (оставить `VERDICT_PASS` вместо `DROP`).
* `pattern=<blob>` — паттерн-байты (из blob), которыми заполняют “фейковые” части.
* `seqovl` / `seqovl_pattern` — “оверлей” (добавка байт + сдвиг seq), подробнее ниже в местах где используется.

---

## drop

```text
--lua-desync=drop:dir=<in|out|any>:payload=<type1,type2>
--lua-desync=drop:dir=any:payload=all
```

**Что делает:** если пакет подходит по `dir` и `payload`, возвращает `VERDICT_DROP` — пакет будет **сброшен**.

**Параметры:**

* `dir` — какие направления дропать.
* `payload` — какие типы полезной нагрузки дропать.

---

## send

```text
--lua-desync=send:dir=<in|out|any>
--lua-desync=send:dir=out:ip_id=<seq|rnd|zero|none>:repeats=<N>
```

**Что делает:** клонирует текущий “dissect” пакет, применяет опции (fooling/ip_id) и **отправляет его через rawsend**, при этом не обязательно изменяет оригинал.

**Параметры:**

* `dir` — по какому направлению разрешить срабатывание.
* `ip_id=seq|rnd|zero|none` — как заполнять IPv4 `IPID`:

  * `seq` — последовательный
  * `rnd` — случайный
  * `zero` — ноль
  * `none` — не менять
* `repeats=N` — сколько раз отправить пакет (повторы).
* (ещё по комментам у функции есть “стандартные” группы: `fooling`, `rawsend`, `reconstruct`, `ipfrag` — но ты их тут не перечислял)

---

## pktmod

```text
--lua-desync=pktmod:dir=<in|out|any>
--lua-desync=pktmod:dir=out:tcp_seq=<N>:tcp_ack=<N>
```

**Что делает:** **модифицирует текущий пакет** (не отправляет отдельный клон), и возвращает `VERDICT_MODIFY`.

**Параметры:**

* `dir` — направление, для которого применяем.
* `tcp_seq=N` — прибавить `N` к TCP `seq`.
* `tcp_ack=N` — прибавить `N` к TCP `ack`.
* (также может принимать “fooling” и `ip_id`, см. общий список в комментариях к библиотеке)

---

## http_domcase

```text
--lua-desync=http_domcase:dir=<in|out>
--lua-desync=http_domcase:dir=out
```

**Что делает:** только для HTTP-запросов (`http_req`): меняет регистр букв в значении `Host` (делает “чередование” upper/lower), и возвращает `VERDICT_MODIFY`.

**Параметры:**

* `dir` — направление (обычно интересны исходящие HTTP-запросы).

---

## http_hostcase

```text
--lua-desync=http_hostcase:dir=<in|out>
--lua-desync=http_hostcase:dir=out:spell=<4chars>
```

**Что делает:** только для HTTP-запросов: меняет написание имени заголовка `Host:` на заданное (например `host:`, `HoSt:`), и возвращает `VERDICT_MODIFY`.

**Параметры:**

* `dir` — направление.
* `spell=<4chars>` — **ровно 4 символа**, новое написание “host” (без двоеточия). Если длина не 4 — будет `error`.

---

## http_methodeol

```text
--lua-desync=http_methodeol:dir=<in|out>
--lua-desync=http_methodeol:dir=out
```

**Что делает:** для HTTP-запроса делает “перестановку”/вставку `\r\n` относительно `User-Agent` (судя по коду — вставляет CRLF в начало и режет строку около конца UA). Возвращает `VERDICT_MODIFY`, если получилось.

**Параметры:**

* `dir` — направление.
  (Параметров кроме `dir` нет.)

---

## http_unixeol

```text
--lua-desync=http_unixeol:dir=<in|out>
--lua-desync=http_unixeol:dir=out
```

**Что делает:** пытается пересобрать HTTP-запрос с Unix-переносами (`\n` вместо `\r\n`) и при необходимости добивает пробелами, чтобы **размер не изменился**. Возвращает `VERDICT_MODIFY`, если реконструкция совпала по длине.

**Параметры:**

* `dir` — направление.
  (Параметров кроме `dir` нет.)

---

## synack_split

```text
--lua-desync=synack_split:mode=<syn|synack|acksyn>
--lua-desync=synack_split:mode=synack:repeats=<N>
```

**Что делает:** срабатывает на TCP пакеты `SYN+ACK` и отправляет их как **два отдельных пакета** (SYN и ACK) либо в заданном порядке. После успешной отправки обычно дропает оригинал.

**Параметры:**

* `mode`:

  * `syn` — отправить только SYN (убрать ACK)
  * `synack` — сначала SYN, потом ACK
  * `acksyn` — сначала ACK, потом SYN
* `repeats=N` — если поддержано базовыми rawsend-опциями, повторы отправки (внутри `desync_opts`).

---

## synack

```text
--lua-desync=synack
--lua-desync=synack:repeats=<N>
```

**Что делает:** на TCP `SYN` (без `ACK`) формирует и отправляет пакет, где принудительно добавлен `ACK` (получается `SYN+ACK`). Обычно это отдельная отправка через rawsend.

**Параметры:**

* `repeats=N` — повторы rawsend (если включено опциями).

---

## wsize

```text
--lua-desync=wsize:wsize=<N>
--lua-desync=wsize:wsize=<N>:scale=<N>
```

**Что делает:** на `SYN+ACK` переписывает TCP window size и/или TCP window scale option. Возвращает `VERDICT_MODIFY`, если удалось переписать.

**Параметры:**

* `wsize=N` — значение TCP window.
* `scale=N` — значение window scale в TCP option.

---

## wssize

```text
--lua-desync=wssize:dir=<in|out>:wsize=<N>:scale=<N>
--lua-desync=wssize:dir=out:wsize=<N>:forced_cutoff=<type1,type2>
```

**Что делает:** как `wsize`, но работает “по направлению” и может **принудительно завершать обработку** (cutoff) после того как увидит полезную нагрузку.

**Параметры:**

* `dir` — направление.
* `wsize=N`, `scale=N` — то же, что в `wsize`.
* `forced_cutoff=<list>` — список payload-типов, при которых делать “forced cutoff” (если не задано — обычно любой непустой payload).

---

## tls_client_hello_clone (тут `blob=` обязателен)

```text
--lua-desync=tls_client_hello_clone:blob=<name>
--lua-desync=tls_client_hello_clone:blob=<name>:fallback=<other_blob>:sni_del_ext
```

**Что делает:** если видит `tls_client_hello`, модифицирует/копирует ClientHello и сохраняет результат в `desync.<blobname>` (то есть blob создаётся “на лету” и доступен следующим функциям).

**Параметры:**

* `blob=<name>` — **обязательно**: куда сохранить (имя поля внутри `desync`).
* `fallback=<other_blob>` — если не удалось клонировать/модифицировать, скопировать данные из другого blob.
* `sni_snt`, `sni_snt_new` — управляют “server name type” для существующих/новых имён (если используешь).
* `sni_del_ext` — удалить расширение SNI целиком.
* `sni_del` — удалить все имена в SNI.
* `sni_first` / `sni_last` — добавить имя в начало/конец списка.

---

## syndata

```text
--lua-desync=syndata:blob=<blobname>
--lua-desync=syndata:blob=<blobname>:tls_mod=<list>:repeats=<N>
```

**Что делает:** на TCP `SYN` отправляет пакет с “фейковым payload” (из blob), применяя выбранные модификации/опции. После успешной отправки дропает оригинал.

**Параметры:**

* `blob=<blobname>` — какой payload использовать (если не задан — по коду дефолт 16 нулевых байт).
* `tls_mod=<list>` — список модификаций TLS-данных (например `rnd`, `rndsni`, `sni=...`, `sni=%var`).
* `repeats=N` — повторы отправки.

---

## rst

```text
--lua-desync=rst:dir=<in|out|any>:payload=<type1,type2>
--lua-desync=rst:dir=out:payload=all:rstack
```

**Что делает:** при совпадении условий отправляет TCP `RST` (с пустым payload). Может отправлять либо `RST`, либо `RST+ACK`.

**Параметры:**

* `dir` — направление.
* `payload` — какие payload-типы должны быть разрешены, чтобы сработало.
* `rstack` — если указан, отправлять `RST+ACK` вместо `RST`.

---

## fake (тут `blob=` обязателен)

```text
--lua-desync=fake:blob=<blobname>:repeats=<N>
--lua-desync=fake:blob=<blobname>:optional:tcp_seq=<N>:tls_mod=<list>
```

**Что делает:** вместо реального payload отправляет **фейковый payload из blob** (сегментировано, если надо), обычно только на “первом куске” (проверка `replay_first`). Оригинальные куски при этом могут дропаться в зависимости от логики replay.

**Параметры:**

* `blob=<blobname>` — **обязательно**: откуда брать фейковый payload.
* `optional` — если blob отсутствует, **пропустить** действие (не падать с ошибкой).
* `repeats=N` — повторы отправки.
* `tcp_seq=N` — сдвиг `seq` у отправляемого (fooling-опция).
* `tls_mod=<list>` — модификации TLS (применяются, когда есть `reasm_data`).

---

## multisplit

```text
--lua-desync=multisplit:pos=<marker_list>
--lua-desync=multisplit:pos=<marker_list>:seqovl=<N>:seqovl_pattern=<blob>
```

**Что делает:** берёт данные (blob или `reasm_data` или текущий payload) и **режет на несколько частей** по списку позиций `pos`, отправляя части по очереди отдельными сегментами.

**Параметры:**

* `pos=<marker_list>` — список позиций/маркеров через запятую (например `2,host,midsld+1,-10`).
* `seqovl=N` — только для **первого сегмента**: уменьшить seq на `N` и добавить в начало `N` байт заполнителя.
* `seqovl_pattern=<blob>` — чем заполнять эти `N` байт (по умолчанию нули).
* `blob=<blob>` — если указан, использовать эти данные вместо текущих.
* `optional` — если blob не найден — пропустить.
* `nodrop` — не дропать оригинал после отправки частей.

---

## multidisorder

```text
--lua-desync=multidisorder:pos=<marker_list>
--lua-desync=multidisorder:pos=<marker_list>:seqovl=<marker_or_pos>:seqovl_pattern=<blob>
```

**Что делает:** тоже делит на части, но отправляет **в обратном/перемешанном порядке** (disorder). Логика `seqovl` тут относится к “второму сегменту в оригинальном порядке” (см. комментарии и `multidisorder_send`).

**Параметры:**

* `pos=<marker_list>` — точки разреза.
* `seqovl=<marker_or_pos>` — позиция (или маркер), где считать `seqovl` (внутри `resolve_pos`).
* `seqovl_pattern=<blob>` — паттерн для заполнения.
* `blob`, `optional`, `nodrop` — как в `multisplit`.

---

## multidisorder_legacy

```text
--lua-desync=multidisorder_legacy:pos=<marker_list>
--lua-desync=multidisorder_legacy:pos=<marker_list>:seqovl=<marker_or_pos>:seqovl_pattern=<blob>
```

**Что делает:** “старый” вариант `multidisorder`, который старается повторить порядок сегментов как в nfqws1, учитывая диапазон текущего reasm-piece.

**Параметры:** те же, что у `multidisorder`.

---

## hostfakesplit (как минимум нужен `host=...`)

```text
--lua-desync=hostfakesplit:host=<template>
--lua-desync=hostfakesplit:host=<template>:midhost=<marker>:disorder_after=<marker>:nodrop
```

**Что делает:** находит диапазон `host..endhost` внутри данных и:

* отправляет “до host”
* отправляет “фейковый host”
* отправляет “реальный host” (в 1 или 2 части, если задан `midhost`)
* снова может отправить “фейковый host”
* затем отправляет “после host” (в обычном или disordered виде)

**Параметры:**

* `host=<template>` — шаблон для генерации фейкового host (например “random.template”).
* `midhost=<marker>` — где дополнительно разрезать **внутри** host-части (должен быть строго внутри диапазона host).
* `nofake1`, `nofake2` — не отправлять первый/второй “индивидуальный фейк”.
* `disorder_after=<marker>` — если задан, “после host” отправлять в другом порядке, начиная с позиции маркера (пустая строка означает маркер `-1`).
* `blob=<blob>`, `optional` — использовать данные из blob / пропустить если нет.
* `nodrop` — не дропать оригинал.

---

## fakedsplit

```text
--lua-desync=fakedsplit:pos=<marker>
--lua-desync=fakedsplit:pos=<marker>:pattern=<blob>:seqovl=<N>:nodrop
```

**Что делает:** делит данные на 2 части по `pos`, и вокруг каждой части отправляет “фейковые” сегменты (по паттерну), чередуя “fake/real/fake”.

**Параметры:**

* `pos=<marker>` — точка разреза (позиция в данных).
* `pattern=<blob>` — чем заполнять fake-сегменты.
* `nofake1..nofake4` — отключить отдельные фейковые отправки (их 4).
* `seqovl=N` / `seqovl_pattern=<blob>` — для **первого real-сегмента**: добавить оверлей-байты в начало и сдвинуть seq.
* `blob`, `optional`, `nodrop` — как раньше.

---

## fakeddisorder

```text
--lua-desync=fakeddisorder:pos=<marker>
--lua-desync=fakeddisorder:pos=<marker>:pattern=<blob>:seqovl=<marker_or_pos>:nodrop
```

**Что делает:** похож на `fakedsplit`, но отправляет части в другом порядке: сначала “вторая часть” (fake/real/fake), потом “первая” (fake/real/fake).

**Параметры:**

* `pos=<marker>` — точка разреза.
* `pattern=<blob>` — чем заполнять fake-сегменты.
* `seqovl=<marker_or_pos>` — маркер/позиция для вычисления оверлея во второй части (и проверка, что он “до split pos”).
* `nofake1..nofake4`, `seqovl_pattern`, `blob`, `optional`, `nodrop` — аналогично.

---

## tcpseg (`pos=` обязателен)

```text
--lua-desync=tcpseg:pos=<range_marker1,range_marker2>
--lua-desync=tcpseg:pos=<range_marker1,range_marker2>:seqovl=<N>:seqovl_pattern=<blob>
```

**Что делает:** отправляет **один TCP-сегмент**, который содержит ровно диапазон данных `pos[1]..pos[2]` (2 позиции). Это не “split на части”, а “вырезка диапазона и отправка”.

**Параметры:**

* `pos=<a,b>` — **обязательно**: два маркера/позиции, задающие диапазон.
* `seqovl=N` / `seqovl_pattern=<blob>` — добавить оверлей в начало диапазона и сдвинуть seq (по коду — в начало `part`).
* `blob`, `optional` — использовать blob вместо текущих данных / пропустить если нет.

---

## oob

```text
--lua-desync=oob
--lua-desync=oob:char=<1byte>:urp=<b|e|marker_or_pos>
```

**Что делает:** пытается вставить **1 байт “out-of-band”** (через TCP URG) в поток. Работает только если подключение отслеживается (`desync.track`) и должно начинаться с самого SYN (иначе отключит себя для этого коннекта).

**Параметры:**

* `char=<1byte>` — какой символ (1 байт) вставлять как OOB.
* (в коде ещё есть `byte=<0..255>` как альтернатива `char`, но в твоём шаблоне ты указал `char` — этого достаточно)
* `urp` — позиция urgent pointer:

  * `b` — начало (по коду выставляет `th_urp=0` и `urp=1`)
  * `e` — конец (после данных)
  * `<marker_or_pos>` — конкретная позиция в данных (через `resolve_pos`)

---

## udplen

```text
--lua-desync=udplen:increment=<N>
--lua-desync=udplen:min=<N>:max=<N>:increment=<N>:pattern=<blob>:pattern_offset=<N>
```

**Что делает:** только для UDP: увеличивает или уменьшает длину payload на `increment` байт (добавляя паттерн или обрезая хвост). Возвращает `VERDICT_MODIFY`, если изменил.

**Параметры:**

* `min=N` — не трогать, если payload меньше N байт.
* `max=N` — не трогать, если payload больше N байт.
* `increment=N`:

  * `>0` — увеличить на N байт
  * `<0` — уменьшить на |N| байт (но не до нуля)
  * `=0` — ничего не делать
* `pattern=<blob>` — чем заполнять добавленные байты (по умолчанию `\x00`).
* `pattern_offset=N` — смещение внутри `pattern`, откуда начинать (0 по умолчанию).

---

## dht_dn

```text
--lua-desync=dht_dn:dn=<N>
--lua-desync=dht_dn:dn=3
```

**Что делает:** только для UDP-пакетов распознанных как `dht`: меняет начало bencode-сообщения, чтобы оно начиналось с `"dN:000...1:x"` вместо `"d1:"` (по коду — “tamper” DHT).

**Параметры:**

* `dn=N` — число N в префиксе `"dN:"` (по умолчанию 3).

---

