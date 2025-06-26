#!/system/bin/sh
# shellcheck disable=SC2034
SKIPUNZIP=1














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
    MSG_HEADER="############################################"
    MSG_DEVICE_INFO="# Информация об устройстве:"
    MSG_MANUFACTURER="#   Производитель: "
    MSG_MODEL="#   Модель: "
    MSG_ANDROID_VERSION="#   Версия Android: "
    MSG_SYSTEM_BUILD="#   Сборка системы: "
    MSG_CPU_ABI="#   Архитектура CPU: "
    MSG_DEVICE_BOARD="#   Плата устройства: "
    MSG_DEVICE_DEVICE="#   Устройство: "
    MSG_ERROR_ANDROID="# Ошибка: требуется Android 7 и выше. Текущая версия:"
    MSG_EXIT_ANDROID_INSTALL="Версия Android слишком старая. Установка прервана."
    MSG_ANDROID_VERSION_CHESK="# Версия Android"  MSG_INSTALL_YES="удовлетворяет требованиям."
    MSG_ANDROID_ERROR_INFO="# Не удалось определить версию Android. Пропускаем проверку минимальной версии."
    MSG_CHECK_ANDROID_TV_YES="# Обнаружено устройство Android TV."
    MSG_WARNING_INSTALL_ANDROID_TV="# Предупреждение: модуль может не запуститься корректно на ТВ приставке (устройство может упасть в бутлуп)."
    MSG_TV_WARNING="# Такие случаи встречаются редко, но обратите внимание на это сообщение."
    MSG_WARNING_IF="Если вам повезет, то при загрузке устройства нажмите кнопку громкости минус (безопасная загрузка)."
    MSG_CHECK_ANDROID_TV_NO="# Устройство не определено как Android TV."
    MSG_INSTALL_CONTINUED="# Продолжаем установку..."
    MSG_VERSION_ZAPRET="Текущая версия zapret:"
    MSG_CHECK_ZAPRET_ERROR="nfqws не найден. Возможно, zapret еще не установлен."
    MSG_CHECK_UPDATE_ZAPRET="Получаем информацию о последней версии..."
    MSG_CHECK_ZAPRET_UPDATE="Ошибка: ни curl, ни wget не найдены в системе."
    MSG_ERROR_CHECK_UPDATE_ZAPRET="Не удалось получить последнюю версию. Пропускаем этап обновления."
    MSG_LAST_ZAPRET="Последняя версия:"
    MSG_INSTALLED_ZAPRET="Установлена последняя версия" MSG_NO_UPDATE_REQUIRED="Обновление не требуется."
    MSG_NEW_VERSION_AVAILABLE="Доступна новая версия:"
    MSG_DOWNLOAD_VERSION="Скачиваем версию"
    MSG_ERROR_DAWNLOAD_VERSION="Ошибка: ни curl, ни wget не найдены в системе. Пропускаем этап загрузки архива."
    MSG_SUCCESSFULLY_DOWNLOAD="Архив успешно загружен:"
    MSG_UNZIP_ARHIVE="Распаковываем архив..."
    MSG_SUCCESSFULLY_UNZIP="Архив распакован в папку:"
    MSG_ERROR_UNZIP_ARHIVE="Ошибка при распаковке архива. Пропускаем этап распаковки."
    MSG_ERROR_DOWNLOAD_ARHIVE="Не удалось загрузить архив. Пропускаем этап загрузки архива."
    MSG_COPY_FILES_ZAPRET="Копируем файлы из" MSG_COPY_FILES_ZAPRET_TO="в"
    MSG_SUCCESSFULLY_COPY_FILES="Файлы успешно скопированы в"
    MSG_BINARIES_NO_CHECK="Папка binaries не найдена:"
    MSG_SKIP_COPY_BINARIES="Пропускаем копирование бинарных файлов."
    MSG_DELETE_EXCEPT_ANDROID="Удаляем все папки, кроме android-*..."
    MSG_SAVE="Сохраняем:"
    MSG_DELETE="Удаляем:"
    MSG_CLEANING="Очистка завершена."
    MSG_EXTRACTION_FILES_MODULES="- Извлечение файлов модуля"
    MSG_CONFLICT_ZAPRET="Здравствуйте, у вас установлен модуль zapret, удалите его пожалуйста."
    MSG_CONFLICT_ZAPRET1="Почему?"
    MSG_CONFLICT_ZAPRET2="Я думаю, модуль может конфликтовать, что приведёт к ошибкам работы..."
    MSG_CONFLICT_DPICLI="Здравствуйте, у вас установлен модуль Zapret DPI Tunnel and Dns Comss, удалите его пожалуйста."
    MSG_CONFLICT_DPICLI1="Почему?"
    MSG_CONFLICT_DPICLI2="Это предыдущий форк модуля, он больше не поддерживается..."
    MSG_NO_CONFLICT="- Конфликтующие модули не найдены, продолжаем работу..."
    MSG_WWW_PATH_ERROR="Ошибка: переменная WWW_PATH не задана."
    MSG_ERROR_CREATED_FOLDERS="Ошибка: не удалось создать папки"
    MSG_ERROR_MOVING_FILES="Ошибка: не удалось переместить файлы."
    MSG_MOVING_FILES_UPDATE="- Перемещение файлов для обновления."
    MSG_ERROR_FOLDER="Ошибка: папка" MSG_ERROR_FOLDER1="не существует или пуста."
    MSG_COPY_FILES_PHP=" Копирование файлов..."
    MSG_SETTING_PERMITS="Настройка разрешений для файлов..."
    MSG_CREATED_EXECUTABLE_DERICTORY="* Создание исполняемой директории."
    MSG_CREATED_EXECUTABLE_DERICTORY1="* Создание директории для конфигурационных файлов."
    MSG_COPYEXECUTABLE_BINARE_FILE="* Копирование исполняемого бинарного файла."
    MSG_ERROR_ABSEND="[ОШИБКА] Бинарный файл для устройства с архитектурой" MSG_ERROR_ABSEND1="отсутствует! Пропускаем этот этап."
    MSG_COPY_DNS_SCRIPT="* Копирование конфигурационных файлов в директорию dnscrypt-proxy."
    MSG_COPY_DNS_SCRIPT_ERROR="[ОШИБКА] Конфигурационный файл (.toml) отсутствует! Пропускаем этот этап."
    MSG_SETTING_rights_DNS_SCRIPT="* Настройка прав доступа для dnscrypt-proxy."
    MSG_OFF_SYSTRM_DNS="* Отключение режима Private DNS (Android 9+)."
    MSG_DELETE_NOT_USED_FILES="* Очистка: удаление неиспользуемых файлов."
    MSG_WIFI_OFF_ON="Wi-Fi выключен. Включаем..."
    MSG_ERROR_ON_WIFI="Не удалось включить Wi-Fi"
    MSG_PLEASE_ON_WIFI="Пожалуйста включите Wi-Fi вручную"
    MSG_WIFI_SUCCESSFULLY_ON="Wi-Fi успешно включен."
    MSG_WIFI_ON="Wi-Fi уже включен"
    MSG_CONNECTED_WIFI="Подключено к Wi-Fi:"
    MSG_EXPECTATION_CONNECTED_WIFI="Ожидание подключения к Wi-Fi..."
    MSG_WIFI_ON_NO_CONNECTED_WIFI="Wi-Fi включен, но не подключился к сети."
    MSG_INTERNET_AVAILABLE="Интернет доступен."
    MSG_EXPECTATION_APPEARANCE_INRERNET="Ожидание появления интернета..."
    MSG_DOWNLOAD_BLOSK_RUSSIA="! - Начинаю загрузку списка blocked list Russia"
    MSG_ERROR_CREATED_DERRIKTORY="Ошибка: не удалось создать директорию"
    MSG_DER="Директория" MSG_CREAT="создана."
    MSG_DOWN_FILE="Загружаем файл из" MSG_TO_TIME_FILE="во временный файл"
    MSG_ERROR_DOWN_CURL="Ошибка: curl не смог загрузить файл. Пропускаем этот этап."
    MSG_ERROR_DOWN_WGET="Ошибка: wget не смог загрузить файл. Пропускаем этот этап."
    MSGMSG_ERROR_DOWN_CURL_WGET="Ошибка: ни curl, ни wget не найдены в системе. Пропускаем этап загрузки файла."
    MSG_DOWN_FILE_EMPTY="Скачанный файл пустой. Пропускаем этап загрузки файла."
    MSG_DIWNL_FILE="Файл успешно загружен:"
    MSG_ERROR_MOVIN_FILE="Ошибка: не удалось переместить файл в"
    MSG_DOWNLOAD_TO="Файл скачан в:"
    MSG_MOVING_FILE_TO="Файл перемещён в:"
    MSG_NOT_FOUND="не найден. Пропускаем перемещение."
    MSG_FILE="Файл"
    MSG_SKIP_DOWNLOAD_FILE="Этап загрузки файла пропущен, так как файл не был скачан."
    MSG_INTERNET_NOT="Интернета нет."
    MSG_UNSUPPORTED_ARCHITECTURE="Неподдерживаемая архитектура:"
    MSG_CREATED_DIR_BIN_FILES="* Создание директории для бинарных файлов."
    MSG_COPY_BIN_FILES="* Копирование бинарных файлов для архитектуры"
    MSG_ERROR_COPY_BIN_FILES="Ошибка: не удалось скопировать бинарные файлы. Проверьте исходную директорию."
    MSG_ERROR_RIGHT="Ошибка: не удалось установить права доступа."
    MSG_DELETE_FOLDER_LIBS="* Удаление папки libs."
    MSG_ERROR_DELETE_FOLDER_LIBS="Ошибка: не удалось удалить папку libs."
    MSG_UNZIP_DONE="* Извлечение завершено, выполняется очистка..."
    MSG_CHECK_FILDER_ICON="* Проверка наличия директории назначения для иконок."
    MSG_COPY_ICON="* Копирование иконок в"
    MSG_DELETE_FOLDER_ICON="* Удаление папки с иконками."
    MSG_ERROR_DELETE_FOLDER_ICON="Ошибка: не удалось удалить папку"
    MSG_COPY_CLEAR_DONE="* Копирование и очистка завершены успешно."
    MSG_IBSTALL_BELAVITA="- Установка Bellavita Toast"
    MSG_ERROR_BELAVITA_SELINUX="! Невозможно установить Bellavita Toast из-за ограничений SELinux"
    MSG_INSTALL_MANUALY="! Пожалуйста, установите приложение вручную после установки."
    MSG_ERROR_KSU_SELINUX="! Невозможно установить KSU WebUI из-за ограничений SELinux"
    MSG_ROOT_KSU="- Пожалуйста, предоставьте root-доступ для KSU WebUI"
    MSG_ROOT_DOSTUPE_KSU="Пожалуйста, предоставьте root доступ приложению WebUI"
    MSG_CLEAR_RESIDUAL_FILES="! Удаление остаточных файлов apk и папок"
    MSG_SETTING_WEB="-     Веб доступ для настройки модуля"
    MSG_ADDRES_WEB="-     Адрес: http://127.0.0.1:1137"
    MSG_WELCOME="-     Приятного пользования!"
    MSG_UPDATE_MODULE="- Обновление модуля"
    MSG_INSTALL_MODULE="- Первая установка."
    MSG_STOP_SERVICE="Остановка сервиса."
    MSG_EXTR="Извлечение"
    MSG_WEB_UI_TV="Обнаружено Android TV, установка KSU WebUI пропускается"
    MSG_MAGISK_INSTALL_WEBUI="Обнаружен Magisk, установка KSU WebUI для Magisk"
