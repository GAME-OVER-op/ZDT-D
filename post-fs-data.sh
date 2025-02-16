#!/system/bin/sh


SETTING_START_PARAMS="/data/adb/modules/ZDT-D/working_folder/params"

# Чтение файла "parament"
while IFS='=' read -r key value || [ -n "$key" ]; do
    # Убираем пробелы с обеих сторон
    key=$(echo $key | xargs)
    value=$(echo $value | xargs)

    # Проверяем значение для dns
    if [ "$key" = "dns" ]; then
        dns="$value"
    fi
done < "$SETTING_START_PARAMS"

# Если значение dns установлено, проверяем его
if [ "$dns" = "1" ]; then
    echo "DNS включен"
    
# Do NOT assume where your module will be located.
# ALWAYS use $MODDIR if you need to know where this script
# and module is placed.
# This will make sure your module will still work
# if Magisk change its mount point in the future
MODDIR=${0%/*}

# This script will be executed in post-fs-data mode

# Redirect DNS requests to localhost
iptables -t nat -A OUTPUT -p tcp ! -d 1.1.1.1 --dport 53 -j DNAT --to-destination 127.0.0.1:5354
iptables -t nat -A OUTPUT -p udp ! -d 1.1.1.1 --dport 53 -j DNAT --to-destination 127.0.0.1:5354

# Force a specific DNS
# First two lines delete current DNS settings 
iptables -t nat -D OUTPUT -p tcp ! -d 1.1.1.1 --dport 53 -j DNAT --to-destination 127.0.0.1:5354
iptables -t nat -D OUTPUT -p udp ! -d 1.1.1.1 --dport 53 -j DNAT --to-destination 127.0.0.1:5354

# These two new lines sets DNS running at 127.0.0.1 on port 5354 
iptables -t nat -A OUTPUT -p tcp ! -d 1.1.1.1 --dport 53 -j DNAT --to-destination 127.0.0.1:5354
iptables -t nat -A OUTPUT -p udp ! -d 1.1.1.1 --dport 53 -j DNAT --to-destination 127.0.0.1:5354
# ip6tables -t nat -A OUTPUT -p tcp ! -d 45.11.45.11 --dport 53 -j DNAT --to-destination [::1]:5354
# ip6tables -t nat -A OUTPUT -p udp ! -d 45.11.45.11 --dport 53 -j DNAT --to-destination [::1]:5354

# Маскарадинг для всего исходящего трафика через любой интерфейс
iptables -t nat -A POSTROUTING -o + -j MASQUERADE

# Force disable IPv6 OS connections
resetprop net.ipv6.conf.all.accept_redirects 0
resetprop net.ipv6.conf.all.disable_ipv6 1
resetprop net.ipv6.conf.default.accept_redirects 0
resetprop net.ipv6.conf.default.disable_ipv6 1
elif [ "$dns" = "0" ]; then
    echo "DNS выключен"
else
    echo "Некорректное значение для dns или его нет"
fi




