#!/system/bin/sh




# Путь к файлу конфигурации
DAM="/data/adb/modules/ZDT-D"
WORKING_FOLDER="$DAM/working_folder"
BIN_DIR="$DAM/system/bin"
TMP_DIR="/data/local/tmp"

CONFIG_FILE="$WORKING_FOLDER/config0"
CONFIG_FILE1="$WORKING_FOLDER/config1"
UID_INPUT_FILE="$WORKING_FOLDER/uid_program0"
UID_INPUT_FILE1="$WORKING_FOLDER/uid_program1"
UID_INPUT_FILE2="$WORKING_FOLDER/zapret_uid0"
UID_INPUT_FILE3="$WORKING_FOLDER/zapret_uid1"
UID_INPUT_FILE4="$WORKING_FOLDER/zapret_uid2"
UID_INPUT_FILE5="$WORKING_FOLDER/zapret_uid3"
UID_INPUT_FILE6="$WORKING_FOLDER/zapret_uid4"
UID_INPUT_FILE7="$WORKING_FOLDER/zapret_uid5"
UID_INPUT_FILE_BYE="$WORKING_FOLDER/bye_dpi"
UID_OUTPUT_FILE="$WORKING_FOLDER/uid_out0"
UID_OUTPUT_FILE1="$WORKING_FOLDER/uid_out1"
UID_OUTPUT_FILE2="$WORKING_FOLDER/zapret_uid_out0"
UID_OUTPUT_FILE3="$WORKING_FOLDER/zapret_uid_out1"
UID_OUTPUT_FILE4="$WORKING_FOLDER/zapret_uid_out2"
UID_OUTPUT_FILE5="$WORKING_FOLDER/zapret_uid_out3"
UID_OUTPUT_FILE6="$WORKING_FOLDER/zapret_uid_out4"
UID_OUTPUT_FILE7="$WORKING_FOLDER/zapret_uid_out5"
UID_OUTPUT_FILE_BYE="$WORKING_FOLDER/bye_dpi_out"
ZAPRET_CONFIG_FILES_DATA="$WORKING_FOLDER/zapret_config"
ZAPRET_DPI_TUNNEL="$WORKING_FOLDER/zapret_dpi"
DPI_TUNNEL_ZAPRET="$WORKING_FOLDER/dpi_zapret"
ZAPRET_DPI_TUNNEL_OUT="$WORKING_FOLDER/zapret_dpi_out"
DPI_TUNNEL_ZAPRET_OUT="$WORKING_FOLDER/dpi_zapret_out"
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
SOURCE_FILE_ICON_MB="$DAM/icon.png"
SOURCE_FILE_ICON_MB1="$DAM/icon1.png"
SOURCE_FILE_ICON_MB2="$DAM/icon2.png"
TARGET_DIRECTORY_ICON_MB="$TMP_DIR"
SETTING_START_PARAMS="$WORKING_FOLDER/params"
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
# ip адреса
IP_FILE3="$WORKING_FOLDER/ip_ranges3.txt"
IP_FILE4="$WORKING_FOLDER/ip_ranges4.txt"
# Префикс для всех уведомлений
PREFIX="ZDT-D:"







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
    MSG_NOTIFICATION_START="Привет, начинаю процедуру настройки модуля."
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
    
else
    
    MSG_ERROR_MODULE_ZAPRET="Hello, you have the zapret module installed, please remove it."
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



















####################################
# Отпраляем тост о начале запуска модуля
####################################
notification_toast_start() {
    /system/bin/am start -a android.intent.action.MAIN -e toasttext "ZDT-D $MSG_START_PROCESS " -n bellavita.toast/.MainActivity
}
















####################################
# Функция проверки наличия интернета.
# Параметры:
#   $1 - задержка между попытками (сек)
#   $2 - максимальное число попыток
####################################
internetcheck() {
    local delay="$1"
    local max_attempts="$2"
    local attempt=0

    while [ "$attempt" -lt "$max_attempts" ]; do
        if ping -c 1 8.8.8.8 >/dev/null 2>&1; then
            internet_echeck_status=1
            echo "Интернет есть. internet_echeck_status=1"
            return 0
        else
            echo "Интернет недоступен. Попытка $((attempt + 1)) из $max_attempts."
            attempt=$((attempt + 1))
            sleep "$delay"
        fi
    done

    internet_echeck_status=0
    echo "Интернет недоступен после $max_attempts попыток. internet_echeck_status=0"
}

























