# shellcheck disable=SC2034
SKIPUNZIP=1

# Flashable integrity checkup
ui_print "- Extracting verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
[ ! -f "$TMPDIR/verify.sh" ] && abort_corrupted
source "$TMPDIR/verify.sh"

# Переменные
ZAPRET_BIN="$MODPATH/libs/android-aarch64/nfqws" # Путь к бинарному файлу nfqws
REPO_OWNER="bol-van"
REPO_NAME="zapret"
BASE_URL="https://github.com/$REPO_OWNER/$REPO_NAME/releases/download"
SCRIPT_DIR="$MODPATH" # Путь к директории, где находится скрипт
BIN_DIR="$MODPATH/libs/" # Папка для бинарных файлов
DOWNLOAD_FOLDER="$SCRIPT_DIR/downloads" # Папка для загрузки
EXTRACT_FOLDER="$DOWNLOAD_FOLDER/zapret"
CURRENT_VERSION=""
URL_SOURCE="https://p.thenewone.lol/domains-export.txt" # URL для загрузки
BLACKLIST_FILENAME="russia-blacklist.txt" # Имя файла для сохранения
DOWNLOAD_PATH="$MODPATH/downloads" # Папка для загрузки
TARGET_SYSTEM_PATH="$MODPATH/working_folder/bin" # Целевая папка для перемещения
TEMP_FILE="/data/local/tmp/domains_temp_file.tmp" # Временный файл для загрузки
WWW_PATH="$MODPATH/www"
# Путь к файлу первой установки 
CHECKONEINSTALL="/data/adb/modules/ZDT-D/working_folder"
SERVICESES="/data/adb/service.d/delete-dpi-tunnel.sh"

# Проверка текущей версии
check_current_version() {
    if [[ -f $ZAPRET_BIN ]]; then
        CURRENT_VERSION=$($ZAPRET_BIN --version 2>/dev/null | grep -oE 'v[0-9]+\.[0-9]+')
        echo "Текущая версия zapret: $CURRENT_VERSION"
    else
        echo "nfqws не найден. Возможно, zapret еще не установлен."
        CURRENT_VERSION=""
    fi
}

# Получение последней версии
get_latest_version() {
    echo "Получаем информацию о последней версии..."

    # Проверяем доступность curl
    if command -v curl >/dev/null 2>&1; then
        # Используем curl для загрузки JSON и извлечения значения "tag_name"
        LATEST_VERSION=$(curl -s "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest" | awk -F'"' '/"tag_name":/ {print $4}')
    elif command -v wget >/dev/null 2>&1; then
        # Если curl недоступен, используем wget
        LATEST_VERSION=$(wget -qO- "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest" | awk -F'"' '/"tag_name":/ {print $4}')
    else
        echo "Ошибка: ни curl, ни wget не найдены в системе."
        return 1
    fi

    if [[ -z $LATEST_VERSION ]]; then
        echo "Не удалось получить последнюю версию. Проверьте подключение к интернету или правильность URL."
        return 1
    fi

    echo "Последняя версия: $LATEST_VERSION"
}

# Проверка необходимости обновления
check_for_update() {
    if [[ $CURRENT_VERSION == "$LATEST_VERSION" ]]; then
        echo "Установлена последняя версия ($CURRENT_VERSION). Обновление не требуется."
        
    else
        echo "Доступна новая версия: $LATEST_VERSION"
    fi
}

