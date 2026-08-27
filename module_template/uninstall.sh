#!/system/bin/sh
(
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 5

settings put global http_proxy :0

for k in \
  global_http_proxy_host \
  global_http_proxy_port \
  global_http_proxy_exclusion_list \
  global_proxy_pac_url \
  captive_portal_detection_enabled \
  captive_portal_server \
  captive_portal_mode
do
  settings delete global "$k"
done

echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null \
  || sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1

rm -rf /data/adb/ZDT-D 2>/dev/null
exit 0
)&