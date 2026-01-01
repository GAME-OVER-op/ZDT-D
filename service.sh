#!/system/bin/sh

# --- дождаться полного старта системы
while [[ "$(getprop sys.boot_completed)" != "1" ]]; do
  sleep 5
done

# --- корректное определение каталога скрипта
MODDIR="$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)"

# --- подготовка папки логов: каждый запуск очищаем содержимое папки
LOGDIR="$MODDIR/log"
mkdir -p "$LOGDIR"
rm -f "$LOGDIR"/* 2>/dev/null || true

# --- основные лог-файлы для служб (dnscrypt без логов)
LOG_SUPERVISOR="$LOGDIR/supervisor.log"
LOG_ZDT="$LOGDIR/zdt_d_service.log"
LOG_PROXY_PY="$LOGDIR/transparent_proxy.log"

# --- создать пустые файлы логов (чтобы потом >> всегда работал)
touch "$LOG_SUPERVISOR" "$LOG_ZDT" "$LOG_PROXY_PY" 2>/dev/null || true

echo "$(date): Запуск service.sh" >> "$LOG_SUPERVISOR"

SCRIPT_PATH="$MODDIR/system/bin/script/ZDT-D.sh"

# --- Утилиты / функции -----------------------------------------------------
run_detach() {
    # $1 = команда (строка), $2 = лог-файл (может быть /dev/null)
    cmd="$1"
    logfile="$2"

    # если путь к логу — не /dev/null, попробуем создать директорию (игнорируем ошибки)
    if [ "$logfile" != "/dev/null" ]; then
        mkdir -p "$(dirname -- "$logfile")" 2>/dev/null || true
    fi

    if command -v setsid >/dev/null 2>&1; then
        setsid sh -c "exec $cmd >>\"$logfile\" 2>&1" </dev/null >/dev/null 2>&1 &
    else
        sh -c "exec $cmd >>\"$logfile\" 2>&1" </dev/null >/dev/null 2>&1 &
    fi
}


# --- Запуск ZDT-D.sh (если есть)
if [ -f "$SCRIPT_PATH" ]; then
    chmod +x "$SCRIPT_PATH" 2>/dev/null || true
    echo "$(date): Запуск ZDT-D.sh" >> "$LOG_ZDT"
    run_detach "su -c \"$SCRIPT_PATH\"" "$LOG_ZDT"
else
    echo "$(date): Скрипт $SCRIPT_PATH не найден." >> "$LOG_SUPERVISOR"
fi

# Подмена региона
resetprop -n gsm.sim.operator.iso-country eu

echo "$(date): Все службы запущены (детачены). Завершение service.sh" >> "$LOG_SUPERVISOR"

exit 0
