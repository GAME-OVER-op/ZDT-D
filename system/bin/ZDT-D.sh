#!/system/bin/sh

# Путь к файлу конфигурации
DAM="/data/adb/modules/ZDT-D"
WORKING_FOLDER="$DAM/working_folder"
BIN_DIR="$DAM/system/bin"
TMP_DIR="/data/local/tmp"

CONFIG_FILE="$WORKING_FOLDER/config0"
CONFIG_FILE1="$WORKING_FOLDER/config1"
CONFIG_FILE2="$WORKING_FOLDER/config2"
UID_INPUT_FILES0="$WORKING_FOLDER/uid_program0"
UID_INPUT_FILES1="$WORKING_FOLDER/uid_program1"
UID_INPUT_FILES2="$WORKING_FOLDER/uid_program2"
UID_INPUT_FILE2="$WORKING_FOLDER/zapret_uid0"
UID_INPUT_FILE3="$WORKING_FOLDER/zapret_uid1"
UID_INPUT_FILE4="$WORKING_FOLDER/zapret_uid2"
UID_INPUT_FILE5="$WORKING_FOLDER/zapret_uid3"
UID_INPUT_FILE6="$WORKING_FOLDER/zapret_uid4"
UID_INPUT_FILE7="$WORKING_FOLDER/zapret_uid5"
UID_INPUT_FILE8="$WORKING_FOLDER/zapret_uid_telegram"
UID_INPUT_FILE9="$WORKING_FOLDER/opera_vpn_uid"
UID_INPUT_FILE10="/data/adb/modules/ZDT-D/working_folder/user_program_opera"
UID_INPUT_FILE11="$WORKING_FOLDER/zapret2_uid"
UID_INPUT_FILE12="$WORKING_FOLDER/sing_box_program"
UID_INPUT_FILE_BYE0="$WORKING_FOLDER/bye_dpi0"
UID_INPUT_FILE_BYE1="$WORKING_FOLDER/bye_dpi1"
UID_INPUT_FILE_BYE2="$WORKING_FOLDER/bye_dpi2"
UID_OUTPUT_FILES0="$WORKING_FOLDER/uid_out0"
UID_OUTPUT_FILES1="$WORKING_FOLDER/uid_out1"
UID_OUTPUT_FILES2="$WORKING_FOLDER/uid_out2"
UID_OUTPUT_FILE2="$WORKING_FOLDER/zapret_uid_out0"
UID_OUTPUT_FILE3="$WORKING_FOLDER/zapret_uid_out1"
UID_OUTPUT_FILE4="$WORKING_FOLDER/zapret_uid_out2"
UID_OUTPUT_FILE5="$WORKING_FOLDER/zapret_uid_out3"
UID_OUTPUT_FILE6="$WORKING_FOLDER/zapret_uid_out4"
UID_OUTPUT_FILE7="$WORKING_FOLDER/zapret_uid_out5"
UID_OUTPUT_FILE8="$WORKING_FOLDER/zapret_out_telegram"
UID_OUTPUT_FILE9="$WORKING_FOLDER/opera_vpn_uid_out"
UID_SYSTEM_SERVICES="$WORKING_FOLDER/system_services_uid"
UID_OUTPUT_FILE10="/data/adb/modules/ZDT-D/working_folder/user_program_opera_out"
UID_OUTPUT_FILE11="$WORKING_FOLDER/zapret2_uid_out"
UID_OUTPUT_FILE12="$WORKING_FOLDER/sing_box_program_out"
UID_OUTPUT_FILE_BYE0="$WORKING_FOLDER/bye_dpi_out0"
UID_OUTPUT_FILE_BYE1="$WORKING_FOLDER/bye_dpi_out1"
UID_OUTPUT_FILE_BYE2="$WORKING_FOLDER/bye_dpi_out2"
ZAPRET_CONFIG_FILES_DATA="$WORKING_FOLDER/zapret_config"
ARCH_DIR="$DAM/bin_zapret"
MARKER_FILE="$DAM/arx_marker"
ZAPRET_BIN_FILES_SYSTEM="$BIN_DIR/nfqws"
INPUT_FILE="$WORKING_FOLDER/apps_list.txt"
FULL_INFO_FILE="$WORKING_FOLDER/full_info.txt"
FILE_CHESK_ZAPRET_UID="$WORKING_FOLDER/zapret_uid0"
dpi_list_path="$WORKING_FOLDER"
CONFIG_ZAPRET0="$WORKING_FOLDER/zapret_config0"
CONFIG_ZAPRET1="$WORKING_FOLDER/zapret_config1"
CONFIG_ZAPRET2="$WORKING_FOLDER/zapret_config2"
CONFIG_ZAPRET3="$WORKING_FOLDER/zapret_config3"
CONFIG_ZAPRET4="$WORKING_FOLDER/zapret_config4"
CONFIG_ZAPRET6="$WORKING_FOLDER/zapret_config6"
CONFIG2_ZAPRET="$WORKING_FOLDER/zapret2_config"
SOURCE_FILE_ICON_MB="$DAM/icon.png"
SOURCE_FILE_ICON_MB1="$DAM/icon1.png"
SOURCE_FILE_ICON_MB2="$DAM/icon2.png"
TARGET_DIRECTORY_ICON_MB="$TMP_DIR"
SETTING_START_PARAMS="$WORKING_FOLDER/params"
SETTING_START_PARAMS_PATCH="$WORKING_FOLDER/params_patch"
SETTING_START_PARAMSET="$WORKING_FOLDER/params"
SETTING_START_PARAMSIN="$DAM/params"
SETTING_START_PARAMSTO="/data/adb"
MODULE_PROP="$DAM/module.prop"
UPDATE_JSON_URL="https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/Update/update.json"
TEMP_JSON="$TMP_DIR/update.json"
PASSWORD="/data/adb/php7/files/www/auth"
PASSWORDTO="/data/adb/credentials.php"
SHA256_WORKING_DIR="$WORKING_FOLDER"
SHA256_FLAG_FILE="$SHA256_WORKING_DIR/flag.sha256"
CURL_BINARIES="$DAM/php7/files/bin/curl"
USER_PROGRAM_TMP="/data/local/tmp/user_program.tmp"
USER_PROGRAM_TMP2="/data/local/tmp/user_program.tmp.2"
SINGBOXXED_CONFIG="$WORKING_FOLDER/sing_box_config.json"
# ip адреса
IP_FILE3="$WORKING_FOLDER/ip_ranges3.txt"
IP_FILE4="$WORKING_FOLDER/ip_ranges4.txt"
# Префикс для всех уведомлений
PREFIX="ZDT-D:"
# чтение триггера из system properties
# Важная информация:
# Пожалуйста очистите приложения из списка $UID_INPUT_FILE10
# Оставьте только то что нужно!!!
TRIGGER=$(getprop persist.vendor.zdtd.trigger 2>/dev/null)
EXPECTED="3f2504e0-4f89-11d3-9a0c-0305e82c3301"


####################################
# Ожидание инициализации устройства
####################################
boot_completed() {
    while [ "$(getprop sys.boot_completed | tr -d r)" != "1" ]
    do
        sleep 1
    done
}


############################################
#  Определение локали и выбор языка для логов
############################################

# Пытаемся получить локаль через системное свойство
locale=$(getprop persist.sys.locale)
if [ -z "$locale" ]; then
    config=$(am get-config 2>/dev/null)
    if [ -n "$config" ]; then
        IFS='-' read -r _ _ lang region _ <<EOF
$config
EOF
        region=$(echo "$region" | sed 's/^r//')
        region=$(echo "$region" | tr '[:lower:]' '[:upper:]')
        lang=$(echo "$lang" | tr '[:upper:]' '[:lower:]')
        locale="${lang}-${region}"
    fi
fi

# Если язык начинается с ru – выбираем русский, иначе английский
case "$locale" in
    ru*|ru-*)
        LANGUAGE="ru"
        ;;
    *)
        LANGUAGE="en"
        ;;
esac


############################################
#  Задание текстов сообщений для логов
############################################
if [ "$LANGUAGE" = "ru" ]; then
    MSG_NOTIFICATION_START="Привет, начинаю процедуру настройки модуля. Возможно потребуется до 10 мин для запуска."
    MSG_IPTABLES_REDIRECT="Перенаправляю трафик приложений через модуль..."
    MSG_SUCCESSFUL_LAUNCH="Запуск завершен!"
    MSG_START_PROCESS="начинаю процедуру запуска..."
    MSG_ERROR_MODULE_ZAPRET="Здравствуйте, у вас установлен модуль zapret, удалите его пожалуйста."
    MSG_WHY="Почему?"
    MSG_CONFLICT="Я думаю, модуль может конфликтовать, что приведёт к ошибкам работы..."
    MSG_FORK_MODULE="Это предыдущий форк модуля, он больше не поддерживается..."
    MSG_Z_D_D="Здравствуйте, у вас установлен модуль Zapret DPI Tunnel and Dns Comss, удалите его пожалуйста."
    MSG_RULE_ERROR="Ошибка оболочки: невозможно добавить правило."
    MSG_PROCESSINGFINAL="Обработка файлов завершена."
    MSG_PROCESSING="Обработка:"
    MSG_ERROR="Ошибка:"
    MSG_NOTIFICATION_USER_PROGRAMM="Собираю некоторую информацию вашего устройства."

else

    MSG_ERROR_MODULE_ZAPRET="Hello, you have the zapret module installed, please remove it. It may take up to 10 minutes to start."
    MSG_START_PROCESS="I'm starting the startup procedure..."
    MSG_IPTABLES_REDIRECT="Redirecting application traffic through the module..."
    MSG_NOTIFICATION_START="Hello, I am starting the module setup procedure."
    MSG_SUCCESSFUL_LAUNCH="Launch complete!"
    MSG_WHY="Why?"
    MSG_CONFLICT="I think the module may conflict, which will lead to errors in operation..."
    MSG_FORK_MODULE="This is a previous fork of the module, it is no longer supported..."
    MSG_Z_D_D="Hello, you have the Zapret DPI Tunnel and Dns Comss module installed, please remove it."
    MSG_RULE_ERROR="Shell error: Unable to add rule."
    MSG_PROCESSINGFINAL="File processing complete."
    MSG_PROCESSING="Processing:"
    MSG_ERROR="Error:"
    MSG_NOTIFICATION_USER_PROGRAMM="Collecting some information from your device."

fi


####################################
# Проверка наличия конфликтуюших модулей
####################################
check_modules() {
    local zapret_path="/data/adb/modules/zapret"
    local fork_path="/data/adb/modules/dpi_tunnel_cli"

    if [ -d "$zapret_path" ]; then
        sleep 30
        su -lp 2000 -c "cmd notification post -S messaging --conversation 'Chat' \
            --message 'ZDT-D:$MSG_ERROR_MODULE_ZAPRET ' \
            --message 'System:$MSG_WHY ' \
            --message 'ZDT-D:$MSG_CONFLICT ' \
            -t 'Ошибка' 'Tag' ''" >/dev/null 2>&1
        exit 1
    fi

    if [ -d "$fork_path" ]; then
        sleep 30
        su -lp 2000 -c "cmd notification post -S messaging --conversation 'Chat' \
            --message 'ZDT-D:$MSG_Z_D_D ' \
            --message 'System:$MSG_WHY ' \
            --message 'ZDT-D:$MSG_FORK_MODULE ' \
            -t 'Ошибка' 'Tag' ''" >/dev/null 2>&1
        exit 1
    fi

    echo "Конфликтующие модули не найдены, продолжаем работу..."
}


####################################
# Ожидаем, когда пользователь разблокирует устройство
# Для безопасного запуска модуля
####################################
start_system_final() {

    # Сохраняем исходное значение режима яркости (0 – ручной, 1 – авто)
    original_brightness_mode=$(settings get system screen_brightness_mode)

    ####################################
    # Функция проверки состояния блокировки устройства.
    # Используем dumpsys window policy для определения, показывается ли экран блокировки.
    ####################################
    check_unlock() {
        # Если keyguard (экран блокировки) отображается, считаем устройство заблокированным.
        if dumpsys window policy | grep -q "keyguardShowing=true"; then
            echo "locked"
            return
        fi

        # Дополнительная проверка состояния дисплея через dumpsys power.
        local power_state
        power_state=$(dumpsys power | grep -i "Display Power" | awk '{print $NF}')
        if [ "$power_state" = "OFF" ]; then
            echo "locked"
        else
            echo "unlocked"
        fi
    }

    ####################################
    # Функция проверки активного приложения.
    # Пробуем несколько методов: через dumpsys activity, window и service call.
    ####################################
    check_active_app() {
        local pkg result

        # Метод 1: dumpsys activity (ищем mFocusedActivity)
        result=$(dumpsys activity | grep "mFocusedActivity")
        if [ -n "$result" ]; then
            pkg=$(echo "$result" | sed -n 's/.*u0 \([^/]*\)\/.*/\1/p')
            if [ "$pkg" != "android" ] && [ -n "$pkg" ]; then
                echo "$pkg"
                return 0
            else
                echo "android_error"
                return 0
            fi
        fi

        # Метод 2: dumpsys window (ищем mCurrentFocus)
        result=$(dumpsys window | grep -E "mCurrentFocus")
        if [ -n "$result" ]; then
            pkg=$(echo "$result" | sed -n 's/.*u0 \([^/]*\)\/.*/\1/p')
            if [ "$pkg" != "android" ] && [ -n "$pkg" ]; then
                echo "$pkg"
                return 0
            else
                echo "android_error"
                return 0
            fi
        fi

        # Метод 3: service call activity (работает не на всех версиях)
        result=$(service call activity 42 2>/dev/null)
        if [ -n "$result" ]; then
            echo "unknown"
            return 0
        fi

        echo "none"
        return 1
    }

    ####################################
    # Основной цикл ожидания.
    # 1. Ждём, пока устройство будет разблокировано (2 последовательных состояния).
    # 2. После этого проверяем наличие активного приложения, отличного от системной ошибки.
    ####################################
    unlocked_count=0
    while true; do
        timestamp=$(date '+%Y-%m-%d %H:%M:%S')
        lock_state=$(check_unlock)
        if [ "$lock_state" = "locked" ]; then
            echo "$timestamp: Устройство заблокировано."
            unlocked_count=0
        else
            unlocked_count=$((unlocked_count + 1))
            echo "$timestamp: Устройство разблокировано. Счётчик разблокировок: $unlocked_count"
        fi

        # Если устройство стабильно разблокировано (например, 2 последовательных раза)
        if [ "$unlocked_count" -ge 2 ]; then
            app=$(check_active_app)
            if [ "$app" = "none" ] || [ "$app" = "android_error" ]; then
                echo "$timestamp: Активное приложение не определено или обнаружена ошибка (android)."
                unlocked_count=0
            else
                echo "$timestamp: Найдено активное приложение: $app"
                echo "$timestamp: Информация о приложении (versionName, versionCode):"
                dumpsys package "$app" | grep -E "versionName|versionCode"
                break
            fi
        fi

        sleep 3
    done

    # Восстанавливаем исходное значение режима яркости, если оно изменилось.
    settings put system screen_brightness_mode "$original_brightness_mode"
}


safestart_module() {
    # если переменная safestart установлена в "1" — делаем паузу 180 секунд
    if [ "${safestart:-0}" = "1" ]; then
        sleep 180
    fi
}


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

sanitize_sni_for_filename() {
    # $1 = оригинальный SNI
    # возвращает безопасную строку: заменяет все недопустимые символы на '_', ограничивает длину
    sni="$1"
    # Заменяем всё, что не буква/цифра/точка/подчёркивание/дефис на _
    safe=$(printf '%s' "$sni" | sed -E 's/[^A-Za-z0-9._-]/_/g')
    # Обрезаем до 64 символов, чтобы избежать слишком длинных имён файлов
    safe=$(printf '%s' "$safe" | cut -c1-64)
    printf '%s' "$safe"
}


notification_send() {
    # 1) Проверяем, включены ли уведомления
    if [ "$notification" != "1" ]; then
        echo "[$(date '+%T')] Уведомления отключены (notification=$notification)" >&2
        return 1
    fi

    # 2) Тип уведомления — первый аргумент
    local type="$1"; shift

    # 3) Константы для cmd notification
    local ICON1="file:///data/local/tmp/icon1.png"
    local ICON2="file:///data/local/tmp/icon.png"
    local STYLE="messaging"
    local TITLE="CLI"

    # 4) Формируем тело (BODY) и тег (TAG)
    local BODY TAG
    case "$type" in
        processing)
            local file_path="$1"; shift
            local percent="$1";    shift
            BODY="$MSG_PROCESSING $(basename "$file_path") — ${percent}%"
            TAG="processing"
            ;;
        processingfinal)
            local msg="$1"; shift
            BODY="$msg"
            TAG="processing"
            ;;
        error)
            local err_msg="$1"; shift
            BODY="$err_msg"
            TAG="error"
            ;;
        info)
            local msg="$1"; shift
            BODY="$msg"
            TAG="info"
            ;;
        support)
            local msg="$1"; shift
            BODY="$msg"
            TAG="support"
            ;;
        *)
            echo "[$(date '+%T')] Неверный тип уведомления: '$type'" >&2
            return 2
            ;;
    esac

    # 5) Добавляем префикс ZDT-D:
    BODY="${PREFIX}${BODY}"

    # 6) Отправляем уведомление (flags… <tag> <text>)
    su -lp 2000 -c \
       "cmd notification post \
         -i $ICON1 -I $ICON2 \
         -S $STYLE \
         --conversation 'System' \
         --message '$BODY' \
         -t '$TITLE' \
         '$TAG' '$BODY'"

    # 7) Логируем результат
    if [ $? -eq 0 ]; then
        echo "[$(date '+%T')] Уведомление [$type] отправлено: $BODY"
    else
        echo "[$(date '+%T')] Не удалось отправить уведомление [$type]" >&2
    fi
}