else
    
    MSG_HEADER="############################################"
    MSG_DEVICE_INFO="# Device Information:"
    MSG_MANUFACTURER="#   Manufacturer: "
    MSG_MODEL="#   Model: "
    MSG_ANDROID_VERSION="#   Android Version: "
    MSG_SYSTEM_BUILD="#   System Build: "
    MSG_CPU_ABI="#   CPU Architecture: "
    MSG_DEVICE_BOARD="#   Device Board: "
    MSG_DEVICE_DEVICE="#   Device:"
    MSG_ERROR_ANDROID="# Error: Android 7 or higher is required. Current version:"
    MSG_EXIT_ANDROID_INSTALL="Android version too old. Installation aborted."
    MSG_ANDROID_VERSION_CHESK="# Android Version"
    MSG_INSTALL_YES="meets the requirements."
    MSG_ANDROID_ERROR_INFO="# Could not determine Android version. Skipping minimum version check."
    MSG_CHECK_ANDROID_TV_YES="# Detected Android TV device."
    MSG_WARNING_INSTALL_ANDROID_TV="# Warning: The module may not run correctly on an Android TV set-top box (device might fall into a bootloop)."
    MSG_TV_WARNING="# These cases are rare, but please note this message."
    MSG_WARNING_IF="If you're lucky, press the volume down button during boot (safe mode)."
    MSG_CHECK_ANDROID_TV_NO="# Device is not recognized as Android TV."
    MSG_INSTALL_CONTINUED="# Continuing installation..."
    MSG_VERSION_ZAPRET="Current zapret version:"
    MSG_CHECK_ZAPRET_ERROR="nfqws not found. Perhaps zapret is not installed yet."
    MSG_CHECK_UPDATE_ZAPRET="Retrieving information on the latest version..."
    MSG_CHECK_ZAPRET_UPDATE="Error: neither curl nor wget found on the system."
    MSG_ERROR_CHECK_UPDATE_ZAPRET="Failed to retrieve the latest version. Skipping the update step."
    MSG_LAST_ZAPRET="Latest version:"
    MSG_INSTALLED_ZAPRET="Latest version installed"
    MSG_NO_UPDATE_REQUIRED="No update required."
    MSG_NEW_VERSION_AVAILABLE="New version available:"
    MSG_DOWNLOAD_VERSION="Downloading version"
    MSG_ERROR_DAWNLOAD_VERSION="Error: neither curl nor wget found on the system. Skipping the archive download step."
    MSG_SUCCESSFULLY_DOWNLOAD="Archive downloaded successfully:"
    MSG_UNZIP_ARHIVE="Extracting archive..."
    MSG_SUCCESSFULLY_UNZIP="Archive extracted to folder:"
    MSG_ERROR_UNZIP_ARHIVE="Error extracting archive. Skipping extraction step."
    MSG_ERROR_DOWNLOAD_ARHIVE="Failed to download archive. Skipping the archive download step."
    MSG_COPY_FILES_ZAPRET="Copying files from"
    MSG_COPY_FILES_ZAPRET_TO="to"
    MSG_SUCCESSFULLY_COPY_FILES="Files copied successfully to"
    MSG_BINARIES_NO_CHECK="Folder 'binaries' not found:"
    MSG_SKIP_COPY_BINARIES="Skipping copying of binary files."
    MSG_DELETE_EXCEPT_ANDROID="Deleting all folders except those starting with android-*..."
    MSG_SAVE="Saving:"
    MSG_DELETE="Deleting:"
    MSG_CLEANING="Cleanup completed."
    MSG_EXTRACTION_FILES_MODULES="- Extracting module files"
    MSG_CONFLICT_ZAPRET="Hello, you have the zapret module installed. Please remove it."
    MSG_CONFLICT_ZAPRET1="Why?"
    MSG_CONFLICT_ZAPRET2="I think the module may conflict, which could lead to operational errors..."
    MSG_CONFLICT_DPICLI="Hello, you have the Zapret DPI Tunnel and Dns Comss module installed. Please remove it."
    MSG_CONFLICT_DPICLI1="Why?"
    MSG_CONFLICT_DPICLI2="This is an older fork of the module and is no longer supported..."
    MSG_NO_CONFLICT="- No conflicting modules found, proceeding..."
    MSG_WWW_PATH_ERROR="Error: WWW_PATH variable is not set."
    MSG_ERROR_CREATED_FOLDERS="Error: failed to create folders"
    MSG_ERROR_MOVING_FILES="Error: failed to move files."
    MSG_MOVING_FILES_UPDATE="- Moving files for update."
    MSG_ERROR_FOLDER="Error: folder"
    MSG_ERROR_FOLDER1="does not exist or is empty."
    MSG_COPY_FILES_PHP=" Copying files..."
    MSG_SETTING_PERMITS="Setting file permissions..."
    MSG_CREATED_EXECUTABLE_DERICTORY="* Creating executable directory."
    MSG_CREATED_EXECUTABLE_DERICTORY1="* Creating directory for configuration files."
    MSG_COPYEXECUTABLE_BINARE_FILE="* Copying executable binary file."
    MSG_ERROR_ABSEND="[ERROR] Binary file for device with architecture"
    MSG_ERROR_ABSEND1="is missing! Skipping this step."
    MSG_COPY_DNS_SCRIPT="* Copying configuration files to the dnscrypt-proxy directory."
    MSG_COPY_DNS_SCRIPT_ERROR="[ERROR] Configuration file (.toml) is missing! Skipping this step."
    MSG_SETTING_rights_DNS_SCRIPT="* Setting access rights for dnscrypt-proxy."
    MSG_OFF_SYSTRM_DNS="* Disabling Private DNS mode (Android 9+)."
    MSG_DELETE_NOT_USED_FILES="* Cleanup: deleting unused files."
    MSG_WIFI_OFF_ON="Wi-Fi is off. Turning it on..."
    MSG_ERROR_ON_WIFI="Failed to enable Wi-Fi"
    MSG_PLEASE_ON_WIFI="Please enable Wi-Fi manually"
    MSG_WIFI_SUCCESSFULLY_ON="Wi-Fi enabled successfully."
    MSG_WIFI_ON="Wi-Fi is already on"
    MSG_CONNECTED_WIFI="Connected to Wi-Fi:"
    MSG_EXPECTATION_CONNECTED_WIFI="Waiting for Wi-Fi connection..."
    MSG_WIFI_ON_NO_CONNECTED_WIFI="Wi-Fi is on, but not connected to any network."
    MSG_INTERNET_AVAILABLE="Internet is available."
    MSG_EXPECTATION_APPEARANCE_INRERNET="Waiting for internet connectivity..."
    MSG_DOWNLOAD_BLOSK_RUSSIA="! - Starting download of the blocked list for Russia"
    MSG_ERROR_CREATED_DERRIKTORY="Error: failed to create directory"
    MSG_DER="Directory"
    MSG_CREAT="created."
    MSG_DOWN_FILE="Downloading file from"
    MSG_TO_TIME_FILE="to a temporary file"
    MSG_ERROR_DOWN_CURL="Error: curl failed to download the file. Skipping this step."
    MSG_ERROR_DOWN_WGET="Error: wget failed to download the file. Skipping this step."
    MSGMSG_ERROR_DOWN_CURL_WGET="Error: neither curl nor wget found on the system. Skipping file download step."
    MSG_DOWN_FILE_EMPTY="Downloaded file is empty. Skipping file download step."
    MSG_DIWNL_FILE="File downloaded successfully:"
    MSG_ERROR_MOVIN_FILE="Error: failed to move file to"
    MSG_DOWNLOAD_TO="File downloaded to:"
    MSG_MOVING_FILE_TO="File moved to:"
    MSG_NOT_FOUND="not found. Skipping move."
    MSG_FILE="File"
    MSG_SKIP_DOWNLOAD_FILE="File download step skipped as the file was not downloaded."
    MSG_INTERNET_NOT="No internet connection."
    MSG_UNSUPPORTED_ARCHITECTURE="Unsupported architecture:"
    MSG_CREATED_DIR_BIN_FILES="* Creating directory for binary files."
    MSG_COPY_BIN_FILES="* Copying binary files for architecture"
    MSG_ERROR_COPY_BIN_FILES="Error: failed to copy binary files. Check the source directory."
    MSG_ERROR_RIGHT="Error: failed to set permissions."
    MSG_DELETE_FOLDER_LIBS="* Deleting the libs folder."
    MSG_ERROR_DELETE_FOLDER_LIBS="Error: failed to delete the libs folder."
    MSG_UNZIP_DONE="* Extraction completed, cleaning up..."
    MSG_CHECK_FILDER_ICON="* Checking for the destination directory for icons."
    MSG_COPY_ICON="* Copying icons to"
    MSG_DELETE_FOLDER_ICON="* Deleting icon folder."
    MSG_ERROR_DELETE_FOLDER_ICON="Error: failed to delete folder"
    MSG_COPY_CLEAR_DONE="* Copying and cleanup completed successfully."
    MSG_IBSTALL_BELAVITA="- Installing Bellavita Toast"
    MSG_ERROR_BELAVITA_SELINUX="! Unable to install Bellavita Toast due to SELinux restrictions"
    MSG_INSTALL_MANUALY="! Please install the application manually after installation."
    MSG_ERROR_KSU_SELINUX="! Unable to install KSU WebUI due to SELinux restrictions"
    MSG_ROOT_KSU="- Please grant root access for KSU WebUI"
    MSG_ROOT_DOSTUPE_KSU="Please grant root access to the WebUI application"
    MSG_CLEAR_RESIDUAL_FILES="! Deleting residual apk files and folders"
    MSG_SETTING_WEB="-     Web access for module configuration"
    MSG_ADDRES_WEB="-     Address: http://127.0.0.1:1137"
    MSG_WELCOME="-     Enjoy!"
    MSG_UPDATE_MODULE="- Updating module"
    MSG_INSTALL_MODULE="- First installation."
    MSG_STOP_SERVICE="Stopping the service."
    MSG_EXTR="Extraction"
    MSG_WEB_UI_TV="Android TV detected, KSU WebUI installation skipped"
    MSG_MAGISK_INSTALL_WEBUI="Magisk Detected, Install KSU WebUI for Magisk"
    
