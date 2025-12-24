#!/system/bin/sh
# zdt-d_system_update_daemon_v3.sh
# Daily updater + WebUI show flow (KernelSU WebUI).
# - Run once per day after RUN_HHMM (default 02:00)
# - Download manifest + files into /data/adb/modules/ZDT-D/download/
# - Verify sha256, install to device paths
# - Download index.html into ZDT-D-UPDATE/webroot/
# - Notify: install done -> wait OFF 5m -> wait ON 7s -> notify "please wait" -> open WebUI after 15s
# - Delete /data/adb/modules/ZDT-D-UPDATE/ 15s after open (guarded: only if WebUI process detected)
# - Adaptive daemon sleep: 5h / 3h / 2h / 1h / 30m / 10m / 20s

LOGTAG="ZDT-D-update-daemon"
PING_TARGET="8.8.8.8"

DAM="/data/adb/modules/ZDT-D"
UPDATE_MOD="/data/adb/modules/ZDT-D-UPDATE"

WORKING="$DAM/working_folder"
LOGDIR="$DAM/log"
LOGFILE="$LOGDIR/system_update.log"

DOWNLOAD_DIR="$DAM/download"

REMOTE_MANIFEST_URL="https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/Update/system_update.json"
LOCAL_MANIFEST="$DAM/files/system_update.json"

# tmp files — only in download/
TMP_MANIFEST="$DOWNLOAD_DIR/system_update.json"
ENTRIES_FILE="$DOWNLOAD_DIR/system_update.entries.jsonl"
TOINSTALL_LIST="$DOWNLOAD_DIR/system_update.toinstall.list"
INDEX_TMP="$DOWNLOAD_DIR/index.html"

# Defaults for info page (can be overridden from manifest: infoPageGitHub/infoPageDevice)
INDEX_REMOTE_URL_DEFAULT="https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/Update/index.html"
INDEX_LOCAL_PATH_DEFAULT="$UPDATE_MOD/webroot/index.html"
INDEX_REMOTE_URL="$INDEX_REMOTE_URL_DEFAULT"
INDEX_LOCAL_PATH="$INDEX_LOCAL_PATH_DEFAULT"

LOCKDIR="$WORKING/.update_lock"

# Schedule
RUN_HHMM="${RUN_HHMM:-0200}"  # 02:00 daily

# Internet retries (inside daily cycle)
NET_RETRY_COUNT="${NET_RETRY_COUNT:-2}"
NET_RETRY_SLEEP="${NET_RETRY_SLEEP:-1800}" # 30 min

# UX rules
WAIT_OFF_AFTER_INSTALL_SEC="${WAIT_OFF_AFTER_INSTALL_SEC:-300}"  # 5 min screen OFF after install
ON_STABLE_SEC="${ON_STABLE_SEC:-7}"                              # screen ON 7 sec
OPEN_AFTER_SEC="${OPEN_AFTER_SEC:-15}"                           # open after 15 sec
DELETE_UPDATE_MOD_AFTER_OPEN_SEC="${DELETE_UPDATE_MOD_AFTER_OPEN_SEC:-15}"

# Max wait windows
WAIT_OFF_MAX_SEC="${WAIT_OFF_MAX_SEC:-43200}" # 12h
WAIT_ON_MAX_SEC="${WAIT_ON_MAX_SEC:-43200}"   # 12h

DEFAULT_MODE_SH="0755"

# Logging
LOG_TO_STDERR="${LOG_TO_STDERR:-1}"

now() { date '+%Y-%m-%d %H:%M:%S'; }
mkdir_p() { [ -d "$1" ] || mkdir -p "$1" >/dev/null 2>&1 || true; }
have_cmd() { command -v "$1" >/dev/null 2>&1; }

log() {
  mkdir_p "$LOGDIR"
  line="$(now) [$LOGTAG] $*"
  printf "%s\n" "$line" >>"$LOGFILE" 2>/dev/null || true
  [ "$LOG_TO_STDERR" = "1" ] && printf "%s\n" "$line" >&2
}

cleanup_lock() { [ -d "$LOCKDIR" ] && rm -rf "$LOCKDIR" >/dev/null 2>&1 || true; }
trap cleanup_lock EXIT HUP INT TERM

acquire_lock() {
  mkdir_p "$WORKING"
  if mkdir "$LOCKDIR" 2>/dev/null; then
    echo "$$" >"$LOCKDIR/pid" 2>/dev/null || true
    log "LOCK acquired: $LOCKDIR (pid=$$)"
    return 0
  fi
  oldpid="$(cat "$LOCKDIR/pid" 2>/dev/null || true)"
  log "LOCK busy: $LOCKDIR (pid=${oldpid:-unknown}) -> skip"
  return 1
}