python3_update_bin() {

  PYINSTBI_ICON_PATH="file:///data/local/tmp/icon4.png"
  PYINSTBI_NOTIFY_UID=2000

  # Список пакетов (редактируй при необходимости)
  PYINSTBI_PACKAGES="scapy pycryptodome requests paramiko dnspython pyOpenSSL netifaces pysocks python-socks aiohttp-socks 'httpx[http2]' h2 aioquic mitmproxy psutil prometheus_client PyYAML orjson uvloop aiohttp cryptography cffi certifi idna charset-normalizer multidict yarl anyio hpack hyperframe h11 brotli pyroute2 netaddr python-dotenv loguru pyinstaller typing-extensions packaging"

  PYINSTBI_timestamp() {
    date +"%Y-%m-%d %H:%M:%S" 2>/dev/null || printf "%s" "$(date)"
  }

  PYINSTBI_log() {
    # Печать в консоль с временной меткой
    printf "%s %s\n" "$(PYINSTBI_timestamp)" "$*"
  }

  # Проверка интернета (ping -> python socket)
  PYINSTBI_check_internet() {
    if command -v ping >/dev/null 2>&1; then
      if ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1; then
        return 0
      fi
    fi

    # Fallback через python socket (выполняется под su чтобы использовать системный python)
    su -c "python3 - <<PY
import socket,sys
try:
    s=socket.create_connection(('1.1.1.1',53),2)
    s.close()
    print('ok')
except Exception:
    sys.exit(1)
PY" >/dev/null 2>&1 && return 0

    return 1
  }

  # Проверка — установлен ли пакет (pip show или import)
  PYINSTBI_is_installed() {
    PYINSTBI_PKG_RAW="$1"
    PYINSTBI_PKG_BASE="${PYINSTBI_PKG_RAW%%[*]}"

    # 1) pip show
    if su -c "python3 -m pip show '${PYINSTBI_PKG_BASE}'" >/dev/null 2>&1; then
      return 0
    fi

    # 2) попытка импортировать вероятные имена
    su -c "python3 - <<PY
import importlib,sys
base='${PYINSTBI_PKG_BASE}'
candidates = [base, base.replace('-','_'), base.replace('-','')]
aliases = {
  'pysocks': ['socks','pysocks'],
  'python-socks': ['socks','python_socks'],
  'pycryptodome': ['Crypto','Cryptodome'],
}
if base in aliases:
    candidates += aliases[base]
candidates += ['socks','Crypto','cryptodome','httpx','h2','aioquic','mitmproxy','scapy']
for name in candidates:
    try:
        importlib.import_module(name)
        sys.exit(0)
    except Exception:
        pass
sys.exit(1)
PY" >/dev/null 2>&1
    return $?
  }

  # --- Начало процедуры ---
  PYINSTBI_log "=== NEW INSTALL SESSION ==="
  notification_send info "Запуск процедуры установки важных библиотек (Py2Droid)."

  # Проверяем наличие python3
  if su -c python3 -V >/dev/null 2>&1; then
    PYINSTBI_PYVER="$(su -c python3 -V 2>/dev/null)"
    PYINSTBI_log "Found python: ${PYINSTBI_PYVER}"
  else
    PYINSTBI_log "ERROR: python3 not found (py2droid missing). Aborting."
    notification_send info "python3 не найден (Py2Droid отсутствует). Прервано."
    return 1
  fi

  # Проверка интернета — если нет, уведомляем и ждём
  PYINSTBI_log "Checking internet connectivity..."
  if PYINSTBI_check_internet; then
    PYINSTBI_log "Internet OK, proceeding."
  else
    PYINSTBI_log "No internet — notifying and waiting..."
    notification_send info "Py2Droid: Ожидание подключения к интернету..."
    PYINSTBI_LAST_NET_NOTIFY=0
    while true; do
      sleep 5
      PYINSTBI_log "Rechecking network..."
      if PYINSTBI_check_internet; then
        PYINSTBI_log "Network restored."
        notification_send info "Py2Droid: Интернет доступен — продолжаю установку"
        break
      fi
      NOW_SEC=$(date +%s 2>/dev/null || echo 0)
      DIFF=$((NOW_SEC - PYINSTBI_LAST_NET_NOTIFY))
      if [ "$DIFF" -ge 60 ]; then
        notification_send info "Py2Droid: Всё ещё ожидаю подключения к интернету..."
        PYINSTBI_LAST_NET_NOTIFY="$NOW_SEC"
      fi
    done
  fi

  # Проверка pip / ensurepip
  if su -c "python3 -m pip --version" >/dev/null 2>&1; then
    PYINSTBI_log "pip present."
  else
    PYINSTBI_log "pip not found — attempting ensurepip..."
    notification_send info "Py2Droid: пытаюсь установить pip..."
    if su -c "python3 -m ensurepip --upgrade" >/dev/null 2>&1; then
      PYINSTBI_log "ensurepip OK"
      notification_send info "pip установлен."
    else
      PYINSTBI_log "ensurepip failed — continuing (pip may be absent)"
      notification_send info "Не удалось установить pip автоматически (продолжаю)."
    fi
  fi

  # Обновление pip/setuptools/wheel — выводим прогресс в консоль
  PYINSTBI_log "Updating pip, setuptools, wheel..."
  if su -c "python3 -m pip install --upgrade pip setuptools wheel"; then
    PYINSTBI_log "pip/setuptools/wheel updated."
  else
    PYINSTBI_log "Warning: pip upgrade failed."
  fi

  # Подсчёт общего числа пакетов
  PYINSTBI_TOTAL=0
  for _ in $PYINSTBI_PACKAGES; do PYINSTBI_TOTAL=$((PYINSTBI_TOTAL+1)); done
  PYINSTBI_i=0

  for PYINSTBI_PKG in $PYINSTBI_PACKAGES; do
    PYINSTBI_i=$((PYINSTBI_i+1))
    PYINSTBI_PCT=$((PYINSTBI_i * 100 / PYINSTBI_TOTAL))

    # Проверяем, установлен ли пакет
    if PYINSTBI_is_installed "$PYINSTBI_PKG"; then
      PYINSTBI_log "Package ${PYINSTBI_PKG} already installed."
      notification_send info "Библиотека ${PYINSTBI_PKG} уже установлена (${PYINSTBI_PCT}%)."
      continue
    fi

    # Уведомление о старте установки
    notification_send info "Py2Droid: Устанавливаю ${PYINSTBI_PKG} (${PYINSTBI_PCT}%)"
    PYINSTBI_log "Installing ${PYINSTBI_PKG} (${PYINSTBI_i}/${PYINSTBI_TOTAL})"

    # Основная попытка (предпочитаем бинарные колёса) — вывод pip в консоль
    su -c "python3 -m pip install --no-cache-dir --upgrade --prefer-binary ${PYINSTBI_PKG}"
    PYINSTBI_RC=$?
    if [ "$PYINSTBI_RC" -eq 0 ]; then
      PYINSTBI_log "Installed ${PYINSTBI_PKG} OK (prefer-binary)"
      notification_send info "Установлено ${PYINSTBI_PKG} (${PYINSTBI_PCT}%)"
      continue
    fi

    # fallback: обычная установка (может компилировать из исходников)
    PYINSTBI_log "First attempt failed for ${PYINSTBI_PKG} (rc=${PYINSTBI_RC}). Trying fallback..."
    su -c "python3 -m pip install --no-cache-dir --upgrade ${PYINSTBI_PKG}"
    PYINSTBI_RC2=$?
    if [ "$PYINSTBI_RC2" -eq 0 ]; then
      PYINSTBI_log "Installed ${PYINSTBI_PKG} OK (fallback)"
      notification_send info "Установлено ${PYINSTBI_PKG} (${PYINSTBI_PCT}%)"
      continue
    fi

    # Если не удалось
    PYINSTBI_log "FAILED to install ${PYINSTBI_PKG} (rc1=${PYINSTBI_RC}, rc2=${PYINSTBI_RC2})."
    notification_send info "Ошибка установки ${PYINSTBI_PKG}. См. вывод консоли для подробностей."
    PYINSTBI_log "NOTE: ${PYINSTBI_PKG} may require native toolchain (C/Rust/NDK) or prebuilt wheels for Android."
  done

  # Попытка обновить бин-оболочки py2droid если есть утилита
  if su -c "command -v py2droid-update-bin" >/dev/null 2>&1; then
    PYINSTBI_log "Running py2droid-update-bin..."
    su -c "py2droid-update-bin"
    if [ $? -ne 0 ]; then
      PYINSTBI_log "py2droid-update-bin returned non-zero"
    fi
  else
    PYINSTBI_log "py2droid-update-bin not found — skipping."
  fi

  notification_send info "Установка библиотек завершена."
  PYINSTBI_log "=== INSTALL SESSION COMPLETE ==="
  return 0
}