fi

############################################
#   Сбор информации об устройстве и       #
#   проверка версии Android (7+)           #
############################################

# Получаем данные через системные свойства
device_manufacturer=$(getprop ro.product.manufacturer 2>/dev/null)
device_model=$(getprop ro.product.model 2>/dev/null)
android_version=$(getprop ro.build.version.release 2>/dev/null)
system_build=$(getprop ro.build.display.id 2>/dev/null)
cpu_abi=$(getprop ro.product.cpu.abi 2>/dev/null)
device_board=$(getprop ro.product.board 2>/dev/null)
device_device=$(getprop ro.product.device 2>/dev/null)

# Выводим собранную информацию с небольшой задержкой
ui_print "$MSG_HEADER"
ui_print "$MSG_DEVICE_INFO"
sleep 0.7
[ -n "$device_manufacturer" ] && { ui_print "$MSG_MANUFACTURER: $device_manufacturer"; sleep 0.5; }
[ -n "$device_model" ] && { ui_print "$MSG_MODEL: $device_model"; sleep 0.5; }
[ -n "$android_version" ] && { ui_print "$MSG_ANDROID_VERSION $android_version"; sleep 0.5; }
[ -n "$system_build" ] && { ui_print "$MSG_SYSTEM_BUILD $system_build"; sleep 0.5; }
[ -n "$cpu_abi" ] && { ui_print "$MSG_CPU_ABI $cpu_abi"; sleep 0.5; }
[ -n "$device_board" ] && { ui_print "$MSG_DEVICE_BOARD $device_board"; sleep 0.5; }
[ -n "$device_device" ] && { ui_print "$MSG_DEVICE_DEVICE $device_device"; sleep 0.5; }
ui_print "$MSG_HEADER"
sleep 1