# Скачивание и распаковка
download_and_extract() {
    ZIP_FILE="$DOWNLOAD_FOLDER/zapret-$LATEST_VERSION.zip"
    DOWNLOAD_URL="$BASE_URL/$LATEST_VERSION/zapret-$LATEST_VERSION.zip"

    echo "Скачиваем версию $LATEST_VERSION с $DOWNLOAD_URL..."
    mkdir -p "$DOWNLOAD_FOLDER"
    
    # Скачиваем файл, проверяем доступность curl или wget
    if command -v curl >/dev/null 2>&1; then
        curl -s -L -o "$ZIP_FILE" "$DOWNLOAD_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$ZIP_FILE" "$DOWNLOAD_URL"
    else
        echo "Ошибка: ни curl, ни wget не найдены в системе."
        return 1
    fi

    # Проверяем, успешно ли загружен файл
    if [[ -f $ZIP_FILE && -s $ZIP_FILE ]]; then
        echo "Архив успешно загружен: $ZIP_FILE"
        echo "Распаковываем архив..."
        mkdir -p "$EXTRACT_FOLDER"
        unzip -o "$ZIP_FILE" -d "$EXTRACT_FOLDER"
        if [[ $? -eq 0 ]]; then
            echo "Архив распакован в папку: $EXTRACT_FOLDER"
        else
            echo "Ошибка при распаковке архива."
            return 1
        fi
    else
        echo "Не удалось загрузить архив. Проверьте URL: $DOWNLOAD_URL"
        return 1
    fi
}

# Копирование бинарных файлов
copy_binaries() {
    BINARIES_FOLDER="$EXTRACT_FOLDER/zapret-$LATEST_VERSION/binaries"
    if [[ -d $BINARIES_FOLDER ]]; then
        echo "Копируем файлы из $BINARIES_FOLDER в $BIN_DIR..."
        mkdir -p "$BIN_DIR"
        cp -r "$BINARIES_FOLDER/"* "$BIN_DIR"
        echo "Файлы успешно скопированы в $BIN_DIR"
    else
        echo "Папка binaries не найдена: $BINARIES_FOLDER"
    fi
}