USER_PROGRAM_LIST() {
  # Очистим/создадим временные файлы
  : > "$USER_PROGRAM_TMP" 2>/dev/null || true
  : > "$USER_PROGRAM_TMP2" 2>/dev/null || true

  # Попытаться получить список третьесторонних пакетов с путями
  pm list packages -3 -f > "$USER_PROGRAM_TMP" 2>/dev/null

  # Если пустой — взять все пакеты и отфильтровать системные по путям
  if [ ! -s "$USER_PROGRAM_TMP" ]; then
    pm list packages -f 2>/dev/null | grep -v '/system/' | grep -v '/apex/' | grep -v '/vendor/' > "$USER_PROGRAM_TMP" 2>/dev/null || true
  fi

  # Если всё ещё пусто — получить список пакетов только именами и выяснить пути отдельно
  if [ ! -s "$USER_PROGRAM_TMP" ]; then
    pm list packages -3 2>/dev/null | sed 's/^package://' > "$USER_PROGRAM_TMP" 2>/dev/null || true
    if [ -s "$USER_PROGRAM_TMP" ]; then
      # Для каждого пакета получить путь apk (pm path) и записать в формат package:APK=PACKAGE
      while IFS= read -r USER_PROGRAM_PKGNAME; do
        [ -z "$USER_PROGRAM_PKGNAME" ] && continue
        USER_PROGRAM_APKPATH="$(pm path "$USER_PROGRAM_PKGNAME" 2>/dev/null | sed 's/^package://g' | head -n1)"
        if [ -n "$USER_PROGRAM_APKPATH" ]; then
          printf "package:%s=%s\n" "$USER_PROGRAM_APKPATH" "$USER_PROGRAM_PKGNAME" >> "$USER_PROGRAM_TMP2"
        else
          printf "package:%s=%s\n" "" "$USER_PROGRAM_PKGNAME" >> "$USER_PROGRAM_TMP2"
        fi
      done < "$USER_PROGRAM_TMP"
      # Подменим tmp на tmp2 (у нас теперь унифицированный формат)
      mv "$USER_PROGRAM_TMP2" "$USER_PROGRAM_TMP" 2>/dev/null || true
    fi
  fi

  # Ещё запасной вариант — сканировать /data/app (требует root)
  if [ ! -s "$USER_PROGRAM_TMP" ] && [ -d /data/app ]; then
    for USER_PROGRAM_DIR in /data/app/*; do
      [ -d "$USER_PROGRAM_DIR" ] || continue
      USER_PROGRAM_APK="$(find "$USER_PROGRAM_DIR" -maxdepth 2 -type f -name '*.apk' -print -quit 2>/dev/null)"
      if [ -n "$USER_PROGRAM_APK" ]; then
        # Попробуем найти соответствующую строку в 'pm list packages -f', иначе добавим без имени пакета
        USER_PROGRAM_LINE="$(pm list packages -f 2>/dev/null | grep -F "$USER_PROGRAM_APK" | head -n1)"
        if [ -n "$USER_PROGRAM_LINE" ]; then
          printf "%s\n" "$USER_PROGRAM_LINE" >> "$USER_PROGRAM_TMP"
        else
          printf "package:%s=\n" "$USER_PROGRAM_APK" >> "$USER_PROGRAM_TMP"
        fi
      fi
    done
  fi

  # Если всё ещё пусто — выдать ошибку
  if [ ! -s "$USER_PROGRAM_TMP" ]; then
    printf "Ничего не найдено: pm вернул пустой результат и /data/app недоступен.\n" >&2
    rm -f "$USER_PROGRAM_TMP" "$USER_PROGRAM_TMP2" 2>/dev/null || true
    return 1
  fi

  # Подготовим файл для записи имён пакетов (перезапишем)
  : > "$UID_INPUT_FILE10" 2>/dev/null || {
    printf "ERROR: не могу записать в %s\n" "$UID_INPUT_FILE10" >&2
    rm -f "$USER_PROGRAM_TMP" "$USER_PROGRAM_TMP2" 2>/dev/null || true
    return 1
  }

  # Печать заголовка в stdout (по желанию)
  printf "PACKAGE\tAPK_PATH\tVERSION\tSIZE_BYTES\tMODIFIED\n"

  # Обработка записей построчно
  while IFS= read -r USER_PROGRAM_LINE || [ -n "$USER_PROGRAM_LINE" ]; do
    [ -z "$USER_PROGRAM_LINE" ] && continue
    # Ожидаемый формат: package:/path/to/base.apk=com.example.app
    # Извлечь apkpath и pkgname
    USER_PROGRAM_APKPATH="$(printf "%s\n" "$USER_PROGRAM_LINE" | sed -n 's/^package:\([^=]*\)=.*$/\1/p')"
    USER_PROGRAM_PKGNAME="$(printf "%s\n" "$USER_PROGRAM_LINE" | sed -n 's/^.*=\(.*\)$/\1/p')"

    # Если pkgname пуст, оставим пустую строку (в таком случае будет сохранено пустое имя — пропускаем)
    if [ -z "$USER_PROGRAM_PKGNAME" ]; then
      # Если имя пакета отсутствует — попробуем попытаться получить его через pm path (если apkpath задан)
      if [ -n "$USER_PROGRAM_APKPATH" ]; then
        # pm list packages -f | grep "<apkpath>" может вернуть строку с именем
        USER_PROGRAM_POSSIBLE="$(pm list packages -f 2>/dev/null | grep -F "$USER_PROGRAM_APKPATH" | sed 's/^package:.*=\(.*\)$/\1/' | head -n1)"
        if [ -n "$USER_PROGRAM_POSSIBLE" ]; then
          USER_PROGRAM_PKGNAME="$USER_PROGRAM_POSSIBLE"
        fi
      fi
    fi

    # Версия (versionName) — через dumpsys package (может быть медленно)
    USER_PROGRAM_VERSION=""
    if [ -n "$USER_PROGRAM_PKGNAME" ]; then
      USER_PROGRAM_VERSION="$(dumpsys package "$USER_PROGRAM_PKGNAME" 2>/dev/null | awk -F= '/versionName=/{print $2; exit}')"
    fi

    # Если apkpath пуст, попробовать pm path
    if [ -z "$USER_PROGRAM_APKPATH" ] && [ -n "$USER_PROGRAM_PKGNAME" ]; then
      USER_PROGRAM_APKPATH="$(pm path "$USER_PROGRAM_PKGNAME" 2>/dev/null | sed 's/^package://g' | head -n1)"
    fi

    USER_PROGRAM_SIZE=""
    USER_PROGRAM_MODIFIED=""
    if [ -n "$USER_PROGRAM_APKPATH" ] && [ -f "$USER_PROGRAM_APKPATH" ]; then
      USER_PROGRAM_STATLINE="$(ls -l "$USER_PROGRAM_APKPATH" 2>/dev/null)"
      USER_PROGRAM_SIZE="$(printf "%s\n" "$USER_PROGRAM_STATLINE" | awk '{print $5}')"
      USER_PROGRAM_MODIFIED="$(printf "%s\n" "$USER_PROGRAM_STATLINE" | awk '{print $6" "$7" "$8}')"
    fi

    # Вывести строку в stdout (таб-разделитель)
    printf "%s\t%s\t%s\t%s\t%s\n" "$USER_PROGRAM_PKGNAME" "$USER_PROGRAM_APKPATH" "$USER_PROGRAM_VERSION" "$USER_PROGRAM_SIZE" "$USER_PROGRAM_MODIFIED"

    # Сохранить имя пакета в файл (если не пустое)
    if [ -n "$USER_PROGRAM_PKGNAME" ]; then
      printf "%s\n" "$USER_PROGRAM_PKGNAME" >> "$UID_INPUT_FILE10"
    fi
  done < "$USER_PROGRAM_TMP"

  # Очистка временных файлов
  rm -f "$USER_PROGRAM_TMP" "$USER_PROGRAM_TMP2" 2>/dev/null || true

  # Завершение
  return 0
}


####################################
# Отпраляем тост о начале запуска модуля
####################################
notification_toast_start() {
    /system/bin/am start -a android.intent.action.MAIN -e toasttext "ZDT-D $MSG_START_PROCESS " -n bellavita.toast/.MainActivity
}


####################################
# Загрузаем переменные из файла params
####################################
read_params() {

    # Чтение файла "params" и присваивание значений переменным
    local PARAMS_SETTING_SYSTEM="$1"
    while IFS='=' read -r key value || [ -n "$key" ]; do
        # Убираем пробелы с обеих сторон
        key=$(echo "$key" | xargs)
        value=$(echo "$value" | xargs)

        # Присваиваем значения переменным
        case "$key" in
            "dns") dns="$value" ;;
            "full_system") full_system="$value" ;;
            "dpi_tunnel_0") dpi_tunnel_0="$value" ;;
            "dpi_tunnel_1") dpi_tunnel_1="$value" ;;
            "dpi_tunnel_2") dpi_tunnel_2="$value" ;;
            "port_preference") port_preference="$value" ;;
            "offonservice") offonservice="$value" ;;
            "notification") notification="$value" ;;
            "alternativel") alternativel="$value" ;;
            "zapretconfig") zapretconfig="$value" ;;
            "safestart") safestart="$value" ;;
            "batterysaver") batterysaver="$value" ;;
            "bypass_opera_uid") bypass_opera_uid="$value" ;;
            "sysctl_patch") sysctl_patch="$value" ;;
            "captive_portal") captive_portal="$value" ;;
            "tether_fix") tether_fix="$value" ;;
            "tether_whitelist") tether_whitelist="$value" ;;
        esac
    done < "$PARAMS_SETTING_SYSTEM"

}


sysct_optimization() {

    # ------------------------ sysctl настройки -------------------------------
    sysctl -w net.core.bpf_jit_enable=1 2>/dev/null || true
    sysctl -w net.core.bpf_jit_harden=0 2>/dev/null || true
    sysctl -w net.core.bpf_jit_kallsyms=1 2>/dev/null || true
    sysctl -w net.core.bpf_jit_limit=33554432 2>/dev/null || true
    
    sysctl -w net.core.busy_poll=0 2>/dev/null || true
    sysctl -w net.core.busy_read=0 2>/dev/null || true
    sysctl -w net.core.default_qdisc=pfifo_fast 2>/dev/null || true
    sysctl -w net.core.dev_weight=64 2>/dev/null || true
    sysctl -w net.core.dev_weight_rx_bias=1 2>/dev/null || true
    sysctl -w net.core.dev_weight_tx_bias=1 2>/dev/null || true
    sysctl -w net.core.flow_limit_cpu_bitmap=00 2>/dev/null || true
    sysctl -w net.core.flow_limit_table_len=4096 2>/dev/null || true
    sysctl -w net.core.max_skb_frags=17 2>/dev/null || true
    sysctl -w net.core.message_burst=10 2>/dev/null || true
    sysctl -w net.core.message_cost=5 2>/dev/null || true
    sysctl -w net.core.netdev_max_backlog=28000000 2>/dev/null || true
    sysctl -w net.core.netdev_budget=1000 2>/dev/null || true
    sysctl -w net.core.netdev_budget_usecs=16000 2>/dev/null || true
    sysctl -w net.core.optmem_max=65536 2>/dev/null || true
    sysctl -w net.core.rmem_default=229376 2>/dev/null || true
    sysctl -w net.core.rmem_max=67108864 2>/dev/null || true
    sysctl -w net.core.wmem_default=229376 2>/dev/null || true
    sysctl -w net.core.wmem_max=67108864 2>/dev/null || true
    sysctl -w net.core.somaxconn=1024 2>/dev/null || true
    sysctl -w net.core.tstamp_allow_data=1 2>/dev/null || true
    
    sysctl -w net.core.xfrm_acq_expires=3600 2>/dev/null || true
    sysctl -w net.core.xfrm_aevent_etime=10 2>/dev/null || true
    sysctl -w net.core.xfrm_aevent_rseqth=2 2>/dev/null || true
    sysctl -w net.core.xfrm_larval_drop=1 2>/dev/null || true
    
    sysctl -w net.ipv4.ip_forward=1 2>/dev/null || true
    sysctl net.ipv4.conf.all.route_localnet=1 2>/dev/null || true
    sysctl -w net.ipv4.tcp_congestion_control=cubic 2>/dev/null || true
    sysctl -w net.ipv4.tcp_mtu_probing=1 2>/dev/null || true
    sysctl -w net.ipv4.ip_local_port_range="10240 65535" 2>/dev/null || true
    sysctl -w net.ipv4.tcp_fin_timeout=30 2>/dev/null || true
    sysctl -w net.ipv4.conf.all.accept_redirects=0 2>/dev/null || true
    sysctl -w net.ipv4.conf.default.accept_redirects=0 2>/dev/null || true
    sysctl -w net.ipv4.conf.all.send_redirects=0 2>/dev/null || true
    sysctl -w net.ipv4.conf.all.accept_source_route=0 2>/dev/null || true
    sysctl -w net.ipv4.conf.all.rp_filter=2 2>/dev/null || true
    sysctl -w net.core.rps_sock_flow_entries=32768 2>/dev/null || true
    sysctl -w net.ipv4.udp_mem="40960 65536 131072" 2>/dev/null || true
    sysctl -w net.ipv4.udp_rmem_min=8192 2>/dev/null || true
    sysctl -w net.ipv4.icmp_echo_ignore_broadcasts=1 2>/dev/null || true
    sysctl -w net.ipv4.icmp_ratelimit=100 2>/dev/null || true
    sysctl -w net.ipv4.tcp_ecn=1 2>/dev/null || true
    sysctl net.netfilter.nf_conntrack_tcp_be_liberal=1 2>/dev/null || true
    
    # sysctl -w net.core.rmem_max=134217728 2>/dev/null || true
    # sysctl -w net.core.wmem_max=134217728 2>/dev/null || true
    # sysctl -w net.ipv4.tcp_rmem="4096 87380 67108864" 2>/dev/null || true
    # sysctl -w net.ipv4.tcp_wmem="4096 65536 67108864" 2>/dev/null || true
    # sysctl -w net.ipv4.tcp_mem="786432 1048576 1572864" 2>/dev/null || true
    # sysctl -w net.netfilter.nf_conntrack_max=262144 2>/dev/null || true
    # sysctl -w net.netfilter.nf_conntrack_tcp_timeout_established=300 2>/dev/null || true
    # sysctl -w fs.file-max=2000000 2>/dev/null || true
    # sysctl -w net.ipv4.tcp_fastopen=3 2>/dev/null || true
    
    # sysctl -w net.core.default_qdisc=fq 2>/dev/null || true
    # # или, если хотите fq_codel:
    # #sysctl -w net.core.default_qdisc=fq_codel 2>/dev/null || true
    
    # sysctl -w net.ipv4.tcp_max_orphans=262144 2>/dev/null || true
    # sysctl -w net.ipv4.tcp_tw_reuse=1 2>/dev/null || true
    # sysctl -w net.ipv6.conf.all.disable_ipv6=1 2>/dev/null || true
    # sysctl -w net.ipv6.conf.default.disable_ipv6=1 2>/dev/null || true
    # sysctl -w net.ipv4.tcp_sack=1 2>/dev/null || true   # SACK полезен
    # sysctl -w net.ipv4.tcp_autocorking=1 2>/dev/null || true  # если доступно
    
    
    # Таймауты и ретраи
    sysctl -w net.ipv4.tcp_keepalive_time=600
    sysctl -w net.ipv4.tcp_keepalive_intvl=60
    sysctl -w net.ipv4.tcp_keepalive_probes=3
    sysctl -w net.ipv4.tcp_retries2=5
    sysctl -w net.ipv4.tcp_retries1=3
    
    # Управление соединениями
    sysctl -w net.ipv4.tcp_max_syn_backlog=65536
    sysctl -w net.ipv4.tcp_syncookies=1
    sysctl -w net.ipv4.tcp_max_tw_buckets=2000000
    sysctl -w net.ipv4.tcp_tw_reuse=1
    sysctl -w net.ipv4.tcp_fin_timeout=20
    sysctl -w net.ipv4.tcp_slow_start_after_idle=0
    
    # Защита от спуфинга
    sysctl -w net.ipv4.conf.all.log_martians=1
    sysctl -w net.ipv4.conf.default.log_martians=1
    
    # ICMP защита
    sysctl -w net.ipv4.icmp_ignore_bogus_error_responses=1
    sysctl -w net.ipv4.icmp_ratemask=88089
    
    # TCP Hardening
    sysctl -w net.ipv4.tcp_dsack=0
    sysctl -w net.ipv4.tcp_fack=0
    
    # Размеры очередей
    sysctl -w net.ipv4.neigh.default.gc_thresh1=1024
    sysctl -w net.ipv4.neigh.default.gc_thresh2=2048
    sysctl -w net.ipv4.neigh.default.gc_thresh3=4096
    
    # Оптимизация прерываний
    sysctl -w net.core.netdev_budget=600
    sysctl -w net.core.netdev_tstamp=0
    
    # Разрешение маршрутизации трафика 
    sysctl -w net.ipv4.conf.all.route_localnet=1
    sysctl -w net.ipv4.conf.default.route_localnet=1
}


####################################
# В зависимости от выкл или вкл ползунка сервиса, запускается модуль
####################################
start_stop_service() {

    # Если значение offonservice установлено, проверяем его
    if [ "$offonservice" = "1" ]; then

        echo "Модуль включен"

    elif [ "$offonservice" = "0" ]; then

        /system/bin/am start -a android.intent.action.MAIN -e toasttext "ZDT-D выключен" -n bellavita.toast/.MainActivity
        echo "модуль выключен"
        exit 0
    else
        echo "Некорректное значение для offonservice или его нет"
    fi
}


####################################
# Запуск сервиса zapret с профилями и конфигурацией
####################################
start_zapret() {
    # Проверка количества параметров
    if [ "$#" -ne 3 ]; then
        echo "Использование: start_zapret <qnum> <config_file> <uid_file>"
        return 1
    fi

    local qnum="$1"
    local config="$2"
    local uid_file="$3"

    # Выполнить действия, которые должны происходить один раз
    if [ -z "$_ZAPRET_INIT" ]; then
        # (Опционально) проверка iptables, выполняется один раз
        if iptables -t mangle -A POSTROUTING -p tcp -m connbytes \
            --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12 -j ACCEPT 2>/dev/null; then
            iptables -t mangle -D POSTROUTING -p tcp -m connbytes \
                --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12 -j ACCEPT 2>/dev/null
        fi

        # Либеральный режим TCP – выполняется один раз
        sysctl net.netfilter.nf_conntrack_tcp_be_liberal=1 > /dev/null

        # Флаг инициализации, чтобы повторно не выполнять блок выше
        _ZAPRET_INIT=1
    fi

    # Проверка файла конфигурации (фактически uid_file)
    if [ -s "$uid_file" ]; then
        nfqws --uid=0:0 --qnum="$qnum" $(cat "$config") > /dev/null 2>&1 &
    else
        echo "Профиль с qnum $qnum не запущен, файл конфигурации пустой: $uid_file"
    fi
}


start_zapret_agressive(){

BASE=/data/adb/modules/ZDT-D/working_folder/bin
PORTS=(20 21 22 23 25 53 67 68 69 80 110 119 123 135 137 138 139 143 161 162 389 443 3306 5222 7000 7500 7999 5432 5900 6379 6667 8080 8443 8888 1194 500 4500 3478 5060 5061 6881 6883 6940 6970 6999 43 79 445 465 514 587 993 995 1521 1701 1723 1935 3268 3269 9200 11211 25565 27015 27017 596 1400 35501 34503 35000-54000 )

# Начальные опции
args=(--uid=0:0 --qnum=205)

for p in "${PORTS[@]}"; do
  ##############################################################################
  # 1) Исходные динамические блоки (TCP multidisorder, UDP fake+multidisorder, TCP fake)
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--dpi-desync=multidisorder)
  args+=(--dpi-desync-split-pos=1,midsld)
  args+=(--dpi-desync-repeats=8)
  args+=(--orig-autottl=+1)
  args+=(--dup-autottl=-1)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_2.bin)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_vk_com_kyber.bin)
  args+=(--dpi-desync-fakedsplit-mod=altorder=1)
  args+=(--dpi-desync-hostfakesplit-mod=altorder=1)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--dpi-desync=hostfakesplit)
  args+=(--dpi-desync-split-pos=1,midsld)
  args+=(--dpi-desync-repeats=8)
  args+=(--orig-autottl=+1)
  args+=(--dup-autottl=-1)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_2.bin)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_vk_com_kyber.bin)
  args+=(--dpi-desync-fakedsplit-mod=altorder=1)
  args+=(--dpi-desync-hostfakesplit-mod=altorder=1)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-split-pos=1,midsld)
  args+=(--dpi-desync-repeats=8)
  args+=(--orig-autottl=+1)
  args+=(--dup-autottl=-1)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_2.bin)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_vk_com_kyber.bin)
  args+=(--dpi-desync-fakedsplit-mod=altorder=1)
  args+=(--dpi-desync-hostfakesplit-mod=altorder=1)
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-repeats=8)
  args+=(--dpi-desync-fake-quic=${BASE}/quic_initial_facebook_com_quiche.bin)
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--dpi-desync=multidisorder)
  args+=(--dpi-desync-repeats=8)
  args+=(--dpi-desync-fake-quic=${BASE}/quic_initial_facebook_com_quiche.bin)
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--dpi-desync=hostfakesplit)
  args+=(--dpi-desync-repeats=8)
  args+=(--dpi-desync-fake-quic=${BASE}/quic_initial_facebook_com_quiche.bin)
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-repeats=8)
  args+=(--dpi-desync-fake-quic=${BASE}/quic_initial_facebook_com_quiche.bin)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-repeats=6)
  args+=(--orig-autottl=+1)
  args+=(--dup-autottl=-1)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--dpi-desync=hostfakesplit)
  args+=(--dpi-desync-repeats=6)
  args+=(--orig-autottl=+1)
  args+=(--dup-autottl=-1)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-repeats=6)
  args+=(--orig-autottl=+1)
  args+=(--dup-autottl=-1)
  args+=(--new)

  ##############################################################################
  # 2) netrogat.txt по TCP
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/blocked.txt)
  args+=(--new)

  ##############################################################################
  # 3) netrogat.txt по TCP (дублируем, как было)
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/blocked.txt)
  args+=(--new)

  # --- Cloudflare: fake TLS + fake QUIC (умеренные повторы)
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/cloudflare.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_http_cloudflare.bin)
  args+=(--dpi-desync-repeats=6)
  args+=(--dpi-desync-autottl)
  args+=(--dpi-desync-skip-nosni=1)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/cloudflare.txt)
  args+=(--dpi-desync=hostfakesplit)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_http_cloudflare.bin)
  args+=(--dpi-desync-repeats=6)
  args+=(--dpi-desync-autottl)
  args+=(--dpi-desync-skip-nosni=1)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/cloudflare.txt)
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_http_cloudflare.bin)
  args+=(--dpi-desync-repeats=6)
  args+=(--dpi-desync-autottl)
  args+=(--dpi-desync-skip-nosni=1)
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--hostlist=${BASE}/cloudflare.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-fake-quic=${BASE}/tls_http_cloudflare_ru.bin)
  args+=(--dpi-desync-repeats=6)
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--hostlist=${BASE}/cloudflare.txt)
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-fake-quic=${BASE}/tls_http_cloudflare_ru.bin)
  args+=(--dpi-desync-repeats=6)
  args+=(--new)

  # --- Telegram: смешанный fake TLS и fake-unknown (MTProto-like)
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/telegram.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_http_telegram.bin)
  args+=(--dpi-desync-repeats=8)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/telegram.txt)
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_http_telegram.bin)
  args+=(--dpi-desync-repeats=8)
  args+=(--dpi-desync-autottl)
  args+=(--new)

    # UDP вариант (если Telegram использует UDP на данном порту)
  args+=(--filter-udp=${p})
  args+=(--hostlist=${BASE}/telegram.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-fake-unknown=${BASE}/tls_http_telegram_ru.bin)
  args+=(--dpi-desync-repeats=6)
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--hostlist=${BASE}/telegram.txt)
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-fake-unknown=${BASE}/tls_http_telegram_ru.bin)
  args+=(--dpi-desync-repeats=6)
  args+=(--new)

  ##############################################################################
  # 4) russia-youtubeQ.txt по UDP (fake QUIC, repeats=2, cutoff=n2)
  ##############################################################################
  args+=(--filter-udp=${p})
  args+=(--hostlist=${BASE}/russia-youtubeQ.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-repeats=2)
  args+=(--dpi-desync-cutoff=n2)
  args+=(--dpi-desync-fake-quic=${BASE}/quic_pl_by_ori.bin)
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--hostlist=${BASE}/russia-youtubeQ.txt)
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-repeats=2)
  args+=(--dpi-desync-cutoff=n2)
  args+=(--dpi-desync-fake-quic=${BASE}/quic_pl_by_ori.bin)
  args+=(--new)

  ##############################################################################
  # 5) russia-youtubeGV.txt по TCP (split, badseq, repeats=10, autottl)
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-youtubeGV.txt)
  args+=(--dpi-desync=split)
  args+=(--dpi-desync-split-pos=1)
  args+=(--dpi-desync-fooling=badseq)
  args+=(--dpi-desync-repeats=10)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-youtubeGV.txt)
  args+=(--dpi-desync=hostfakesplit)
  args+=(--dpi-desync-split-pos=1)
  args+=(--dpi-desync-fooling=badseq)
  args+=(--dpi-desync-repeats=10)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  ##############################################################################
  # 6) russia-youtube.txt по TCP (fake, split2)
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-youtube.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-split-seqovl=2)
  args+=(--dpi-desync-split-pos=2)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-youtube.txt)
  args+=(--dpi-desync=split2)
  args+=(--dpi-desync-split-seqovl=2)
  args+=(--dpi-desync-split-pos=2)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-youtube.txt)
  args+=(--dpi-desync=hostfakesplit)
  args+=(--dpi-desync-split-seqovl=2)
  args+=(--dpi-desync-split-pos=2)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  ##############################################################################
  # 7) russia-blacklist.txt по TCP и UDP (fake, split2)
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-fooling=badseq)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--dpi-desync=split2)
  args+=(--dpi-desync-fooling=badseq)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  args+=(--filter-tcp=${p},${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--hostlist=${BASE}/myhostlist.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-split-seqovl=1)
  args+=(--dpi-desync-split-pos=2)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--dpi-desync-skip-nosni=1)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-fakedsplit-mod=altorder=1)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  ##############################################################################
  # 8) TCP 443 + russia-blacklist (fake+multidisorder, repeats=11)
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--dpi-desync=fake)
  args+=(--dpi-desync-repeats=11)
  args+=(--dpi-desync-fooling=md5sig)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--dpi-desync=multidisorder)
  args+=(--dpi-desync-split-pos=1,midsld)
  args+=(--dpi-desync-repeats=11)
  args+=(--dpi-desync-fooling=md5sig)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--dpi-desync=hostfakesplit)
  args+=(--dpi-desync-split-pos=1,midsld)
  args+=(--dpi-desync-repeats=11)
  args+=(--dpi-desync-fooling=md5sig)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--dpi-desync=fakedsplit)
  args+=(--dpi-desync-split-pos=1,midsld)
  args+=(--dpi-desync-repeats=11)
  args+=(--dpi-desync-fooling=md5sig)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--new)


done

# Запускаем nfqws со всеми динамическими стратегиями
exec nfqws "${args[@]}" > /dev/null 2>&1 &
}


# Функция для чтения конфигурации из файла
load_config() {
    local config_file=$1
    local suffix=$2

    while IFS= read -r line; do
        # Пропуск строк с комментариями или пустых строк
        echo "$line" | grep -q '^[[:space:]]*#' && continue
        [ -z "$line" ] && continue

        # Разбор ключа и значения
        IFS="=" read -r key value <<< "$line"
        key=$(echo "$key" | xargs)
        value=$(echo "$value" | xargs)

        # Назначение переменных с суффиксом, если он есть
        case "$key" in
            ip) eval "IP${suffix}=\"$value\"" ;;
            port) eval "PORTS${suffix}=\"$value\"" ;;
            mode) eval "MODE${suffix}=\"$value\"" ;;
            ca-bundle-path) eval "CA_BUNDLE_PATH${suffix}=\"$value\"" ;;
            pid) eval "PID${suffix}=\"$value\"" ;;
            profile) eval "PROFILE${suffix}=\"$value\"" ;;
            buffer-size) eval "BUFFER_SIZE${suffix}=\"$value\"" ;;
            doh-server) eval "DOH_SERVER${suffix}=\"$value\"" ;;
            daemon) eval "DAEMON${suffix}=\"$value\"" ;;
            ttl) eval "TTL${suffix}=\"$value\"" ;;
            auto-ttl) eval "AUTO_TTL${suffix}=\"$value\"" ;;
            min-ttl) eval "MIN_TTL${suffix}=\"$value\"" ;;
            desync-attacks) eval "DESYNC_ATTACKS${suffix}=\"$value\"" ;;
            wsize) eval "WSIZE${suffix}=\"$value\"" ;;
            wsfactor) eval "WSFACTOR${suffix}=\"$value\"" ;;
            split-at-sni) eval "SPLIT_AT_SNI${suffix}=\"$value\"" ;;
            split-position) eval "SPLIT_POSITION${suffix}=\"$value\"" ;;
            wrong-seq) eval "WRONG_SEQ${suffix}=\"$value\"" ;;
            builtin-dns) eval "BUILTIN_DNS${suffix}=\"$value\"" ;;
            builtin-dns-ip) eval "BUILTIN_DNS_IP${suffix}=\"$value\"" ;;
            builtin-dns-port) eval "BUILTIN_DNS_PORT${suffix}=\"$value\"" ;;
            protocol-tcp-udp) eval "PROTOCOL_TRAFFIC${suffix}=\"$value\"" ;;
        esac
    done < "$config_file"
}


start_load_config() {

    # Вызов функции для загрузки конфигураций
    if [ "$dpi_tunnel_0" = "1" ]; then
        echo "DPI tunnel 0 включён"
        load_config "$CONFIG_FILE" "0"
        :
    elif [ "$dpi_tunnel_0" = "0" ]; then
        echo "DPI tunnel 0 выключен"
    else
        echo "Некорректное значение для dpi_tunnel_0 или его нет"
    fi

    if [ "$dpi_tunnel_1" = "1" ]; then
        echo "DPI tunnel 1 включён"
        load_config "$CONFIG_FILE1" "1"
        :
    elif [ "$dpi_tunnel_1" = "0" ]; then
        echo "DPI tunnel 1 выключен"
    else
        echo "Некорректное значение для dpi_tunnel_1 или его нет"
    fi

    if [ "$dpi_tunnel_2" = "1" ]; then
        echo "DPI tunnel 2 включён"
        load_config "$CONFIG_FILE2" "2"
        :
    elif [ "$dpi_tunnel_1" = "0" ]; then
        echo "DPI tunnel 2 выключен"
    else
        echo "Некорректное значение для dpi_tunnel_2 или его нет"
    fi
}


unified_processing() {
    # Usage:
    #   unified_processing <output_file> <input_file>
    # или, для DPI режима:
    #   unified_processing dpi <output_file> <input_file>

    if [ "$1" = "dpi" ]; then
        mode="dpi"
        output_file="$2"
        input_file="$3"
    else
        mode="default"
        output_file="$1"
        input_file="$2"
    fi

    ##############################
    # Проверка рабочей директории и подготовка SHA256
    ##############################
    if [ ! -d "$SHA256_WORKING_DIR" ]; then
        echo "Рабочая директория отсутствует. Пропуск обработки."
        return 0
    fi

    calculate_sha256() {
        sha256sum "$1" | awk '{print $1}'
    }

    if [ ! -f "$SHA256_FLAG_FILE" ]; then
        echo "Файл $SHA256_FLAG_FILE отсутствует. Создание..."
        notification_send info "$MSG_NOTIFICATION_START"
        > "$SHA256_FLAG_FILE"
        safestart_module
        SAFESTART_MODULE_CALLED=1
        sleep 20
        python3_update_bin
        sleep 15
        # Собираю весь список приложений на устройстве (не системные)
        notification_send info "$MSG_NOTIFICATION_USER_PROGRAMM"
        USER_PROGRAM_LIST
        iptables-save -c > /data/adb/modules/ZDT-D/files/setting/iptables_save_becup.rules
    fi

    # Используем basename входного файла как ключ
    file_key=$(basename "$input_file")
    old_hash=$(grep "^${file_key}=" "$SHA256_FLAG_FILE" | cut -d'=' -f2)
    new_hash=$(calculate_sha256 "$input_file")

    # Обновляем флаговый файл для этого файла
    grep -v "^${file_key}=" "$SHA256_FLAG_FILE" > "$SHA256_FLAG_FILE.tmp"
    echo "${file_key}=${new_hash}" >> "$SHA256_FLAG_FILE.tmp"
    mv "$SHA256_FLAG_FILE.tmp" "$SHA256_FLAG_FILE"

    # Вызвать safestart_module ровно 1 раз
    if [ -z "${SAFESTART_MODULE_CALLED}" ]; then
        SAFESTART_MODULE_CALLED=1
        safestart_module
    fi

    ##############################
    # Проверка изменения SHA256 и вызов задержки, если требуется
    ##############################
    if [ "$old_hash" != "$new_hash" ]; then
        # Если хеш отсутствовал или изменился, вызываем start_system_final один раз,
        # используя переменную для блокировки повторного вызова.
        if [ -z "$START_SYSTEM_FINAL_CALLED" ]; then
            START_SYSTEM_FINAL_CALLED=1
            echo "SHA256 для $input_file изменён или отсутствует. Вызываю start_system_final (задержка до разблокировки системы)..."
            start_system_final
        else
            echo "Функция start_system_final уже была вызвана ранее. Продолжаю выполнение без дополнительной задержки."
        fi
    else
        echo "Хеш входного файла не изменился, пропускаю обработку."
        return 0
    fi

    ##############################
    # Очистка выходного файла (происходит только если SHA256 изменился)
    ##############################
    > "$output_file"
    echo "Файл $output_file очищен."

    ##############################
    # Обработка файла
    ##############################
    if [ "$mode" = "dpi" ]; then
        echo "Режим DPI: выполняется обработка с использованием dumpsys и stat."
        # Обработка через dumpsys
        while IFS= read -r package_name; do
            [ -z "$package_name" ] && { echo "Пропущена пустая строка"; continue; }
            if grep -q "^$package_name=" "$output_file"; then
                echo "UID для $package_name уже найден, пропуск..."
                continue
            fi
            echo "Обработка пакета (dumpsys): $package_name"
            uid=$(dumpsys package "$package_name" 2>/dev/null | grep "userId=" | awk -F'=' '{print $2}' | head -n 1)
            if [ -n "$uid" ]; then
                echo "$package_name=$uid" >> "$output_file"
                sync
                echo "Записан UID через dumpsys: $uid"
            else
                echo "UID не найден через dumpsys для $package_name"
            fi
        done < "$input_file"

        # Обработка через stat
        while IFS= read -r package_name; do
            [ -z "$package_name" ] && { echo "Пропущена пустая строка"; continue; }
            if grep -q "^$package_name=" "$output_file"; then
                echo "UID для $package_name уже найден, пропуск..."
                continue
            fi
            echo "Обработка пакета (stat): $package_name"
            uid=$(stat -c '%u' "/data/data/$package_name" 2>/dev/null)
            if [ -n "$uid" ]; then
                echo "$package_name=$uid" >> "$output_file"
                sync
                echo "Записан UID через stat: $uid"
            else
                echo "UID не найден через stat для $package_name"
            fi
        done < "$input_file"
    else
        echo "Режим по умолчанию: выполняется обработка с использованием dumpsys."
        while IFS= read -r package_name; do
            [ -z "$package_name" ] && { echo "Пропущена пустая строка"; continue; }
            if grep -q "^$package_name=" "$output_file"; then
                echo "UID для $package_name уже найден, пропуск..."
                continue
            fi
            echo "Обработка пакета: $package_name"
            uid=$(dumpsys package "$package_name" 2>/dev/null | grep "userId=" | awk -F'=' '{print $2}' | head -n 1)
            if [ -n "$uid" ]; then
                echo "$package_name=$uid" >> "$output_file"
                sync
                echo "Записан UID через dumpsys: $uid"
            else
                echo "UID не найден через dumpsys для $package_name, пробуем stat..."
                uid=$(stat -c '%u' "/data/data/$package_name" 2>/dev/null)
                if [ -n "$uid" ]; then
                    echo "$package_name=$uid" >> "$output_file"
                    sync
                    echo "Записан UID через stat: $uid"
                else
                    echo "UID не найден через stat для $package_name"
                fi
            fi
        done < "$input_file"
    fi

    echo "Обработка завершена для файла $input_file. Результаты сохранены в $output_file"
}


iptables_zapret_default_full() {
    # Выполнить действия, которые должны происходить один раз
    if [ -z "$_NOTIFICATION_INIT" ]; then
        # Отравка уведомления о настройке iptables
        notification_send info "$MSG_IPTABLES_REDIRECT"
        # Флаг инициализации, чтобы повторно не выполнять блок выше
        _NOTIFICATION_INIT=1
    fi

    # Если альтернативный режим включён, выполняем только альтернативный блок
    if [ "$alternativel" = "1" ]; then
        # -----------------------------
        # Альтернативный режим (код первого скрипта)
        # -----------------------------
        uselist="1"      # Автоматический список хостов
        debug="0"        # Логи в файл: 0 – отключено, 1 – включено
        local QUEUE="$1"         # Первый параметр (например, "200")

        dpi_list="pasket.txt"         # Список URL-адресов
        dpi_ignore="pasket_skip.txt"    # Список для игнорирования URL
        nftname="inet zapret"           # Таблица для nftables
        iptname="mangle"                # Таблица для iptables

        # Если не задан путь для списков, используем текущую директорию
        if [ -z "$dpi_list_path" ]; then
            dpi_list_path="."
        fi

        # Автоматический список хостов
        if [ "$uselist" = "1" ]; then
            echo "Используется автоматический список хостов"
            if ! [ -e "$dpi_list_path/$dpi_ignore" ]; then
                echo -n "" > "$dpi_list_path/$dpi_ignore"
                chmod 666 "$dpi_list_path/$dpi_ignore"
            fi
            HOSTLIST_NOAUTO="--hostlist-auto=$dpi_list_path/$dpi_list --hostlist-exclude=$dpi_list_path/$dpi_ignore"
        else
            HOSTLIST_NOAUTO=""
        fi

        ###########################
        # Параметры для фильтрации портов
        NFQWS_OPT="--filter-tcp=80,8000 --filter-tcp=443 --filter-udp=50000-50099 --filter-tcp=443 --filter-udp=443,6969"
        if [ "$debug" = "1" ]; then
            echo "Отладка всех Desync: $NFQWS_OPT" >> "$dpi_list_path/DPI_logS.txt"
        fi
        ###########################

        addHlist() {
            if ! [ -e "$dpi_list_path/$dpi_list" ]; then
                echo -n "" > "$dpi_list_path/$dpi_list"
                chmod 666 "$dpi_list_path/$dpi_list"
            fi
            for site in $1; do
                if [ "$(grep -c "$site" "$dpi_list_path/$dpi_list")" = "0" ]; then
                    echo "$site" >> "$dpi_list_path/$dpi_list"
                fi
            done
        }

        # В альтернативном режиме интерфейс не используется – применяем правила ко всем интерфейсам
        iifnm=""
        oifnm=""
        echo "Запуск для всех интерфейсов (альтернативный режим)"

        # Корректировка параметров NetFilter
        if [ "$uselist" = "1" ]; then
            sysctl net.netfilter.nf_conntrack_tcp_be_liberal=1 > /dev/null
        fi
        if [ "$(echo "$NFQWS_OPT" | grep -c badsum)" != "0" ]; then
            sysctl net.netfilter.nf_conntrack_checksum=0 > /dev/null
        fi

        # Извлечение портов
        NFQWS_PORTS_TCP="$(echo "$NFQWS_OPT" | grep -oE 'filter-tcp=[0-9,-]+' | sed -e 's/.*=//' -e 's/,/\n/g' | sort -un)"
        NFQWS_PORTS_UDP="$(echo "$NFQWS_OPT" | grep -oE 'filter-udp=[0-9,-]+' | sed -e 's/.*=//' -e 's/,/\n/g' | sort -un)"
        if [ "$debug" = "1" ]; then
            echo "Отладка TCP портов: $NFQWS_PORTS_TCP" >> "$dpi_list_path/DPI_logS.txt"
            echo "Отладка UDP портов: $NFQWS_PORTS_UDP" >> "$dpi_list_path/DPI_logS.txt"
        fi

        iptAdd() {
            if [ "$debug" = "1" ]; then
                echo "Отладка ipt_Add $1, Порт: $2" >> "$dpi_list_path/DPI_logS.txt"
            fi
            iptables -t "$iptname" -I POSTROUTING $oifnm -p "$1" $iMportD "$2" $iCBo $iMark -j NFQUEUE --queue-num $QUEUE --queue-bypass
            if [ "$uselist" = "1" ]; then
                iptables -t "$iptname" -I PREROUTING $iifnm -p "$1" $iMportS "$2" $iCBr $iMark -j NFQUEUE --queue-num $QUEUE --queue-bypass
            fi
        }

        iptMultiPort() {
            if [ "$(echo "$iMportD" | grep -c 'multiport')" != "0" ]; then
                iptAdd "$1" "$(echo $2 | sed -e 's/ /,/g' -e 's/-/:/g')"
            else
                for current_port in $2; do
                    if [[ $current_port == *-* ]]; then
                        for i in $(seq ${current_port%-*} ${current_port#*-}); do
                            iptAdd "$1" "$i"
                        done
                    else
                        iptAdd "$1" "$current_port"
                    fi
                done
            fi
        }

        # Использование nftables, если доступно, иначе – iptables
        if ! [ -e "/proc/net/ip_tables_targets" ]; then
            echo "Использование nftables"
            nft create table "$nftname"
            nft add chain "$nftname" post "{type filter hook postrouting priority mangle;}"
            nft add rule "$nftname" post $oifnm tcp dport "{ $(echo "$NFQWS_PORTS_TCP" | sed 's/ /,/g') }" ct original packets 1-12 meta mark and 0x40000000 == 0 queue num $QUEUE bypass
            nft add rule "$nftname" post $oifnm udp dport "{ $(echo "$NFQWS_PORTS_UDP" | sed 's/ /,/g') }" ct original packets 1-12 meta mark and 0x40000000 == 0 queue num $QUEUE bypass
            if [ "$uselist" = "1" ]; then
                nft add chain "$nftname" pre "{type filter hook prerouting priority filter;}"
                nft add rule "$nftname" pre $iifnm tcp sport "{ $(echo "$NFQWS_PORTS_TCP" | sed 's/ /,/g') }" ct reply packets 1-3 queue num $QUEUE bypass
                nft add rule "$nftname" pre $iifnm udp sport "{ $(echo "$NFQWS_PORTS_UDP" | sed 's/ /,/g') }" ct reply packets 1-3 queue num $QUEUE bypass
            fi

            if [ "$debug" = "1" ]; then
                echo "Отладка nftables" >> "$dpi_list_path/DPI_logS.txt"
                nft list table "$nftname" >> "$dpi_list_path/DPI_logS.txt"
            fi
        else
            echo "Использование iptables"
            if [ "$(cat /proc/net/ip_tables_targets | grep -c 'NFQUEUE')" = "0" ]; then
                echo "Ошибка - плохой iptables, скрипт не будет работать"
                return 1
            else
                if [ "$(cat /proc/net/ip_tables_matches | grep -c 'multiport')" != "0" ]; then
                    iMportS="-m multiport --sports"
                    iMportD="-m multiport --dports"
                else
                    iMportS="--sport"
                    iMportD="--dport"
                    echo "Плохой iptables, пропуск multiport"
                fi

                if [ "$(cat /proc/net/ip_tables_matches | grep -c 'connbytes')" != "0" ]; then
                    iCBo="-m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12"
                    iCBr="-m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes 1:3"
                else
                    iCBo=""
                    iCBr=""
                    echo "Плохой iptables, пропуск connbytes"
                fi

                if [ "$(cat /proc/net/ip_tables_matches | grep -c 'mark')" != "0" ]; then
                    iMark="-m mark ! --mark 0x40000000/0x40000000"
                else
                    iMark=""
                    echo "Плохой iptables, пропуск mark"
                fi

                iptMultiPort "tcp" "$NFQWS_PORTS_TCP"
                iptMultiPort "udp" "$NFQWS_PORTS_UDP"

                if [ "$debug" = "1" ]; then
                    echo "Отладка iptables" >> "$dpi_list_path/DPI_logS.txt"
                    iptables -t "$iptname" -L >> "$dpi_list_path/DPI_logS.txt"
                fi
            fi

            if [ "$debug" = "1" ]; then
                ndebug="--debug=@$dpi_list_path/DPI_logN.txt"
            else
                ndebug=""
            fi

            sed -i 's/^full_system=1$/full_system=0/' "$SETTING_START_PARAMSET"
            sed -i 's/^dpi_tunnel_0=1$/dpi_tunnel_0=0/' "$SETTING_START_PARAMSET"
            sed -i 's/^dpi_tunnel_1=1$/dpi_tunnel_1=0/' "$SETTING_START_PARAMSET"

            echo "AntiDPI служба включена"
        fi

        return 0
    fi

    # -----------------------------
    # Второй режим (если alternativel=0)
    # -----------------------------
    local QUEUE="$1"         # Первый параметр (например, "200")
    local UID_FILE="$2"      # Второй параметр – файл с данными UID
    local MARK="0x40000000"
    full_system=${full_system:-0}

    check_iptables() {
        if ! command -v iptables >/dev/null 2>&1; then
            echo "iptables не найден, завершаем выполнение."
            return 1
        fi
        echo "iptables найден: $(iptables --version)"
    }

    chain_supported() {
        local table="$1"
        local chain="$2"
        iptables -t "$table" -L "$chain" >/dev/null 2>&1
        return $?
    }

    add_rule() {
        local table="$1"
        local chain="$2"
        local rule="$3"

        echo "Проверка правила в таблице '$table', цепочке '$chain': $rule"
        iptables -t "$table" -C "$chain" $rule 2>/dev/null
        if [ $? -ne 0 ]; then
            iptables -t "$table" -A "$chain" $rule
            if [ $? -eq 0 ]; then
                echo "✅ Правило успешно добавлено: $table $chain: $rule"
                return 0
            else
                echo "❌ Ошибка при добавлении правила: $table $chain: $rule"
                return 1
            fi
        else
            echo "ℹ️  Правило уже существует: $table $chain: $rule"
            return 0
        fi
    }

    add_outbound_rule_uid_old() {
        local uid="$1"
        local rule="-m owner --uid-owner $uid -j NFQUEUE --queue-num $QUEUE --queue-bypass"
        local added=1
        for table in mangle; do
            for chain in OUTPUT PREROUTING; do
                if chain_supported "$table" "$chain"; then
                    echo "Попытка добавить OLD outbound правило для UID $uid в таблице $table, цепочке $chain..."
                    if add_rule "$table" "$chain" "$rule"; then
                        added=0
                        break 2
                    fi
                fi
            done
        done
        return $added
    }

    add_outbound_rule_uid_new() {
        local uid="$1"
        local rule="-m owner --uid-owner $uid -m mark ! --mark $MARK/$MARK -j NFQUEUE --queue-num $QUEUE --queue-bypass"
        local added=1
        for table in mangle; do
            for chain in OUTPUT PREROUTING; do
                if chain_supported "$table" "$chain"; then
                    echo "Попытка добавить NEW outbound правило для UID $uid в таблице $table, цепочке $chain..."
                    if add_rule "$table" "$chain" "$rule"; then
                        added=0
                        break 2
                    fi
                fi
            done
        done
        return $added
    }

    add_common_inbound_rule_fallback() {
        local rule="-m mark ! --mark $MARK/$MARK -j NFQUEUE --queue-num $QUEUE --queue-bypass"
        for table in mangle; do
            for chain in PREROUTING INPUT; do
                if chain_supported "$table" "$chain"; then
                    echo "Попытка добавить общий inbound fallback правило в таблице $table, цепочке $chain..."
                    if add_rule "$table" "$chain" "$rule"; then
                        return 0
                    fi
                fi
            done
        done
        return 1
    }

    add_common_outbound_rule_fallback() {
        local rule="-m mark ! --mark $MARK/$MARK -j NFQUEUE --queue-num $QUEUE --queue-bypass"
        for table in mangle; do
            for chain in OUTPUT PREROUTING; do
                if chain_supported "$table" "$chain"; then
                    echo "Попытка добавить общий outbound fallback правило в таблице $table, цепочке $chain..."
                    if add_rule "$table" "$chain" "$rule"; then
                        return 0
                    fi
                fi
            done
        done
        return 1
    }

    check_iptables

    if [ "$full_system" -eq 1 ]; then
        echo "Переменная full_system=1. Устанавливаем общее правило для всей системы."

        if add_common_inbound_rule_fallback; then
            echo "✅ Общее inbound правило успешно добавлено."
        else
            echo "❌ Не удалось добавить общее inbound правило!"
        fi

        if add_common_outbound_rule_fallback; then
            echo "✅ Общее outbound правило успешно добавлено."
        else
            echo "❌ Не удалось добавить общее outbound правило!"
        fi

        echo "✅ Завершено. Правила установлены для всей системы."
    else
        echo "Переменная full_system не равна 1. Пытаемся установить правила для каждого UID индивидуально."

        local INDIVIDUAL_OUTBOUND_TOTAL=0
        local INDIVIDUAL_OUTBOUND_SUCCESS=0

        while IFS='=' read -r app_name uid; do
            [ -z "$app_name" ] && continue
            [ -z "$uid" ] && continue
            echo "Обработка приложения \"$app_name\" (UID: $uid)..."

            INDIVIDUAL_OUTBOUND_TOTAL=$((INDIVIDUAL_OUTBOUND_TOTAL+1))
            if add_outbound_rule_uid_old "$uid"; then
                echo "OLD outbound правило успешно добавлено для UID $uid."
                INDIVIDUAL_OUTBOUND_SUCCESS=$((INDIVIDUAL_OUTBOUND_SUCCESS+1))
            else
                echo "OLD outbound правило не удалось для UID $uid, пробую NEW outbound..."
                if add_outbound_rule_uid_new "$uid"; then
                    echo "NEW outbound правило успешно добавлено для UID $uid."
                    INDIVIDUAL_OUTBOUND_SUCCESS=$((INDIVIDUAL_OUTBOUND_SUCCESS+1))
                else
                    echo "Не удалось добавить индивидуальное outbound правило для UID $uid."
                fi
            fi

        done < "$UID_FILE"

        if [ "$INDIVIDUAL_OUTBOUND_SUCCESS" -lt 1 ]; then
            echo "Индивидуальные outbound правила не установлены, пробую добавить общий outbound fallback..."
            if ! add_common_outbound_rule_fallback; then
                echo "❌ Не удалось добавить ни индивидуальное, ни общий outbound правило!"
            fi
        fi

        echo "✅ Завершено. Индивидуальные правила: outbound $INDIVIDUAL_OUTBOUND_SUCCESS/$INDIVIDUAL_OUTBOUND_TOTAL."
    fi
}


full_id_iptables() {
    mode="$1"
    queue="$2"
    iface="$3"
    uid_file="$4"

    # 1. Проверяем обязательные параметры
    if [ -z "$mode" ] || [ -z "$queue" ]; then
        echo "Usage: full_id_iptables {full|no_full} <queue-num> [iface] [uid_file]"
        return 1
    fi

    # 2. Опция интерфейса (или пусто для всех)
    if [ -n "$iface" ]; then
        iopt="-o $iface"
    else
        iopt=""
    fi

    # 3. Если указан файл UID и он существует — добавляем правила по UID
    if [ -n "$uid_file" ] && [ -f "$uid_file" ]; then
        while IFS='=' read -r _ uid; do
            case "$uid" in
                [0-9]*)
                    if [ "$mode" = "full" ]; then
                        iptables -t mangle -I OUTPUT $iopt \
                            -m owner --uid-owner "$uid" \
                            -j NFQUEUE --queue-num "$queue" --queue-bypass
                    else
                        # no_full: только HTTP/HTTPS
                        iptables -t mangle -I OUTPUT $iopt \
                            -p tcp --dport 80  -m owner --uid-owner "$uid" \
                            -j NFQUEUE --queue-num "$queue" --queue-bypass
                        iptables -t mangle -I OUTPUT $iopt \
                            -p tcp --dport 443 -m owner --uid-owner "$uid" \
                            -j NFQUEUE --queue-num "$queue" --queue-bypass
                    fi
                    ;;
            esac
        done < "$uid_file"
        return 0
    fi

    # 4. Иначе — общие правила в зависимости от режима
    case "$mode" in
        full)
            iptables -t mangle -I OUTPUT $iopt \
                -j NFQUEUE --queue-num "$queue" --queue-bypass
            ;;
        no_full)
            iptables -t mangle -I OUTPUT $iopt \
                -p tcp --dport 80  -j NFQUEUE --queue-num "$queue" --queue-bypass
            iptables -t mangle -I OUTPUT $iopt \
                -p tcp --dport 443 -j NFQUEUE --queue-num "$queue" --queue-bypass
            ;;
        *)
            echo "Invalid mode: $mode"
            echo "Usage: full_id_iptables {full|no_full} <queue-num> [iface] [uid_file]"
            return 1
            ;;
    esac

    return 0
}


####################################
# Добавляю правило ip (IPv4/IPv6) с опциональной поддержкой ipset
####################################

success_count=0
fail_count=0

mobile_iptables_beta() {
    # --- 1) Флаги полного или альтернативного режима ---
    full_system=${full_system:-0}
    alternativel=${alternativel:-0}
    if [ "$full_system" -eq 1 ] || [ "$alternativel" -eq 1 ]; then
        echo "Пропускаем mobile_iptables_beta: full_system=$full_system, alternativel=$alternativel"
        return 0
    fi

    # --- 2) Проверка аргументов: порт + файл ---
    if [ -z "$1" ] || [ -z "$2" ]; then
        echo "Ошибка: Не заданы порт или файл с IP-адресами."
        notification_send error "$MSG_RULE_ERROR"
        return 1
    fi
    port="$1"; shift
    ip_file="$1"; shift

    if [ ! -f "$ip_file" ]; then
        echo "Ошибка: Файл не найден: $ip_file"
        notification_send error "$MSG_RULE_ERROR"
        return 1
    fi

    # --- 3) Вспомогательная функция добавления правила в iptables ---
    add_rule() {
        # $1 = цепочка, $2 = команда (iptables)
        $2 -t mangle -A "$1" -d "$IP" -j NFQUEUE --queue-num "$port" 2>/dev/null
        return $?
    }

    # --- 4) Подготовка к обработке и стартовое уведомление 0% ---
    # считаем ТОЛЬКО IPv4-строки (пропускаем пустые, комменты и IPv6)
    total=$(grep -E '^[[:space:]]*[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' "$ip_file" 2>/dev/null | grep -v ':' | grep -v '^[[:space:]]*#' | wc -l | tr -d ' ')
    if [ -z "$total" ]; then
        total=0
    fi

    processed=0
    thresholds="10 25 50 75 100"
    next_threshold=$(echo $thresholds | cut -d' ' -f1)
    sent_final=0

    notification_send processing "$ip_file" 0
    echo "Начали обработку файла $ip_file — всего $total адресов"

    # если нет IPv4-адресов — аккуратно выйти, чтобы не делить на ноль
    if [ "$total" -eq 0 ]; then
        echo "Файл $ip_file не содержит IPv4-адресов для обработки. Пропускаем."
        notification_send processing "$ip_file" 100
        return 0
    fi

    # --- 5) Режим ipset: создаём только IPv4-набор и правила iptables ---
    if command -v ipset >/dev/null 2>&1; then
        echo "ipset найден — создаём IPv4-набор и правило iptables"

        set_v4="iplist_v4_$port"

        # Убираем старый набор и создаём новый
        ipset destroy "$set_v4" 2>/dev/null
        ipset create  "$set_v4" hash:ip family inet  2>/dev/null

        # Заполняем только IPv4: пропускаем строки с ':'
        while IFS= read -r IP; do
            [ -z "$IP" ] || [ "${IP#\#}" != "$IP" ] && continue
            if echo "$IP" | grep -q ':'; then
                echo "  Пропускаем IPv6 $IP"
                continue
            fi
            ipset add "$set_v4" "$IP" 2>/dev/null
        done < "$ip_file"

        # Добавляем ровно 2 правила NFQUEUE для IPv4 (PREROUTING и OUTPUT)
        iptables -t mangle -A PREROUTING -m set --match-set "$set_v4" dst -j NFQUEUE --queue-num "$port"
        iptables -t mangle -A OUTPUT     -m set --match-set "$set_v4" dst -j NFQUEUE --queue-num "$port"

        notification_send processing "$ip_file" 100
        echo "Готово: ipset-набор создан, правила NFQUEUE на порт $port"
        return 0
    fi

    # --- 6) Пошаговый режим без ipset: перебор по IPv4 и пропуск IPv6 ---
    echo "ipset не найден — перебираем построчно, пропуская IPv6"
    while IFS= read -r IP; do
        # Пропускаем пустые и комментарии
        [ -z "$IP" ] || [ "${IP#\#}" != "$IP" ] && continue

        # Если это IPv6 (есть ':'), просто пропускаем
        if echo "$IP" | grep -q ':'; then
            echo "  Пропускаем IPv6 $IP"
            continue
        fi

        echo "Обработка IPv4: $IP"
        ingress_success=0
        egress_success=0

        # # Входящий (PREROUTING → INPUT) с retry
        # if add_rule PREROUTING iptables; then
            # echo "  ВХОД: PREROUTING OK"
            # ingress_success=1
        # else
            # notification_send error "Retry PREROUTING $IP"
            # sleep 3
            # if add_rule PREROUTING iptables; then
                # echo "  ВХОД: PREROUTING после retry OK"
                # ingress_success=1
            # else
                # echo "  ВХОД: пробуем INPUT…"
                # if add_rule INPUT iptables; then
                    # echo "  ВХОД: INPUT OK"
                    # ingress_success=1
                # else
                    # notification_send error "Ошибка INPUT $IP"
                # fi
            # fi
        # fi

        # Исходящий (OUTPUT → POSTROUTING) с retry
        if add_rule OUTPUT iptables; then
            echo "  ИСХОД: OUTPUT OK"
            egress_success=1
        else
            notification_send error "Retry OUTPUT $IP"
            sleep 3
            if add_rule OUTPUT iptables; then
                echo "  ИСХОД: OUTPUT после retry OK"
                egress_success=1
            else
                echo "  ИСХОД: пробуем POSTROUTING…"
                if add_rule POSTROUTING iptables; then
                    echo "  ИСХОД: POSTROUTING OK"
                    egress_success=1
                else
                    notification_send error "Ошибка POSTROUTING $IP"
                fi
            fi
        fi

        # Статистика
        if [ "$ingress_success" -eq 1 ] && [ "$egress_success" -eq 1 ]; then
            success_count=$((success_count + 1))
        else
            fail_count=$((fail_count + 1))
        fi

        # Прогресс
        processed=$((processed + 1))
        pct=$(( processed * 100 / total ))
        if [ "$pct" -ge "$next_threshold" ]; then
            notification_send processing "$ip_file" "$next_threshold"
            [ "$next_threshold" -eq 100 ] && sent_final=1
            thresholds="${thresholds#* }"
            if [ -n "$thresholds" ]; then
                next_threshold=$(echo $thresholds | cut -d' ' -f1)
            else
                next_threshold=101
            fi
        fi

    done < "$ip_file"

    # --- 7) Гарантируем уведомление 100%, если не было ---
    if [ "$sent_final" -eq 0 ]; then
        notification_send processing "$ip_file" 100
    fi

    echo "Готово: обработано $processed/$total IPv4-адресов, успехов: $success_count, ошибок: $fail_count"
    return 0
}


apply_zdt_rules() {
    local DIR="/storage/emulated/0/ZDT-D"

    # 1) Проверяем, что каталог существует
    if [ ! -d "$DIR" ]; then
        echo "apply_zdt_rules: каталог не найден: $DIR" >&2
        return 0
    fi

    # 2) Проверяем, что есть .txt
    set -- "$DIR"/*.txt
    if [ ! -e "$1" ]; then
        echo "apply_zdt_rules: нет .txt в $DIR" >&2
        return 0
    fi

    for file_path in "$DIR"/*.txt; do
        echo "apply_zdt_rules: обрабатываю $file_path" >&2

        # читаем первую непустую строку, убираем BOM и пробелы
        local line cidr addr mask
        while IFS= read -r line; do
            cidr=$(printf "%s" "$line" \
                | sed -e 's/^\xEF\xBB\xBF//' \
                      -e 's/^[[:space:]]*//' \
                      -e 's/[[:space:]]*$//')
            [ -n "$cidr" ] && break
        done <"$file_path"

        echo "apply_zdt_rules: прочитано '$cidr'" >&2

        # делим на адрес и маску
        addr=${cidr%%/*}
        mask=${cidr#*/}

        # проверка, что есть слеш и обе части непустые
        if [ "$addr" = "$cidr" ] || [ -z "$mask" ]; then
            echo "apply_zdt_rules: нет разделителя '/', пропускаю" >&2
            continue
        fi

        # проверяем маску — число
        if ! printf "%s" "$mask" | grep -Eq '^[0-9]+$'; then
            echo "apply_zdt_rules: маска не число: $mask" >&2
            continue
        fi

        # теперь выбор по типу адреса
        if printf "%s" "$addr" | grep -q '\.'; then
            # IPv4
            # проверим четыре октета 0-255
            if printf "%s" "$addr" \
               | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
                # дополнительно проверяем каждый октет <=255
                valid4=1
                IFS=.; set -- $addr; unset IFS
                for oct in "$1" "$2" "$3" "$4"; do
                    if [ "$oct" -gt 255 ] 2>/dev/null || [ "$oct" -lt 0 ] 2>/dev/null; then
                        valid4=0; break
                    fi
                done
                if [ "$valid4" -eq 1 ] && [ "$mask" -ge 0 ] && [ "$mask" -le 32 ]; then
                    echo "apply_zdt_rules: корректный IPv4-CIDR, применяю" >&2
                    mobile_iptables_beta "205" "$file_path"
                else
                    echo "apply_zdt_rules: неверный IPv4-CIDR, пропускаю" >&2
                fi
            else
                echo "apply_zdt_rules: неверный формат IPv4-адреса, пропускаю" >&2
            fi

        elif printf "%s" "$addr" | grep -q ':'; then
            # IPv6 (с поддержкой ::)
            # простая проверка: хотя бы одно ':' и каждая группа 0-4 hex
            if printf "%s" "$addr" \
               | grep -Eq '^([0-9A-Fa-f]{0,4}:){1,7}[0-9A-Fa-f]{0,4}$'; then
                if [ "$mask" -ge 0 ] && [ "$mask" -le 128 ]; then
                    echo "apply_zdt_rules: корректный IPv6-CIDR, применяю" >&2
                    mobile_iptables_beta "205" "$file_path"
                else
                    echo "apply_zdt_rules: неверная IPv6-маска, пропускаю" >&2
                fi
            else
                echo "apply_zdt_rules: неверный формат IPv6-адреса, пропускаю" >&2
            fi

        else
            echo "apply_zdt_rules: не IP-адрес, пропускаю" >&2
        fi
    done
    notification_send processingfinal "$MSG_PROCESSINGFINAL"
    notification_send error "Успешно добавлено: $success_count / Неудача: $fail_count"
    return 0
}