# ---------------- Time helpers ----------------
hm_to_minutes() {
  hm="$1"
  hh="${hm%??}"
  mm="${hm#??}"
  # force base-10
  echo $((10#$hh * 60 + 10#$mm))
}

now_minutes() {
  h="$(date +%H 2>/dev/null || echo 0)"
  m="$(date +%M 2>/dev/null || echo 0)"
  echo $((10#$h * 60 + 10#$m))
}

mins_until_next_run() {
  nm="$(now_minutes)"
  rm="$(hm_to_minutes "$RUN_HHMM")"
  if [ "$nm" -le "$rm" ]; then
    echo $((rm - nm))
  else
    echo $((1440 - (nm - rm)))
  fi
}

choose_daemon_sleep_sec() {
  mins="$1"
  # requested steps: 5h, 3h, 2h, 1h, 30m, 10m, else 20s
  if [ "$mins" -gt 300 ] 2>/dev/null; then sec=18000
  elif [ "$mins" -gt 180 ] 2>/dev/null; then sec=10800
  elif [ "$mins" -gt 120 ] 2>/dev/null; then sec=7200
  elif [ "$mins" -gt 60 ] 2>/dev/null; then sec=3600
  elif [ "$mins" -gt 30 ] 2>/dev/null; then sec=1800
  elif [ "$mins" -gt 10 ] 2>/dev/null; then sec=600
  else sec=20
  fi

  # never oversleep past run moment (keep 5s margin), unless we're in 20s mode anyway
  maxsec=$((mins * 60 - 5))
  if [ "$maxsec" -gt 0 ] 2>/dev/null; then
    if [ "$sec" -gt "$maxsec" ] 2>/dev/null; then sec="$maxsec"; fi
  else
    # if already at/after run, keep short sleep
    [ "$sec" -gt 20 ] && sec=20
  fi
  [ "$sec" -lt 5 ] 2>/dev/null && sec=5
  echo "$sec"
}

# ---------------- Internet ----------------
check_internet_once() { ping -c 1 "$PING_TARGET" >/dev/null 2>&1; }

ensure_internet_with_retries() {
  i=1
  while [ "$i" -le "$NET_RETRY_COUNT" ]; do
    log "Проверка интернета (попытка $i/$NET_RETRY_COUNT) ping $PING_TARGET ..."
    if check_internet_once; then
      log "Интернет OK."
      return 0
    fi
    log "Интернета нет."
    i=$((i+1))
    if [ "$i" -le "$NET_RETRY_COUNT" ]; then
      log "Жду ${NET_RETRY_SLEEP}s и повторяю..."
      sleep "$NET_RETRY_SLEEP"
    fi
  done
  log "Интернета нет после ${NET_RETRY_COUNT} попыток -> перенос на следующий день."
  return 1
}

# ---------------- SHA256 ----------------
sha256_file() {
  f="$1"
  if have_cmd sha256sum; then sha256sum "$f" 2>/dev/null | awk '{print $1}'; return; fi
  if have_cmd busybox; then busybox sha256sum "$f" 2>/dev/null | awk '{print $1}'; return; fi
  if have_cmd openssl; then openssl dgst -sha256 "$f" 2>/dev/null | awk '{print $NF}'; return; fi
  echo ""
}

looks_like_html_or_error() {
  f="$1"
  head256="$(head -c 256 "$f" 2>/dev/null | tr -d '\r')"
  echo "$head256" | grep -qiE '<!doctype html|<html|<head|<body|404: Not Found|Access denied|captcha|cloudflare|Just a moment' && return 0
  return 1
}

strip_utf8_bom_if_any() {
  f="$1"
  # detect BOM by first 3 bytes = ef bb bf
  if have_cmd od && have_cmd dd && have_cmd tail; then
    sig="$(dd if="$f" bs=3 count=1 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')"
    if [ "$sig" = "efbbbf" ]; then
      tmp="$DOWNLOAD_DIR/.nobom.$$"
      tail -c +4 "$f" >"$tmp" 2>/dev/null || { rm -f "$tmp" >/dev/null 2>&1 || true; return 1; }
      mv -f "$tmp" "$f" >/dev/null 2>&1 || true
      log "SANITIZE: stripped UTF-8 BOM"
    fi
  fi
  return 0
}

sanitize_json_inplace() {
  f="$1"
  [ -f "$f" ] || return 1
  tmp="$DOWNLOAD_DIR/.san.$$"
  # remove NUL + CR (some proxies inject), keep LF
  LC_ALL=C tr -d '\000\r' <"$f" >"$tmp" 2>/dev/null || { rm -f "$tmp" >/dev/null 2>&1 || true; return 1; }
  mv -f "$tmp" "$f" >/dev/null 2>&1 || true
  strip_utf8_bom_if_any "$f" || true
  return 0
}

# ---------------- Download (curl/wget/busybox + python3 fallback) ----------------
download_with_py3() {
  url="$1"; out="$2"
  py="$DOWNLOAD_DIR/.py_dl.$$"
  cat >"$py" <<'PY'
import sys, urllib.request
url=sys.argv[1]; out=sys.argv[2]
with urllib.request.urlopen(url, timeout=60) as r:
    data=r.read()
with open(out,"wb") as f:
    f.write(data)
PY

  if have_cmd python3; then
    python3 "$py" "$url" "$out" >/dev/null 2>&1
    rc=$?
    rm -f "$py" >/dev/null 2>&1 || true
    return $rc
  fi

  if have_cmd su && su -c "command -v python3" >/dev/null 2>&1; then
    su -c "python3 '$py' '$url' '$out'" >/dev/null 2>&1
    rc=$?
    rm -f "$py" >/dev/null 2>&1 || true
    return $rc
  fi

  rm -f "$py" >/dev/null 2>&1 || true
  return 1
}

download_url() {
  url="$1"; out="$2"
  mkdir_p "$(dirname "$out")"
  rm -f "$out" >/dev/null 2>&1 || true

  if have_cmd curl; then
    log "DOWNLOAD: curl -fsSL -> $url"
    curl -fsSL --max-time 60 --retry 2 --retry-delay 2 -o "$out" "$url" >/dev/null 2>&1 && [ -s "$out" ] && return 0
    log "FAIL: curl"
    log "DOWNLOAD: curl -kfsSL -> $url"
    curl -kfsSL --max-time 60 --retry 2 --retry-delay 2 -o "$out" "$url" >/dev/null 2>&1 && [ -s "$out" ] && return 0
    log "FAIL: curl -k"
  fi

  if have_cmd wget; then
    log "DOWNLOAD: wget -> $url"
    wget -q -O "$out" --timeout=60 --tries=2 "$url" >/dev/null 2>&1 && [ -s "$out" ] && return 0
    log "FAIL: wget"
  fi

  if have_cmd busybox; then
    log "DOWNLOAD: busybox wget -> $url"
    busybox wget -q -O "$out" "$url" >/dev/null 2>&1 && [ -s "$out" ] && return 0
    log "FAIL: busybox wget"
  fi

  log "DOWNLOAD: python3 fallback -> $url"
  download_with_py3 "$url" "$out" >/dev/null 2>&1 && [ -s "$out" ] && return 0
  log "FAIL: python3 fallback"

  rm -f "$out" >/dev/null 2>&1 || true
  return 1
}

# ---------------- WebUI skeleton ----------------
ensure_update_webui_skeleton() {
  mkdir_p "$UPDATE_MOD/webroot/data"

  if [ ! -f "$UPDATE_MOD/module.prop" ]; then
    log "Создаю $UPDATE_MOD/module.prop"
    mkdir_p "$UPDATE_MOD"
    cat >"$UPDATE_MOD/module.prop" <<'EOF'
id=ZDT-D-UPDATE
name=ZDT-D-UPDATE
version=0
versionCode=0
author=UPDATE
EOF
  fi

  if [ -f "$DAM/webroot/kernelsu.js" ] && [ ! -f "$UPDATE_MOD/webroot/kernelsu.js" ]; then
    log "Копирую kernelsu.js -> ZDT-D-UPDATE/webroot/"
    cp -f "$DAM/webroot/kernelsu.js" "$UPDATE_MOD/webroot/kernelsu.js" >/dev/null 2>&1 || true
  fi
}

github_to_raw_url() {
  u="$1"
  # github blob/tree -> raw
  printf '%s' "$u" | sed -e 's#^https://github.com/#https://raw.githubusercontent.com/#' -e 's#/blob/#/#' -e 's#/tree/#/#'
}

refresh_webui_index_via_download() {
  log "Скачиваю index.html (WebUI) -> $INDEX_LOCAL_PATH"
  rm -f "$INDEX_TMP" >/dev/null 2>&1 || true
  if ! download_url "$INDEX_REMOTE_URL" "$INDEX_TMP"; then
    log "WARN: не удалось скачать index.html"
    return 1
  fi
  mkdir_p "$(dirname "$INDEX_LOCAL_PATH")"
  mv -f "$INDEX_TMP" "$INDEX_LOCAL_PATH" >/dev/null 2>&1 || true
  log "OK: index.html обновлён -> $INDEX_LOCAL_PATH"
  return 0
}

load_infopage_from_manifest() {
  f="$1"
  INDEX_REMOTE_URL="$INDEX_REMOTE_URL_DEFAULT"
  INDEX_LOCAL_PATH="$INDEX_LOCAL_PATH_DEFAULT"

  if have_cmd jq; then
    ipg="$(jq -r '.infoPageGitHub // (.files[0].infoPageGitHub // empty) // empty' "$f" 2>/dev/null)"
    ipd="$(jq -r '.infoPageDevice // (.files[0].infoPageDevice // empty) // empty' "$f" 2>/dev/null)"

    if [ -n "$ipg" ] && [ "$ipg" != "null" ]; then
      # if they give github blob url, convert to raw
      case "$ipg" in
        https://raw.githubusercontent.com/*|http://raw.githubusercontent.com/*) INDEX_REMOTE_URL="$ipg" ;;
        https://github.com/*) INDEX_REMOTE_URL="$(github_to_raw_url "$ipg")" ;;
        *) INDEX_REMOTE_URL="$ipg" ;;
      esac
    fi

    if [ -n "$ipd" ] && [ "$ipd" != "null" ]; then
      case "$ipd" in
        */) INDEX_LOCAL_PATH="${ipd}index.html" ;;
        *)  INDEX_LOCAL_PATH="${ipd%/}/index.html" ;;
      esac
    fi
  fi

  log "INFO PAGE: remote=$INDEX_REMOTE_URL"
  log "INFO PAGE: local =$INDEX_LOCAL_PATH"
}

# ---------------- JSON entries ----------------
derive_raw_url() {
  github="$1"
  name="$2"
  case "$github" in
    https://raw.githubusercontent.com/*|http://raw.githubusercontent.com/*) base="$github" ;;
    *) base="$(printf '%s' "$github" | sed -e 's#^https://github.com/#https://raw.githubusercontent.com/#' -e 's#/tree/#/#' -e 's#/blob/#/#')" ;;
  esac
  case "$base" in */) printf '%s%s' "$base" "$name" ;; *) printf '%s' "$base" ;; esac
}

