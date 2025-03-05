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

    if [ "$old_hash" = "$new_hash" ]; then
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