####################################
# Проверка наличия обновления
# Если обновления есть, отправляем уведомление с ссылкой 
####################################
check_update() {
    # Если не удалось изменить права, не прерываем работу
    chmod 777 "$CURL_BINARIES" || true

    # Функция для получения значений из module.prop
    get_prop_value() {
        grep "^$1=" "$MODULE_PROP" | cut -d'=' -f2
    }

    # Проверка наличия module.prop
    if [ ! -f "$MODULE_PROP" ]; then
        echo "Файл module.prop не найден. Пропускаем проверку обновлений."
        return 0
    fi

    # Скачивание файла JSON с данными обновления
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$UPDATE_JSON_URL" -o "$TEMP_JSON"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$UPDATE_JSON_URL" -O "$TEMP_JSON"
    elif [ -x "$CURL_BINARIES" ]; then
        $CURL_BINARIES -k -fsSL "$UPDATE_JSON_URL" -o "$TEMP_JSON"
    else
        echo "curl или wget не найдены. Пропускаем проверку обновлений."
        return 0
    fi

    # Проверка, что JSON успешно загружен
    if [ ! -f "$TEMP_JSON" ]; then
        echo "Не удалось загрузить файл обновлений. Пропускаем проверку обновлений."
        return 0
    fi

    # Проверка наличия утилиты jq
    if ! command -v jq >/dev/null 2>&1; then
        echo "Утилита jq не установлена. Пропускаем проверку обновлений."
        rm -f "$TEMP_JSON"
        return 0
    fi

    # Извлечение данных из module.prop
    LOCAL_VERSION=$(get_prop_value "version")
    LOCAL_VERSION_CODE=$(get_prop_value "versionCode")

    # Извлечение данных из JSON с помощью jq
    REMOTE_VERSION=$(jq -r '.version' "$TEMP_JSON")
    REMOTE_VERSION_CODE=$(jq -r '.versionCode' "$TEMP_JSON")
    NEW_UPDATE_URL=$(jq -r '.zipUrl' "$TEMP_JSON")
    CHANGELOG_URL=$(jq -r '.changelog' "$TEMP_JSON")

    # Удаление временного файла JSON
    rm -f "$TEMP_JSON"

    # Проверка корректности извлечённых данных
    if [ -z "$LOCAL_VERSION_CODE" ] || [ -z "$REMOTE_VERSION_CODE" ]; then
        echo "Ошибка извлечения версии. Пропускаем проверку обновлений."
        return 0
    fi

    # Сравнение версий
    if [ "$LOCAL_VERSION_CODE" -lt "$REMOTE_VERSION_CODE" ]; then
        MESSAGE="Доступно обновление!: Текущая версия: $LOCAL_VERSION Новая версия: $REMOTE_VERSION."

        # Отправка уведомления с помощью команды cmd
        if command -v cmd >/dev/null 2>&1; then
            su -lp 2000 -c "cmd notification post -i file:///data/local/tmp/icon1.png -I file:///data/local/tmp/icon2.png -S messaging --conversation 'ZDT-D' --message '$MESSAGE' --message 'Ссылка для скачивания: $NEW_UPDATE_URL' -t 'ZDT-D' 'UpdateCheck' 'Проверка обновлений завершена.'" >/dev/null 2>&1
        else
            echo "Команда cmd не найдена. Уведомление не может быть отправлено."
            echo -e "$MESSAGE"
        fi
    else
        echo "Обновлений нет."
    fi
}






















