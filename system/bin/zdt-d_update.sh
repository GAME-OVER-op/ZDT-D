#!/system/bin/sh
DAM="/data/adb/modules/ZDT-D"
WORKING_FOLDER="$DAM/working_folder"
BIN_DIR="$DAM/system/bin"
TMP_DIR="/data/local/tmp"
MODULE_PROP="$DAM/module.prop"
UPDATE_JSON_URL="https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/Update/update.json"
TEMP_JSON="$TMP_DIR/update.json"
CURL_BINARIES="$DAM/files/bin/curl"
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
    if command -v wget >/dev/null 2>&1; then
        wget -q "$UPDATE_JSON_URL" -O "$TEMP_JSON"
    elif command -v curl >/dev/null 2>&1; then
        curl -fsSL "$UPDATE_JSON_URL" -o "$TEMP_JSON"
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




# Проверка доступности интернета с задержкой 15 секунд и интервалом проверки 5 секунд
internetcheck "15" "5"

# Если интернет доступен (индикатор равен 1), запускается проверка обновлений
if [ "$internet_echeck_status" -eq 1 ]; then
    echo "Интернет доступен, запускаем проверку обновлений..."
    check_update
else
    echo "Интернет недоступен, обновление не будет выполнено."
fi