apply_iptables_rules() {
    local uid_file=$1
    local port=$2
    local tunnel_id=$3

    echo "Добавляю правила в iptables для порта $port (tunnel ID: $tunnel_id) с использованием файла $uid_file"
    # Пример команды (замените на свою логику):
    # iptables -A INPUT -p tcp --dport "$port" -j ACCEPT
}


check_and_restart_dpi_tunnel() {
    local pid="$1"
    local ports="$2"
    local ip="$3"
    local mode="$4"
    local profile="$5"
    local buffer_size="$6"
    local doh_server="$7"
    local ttl="$8"
    local auto_ttl="$9"
    local min_ttl="${10}"
    local desync_attacks="${11}"
    local wsize="${12}"
    local wsfactor="${13}"
    local split_at_sni="${14}"
    local split_position="${15}"
    local wrong_seq="${16}"
    local builtin_dns="${17}"
    local builtin_dns_ip="${18}"
    local builtin_dns_port="${19}"

    if ! netstat -tuln | grep -q ":$ports .*LISTEN"; then
        echo "Порт $ports не прослушивается. Перезапуск процесса..."

        # запускаем команду в фоне и отсоединяем её, чтобы она пережила exit
        setsid /system/bin/dpitunnel-cli \
            --pid "$pid" \
            --daemon \
            --ca-bundle-path "$CA_BUNDLE_PATH" \
            --ip "$ip" \
            --port "$ports" \
            --mode "$mode" \
            --profile "$profile" \
            --buffer-size "$buffer_size" \
            --doh-server "$doh_server" \
            --ttl "$ttl" \
            --auto-ttl "$auto_ttl" \
            --min-ttl "$min_ttl" \
            --desync-attacks "$desync_attacks" \
            --wsize "$wsize" \
            --wsfactor "$wsfactor" \
            --split-at-sni "$split_at_sni" \
            --split-position "$split_position" \
            --wrong-seq "$wrong_seq" \
            --builtin-dns "$builtin_dns" \
            --builtin-dns-ip "$builtin_dns_ip" \
            --builtin-dns-port "$builtin_dns_port" \
            >/dev/null 2>&1 &

        echo "DPI Tunnel на порту $ports запущен (nohup &)."
    fi
}



