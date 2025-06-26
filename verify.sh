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
    MSG_DAMAGED_FILE="! Этот zip-файл может быть повреждён, попробуйте скачать его снова."
    MSG_DOESNT_EXIST="не существует"
    MSG_SHA256_ERROR="не соответствует своему контрольному суммарному значению"
    MSG_CHECK="- Проверено"
    MSG_DOWNLOAD_MAGISK="- Скачайте из приложения Magisk"
else
    MSG_DAMAGED_FILE="! This zip file may be corrupted, try downloading it again."
    MSG_DOESNT_EXIST="does not exist"
    MSG_SHA256_ERROR="does not match its checksum"
    MSG_CHECK="- Checked"
    MSG_DOWNLOAD_MAGISK="- Download from the Magisk app"
fi

TMPDIR_FOR_VERIFY="$TMPDIR/.vunzip"
mkdir -p "$TMPDIR_FOR_VERIFY"

abort_verify() {
	ui_print "*********************************************************"
	ui_print "! $1"
	ui_print "$MSG_DAMAGED_FILE"
	abort "*********************************************************"
}

# extract <zip> <file> <target dir> <junk paths>
extract() {
	zip=$1
	file=$2
	dir=$3
	junk_paths=$4
	[ -z "$junk_paths" ] && junk_paths=false
	opts="-o"
	[ "$junk_paths" = true ] && opts="-oj"

	if [ "$junk_paths" = true ]; then
		file_path="$dir/$(basename "$file")"
		hash_path="$TMPDIR_FOR_VERIFY/$(basename "$file").sha256"
	else
		file_path="$dir/$file"
		hash_path="$TMPDIR_FOR_VERIFY/$file.sha256"
	fi

	unzip $opts "$zip" "$file" -d "$dir" >&2
	[ -f "$file_path" ] || abort_verify "$file $MSG_DOESNT_EXIST"

	unzip $opts "$zip" "$file.sha256" -d "$TMPDIR_FOR_VERIFY" >&2
	[ -f "$hash_path" ] || abort_verify "$file.sha256 $MSG_DOESNT_EXIST"

	(echo "$(cat "$hash_path")  $file_path" | sha256sum -c -s -) || abort_verify "$file $MSG_SHA256_ERROR"
	ui_print "$MSG_CHECK $file" >&1

	unset file_path
	unset hash_path
}

file="META-INF/com/google/android/update-binary"
file_path="$TMPDIR_FOR_VERIFY/$file"
hash_path="$file_path.sha256"
unzip -o "$ZIPFILE" "META-INF/com/google/android/*" -d "$TMPDIR_FOR_VERIFY" >&2
[ -f "$file_path" ] || abort_verify "$file $MSG_DOESNT_EXIST"
if [ -f "$hash_path" ]; then
	(echo "$(cat "$hash_path")  $file_path" | sha256sum -c -s -) || abort_verify "$file $MSG_SHA256_ERROR"
	ui_print "$MSG_CHECK $file" >&1
else
	ui_print "$MSG_DOWNLOAD_MAGISK"
fi