# Проверка минимальной версии Android (только если удалось определить версию)
if [ -n "$android_version" ]; then
    android_major=$(echo "$android_version" | cut -d. -f1)
    if [ "$android_major" -lt 7 ]; then
        ui_print "$MSG_ERROR_ANDROID $android_version"
        abort "$MSG_EXIT_ANDROID_INSTALL"
    else
        ui_print "$MSG_ANDROID_VERSION_CHESK $android_version $MSG_INSTALL_YES"
    fi
else
    ui_print "$MSG_ANDROID_ERROR_INFO"
fi

############################################
#   Проверка, является ли устройство Android TV   #
############################################

# Получаем характеристику устройства
android_characteristics=$(getprop ro.build.characteristics 2>/dev/null)

# Если в характеристиках присутствует "tv", считаем, что устройство — Android TV
if echo "$android_characteristics" | grep -iq "tv"; then
    ui_print "$MSG_CHECK_ANDROID_TV_YES"
    ui_print "$MSG_WARNING_INSTALL_ANDROID_TV"
    ui_print "$MSG_TV_WARNING"
    ui_print "$MSG_WARNING_IF"
    sleep 15
else
    ui_print "$MSG_CHECK_ANDROID_TV_NO"
    ui_print "$MSG_INSTALL_CONTINUED"
    sleep 3
fi
############################################

# Flashable integrity checkup
ui_print "- Извлечение verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
[ ! -f "$TMPDIR/verify.sh" ] && abort_corrupted
. "$TMPDIR/verify.sh"  # source заменён на POSIX-совместимый вызов

# Переменные
ZAPRET_BIN="$MODPATH/libs/android-aarch64/nfqws"  # Путь к бинарному файлу nfqws
REPO_OWNER="bol-van"
REPO_NAME="zapret"
BASE_URL="https://github.com/$REPO_OWNER/$REPO_NAME/releases/download"
SCRIPT_DIR="$MODPATH"
BIN_DIR="$MODPATH/libs/"  # Папка для бинарных файлов
DOWNLOAD_FOLDER="$SCRIPT_DIR/downloads"
EXTRACT_FOLDER="$DOWNLOAD_FOLDER/zapret"
CURRENT_VERSION=""
URL_SOURCE="https://p.thenewone.lol/domains-export.txt"  # URL для загрузки
BLACKLIST_FILENAME="russia-blacklist.txt"
DOWNLOAD_PATH="$MODPATH/downloads"
TARGET_SYSTEM_PATH="$MODPATH/working_folder/bin"
TEMP_FILE="/data/local/tmp/domains_temp_file.tmp"
WWW_PATH="$MODPATH/www"
CHECKONEINSTALL="/data/adb/modules/ZDT-D/working_folder"
SERVICESES="/data/adb/service.d/delete-dpi-tunnel.sh"

[ -d "$CHECKONEINSTALL" ] && {
    echo "# $MSG_STOP_SERVICE 🫠"
    zapret stop > /dev/null 2>&1 &
    sleep 3
}

##########################
# Функция: Проверка текущей версии
##########################
check_current_version() {
    if [ -f "$ZAPRET_BIN" ]; then
        CURRENT_VERSION=$("$ZAPRET_BIN" --version 2>/dev/null | grep -oE 'v[0-9]+\.[0-9]+')
        echo "$MSG_VERSION_ZAPRET $CURRENT_VERSION"
    else
        echo "$MSG_CHECK_ZAPRET_ERROR"
        CURRENT_VERSION=""
    fi
}