dns_redirect () {

    # --- DNS: если dns=1, ставим mangle-исключения и NAT-DNS внутри NAT_DPI ---
    if [ "${dns:-0}" != "1" ]; then
        return 0
    fi

    echo ">>> DNS режим включён: настраиваем mangle-исключения и NAT_DPI DNS-правила"

    # --- dnscrypt (без логов)
    # оставляем как есть — setsid может быть доступен/не доступен на устройстве
    setsid dnscrypt-proxy -config /data/adb/modules/ZDT-D/dnscrypt-proxy/dnscrypt-proxy.toml </dev/null >/dev/null 2>&1 &

    # определим iptables-бинарь (PATH или стандартные android-пути)
    IPTABLES="$(command -v iptables 2>/dev/null || true)"
    if [ -z "$IPTABLES" ]; then
        for p in /system/bin/iptables /system/xbin/iptables /sbin/iptables /bin/iptables /usr/sbin/iptables; do
            if [ -x "$p" ]; then
                IPTABLES="$p"
                break
            fi
        done
    fi
    # fallback на plain "iptables" если ничего не найдено — вызов вернёт ошибку, если его нет
    [ -z "$IPTABLES" ] && IPTABLES="iptables"

    # Убедимся, что MANGLE_APP и джамп в OUTPUT есть (инициализация mangle)
    $IPTABLES -t mangle -nL MANGLE_APP >/dev/null 2>&1 || $IPTABLES -t mangle -N MANGLE_APP
    $IPTABLES -t mangle -C OUTPUT -j MANGLE_APP 2>/dev/null || $IPTABLES -t mangle -A OUTPUT -j MANGLE_APP

    # порты DNS: обычный DNS и DoT/DoH-over-TCP порт (используем 53 и 853)
    DNS_PORTS="53 853"
    DNS_DPORTS="53,853"    # для multiport

    # ---- 1) mangle-исключения (RETURN) ----
    for proto in udp tcp sctp; do
        # сначала проверим наличие multiport-правила
        if $IPTABLES -t mangle -C MANGLE_APP -p "$proto" -m multiport --dports "$DNS_DPORTS" -j RETURN >/dev/null 2>&1; then
            : # есть multiport-исключение
        else
            # попробуем вставить multiport
            if $IPTABLES -t mangle -I MANGLE_APP 1 -p "$proto" -m multiport --dports "$DNS_DPORTS" -j RETURN >/dev/null 2>&1; then
                echo ">>> mangle: добавлено multiport-исключение ($DNS_DPORTS) для $proto"
            else
                # fallback: отдельные правила для каждого порта (вставляем на позицию 1 чтобы быть первыми)
                for p in $DNS_PORTS; do
                    if $IPTABLES -t mangle -C MANGLE_APP -p "$proto" --dport "$p" -j RETURN >/dev/null 2>&1; then
                        : # уже есть
                    else
                        $IPTABLES -t mangle -I MANGLE_APP 1 -p "$proto" --dport "$p" -j RETURN 2>/dev/null || \
                            $IPTABLES -t mangle -A MANGLE_APP -p "$proto" --dport "$p" -j RETURN 2>/dev/null
                    fi
                done
                echo ">>> mangle: multiport не доступен — добавлены отдельные исключения $DNS_PORTS для $proto"
            fi
        fi
    done

    echo ">>> mangle-исключение DNS добавлено в MANGLE_APP (функционально как -I OUTPUT ... RETURN)"

    # ---- 2) удалим возможные глобальные DNAT из nat OUTPUT, чтобы не было дублирования ----
    for port in 53 853; do
        $IPTABLES -t nat -C OUTPUT -p udp --dport "$port" -j DNAT --to-destination 127.0.0.1:863 2>/dev/null \
            && $IPTABLES -t nat -D OUTPUT -p udp --dport "$port" -j DNAT --to-destination 127.0.0.1:863 2>/dev/null
        $IPTABLES -t nat -C OUTPUT -p tcp --dport "$port" -j DNAT --to-destination 127.0.0.1:863 2>/dev/null \
            && $IPTABLES -t nat -D OUTPUT -p tcp --dport "$port" -j DNAT --to-destination 127.0.0.1:863 2>/dev/null
    done

    # ---- 3) добавим DNAT-перехват внутрь NAT_DPI (и PREROUTING) ----
    # цепочки, в которых хотим ставить правила (NAT_DPI + PREROUTING для захвата входящего трафика)
    CHAINS="NAT_DPI PREROUTING"
    for proto in udp tcp sctp; do
        for chain in $CHAINS; do
            # сначала проверим наличие multiport DNAT
            if $IPTABLES -t nat -C "$chain" -p "$proto" -m multiport --dports "$DNS_DPORTS" -j DNAT --to-destination 127.0.0.1:863 >/dev/null 2>&1; then
                : # правило уже есть
            else
                # попробуем вставить multiport-правило первым
                if $IPTABLES -t nat -I "$chain" 1 -p "$proto" -m multiport --dports "$DNS_DPORTS" -j DNAT --to-destination 127.0.0.1:863 >/dev/null 2>&1; then
                    echo ">>> nat: добавлено multiport DNAT ($DNS_DPORTS) для $proto -> 127.0.0.1:863 в $chain"
                else
                    # fallback: добавляем по-порту
                    for p in $DNS_PORTS; do
                        if $IPTABLES -t nat -C "$chain" -p "$proto" --dport "$p" -j DNAT --to-destination 127.0.0.1:863 >/dev/null 2>&1; then
                            : # уже есть
                        else
                            $IPTABLES -t nat -I "$chain" 1 -p "$proto" --dport "$p" -j DNAT --to-destination 127.0.0.1:863 2>/dev/null || \
                                $IPTABLES -t nat -A "$chain" -p "$proto" --dport "$p" -j DNAT --to-destination 127.0.0.1:863 2>/dev/null
                        fi
                    done
                    echo ">>> nat: multiport не доступен — добавлены отдельные DNAT $DNS_PORTS для $proto в $chain"
                fi
            fi
        done
    done

    echo ">>> Нативные DNS правила (53,853 -> 127.0.0.1:863) расположены первыми внутри NAT_DPI/PREROUTING (если возможно)"

    return 0
}


