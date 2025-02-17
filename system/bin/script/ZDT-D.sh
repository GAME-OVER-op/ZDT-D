#!/system/bin/sh


# Ожидание завершения загрузки устройства
boot_completed() {
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 1
    done
}

# Путь к файлу конфигурации
DAM="/data/adb/modules"
# Путь к файлу конфигурации
CONFIG_FILE="$DAM/ZDT-D/working_folder/config0"
CONFIG_FILE1="$DAM/ZDT-D/working_folder/config1"
# Путь к входному файлу с именами приложений
UID_INPUT_FILE="$DAM/ZDT-D/working_folder/uid_program0"
UID_INPUT_FILE1="$DAM/ZDT-D/working_folder/uid_program1"
UID_INPUT_FILE2="$DAM/ZDT-D/working_folder/zapret_uid"
# Путь к выходному файлу с UID
UID_OUTPUT_FILE="$DAM/ZDT-D/working_folder/uid_out0"
UID_OUTPUT_FILE1="$DAM/ZDT-D/working_folder/uid_out1"
UID_OUTPUT_FILE2="$DAM/ZDT-D/working_folder/zapret_uid_out"
# Путь для файлов zapret
ZAPRET_CONFIG_FILES_DATA="/data/adb/working_folder/zapret_config"
ARCH_DIR="$DAM/ZDT-D/bin_zapret/"
BIN_DIR="$DAM/ZDT-D/system/bin/"
MARKER_FILE="$DAM/ZDT-D/arx_marker"
ZAPRET_BIN_FILES_SYSTEM="$DAM/ZDT-D/system/bin/nfqws"
INPUT_FILE="$DAM/ZDT-D/working_folder/apps_list.txt"
FULL_INFO_FILE="$DAM/ZDT-D/working_folder/full_info.txt"
FILE_CHESK_ZAPRET_UID="$DAM/ZDT-D/working_folder/zapret_uid"
dpi_list_path="$DAM/ZDT-D/working_folder/"
# Файлы уведомления
SOURCE_FILE_ICON_MB="$DAM/ZDT-D/icon.png"
SOURCE_FILE_ICON_MB1="$DAM/ZDT-D/icon1.png"
SOURCE_FILE_ICON_MB2="$DAM/ZDT-D/icon2.png"
TARGET_DIRECTORY_ICON_MB="/data/local/tmp/"
# Условия для применения по всей системе
SETTING_START_PARAMS="$DAM/ZDT-D/working_folder/params"
SETTING_START_PARAMSET="$DAM/ZDT-D/working_folder/params"
SETTING_START_PARAMSIN="$DAM/ZDT-D/params"
SETTING_START_PARAMSTO="/data/adb/"
# Обновление
MODULE_PROP="$DAM/ZDT-D/module.prop"
UPDATE_JSON_URL="https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/Update/update.json"
TEMP_JSON="/data/local/tmp/update.json"
PASSWORD="/data/adb/php7/files/www/auth/"
PASSWORDTO="/data/adb/credentials.php"
SHA256_WORKING_DIR="$DAM/ZDT-D/working_folder"
SHA256_FLAG_FILE="$SHA256_WORKING_DIR/flag.sha256"
CURL_BINARIES="$DAM/ZDT-D/php7/files/bin/curl"

check_modules() {
    local zapret_path="/data/adb/modules/zapret"
    local fork_path="/data/adb/modules/dpi_tunnel_cli"

    if [ -d "$zapret_path" ]; then
        sleep 30
        su -lp 2000 -c "cmd notification post -S messaging --conversation 'Chat' \
            --message 'ZDT-D:Здравствуйте, у вас установлен модуль zapret, удалите его пожалуйста.' \
            --message 'System:Почему?' \
            --message 'ZDT-D:Я думаю, модуль может конфликтовать, что приведёт к ошибкам работы...' \
            -t 'Ошибка' 'Tag' ''" >/dev/null 2>&1
        exit 1
    fi

    if [ -d "$fork_path" ]; then
        sleep 30
        su -lp 2000 -c "cmd notification post -S messaging --conversation 'Chat' \
            --message 'ZDT-D:Здравствуйте, у вас установлен модуль Zapret DPI Tunnel and Dns Comss, удалите его пожалуйста.' \
            --message 'System:Почему?' \
            --message 'ZDT-D:Это предыдущий форк модуля, он больше не поддерживается...' \
            -t 'Ошибка' 'Tag' ''" >/dev/null 2>&1
        exit 1
    fi

    echo "Конфликтующие модули не найдены, продолжаем работу..."
}

start_system_final() {
# Счетчик разблокированного состояния
local unlocked_count=0

while true; do
    # Получение текущей даты и времени
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')

    # Проверка состояния lockscreen через dumpsys
    lock_state=$(dumpsys window | grep 'mCurrentFocus=' | grep 'NotificationShade' >/dev/null && echo "locked" || echo "unlocked")

    # Запись в лог в зависимости от состояния
    if [ "$lock_state" = "locked" ]; then
        echo "$timestamp: Телефон заблокирован"
        unlocked_count=0  # Сбрасываем счетчик разблокированного состояния
    elif [ "$lock_state" = "unlocked" ]; then
        unlocked_count=$((unlocked_count + 1))
        echo "$timestamp: Телефон разблокирован"

        # Если разблокировали 2 раза подряд, выход из цикла
        if [ "$unlocked_count" -ge 2 ]; then
            echo "$timestamp: Телефон разблокирован 2 раза подряд, выход из цикла."
            break
        fi
    fi

    # Ожидание 3 секунд перед следующей проверкой
    sleep 1
done
}

notification_toast_start() {
/system/bin/am start -a android.intent.action.MAIN -e toasttext "DPI Tunnel CLi начинаю процедуру запуска..." -n bellavita.toast/.MainActivity
}

internetcheck() {
    # Переменные для попыток
    attempt=0
    max_attempts=3

    # Цикл проверки интернета
    while [ $attempt -lt $max_attempts ]; do
        if ping -c 1 8.8.8.8 >/dev/null 2>&1; then
            echo "Интернет есть."
            break
        else
            echo "Интернет недоступен. Попытка $((attempt + 1)) из $max_attempts."
            attempt=$((attempt + 1))
            sleep 10 # Задержка между попытками
        fi
    done

    # Если интернет недоступен после 3 попыток
    if [ $attempt -eq $max_attempts ]; then
        echo "Интернет недоступен после $max_attempts попыток. Продолжаем работу."
    fi
}