##########################
# Функция: Получение последней версии
##########################
get_latest_version() {
    echo "$MSG_CHECK_UPDATE_ZAPRET"
    if command -v curl >/dev/null 2>&1; then
        LATEST_VERSION=$(curl -s "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest" | awk -F'"' '/"tag_name":/ {print $4}')
    elif command -v wget >/dev/null 2>&1; then
        LATEST_VERSION=$(wget -qO- "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest" | awk -F'"' '/"tag_name":/ {print $4}')
    else
        echo "$MSG_CHECK_ZAPRET_UPDATE"
        LATEST_VERSION=""
        return 0  # Продолжаем работу
    fi

    if [ -z "$LATEST_VERSION" ]; then
        echo "$MSG_ERROR_CHECK_UPDATE_ZAPRET"
        # Если обновление не удалось, оставляем текущую версию
        LATEST_VERSION="$CURRENT_VERSION"
        return 0
    fi

    echo "$MSG_LAST_ZAPRET $LATEST_VERSION"
}

##########################
# Функция: Проверка необходимости обновления
##########################
check_for_update() {
    if [ "$CURRENT_VERSION" = "$LATEST_VERSION" ]; then
        echo "$MSG_INSTALLED_ZAPRET ($CURRENT_VERSION). $MSG_NO_UPDATE_REQUIRED."
    else
        echo "$MSG_NEW_VERSION_AVAILABLE $LATEST_VERSION"
    fi
}

##########################
# Функция: Скачивание и распаковка архива обновления
##########################
download_and_extract() {
    ZIP_FILE="$DOWNLOAD_FOLDER/zapret-$LATEST_VERSION.zip"
    DOWNLOAD_URL="$BASE_URL/$LATEST_VERSION/zapret-$LATEST_VERSION.zip"

    echo "$MSG_DOWNLOAD_VERSION $LATEST_VERSION с $DOWNLOAD_URL..."
    mkdir -p "$DOWNLOAD_FOLDER"
    
    if command -v curl >/dev/null 2>&1; then
        curl -s -L -o "$ZIP_FILE" "$DOWNLOAD_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$ZIP_FILE" "$DOWNLOAD_URL"
    else
        echo "$MSG_ERROR_DAWNLOAD_VERSION"
        return 0
    fi

    if [ -f "$ZIP_FILE" ] && [ -s "$ZIP_FILE" ]; then
        echo "$MSG_SUCCESSFULLY_DOWNLOAD $ZIP_FILE"
        echo "$MSG_UNZIP_ARHIVE"
        mkdir -p "$EXTRACT_FOLDER"
        unzip -o "$ZIP_FILE" -d "$EXTRACT_FOLDER"
        if [ $? -eq 0 ]; then
            echo "$MSG_SUCCESSFULLY_UNZIP $EXTRACT_FOLDER"
        else
            echo "$MSG_ERROR_UNZIP_ARHIVE"
            return 0
        fi
    else
        echo "$MSG_ERROR_DOWNLOAD_ARHIVE"
        return 0
    fi
}

##########################
# Функция: Копирование бинарных файлов
##########################
copy_binaries() {
    BINARIES_FOLDER="$EXTRACT_FOLDER/zapret-$LATEST_VERSION/binaries"
    if [ -d "$BINARIES_FOLDER" ]; then
        echo "$MSG_COPY_FILES_ZAPRET $BINARIES_FOLDER $MSG_COPY_FILES_ZAPRET_TO $BIN_DIR..."
        mkdir -p "$BIN_DIR"
        cp -r "$BINARIES_FOLDER/"* "$BIN_DIR"
        echo "$MSG_SUCCESSFULLY_COPY_FILES $BIN_DIR"
    else
        echo "$MSG_BINARIES_NO_CHECK $BINARIES_FOLDER. $MSG_SKIP_COPY_BINARIES"
    fi
}