# Улучшенная версия функции load_config_dpi_tunnel
# Сохранить как отдельный файл/скрипт или source в текущем окружении.
# Функция сохраняет исходный функционал, но:
# - уменьшает вызовы внешних утилит где возможно
# - делает добавление правил idempotent (не дублирует правила)
# - парсит файл uid единожды и хранит в массиве для многократного использования
# - улучшен вывод логов и обработка интерфейсов/портов

load_config_dpi_tunnel() {
    local uid_file="$1"
    local dest_port="$2"
    local proto_choice="${3:-tcp}"
    local ifaces_raw="${4-}"

    # внешняя конфигурация
    port_preference="${port_preference:-0}"   # 0 -> dpi_ports, 1 -> all ports
    dpi_ports="${dpi_ports:-80 443 2710 6969 51413 6771 6881-6999 49152-65535}"

    # локальные переменные
    local MODE L_IFACES valid_ifaces invalid_ifaces detected
    local UID_PAIRS   # NOTE: не используем bash-массивы, держим список как "слово-столбец"

    # ---- logging ----
    _log() { printf "[dpi] %s\n" "$*" >&2; }
    _err() { printf "[dpi][ERR] %s\n" "$*" >&2; }

    # ---- util: trim ----
    _trim() { printf "%s" "${1# }" | sed 's/^ *//;s/ *$//'; }

    # ---- функции для интерфейсов ----
    _split_and_normalize_ifaces() {
        local raw="$1"
        raw="$(printf "%s" "$raw" | tr ',' ' ' | tr -s '[:space:]' ' ')"
        L_IFACES=""
        [ -z "${raw// /}" ] && return 0
        for f in $raw; do
            f="$(printf "%s" "$f" | sed 's/^ *//;s/ *$//')"
            [ -z "$f" ] && continue
            L_IFACES="$L_IFACES $f"
        done
        L_IFACES="$(printf "%s" "$L_IFACES" | sed 's/^ *//;s/ *$//')"
    }

    _is_iface_exists() {
        local ifname="$1"
        if command -v ip >/dev/null 2>&1; then
            ip link show "$ifname" >/dev/null 2>&1 && return 0
        fi
        if command -v ifconfig >/dev/null 2>&1; then
            ifconfig "$ifname" >/dev/null 2>&1 && return 0
        fi
        [ -d "/sys/class/net/$ifname" ] && return 0
        return 1
    }

    _detect_default_iface() {
        local ifc
        if command -v ip >/dev/null 2>&1; then
            ifc="$(ip route get 8.8.8.8 2>/dev/null | awk '/dev/ {for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')"
            [ -n "$ifc" ] && printf "%s" "$ifc" && return 0
            ifc="$(ip route 2>/dev/null | awk '/default/ {for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')"
            [ -n "$ifc" ] && printf "%s" "$ifc" && return 0
        fi
        if command -v route >/dev/null 2>&1; then
            ifc="$(route -n 2>/dev/null | awk '/^0.0.0.0/ {print $8; exit}')"
            [ -n "$ifc" ] && printf "%s" "$ifc" && return 0
        fi
        if [ -d /sys/class/net ]; then
            for i in /sys/class/net/*; do
                i="$(basename "$i")"
                [ "$i" = "lo" ] && continue
                printf "%s" "$i"
                return 0
            done
        fi
        return 1
    }

    # ---- нормализация аргумента интерфейсов ----
    if [ -z "${ifaces_raw+x}" ] || [ -z "$ifaces_raw" ]; then
        MODE="all"
    else
        local tmp
        tmp="$(printf "%s" "$ifaces_raw" | tr ',' ' ' | tr -s '[:space:]' ' ' | sed 's/^ *//;s/ *$//')"
        case "$tmp" in
            all|ALL) MODE="all" ;;
            auto|AUTO|detect|DETECT) MODE="detect" ;;
            *) MODE="user" ;;
        esac
    fi

    L_IFACES=""
    valid_ifaces=""
    invalid_ifaces=""

    if [ "$MODE" = "all" ]; then
        _log "interface: ALL (без -o)"
    elif [ "$MODE" = "detect" ]; then
        _log "interface: auto-detect"
        detected="$(_detect_default_iface)"
        if [ -n "$detected" ]; then
            _log "detected iface: $detected"
            L_IFACES="$detected"
        else
            _err "auto-detect не нашёл интерфейс — переключаемся в ALL"
            MODE="all"
        fi
    else
        _split_and_normalize_ifaces "$ifaces_raw"
        for f in $L_IFACES; do
            if _is_iface_exists "$f"; then
                valid_ifaces="$valid_ifaces $f"
            else
                invalid_ifaces="$invalid_ifaces $f"
            fi
        done
        valid_ifaces="$(printf "%s" "$valid_ifaces" | sed 's/^ *//;s/ *$//')"
        invalid_ifaces="$(printf "%s" "$invalid_ifaces" | sed 's/^ *//;s/ *$//')"

        if [ -n "$valid_ifaces" ]; then
            L_IFACES="$valid_ifaces"
            _log "interface(s) validated: $L_IFACES"
            [ -n "$invalid_ifaces" ] && _err "пропущены несуществующие: $invalid_ifaces"
        else
            _err "Ни один из указанных интерфейсов не существует: $invalid_ifaces"
            detected="$(_detect_default_iface)"
            if [ -n "$detected" ]; then
                _log "Используем auto-detected iface: $detected"
                L_IFACES="$detected"
            else
                _err "auto-detect не дал результата — переключаемся в ALL"
                MODE="all"; L_IFACES=""
            fi
        fi
    fi

    _log "port_preference='${port_preference}', protocol_choice='${proto_choice}', dpi_ports='${dpi_ports}'"

    # ---- подготовка цепочек iptables ----
    iptables -t nat -nL NAT_DPI >/dev/null 2>&1 || iptables -t nat -N NAT_DPI
    # ensure OUTPUT jumps to NAT_DPI
    if ! iptables -t nat -C OUTPUT -j NAT_DPI >/dev/null 2>&1; then
        iptables -t nat -I OUTPUT 1 -j NAT_DPI
    fi
    _log "NAT_DPI ready"

    if [ -z "${_IPTABLES_DPI_TUNNEL_MANGLE_INIT-}" ]; then
        iptables -t mangle -nL MANGLE_APP >/dev/null 2>&1 || iptables -t mangle -N MANGLE_APP
        if ! iptables -t mangle -C OUTPUT -j MANGLE_APP >/dev/null 2>&1; then
            iptables -t mangle -A OUTPUT -j MANGLE_APP
        fi
        _IPTABLES_DPI_TUNNEL_MANGLE_INIT=1
        _log "MANGLE_APP ready"
    fi

    # ---- парсим uid_file один раз и формируем список "uid:app" ----
    if [ ! -r "$uid_file" ]; then
        _err "Файл uid не доступен: $uid_file"
        return 1
    fi
    UID_PAIRS=""
    while IFS='=' read -r app uid || [ -n "$app" ]; do
        [ -z "$app" ] && continue
        if ! printf "%s" "$uid" | grep -Eq '^[0-9]+'; then
            continue
        fi
        UID_PAIRS="$UID_PAIRS $uid:$app"
    done < "$uid_file"
    UID_PAIRS="$(printf "%s" "$UID_PAIRS" | sed 's/^ *//;s/ *$//')"

    if [ -z "$UID_PAIRS" ]; then
        _err "Нет валидных UID в файле: $uid_file"
        return 1
    fi

    # ---- mangle исключения (uid -> RETURN) ----
    for pair in $UID_PAIRS; do
        uid_only=${pair%%:*}
        if ! iptables -t mangle -C MANGLE_APP -m owner --uid-owner "$uid_only" -j RETURN >/dev/null 2>&1; then
            iptables -t mangle -I MANGLE_APP -m owner --uid-owner "$uid_only" -j RETURN
        fi
        _log "mangle-исключение для UID $uid_only"
    done

    # ---- протоколы ----
    local protos
    case "$proto_choice" in
        tcp) protos="tcp" ;;
        udp) protos="udp" ;;
        tcp_udp) protos="tcp udp" ;;
        *) _err "Неизвестный protocol_choice '$proto_choice' — по умолчанию tcp"; protos="tcp" ;;
    esac

    # ---- вспомогательная функция добавления правила (idempotent, POSIX) ----
    local RULES_ADDED=0
    _add_nat_rule() {
        local _uid _proto _extra extra_str IPTABLES_BIN IPT_WAIT tried variant iface v command_check command_add
        _uid="$1"; shift
        _proto="$1"; shift
        _extra="$*"

        # normalize extra tokens (keeps "--dport 80" as single string with space)
        extra_str="$_extra"

        # find iptables binary
        IPTABLES_BIN="$(command -v iptables 2>/dev/null || true)"
        for p in "$IPTABLES_BIN" /system/bin/iptables /system/xbin/iptables /sbin/iptables /usr/sbin/iptables; do
            [ -n "$p" ] && [ -x "$p" ] && { IPTABLES_BIN="$p"; break; }
        done
        [ -z "$IPTABLES_BIN" ] && IPTABLES_BIN="iptables"

        # detect -w support
        IPT_WAIT=""
        if "$IPTABLES_BIN" -w -L >/dev/null 2>&1; then IPT_WAIT="-w"; fi

        # variants order from strict to permissive
        variants="p_mproto_mowner p_mowner_mproto mowner_p_mproto p_mowner only_mowner"

        # build iface list: empty string => treat as ALL (no -o)
        if [ -z "$L_IFACES" ] && [ "$MODE" = "all" ]; then
            iface_list=""
        else
            iface_list="$L_IFACES"
        fi

        # try each iface (or once for ALL) and each variant
        for iface in $iface_list; do
            for v in $variants; do
                case "$v" in
                    p_mproto_mowner)
                        proto_part="-p $_proto -m $_proto -m owner --uid-owner $_uid"
                        ;;
                    p_mowner_mproto)
                        proto_part="-p $_proto -m owner --uid-owner $_uid -m $_proto"
                        ;;
                    mowner_p_mproto)
                        proto_part="-m owner --uid-owner $_uid -p $_proto -m $_proto"
                        ;;
                    p_mowner)
                        proto_part="-p $_proto -m owner --uid-owner $_uid"
                        ;;
                    only_mowner)
                        proto_part="-m owner --uid-owner $_uid"
                        ;;
                    *)
                        proto_part="-p $_proto -m $_proto -m owner --uid-owner $_uid"
                        ;;
                esac

                # build check and add commands as strings (eval will execute)
                command_check="$IPTABLES_BIN $IPT_WAIT -t nat -C NAT_DPI"
                command_add="$IPTABLES_BIN $IPT_WAIT -t nat -A NAT_DPI"

                if [ -n "$iface" ]; then
                    command_check="$command_check -o $iface"
                    command_add="$command_add -o $iface"
                fi

                command_check="$command_check $proto_part $extra_str -j DNAT --to-destination 127.0.0.1:$dest_port"
                command_add="$command_add $proto_part $extra_str -j DNAT --to-destination 127.0.0.1:$dest_port"

                tried="$command_check"
                # if rule exists -> ok
                eval "$command_check" >/dev/null 2>&1 && return 0

                # try add
                if eval "$command_add" >/dev/null 2>&1; then
                    RULES_ADDED=$((RULES_ADDED+1))
                    _log "Добавлено (uid=$_uid proto=$_proto iface=${iface:-ALL} variant=$v)"
                    return 0
                else
                    _err "variant failed: iface=${iface:-ALL} variant=$v"
                fi
            done
        done

        # If iface_list was empty (ALL) we tried nothing in loop above - handle ALL case here
        if [ -z "$iface_list" ]; then
            for v in $variants; do
                case "$v" in
                    p_mproto_mowner)
                        proto_part="-p $_proto -m $_proto -m owner --uid-owner $_uid"
                        ;;
                    p_mowner_mproto)
                        proto_part="-p $_proto -m owner --uid-owner $_uid -m $_proto"
                        ;;
                    mowner_p_mproto)
                        proto_part="-m owner --uid-owner $_uid -p $_proto -m $_proto"
                        ;;
                    p_mowner)
                        proto_part="-p $_proto -m owner --uid-owner $_uid"
                        ;;
                    only_mowner)
                        proto_part="-m owner --uid-owner $_uid"
                        ;;
                    *)
                        proto_part="-p $_proto -m $_proto -m owner --uid-owner $_uid"
                        ;;
                esac

                command_check="$IPTABLES_BIN $IPT_WAIT -t nat -C NAT_DPI $proto_part $extra_str -j DNAT --to-destination 127.0.0.1:$dest_port"
                command_add="$IPTABLES_BIN $IPT_WAIT -t nat -A NAT_DPI $proto_part $extra_str -j DNAT --to-destination 127.0.0.1:$dest_port"

                eval "$command_check" >/dev/null 2>&1 && return 0

                if eval "$command_add" >/dev/null 2>&1; then
                    RULES_ADDED=$((RULES_ADDED+1))
                    _log "Добавлено (uid=$_uid proto=$_proto ALL variant=$v)"
                    return 0
                else
                    _err "variant failed (ALL): variant=$v"
                fi
            done
        fi

        # try alternative iptables binaries (legacy/nft/busybox)
        alt_bins="iptables-legacy iptables-nft /system/bin/iptables /system/xbin/iptables /sbin/iptables /bin/iptables"
        for alt in $alt_bins; do
            [ "$alt" = "$IPTABLES_BIN" ] && continue
            if command -v "$alt" >/dev/null 2>&1 || [ -x "$alt" ]; then
                IPTABLES_BIN="$alt"
                if "$IPTABLES_BIN" -w -L >/dev/null 2>&1; then IPT_WAIT="-w"; else IPT_WAIT=""; fi
                _log "Пробую альтернативный бинарь: $IPTABLES_BIN"
                # try same variants for ALL
                for v in $variants; do
                    case "$v" in
                        p_mproto_mowner)
                            proto_part="-p $_proto -m $_proto -m owner --uid-owner $_uid"
                            ;;
                        p_mowner_mproto)
                            proto_part="-p $_proto -m owner --uid-owner $_uid -m $_proto"
                            ;;
                        mowner_p_mproto)
                            proto_part="-m owner --uid-owner $_uid -p $_proto -m $_proto"
                            ;;
                        p_mowner)
                            proto_part="-p $_proto -m owner --uid-owner $_uid"
                            ;;
                        only_mowner)
                            proto_part="-m owner --uid-owner $_uid"
                            ;;
                        *)
                            proto_part="-p $_proto -m $_proto -m owner --uid-owner $_uid"
                            ;;
                    esac

                    command_check="$IPTABLES_BIN $IPT_WAIT -t nat -C NAT_DPI $proto_part $extra_str -j DNAT --to-destination 127.0.0.1:$dest_port"
                    command_add="$IPTABLES_BIN $IPT_WAIT -t nat -A NAT_DPI $proto_part $extra_str -j DNAT --to-destination 127.0.0.1:$dest_port"

                    eval "$command_check" >/dev/null 2>&1 && return 0

                    if eval "$command_add" >/dev/null 2>&1; then
                        RULES_ADDED=$((RULES_ADDED+1))
                        _log "Добавлено (uid=$_uid proto=$_proto ALL via $IPTABLES_BIN variant=$v)"
                        return 0
                    fi
                done
            fi
        done

        # final simple fallback (busybox-like)
        if [ -z "$L_IFACES" ] && [ "$MODE" = "all" ]; then
            if ! iptables -t nat -C NAT_DPI -m owner --uid-owner "$_uid" $extra_str -j DNAT --to-destination 127.0.0.1:"$dest_port" >/dev/null 2>&1; then
                if iptables -t nat -A NAT_DPI -m owner --uid-owner "$_uid" $extra_str -j DNAT --to-destination 127.0.0.1:"$dest_port" >/dev/null 2>&1; then
                    RULES_ADDED=$((RULES_ADDED+1))
                    _log "Добавлено (fallback simple uid=$_uid)"
                    return 0
                fi
            else
                return 0
            fi
        else
            for iface in $L_IFACES; do
                if ! iptables -t nat -C NAT_DPI -o "$iface" -m owner --uid-owner "$_uid" $extra_str -j DNAT --to-destination 127.0.0.1:"$dest_port" >/dev/null 2>&1; then
                    if iptables -t nat -A NAT_DPI -o "$iface" -m owner --uid-owner "$_uid" $extra_str -j DNAT --to-destination 127.0.0.1:"$dest_port" >/dev/null 2>&1; then
                        RULES_ADDED=$((RULES_ADDED+1))
                        _log "Добавлено (fallback simple iface=$iface uid=$_uid)"
                        return 0
                    fi
                else
                    return 0
                fi
            done
        fi

        _err "НЕ удалось добавить правило для UID=$_uid proto=$_proto ($_extra). Проверьте iptables на целевой машине."
        return 1
    }

    # ---- если нужно все порты для каждого uid ----
    if [ "$port_preference" = "1" ]; then
        _log "Применяем DNAT на ВСЕ порты ($protos)"
        for pair in $UID_PAIRS; do
            uid_only=${pair%%:*}
            for proto in $protos; do
                _add_nat_rule "$uid_only" "$proto" ""
            done
            _log "NAT для UID $uid_only -> 127.0.0.1:$dest_port (all ports)"
        done
        _log "Готово (all ports). Правил добавлено: $RULES_ADDED"
        return 0
    fi

    # ---- нормализуем dpi_ports -> csv ----
    local ports_csv
    ports_csv="$(printf "%s" "$dpi_ports" | tr ' ' ',' | sed 's/,,*/,/g' | sed 's/^,//;s/,$//')"
    _log "Нормализованные порты: $ports_csv"

    # ---- анализируем на наличие диапазонов и поддержку multiport ----
    local parts_count=0 has_range=0
    local OLDIFS="$IFS"
    IFS=','
    for token in $ports_csv; do
        token="$(printf "%s" "$token" | tr -d '[:space:]')"
        [ -z "$token" ] && continue
        parts_count=$((parts_count+1))
        case "$token" in
            *-*) has_range=1 ;;
            *) if ! printf "%s" "$token" | grep -Eq '^[0-9]+$'; then parts_count=$((parts_count-1)); _err "Неверный токен портов: $token"; fi ;;
        esac
    done
    IFS="$OLDIFS"

    # проверка multiport
    local multiport_supported=0
    if iptables -t nat -I NAT_DPI 1 -p tcp -m multiport --dports 1 -j RETURN >/dev/null 2>&1; then
        iptables -t nat -D NAT_DPI -p tcp -m multiport --dports 1 -j RETURN >/dev/null 2>&1 || true
        multiport_supported=1
    fi

    _log "parts_count=$parts_count, has_range=$has_range, multiport_supported=$multiport_supported"

    local use_multiport=0
    if [ "$has_range" -eq 0 ] && [ "$parts_count" -le 15 ] && [ "$multiport_supported" -eq 1 ]; then
        use_multiport=1
    fi

    if [ "$use_multiport" -eq 1 ]; then
        local ports_for_multi
        ports_for_multi="$(printf "%s" "$ports_csv" | tr -d '[:space:]')"
        _log "Используем multiport для: $ports_for_multi"
        for pair in $UID_PAIRS; do
            uid_only=${pair%%:*}
            for proto in $protos; do
                if [ -z "$L_IFACES" ] && [ "$MODE" = "all" ]; then
                    if ! iptables -t nat -C NAT_DPI -p "$proto" -m owner --uid-owner "$uid_only" -m multiport --dports "$ports_for_multi" -j DNAT --to-destination 127.0.0.1:"$dest_port" >/dev/null 2>&1; then
                        iptables -t nat -A NAT_DPI -p "$proto" -m owner --uid-owner "$uid_only" -m multiport --dports "$ports_for_multi" -j DNAT --to-destination 127.0.0.1:"$dest_port" >/dev/null 2>&1 && RULES_ADDED=$((RULES_ADDED+1)) || _err "Ошибка multiport (uid=$uid_only proto=$proto)"
                    fi
                else
                    for iface in $L_IFACES; do
                        if ! iptables -t nat -C NAT_DPI -o "$iface" -p "$proto" -m owner --uid-owner "$uid_only" -m multiport --dports "$ports_for_multi" -j DNAT --to-destination 127.0.0.1:"$dest_port" >/dev/null 2>&1; then
                            iptables -t nat -A NAT_DPI -o "$iface" -p "$proto" -m owner --uid-owner "$uid_only" -m multiport --dports "$ports_for_multi" -j DNAT --to-destination 127.0.0.1:"$dest_port" >/dev/null 2>&1 && RULES_ADDED=$((RULES_ADDED+1)) || _err "Ошибка multiport iface=$iface (uid=$uid_only proto=$proto)"
                        fi
                    done
                fi
            done
            _log "NAT для UID $uid_only ->127.0.0.1:$dest_port (multiport: $ports_for_multi)"
        done
    else
        if [ "$multiport_supported" -ne 1 ]; then
            if command -v notification_send >/dev/null 2>&1; then
                notification_send support "Ваше устройство ограничено в возможностях, для полного запуска понадобится больше времени. Установите iptables с поддержкой multiport."
            else
                _err "notification_send не найдена — уведомление пропущено"
            fi
            _log "Fallback: добавляем правила по-элементно"
        else
            _log "Добавляем правила по-элементно (диапазоны или частей >15)"
        fi

        IFS=','
        for token in $ports_csv; do
            token="$(printf "%s" "$token" | tr -d '[:space:]')"
            [ -z "$token" ] && continue
            if printf "%s" "$token" | grep -Eq '^[0-9]+-[0-9]+$'; then
                start="${token%%-*}"
                end="${token##*-}"
                if ! printf "%s" "$start" | grep -Eq '^[0-9]+$' || ! printf "%s" "$end" | grep -Eq '^[0-9]+$'; then
                    _err "Неверный диапазон '$token' — пропускаем"; continue
                fi
                if [ "$start" -gt "$end" ]; then local tmp="$start"; start="$end"; end="$tmp"; fi
                for pair in $UID_PAIRS; do
                    uid_only=${pair%%:*}
                    for proto in $protos; do
                        _add_nat_rule "$uid_only" "$proto" "--dport ${start}:${end}"
                    done
                    _log "NAT для UID $uid_only ->127.0.0.1:$dest_port (range ${start}:${end})"
                done
            elif printf "%s" "$token" | grep -Eq '^[0-9]+$'; then
                for pair in $UID_PAIRS; do
                    uid_only=${pair%%:*}
                    for proto in $protos; do
                        _add_nat_rule "$uid_only" "$proto" "--dport $token"
                    done
                    _log "NAT для UID $uid_only ->127.0.0.1:$dest_port (port $token)"
                done
            else
                _err "Пропущен некорректный токен: '$token'"
            fi
        done
        IFS="$OLDIFS"
    fi

    _log "Правила DNAT добавлены в NAT_DPI. Всего правил добавлено: $RULES_ADDED"
    if [ -n "$L_IFACES" ]; then
        _log "Использованные интерфейсы: $L_IFACES"
    else
        _log "Использованы: ALL (нет опции -o)"
    fi
    [ -n "$invalid_ifaces" ] && _err "Пропущены несуществующие интерфейсы: $invalid_ifaces"

    return 0
}


