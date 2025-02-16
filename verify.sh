TMPDIR_FOR_VERIFY="$TMPDIR/.vunzip"
mkdir "$TMPDIR_FOR_VERIFY"

abort_verify() {
	ui_print "*********************************************************"
	ui_print "! $1"
	ui_print "! Этот zip-файл может быть повреждён, попробуйте скачать его снова"
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
	[ $junk_paths = true ] && opts="-oj"

	if [ $junk_paths = true ]; then
		file_path="$dir/$(basename "$file")"
		hash_path="$TMPDIR_FOR_VERIFY/$(basename "$file").sha256"
	else
		file_path="$dir/$file"
		hash_path="$TMPDIR_FOR_VERIFY/$file.sha256"
	fi

	unzip $opts "$zip" "$file" -d "$dir" >&2
	[ -f "$file_path" ] || abort_verify "$file не существует"

	unzip $opts "$zip" "$file.sha256" -d "$TMPDIR_FOR_VERIFY" >&2
	[ -f "$hash_path" ] || abort_verify "$file.sha256 не существует"

	(echo "$(cat "$hash_path")  $file_path" | sha256sum -c -s -) || abort_verify "$file не соответствует своему контрольному суммарному значению"
	ui_print "- Проверено $file" >&1

	unset file_path
	unset hash_path
}

file="META-INF/com/google/android/update-binary"
file_path="$TMPDIR_FOR_VERIFY/$file"
hash_path="$file_path.sha256"
unzip -o "$ZIPFILE" "META-INF/com/google/android/*" -d "$TMPDIR_FOR_VERIFY" >&2
[ -f "$file_path" ] || abort_verify "$file не существует"
if [ -f "$hash_path" ]; then
	(echo "$(cat "$hash_path")  $file_path" | sha256sum -c -s -) || abort_verify "$file не соответствует своему контрольному суммарному значению"
	ui_print "- Проверено $file" >&1
else
	ui_print "- Скачайте из приложения Magisk"
fi