prepare_entries_from_manifest() {
  f="$1"

  if ! have_cmd jq; then
    log "ERROR: jq не найден"
    return 11
  fi

  sanitize_json_inplace "$f" || true

  if ! jq -e '.' "$f" >/dev/null 2>&1; then
    errline="$(jq -e '.' "$f" 2>&1 | head -n 1)"
    log "ERROR: manifest не JSON (jq parse failed): $errline"
    log "MANIFEST size: $(wc -c <"$f" 2>/dev/null || echo 0) bytes"
    log "head: $(head -c 220 "$f" 2>/dev/null | tr '\r\n' ' ')"
    return 12
  fi

  jtype="$(jq -r 'type' "$f" 2>/dev/null)"
  log "MANIFEST JSON type: $jtype"

  rm -f "$ENTRIES_FILE" >/dev/null 2>&1 || true
  if [ "$jtype" = "object" ] && [ "$(jq -r 'has("files")' "$f")" = "true" ] && [ "$(jq -r '.files|type' "$f")" = "array" ]; then
    log "MANIFEST format: object.files[]"
    jq -c '.files[]' "$f" >"$ENTRIES_FILE" 2>/dev/null || true
  elif [ "$jtype" = "array" ]; then
    log "MANIFEST format: array[]"
    jq -c '.[]' "$f" >"$ENTRIES_FILE" 2>/dev/null || true
  else
    log "MANIFEST format: single object"
    jq -c '.' "$f" >"$ENTRIES_FILE" 2>/dev/null || true
  fi

  cnt="$(wc -l <"$ENTRIES_FILE" 2>/dev/null || echo 0)"
  log "MANIFEST entries prepared: $cnt"
  return 0
}

