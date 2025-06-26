#!/system/bin/sh
(
while [ "$(getprop sys.boot_completed)" != "1" ] && [ ! -d "/storage/emulated/0/Android" ]; do
  sleep 15
done

sleep 5
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
    
    MSG="До свидания, мой друг, и хорошего тебе дня."
else
    
    MSG="Goodbye my friend, and have a nice day."
    
fi

su -lp 2000 -c "cmd notification post -i file:///data/local/tmp/icon4.png -I file:///data/local/tmp/icon4.png -S messaging --conversation 'Goodbye' --message 'ZDT-D:$MSG ' -t 'CLI' 'Tag' 'Goodbye'" >/dev/null 2>&1

sleep 60
# Удаление временных файлов 
rm -f /data/local/tmp/icon.png
rm -f /data/local/tmp/icon1.png
rm -f /data/local/tmp/icon2.png
rm -f /data/local/tmp/icon3.png
rm -f /data/local/tmp/icon4.png

settings put global http_proxy ""
settings put global http_proxy :0
iptables -t nat -F
iptables -t mangle -F

)&
