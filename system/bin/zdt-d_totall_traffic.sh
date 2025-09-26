#!/system/bin/sh

# Базовые переменные
BASE_DIR="/data/adb/modules/ZDT-D"
MODULE_INFO="$BASE_DIR/module.prop"
STATE_FILE="$BASE_DIR/traffic.state"
LOG_FILE="$BASE_DIR/log/traffic.log"
TMP_FILE_BASE="${MODULE_INFO}.tmp"
PID=$$

# Исходное содержимое module.prop (шаблон восстановления)
ORIGINAL_CONTENT="id=ZDT-D
name=ZDT-D
version=1.6.3
versionCode=16300
author=Ggover 
description=DPI bypass tool. Total traffic: 0.00 KB
updateJson=https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/Update/update.json"

# Логирование
log() {
    if command -v date >/dev/null 2>&1; then
        echo "$(date '+%F %T') - $1" >> "$LOG_FILE"
    else
        echo "$(cat /proc/uptime 2>/dev/null | awk '{print $1}') - $1" >> "$LOG_FILE"
    fi
}

# Проверка наличия bc
check_bc() {
    if ! command -v bc >/dev/null 2>&1; then
        log "ERROR: bc not found"
        echo "ERROR: bc not found. Please install bc package."
        exit 1
    fi
}

# Восстановление module.prop из встроенного шаблона
restore_module_prop() {
    echo "$ORIGINAL_CONTENT" > "$MODULE_INFO"
    sync
    log "module.prop restored from ORIGINAL_CONTENT"
}

# Ротация резервных копий (две копии: .bak1 — самая свежая, .bak2 — предыдущая)
rotate_backup() {
    if [ -f "${MODULE_INFO}.bak1" ]; then
        cp -p "${MODULE_INFO}.bak1" "${MODULE_INFO}.bak2" 2>/dev/null || true
    fi
    if [ -f "$MODULE_INFO" ]; then
        cp -p "$MODULE_INFO" "${MODULE_INFO}.bak1" 2>/dev/null || true
    fi
}

# Безопасная запись module.prop: создаём временный файл, проверяем, затем mv
safe_write_module_prop() {
    local tmp="${TMP_FILE_BASE}.$$"
    # Выполняем замену в temp-файл — аккуратно подставляем VALUE и UNIT
    sed -E 's/^(description=.*Total traffic: ).*/\1'"$1 $2"'/' "$MODULE_INFO" > "$tmp" 2>/dev/null
    if [ ! -f "$tmp" ]; then
        log "ERROR: temp file not created ($tmp)"
        return 1
    fi

    if [ ! -s "$tmp" ]; then
        log "ERROR: temp file is empty ($tmp)"
        rm -f "$tmp"
        return 1
    fi
    if ! grep -q "Total traffic:" "$tmp" 2>/dev/null; then
        log "ERROR: temp file doesn't contain 'Total traffic:' ($tmp)"
        rm -f "$tmp"
        return 1
    fi

    rotate_backup

    mv -f "$tmp" "$MODULE_INFO" 2>/dev/null
    sync

    if [ ! -s "$MODULE_INFO" ] || ! grep -q "Total traffic:" "$MODULE_INFO" 2>/dev/null; then
        log "ERROR: After mv module.prop is invalid, will try restore from backup"
        if [ -f "${MODULE_INFO}.bak1" ]; then
            cp -p "${MODULE_INFO}.bak1" "$MODULE_INFO" 2>/dev/null && sync
            log "Restored module.prop from ${MODULE_INFO}.bak1"
            return 0
        else
            restore_module_prop
            return 0
        fi
    fi

    log "module.prop updated successfully to: $1 $2"
    return 0
}

# Безопасная запись состояния (traffic.state) через временный файл
safe_write_state() {
    local tmp="${STATE_FILE}.tmp.$$"
    echo "$1 $2" > "$tmp"
    if [ ! -s "$tmp" ]; then
        log "ERROR: state temp file empty: $tmp"
        rm -f "$tmp"
        return 1
    fi
    mv -f "$tmp" "$STATE_FILE"
    sync
    return 0
}

# Инициализация файлов (если отсутствуют)
if [ ! -f "$MODULE_INFO" ]; then
    restore_module_prop