# Удаление всех папок, кроме Android-архитектур
clean_non_android_architectures() {
    echo "Удаляем все папки, кроме android-*..."
    for item in "$BIN_DIR"/*; do
        if [[ -d $item ]]; then
            folder_name=$(basename "$item")
            if [[ $folder_name != android-* ]]; then
                echo "Удаляем: $item"
                rm -rf "$item"
            else
                echo "Сохраняем: $item"
            fi
        fi
    done
    echo "Очистка завершена."
}

# Извлечение файлов модуля
ui_print "- Извлечение файлов модуля"
extract "$ZIPFILE" 'module.prop' $MODPATH
extract "$ZIPFILE" 'service.sh' $MODPATH
extract "$ZIPFILE" 'binary/dnscrypt-proxy-arm' $MODPATH
extract "$ZIPFILE" 'binary/dnscrypt-proxy-arm64' $MODPATH
extract "$ZIPFILE" 'binary/dnscrypt-proxy-i386' $MODPATH
extract "$ZIPFILE" 'binary/dnscrypt-proxy-x86_64' $MODPATH
extract "$ZIPFILE" 'www/index.php' $MODPATH
extract "$ZIPFILE" 'www/reboot.php' $MODPATH
extract "$ZIPFILE" 'icon/icon.png' $MODPATH
extract "$ZIPFILE" 'icon/icon1.png' $MODPATH
extract "$ZIPFILE" 'icon/icon2.png' $MODPATH
extract "$ZIPFILE" 'icon/icon3.png' $MODPATH
extract "$ZIPFILE" 'system/bin/script/ZDT-D.sh' $MODPATH
extract "$ZIPFILE" 'system/bin/ca.bundle' $MODPATH
extract "$ZIPFILE" 'system/bin/dpitunnel-cli' $MODPATH
extract "$ZIPFILE" 'system/bin/zapret' $MODPATH
extract "$ZIPFILE" 'post-fs-data.sh' $MODPATH
extract "$ZIPFILE" 'uninstall.sh' $MODPATH
extract "$ZIPFILE" 'webroot/module_icon.png' $MODPATH

extract "$ZIPFILE" 'files/php.ini' $MODPATH
extract "$ZIPFILE" 'files/bin/calendar.so' $MODPATH
extract "$ZIPFILE" 'files/bin/ctype.so' $MODPATH
extract "$ZIPFILE" 'files/bin/curl' $MODPATH
extract "$ZIPFILE" 'files/bin/fileinfo.so' $MODPATH
extract "$ZIPFILE" 'files/bin/gd.so' $MODPATH
extract "$ZIPFILE" 'files/bin/gettext.so' $MODPATH
extract "$ZIPFILE" 'files/bin/gmp.so' $MODPATH
extract "$ZIPFILE" 'files/bin/iconv.so' $MODPATH
extract "$ZIPFILE" 'files/bin/mbstring.so' $MODPATH
extract "$ZIPFILE" 'files/bin/php' $MODPATH
extract "$ZIPFILE" 'files/bin/rar.so' $MODPATH
extract "$ZIPFILE" 'files/bin/zip.so' $MODPATH
extract "$ZIPFILE" 'system/bin/ZDT-D' $MODPATH
extract "$ZIPFILE" 'config/dnscrypt-proxy.toml' $MODPATH


extract "$ZIPFILE" 'delete-dpi-tunnel.sh' /data/adb/service.d/
chmod 755 "$SERVICESES"

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

check_modules

php_data="/data/adb/modules/ZDT-D/php7"

rm_data() {
    rm -rf ${php_data}
}
# Вызов функции очистки 
rm_data

# Путь к целевой папке
TARGET_DIR="/data/adb/modules/ZDT-D/php7/files/www"

# Убедимся, что переменная WWW_PATH задана
if [ -z "$WWW_PATH" ]; then
    echo "Ошибка: переменная WWW_PATH не задана."
   
fi

# Создание целевых папок
mkdir -p "$TARGET_DIR" || {
    echo "Ошибка: не удалось создать папки $TARGET_DIR."
    
}

# Проверка, существует ли папка WWW_PATH и содержит ли она файлы
if [ -d "$WWW_PATH" ] && [ "$(ls -A "$WWW_PATH" 2>/dev/null)" ]; then
    # Перемещение содержимого
    mv "$WWW_PATH"/* "$TARGET_DIR"/ || {
        echo "Ошибка: не удалось переместить файлы."
        
    }
    echo "- Перемещение файлов для обновления."
else
    echo "Ошибка: папка $WWW_PATH не существует или пуста."
    
fi

# Переменные 
system_gid="1000"
system_uid="1000"
php_data_dir="$MODPATH/php7"
php_bin_dir="${php_data_dir}/files/bin"

mkdir -p ${MODPATH}/system/bin
mkdir -p ${php_data_dir}


unzip -o "${ZIPFILE}" -x 'META-INF/*' -d $MODPATH >&2

ui_print "[20] Копирование файлов..."

mv ${MODPATH}/files ${php_data_dir}
#mv ${MODPATH}/scripts ${php_data_dir}

ui_print "[90] Настройка разрешений для файлов..."

set_perm_recursive ${MODPATH} 0 0 0755 0644
set_perm_recursive ${php_data_dir} 0 0 0755 0644
set_perm_recursive ${php_data_dir}/scripts 0 0 0755 0755
set_perm_recursive ${php_data_dir}/files/config 0 0 0755 0644
set_perm_recursive ${php_data_dir}/files/www ${system_uid} ${system_gid} 0755 0644
set_perm_recursive ${php_bin_dir} ${system_uid} ${system_gid} 0755 0755

#set_perm  ${php_data_dir}/scripts/php_run  0  0  0755
#set_perm  ${php_data_dir}/scripts/php_inotifyd  0  0  0755
set_perm  ${php_data_dir}/files/bin/php  0  0  0755
set_perm  ${php_data_dir}/files/config/php.config ${system_uid} ${system_gid} 0755
set_perm  ${php_data_dir}/files/config/php.ini ${system_uid} ${system_gid} 0755
# chmod +x ${php_data_dir}/files

ui_print "[100] Done..."

# Get architecture specific binary file
if [ "$ARCH" == "arm" ];then
  BINARY_PATH=$MODPATH/binary/dnscrypt-proxy-arm
elif [ "$ARCH" == "arm64" ];then
  BINARY_PATH=$MODPATH/binary/dnscrypt-proxy-arm64
elif [ "$ARCH" == "x86" ];then
  BINARY_PATH=$MODPATH/binary/dnscrypt-proxy-i386
elif [ "$ARCH" == "x64" ];then
  BINARY_PATH=$MODPATH/binary/dnscrypt-proxy-x86_64
fi

# Set destination paths
CONFIG_PATH=$MODPATH/config

# Create the path for the binary file
ui_print "* Создание исполняемой дериктории."
mkdir -p $MODPATH/system/bin

# Create the path for the configuration files
ui_print "* Создание конфига. path."
mkdir -p $MODPATH/dnscrypt-proxy

# Copy the binary files into the right folder
if [ -f "$BINARY_PATH" ]; then
ui_print "* Копирование исполныемых бинарных файлов."
 cp -af $BINARY_PATH $MODPATH/system/bin/dnscrypt-proxy
else
  abort "The binary file for your $ARCH device is missing!"
fi

# Copy the configuration files into the right folder
if [ -d "$CONFIG_PATH" ]; then
ui_print "* Copying the configuration files into the dnscrypt-proxy folder."
  cp -af $CONFIG_PATH/* $MODPATH/dnscrypt-proxy
else
  abort "Configuration file (.toml) is missing!"
fi

# Set the right permissions to the dnscrypt-proxy binary file
ui_print "* Setting up the right permissions to the dnscrypt-proxy binary file."
set_perm_recursive $MODPATH 0 0 0755 0755
set_perm $MODPATH/system/bin/dnscrypt-proxy 0 0 0755

# Set Private DNS mode off
ui_print "* Disabling Android 9+ Private DNS mode."
settings put global private_dns_mode off

# Cleanup unneeded binary files
ui_print "* Cleaning up the unnecessary files."
rm -r $MODPATH/binary
rm -r $MODPATH/config

# Основной процесс
check_current_version
get_latest_version
check_for_update
download_and_extract
copy_binaries
clean_non_android_architectures

ui_print "! - Начинаю загрузку списка blocked list Russia"

# Проверяем и создаем папку DOWNLOAD_PATH
if [ ! -d "$DOWNLOAD_PATH" ]; then
    mkdir -p "$DOWNLOAD_PATH" || {
        ui_print "Error: Unable to create directory $DOWNLOAD_PATH"
        
    }
    ui_print "Directory $DOWNLOAD_PATH created."
fi

# Проверяем и создаем папку TARGET_SYSTEM_PATH
if [ ! -d "$TARGET_SYSTEM_PATH" ]; then
    mkdir -p "$TARGET_SYSTEM_PATH" || {
        ui_print "Error: Unable to create directory $TARGET_SYSTEM_PATH"
        
    }
    ui_print "Directory $TARGET_SYSTEM_PATH created."
fi

# Загрузка файла
download_to_temp_file() {
    echo "Загружаем файл из $URL_SOURCE во временный файл $TEMP_FILE..."

    if command -v curl >/dev/null 2>&1; then
        curl -s -o "$TEMP_FILE" "$URL_SOURCE" 2>/data/local/tmp/curl_error.log || {
            ui_print "curl failed. Check /data/local/tmp/curl_error.log"
            return 1
        }
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$TEMP_FILE" "$URL_SOURCE" 2>/data/local/tmp/wget_error.log || {
            ui_print "wget failed. Check /data/local/tmp/wget_error.log"
            return 1
        }
    else
        ui_print "Ошибка: ни curl, ни wget не найдены в системе."
        return 1
    fi

    if [ ! -s "$TEMP_FILE" ]; then
        ui_print "Downloaded file is empty. Aborting."
        rm -f "$TEMP_FILE"
        return 1
    fi

    echo "Файл успешно загружен: $TEMP_FILE"
    return 0
}

# Выполняем загрузку
download_to_temp_file || {
    ui_print "Error: File download failed. Aborting."
}

# Перемещаем файл в папку DOWNLOAD_PATH с нужным именем
mv "$TEMP_FILE" "$DOWNLOAD_PATH/$BLACKLIST_FILENAME" || {
    ui_print "Error: Unable to move file to $DOWNLOAD_PATH"
    rm -f "$TEMP_FILE"
    
}
ui_print "File downloaded to: $DOWNLOAD_PATH/$BLACKLIST_FILENAME"

# Перемещаем файл в TARGET_SYSTEM_PATH
mv "$DOWNLOAD_PATH/$BLACKLIST_FILENAME" "$TARGET_SYSTEM_PATH/$BLACKLIST_FILENAME" || {
    ui_print "Error: Unable to move file to $TARGET_SYSTEM_PATH"
    
}
ui_print "File moved to: $TARGET_SYSTEM_PATH/$BLACKLIST_FILENAME"



ui_print "File downloaded to: $DOWNLOAD_PATH/$BLACKLIST_FILENAME"

# Перемещаем файл из DOWNLOAD_PATH в TARGET_SYSTEM_PATH
mv "$DOWNLOAD_PATH/$BLACKLIST_FILENAME" "$TARGET_SYSTEM_PATH/$BLACKLIST_FILENAME"
if [ $? -ne 0 ]; then
    ui_print "Error: Unable to move file to $TARGET_SYSTEM_PATH"
    
fi
ui_print "File moved to: $TARGET_SYSTEM_PATH/$BLACKLIST_FILENAME"

# Get architecture specific binary folder
if [ "$ARCH" == "arm" ]; then
  BINARY_PATH="$MODPATH/libs/android-arm"
elif [ "$ARCH" == "arm64" ]; then
  BINARY_PATH="$MODPATH/libs/android-aarch64"
elif [ "$ARCH" == "x86" ]; then
  BINARY_PATH="$MODPATH/libs/android-x86"
elif [ "$ARCH" == "x64" ]; then
  BINARY_PATH="$MODPATH/libs/android-x86_64"
else
  ui_print "Unsupported architecture: $ARCH"
  
fi

# Ensure the target directory exists
ui_print "* Creating the binary path."
mkdir -p "$MODPATH/system/bin"

# Copy all files from the architecture folder to the binary directory
ui_print "* Extracting binaries for $ARCH."
cp -r "$BINARY_PATH/"* "$MODPATH/system/bin/" || {
  ui_print "Failed to copy binaries. Check the source directory."
  
}

# Make all binaries executable
ui_print "* Setting executable permissions."
chmod -R 0755 "$MODPATH/system/bin" || {
  ui_print "Failed to set permissions."
  
}

# Remove the libs folder
ui_print "* Removing the libs folder."
rm -rf "$MODPATH/libs" || {
  ui_print "Failed to remove the libs folder."
  
}

ui_print "* Extraction complete and cleanup done"

rm -r $MODPATH/icon/icon.png.sha256
rm -r $MODPATH/icon/icon1.png.sha256
rm -r $MODPATH/icon/icon2.png.sha256
rm -r $MODPATH/icon/icon3.png.sha256

# Define the source and destination paths
ICON_PATH="$MODPATH/icon"
DEST_PATH="/data/local/tmp"

# Ensure the destination directory exists
ui_print "* Ensuring destination path exists."
mkdir -p "$DEST_PATH" || {
  ui_print "Failed to create destination path: $DEST_PATH"
  
}

# Copy all files from the icon folder to the destination
ui_print "* Copying files from icon to $DEST_PATH."
cp -r "$ICON_PATH/"* "$DEST_PATH/" || {
  ui_print "Failed to copy files from $ICON_PATH to $DEST_PATH."
  
}

# Remove the icon folder
ui_print "* Removing the icon folder."
rm -rf "$ICON_PATH" || {
  ui_print "Failed to remove the icon folder."
  
}

ui_print "* Copying and cleanup completed successfully."


APK_PATH="$MODPATH"

# Установка Bellavita Toast
if ! pm list packages | grep -q bellavita.toast; then
	ui_print "- Установка Bellavita Toast"
	extract "$ZIPFILE" 'toast.apk' $APK_PATH
	pm install $APK_PATH/toast.apk >&2
fi

if ! pm list packages | grep -q bellavita.toast; then
	ui_print "! Невозможно установить Bellavita Toast из-за ограничений SELinux"
	ui_print "! Пожалуйста, установите приложение вручную после установки."
fi

# Установка KSU WebUI для пользователей Magisk
if [ "$(which magisk)" ]; then

	if ! pm list packages | grep -q io.github.a13e300.ksuwebui; then
		ui_print "- Обнаружен Magisk, установка KSU WebUI для Magisk"
		extract "$ZIPFILE" 'webui.apk' $APK_PATH
		pm install $APK_PATH/webui.apk >&2
		rm -f $APK_PATH/webui.apk
	fi

	if ! pm list packages | grep -q io.github.a13e300.ksuwebui; then
		ui_print "! Невозможно установить KSU WebUI из-за ограничений SELinux"
		ui_print "! Пожалуйста, установите приложение вручную после установки."
	else
		ui_print "- Пожалуйста, предоставьте root-доступ для KSU WebUI"
		sleep 2
		am start -n io.github.a13e300.ksuwebui/.MainActivity >/dev/null 2>&1
		setenforce 0
        /system/bin/am start -a android.intent.action.MAIN -e toasttext "Пожалуйста предоставьте root доступ приложеню WebUI" -n bellavita.toast/.MainActivity > /dev/null 2>&1
        sleep 5
        /system/bin/am start -a android.intent.action.MAIN -e toasttext "Пожалуйста предоставьте root доступ приложеню WebUI" -n bellavita.toast/.MainActivity > /dev/null 2>&1
        setenforce 1
	fi
fi

mkdir -p $MODPATH/log

ui_print "! Удаление остаточных файлов apk и папок"
rm -r $APK_PATH/toast.apk
rm -r $APK_PATH/webui.apk
rm -r $APK_PATH/verify.sh
rm -r $MODPATH/downloads
rm -r $MODPATH/www
rm -r $MODPATH/module.prop.sha256
rm -r $MODPATH/service.sh.sha256
rm -r $MODPATH/toast.apk.sha256
rm -r $MODPATH/toast.apk.sha256
rm -r $MODPATH/uninstall.sh.sha256
rm -r $MODPATH/webui.apk.sha256
rm -r $MODPATH/post-fs-data.sh.sha256
rm -r $MODPATH/system/bin/zapret.sha256
rm -r $MODPATH/system/bin/dpitunnel-cli.sha256
rm -r $MODPATH/system/bin/ca.bundle.sha256
rm -r $MODPATH/system/bin/script/ZDT-D.sh.sha256
rm -r $MODPATH/webroot/index.html.sha256
rm -r $MODPATH/webroot/module_icon.png.sha256
rm -r $MODPATH/delete-dpi-tunnel.sh.sha256
rm -r $MODPATH/delete-dpi-tunnel.sh
rm -r $MODPATH/files/php.ini.sha256
rm -r $MODPATH/files/bin/calendar.so.sha256
rm -r $MODPATH/files/bin/ctype.so.sha256
rm -r $MODPATH/files/bin/curl.sha256
rm -r $MODPATH/files/bin/fileinfo.so.sha256
rm -r $MODPATH/files/bin/gd.so.sha256
rm -r $MODPATH/files/bin/gettext.so.sha256
rm -r $MODPATH/files/bin/gmp.so.sha256
rm -r $MODPATH/files/bin/iconv.so.sha256
rm -r $MODPATH/files/bin/mbstring.so.sha256
rm -r $MODPATH/files/bin/php.sha256
rm -r $MODPATH/files/bin/rar.so.sha256
rm -r $MODPATH/files/bin/zip.so.sha256
rm -r $MODPATH/system/bin/ZDT-D.sha256
rm -r $MODPATH/config/dnscrypt-proxy.toml.sha256

ui_print " "
ui_print "-     Веб доступ для настройки модуля"
ui_print "-     Имя: admin & Пароль: 12345"
ui_print "-     Адрес: http://127.0.0.1:1137"

# Проверка Первой установки 
if [ -d "$CHECKONEINSTALL" ]; then
    sleep 8
    echo "- Обновление модуля"
    am start -n io.github.a13e300.ksuwebui/.WebUIActivity --es id "ZDT-D" >/dev/null 2>&1;
else
    echo "- Первая установка."
    
    am start -a android.intent.action.VIEW -d "https://t.me/module_ggover" >/dev/null 2>&1;
fi