check_update() {
    chmod 777 "$CURL_BINARIES"
    # Функция для получения значений из module.prop
    get_prop_value() {
        grep "^$1=" "$MODULE_PROP" | cut -d'=' -f2
    }

    # Проверка наличия module.prop
    if [ ! -f "$MODULE_PROP" ]; then
        echo "Файл module.prop не найден."
        exit 1
    fi


# Скачивание файла JSON
if command -v curl >/dev/null 2>&1; then
    # Если команда curl доступна в PATH, используем её.
    curl -fsSL "$UPDATE_JSON_URL" -o "$TEMP_JSON"
elif command -v wget >/dev/null 2>&1; then
    # Если curl не найден, а команда wget доступна, используем wget.
    wget -q "$UPDATE_JSON_URL" -O "$TEMP_JSON"
elif [ -x "$CURL_BINARIES" ]; then
    # Если ни curl, ни wget не найдены в PATH, но существует исполняемый файл по указанному пути,
    # используем его с опцией --insecure (-k) для игнорирования проверки сертификата.
    $CURL_BINARIES -k -fsSL "$UPDATE_JSON_URL" -o "$TEMP_JSON"
else
    # Если ни curl, ни wget, ни указанный бинарник curl не найдены, выводим сообщение об ошибке.
    echo "curl или wget не найдены. Установите одно из них."
    exit 1
fi


    # Проверяем, что файл JSON был успешно загружен
    if [ ! -f "$TEMP_JSON" ]; then
        echo "Не удалось загрузить файл обновлений."
    fi

    # Проверка наличия jq
    if ! command -v jq >/dev/null 2>&1; then
        echo "Утилита jq не установлена."
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

    # Проверка корректности данных
    if [ -z "$LOCAL_VERSION_CODE" ] || [ -z "$REMOTE_VERSION_CODE" ]; then
        echo "Ошибка извлечения версии. Проверьте файлы."
    fi

    # Сравнение версий
    if [ "$LOCAL_VERSION_CODE" -lt "$REMOTE_VERSION_CODE" ]; then
        MESSAGE="Доступно обновление!:Текущая версия: $LOCAL_VERSION Новая версия: $REMOTE_VERSION."

        # Отправка уведомления с помощью cmd
        if command -v cmd >/dev/null 2>&1; then
            su -lp 2000 -c "cmd notification post -i file:///data/local/tmp/icon1.png -I file:///data/local/tmp/icon2.png -S messaging --conversation 'ZDT-D' --message '$MESSAGE' --message 'Ссылка для скачивания:$NEW_UPDATE_URL' -t 'DPI Tunnel CLI' 'UpdateCheck' 'Проверка обновлений завершена.'" >/dev/null 2>&1
        else
            echo "Команда cmd не найдена. Уведомление не может быть отправлено."
            echo -e "$MESSAGE"
        fi
    else
        echo "Обновлений нет."
    fi
}


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

start_stop_service() {

# Если значение offonservice установлено, проверяем его
if [ "$offonservice" = "1" ]; then
    
    echo "Модуль включен"
    
elif [ "$offonservice" = "0" ]; then
    
    /system/bin/am start -a android.intent.action.MAIN -e toasttext "DPI Tunnel CLI выключен" -n bellavita.toast/.MainActivity
    echo "модуль выключен"
    exit 0
else
    echo "Некорректное значение для offonservice или его нет"
fi
}

