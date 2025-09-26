#!/system/bin/sh
# Привязка процессов к ядрам — Android sh (не запускаем сервис если он не запущен)
PROGRAM_SERVICE="${PROGRAM_SERVICE:-nfqws dnscrypt-proxy ciadpi-zdt dnscrypt-proxy.toml}"
CPU_LIMITATION="${CPU_LIMITATION:-0,1,5}"

# определить taskset (или busybox taskset)
TASKSET_CMD=""
if command -v taskset >/dev/null 2>&1; then
  TASKSET_CMD="taskset"
elif command -v busybox >/dev/null 2>&1 && busybox taskset >/dev/null 2>&1; then
  TASKSET_CMD="busybox taskset"
else
  echo "Ошибка: taskset не найден." >&2
  exit 1
fi

get_pids_for_name() {
  name="$1"
  if command -v pidof >/dev/null 2>&1; then
    pids=$(pidof "$name" 2>/dev/null || true)
    [ -n "$pids" ] && { echo "$pids"; return 0; }
  fi
  if command -v pgrep >/dev/null 2>&1; then
    pids=$(pgrep -f "$name" 2>/dev/null || true)
    [ -n "$pids" ] && { echo "$pids"; return 0; }
  fi
  ps | while read line; do
    set -- $line
    pid=$2
    shift 2
    case "$pid" in ''|*[!0-9]*) continue ;; esac
    cmd="$*"
    case "$cmd" in *"$name"*) echo "$pid" ;; esac
  done
}

# вычисление маски
m=0
if [ -z "$CPU_LIMITATION" ]; then
  echo "CPU_LIMITATION пустой." >&2
  exit 1
fi
OLD_IFS="$IFS"; IFS=','
for c in $CPU_LIMITATION; do
  c=$(echo "$c" | tr -d ' ')
  case "$c" in ''|*[!0-9]*) echo "Неправильный номер ядра: '$c'." >&2; IFS="$OLD_IFS"; exit 1 ;; esac
  m=$(( m | (1 << c) ))
done
IFS="$OLD_IFS"
[ "$m" -eq 0 ] && { echo "Маска CPU = 0." >&2; exit 1; }
mask_hex=$(printf "%x" "$m")

# Применение affinity: пробуем несколько вариантов вызова taskset, чтобы покрыть toybox/busybox/прочие
for svc in $PROGRAM_SERVICE; do
  pids=$(get_pids_for_name "$svc" || true)
  if [ -z "$pids" ]; then
    echo "Сервис '$svc' — не найден/не запущен, пропускаем."
    continue
  fi

  for pid in $pids; do
    success=0
    for opt in "-ap" "-p" "-a" ""; do
      # пробуем вызвать: TASKSET_CMD <opt> <mask> <pid>
      if [ -z "$opt" ]; then
        # если opt пустой — вызываем без флагов
        if $TASKSET_CMD "$mask_hex" "$pid" >/dev/null 2>&1; then success=1; break; fi
      else
        if $TASKSET_CMD $opt "$mask_hex" "$pid" >/dev/null 2>&1; then success=1; break; fi
      fi
    done

    if [ "$success" -eq 0 ]; then
      echo "taskset не сработал для PID $pid (svc=$svc)." >&2
    fi
  done

  echo "Сервис '$svc': привязаны PID: $pids -> ядра $CPU_LIMITATION (маска 0x$mask_hex)"
done

exit 0