fi

if [ ! -f "$STATE_FILE" ]; then
    echo "0 0" > "$STATE_FILE"
    sync
fi

# Функция конвертации в читаемый формат
convert_bytes() {
    echo "$1" | awk '
    BEGIN {
        split("B KB MB GB TB PB EB", u, " ")
        idx = 1
    }
    {
        b = $1
        while (b >= 1000 && idx < length(u)) {
            b = b / 1000
            idx++
        }
        printf "%.2f %s\n", b, u[idx]
    }
    '
}

# Функция для арифметики с bc
math() {
    echo "$1" | bc -l
}

# Главный цикл
while true; do
    check_bc

    if [ ! -f "$MODULE_INFO" ]; then
        log "module.prop missing -> restore"
        restore_module_prop
        sleep 2
        continue
    fi

    # --- ВАЖНОЕ ИЗМЕНЕНИЕ: получаем весь вывод таблиц mangle и nat (включая все цепочки) ---
    LISTING=$(
        iptables -t mangle -L -v -n -x 2>/dev/null
        iptables -t nat -L -v -n -x 2>/dev/null
    )

    # Парсинг: суммируем байты для:
    # - цель NFQUEUE (в любой из перечисленных цепочек)
    # - цель RETURN, если строка содержит 'owner UID match'
    # - цель DNAT в любых цепочках (включая NAT_DPI)
    CURRENT_BYTES=$(echo "$LISTING" | awk '
    BEGIN { sum = 0; chain = "" }
    # захватываем имя цепочки в заголовке "Chain NAME (..."
    /^Chain[[:space:]]+/ {
        # $2 обычно имя цепочки, но убираем возможные "(" на конце
        chain = $2
        sub(/\(.*/, "", chain)
        next
    }
    # строки правил: первые два поля — pkts и bytes (числа)
    $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ {
        bytes = $2
        target = $3
        # NFQUEUE
        if (target == "NFQUEUE") {
            sum += bytes
        }
        # DNAT в любой цепочке (включая NAT_DPI)
        else if (target == "DNAT") {
            sum += bytes
        }
        # RETURN + owner UID match (учёт правил owner)
        else if (target == "RETURN" && $0 ~ /owner[[:space:]]+UID[[:space:]]+match/) {
            sum += bytes
        }
    }
    END { print sum }
    ')

    # Защита: если awk вернул пустую строку -> 0
    CURRENT_BYTES=${CURRENT_BYTES:-0}

    # Чтение предыдущего состояния (защищённо)
    if ! read LAST_COUNTER ACCUMULATED < "$STATE_FILE" 2>/dev/null; then
        LAST_COUNTER=0
        ACCUMULATED=0
    fi

    # Рассчёт дельты (учёт возможного отката счётчика)
    if [ "$(math "$CURRENT_BYTES >= $LAST_COUNTER")" -eq 1 ]; then
        DELTA=$(math "$CURRENT_BYTES - $LAST_COUNTER")
    else
        # Если счётчик уменьшился (перезапуск/обнуление) — считаем весь текущий
        DELTA=$CURRENT_BYTES
    fi

    NEW_ACC=$(math "$ACCUMULATED + $DELTA")

    # Защита от отрицательных (навсегда вернуть предыдущие значения, если что-то не так)
    if [ "$(math "$NEW_ACC < 0")" -eq 1 ]; then
        NEW_ACC=$ACCUMULATED
        CURRENT_BYTES=$LAST_COUNTER
    fi

    if ! safe_write_state "$CURRENT_BYTES" "$NEW_ACC"; then
        log "ERROR: failed to write state ($STATE_FILE)"
    fi

    HR=$(convert_bytes "$NEW_ACC")
    VALUE=$(echo "$HR" | awk '{print $1}')
    UNIT=$(echo "$HR" | awk '{print $2}')

    if ! grep -q "description=.*Total traffic:" "$MODULE_INFO" 2>/dev/null; then
        log "WARN: 'Total traffic' not found in module.prop -> restore template before update"
        restore_module_prop
    fi

    if ! safe_write_module_prop "$VALUE" "$UNIT"; then
        log "ERROR: safe_write_module_prop failed, attempted restore from backup/template"
    fi

    sleep 300
done