start_zapret() {

# Проверка поддержки iptables
use_iptables=$(iptables -t mangle -A POSTROUTING -p tcp -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12 -j ACCEPT 2>/dev/null && echo "2" || echo "3")
[ "$use_iptables" = "2" ] && iptables -t mangle -D POSTROUTING -p tcp -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12 -j ACCEPT 2>/dev/null

# Либеральный режим TCP
sysctl net.netfilter.nf_conntrack_tcp_be_liberal=1 > /dev/null


# Если значение dns установлено, проверяем его
if [ "$zapretconfig" = "1" ]; then


# -------------------------------------------
# ГЛОБАЛЬНЫЕ НАСТРОЙКИ И БЛОК "PACKET"
# -------------------------------------------
GLOBAL="--uid=0:0 --qnum=200"
# Блок для базовой фильтрации на порту 80 с использованием pasket.txt/pasket_skip.txt
PACKET="--filter-tcp=80 --dpi-desync=fake,fakedsplit --dpi-desync-autottl=2 --dpi-desync-fooling=md5sig \
--hostlist-auto=/data/adb/modules/ZDT-D/working_folder/bin/pasket.txt \
--hostlist-exclude=/data/adb/modules/ZDT-D/working_folder/bin/pasket_skip.txt"

# -------------------------------------------
# БЛОК YOUTUBE (TCP и UDP)
# -------------------------------------------
YOUTUBE_TCP_1="--new \
  --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/youtube.txt \
  --dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,midsld \
  --dpi-desync-repeats=11 --dpi-desync-fooling=md5sig \
  --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin"

YOUTUBE_TCP_2="--new \
  --filter-tcp=443 --dpi-desync=fake,multidisorder --dpi-desync-split-pos=midsld \
  --dpi-desync-repeats=6 --dpi-desync-fooling=badseq,md5sig \
  --hostlist-auto=/data/adb/modules/ZDT-D/working_folder/bin/pasket.txt \
  --hostlist-exclude=/data/adb/modules/ZDT-D/working_folder/bin/pasket_skip.txt"

YOUTUBE_UDP_1="--new \
  --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/youtube.txt \
  --dpi-desync=fake --dpi-desync-repeats=11 \
  --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin"

YOUTUBE_UDP_2="--new \
  --filter-udp=443 --dpi-desync=fake --dpi-desync-repeats=11 \
  --hostlist-auto=/data/adb/modules/ZDT-D/working_folder/bin/pasket.txt \
  --hostlist-exclude=/data/adb/modules/ZDT-D/working_folder/bin/pasket_skip.txt"

# -------------------------------------------
# БЛОК NETROGAT
# -------------------------------------------
NETROGAT_1="--new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt"
NETROGAT_2="--new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt"

# -------------------------------------------
# БЛОК RUSSIA YOUTUBE (несколько секций)
# -------------------------------------------
RUS_YT_Q="--new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtubeQ.txt \
  --dpi-desync=fake --dpi-desync-repeats=8 --dpi-desync-cutoff=n2 \
  --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin"

RUS_YT_GV="--new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtubeGV.txt \
  --dpi-desync=split --dpi-desync-split-pos=1 \
  --dpi-desync-fooling=badseq --dpi-desync-repeats=20 --dpi-desync-autottl"

RUS_YT="--new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtube.txt \
  --dpi-desync=fake,split2 --dpi-desync-split-seqovl=2 \
  --dpi-desync-split-pos=2 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin \
  --dpi-desync-autottl"

# -------------------------------------------
# БЛОК RUSSIA BLACKLIST
# -------------------------------------------
RUS_BL_1="--new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt \
  --dpi-desync=fake,split2 --dpi-desync-fooling=badseq --dpi-desync-autottl"

RUS_BL_2="--new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt \
  --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/myhostlist.txt \
  --dpi-desync=fake,split2 --dpi-desync-split-seqovl=1 --dpi-desync-split-pos=2 \
  --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin \
  --dpi-desync-skip-nosni=1 --dpi-desync-autottl"

# -------------------------------------------
# БЛОК DISCORD
# -------------------------------------------
DISCORD_UDP="--new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-discord.txt \
  --dpi-desync=fake --dpi-desync-udplen-increment=10 --dpi-desync-repeats=7 \
  --dpi-desync-udplen-pattern=0xDEADBEEF \
  --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin \
  --dpi-desync-cutoff=n2"

DISCORD_TCP="--new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/discord.txt \
  --dpi-desync=fake,disorder2 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin \
  --dpi-desync-split-pos=3 --dpi-desync-fooling=md5sig --dpi-desync-repeats=12 --dpi-desync-autottl"

DISCORD_IPSET="--new --filter-udp=443 --ipset=/data/adb/modules/ZDT-D/working_folder/bin/ipset-discord.txt \
  --dpi-desync=fake --dpi-desync-any-protocol --dpi-desync-cutoff=d3 --dpi-desync-repeats=6"

# -------------------------------------------
# БЛОК AUT0HOSTLIST
# -------------------------------------------
AUTOHOST="--new --filter-tcp=443 --hostlist-auto=/data/adb/modules/ZDT-D/autohostlist.txt \
  --hostlist-exclude=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt \
  --dpi-desync=split --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq \
  --dpi-desync-repeats=10 --dpi-desync-autottl"

# -------------------------------------------
# БЛОК IPS (TCP и UDP)
# -------------------------------------------
IPS_TCP_80="--new --filter-tcp=80 --dpi-desync=fake,split2 --dpi-desync-autottl=6 \
  --dpi-desync-fooling=md5sig --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt"

IPS_TCP_443="--new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt \
  --dpi-desync=fake,split2 --dpi-desync-repeats=16 --dpi-desync-fooling=md5sig \
  --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin"

IPS_TCP_BOTH="--new --filter-tcp=80,443 --dpi-desync=fake,disorder2 --dpi-desync-repeats=6 \
  --dpi-desync-autottl=3 --dpi-desync-fooling=md5sig --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt"

IPS_UDP="--new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt \
  --dpi-desync=fake --dpi-desync-repeats=13 \
  --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin"

# -------------------------------------------
# БЛОК ДОПОЛНИТЕЛЬНЫХ FAKE TLS/QUIC
# -------------------------------------------
FAKE_TLS_SBER="--new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_sberbank_ru.bin"
FAKE_TLS_VK="--new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_vk_com.bin"
FAKE_QUIC_DTLS="--new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/dtls_clienthello_w3_org.bin"
FAKE_QUIC_WG="--new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/wireguard_response.bin"

# -------------------------------------------
# БЛОК SYNDATA И GENERAL
# -------------------------------------------
SYNDATA="--new --filter-l3=ipv4 --filter-tcp=443 --dpi-desync=syndata"
GENERAL="--new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/general.txt \
  --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fooling=md5sig \
  --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin"

# -------------------------------------------
# БЛОК DISORDER (с указанием wssize)
# -------------------------------------------
DISORDER="--new --dpi-desync=fake,disorder2 --dpi-desync-split-pos=1 --dpi-desync-ttl=6 \
  --dpi-desync-fooling=md5sig,badseq --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin \
  --wssize 1:6"

# -------------------------------------------
# БЛОК повторного применения (NETROGAT, RUSSIA YOUTUBE, DISCORD, RUSSIA BLACKLIST, AUT0HOSTLIST)
# -------------------------------------------
# Повторное применение блоков для Netrogat:
NETROGAT_R="--new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt \
  --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt"

# Повтор для Russia YouTube:
RUS_YT_R="--new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtubeQ.txt \
  --dpi-desync=fake --dpi-desync-repeats=9 --dpi-desync-cutoff=n2 \
  --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin \
  --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtubeGV.txt \
  --dpi-desync=split --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq \
  --dpi-desync-repeats=16 --dpi-desync-autottl"

# Повтор для Discord (UDP):
DISCORD_R="--new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-discord.txt \
  --dpi-desync=fake --dpi-desync-udplen-increment=13 --dpi-desync-repeats=7 \
  --dpi-desync-udplen-pattern=0xDEADBEEF \
  --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin \
  --dpi-desync-cutoff=n2"

# Повтор для Russia Blacklist:
RUS_BL_R="--new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt \
  --dpi-desync=fake,split2 --dpi-desync-fooling=badseq --dpi-desync-autottl \
  --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt \
  --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/myhostlist.txt \
  --dpi-desync=fake,split2 --dpi-desync-split-seqovl=2 --dpi-desync-split-pos=4 \
  --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin \
  --dpi-desync-skip-nosni=1 --dpi-desync-autottl"

# Повтор для Autohostlist:
AUTOHOST_R="--new --filter-udp=50000-50200 --dpi-desync=fake --dpi-desync-any-protocol \
  --dpi-desync-cutoff=d2 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin \
  --new --filter-tcp=443 --hostlist-auto=/data/adb/modules/ZDT-D/autohostlist.txt \
  --hostlist-exclude=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt \
  --dpi-desync=split --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq \
  --dpi-desync-repeats=10 --dpi-desync-autottl"

# -------------------------------------------
# БЛОК IPS И ДОПОЛНИТЕЛЬНЫЕ FINAl настройки
# -------------------------------------------
IPS_FINAL="--new --filter-tcp=80,443 --dpi-desync=fake,disorder2 --dpi-desync-repeats=6 \
  --dpi-desync-autottl=6 --dpi-desync-fooling=md5sig --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt \
--new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt \
  --dpi-desync=fake --dpi-desync-repeats=11 \
  --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin \
--new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_sberbank_ru.bin \
--new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_vk_com.bin \
--new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/dtls_clienthello_w3_org.bin \
--new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/wireguard_response.bin"

# -------------------------------------------
# Итоговый запуск: объединяем все блоки
# -------------------------------------------
nfqws $GLOBAL $PACKET $YOUTUBE_TCP_1 $YOUTUBE_TCP_2 $YOUTUBE_UDP_1 $YOUTUBE_UDP_2 \
      $NETROGAT_1 $NETROGAT_2 \
      $RUS_YT_Q $RUS_YT_GV $RUS_YT \
      $RUS_BL_1 $RUS_BL_2 \
      $RUS_DISCORD \
      $FALLBACK_UDP \
      $DISCORD_TCP $DISCORD_IPSET \
      $AUTOHOST \
      $IPS_TCP_80 $IPS_TCP_443 $IPS_TCP_BOTH $IPS_UDP \
      $FAKE_TLS_SBER $FAKE_TLS_VK $FAKE_QUIC_DTLS $FAKE_QUIC_WG \
      $SYNDATA $GENERAL $DISORDER \
      $NETROGAT_R $RUS_YT_R $DISCORD_R $RUS_BL_R $AUTOHOST_R \
      $IPS_FINAL > /dev/null 2>&1 &
    
elif [ "$zapretconfig" = "0" ]; then
    
    
    # Запуск nfqws с заданной конфигурацией
    nfqws --uid=0:0 --qnum=200 --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtubeQ.txt --dpi-desync=fake --dpi-desync-repeats=8 --dpi-desync-cutoff=n2 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtubeGV.txt --dpi-desync=split --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq --dpi-desync-repeats=20 --dpi-desync-autottl --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtube.txt --dpi-desync=fake,split2 --dpi-desync-split-seqovl=2 --dpi-desync-split-pos=2 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --dpi-desync-autottl --new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt --dpi-desync=fake,split2 --dpi-desync-fooling=badseq --dpi-desync-autottl --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/myhostlist.txt --dpi-desync=fake,split2 --dpi-desync-split-seqovl=1 --dpi-desync-split-pos=2 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --dpi-desync-skip-nosni=1 --dpi-desync-autottl --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-discord.txt --dpi-desync=fake --dpi-desync-udplen-increment=10 --dpi-desync-repeats=7 --dpi-desync-udplen-pattern=0xDEADBEEF --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin --dpi-desync-cutoff=n2 --new --filter-udp=50000-50200 --dpi-desync=fake --dpi-desync-any-protocol --dpi-desync-cutoff=d2 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-discord.txt --dpi-desync=fake,disorder2 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --dpi-desync-split-pos=3 --dpi-desync-fooling=md5sig --dpi-desync-repeats=12 --dpi-desync-autottl --new --filter-tcp=443 --hostlist-auto=/data/adb/modules/ZDT-D/autohostlist.txt --hostlist-exclude=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt --dpi-desync=split --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq --dpi-desync-repeats=10 --dpi-desync-autottl --new --filter-tcp=80 --dpi-desync=fake,split2 --dpi-desync-autottl=6 --dpi-desync-fooling=md5sig --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt --dpi-desync=fake,split2 --dpi-desync-repeats=16 --dpi-desync-fooling=md5sig --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --new --filter-tcp=80,443 --dpi-desync=fake,disorder2 --dpi-desync-repeats=6 --dpi-desync-autottl=3 --dpi-desync-fooling=md5sig --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt --dpi-desync=fake --dpi-desync-repeats=13 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin --new --filter-udp=443 --dpi-desync=fake --dpi-desync-repeats=8 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt --new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_sberbank_ru.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_facebook_com.bin --new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_vk_com.bin --new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_rutracker_org_kyber.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_rutracker_org_kyber_1.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_rr2---sn-gvnuxaxjvh-o8ge_googlevideo_com.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/dht_find_node.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/dtls_clienthello_w3_org.bin --new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_iana_org.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/zero_1024.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/wireguard_response.bin --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/Instagram.list.txt --dpi-desync=fake --dpi-desync-repeats=9 --dpi-desync-cutoff=n2 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_1.bin --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/Instagram.list.txt --dpi-desync=fake --dpi-desync-repeats=12 --dpi-desync-cutoff=n2 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_2.bin --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/Instagram.list.txt --dpi-desync=fake --dpi-desync-repeats=2 --dpi-desync-cutoff=n2 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_1.bin --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/Instagram.list.txt --dpi-desync=fake --dpi-desync-repeats=2 --dpi-desync-cutoff=n2 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_2.bin --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/Instagram.list.txt --dpi-desync=fake --dpi-desync-repeats=2 --dpi-desync-cutoff=n2 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_3.bin --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/discord.txt --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin --new --filter-udp=443 --ipset=/data/adb/modules/ZDT-D/working_folder/bin/ipset-discord.txt --dpi-desync=fake --dpi-desync-any-protocol --dpi-desync-cutoff=d3 --dpi-desync-repeats=6 --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/discord.txt --dpi-desync=fake,split --dpi-desync-autottl=13 --dpi-desync-repeats=6 --dpi-desync-fooling=badseq --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/general.txt --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin --new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/general.txt --dpi-desync=fake,split2 --dpi-desync-autottl=11 --dpi-desync-fooling=md5sig --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/general.txt --dpi-desync=fake,split --dpi-desync-autottl=2 --dpi-desync-repeats=19 --dpi-desync-fooling=badseq --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/general.txt --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin --new --filter-udp=50000-50100 --ipset=/data/adb/modules/ZDT-D/working_folder/bin/ipset-discord.txt --dpi-desync=fake --dpi-desync-any-protocol --dpi-desync-cutoff=d3 --dpi-desync-repeats=6 --new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/general.txt --dpi-desync=fake,split2 --dpi-desync-autottl=2 --dpi-desync-fooling=md5sig --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/general.txt --dpi-desync=split2 --dpi-desync-split-seqovl=652 --dpi-desync-split-pos=2 --dpi-desync-split-seqovl-pattern=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --new --filter-l3=ipv4 --filter-tcp=443 --dpi-desync=syndata --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/general.txt --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fooling=md5sig --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --new --dpi-desync=fake,disorder2 --dpi-desync-split-pos=1 --dpi-desync-ttl=6 --dpi-desync-fooling=md5sig,badseq --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin --wssize 1:6 --new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtubeQ.txt --dpi-desync=fake --dpi-desync-repeats=9 --dpi-desync-cutoff=n2 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-youtubeGV.txt --dpi-desync=split --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq --dpi-desync-repeats=16 --dpi-desync-autottl --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-discord.txt --dpi-desync=fake --dpi-desync-udplen-increment=13 --dpi-desync-repeats=7 --dpi-desync-udplen-pattern=0xDEADBEEF --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin --dpi-desync-cutoff=n2 --new --filter-tcp=80 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt --dpi-desync=fake,split2 --dpi-desync-fooling=badseq --dpi-desync-autottl --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/myhostlist.txt --dpi-desync=fake,split2 --dpi-desync-split-seqovl=2 --dpi-desync-split-pos=4 --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --dpi-desync-skip-nosni=1 --dpi-desync-autottl --new --filter-udp=50000-50200 --dpi-desync=fake --dpi-desync-any-protocol --dpi-desync-cutoff=d2 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_pl_by_ori.bin --new --filter-tcp=443 --hostlist-auto=/data/adb/modules/ZDT-D/autohostlist.txt --hostlist-exclude=/data/adb/modules/ZDT-D/working_folder/bin/netrogat.txt --dpi-desync=split --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq --dpi-desync-repeats=10 --dpi-desync-autottl --new --filter-tcp=80,443 --dpi-desync=fake,disorder2 --dpi-desync-repeats=6 --dpi-desync-autottl=6 --dpi-desync-fooling=md5sig --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/ips.txt --dpi-desync=fake --dpi-desync-repeats=11 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin --new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_sberbank_ru.bin --new --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_vk_com.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/dtls_clienthello_w3_org.bin --new --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/wireguard_response.bin --new --filter-l3=ipv4 --filter-tcp=443 --dpi-desync=syndata --wssize 1:6 --filter-l3=ipv4 --filter-tcp=443 --filter-udp=443,50000-50050 --dpi-desync=fake --dpi-desync-cutoff=d2 --dpi-desync-any-protocol --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/google.txt --dpi-desync=fake,split --dpi-desync-fooling=badseq --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_drive_google_com.bin --dpi-desync-repeats=10 --new --filter-tcp=443 --hostlist-auto=/data/adb/modules/ZDT-D/working_folder/bin/blocked.txt --dpi-desync=fake,split --dpi-desync-fooling=badseq --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_drive_google_com.bin --new --filter-udp=443 --dpi-desync=fake --new --filter-tcp=443 --hostlist-domains=googlevideo.com --dpi-desync=multidisorder --wssize=1:6 --ipset-ip=2a00:1450:4011::/56 --dpi-desync-split-seqovl=1 --dpi-desync-split-pos=1,host+2,sld+2,sld+5,sniext+1,sniext+2,endhost-2 --new --filter-tcp=80 --dpi-desync=fakeddisorder --dpi-desync-ttl=1 --dpi-desync-autottl=2 --dpi-desync-split-pos=method+2 --new --filter-tcp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt --dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,midsld --dpi-desync-repeats=11 --dpi-desync-fooling=md5sig --dpi-desync-fake-tls=/data/adb/modules/ZDT-D/working_folder/bin/tls_clienthello_www_google_com.bin --new --filter-tcp=443 --dpi-desync=fake,multidisorder --dpi-desync-split-pos=midsld --dpi-desync-repeats=6 --dpi-desync-fooling=badseq,md5sig --new --filter-udp=443 --hostlist=/data/adb/modules/ZDT-D/working_folder/bin/russia-blacklist.txt --dpi-desync=fake --dpi-desync-repeats=11 --dpi-desync-fake-quic=/data/adb/modules/ZDT-D/working_folder/bin/quic_initial_www_google_com.bin --new --filter-udp=443 --dpi-desync=fake --dpi-desync-repeats=11 --new --filter-udp=50000-50099 --ipset=/data/adb/modules/ZDT-D/working_folder/bin/ipset-discord.txt --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-any-protocol --dpi-desync-cutoff=n4 > /dev/null 2>&1 &

    echo "Старый конфиг zapret"
else
    echo "Некорректное значение для zapret или его нет"
fi

}