# ---------------- Notifications (tag + proper who:text) ----------------
send_notification() {
  # $1 msg, $2 tag, $3 title
  msg="$1"
  tag="${2:-UpdateInfo}"
  title="${3:-ZDT-D}"

  # Messaging style expects --message <who>:<text>
  # Force "who" = ZDT-D so Android won't show "You"
  body="ZDT-D:${msg}"

  ICON1="file:///data/local/tmp/icon1.png"
  ICON2="file:///data/local/tmp/icon2.png"
  ICON_ARG=""
  [ -f "/data/local/tmp/icon1.png" ] && [ -f "/data/local/tmp/icon2.png" ] && ICON_ARG="-i $ICON1 -I $ICON2"

  # escape for single quotes inside '...'
  esc_sq() { printf '%s' "$1" | sed "s/'/'\"'\"'/g"; }

  body_esc="$(esc_sq "$body")"
  tag_esc="$(esc_sq "$tag")"
  title_esc="$(esc_sq "$title")"

  if have_cmd su; then
    # last arg = TEXT (use same body so list view shows correct text)
    NOTIF_CMD="cmd notification post $ICON_ARG -S messaging --conversation 'ZDT-D' --message '$body_esc' -t '$title_esc' '$tag_esc' '$body_esc'"
    su -lp 2000 -c "$NOTIF_CMD" >/dev/null 2>&1
    rc=$?
    [ $rc -eq 0 ] && log "OK: уведомление отправлено (tag=$tag)" || log "WARN: уведомление не отправилось (rc=$rc)"
    return $rc
  fi

  log "WARN: su не найден — уведомление не отправлено"
  return 1
}