##########################
# Функция: Удаление всех папок, кроме тех, что начинаются на android-
##########################
clean_non_android_architectures() {
    echo "$MSG_DELETE_EXCEPT_ANDROID"
    for item in "$BIN_DIR"/*; do
        if [ -d "$item" ]; then
            folder_name=$(basename "$item")
            case "$folder_name" in
                android-*)
                    echo "$MSG_SAVE $item"
                    ;;
                *)
                    echo "$MSG_DELETE $item"
                    rm -rf "$item"
                    ;;
            esac
        fi
    done
    echo "$MSG_CLEANING"
}

##########################
# Извлечение файлов модуля (используется стандартная функция extract от Magisk)
##########################
ui_print "$MSG_EXTRACTION_FILES_MODULES"
extract "$ZIPFILE" 'module.prop' "$MODPATH"
extract "$ZIPFILE" 'service.sh' "$MODPATH"
extract "$ZIPFILE" 'binary/dnscrypt-proxy-arm' "$MODPATH"
extract "$ZIPFILE" 'binary/dnscrypt-proxy-arm64' "$MODPATH"
extract "$ZIPFILE" 'binary/dnscrypt-proxy-i386' "$MODPATH"
extract "$ZIPFILE" 'binary/dnscrypt-proxy-x86_64' "$MODPATH"
extract "$ZIPFILE" 'www/index.php' "$MODPATH"
extract "$ZIPFILE" 'www/reboot.php' "$MODPATH"
extract "$ZIPFILE" 'icon/icon.png' "$MODPATH"
extract "$ZIPFILE" 'icon/icon1.png' "$MODPATH"
extract "$ZIPFILE" 'icon/icon2.png' "$MODPATH"
extract "$ZIPFILE" 'icon/icon3.png' "$MODPATH"
extract "$ZIPFILE" 'icon/icon4.png' "$MODPATH"
extract "$ZIPFILE" 'system/bin/script/ZDT-D.sh' "$MODPATH"
extract "$ZIPFILE" 'system/bin/ca.bundle' "$MODPATH"
extract "$ZIPFILE" 'system/bin/dpitunnel-cli' "$MODPATH"
extract "$ZIPFILE" 'system/bin/zapret' "$MODPATH"
extract "$ZIPFILE" 'post-fs-data.sh' "$MODPATH"
extract "$ZIPFILE" 'uninstall.sh' "$MODPATH"
extract "$ZIPFILE" 'webroot/module_icon.png' "$MODPATH"
extract "$ZIPFILE" 'files/php.ini' "$MODPATH"
extract "$ZIPFILE" 'files/bin/calendar.so' "$MODPATH"
extract "$ZIPFILE" 'files/bin/ctype.so' "$MODPATH"
extract "$ZIPFILE" 'files/bin/curl' "$MODPATH"
extract "$ZIPFILE" 'files/bin/fileinfo.so' "$MODPATH"
extract "$ZIPFILE" 'files/bin/gd.so' "$MODPATH"
extract "$ZIPFILE" 'files/bin/gettext.so' "$MODPATH"
extract "$ZIPFILE" 'files/bin/gmp.so' "$MODPATH"
extract "$ZIPFILE" 'files/bin/iconv.so' "$MODPATH"
extract "$ZIPFILE" 'files/bin/mbstring.so' "$MODPATH"
extract "$ZIPFILE" 'php/php32' "$MODPATH"
extract "$ZIPFILE" 'php/php64' "$MODPATH"
extract "$ZIPFILE" 'files/bin/rar.so' "$MODPATH"
extract "$ZIPFILE" 'files/bin/zip.so' "$MODPATH"
#extract "$ZIPFILE" 'system/bin/ZDT-D' "$MODPATH"
extract "$ZIPFILE" 'config/dnscrypt-proxy.toml' "$MODPATH"

# Проверяем наличие директории
if [ -d /data/data/com.termux/files/home/ ]; then
    extract "$ZIPFILE" 'ZDT-fix' /data/data/com.termux/files/home/
    chmod 755 /data/data/com.termux/files/home/ZDT-fix
fi

#extract "$ZIPFILE" 'delete-dpi-tunnel.sh' /data/adb/service.d/
#chmod 755 "$SERVICESES"

##########################
# Функция: Проверка конфликтующих модулей
##########################
check_modules() {
    zapret_path="/data/adb/modules/zapret"
    fork_path="/data/adb/modules/dpi_tunnel_cli"

    if [ -d "$zapret_path" ]; then
        sleep 30
        su -lp 2000 -c "cmd notification post -S messaging --conversation 'Chat' \
            --message 'ZDT-D: $MSG_CONFLICT_ZAPRET' \
            --message 'System: $MSG_CONFLICT_ZAPRET1' \
            --message 'ZDT-D: $MSG_CONFLICT_ZAPRET2' \
            -t 'Ошибка' 'Tag' ''" >/dev/null 2>&1
        exit 1
    fi

    if [ -d "$fork_path" ]; then
        sleep 30
        su -lp 2000 -c "cmd notification post -S messaging --conversation 'Chat' \
            --message 'ZDT-D: $MSG_CONFLICT_DPICLI' \
            --message 'System: $MSG_CONFLICT_DPICLI1' \
            --message 'ZDT-D: $MSG_CONFLICT_DPICLI2' \
            -t 'Ошибка' 'Tag' ''" >/dev/null 2>&1
        exit 1
    fi

    echo "$MSG_NO_CONFLICT"
}

check_modules

php_data="/data/adb/modules/ZDT-D/php7"

# Функция: Очистка php данных
rm_data() {
    rm -rf "$php_data"
}
rm_data

# Создание целевой папки для www
TARGET_DIR="/data/adb/modules/ZDT-D/php7/files/www"

if [ -z "$WWW_PATH" ]; then
    echo "$MSG_WWW_PATH_ERROR"
fi

mkdir -p "$TARGET_DIR" || {
    echo "$MSG_ERROR_CREATED_FOLDERS $TARGET_DIR."
}

if [ -d "$WWW_PATH" ] && [ "$(ls -A "$WWW_PATH" 2>/dev/null)" ]; then
    mv "$WWW_PATH"/* "$TARGET_DIR"/ || {
        echo "$MSG_ERROR_MOVING_FILES"
    }
    echo "$MSG_MOVING_FILES_UPDATE"
else
    echo "$MSG_ERROR_FOLDER $WWW_PATH $MSG_ERROR_FOLDER1"
fi

# Права доступа и создание директорий для php
system_gid="1000"
system_uid="1000"
php_data_dir="$MODPATH/php7"
php_bin_dir="${php_data_dir}/files/bin"

mkdir -p "$MODPATH/system/bin"
mkdir -p "$php_data_dir"

unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2

ui_print "[20] $MSG_COPY_FILES_PHP"
mv "$MODPATH/files" "$php_data_dir"

ui_print "[90] $MSG_SETTING_PERMITS"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$php_data_dir" 0 0 0755 0644
set_perm_recursive "$php_data_dir/scripts" 0 0 0755 0755
set_perm_recursive "$php_data_dir/files/config" 0 0 0755 0644
set_perm_recursive "$php_data_dir/files/www" "$system_uid" "$system_gid" 0755 0644
set_perm_recursive "$php_bin_dir" "$system_uid" "$system_gid" 0755 0755

set_perm "$php_data_dir/files/bin/php" 0 0 0755
set_perm "$php_data_dir/files/config/php.config" "$system_uid" "$system_gid" 0755
set_perm "$php_data_dir/files/config/php.ini" "$system_uid" "$system_gid" 0755

ui_print "[100] Done..."

##########################
# Выбор архитектурно-специфичного бинарного файла dnscrypt-proxy
##########################
if [ "$ARCH" = "arm" ]; then
  BINARY_PATH="$MODPATH/binary/dnscrypt-proxy-arm"
elif [ "$ARCH" = "arm64" ]; then
  BINARY_PATH="$MODPATH/binary/dnscrypt-proxy-arm64"
elif [ "$ARCH" = "x86" ]; then
  BINARY_PATH="$MODPATH/binary/dnscrypt-proxy-i386"
elif [ "$ARCH" = "x64" ]; then
  BINARY_PATH="$MODPATH/binary/dnscrypt-proxy-x86_64"
fi

CONFIG_PATH="$MODPATH/config"

ui_print "$MSG_CREATED_EXECUTABLE_DERICTORY"
mkdir -p "$MODPATH/system/bin"

ui_print "$MSG_CREATED_EXECUTABLE_DERICTORY1"
mkdir -p "$MODPATH/dnscrypt-proxy"

if [ -f "$BINARY_PATH" ]; then
    ui_print "$MSG_COPYEXECUTABLE_BINARE_FILE"
    cp -af "$BINARY_PATH" "$MODPATH/system/bin/dnscrypt-proxy"
else
    ui_print "$MSG_ERROR_ABSEND $ARCH $MSG_ERROR_ABSEND1"
fi

if [ -d "$CONFIG_PATH" ]; then
    ui_print "$MSG_COPY_DNS_SCRIPT"
    cp -af "$CONFIG_PATH/"* "$MODPATH/dnscrypt-proxy"
else
    ui_print "$MSG_COPY_DNS_SCRIPT_ERROR"
fi

ui_print "$MSG_SETTING_rights_DNS_SCRIPT"
set_perm_recursive "$MODPATH" 0 0 0755 0755
set_perm "$MODPATH/system/bin/dnscrypt-proxy" 0 0 0755

ui_print "$MSG_OFF_SYSTRM_DNS"
settings put global private_dns_mode off

ui_print "$MSG_DELETE_NOT_USED_FILES"
rm -rf "$MODPATH/binary"
rm -rf "$MODPATH/config"

log() {
    echo "- $1"
}

# Проверка состояния Wi-Fi
wifi_status=$(settings get global wifi_on)
if [ "$wifi_status" = "0" ]; then
    log "$MSG_WIFI_OFF_ON"
    svc wifi enable
    sleep 3
    
    # Проверка состояния Wi-Fi после попытки включения
    wifi_status=$(settings get global wifi_on)
    if [ "$wifi_status" = "0" ]; then
        log "$MSG_ERROR_ON_WIFI"
        log "$MSG_PLEASE_ON_WIFI"
        sleep 15
    else
        log "$MSG_WIFI_SUCCESSFULLY_ON"
    fi
else
    log "$MSG_WIFI_ON."
fi

sleep 2

# Ожидание подключения к Wi-Fi (до 30 секунд)
wifi_connected=0
for i in $(seq 1 5); do
    ssid=$(dumpsys wifi | grep "SSID" | awk -F'"' '{print $2}' | head -n 1)
    if [ -n "$ssid" ]; then
        log "$MSG_CONNECTED_WIFI $ssid"
        wifi_connected=1
        break
    fi
    log "$MSG_EXPECTATION_CONNECTED_WIFI ($i/5)"
    sleep 5
done

if [ "$wifi_connected" -eq 0 ]; then
    log "$MSG_WIFI_ON_NO_CONNECTED_WIFI"
    INTERNET_CHESK_CONNEKT=0
fi

sleep 2

# Ожидание появления интернета (до 30 секунд)
for i in $(seq 1 5); do
    if ping -c 1 -W 2 8.8.8.8 > /dev/null 2>&1; then
        log "$MSG_INTERNET_AVAILABLE"
        INTERNET_CHESK_CONNEKT=1
        break
    fi
    log "$MSG_EXPECTATION_APPEARANCE_INRERNET ($i/5)"
    sleep 5
done

sleep 1

createg_file_blosk() {
    ui_print "$MSG_DOWNLOAD_BLOSK_RUSSIA"
    
    # Проверка и создание папки DOWNLOAD_PATH
    if [ ! -d "$DOWNLOAD_PATH" ]; then
        mkdir -p "$DOWNLOAD_PATH" || {
            ui_print "$MSG_ERROR_CREATED_DERRIKTORY $DOWNLOAD_PATH"
        }
        ui_print "$MSG_DER $DOWNLOAD_PATH $MSG_CREAT"
    fi
    
    # Проверка и создание папки TARGET_SYSTEM_PATH
    if [ ! -d "$TARGET_SYSTEM_PATH" ]; then
        mkdir -p "$TARGET_SYSTEM_PATH" || {
            ui_print "$MSG_ERROR_CREATED_DERRIKTORY $TARGET_SYSTEM_PATH"
        }
        ui_print "$MSG_DER $TARGET_SYSTEM_PATH $MSG_CREAT."
    fi
}

##########################
# Функция: Загрузка файла с чёрным списком (blacklist)
##########################
download_to_temp_file() {
    echo "$MSG_DOWN_FILE $URL_SOURCE $MSG_TO_TIME_FILE $TEMP_FILE..."
    if command -v curl >/dev/null 2>&1; then
        curl -s -o "$TEMP_FILE" "$URL_SOURCE" 2>/data/local/tmp/curl_error.log || {
            ui_print "$MSG_ERROR_DOWN_CURL"
            return 0
        }
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$TEMP_FILE" "$URL_SOURCE" 2>/data/local/tmp/wget_error.log || {
            ui_print "$MSG_ERROR_DOWN_WGET"
            return 0
        }
    else
        ui_print "$MSGMSG_ERROR_DOWN_CURL_WGET"
        return 0
    fi

    if [ ! -s "$TEMP_FILE" ]; then
        ui_print "$MSG_DOWN_FILE_EMPTY"
        rm -f "$TEMP_FILE"
        return 0
    fi

    echo "$MSG_DIWNL_FILE $TEMP_FILE"
    return 0
}

moving_file_blosk() {
    
    # Если файл был успешно скачан, перемещаем его в нужные папки
    if [ -f "$TEMP_FILE" ]; then
        mv "$TEMP_FILE" "$DOWNLOAD_PATH/$BLACKLIST_FILENAME" || {
            ui_print "$MSG_ERROR_MOVIN_FILE $DOWNLOAD_PATH"
            rm -f "$TEMP_FILE"
        }
        ui_print "$MSG_DOWNLOAD_TO $DOWNLOAD_PATH/$BLACKLIST_FILENAME"
    
        if [ -f "$DOWNLOAD_PATH/$BLACKLIST_FILENAME" ]; then
            mv "$DOWNLOAD_PATH/$BLACKLIST_FILENAME" "$TARGET_SYSTEM_PATH/$BLACKLIST_FILENAME" || {
                ui_print "$MSG_ERROR_MOVIN_FILE $TARGET_SYSTEM_PATH"
            }
            ui_print "$MSG_MOVING_FILE_TO $TARGET_SYSTEM_PATH/$BLACKLIST_FILENAME"
        else
            ui_print "$MSG_FILE $DOWNLOAD_PATH/$BLACKLIST_FILENAME $MSG_NOT_FOUND"
        fi
    else
        ui_print "$MSG_SKIP_DOWNLOAD_FILE"
    fi
}

##########################
# Обновление и копирование файлов обновления модуля
##########################
# Выводим сообщение о наличии интернета
if [ "$INTERNET_CHESK_CONNEKT" -eq 1 ]; then
    check_current_version
    get_latest_version
    check_for_update
    download_and_extract
    copy_binaries
    clean_non_android_architectures
    createg_file_blosk
    download_to_temp_file

else
    log "$MSG_INTERNET_NOT"
fi

##########################
# Получение архитектурно-специфичной папки бинарных файлов и их копирование
##########################
if [ "$ARCH" = "arm" ]; then
  BINARY_PATH="$MODPATH/libs/android-arm"
elif [ "$ARCH" = "arm64" ]; then
  BINARY_PATH="$MODPATH/libs/android-aarch64"
elif [ "$ARCH" = "x86" ]; then
  BINARY_PATH="$MODPATH/libs/android-x86"
elif [ "$ARCH" = "x64" ]; then
  BINARY_PATH="$MODPATH/libs/android-x86_64"
else
  ui_print "$MSG_UNSUPPORTED_ARCHITECTURE $ARCH. $MSG_SKIP_COPY_BINARIES"
fi

ui_print "$MSG_CREATED_DIR_BIN_FILES"
mkdir -p "$MODPATH/system/bin"

ui_print "$MSG_COPY_BIN_FILES $ARCH."
cp -r "$BINARY_PATH/"* "$MODPATH/system/bin/" || {
  ui_print "MSG_ERROR_COPY_BIN_FILES"
}

ui_print "* Установка исполняемых прав для бинарных файлов."
chmod -R 0755 "$MODPATH/system/bin" || {
  ui_print "$MSG_ERROR_RIGHT"
}

ui_print "$MSG_DELETE_FOLDER_LIBS"
rm -rf "$MODPATH/libs" || {
  ui_print "MSG_ERROR_DELETE_FOLDER_LIBS"
}

ui_print "$MSG_UNZIP_DONE"

rm -rf "$MODPATH/icon/icon.png.sha256"
rm -rf "$MODPATH/icon/icon1.png.sha256"
rm -rf "$MODPATH/icon/icon2.png.sha256"
rm -rf "$MODPATH/icon/icon3.png.sha256"
rm -rf "$MODPATH/icon/icon4.png.sha256"

# Перемещение и очистка иконок
ICON_PATH="$MODPATH/icon"
DEST_PATH="/data/local/tmp"

ui_print "$MSG_CHECK_FILDER_ICON"
mkdir -p "$DEST_PATH" || {
  ui_print "$MSG_ERROR_CREATED_DERRIKTORY $DEST_PATH."
}

ui_print "$MSG_COPY_ICON $DEST_PATH."
cp -r "$ICON_PATH/"* "$DEST_PATH/" || {
  ui_print "Ошибка: не удалось скопировать файлы из $ICON_PATH в $DEST_PATH."
}

ui_print "$MSG_DELETE_FOLDER_ICON"
rm -rf "$ICON_PATH" || {
  ui_print "$MSG_ERROR_DELETE_FOLDER_ICON $ICON_PATH."
}

ui_print "$MSG_COPY_CLEAR_DONE"

APK_PATH="$MODPATH"

##########################
# Определяем, Android TV ли это
##########################
# Если в характеристиках есть "tv" (регистронезависимо), то это Android TV
if echo "$android_characteristics" | grep -iq "tv"; then
    ui_print "- $MSG_WEB_UI_TV"
else
    ##########################
    # Установка KSU WebUI для пользователей Magisk
    ##########################
    # Проверяем, что Magisk установлен в системе
    if [ "$(which magisk)" ]; then
        # Если пакета ещё нет — устанавливаем
        if ! pm list packages | grep -q io.github.a13e300.ksuwebui; then
            ui_print "- $MSG_MAGISK_INSTALL_WEBUI"
            extract "$ZIPFILE" 'webui.apk' "$APK_PATH"
            pm install "$APK_PATH/webui.apk" >&2
            rm -f "$APK_PATH/webui.apk"
        fi

        # Проверяем, успешно ли установился
        if ! pm list packages | grep -q io.github.a13e300.ksuwebui; then
            ui_print "$MSG_ERROR_KSU_SELINUX"
            ui_print "$MSG_INSTALL_MANUALY"
        else
            ui_print "$MSG_ROOT_KSU"
            sleep 2
            # Запускаем интерфейс WebUI
            am start -n io.github.a13e300.ksuwebui/.MainActivity >/dev/null 2>&1

            # Временно переводим SELinux в permissive, показываем тост
            setenforce 0
            /system/bin/am start \
                -a android.intent.action.MAIN \
                -e toasttext "$MSG_ROOT_DOSTUPE_KSU" \
                -n bellavita.toast/.MainActivity \
                >/dev/null 2>&1
            sleep 5
            /system/bin/am start \
                -a android.intent.action.MAIN \
                -e toasttext "$MSG_ROOT_DOSTUPE_KSU" \
                -n bellavita.toast/.MainActivity \
                >/dev/null 2>&1
            setenforce 1
        fi
    fi
fi


mkdir -p "$MODPATH/log"


##########################
# Блок кода выбора php
# В зависимости от поддержки архитектур выбирается бинарник php
##########################
ARCHPHP=$(su -c "getprop ro.product.cpu.abilist")

# Проверяем наличие "armeabi-v7a" в выводе
if echo "$ARCHPHP" | grep -q "armeabi-v7a"; then
    cp $MODPATH/php/php32 $MODPATH/php7/files/bin/php
else
    cp $MODPATH/php/php64 $MODPATH/php7/files/bin/php
fi

ui_print "$MSG_CLEAR_RESIDUAL_FILES"
rm -rf "$MODPATH/ZDT-fix"
rm -rf "$MODPATH/ZDT-fix.sha256"
rm -rf "$APK_PATH/toast.apk"
rm -rf "$APK_PATH/webui.apk"
rm -rf "$APK_PATH/verify.sh"
rm -rf "$MODPATH/downloads"
rm -rf "$MODPATH/www"
rm -rf "$MODPATH/module.prop.sha256"
rm -rf "$MODPATH/service.sh.sha256"
rm -rf "$MODPATH/toast.apk.sha256"
rm -rf "$MODPATH/uninstall.sh.sha256"
rm -rf "$MODPATH/webui.apk.sha256"
rm -rf "$MODPATH/post-fs-data.sh.sha256"
rm -rf "$MODPATH/system/bin/zapret.sha256"
rm -rf "$MODPATH/system/bin/dpitunnel-cli.sha256"
rm -rf "$MODPATH/system/bin/ca.bundle.sha256"
rm -rf "$MODPATH/system/bin/script/ZDT-D.sh.sha256"
rm -rf "$MODPATH/webroot/index.html.sha256"
rm -rf "$MODPATH/webroot/module_icon.png.sha256"
#rm -rf "$MODPATH/delete-dpi-tunnel.sh.sha256"
#rm -rf "$MODPATH/delete-dpi-tunnel.sh"
rm -rf "$MODPATH/php7/files/php.ini.sha256"
rm -rf "$MODPATH/php7/files/bin/calendar.so.sha256"
rm -rf "$MODPATH/php7/files/bin/ctype.so.sha256"
rm -rf "$MODPATH/php7/files/bin/curl.sha256"
rm -rf "$MODPATH/php7/files/bin/fileinfo.so.sha256"
rm -rf "$MODPATH/php7/files/bin/gd.so.sha256"
rm -rf "$MODPATH/php7/files/bin/gettext.so.sha256"
rm -rf "$MODPATH/php7/files/bin/gmp.so.sha256"
rm -rf "$MODPATH/php7/files/bin/iconv.so.sha256"
rm -rf "$MODPATH/php7/files/bin/mbstring.so.sha256"
rm -rf "$MODPATH/php"
rm -rf "$MODPATH/php7/files/bin/rar.so.sha256"
rm -rf "$MODPATH/php7/files/bin/zip.so.sha256"
rm -rf "$MODPATH/system/bin/ZDT-D.sha256"
rm -rf "$MODPATH/dnscrypt-proxy/dnscrypt-proxy.toml.sha256"

ui_print " "
ui_print "$MSG_SETTING_WEB 🔧"
ui_print "$MSG_ADDRES_WEB 🌐"
ui_print "$MSG_WELCOME 😈"

##########################
# Проверка первой установки
##########################
if [ -d "$CHECKONEINSTALL" ]; then
    sleep 8
    echo "$MSG_UPDATE_MODULE ♻️"
    am start -n io.github.a13e300.ksuwebui/.WebUIActivity --es id "ZDT-D" >/dev/null 2>&1
else
    echo "$MSG_INSTALL_MODULE 📥"
    am start -a android.intent.action.VIEW -d "https://t.me/module_ggover" >/dev/null 2>&1
fi