####################################
# Загрузаем переменные из файла params
####################################
read_params() {
    
    # Чтение файла "params" и присваивание значений переменным
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
            "offonservice") offonservice="$value" ;;
            "notification") notification="$value" ;;
            "alternativel") alternativel="$value" ;;
            "zapretconfig") zapretconfig="$value" ;;
        esac
    done < "$SETTING_START_PARAMS"
    
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
PORTS=(20 21 22 23 25 53 67 68 69 80 110 119 123 135 137 138 139 143 161 162 389 443 3306 5222 7000 7500 7999 5432 5900 6379 6667 8080 8443 8888 1194 51820 500 4500 3478 5060 5061 6881 6883 6940 6970 6999 43 79 445 465 514 587 993 995 1521 1701 1723 1935 3268 3269 9200 11211 25565 27015 27017)

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
  args+=(--new)

  args+=(--filter-udp=${p})
  args+=(--dpi-desync=fake,multidisorder)
  args+=(--dpi-desync-repeats=8)
  args+=(--dpi-desync-fake-quic=${BASE}/quic_initial_facebook_com_quiche.bin)
  args+=(--new)

  args+=(--filter-tcp=${p})
  args+=(--dpi-desync=fake)
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

  ##############################################################################
  # 6) russia-youtube.txt по TCP (fake, split2)
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-youtube.txt)
  args+=(--dpi-desync=fake,split2)
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
  args+=(--dpi-desync=fake,split2)
  args+=(--dpi-desync-fooling=badseq)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  args+=(--filter-tcp=${p},${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--hostlist=${BASE}/myhostlist.txt)
  args+=(--dpi-desync=fake,split2)
  args+=(--dpi-desync-split-seqovl=1)
  args+=(--dpi-desync-split-pos=2)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--dpi-desync-skip-nosni=1)
  args+=(--dpi-desync-autottl)
  args+=(--new)

  ##############################################################################
  # 8) TCP 443 + russia-blacklist (fake+multidisorder, repeats=11)
  ##############################################################################
  args+=(--filter-tcp=${p})
  args+=(--hostlist=${BASE}/russia-blacklist.txt)
  args+=(--dpi-desync=fake,multidisorder)
  args+=(--dpi-desync-split-pos=1,midsld)
  args+=(--dpi-desync-repeats=11)
  args+=(--dpi-desync-fooling=md5sig)
  args+=(--dpi-desync-fake-tls=${BASE}/tls_clienthello_www_google_com.bin)
  args+=(--new)

done

# Запускаем nfqws со всеми динамическими стратегиями
exec nfqws "${args[@]}" > /dev/null 2>&1 &
}





AGR_ZAPRET_TXT=/storage/emulated/0/ZDT-D
ZAPRETUID_CHECK=/data/adb/modules/ZDT-D/working_folder/zapret_uid_out5
[ -n "$(find "$AGR_ZAPRET_TXT" -maxdepth 1 -type f -name '*.txt' -print -quit)" ] || [ -s "$ZAPRETUID_CHECK" ] && start_zapret_agressive













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
            port) eval "PORT${suffix}=\"$value\"" ;;
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
        esac
    done < "$config_file"
}





