# ---------------- Screen state ----------------
is_screen_off() {
  if have_cmd dumpsys; then
    dumpsys power 2>/dev/null | grep -Ei -m1 "mInteractive=(false|0)|Display Power.*state=OFF|state=OFF" >/dev/null 2>&1 && return 0
  fi
  for fb in /sys/class/graphics/fb*/blank; do
    [ -r "$fb" ] || continue
    v="$(cat "$fb" 2>/dev/null || echo "")"
    case "$v" in ""|0) ;; *) return 0 ;; esac
  done
  for b in /sys/class/backlight/*/brightness; do
    [ -r "$b" ] || continue
    v="$(cat "$b" 2>/dev/null || echo "")"
    [ "$v" = "0" ] && return 0
  done
  return 1
}

wait_screen_off_stable() {
  need="$1"
  max="$2"
  log "Жду экран OFF непрерывно ${need}s (max wait ${max}s)..."
  off=0
  waited=0
  while [ "$waited" -lt "$max" ]; do
    if is_screen_off; then
      off=$((off+5))
      [ "$off" -eq 5 ] && log "Экран OFF (таймер пошёл)"
      [ "$off" -ge "$need" ] && { log "OFF условие выполнено (${off}s)"; return 0; }
    else
      [ "$off" -gt 0 ] && log "Экран включился -> сброс OFF (было ${off}s)"
      off=0
    fi
    sleep 5
    waited=$((waited+5))
  done
  log "OFF условие не выполнено за ${max}s"
  return 1
}

wait_screen_on_stable() {
  need="$1"
  max="$2"
  log "Жду экран ON непрерывно ${need}s (max wait ${max}s)..."
  on=0
  waited=0
  while [ "$waited" -lt "$max" ]; do
    if is_screen_off; then
      [ "$on" -gt 0 ] && log "Экран снова OFF -> сброс ON (было ${on}s)"
      on=0
    else
      on=$((on+1))
      [ "$on" -eq 1 ] && log "Экран ON (таймер пошёл)"
      [ "$on" -ge "$need" ] && { log "ON условие выполнено (${on}s)"; return 0; }
    fi
    sleep 1
    waited=$((waited+1))
  done
  log "ON условие не выполнено за ${max}s"
  return 1
}

# ---------------- WebUI open + guarded delete ----------------
open_webui() {
  CMD="am start -n io.github.a13e300.ksuwebui/.WebUIActivity --es id \"ZDT-D-UPDATE\""
  log "OPEN WebUI: $CMD"
  if have_cmd su; then
    su -lp 2000 -c "$CMD" >/dev/null 2>&1
  else
    am start -n io.github.a13e300.ksuwebui/.WebUIActivity --es id "ZDT-D-UPDATE" >/dev/null 2>&1
  fi
  log "OK: команда открытия отправлена"
}

is_webui_running() {
  pkg="io.github.a13e300.ksuwebui"
  if have_cmd pidof; then
    p="$(pidof "$pkg" 2>/dev/null || true)"
    [ -n "$p" ] && return 0
  fi
  if have_cmd pgrep; then
    p="$(pgrep -f "$pkg" 2>/dev/null || true)"
    [ -n "$p" ] && return 0
  fi
  if have_cmd ps; then
    ps 2>/dev/null | grep -F "$pkg" | grep -v grep >/dev/null 2>&1 && return 0
    ps -A 2>/dev/null | grep -F "$pkg" | grep -v grep >/dev/null 2>&1 && return 0
  fi
  return 1
}

delete_update_module_after_open_guarded() {
  sec="$1"
  [ -d "$UPDATE_MOD" ] || { log "DELETE: $UPDATE_MOD отсутствует -> пропуск"; return 0; }

  log "DELETE: защита — удалю $UPDATE_MOD через ${sec}s, только если WebUI стартовал"
  t0="$(date +%s 2>/dev/null || echo 0)"
  started=0

  while true; do
    if is_webui_running; then
      started=1
      log "DELETE: WebUI процесс обнаружен -> удаление разрешено"
      break
    fi
    now_epoch="$(date +%s 2>/dev/null || echo 0)"
    elapsed=$((now_epoch - t0))
    [ "$elapsed" -ge "$sec" ] 2>/dev/null && break
    sleep 1
  done

  if [ "$started" -ne 1 ]; then
    log "DELETE: WebUI не стартовал за ${sec}s -> модуль НЕ удаляю"
    return 0
  fi

  now_epoch="$(date +%s 2>/dev/null || echo 0)"
  elapsed=$((now_epoch - t0))
  remain=$((sec - elapsed))
  [ "$remain" -gt 0 ] 2>/dev/null && sleep "$remain"

  rm -rf "$UPDATE_MOD" >/dev/null 2>&1 || true
  log "DELETE: муляж-модуль удалён: $UPDATE_MOD"
}

# ---------------- Update phases ----------------
download_all_needed() {
  mkdir_p "$DOWNLOAD_DIR"
  : > "$TOINSTALL_LIST" 2>/dev/null || true

  need=0; okdl=0; fail=0

  while IFS= read -r item || [ -n "$item" ]; do
    name="$(printf '%s' "$item" | jq -r '.name // empty')"
    deviceDir="$(printf '%s' "$item" | jq -r '.deviceDir // empty')"
    devicePathIn="$(printf '%s' "$item" | jq -r '.devicePath // empty')"
    githubDir="$(printf '%s' "$item" | jq -r '.githubDir // empty')"
    rawUrlIn="$(printf '%s' "$item" | jq -r '.rawUrl // empty')"
    wantSha="$(printf '%s' "$item" | jq -r '.sha256 // empty')"
    mode="$(printf '%s' "$item" | jq -r '.mode // empty')"

    [ -z "$name" ] && continue

    if [ -n "$devicePathIn" ] && [ "$devicePathIn" != "null" ]; then
      devicePath="$devicePathIn"
    else
      deviceDir="${deviceDir%/}"
      devicePath="$deviceDir/$name"
    fi

    if [ -n "$rawUrlIn" ] && [ "$rawUrlIn" != "null" ]; then
      rawUrl="$rawUrlIn"
    else
      rawUrl="$(derive_raw_url "$githubDir" "$name")"
    fi

    localSha=""
    [ -f "$devicePath" ] && localSha="$(sha256_file "$devicePath")"

    if [ -n "$wantSha" ] && [ -n "$localSha" ] && [ "$wantSha" = "$localSha" ]; then
      log "OK: $name (sha совпал) -> не нужно"
      continue
    fi

    need=$((need+1))
    log "NEED: $name"
    log "  devicePath: $devicePath"
    log "  rawUrl    : $rawUrl"

    outPart="$DOWNLOAD_DIR/$name.part"
    outReady="$DOWNLOAD_DIR/$name"
    rm -f "$outPart" "$outReady" >/dev/null 2>&1 || true

    if ! download_url "$rawUrl" "$outPart"; then
      log "ERROR: download failed for $name"
      fail=$((fail+1))
      continue
    fi

    case "$name" in
      *.sh|*.py|*.json|*.conf|*.ini|*.txt|*.list|*.html)
        if looks_like_html_or_error "$outPart"; then
          log "ERROR: скачалось похоже HTML/ошибка -> $name"
          mv -f "$outPart" "$DOWNLOAD_DIR/$name.bad" >/dev/null 2>&1 || true
          fail=$((fail+1))
          continue
        fi
      ;;
    esac

    gotSha="$(sha256_file "$outPart")"
    log "  downloadedSha: $gotSha"

    if [ -n "$wantSha" ] && [ "$wantSha" != "$gotSha" ]; then
      log "ERROR: sha mismatch for $name"
      mv -f "$outPart" "$DOWNLOAD_DIR/$name.bad" >/dev/null 2>&1 || true
      fail=$((fail+1))
      continue
    fi

    mv -f "$outPart" "$outReady" >/dev/null 2>&1 || true
    [ -z "$mode" ] || [ "$mode" = "null" ] && mode=""
    [ -z "$mode" ] && mode="$DEFAULT_MODE_SH"

    printf '%s|%s|%s\n' "$name" "$devicePath" "$mode" | tr -d '\r' >> "$TOINSTALL_LIST" 2>/dev/null || true
    log "QUEUE: $name -> $devicePath (mode=$mode)"
    okdl=$((okdl+1))
  done < "$ENTRIES_FILE"

  log "DOWNLOAD SUMMARY: need=$need okdl=$okdl fail=$fail"
  if [ "$need" -eq 0 ]; then return 2; fi
  [ "$fail" -eq 0 ] && [ "$okdl" -eq "$need" ] && return 0
  return 1
}

install_all() {
  installed=0
  failed=0

  log "INSTALL LIST:"
  if [ -f "$TOINSTALL_LIST" ]; then
    awk '{print "  " NR ": " $0}' "$TOINSTALL_LIST" 2>/dev/null | head -n 60 >&2 || true
  fi

  while IFS='|' read -r name devicePath mode || [ -n "$name" ]; do
    [ -z "$name" ] && continue
    [ -z "$devicePath" ] && { log "ERROR: пустой devicePath для $name"; failed=$((failed+1)); continue; }
    [ -z "$mode" ] && mode="$DEFAULT_MODE_SH"

    src="$DOWNLOAD_DIR/$name"
    dst="$devicePath"
    dstDir="$(dirname "$dst")"

    log "INSTALL: $name"
    log "  src: $src"
    log "  dst: $dst"
    log "  mode: $mode"

    [ -f "$src" ] || { log "ERROR: src missing $src"; failed=$((failed+1)); continue; }

    mkdir_p "$dstDir"

    if [ -f "$dst" ]; then
      bdir="$WORKING/backup"
      mkdir_p "$bdir"
      ts="$(date +%Y%m%d_%H%M%S)"
      cp -f "$dst" "$bdir/$name.$ts.bak" >/dev/null 2>&1 || true
    fi

    if mv -f "$src" "$dst" >/dev/null 2>&1; then
      chmod "$mode" "$dst" >/dev/null 2>&1 || true
      installed=$((installed+1))
      log "OK: installed $name"
    else
      failed=$((failed+1))
      log "ERROR: install failed $name"
    fi
  done < "$TOINSTALL_LIST"

  log "INSTALL SUMMARY: installed=$installed failed=$failed"
  [ "$failed" -eq 0 ] && [ "$installed" -gt 0 ] && return 0
  [ "$installed" -eq 0 ] && [ "$failed" -eq 0 ] && return 2
  return 1
}

# ---------------- Main daily cycle ----------------
run_daily_cycle() {
  if ! acquire_lock; then
    return 0
  fi

  mkdir_p "$DOWNLOAD_DIR"
  rm -f "$ENTRIES_FILE" "$TOINSTALL_LIST" "$TMP_MANIFEST" "$INDEX_TMP" >/dev/null 2>&1 || true

  log "=== DAILY CYCLE START ==="

  if ! ensure_internet_with_retries; then
    cleanup_lock
    log "=== DAILY CYCLE END (no internet) ==="
    return 0
  fi

  # Download manifest
  log "Скачиваю manifest -> $TMP_MANIFEST"
  if ! download_url "$REMOTE_MANIFEST_URL" "$TMP_MANIFEST"; then
    log "ERROR: не удалось скачать manifest"
    cleanup_lock
    log "=== DAILY CYCLE END (manifest fail) ==="
    return 0
  fi

  if looks_like_html_or_error "$TMP_MANIFEST"; then
    log "ERROR: manifest похож на HTML/ошибку (подмена/блок)"
    cleanup_lock
    log "=== DAILY CYCLE END (bad manifest content) ==="
    return 0
  fi

  sanitize_json_inplace "$TMP_MANIFEST" || true

  mkdir_p "$(dirname "$LOCAL_MANIFEST")"
  cp -f "$TMP_MANIFEST" "$LOCAL_MANIFEST" >/dev/null 2>&1 || true
  log "Manifest сохранён локально: $LOCAL_MANIFEST"

  # Parse entries (retry once if jq fails due to BOM/truncation)
  if ! prepare_entries_from_manifest "$LOCAL_MANIFEST"; then
    log "WARN: manifest parse failed -> retry download once"
    if download_url "$REMOTE_MANIFEST_URL" "$TMP_MANIFEST"; then
      sanitize_json_inplace "$TMP_MANIFEST" || true
      cp -f "$TMP_MANIFEST" "$LOCAL_MANIFEST" >/dev/null 2>&1 || true
      if ! prepare_entries_from_manifest "$LOCAL_MANIFEST"; then
        log "ERROR: manifest parse/prepare failed (after retry)"
        cleanup_lock
        log "=== DAILY CYCLE END (bad manifest) ==="
        return 0
      fi
    else
      log "ERROR: retry download manifest failed"
      cleanup_lock
      log "=== DAILY CYCLE END (bad manifest) ==="
      return 0
    fi
  fi

  # Load infoPageGitHub/infoPageDevice (if present)
  load_infopage_from_manifest "$LOCAL_MANIFEST"

  download_all_needed
  dl_rc=$?

  if [ $dl_rc -eq 2 ]; then
    log "Обновления не требуются."
    rm -f "$ENTRIES_FILE" "$TOINSTALL_LIST" "$TMP_MANIFEST" "$INDEX_TMP" >/dev/null 2>&1 || true
    cleanup_lock
    log "=== DAILY CYCLE END (nothing) ==="
    return 0
  fi

  if [ $dl_rc -ne 0 ]; then
    log "ERROR: не удалось скачать все нужные файлы. download/ оставляю для диагностики."
    cleanup_lock
    log "=== DAILY CYCLE END (download fail) ==="
    return 0
  fi

  if ! install_all; then
    log "ERROR: установка с ошибками. download/ оставляю."
    cleanup_lock
    log "=== DAILY CYCLE END (install fail) ==="
    return 0
  fi

  # Prepare WebUI + index.html
  ensure_update_webui_skeleton
  refresh_webui_index_via_download || true

  # Remove download dir after successful install
  log "Удаляю папку download: $DOWNLOAD_DIR"
  rm -rf "$DOWNLOAD_DIR" >/dev/null 2>&1 || true

  # Notify install done
  send_notification "Произведена установка важных обновлений" "UpdateDone" "Установка завершена" || true

  # Release lock (UX flow no longer needs lock)
  cleanup_lock

  # UX: wait OFF 5 min, then ON 7 sec, then notify + open WebUI
  log "Теперь жду OFF ${WAIT_OFF_AFTER_INSTALL_SEC}s (после установки), чтобы не мешать..."
  if ! wait_screen_off_stable "$WAIT_OFF_AFTER_INSTALL_SEC" "$WAIT_OFF_MAX_SEC"; then
    log "Не дождался OFF окна -> показ страницы отменён."
    log "=== DAILY CYCLE END (no OFF window for UX) ==="
    return 0
  fi

  if ! wait_screen_on_stable "$ON_STABLE_SEC" "$WAIT_ON_MAX_SEC"; then
    log "Не дождался ON окна -> показ страницы отменён."
    log "=== DAILY CYCLE END (no ON window for UX) ==="
    return 0
  fi

  send_notification "Подождите пожалуйста, сейчас откроется страница с информацией об обновлениях" "UpdateOpen" "Информация об обновлениях" || true
  log "Жду ${OPEN_AFTER_SEC}s перед открытием WebUI..."
  sleep "$OPEN_AFTER_SEC"
  open_webui
  delete_update_module_after_open_guarded "$DELETE_UPDATE_MOD_AFTER_OPEN_SEC"

  log "=== DAILY CYCLE END (ok) ==="
  return 0
}

# ---------------- Daemon loop ----------------
log "Daemon start. Run time HHMM=$RUN_HHMM (daily)."

LAST_RUN_DATE=""

while true; do
  cur_date="$(date +%F 2>/dev/null || echo "")"
  nm="$(now_minutes)"
  rm="$(hm_to_minutes "$RUN_HHMM")"

  # Trigger once per date, when now >= run_time (also works if started late)
  if [ -n "$cur_date" ] && [ "$nm" -ge "$rm" ] 2>/dev/null; then
    if [ "$LAST_RUN_DATE" != "$cur_date" ]; then
      LAST_RUN_DATE="$cur_date"
      log "Trigger: $cur_date (now_minutes=$nm run_minutes=$rm)"
      run_daily_cycle
    fi
  fi

  mins="$(mins_until_next_run)"
  sleepsec="$(choose_daemon_sleep_sec "$mins")"
  log "Sleep plan: mins_until_run=$mins -> sleep=${sleepsec}s"
  sleep "$sleepsec"
done