operax_main() {
  # --- корректное определение каталога модуля и параметры --------------------
  operax_MODDIR="/data/adb/modules/ZDT-D/"
  operax_SNI_FILE="$operax_MODDIR/working_folder/sni_service"
  operax_START_PORT=11145
  operax_MAX_SERVICES=10

  # --- подготовка папки логов
  operax_LOGDIR="$operax_MODDIR/log"
  operax_LOG_PROXY_PY="${operax_LOG_PROXY_PY:-$operax_LOGDIR/transparent_proxy.log}"

  # --- выбрать адрес прослушки в зависимости от tether_whitelist (по умолчанию 0)
  if [ "${tether_whitelist:-0}" = "1" ]; then
    operax_listen_addr="0.0.0.0"
  else
    operax_listen_addr="127.0.0.1"
  fi

  # --- Счётчики / аккумуляторы
  operax_count=0        # сколько сервисов запущено
  operax_ports=""       # список портов для python (через запятую)

  # Создаём папку логов, если нет
  if [ ! -d "$operax_LOGDIR" ]; then
    mkdir -p "$operax_LOGDIR" 2>/dev/null || true
  fi

  # --- 1) Запуск BYEDPI (полная команда) для первоначального подъёма
  run_detach "ciadpi-zdt -i $operax_listen_addr -p 10190 -x 1 -n m.vk.com vk.com max.ru gosuslugi.ru sun6-20.userapi.com ok.ru online.sberbank.ru -Qr -f6+nr -d2 -d11 -f9+hm -o3 -t7 -a1  -As -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1 -As -r5 -Mh -s1:5+s -s3:7+sm -a1 -o1 -d1 -a1" "$operax_LOGDIR/bye_opera.log"

  # даём немного времени на старт ciadpi (не менее чем раньше было)
  sleep 5

  # --- 2) Читаем файл SNI_FILE построчно и создаём отдельный лог на каждую строку
  while IFS= read -r operax_line || [ -n "$operax_line" ]; do
    operax_trimmed=$(printf '%s' "$operax_line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')

    # игнорируем пустые и комментированные строки
    case "$operax_trimmed" in
      ''|\#*) continue;;
    esac

    # превысили лимит сервисов?
    if [ "$operax_count" -ge "$operax_MAX_SERVICES" ]; then
      break
    fi

    operax_port=$((operax_START_PORT + operax_count))

    # безопасное имя для файла лога (предполагается, что sanitize_sni_for_filename определена)
    operax_sni_safe=$(sanitize_sni_for_filename "$operax_trimmed")
    operax_logfile="$operax_LOGDIR/opera_proxy${operax_count}_${operax_sni_safe}.log"

    # создаём файл лога (touch) чтобы >> всегда работал
    touch "$operax_logfile" 2>/dev/null || true

    # запускаем opera-proxy (предполагается, что run_detach определена)
    run_detach "opera-proxy \
  -bind-address 127.0.0.1:$operax_port \
  -socks-mode \
  -cafile /system/bin/ca.bundle \
  -fake-SNI $operax_trimmed \
  -bootstrap-dns https://127.0.0.1:863,https://1.1.1.1/dns-query,tls://9.9.9.9:853,https://1.1.1.3/dns-query,https://8.8.8.8/dns-query,https://dns.google/dns-query,https://security.cloudflare-dns.com/dns-query,https://fidelity.vm-0.com/q,https://wikimedia-dns.org/dns-query,https://dns.adguard-dns.com/dns-query,https://dns.quad9.net/dns-query,https://doh.cleanbrowsing.org/doh/adult-filter/ \
  -api-user-agent 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36' \
  -certchain-workaround \
  -country EU \
  -init-retries 15 \
  -init-retry-interval 30s \
  -server-selection fastest \
  -server-selection-dl-limit 204800 \
  -server-selection-timeout 30s \
  -proxy socks5://127.0.0.1:10190" "$operax_logfile"

    # небольшая задержка между стартами проксей
    sleep 5

    # Добавляем порт в список для python (формат: 11145,11146,...)
    if [ -z "$operax_ports" ]; then
      operax_ports="$operax_port"
    else
      operax_ports="$operax_ports,$operax_port"
    fi

    operax_count=$((operax_count + 1))

  done < "$operax_SNI_FILE"

  # --- 3) Если нашли хотя бы один порт — запускаем встроенную проверку (python) и ждём до 30 секунд
  if [ -n "$operax_ports" ]; then
    # Вычисляем минимально требуемое количество рабочих серверов:
    # если запущено 1-2 сервера -> min_ok=1, иначе -> min_ok=2 (по вашему правилу)
    if [ "$operax_count" -le 2 ]; then
      min_ok=1
    else
      min_ok=2
    fi

    # максимальное время ожидания в секундах и интервал перезапуска проверки
    max_wait=30
    interval=5
    waited=0
    ok=0

    # Лог для попыток проверки
    PY_LOG="$operax_LOGDIR/socks_check.log"

    echo "Запускаю проверку SOCKS5 портов: $operax_ports (min_ok=$min_ok). Жду до ${max_wait}s..." >>"$PY_LOG"

    while [ $waited -lt $max_wait ]; do
      # запускаем встроенный Python-скрипт (берёт PORTS и MIN_OK из окружения)
      # NOTE: скрипт основан на вашем коде, но принимает PORTS/MIN_OK через os.environ
      PORTS="$operax_ports" MIN_OK="$min_ok" python3 - <<'PY' >>"$PY_LOG" 2>&1
# Встроенный проверщик, основан на вашем коде.
import os
import sys
import time
import socket
import struct
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed

ERROR_CODES = {
    0x01: "general SOCKS server failure",
    0x02: "connection not allowed by ruleset",
    0x03: "Network unreachable",
    0x04: "Host unreachable",
    0x05: "Connection refused",
    0x06: "TTL expired",
    0x07: "Command not supported",
    0x08: "Address type not supported"
}

def recv_exact(sock, n):
    data = b''
    while len(data) < n:
        try:
            chunk = sock.recv(n - len(data))
            if not chunk:
                return None
            data += chunk
        except socket.timeout:
            return None
        except Exception:
            return None
    return data

def check_socks5_health(socks_host, socks_port, socks_user=None, socks_pass=None, timeout=5):
    s = None
    local_ping = None
    internet_ping = None
    try:
        start_local = time.time()
        s = socket.create_connection((socks_host, socks_port), timeout=timeout)
        s.settimeout(timeout)
        local_ping = (time.time() - start_local) * 1000

        methods = [0x00]
        s.sendall(struct.pack("!BB", 0x05, len(methods)) + bytes(methods))
        data = recv_exact(s, 2)
        if not data or len(data) < 2:
            return False, local_ping, None, "Short METHODS reply"

        ver, method = struct.unpack("!BB", data)
        if ver != 0x05:
            return False, local_ping, None, f"Bad version: {ver}"

        if method == 0xFF:
            return False, local_ping, None, "No acceptable authentication methods"

        target_host = "8.8.8.8"
        target_port = 53

        try:
            ipv4 = socket.inet_aton(target_host)
            atyp = 0x01
            addr_part = ipv4
        except Exception:
            atyp = 0x03
            host_b = target_host.encode('idna')
            addr_part = struct.pack("!B", len(host_b)) + host_b

        port_part = struct.pack("!H", int(target_port))
        req = struct.pack("!BBB", 0x05, 0x01, 0x00) + struct.pack("!B", atyp) + addr_part + port_part

        start_internet = time.time()
        s.sendall(req)

        resp = recv_exact(s, 4)
        if not resp or len(resp) < 4:
            return False, local_ping, None, "Short CONNECT reply"

        ver_r, rep, rsv, atyp_r = struct.unpack("!BBBB", resp)
        if ver_r != 0x05:
            return False, local_ping, None, f"Bad version in reply: {ver_r}"

        if rep != 0x00:
            return False, local_ping, None, f"CONNECT failed: {ERROR_CODES.get(rep, f'unknown error (0x{rep:02x})')}"

        if atyp_r == 0x01:
            if recv_exact(s, 4) is None:
                return False, local_ping, None, "Short IPv4 in reply"
        elif atyp_r == 0x03:
            ln_b = recv_exact(s, 1)
            if not ln_b:
                return False, local_ping, None, "Short domain length"
            ln = struct.unpack("!B", ln_b)[0]
            if recv_exact(s, ln) is None:
                return False, local_ping, None, "Short domain in reply"
        elif atyp_r == 0x04:
            if recv_exact(s, 16) is None:
                return False, local_ping, None, "Short IPv6 in reply"
        else:
            return False, local_ping, None, f"Unknown address type in reply: {atyp_r}"

        if recv_exact(s, 2) is None:
            return False, local_ping, None, "Short port in reply"

        internet_ping = (time.time() - start_internet) * 1000
        return True, local_ping, internet_ping, None

    except socket.timeout:
        return False, local_ping, internet_ping, "Connection timeout"
    except ConnectionRefusedError:
        return False, local_ping, internet_ping, "Connection refused"
    except Exception as e:
        return False, local_ping, internet_ping, f"Error: {e}"
    finally:
        try:
            if s:
                s.close()
        except Exception:
            pass

def parse_ports(ports_str):
    items = (p.strip() for p in ports_str.split(','))
    ports = set()
    for it in items:
        if not it:
            continue
        if '-' in it:
            try:
                a, b = it.split('-', 1)
                start = int(a); end = int(b)
                if start > end:
                    start, end = end, start
                if end - start > 1000:
                    continue
                for p in range(max(1, start), min(65535, end) + 1):
                    ports.add(p)
            except Exception:
                continue
        else:
            try:
                p = int(it)
                if 1 <= p <= 65535:
                    ports.add(p)
            except Exception:
                continue
    return sorted(ports)

def check_all(ports, timeout=5, parallel=True):
    host = "127.0.0.1"
    results = []
    working = 0
    basic_ping = None
    if parallel:
        max_workers = min(10, len(ports))
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = {executor.submit(check_socks5_health, host, p, None, None, timeout): p for p in ports}
            for future in futures:
                try:
                    healthy, lp, ip, err = future.result()
                except Exception:
                    continue
                results.append((futures[future], healthy, lp, ip, err))
                if healthy:
                    working += 1
    else:
        for p in ports:
            healthy, lp, ip, err = check_socks5_health(host, p, None, None, timeout)
            results.append((p, healthy, lp, ip, err))
            if healthy:
                working += 1
    return working, results

def main_env():
    ports_str = os.environ.get('PORTS', '')
    min_ok = int(os.environ.get('MIN_OK', '1'))
    timeout = int(os.environ.get('SCK_TIMEOUT', '5'))
    parallel = True

    if not ports_str:
        print("No ports provided via PORTS env", file=sys.stderr)
        sys.exit(3)

    ports = parse_ports(ports_str)
    if not ports:
        print("No valid ports parsed", file=sys.stderr)
        sys.exit(4)

    working, results = check_all(ports, timeout=timeout, parallel=parallel)
    print(f"CHECK RESULT: working={working} total={len(ports)} min_ok={min_ok}")
    for p, healthy, lp, ip, err in results:
        status = "OK" if healthy else f"FAIL ({err})"
        print(f"  {p}: {status}")

    if working >= min_ok:
        sys.exit(0)
    else:
        sys.exit(2)

if __name__ == '__main__':
    main_env()
PY
      rc=$?
      if [ $rc -eq 0 ]; then
        echo "$(date +%s) — Требуемое число рабочих серверов достигнуто (min_ok=$min_ok)." >>"$PY_LOG"
        ok=1
        break
      else
        echo "$(date +%s) — Проверка вернула $rc, ещё жду..." >>"$PY_LOG"
      fi

      sleep $interval
      waited=$((waited + interval))
    done

    if [ $ok -ne 1 ]; then
      echo "Внимание: требуемое количество рабочих серверов не достигнуто за ${max_wait}s. Продолжаю." >>"$PY_LOG"
    fi

    # --- 4) kill ciadpi (порт 10190) встроенно (никаких внешних скриптов)
    KILL_LOG="$operax_LOGDIR/bye_opera_kill.log"
    echo "Ищу PID по порту 10190 и пытаюсь убить (лог: $KILL_LOG)..." >>"$KILL_LOG"
    PID=$(su -c "ss -ltnp 2>/dev/null | grep ':10190' | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n1")
    if [ -z "$PID" ]; then
      echo "PID для порта 10190 не найден." >>"$KILL_LOG"
    else
      echo "Найден PID: $PID. Убиваю..." >>"$KILL_LOG"
      su -c "kill -9 $PID" >>"$KILL_LOG" 2>&1 || echo "Не удалось отправить сигнал kill -9 $PID" >>"$KILL_LOG"
      sleep 0.5
      if su -c "ss -ltnp 2>/dev/null | grep -q ':10190'"; then
        echo "Порт 10190 всё ещё занят после kill." >>"$KILL_LOG"
      else
        echo "Порт 10190 освобождён." >>"$KILL_LOG"
      fi
    fi

    # --- 5) Запускаем ciadpi в упрощённом режиме (через run_detach)
    run_detach "ciadpi-zdt -i $operax_listen_addr -p 10190 -x 1 -s 43690:2:43690" "$operax_LOGDIR/bye_opera.log"
  else
    echo "Нет корректных SNI в $operax_SNI_FILE — ничего не запущено." >&2
  fi

  # --- 6) После этого запускаем python-прокси (как у вас было ранее), если есть порты
  if [ -n "$operax_ports" ]; then
    if [ "${tether_whitelist:-0}" = "1" ]; then
      operax_listen_addr="0.0.0.0"
    else
      operax_listen_addr="127.0.0.1"
    fi

    run_detach "python3 /system/bin/zdt-d_tanspanent_proxy-sosck5.py --listen-addr $operax_listen_addr --listen-port 11260 --socks-host 127.0.0.1 --socks-port $operax_ports --mode tcp --max-conns 600 --idle-timeout 5000 --connect-timeout 30 --enable-http2 --web-socket --certificate /system/bin/ca.bundle" "$operax_LOG_PROXY_PY"
  fi

  # --- iptables: если tether_whitelist = 1 — добавляем PREROUTING для редиректа,
  # и делаем POSTROUTING более общий, иначе прежнее поведение.
  if [ "${tether_whitelist:-0}" = "1" ]; then
    iptables -t nat -I PREROUTING -p tcp -j REDIRECT --to-ports 11260
    iptables -t nat -A POSTROUTING -p tcp --dport 11260 -j MASQUERADE
  else
    iptables -t nat -A POSTROUTING -p tcp -d 127.0.0.1 --dport 11260 -j MASQUERADE
  fi

}


sbox_gen_from_file() {
  sbox_input="$1"
  sbox_out="" sbox_status=0 sbox_ret=0 sbox_first_char=""

  # аргументы / существование файла
  if [ -z "$sbox_input" ]; then
    return 1
  fi
  if [ ! -f "$sbox_input" ]; then
    return 2
  fi

  # пустой файл -> тихо выходим
  if [ ! -s "$sbox_input" ]; then
    return 0
  fi

  sbox_out="$sbox_input"

  # определяем, JSON ли (первый значимый символ { или [), иначе ищем vless://
  sbox_first_char=$(awk '/\S/ { print substr($0, match($0,/[^[:space:]]/),1); exit }' "$sbox_out" 2>/dev/null || printf "")
  if printf "%s" "$sbox_first_char" | grep -qE '^[\{\[]$'; then
    sbox_has_config=1
  elif grep -qF 'vless://' "$sbox_out" 2>/dev/null; then
    sbox_has_config=1
  else
    # нет ни JSON ни vless -> тихо выходим
    unset sbox_input sbox_out sbox_first_char sbox_has_config sbox_status sbox_ret
    return 0
  fi

  # проверка синтаксиса sing-box (выполняется как есть)
  sing-box check -c "$sbox_out"
  sbox_status=$?

  if [ $sbox_status -ne 0 ]; then
    # неверный конфиг -> уведомление и выход
    notification_send error "Не верно сгенерированный конфиг sing-box, запуск отклонен!"
    sbox_ret=4
    unset sbox_input sbox_out sbox_first_char sbox_has_config sbox_status
    return $sbox_ret
  fi

  # конфиг валиден -> запускаем службы (run_detach и load_config_dpi_tunnel предполагаются определёнными)
  run_detach "python3 /system/bin/zdt-d_tanspanent_proxy-sosck5.py --listen-addr 127.0.0.1 --listen-port 11261 --socks-host 127.0.0.1 --socks-port 11160 --mode tcp --max-conns 600 --idle-timeout 5000 --connect-timeout 30 --enable-http2 --certificate /system/bin/ca.bundle" "/data/adb/modules/ZDT-D/log/opera_sing-box.log"
  run_detach "sing-box run -c '$sbox_out'" "/data/adb/modules/ZDT-D/log/sing_box.log"

  load_config_dpi_tunnel "$UID_OUTPUT_FILE12" "11261"

  # очистка локальных переменных
  unset sbox_input sbox_out sbox_first_char sbox_has_config sbox_status sbox_ret

  return 0
}


BYEDPI_RESTART() {
  #  local DPI_SCRIPT_DIR=$(dirname "$0")
  #  local DPI_CONFIG_FILE="/data/adb/modules/ZDT-D/working_folder/ciadpi.conf"
  local DPI_CONFIG_FILE="$2"
  local DPI_PORT="$1"

  # счётчик вызовов функции (глобальная переменная; если не задана — начинаем с 0)
  BYEDPI_CALL_COUNT=${BYEDPI_CALL_COUNT:-0}
  local DPI_LOG_FILE="/data/adb/modules/ZDT-D/log/ciadpi${BYEDPI_CALL_COUNT}.log"
  # подготовили имя лога для текущего вызова — увеличим счётчик для следующего
  BYEDPI_CALL_COUNT=$((BYEDPI_CALL_COUNT + 1))

  # убиваем процесс, слушающий DPI_PORT
  local DPI_PID
  DPI_PID=$(netstat -nlp 2>/dev/null | grep ":$DPI_PORT" | awk '{print $7}' | cut -d'/' -f1)
  [ -n "$DPI_PID" ] && kill -9 "$DPI_PID"

  # читаем дополнительные опции из конфигурации
  local DPI_CONFIG
  DPI_CONFIG=$(cat "$DPI_CONFIG_FILE")

  # запускаем ciadpi с базовыми параметрами и опциями из файла, логи — в DPI_LOG_FILE
  set -- $DPI_CONFIG
  ciadpi-zdt -i 127.0.0.1 -p "$DPI_PORT" -x 2 -E "$@" >> "$DPI_LOG_FILE" 2>&1 &
}


restarting_dpi_tunnel() {
    iptables -t nat -I NAT_DPI 1 -d 127.0.0.1 -j RETURN
    iptables -t mangle -C MANGLE_APP -d 127.0.0.1 -j RETURN 2>/dev/null || iptables -t mangle -I MANGLE_APP 1 -d 127.0.0.1 -j RETURN
    any=0
    for i in 0 1 2; do
        # достаём значение переменной dpi_tunnel_<i>
        eval enabled=\"\$dpi_tunnel_${i}\"
        if [ "$enabled" = "1" ]; then
            any=1
            # вызываем check_and_restart_dpi_tunnel с соответствующими параметрами
            eval "check_and_restart_dpi_tunnel \"\$PID${i}\" \"\$PORTS${i}\" \"\$IP${i}\" \"\$MODE${i}\" \"\$PROFILE${i}\" \"\$BUFFER_SIZE${i}\" \"\$DOH_SERVER${i}\" \"\$TTL${i}\" \"\$AUTO_TTL${i}\" \"\$MIN_TTL${i}\" \"\$DESYNC_ATTACKS${i}\" \"\$WSIZE${i}\" \"\$WSFACTOR${i}\" \"\$SPLIT_AT_SNI${i}\" \"\$SPLIT_POSITION${i}\" \"\$WRONG_SEQ${i}\" \"\$BUILTIN_DNS${i}\" \"\$BUILTIN_DNS_IP${i}\" \"\$BUILTIN_DNS_PORT${i}\""
        elif [ "$enabled" = "0" ]; then
            : # ничего не делаем, отключён
        else
            echo "Некорректное значение для dpi_tunnel_${i} или его нет"
        fi
    done

    if [ "$any" -eq 0 ]; then
        echo "Скрипт выключен"
        exit 0
    fi
}


# ========================================================================
# Скрипт инициализации и настройки сервисов с проверкой конфигураций,
# профилей безопасности и обновлений.
#
# Основные этапы:
# 1. Ожидание загрузки устройства и проверка конфликтующих модулей.
# 2. Настройка SELinux: переключение из режима Permissive в Enforcing.
# 3. Отправка уведомлений и чтение конфигурационных параметров.
# 4. Обработка UID-файлов и запуск профилей zapret с настройкой iptables.
# 5. Настройка DPI туннелей.
# 6. Проверка обновлений и перезапуск DPI tunnel, возврат SELinux в исходное состояние.
# ========================================================================

##########################################
# Этап 1: Инициализация устройства
##########################################

# Ожидание завершения загрузки устройства
boot_completed


# Проверка наличия конфликтующего модуля, который может помешать работе
check_modules

# Ожидание разблокировки экрана пользователем
# start_system_final   # Если требуется дождаться разблокировки, раскомментируйте эту строку
# Не нужна ибо используется в условии первого запуска модуля

##########################################
# Этап 2: Настройка SELinux
##########################################

# Получение текущего состояния SELinux (например, Enforcing или Permissive)
SELINUX_STATE=$(getenforce)

# Если SELinux работает в режиме Enforcing"", переключаем его в "Permissive"
if [ "$SELINUX_STATE" = "Enforcing" ]; then
    setenforce 0
fi

##########################################
# Этап 3: Инициализация запуска системы
##########################################

# Отправка уведомления (тоста) о начале процесса запуска системы
notification_toast_start

# Чтение и загрузка конфигурационных параметров, необходимых для дальнейшей работы
read_params "$SETTING_START_PARAMS"
read_params "$SETTING_START_PARAMS_PATCH"

# Сетевые оптимизации, применяем при условии
if [ "$sysctl_patch" = "1" ]; then
    sysct_optimization
fi

# Отключение Captive portal 
if [ "$captive_portal" = "1" ]; then
    settings put global captive_portal_mode 0
fi


# Проверка разрешения на запуск сервисов (например, может быть проверка настроек или лицензии)
start_stop_service

# Запуск nfqws с использованием конфигурации (закомментировано, не требуется)
# start_zapret


# Загрузка конфигурации для DPI туннеля
start_load_config
##########################################
# Этап 4: Обработка UID-файлов и запуск профилей zapret
##########################################

# Первичная обработка UID-файлов (основной и дополнительный)
for i in 0 1 2; do
    eval "unified_processing \"\$UID_OUTPUT_FILES${i}\" \"\$UID_INPUT_FILES${i}\""
done


# Блок 1 — обёртка вызовов unified_processing для 0,1,2
i=0
while [ "$i" -le 2 ]; do
  # получить значения переменных UID_OUTPUT_FILE_BYE{i} и UID_INPUT_FILE_BYE{i}
  eval "out=\$UID_OUTPUT_FILE_BYE$i"
  eval "in=\$UID_INPUT_FILE_BYE$i"

  unified_processing "$out" "$in"

  i=$((i+1))
done
# unified_processing "$ZAPRET_DPI_TUNNEL_OUT" "$ZAPRET_DPI_TUNNEL"
# unified_processing "$DPI_TUNNEL_ZAPRET_OUT" "$DPI_TUNNEL_ZAPRET"
# --- Обработка профилей zapret ---
# Здесь для каждого профиля выполняется:
#   1. Проверка наличия входного файла с UID (если файл не пустой, то профиль активен)
#   2. Вызов функции start_zapret с уникальным идентификатором и конфигурацией
#   3. Обработка файла с UID через функцию unified_processing
#   4. Применение настроек iptables через функцию iptables_zapret_default_full


# Unified script:
# 1) unified_processing всех файлов
# 2) full_id_iptables для Wi‑Fi/Mobile + iptables_zapret_default_full для UID‑профилей
# 3) в конце — единожды start_zapret для каждого кода 200..204

# --- Stage 1: unified_processing ---

# 1.1 Wi‑Fi и Mobile профили N=0..5
for N in 0 1 2 3 4 5; do
  IN_WIFI="$WORKING_FOLDER/zapret_wifi${N}"
  OUT_WIFI="$WORKING_FOLDER/zapret_out_wifi${N}"
  echo "Stage1: unified_processing Wi‑Fi N=$N"
  unified_processing "$OUT_WIFI" "$IN_WIFI"

  IN_MOB="$WORKING_FOLDER/zapret_mobile${N}"
  OUT_MOB="$WORKING_FOLDER/zapret_out_mobile${N}"
  echo "Stage1: unified_processing Mobile N=$N"
  unified_processing "$OUT_MOB" "$IN_MOB"
done

# 1.2 UID‑профили i=2..7
for i in 2 3 4 5 6 7 8 9 10 11 12; do
  idx=$((i - 1))
  eval IN_UID=\$UID_INPUT_FILE$i
  eval OUT_UID=\$UID_OUTPUT_FILE$i

  echo "Stage1: unified_processing UID profile $idx (i=$i)"
  if [ -s "$IN_UID" ]; then
    unified_processing "$OUT_UID" "$IN_UID"
  else
    echo "WARN: \$IN_UID ($IN_UID) пуст, пропускаем"
    : > "$OUT_UID"
  fi
done

# --- Stage 2: iptables наложение ---

# 2.1 full_id_iptables для Wi‑Fi/Mobile
for N in 0 1 2 3 4 5; do
  PORT=$((200 + N))

  OUT_WIFI="$WORKING_FOLDER/zapret_out_wifi${N}"
  PARAM_WIFI="$WORKING_FOLDER/zapret_params_wifi${N}"
  if [ -s "$OUT_WIFI" ]; then
    read -r IFACE_WIFI < "$PARAM_WIFI"; IFACE_WIFI="${IFACE_WIFI%$'\r'}"
    echo "LOG: full_id_iptables Wi‑Fi N=$N → port=$PORT iface=$IFACE_WIFI"
    full_id_iptables "full" "$PORT" "$IFACE_WIFI" "$OUT_WIFI"
  fi

  OUT_MOB="$WORKING_FOLDER/zapret_out_mobile${N}"
  PARAM_MOB="$WORKING_FOLDER/zapret_params_mobile${N}"
  if [ -s "$OUT_MOB" ]; then
    read -r IFACE_MOB < "$PARAM_MOB"; IFACE_MOB="${IFACE_MOB%$'\r'}"
    echo "LOG: full_id_iptables Mobile N=$N → port=$PORT iface=$IFACE_MOB"
    full_id_iptables "full" "$PORT" "$IFACE_MOB" "$OUT_MOB"
  fi
done

ONLY_200=0
if [ "$full_system" = "1" ] || [ "$alternativel" = "1" ]; then
  ONLY_200=1
fi

# 2.2 iptables_zapret_default_full для UID‑профилей i=2..6
for i in 2 3 4 5 6 7 8; do
  # если привязка к ONLY_200 — пропускаем все кроме i=2
  if [ "${ONLY_200:-0}" = "1" ] && [ "$i" != "2" ]; then
    continue
  fi

  code=$((200 + i - 2))
  eval OUT_UID=\$UID_OUTPUT_FILE$i

  if [ -s "$OUT_UID" ]; then
    echo "LOG: UID profile i=$i: iptables_zapret_default_full code=$code"
    iptables_zapret_default_full "$code" "$OUT_UID"
  fi
done

# --- Stage 3: запускаем start_zapret **единожды** для каждого кода 200..204 ---
for N in 0 1 2 3 4 6; do
  if [ "$ONLY_200" = "1" ] && [ "$N" != "0" ]; then
    # пропускаем все кроме N=0
    continue
  fi

  code=$((200 + N))
  cfg_var="CONFIG_ZAPRET$N"
  eval CONFIG=\$$cfg_var

  # ... (остальной код без изменений)
  i=$((N + 2))
  eval OUT_UID=\$UID_OUTPUT_FILE$i
  OUT_WIFI="$WORKING_FOLDER/zapret_out_wifi${N}"
  OUT_MOB="$WORKING_FOLDER/zapret_out_mobile${N}"

  if [ -s "$OUT_UID" ]; then
    INPUT_FILE="$OUT_UID"
  elif [ -s "$OUT_WIFI" ]; then
    INPUT_FILE="$OUT_WIFI"
  elif [ -s "$OUT_MOB" ]; then
    INPUT_FILE="$OUT_MOB"
  else
    INPUT_FILE=""
  fi

  if [ -n "$INPUT_FILE" ]; then
    echo "LOG: start_zapret code=$code config='\$CONFIG' input='$INPUT_FILE'"
    start_zapret "$code" "$CONFIG" "$INPUT_FILE"
  else
    echo "LOG: код=$code — нет непустых файлов, пропускаем start_zapret"
  fi
done

# Дополнительная настройка iptables для мобильного, с передачей параметров
# Массив профилей (0–2 пока не активны)
PROFILES="3 4"  # сюда можно дописать: 0 1 2

for p in $PROFILES; do
    # Получаем имя переменной с файлом для текущего профиля
    # например, IP_FILE3 или IP_FILE4
    file_var="IP_FILE${p}"
    IP_FILE=$(eval echo "\$$file_var")

    if [ -s "$IP_FILE" ]; then
        # Номер профиля в iptables = 200 + профиль
        mobile_iptables_beta "$((200 + p))" "$IP_FILE"
    else
        echo "не запущен"
    fi
done


AGR_ZAPRET_TXT=/storage/emulated/0/ZDT-D
ZAPRETUID_CHECK=/data/adb/modules/ZDT-D/working_folder/zapret_uid_out5

# Запускаем только если оба флага равны "0"
if [ "${full_system:-0}" = "0" ] && [ "${alternativel:-0}" = "0" ]; then
  # Существующая проверка файлов оставлена без изменений
  if [ -n "$(find "$AGR_ZAPRET_TXT" -maxdepth 1 -type f -name '*.txt' -print -quit)" ] || [ -s "$ZAPRETUID_CHECK" ]; then
    start_zapret_agressive
  fi
fi


apply_zdt_rules


##########################################
# Этап 5: Настройка DPI туннелей через iptables
##########################################

# ByeDpi
# Запуск службы bye dpi при условии что выходной файл не пуст
#!/system/bin/sh
# Блок 2 — проверка файлов и запуск BYEDPI/загрузки конфигурации для 0,1,2
# Переменные внутри цикла имеют приставку byezdtbye_ чтобы исключить конфликты
byezdtbye_i=0
while [ "$byezdtbye_i" -le 2 ]; do
  # получаем значение переменной UID_OUTPUT_FILE_BYE0/1/2 в нашу уникальную переменную
  eval "byezdtbye_out=\${UID_OUTPUT_FILE_BYE${byezdtbye_i}}"

  # порт: 1125, 1126, 1127
  byezdtbye_port=$((1134 + byezdtbye_i))

  # путь к конфигу
  byezdtbye_conf="/data/adb/modules/ZDT-D/working_folder/ciadpi${byezdtbye_i}.conf"

  # проверяем — непустой ли файл
  if [ -n "$byezdtbye_out" ] && [ -s "$byezdtbye_out" ]; then
    echo "Запуск Bye dpi для индекса ${byezdtbye_i} (порт ${byezdtbye_port})"
    # вызываем внешние функции/команды, аргументы передаются позиционно
    BYEDPI_RESTART "$byezdtbye_port" "$byezdtbye_conf"
    load_config_dpi_tunnel "$byezdtbye_out" "$byezdtbye_port" "tcp_udp"
  else
    # если переменная пуста, подставляем понятное сообщение
    if [ -z "$byezdtbye_out" ]; then
      byezdtbye_display="<переменная UID_OUTPUT_FILE_BYE${byezdtbye_i} не установлена>"
    else
      byezdtbye_display="$byezdtbye_out"
    fi
    echo "Bye dpi не запущен для индекса ${byezdtbye_i} (файл ${byezdtbye_display} пуст или отсутствует)"
  fi

  byezdtbye_i=$((byezdtbye_i + 1))
done



# DPI tunnel 0:
# Если переменная dpi_tunnel_0 равна "1", то туннель включён и запускается соответствующая функция;
# если "0" — выводится сообщение, что туннель выключен;
# в остальных случаях выводится сообщение об ошибке.
if [ "$dpi_tunnel_0" = "1" ]; then
    echo "DPI tunnel 0 включён, запуск"
    load_config_dpi_tunnel "$UID_OUTPUT_FILES0" "$PORTS0" "$PROTOCOL_TRAFFIC0"
elif [ "$dpi_tunnel_0" = "0" ]; then
    echo "DPI tunnel 0 выключен."
else
    echo "Некорректное значение для dpi_tunnel_0 или его нет"
fi

# DPI tunnel 1:
if [ "$dpi_tunnel_1" = "1" ]; then
    echo "DPI tunnel 1 включён, запуск"
    load_config_dpi_tunnel "$UID_OUTPUT_FILES1" "$PORTS1" "$PROTOCOL_TRAFFIC1"
elif [ "$dpi_tunnel_1" = "0" ]; then
    echo "DPI tunnel 1 выключен."
else
    echo "Некорректное значение для dpi_tunnel_1 или его нет"
fi

# DPI tunnel 2:
if [ "$dpi_tunnel_2" = "1" ]; then
    echo "DPI tunnel 1 включён, запуск"
    load_config_dpi_tunnel "$UID_OUTPUT_FILES2" "$PORTS2" "$PROTOCOL_TRAFFIC2"
elif [ "$dpi_tunnel_1" = "0" ]; then
    echo "DPI tunnel 1 выключен."
else
    echo "Некорректное значение для dpi_tunnel_1 или его нет"
fi

dns_redirect

# 1) Проверка файла 9
if [ -s "$UID_OUTPUT_FILE9" ]; then
    echo "Запуск OperaVPN (UID_OUTPUT_FILE9)"
    operax_main
    operax_called=1
    load_config_dpi_tunnel "$UID_OUTPUT_FILE9" "11260" "tcp"
else
    echo "OperaVPN не запущен (UID_OUTPUT_FILE9 пустой)"
fi

# Вариант 1: точное совпадение с ожидаемым UUID (используется по умолчанию)
if [ "x$TRIGGER" = "x$EXPECTED" ]; then
    if [ -s "$UID_OUTPUT_FILE10" ]; then
        # вызвать operax_main только если он ещё не был вызван
        if [ "x$operax_called" != "x1" ]; then
            echo "Запуск OperaVPN (UID_OUTPUT_FILE10)"
            operax_main
            operax_called=1
        else
            echo "operax_main уже был вызван ранее — пропускаем повторный запуск"
        fi
        load_config_dpi_tunnel "$UID_OUTPUT_FILE10" "11260" "tcp"# "rmnet_data+ rmnet_data0 rmnet_data1 rmnet_data2 rmnet_data3 rmnet_data4 rmnet_data5 rmnet_data6 rmnet_data7 rmnet_data8 rmnet_data9 rmnet_data10 rmnet_ipa0"
        #load_config_dpi_tunnel "$UID_OUTPUT_FILE10" "10190" "udp"
    else
        echo "UID_OUTPUT_FILE10 пустой — при установленном триггере operax_main не вызываем"
    fi
else
    echo "'$TRIGGER' — проверка UID_OUTPUT_FILE10 пропущена"
fi

#[ -n "${UID_OUTPUT_FILE12:-}" ] && sbox_gen_from_file "${SINGBOXXED_CONFIG}"

##########################################
# Этап 6: Финальные действия и обновления
##########################################

# Отправка уведомления о запуске всех сервисов
notification_send info "$MSG_SUCCESSFUL_LAUNCH"

setsid $BIN_DIR/script/zdt-d_totall_traffic.sh >/dev/null >/dev/null 2>&1 &
setsid $BIN_DIR/script/zdt-d_update.sh </dev/null >/dev/null 2>&1 &


[ "${batterysaver:-0}" = "1" ] && setsid "$BIN_DIR/script/zdt-d_cpu_control.sh" </dev/null >/dev/null 2>&1 &


# Если исходное состояние SELinux было Enforcing, возвращаем его обратно
if [ "$SELINUX_STATE" = "Enforcing" ]; then
    setenforce 1
fi

# Проверка работоспособности сервиса DPI tunnel
restarting_dpi_tunnel


echo "Завершение"
exit 0