start_load_config() {
    
    # Вызов функции для загрузки конфигураций
    if [ "$dpi_tunnel_0" = "1" ]; then
        echo "DPI tunnel 0 включён"
        load_config "$CONFIG_FILE" ""
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
    fi

    # Используем basename входного файла как ключ
    file_key=$(basename "$input_file")
    old_hash=$(grep "^${file_key}=" "$SHA256_FLAG_FILE" | cut -d'=' -f2)
    new_hash=$(calculate_sha256 "$input_file")
    
    # Обновляем флаговый файл для этого файла
    grep -v "^${file_key}=" "$SHA256_FLAG_FILE" > "$SHA256_FLAG_FILE.tmp"
    echo "${file_key}=${new_hash}" >> "$SHA256_FLAG_FILE.tmp"
    mv "$SHA256_FLAG_FILE.tmp" "$SHA256_FLAG_FILE"

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

    add_inbound_rule_uid_old() {
        local uid="$1"
        local rule="-m owner --uid-owner $uid -j NFQUEUE --queue-num $QUEUE --queue-bypass"
        local added=1
        for table in mangle nat; do
            for chain in PREROUTING INPUT; do
                if chain_supported "$table" "$chain"; then
                    echo "Попытка добавить OLD inbound правило для UID $uid в таблице $table, цепочке $chain..."
                    if add_rule "$table" "$chain" "$rule"; then
                        added=0
                        break 2
                    fi
                fi
            done
        done
        return $added
    }

    add_inbound_rule_uid_new() {
        local uid="$1"
        local rule="-m owner --uid-owner $uid -m mark ! --mark $MARK/$MARK -j NFQUEUE --queue-num $QUEUE --queue-bypass"
        local added=1
        for table in mangle nat; do
            for chain in PREROUTING INPUT; do
                if chain_supported "$table" "$chain"; then
                    echo "Попытка добавить NEW inbound правило для UID $uid в таблице $table, цепочке $chain..."
                    if add_rule "$table" "$chain" "$rule"; then
                        added=0
                        break 2
                    fi
                fi
            done
        done
        return $added
    }

    add_outbound_rule_uid_old() {
        local uid="$1"
        local rule="-m owner --uid-owner $uid -j NFQUEUE --queue-num $QUEUE --queue-bypass"
        local added=1
        for table in mangle nat; do
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
        for table in mangle nat; do
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
        for table in mangle nat; do
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
        for table in mangle nat; do
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

        local INDIVIDUAL_INBOUND_TOTAL=0
        local INDIVIDUAL_INBOUND_SUCCESS=0
        local INDIVIDUAL_OUTBOUND_TOTAL=0
        local INDIVIDUAL_OUTBOUND_SUCCESS=0

        while IFS='=' read -r app_name uid; do
            [ -z "$app_name" ] && continue
            [ -z "$uid" ] && continue
            echo "Обработка приложения \"$app_name\" (UID: $uid)..."
            
            INDIVIDUAL_INBOUND_TOTAL=$((INDIVIDUAL_INBOUND_TOTAL+1))
            if add_inbound_rule_uid_old "$uid"; then
                echo "OLD inbound правило успешно добавлено для UID $uid."
                INDIVIDUAL_INBOUND_SUCCESS=$((INDIVIDUAL_INBOUND_SUCCESS+1))
            else
                echo "OLD inbound правило не удалось для UID $uid, пробую NEW inbound..."
                if add_inbound_rule_uid_new "$uid"; then
                    echo "NEW inbound правило успешно добавлено для UID $uid."
                    INDIVIDUAL_INBOUND_SUCCESS=$((INDIVIDUAL_INBOUND_SUCCESS+1))
                else
                    echo "Не удалось добавить индивидуальное inbound правило для UID $uid."
                fi
            fi

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

        if [ "$INDIVIDUAL_INBOUND_SUCCESS" -lt 1 ]; then
            echo "Индивидуальные inbound правила не установлены, пробую добавить общий inbound fallback..."
            if ! add_common_inbound_rule_fallback; then
                echo "❌ Не удалось добавить ни индивидуальное, ни общий inbound правило!"
            fi
        fi

        if [ "$INDIVIDUAL_OUTBOUND_SUCCESS" -lt 1 ]; then
            echo "Индивидуальные outbound правила не установлены, пробую добавить общий outbound fallback..."
            if ! add_common_outbound_rule_fallback; then
                echo "❌ Не удалось добавить ни индивидуальное, ни общий outbound правило!"
            fi
        fi

        echo "✅ Завершено. Индивидуальные правила: inbound $INDIVIDUAL_INBOUND_SUCCESS/$INDIVIDUAL_INBOUND_TOTAL, outbound $INDIVIDUAL_OUTBOUND_SUCCESS/$INDIVIDUAL_OUTBOUND_TOTAL."
    fi
}




















####################################
# Добавляю правило ip (IPv4/IPv6) с опциональной поддержкой ipset
####################################
# --- Глобальные счётчики успехов/провалов для статистики ---
success_count=0
fail_count=0

mobile_iptables_beta() {
    # --- 1) Проверка флагов full_system и alternativel (как было) ---
    full_system=${full_system:-0}
    alternativel=${alternativel:-0}
    if [ "$full_system" -eq 1 ] || [ "$alternativel" -eq 1 ]; then
        echo "Пропускаем mobile_iptables_beta: full_system=$full_system, alternativel=$alternativel"
        return 0
    fi

    # --- 2) Проверка входных параметров (порт + файл) ---
    if [ -z "$1" ] || [ -z "$2" ]; then
        echo "Ошибка: Не заданы параметры (порт и файл с IP-адресами)."
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

    # --- 3) Утилиты и вспомогательная функция добавления правил ---
    # Единая функция add_rule() как в первом скрипте
    add_rule() {
        # $1 = цепочка (PREROUTING|INPUT|OUTPUT|POSTROUTING)
        # $2 = команда iptables (iptables или ip6tables)
        $2 -t mangle -A "$1" -d "$IP" -j NFQUEUE --queue-num "$port" 2>/dev/null
        return $?
    }

    # --- 4) Подготовка прогресса и уведомление о старте обработки ---
    total=$(grep -cvE '^\s*(#|$)' "$ip_file" 2>/dev/null)
    processed=0
    thresholds="10 25 50 75 100"
    next_threshold=$(echo $thresholds | cut -d' ' -f1)
    notification_send processing "$ip_file" 0

    echo "Начали обработку файла $ip_file — всего $total адресов"

    # --- 5) Блок с ipset (если он есть) ---
    if command -v ipset >/dev/null 2>&1; then
        echo "ipset найден — используем наборы вместо множества правил"

        set_v4="iplist_v4_$port"
        set_v6="iplist_v6_$port"
        ipset destroy "$set_v4" 2>/dev/null
        ipset destroy "$set_v6" 2>/dev/null
        ipset create  "$set_v4" hash:ip family inet   2>/dev/null
        ipset create  "$set_v6" hash:ip family inet6  2>/dev/null

        while IFS= read -r IP; do
            [ -z "$IP" ] || [ "${IP#\#}" != "$IP" ] && continue
            if echo "$IP" | grep -q ':'; then
                ipset add "$set_v6" "$IP" 2>/dev/null
            else
                ipset add "$set_v4" "$IP" 2>/dev/null
            fi
        done < "$ip_file"

        # --- здесь метод из 1-го: ровно 4 правила (v4 PREROUTING/OUTPUT + v6 PREROUTING/OUTPUT) ---
        iptables  -t mangle -A PREROUTING  -m set --match-set "$set_v4" dst -j NFQUEUE --queue-num "$port"
        iptables  -t mangle -A OUTPUT      -m set --match-set "$set_v4" dst -j NFQUEUE --queue-num "$port"
        ip6tables -t mangle -A PREROUTING  -m set --match-set "$set_v6" dst -j NFQUEUE --queue-num "$port"
        ip6tables -t mangle -A OUTPUT      -m set --match-set "$set_v6" dst -j NFQUEUE --queue-num "$port"

        notification_send processing "$ip_file" 100
        echo "Готово: ipset-наборы созданы, правила NFQUEUE на порт $port"
        return 0
    fi

    # --- 6) Старый режим: перебор по IP (без ipset) с retry-логикой ---
    echo "ipset не найден — переходим к перебору по IP (старый режим)"
    while IFS= read -r IP; do
        [ -z "$IP" ] || [ "${IP#\#}" != "$IP" ] && continue

        ingress_success=0
        egress_success=0

        if echo "$IP" | grep -q ':'; then
            IPT_CMD="ip6tables"; FAMILY="IPv6"
        else
            IPT_CMD="iptables";  FAMILY="IPv4"
        fi

        echo "Обработка адреса: $IP ($FAMILY)"

        # ——— ВХОД: PREROUTING → INPUT (как в 1-м) с retry из 2-го ———
        if add_rule PREROUTING "$IPT_CMD"; then
            echo "  ВХОД: правило добавлено в PREROUTING"
            ingress_success=1
        else
            notification_send error "Retry PREROUTING для $IP"
            sleep 3
            if add_rule PREROUTING "$IPT_CMD"; then
                echo "  ВХОД: PREROUTING после retry"
                ingress_success=1
            else
                echo "  ВХОД: PREROUTING не сработал, пробуем INPUT..."
                if add_rule INPUT "$IPT_CMD"; then
                    echo "  ВХОД: правило добавлено в INPUT"
                    ingress_success=1
                else
                    notification_send error "Ошибка INPUT для $IP"
                fi
            fi
        fi

        # ——— ИСХОД: OUTPUT → POSTROUTING (аналогично) ———
        if add_rule OUTPUT "$IPT_CMD"; then
            echo "  ИСХОД: правило добавлено в OUTPUT"
            egress_success=1
        else
            notification_send error "Retry OUTPUT для $IP"
            sleep 3
            if add_rule OUTPUT "$IPT_CMD"; then
                echo "  ИСХОД: OUTPUT после retry"
                egress_success=1
            else
                echo "  ИСХОД: OUTPUT не сработал, пробуем POSTROUTING..."
                if add_rule POSTROUTING "$IPT_CMD"; then
                    echo "  ИСХОД: правило добавлено в POSTROUTING"
                    egress_success=1
                else
                    notification_send error "Ошибка POSTROUTING для $IP"
                fi
            fi
        fi

        # ——— Учёт статистики ———
        if [ "$ingress_success" -eq 1 ] && [ "$egress_success" -eq 1 ]; then
            success_count=$((success_count + 1))
        else
            fail_count=$((fail_count + 1))
        fi

        # ——— Обновление прогресса ———
        processed=$((processed + 1))
        pct=$(( processed * 100 / total ))
        if [ "$pct" -ge "$next_threshold" ]; then
            notification_send processing "$ip_file" "$next_threshold"
            thresholds="${thresholds#* }"
            if [ -n "$thresholds" ]; then
                next_threshold=$(echo $thresholds | cut -d' ' -f1)
            else
                next_threshold=101
            fi
        fi

    done < "$ip_file"

    # ——— Завершаем и даём время «уведомлениям» отработать ———
    echo "Готово: обработано $processed/$total адресов, успехов: $success_count, ошибок: $fail_count"
    sleep 5
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
    local port="$2"
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

    if ! netstat -tuln | grep -q ":$port .*LISTEN"; then
        echo "Порт $port не прослушивается. Перезапуск процесса..."

        if /system/bin/dpitunnel-cli \
            --pid "$pid" \
            --daemon \
            --ca-bundle-path "$CA_BUNDLE_PATH" \
            --ip "$ip" \
            --port "$port" \
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
            --builtin-dns-port "$builtin_dns_port"; then

            echo "DPI Tunnel на порту $port успешно перезапущен."
            
        else
            echo "Не удалось запустить DPI Tunnel на порту $port."
            
        fi
    fi
}














load_config_dpi_tunnel() {
    # Параметры функции:
    # $1 — Путь к файлу с UID
    # $2 — Порт назначения для iptables (например, 1123 или 1124)
    local uid_file="$1"
    local dest_port="$2"

    # Чтение файла построчно, где каждая строка имеет вид "app_name=uid"
    while IFS='=' read -r app_name uid; do
        # Проверяем, что переменная uid начинается с цифры (то есть, это число)
        if [[ "$uid" == [0-9]* ]]; then
            # Добавляем правила iptables для порта 80
            iptables -t nat -A OUTPUT -p tcp --dport 80 -m owner --uid-owner "$uid" -j DNAT --to-destination 127.0.0.1:"$dest_port"
            # Добавляем правила iptables для порта 443
            iptables -t nat -A OUTPUT -p tcp --dport 443 -m owner --uid-owner "$uid" -j DNAT --to-destination 127.0.0.1:"$dest_port"
            echo "Установлено правило для UID: $uid"
        else
            # Если строка не содержит корректное числовое значение для UID, выводим сообщение
            echo "Пропущена строка без цифр: $app_name=$uid"
        fi
    done < "$uid_file"
}












BYEDPI_RESTART() {
#  local DPI_SCRIPT_DIR=$(dirname "$0")
  local DPI_CONFIG_FILE="/data/adb/modules/ZDT-D/working_folder/ciadpi.conf"
  local DPI_LOG_FILE="/data/adb/modules/ZDT-D/log/ciadpi.log"
  local DPI_PORT=${1:-1125}

  # убиваем процесс, слушающий DPI_PORT
  local DPI_PID
  DPI_PID=$(netstat -nlp 2>/dev/null | grep ":$DPI_PORT" | awk '{print $7}' | cut -d'/' -f1)
  [ -n "$DPI_PID" ] && kill -9 "$DPI_PID"

  # читаем дополнительные опции из конфигурации
  local DPI_CONFIG
  DPI_CONFIG=$(cat "$DPI_CONFIG_FILE")

  # запускаем ciadpi с базовыми параметрами и опциями из файла, логи — в DPI_LOG_FILE
  set -- $DPI_CONFIG
  ciadpi-zdt -i 127.0.0.1 -p "$DPI_PORT" -E "$@" >> "$DPI_LOG_FILE" 2>&1 &
}

























restarting_dpi_tunnel() {
    
    # Проверка условий
    if [ "$dpi_tunnel_0" = "1" ] || [ "$dpi_tunnel_1" = "1" ]; then
            
        # Основной цикл
        while true; do
            if [ "$dpi_tunnel_0" = "1" ]; then
                check_and_restart_dpi_tunnel "$PID" "$PORT" "$IP" "$MODE" "$PROFILE" "$BUFFER_SIZE" "$DOH_SERVER" "$TTL" "$AUTO_TTL" "$MIN_TTL" "$DESYNC_ATTACKS" "$WSIZE" "$WSFACTOR" "$SPLIT_AT_SNI" "$SPLIT_POSITION" "$WRONG_SEQ" "$BUILTIN_DNS" "$BUILTIN_DNS_IP" "$BUILTIN_DNS_PORT"
            elif [ "$dpi_tunnel_0" = "0" ]; then
                # Убираем логирование при выключении
                :
            else
                echo "Некорректное значение для dpi_tunnel_0 или его нет"
            fi
        
            if [ "$dpi_tunnel_1" = "1" ]; then
                check_and_restart_dpi_tunnel "$PID1" "$PORT1" "$IP1" "$MODE1" "$PROFILE1" "$BUFFER_SIZE1" "$DOH_SERVER1" "$TTL1" "$AUTO_TTL1" "$MIN_TTL1" "$DESYNC_ATTACKS1" "$WSIZE1" "$WSFACTOR1" "$SPLIT_AT_SNI1" "$SPLIT_POSITION1" "$WRONG_SEQ1" "$BUILTIN_DNS1" "$BUILTIN_DNS_IP1" "$BUILTIN_DNS_PORT1"
            elif [ "$dpi_tunnel_1" = "0" ]; then
                # Убираем логирование при выключении
                :
            else
                echo "Некорректное значение для dpi_tunnel_1 или его нет"
            fi
        
            # Ожидание 30 секунд перед следующей проверкой
            sleep 30
        done
    
    else
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

# Определям язык системы
language_sustem

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
read_params

# Проверка разрешения на запуск сервисов (например, может быть проверка настроек или лицензии)
start_stop_service

# Запуск nfqws с использованием конфигурации (закомментировано, если не требуется)
# start_zapret

# Загрузка конфигурации для DPI туннеля
start_load_config

##########################################
# Этап 4: Обработка UID-файлов и запуск профилей zapret
##########################################

# Первичная обработка UID-файлов (основной и дополнительный)
unified_processing "$UID_OUTPUT_FILE" "$UID_INPUT_FILE"
unified_processing "$UID_OUTPUT_FILE1" "$UID_INPUT_FILE1"
unified_processing "$UID_OUTPUT_FILE_BYE" "$UID_INPUT_FILE_BYE"
unified_processing "$ZAPRET_DPI_TUNNEL_OUT" "$ZAPRET_DPI_TUNNEL"
unified_processing "$DPI_TUNNEL_ZAPRET_OUT" "$DPI_TUNNEL_ZAPRET"
# --- Обработка профилей zapret ---
# Здесь для каждого профиля выполняется:
#   1. Проверка наличия входного файла с UID (если файл не пустой, то профиль активен)
#   2. Вызов функции start_zapret с уникальным идентификатором и конфигурацией
#   3. Обработка файла с UID через функцию unified_processing
#   4. Применение настроек iptables через функцию iptables_zapret_default_full

#!/system/bin/sh

# --- БЛОК 1: профили 2–6 (полная обработка) ---
for i in 2 3 4 5 6; do
    profile=$((i - 1))
    input_var="UID_INPUT_FILE$i"
    output_var="UID_OUTPUT_FILE$i"
    eval input_val=\$$input_var
    eval output_val=\$$output_var

    # 1) unified_processing при непустом входе
    if [ -s "$input_val" ]; then
        echo "Профиль ${profile} (i=$i): запускаем unified_processing"
        unified_processing "$output_val" "$input_val"
    else
        echo "Профиль ${profile} (i=$i): входной файл пуст → unified_processing пропущен."
    fi

    # 2) start_zapret + iptables при непустом выходе
    if [ -s "$output_val" ]; then
        echo "Профиль ${profile} (i=$i): запускаем start_zapret + iptables"

        code=$((200 + i - 2))
        config_index=$((i - 2))
        config_var="CONFIG_ZAPRET${config_index}"
        eval config_val=\$$config_var

        start_zapret "$code" "$config_val" "$input_val"
        iptables_zapret_default_full "$code" "$output_val"
    else
        echo "Профиль ${profile} (i=$i): выходной файл пуст → start_zapret и iptables пропущены."
    fi
done

# --- БЛОК 2: профили 7–9 (unified_processing + только iptables) ---
for i in 7; do
    profile=$((i - 1))
    input_var="UID_INPUT_FILE$i"
    output_var="UID_OUTPUT_FILE$i"
    eval input_val=\$$input_var
    eval output_val=\$$output_var

    # 1) unified_processing при непустом входе
    if [ -s "$input_val" ]; then
        echo "Профиль ${profile} (i=$i): запускаем unified_processing"
        unified_processing "$output_val" "$input_val"
    else
        echo "Профиль ${profile} (i=$i): входной файл пуст → unified_processing пропущен."
    fi

    # 2) только iptables при непустом выходе
    if [ -s "$output_val" ]; then
        echo "Профиль ${profile} (i=$i): запускаем только iptables_zapret_default_full"

        code=$((200 + i - 2))
        iptables_zapret_default_full "$code" "$output_val"
    else
        echo "Профиль ${profile} (i=$i): выходной файл пуст → iptables пропущен."
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


# Проверка доступности интернета с задержкой 15 секунд и интервалом проверки 5 секунд
internetcheck "15" "5"

# Если интернет доступен (индикатор равен 1), запускается проверка обновлений
if [ "$internet_echeck_status" -eq 1 ]; then
    echo "Интернет доступен, запускаем проверку обновлений..."
    check_update
else
    echo "Интернет недоступен, обновление не будет выполнено."
fi

apply_zdt_rules



##########################################
# Этап 5: Настройка DPI туннелей через iptables
##########################################

# ByeDpi
# Запуск службы bye dpi при условии что выходной файл не пуст
# Проверка файла, если он не пустой то запускаем службу 
if [ -s "$UID_OUTPUT_FILE_BYE" ]; then
    echo "Запуск Bye dpi"
    BYEDPI_RESTART "1125"
    load_config_dpi_tunnel "$UID_OUTPUT_FILE_BYE" 1125
else
    echo "Bye dpi не запущен"
fi

# DPI tunnel 0:
# Если переменная dpi_tunnel_0 равна "1", то туннель включён и запускается соответствующая функция;
# если "0" — выводится сообщение, что туннель выключен;
# в остальных случаях выводится сообщение об ошибке.
if [ "$dpi_tunnel_0" = "1" ]; then
    echo "DPI tunnel 0 включён, запуск"
    load_config_dpi_tunnel "$UID_OUTPUT_FILE" 1123
elif [ "$dpi_tunnel_0" = "0" ]; then
    echo "DPI tunnel 0 выключен."
else
    echo "Некорректное значение для dpi_tunnel_0 или его нет"
fi

# DPI tunnel 1:
if [ "$dpi_tunnel_1" = "1" ]; then
    echo "DPI tunnel 1 включён, запуск"
    load_config_dpi_tunnel "$UID_OUTPUT_FILE1" 1124
elif [ "$dpi_tunnel_1" = "0" ]; then
    echo "DPI tunnel 1 выключен."
else
    echo "Некорректное значение для dpi_tunnel_1 или его нет"
fi

##########################################
# Этап 6: Финальные действия и обновления
##########################################

# Отправка уведомления о запуске всех сервисов
notification_send info "$MSG_SUCCESSFUL_LAUNCH"

# Если исходное состояние SELinux было Permissive, возвращаем его обратно
if [ "$SELINUX_STATE" = "Enforcing" ]; then
    setenforce 1
fi

# Проверка работоспособности сервиса DPI tunnel и его перезапуск при необходимости
restarting_dpi_tunnel