alt_start_iptables() {


# Чтение и проверка для alternativel
if [ "$alternativel" = "1" ]; then
# Основная конфигурация
uselist="1"; # Включение/выключение автоматического списка хостов
debug="0"; # Включение/выключение логов в файл

# Имена:
dpi_list="pasket.txt"; # Список URL-адресов
dpi_ignore="pasket_skip.txt"; # Список URL-адресов для игнорирования
nftname="inet zapret"; iptname="mangle"; # Название таблиц

# Конфигурация автоматического списка хостов
if [ "$uselist" == "1" ]; then echo "Используется автоматический список хостов";
if ! [ -e "$dpi_list_path/$dpi_ignore" ]; then echo -n "" > "$dpi_list_path/$dpi_ignore";
chmod 666 "$dpi_list_path/$dpi_ignore"; fi; # Если файл списка не найден
HOSTLIST_NOAUTO="--hostlist-auto=$dpi_list_path/$dpi_list --hostlist-exclude=$dpi_list_path/$dpi_ignore";
else HOSTLIST_NOAUTO=""; fi

###################
# Конфигурация десинхронизации
DesyncHTTP="--filter-tcp=80,8000 --wssize=1:6 --dpi-desync=split --dpi-desync-fooling=md5sig,badsum --dpi-desync-ttl=0 $HOSTLIST_NOAUTO";

Desync1="--new --filter-tcp=443 --wssize=1:6 --dpi-desync=fake --dpi-desync-repeats=2 --dpi-desync-autottl=2 --dpi-desync-fake-tls=0x16030102 --hostlist-domains=youtube.com,googlevideo.com,ytimg.com,ggpht.com,youtubei.googleapis.com"; # YouTube
Desync2="--new --filter-udp=50000-50099 --dpi-desync=fake --dpi-desync-any-protocol --dpi-desync-fake-quic=0xC30000000108"; # Discord

#Desync3="--new ";
#Desync4="--new ";
#Desync5="--new ";

DesyncHTTPS="--new --filter-tcp=443 --wssize=1:6 --dpi-desync=disorder2 $HOSTLIST_NOAUTO";
DesyncQUIC="--new --filter-udp=443,6969 --dpi-desync=fake --dpi-desync-any-protocol --dpi-desync-fake-quic=0xC30000000108 $HOSTLIST_NOAUTO";
###################

# Сбор всех Desync в одну строку
NFQWS_OPT="$(echo $DesyncHTTP $Desync1 $Desync2 $Desync3 $Desync4 $Desync5 $DesyncHTTPS $DesyncQUIC | sed 's/  / /g')";
if [ "$debug" == "1" ]; then echo "Отладка всех Desync: $NFQWS_OPT" >> $dpi_list_path/DPI_logS.txt; fi;

# Добавление сайта в файл списка хостов
addHlist() {
if ! [ -e "$dpi_list_path/$dpi_list" ]; then 
echo -n "" > "$dpi_list_path/$dpi_list"; 
chmod 666 "$dpi_list_path/$dpi_list"; 
fi;
for site in $1; do 
if [ "$(grep -c '$site' $dpi_list_path/$dpi_list)" == "0" ]; 
then echo "$site" >> $dpi_list_path/$dpi_list; fi; 
done; 
}

# Сайты Discord
listDiscord="discord.com
discord.gg
discord.co
discord.dev
discord.new
discord.gift
discord.app
discord.media
discordapp.com
discordapp.net
discordcdn.com
discordstatus.com
dis.gd
rutracker.org
ntc.party
discord.com
gateway.discord.gg
cdn.discordapp.com
discordapp.net
discordapp.com
discord.gg
media.discordapp.net
images-ext-1.discordapp.net
www.discord.com
www.discord.app
discord.app
*.discord.com
*.discord.gg
*.discordapp.com
*.discordapp.net
discord.media
*.discord.media
discordcdn.com
discord.dev
discord.new
discord.gift
discordstatus.com
dis.gd
discord.co
discord-attachments-uploads-prd.storage.googleapis.com
prostovpn.org
discord.com
discord.gg
discordapp.com
discordapp.net
discord.app
discord.media
discordcdn.com
discord.dev
discord.new
discord.gift
discordstatus.com
dis.gd
chatgpt.com
ab.chatgpt.com
auth.openai.com
auth0.openai.com
platform.openai.com
cdn.oaistatic.com
files.oaiusercontent.com
cdn.auth0.com
tcr9i.chat.openai.com
webrtc.chatgpt.com
android.chat.openai.com
gemini.google.com
aistudio.google.com
generativelanguage.googleapis.com
alkalimakersuite-pa.clients6.google.com
copilot.microsoft.com
sydney.bing.com
edgeservices.bing.com
claude.ai
aitestkitchen.withgoogle.com
aisandbox-pa.googleapis.com
o.pki.goog
www.googleapis.com
speechs3proto2-pa.googleapis.com
firebaseinstallations.googleapis.com
proactivebackend-pa.googleapis.com
geller-pa.googleapis.com
assistantfrontend-pa.googleapis.com
assistant-s3-pa.googleapis.com
searchnotifications-pa.googleapis.com
notifications-pa.googleapis.com
update.googleapis.com
lamssettings-pa.googleapis.com
taskassist-pa.googleapis.com
play.geforcenow.com
geforcenow.com
static-login.nvidia.com
login.nvgs.nvidia.com
*.nvgs.nvidia.com
*.geforcenow.com
static-login.nvidia.com/*
login.nvgs.nvidia.com
*.nvgs.nvidia.com
*.nvgs.nvidia.com/*
185.136.70.146
events.gfe.nvidia.com
events.gfe.nvidia.com/*
*.gfe.nvidia.com
*.gfe.nvidia.com/*
prod.otel.kaizen.nvidia.com
*.otel.kaizen.nvidia.com
prod.otel.kaizen.nvidia.com/*
*.otel.kaizen.nvidia.com/*
gx-target-survey-frontend-api.gx.nvidia.com
gx-target-survey-frontend-api.gx.nvidia.com/*
gx.nvidia.com
*.gx.nvidia.com
*.gx.nvidia.com/*
telemetry.gfe.nvidia.com
*.gfe.nvidia.com
telemetry.gfe.nvidia.com/*
*.gfe.nvidia.com/*
pcs.geforcenow.com
pcs.geforcenow.com/*
*.geforcenow.com
*.geforcenow.com/*
public.games.geforce.com
*.games.geforce.com
public.games.geforce.com/*
*.games.geforce.com/*
eu-northeast.cloudmatchbeta.nvidiagrid.net
eu-northeast.cloudmatchbeta.nvidiagrid.net/*
*.cloudmatchbeta.nvidiagrid.net
*.cloudmatchbeta.nvidiagrid.net/*
api.cloudflareclient.com
api.cloudflareclient.com/*
*.cloudflareclient.com
*.cloudflareclient.com/*
discord.co
discord-attachments-uploads-prd.storage.googleapis.com
discord-attachments-uploads-prd.storag=e.googleapis.com";

if ! [ -e "$dpi_list_path/$dpi_list" ]; then 
addHlist "$listDiscord"; 
fi;

# Пользовательский интерфейс
if [ -n "$2" ]; then iface=$2; echo "Запуск для интерфейса $iface"; 
if ! [ -e "/proc/net/ip_tables_targets" ]; then iifnm="iifname $iface"; oifnm="oifname $iface";
else iifnm="-i $iface"; oifnm="-o $iface"; fi;
else iifnm=""; oifnm=""; echo "Запуск для всех интерфейсов"; fi; 

# Правила NetFilter
if [ "$uselist" == "1" ]; then sysctl net.netfilter.nf_conntrack_tcp_be_liberal=1 > /dev/null; fi; 
if [ "$(echo $NFQWS_OPT | grep -c badsum)" != "0" ]; then sysctl net.netfilter.nf_conntrack_checksum=0 > /dev/null; fi;
#net.netfilter.nf_conntrack_tcp_ignore_invalid_rst=1; #X3

# Сбор портов
NFQWS_PORTS_TCP="$(echo $NFQWS_OPT | grep -oE 'filter-tcp=[0-9,-]+' | sed -e 's/.*=//g' -e 's/,/\n/g' | sort -un)";
NFQWS_PORTS_UDP="$(echo $NFQWS_OPT | grep -oE 'filter-udp=[0-9,-]+' | sed -e 's/.*=//g' -e 's/,/\n/g' | sort -un)";
if [ "$debug" == "1" ]; then 
echo "Отладка TCP портов: $NFQWS_PORTS_TCP" >> $dpi_list_path/DPI_logS.txt;
echo "Отладка UDP портов: $NFQWS_PORTS_UDP" >> $dpi_list_path/DPI_logS.txt;
fi;

# Добавление порта в iptables 
iptAdd() { 
if [ "$debug" == "1" ]; then echo "Отладка ipt_Add TCP/UDP: $1 Порт: $2" >> $dpi_list_path/DPI_logS.txt; fi;
iptables -t $iptname -I POSTROUTING $oifnm -p $1 $iMportD $2 $iCBo $iMark -j NFQUEUE --queue-num 200 --queue-bypass;
if [ "$uselist" == "1" ]; then 
iptables -t $iptname -I PREROUTING $iifnm -p $1 $iMportS $2 $iCBr $iMark -j NFQUEUE --queue-num 200 --queue-bypass; 
fi;
}

# multiport в порт
iptMultiPort() { # TCP/UDP; порты
if [ "$(echo $iMportD | grep -c 'multiport')" != "0" ]; then 
iptAdd "$1" "$(echo $2 | sed -e 's/ /,/g' -e 's/-/:/g')"; # Если полный iptables
else for current_port in $2; do
if [ $current_port == *-* ]; then 
for i in $(seq ${current_port%-*} ${current_port#*-}); do iptAdd "$1" "$i"; done 
else iptAdd "$1" "$current_port"; fi; done; 
fi;
}

# Использование nftables
if ! [ -e "/proc/net/ip_tables_targets" ]; then echo "Использование nftables";
nft create table $nftname; 
nft add chain $nftname post "{type filter hook postrouting priority mangle;}";
nft add rule $nftname post $oifnm tcp dport "{ $(echo $NFQWS_PORTS_TCP | sed 's/ /,/g') }" ct original packets 1-12 meta mark and 0x40000000 == 0 queue num 200 bypass;
nft add rule $nftname post $oifnm udp dport "{ $(echo $NFQWS_PORTS_UDP | sed 's/ /,/g') }" ct original packets 1-12 meta mark and 0x40000000 == 0 queue num 200 bypass;
if [ "$uselist" == "1" ]; then 
nft add chain $nftname pre "{type filter hook prerouting priority filter;}";
nft add rule $nftname pre $iifnm tcp sport "{ $(echo $NFQWS_PORTS_TCP | sed 's/ /,/g') }" ct reply packets 1-3 queue num 200 bypass;
nft add rule $nftname pre $iifnm udp sport "{ $(echo $NFQWS_PORTS_UDP | sed 's/ /,/g') }" ct reply packets 1-3 queue num 200 bypass;
fi; 

# Отладка nftables 
if [ "$debug" == "1" ]; then 
echo "Отладка nftales" >> $dpi_list_path/DPI_logS.txt; 
nft list table $nftname >> $dpi_list_path/DPI_logS.txt; 
fi;

else 
echo "Использование iptables"; 
if [ "$(cat /proc/net/ip_tables_targets | grep -c 'NFQUEUE')" == "0" ]; then
echo "Ошибка - плохой iptables, скрипт не будет работать"; exit
else

# Проверка поддержки multiport
if [ "$(cat /proc/net/ip_tables_matches | grep -c 'multiport')" != "0" ]; then 
iMportS="-m multiport --sports"; iMportD="-m multiport --dports"; 
else iMportS="--sport"; iMportD="--dport"; echo "Плохой iptables, пропуск multiport"; fi;

# Проверка поддержки connbytes
if [ "$(cat /proc/net/ip_tables_matches | grep -c 'connbytes')" != "0" ]; then 
iCBo="-m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12"; 
iCBr="-m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes 1:3"; 
else iCBo=""; iCBr=""; echo "Плохой iptables, пропуск connbytes"; fi; 

# Проверка поддержки mark
if [ "$(cat /proc/net/ip_tables_matches | grep -c 'mark')" != "0" ]; then
iMark="-m mark ! --mark 0x40000000/0x40000000"; 
else iMark=""; echo "Плохой iptables, пропуск mark";fi; 

iptMultiPort "tcp" "$NFQWS_PORTS_TCP"; 
iptMultiPort "udp" "$NFQWS_PORTS_UDP"; 

# Отладка iptables 
if [ "$debug" == "1" ]; then 
echo "Отладка iptables" >> $dpi_list_path/DPI_logS.txt; 
iptables -t $iptname -L >> $dpi_list_path/DPI_logS.txt; 
fi;
fi;

if [ "$debug" == "1" ]; then ndebug="--debug=@$dpi_list_path/DPI_logN.txt"; else ndebug=""; fi;

# Переключаем ползунки
sed -i 's/^full_system=1$/full_system=0/' "$SETTING_START_PARAMSET"
sed -i 's/^dpi_tunnel_0=1$/dpi_tunnel_0=0/' "$SETTING_START_PARAMSET"
sed -i 's/^dpi_tunnel_1=1$/dpi_tunnel_1=0/' "$SETTING_START_PARAMSET"


echo "AntiDPI служба включена"; fi;

# Если значение notification установлено, проверяем его
if [ "$notification" = "1" ]; then
    su -lp 2000 -c "cmd notification post -i file:///data/local/tmp/icon1.png -I file:///data/local/tmp/icon.png -S messaging --conversation 'System CLi' --message 'DPI Tunnel CLI:Запуск завершен!' --message 'DPI Tunnel CLI был успешно запущен в альтернативном режиме.' -t 'DPI Tunnel CLI' 'Tag' 'Запуск успешен...'" >/dev/null 2>&1
    echo "Отправка уведомления"
    
elif [ "$notification" = "0" ]; then
    echo "Уведомления выключены"
else
    echo "Некорректное значение для notification или его нет"
fi

exit 0

elif [ "$alternativel" = "0" ]; then
    echo "Альтернативный режим выключен"
else
    echo "Некорректное значение для alternativel или его нет"
fi

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

# Функция для очистки выходных файлов
initialize_output_files() {
    # Очистка всех выходных файлов
    > "$UID_OUTPUT_FILE"
    > "$UID_OUTPUT_FILE1"
    > "$UID_OUTPUT_FILE2"
    echo "Файлы $UID_OUTPUT_FILE, $UID_OUTPUT_FILE1, $UID_OUTPUT_FILE2 очищены."
}

# Функция обработки с использованием dumpsys
process_file_dumpsys() {
    input_file=$1
    output_file=$2

    echo "Обработка файла $input_file с использованием dumpsys..."
    while IFS= read -r package_name; do
        # Пропуск пустых строк
        if [ -z "$package_name" ]; then
            echo "Пропущена пустая строка"
            continue
        fi

        # Проверка, есть ли уже запись для пакета в выходном файле
        if grep -q "^$package_name=" "$output_file"; then
            echo "UID для $package_name уже найден, пропуск..."
            continue
        fi

        echo "Обработка пакета: $package_name"
        # Получение UID через dumpsys
        uid=$(dumpsys package "$package_name" | grep "userId=" | awk -F'=' '{print $2}' | head -n 1)

        # Проверка, найден ли UID
        if [ -n "$uid" ]; then
            echo "$package_name=$uid" >> "$output_file"
            sync # Принудительная запись данных на диск
            echo "Записан UID для $package_name через dumpsys: $uid"
        else
            echo "UID не найден для пакета $package_name через dumpsys"
        fi
    done < "$input_file"
}

# Функция обработки с использованием stat
process_file_stat() {
    input_file=$1
    output_file=$2

    echo "Обработка файла $input_file с использованием stat..."
    while IFS= read -r package_name; do
        # Пропуск пустых строк
        if [ -z "$package_name" ]; then
            echo "Пропущена пустая строка"
            continue
        fi

        # Проверка, есть ли уже запись для пакета в выходном файле
        if grep -q "^$package_name=" "$output_file"; then
            echo "UID для $package_name уже найден, пропуск..."
            continue
        fi

        echo "Обработка пакета: $package_name"
        # Получение UID через stat
        uid=$(stat -c '%u' "/data/data/$package_name" 2>/dev/null)

        # Проверка, найден ли UID
        if [ -n "$uid" ]; then
            echo "$package_name=$uid" >> "$output_file"
            sync # Принудительная запись данных на диск
            echo "Записан UID для $package_name через stat: $uid"
        else
            echo "UID не найден для пакета $package_name через stat"
        fi
    done < "$input_file"
}

parsing_uid() {

# Функция для вычисления sha256
calculate_sha256() {
    sha256sum "$1" | awk '{print $1}'
}

# Проверяем наличие рабочего каталога
if [ ! -d "$SHA256_WORKING_DIR" ]; then
    echo "Рабочая директория отсутствует. Пропуск."
    return 0
fi

# Определяем файлы для проверки
SHA256_FILES="zapret_uid uid_program0 uid_program1"

# Если файл flag.sha256 отсутствует, создаём его и выводим "Проверено"
if [ ! -f "$SHA256_FLAG_FILE" ]; then
    echo "Файл $SHA256_FLAG_FILE отсутствует. Создание..."
    > "$SHA256_FLAG_FILE"
fi

# Читаем текущие значения хешей из flag.sha256
SHA256_RECORDED_HASHES=""
if [ -f "$SHA256_FLAG_FILE" ]; then
    SHA256_RECORDED_HASHES=$(cat "$SHA256_FLAG_FILE")
fi

# Проверяем и обновляем хеши файлов
SHA256_CHANGED=0
> "$SHA256_FLAG_FILE"  # Очищаем файл для перезаписи
for SHA256_FILE in $SHA256_FILES; do
    SHA256_FILE_PATH="$SHA256_WORKING_DIR/$SHA256_FILE"
    if [ -f "$SHA256_FILE_PATH" ]; then
        SHA256_CURRENT_HASH=$(calculate_sha256 "$SHA256_FILE_PATH")
        echo "$SHA256_FILE=$SHA256_CURRENT_HASH" >> "$SHA256_FLAG_FILE"
        # Проверяем, изменился ли хеш
        if echo "$SHA256_RECORDED_HASHES" | grep -q "$SHA256_FILE=$SHA256_CURRENT_HASH"; then
            continue
        else
            SHA256_CHANGED=1
        fi
    fi
done

# Выводим результат проверки
if [ "$SHA256_CHANGED" -eq 1 ]; then
    # Очистка выходных файлов
    initialize_output_files
    
    # Вызов функции для загрузки конфигураций
    if [ "$dpi_tunnel_0" = "1" ]; then
        echo "DPI tunnel 0 включён iptables"
        process_file_dumpsys "$UID_INPUT_FILE" "$UID_OUTPUT_FILE"
        process_file_dumpsys "$UID_INPUT_FILE1" "$UID_OUTPUT_FILE1"
        :
    elif [ "$dpi_tunnel_0" = "0" ]; then
        echo "DPI tunnel 0 выключен iptables"
    else
        echo "Некорректное значение для dpi_tunnel_0 или его нет"
    fi
    
    if [ "$dpi_tunnel_1" = "1" ]; then
        echo "DPI tunnel 1 включён iptables"
        process_file_stat "$UID_INPUT_FILE" "$UID_OUTPUT_FILE"
        process_file_stat "$UID_INPUT_FILE1" "$UID_OUTPUT_FILE1"
        :
    elif [ "$dpi_tunnel_1" = "0" ]; then
        echo "DPI tunnel 1 выключен iptables"
    else
        echo "Некорректное значение для dpi_tunnel_1 или его нет"
    fi
    
    # Обработка файлов с использованием dumpsys zapret 
    process_file_dumpsys "$UID_INPUT_FILE2" "$UID_OUTPUT_FILE2"
    # Обработка файлов с использованием stat zapret 
    process_file_stat "$UID_INPUT_FILE2" "$UID_OUTPUT_FILE2"

    echo "Файлы списка uid были изменены, обновляю sha256"
else
    echo "Пропускаю перебор uid"
fi

}

iptables_zapret_default_full() {

# Чтение и проверка для full_system
if [ "$full_system" = "1" ]; then
    echo "Применение правил по всей системе"

# Установка правил в цепочке PREROUTING
iptables -t mangle -A PREROUTING -p udp --sport 6969 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass
iptables -t mangle -A PREROUTING -p udp --sport 443 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass
iptables -t mangle -A PREROUTING -p tcp --sport 8000 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass
iptables -t mangle -A PREROUTING -p tcp --sport 443 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass
iptables -t mangle -A PREROUTING -p tcp --sport 80 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass

# Установка правил в цепочке POSTROUTING
iptables -t mangle -A POSTROUTING -p udp --dport 6969 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass
iptables -t mangle -A POSTROUTING -p udp --dport 443 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass
iptables -t mangle -A POSTROUTING -p tcp --dport 8000 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass
iptables -t mangle -A POSTROUTING -p tcp --dport 443 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass
iptables -t mangle -A POSTROUTING -p tcp --dport 80 -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass

elif [ "$full_system" = "0" ]; then
    echo "Применение правил обычно"


# Проверяем, пуст ли файл
if [ ! -s "$FILE_CHESK_ZAPRET_UID" ]; then
  echo "Файл пустой"
  
    setenforce 0
    /system/bin/am start -a android.intent.action.MAIN -e toasttext "Zapret: по какой-то причине файл пустой..." -n bellavita.toast/.MainActivity
    setenforce 1

    setenforce 0
    /system/bin/am start -a android.intent.action.MAIN -e toasttext "Zapret: Применяется правила для всей системы..." -n bellavita.toast/.MainActivity
    setenforce 1

  # Проверка поддержки iptables
  use_iptables=$(iptables -t mangle -A POSTROUTING -p tcp -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12 -j ACCEPT 2>/dev/null && echo "2" || echo "3")
  [ "$use_iptables" = "2" ] && iptables -t mangle -D POSTROUTING -p tcp -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12 -j ACCEPT 2>/dev/null

  # Переменные для правил
  tcp_ports="80,443"
  udp_ports="50000-50200,443"
  
  #Меняем переменную 
  sed -i 's/^full_system=0$/full_system=1/' "$SETTING_START_PARAMSET"
  
  # Либеральный режим TCP
  sysctl net.netfilter.nf_conntrack_tcp_be_liberal=1 > /dev/null

  # Упрощённые функции добавления правил iptables
  iptAdd() {
      proto=$1
      port=$2
      cbOrig="-m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:12 -m mark ! --mark 0x40000000/0x40000000"
      cbReply="-m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes 1:6 -m mark ! --mark 0x40000000/0x40000000"
      [ "$use_iptables" = "3" ] && cbOrig="" && cbReply=""
      iptables -t mangle -I POSTROUTING -p $proto --dport $port $cbOrig -j NFQUEUE --queue-num 200 --queue-bypass
      iptables -t mangle -I PREROUTING -p $proto --sport $port $cbReply -j NFQUEUE --queue-num 200 --queue-bypass
  }

  iptMultiPort() {
      proto=$1
      ports=$2
      for port in $(echo $ports | tr ',' ' '); do
          if echo "$port" | grep -q '-'; then
              start=$(echo "$port" | cut -d'-' -f1)
              end=$(echo "$port" | cut -d'-' -f2)
              for i in $(seq $start $end); do
                  iptAdd $proto $i
              done
          else
              iptAdd $proto $port
          fi
      done
  }

  # Настройка правил
  iptMultiPort "tcp" "$tcp_ports"
  iptMultiPort "udp" "$udp_ports"

else
  # Если файл не пустой, пропускаем выполнение некоторых шагов
  echo "Файл не пустой, пропускаем замену и настройку iptables по всей системе."

fi




# Настройки
QUEUE=200
MARK=0x40000000


#########################
# Функция проверки iptables
check_iptables() {
    if ! command -v iptables >/dev/null 2>&1; then
        echo "iptables не найден, завершаем выполнение."
        exit 1
    fi
    echo "iptables найден: $(iptables --version)"
}

#########################
# Функция для проверки поддержки цепочки в таблице
chain_supported() {
    local table="$1"
    local chain="$2"
    iptables -t "$table" -L "$chain" >/dev/null 2>&1
    return $?
}

#########################
# Функция для проверки и добавления правила
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

#########################
# Функции для установки индивидуальных inbound правил по UID

# OLD метод: правило без проверки метки
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

# NEW метод: правило с проверкой метки (MARK=0x40000000)
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

#########################
# Функции для установки индивидуальных outbound правил по UID

# OLD метод: правило без проверки метки
add_outbound_rule_uid_old() {
    local uid="$1"
    local rule="-m owner --uid-owner $uid -j NFQUEUE --queue-num $QUEUE --queue-bypass"
    local added=1
    for table in mangle nat; do
        # Сначала пробуем OUTPUT, если нет – PREROUTING
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

# NEW метод: правило с проверкой метки (MARK=0x40000000)
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

#########################
# Функции для добавления fallback (общих) правил

# Fallback inbound: общее правило с проверкой метки, в цепочке PREROUTING или INPUT
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

# Fallback outbound: общее правило с проверкой метки, в цепочке OUTPUT или PREROUTING
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

#########################
# Основная логика

check_iptables

INDIVIDUAL_INBOUND_TOTAL=0
INDIVIDUAL_INBOUND_SUCCESS=0
INDIVIDUAL_OUTBOUND_TOTAL=0
INDIVIDUAL_OUTBOUND_SUCCESS=0

while IFS='=' read -r app_name uid; do
    [ -z "$app_name" ] && continue
    [ -z "$uid" ] && continue
    echo "Обработка приложения \"$app_name\" (UID: $uid)..."
    
    # Inbound: сначала OLD метод, затем NEW, если не удалось
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

    # Outbound: сначала OLD метод, затем NEW
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

done < "$UID_OUTPUT_FILE2"

# Если индивидуальные inbound правила не установлены (для всех UID), добавляем общий fallback
if [ "$INDIVIDUAL_INBOUND_SUCCESS" -lt 1 ]; then
    echo "Индивидуальные inbound правила не установлены, пробую добавить общий inbound fallback..."
    if ! add_common_inbound_rule_fallback; then
        echo "❌ Не удалось добавить ни индивидуальное, ни общий inbound правило!"
    fi
fi

# Если индивидуальные outbound правила не установлены, добавляем общий fallback
if [ "$INDIVIDUAL_OUTBOUND_SUCCESS" -lt 1 ]; then
    echo "Индивидуальные outbound правила не установлены, пробую добавить общий outbound fallback..."
    if ! add_common_outbound_rule_fallback; then
        echo "❌ Не удалось добавить ни индивидуальное, ни общий outbound правило!"
    fi
fi

echo "✅ Завершено. INDIVIDUAL_INBOUND: $INDIVIDUAL_INBOUND_SUCCESS/$INDIVIDUAL_INBOUND_TOTAL, INDIVIDUAL_OUTBOUND: $INDIVIDUAL_OUTBOUND_SUCCESS/$INDIVIDUAL_OUTBOUND_TOTAL."




else
    echo "Некорректное значение для full_system или его нет"
fi

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

# Вызов функции для загрузки конфигураций
if [ "$dpi_tunnel_0" = "1" ]; then
    echo "DPI tunnel 0 включён запуск"
    
# Чтение выходного файла и применение правил iptables
while IFS='=' read -r app_name uid; do
            # Проверка, что UID - это число
            if [[ "$uid" == [0-9]* ]]; then
                # Применение правил iptables
                iptables -t nat -A OUTPUT -p tcp --dport 80 -m owner --uid-owner "$uid" -j DNAT --to-destination 127.0.0.1:1123
                iptables -t nat -A OUTPUT -p tcp --dport 443 -m owner --uid-owner "$uid" -j DNAT --to-destination 127.0.0.1:1123
                echo "Установлено правило для UID: $uid"
            else
                echo "Пропущена строка без цифр: $app_name=$uid"
            fi
        done < "$UID_OUTPUT_FILE"
    
    :
elif [ "$dpi_tunnel_0" = "0" ]; then
    echo "DPI tunnel 0 выключен запуск"
else
    echo "Некорректное значение для dpi_tunnel_0 или его нет"
fi

if [ "$dpi_tunnel_1" = "1" ]; then
    echo "DPI tunnel 1 включён запуск"
    
# Чтение выходного файла и применение правил iptables
while IFS='=' read -r app_name uid; do
            # Проверка, что UID - это число
            if [[ "$uid" == [0-9]* ]]; then
                # Применение правил iptables
                iptables -t nat -A OUTPUT -p tcp --dport 80 -m owner --uid-owner "$uid" -j DNAT --to-destination 127.0.0.1:1124
                iptables -t nat -A OUTPUT -p tcp --dport 443 -m owner --uid-owner "$uid" -j DNAT --to-destination 127.0.0.1:1124
                echo "Установлено правило для UID: $uid"
            else
                echo "Пропущена строка без цифр: $app_name=$uid"
            fi
        done < "$UID_OUTPUT_FILE1"
    
    :
elif [ "$dpi_tunnel_1" = "0" ]; then
    echo "DPI tunnel 1 выключен запуск"
else
    echo "Некорректное значение для dpi_tunnel_1 или его нет"
fi

}

notification_start_services() {

# Если значение notification установлено, проверяем его
if [ "$notification" = "1" ]; then
    su -lp 2000 -c "cmd notification post -i file:///data/local/tmp/icon1.png -I file:///data/local/tmp/icon.png -S messaging --conversation 'System' --message 'ZDT-D:Запуск завершен!' -t 'CLI' 'Tag' 'Запуск успешен...'" >/dev/null 2>&1
    echo "Отправка уведомления"
    
elif [ "$notification" = "0" ]; then
    echo "Уведомления выключены"
else
    echo "Некорректное значение для notification или его нет"
fi
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
    :
else
    echo "Скрипт выключен"
    exit 0
    :
fi

}








# Ожидание загрузки устройства 
boot_completed
# Проверка начиличия конфликтующего модуля 
check_modules
# Ожидание когда пользователь разблокирует экран 
start_system_final

#!/system/bin/sh

# Проверка текущего состояния SELinux
SELINUX_STATE=$(getenforce)

# Если SELinux в permissive (0), включаем enforcing (1)
if [ "$SELINUX_STATE" = "Permissive" ]; then
    setenforce 1
fi

# Отправка тост о начале запуска
notification_toast_start
# Чтение конфигурации 
read_params
# Проверка разрешен ли запуск 
start_stop_service
# Запуск nfqws с конфигурацией 
start_zapret
# Альтернативные правила iptables 
alt_start_iptables
# Чтение конфигурации DPI tunnel 
start_load_config
# Перебор идентификаторов приложений (uid)
parsing_uid
# Правила iptables по всей системе или по умолчанию точечно 
iptables_zapret_default_full
# Загрузка конфигурации DPI tunnel 
load_config_dpi_tunnel
# Отправка уведомления о запуске сервиса 
notification_start_services
# Проверка обновлений
internetcheck
check_update
# Проверка сервиса DPI tunnel и его перезапуск
restarting_dpi_tunnel

# Если SELinux был изменен, возвращаем его в permissive (0)
if [ "$SELINUX_STATE" = "Permissive" ]; then
    setenforce 0
fi

