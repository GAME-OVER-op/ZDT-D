#!/system/bin/sh
(
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 5
done

sleep 5

settings put global http_proxy :0
settings delete global global_http_proxy_host
settings delete global global_http_proxy_port
settings delete global global_http_proxy_exclusion_list
settings delete global global_proxy_pac_url

settings delete global captive_portal_detection_enabled
settings delete global captive_portal_server
settings delete global captive_portal_mode
)&