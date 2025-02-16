(
#!/system/bin/sh

# Ожидание завершения загрузки устройства
boot_completed() {
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 1
    done
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
    sleep 3
done
}

# Функция проверки и удаления файла
delete_file_if_exists() {
    file="$1"
    if [ -f "$file" ]; then
        echo "Удаление файла: $file"
        rm -f "$file"
    else
        echo "Файл не найден: $file"
    fi
}

# Функция проверки и удаления директории
delete_dir_if_exists() {
    dir="$1"
    if [ -d "$dir" ]; then
        echo "Удаление директории: $dir"
        rm -rf "$dir"
    else
        echo "Директория не найдена: $dir"
    fi
}

# Удаление uninstall.sh и создание файла remove
handle_uninstall_file() {
    module_path="/data/adb/modules/dpi_tunnel_cli"
    uninstall_file="$module_path/uninstall.sh"
    remove_file="$module_path/remove"

    if [ -d "$module_path" ]; then
        echo "Проверка директории: $module_path"
        delete_file_if_exists "$uninstall_file"
        if [ ! -f "$uninstall_file" ]; then
            echo "Создание файла: $remove_file"
            touch "$remove_file"
        fi
    else
        echo "Директория модуля не найдена: $module_path"
    fi
}

# Удаление всех директорий dnscrypt-proxy
handle_dnscrypt_proxy_dirs() {
    paths="/data/media/0/dnscrypt-proxy
/mnt/runtime/default/emulated/0/dnscrypt-proxy
/mnt/runtime/full/emulated/0/dnscrypt-proxy
/mnt/runtime/read/emulated/0/dnscrypt-proxy
/mnt/runtime/write/emulated/0/dnscrypt-proxy
/sdcard/dnscrypt-proxy
/storage/emulated/0/dnscrypt-proxy
/storage/self/primary/dnscrypt-proxy"

    for path in $paths; do
        delete_dir_if_exists "$path"
    done
}

kill_service() {
  # Ищем процесс dpi-tunnel.sh и убиваем его
  PID=$(ps -ef | grep 'dpi-tunnel.sh' | awk '{print $2}')
  if [ -n "$PID" ]; then
    echo "Процесс dpi-tunnel.sh найден, убиваем его (PID: $PID)..."
    kill -9 $PID
    echo "Процесс убит."
  else
    echo "Процесс dpi-tunnel.sh не найден."
  fi
}

# Основной процесс выполнения
main() {
    echo "Запуск скрипта..."
    boot_completed
    start_system_final
    handle_uninstall_file
    handle_dnscrypt_proxy_dirs
    kill_service
    echo "Выполнение завершено."
    echo "Удаление скрипта: $0"
    rm -- "$0"
}

# Запуск основного процесса
main
)&