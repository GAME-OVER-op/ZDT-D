(
while [ "$(getprop sys.boot_completed)" != "1" ] && [ ! -d "/storage/emulated/0/Android" ]; do
  sleep 1
done

# Удаление временных файлов 
rm -f /data/local/tmp/icon.png
rm -f /data/local/tmp/icon1.png
rm -f /data/local/tmp/icon2.png
rm -f /data/local/tmp/icon3.png

settings put global http_proxy ""
settings put global http_proxy :0
iptables -t nat -F
iptables -t mangle -F

)&
