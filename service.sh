#!/system/bin/sh
MODDIR=${0%/*}
LOGFILE="$MODDIR/log/service.log"
LOGFILEPHP="$MODDIR/log/log_server_php.txt"
LOGFILEBYE="$MODDIR/log/ciadpi.log"
PHP_BINARY="$MODDIR/php7/files/bin/php"
> "$LOGFILE"
> "$LOGFILEPHP"
> "$LOGFILEBYE"

echo "$(date): Запуск service.sh" >> "$LOGFILE"

SCRIPT_PATH="$MODDIR/system/bin/script/ZDT-D.sh"

if [ -f "$SCRIPT_PATH" ]; then
    chmod +x "$SCRIPT_PATH"
    echo "$(date): Запуск ZDT-D.sh" >> "$LOGFILE"
    su -c "$SCRIPT_PATH" >> "$LOGFILE" 2>&1 &
else
    echo "$(date): Скрипт $SCRIPT_PATH не найден." >> "$LOGFILE"
fi

# Параметры BPF JIT
sysctl -w net.core.bpf_jit_enable=1
sysctl -w net.core.bpf_jit_harden=0
sysctl -w net.core.bpf_jit_kallsyms=1
sysctl -w net.core.bpf_jit_limit=33554432

# Busy polling
sysctl -w net.core.busy_poll=0
sysctl -w net.core.busy_read=0

# Очередь по умолчанию
sysctl -w net.core.default_qdisc=pfifo_fast

# Вес обработки сетевых пакетов
sysctl -w net.core.dev_weight=64
sysctl -w net.core.dev_weight_rx_bias=1
sysctl -w net.core.dev_weight_tx_bias=1

# Ограничения на потоки
sysctl -w net.core.flow_limit_cpu_bitmap=00
sysctl -w net.core.flow_limit_table_len=4096

# Фрагменты пакетов
sysctl -w net.core.max_skb_frags=17

# Параметры сообщений
sysctl -w net.core.message_burst=10
sysctl -w net.core.message_cost=5

# Очередь обработки пакетов
sysctl -w net.core.netdev_max_backlog=28000000
sysctl -w net.core.netdev_budget=1000
sysctl -w net.core.netdev_budget_usecs=16000

# Дополнительная память сокетов
sysctl -w net.core.optmem_max=65536

# Размеры буферов чтения и записи
sysctl -w net.core.rmem_default=229376
sysctl -w net.core.rmem_max=67108864
sysctl -w net.core.wmem_default=229376
sysctl -w net.core.wmem_max=67108864

# Максимальная очередь соединений
sysctl -w net.core.somaxconn=1024

# Разрешить временные метки данных
sysctl -w net.core.tstamp_allow_data=1

# Параметры XFRM
sysctl -w net.core.xfrm_acq_expires=3600
sysctl -w net.core.xfrm_aevent_etime=10
sysctl -w net.core.xfrm_aevent_rseqth=2
sysctl -w net.core.xfrm_larval_drop=1

# Проверяем существование файла php
if [ ! -f "$PHP_BINARY" ]; then
    echo "Файл $PHP_BINARY не найден. Убедитесь, что он существует." >> "$LOGFILEPHP"
    exit 1
fi

# Выставляем права 777 для php
chmod 777 "$PHP_BINARY"

# Запускаем сервер
"$PHP_BINARY" -S 127.0.0.1:1137 -t "$MODDIR/php7/files/www/" -c "$MODDIR/php7/files/php.ini" >> "$LOGFILEPHP" 2>&1 &

while ! [ `pgrep -x dnscrypt-proxy` ] ; do
	$MODDIR/system/bin/dnscrypt-proxy -config $MODDIR/dnscrypt-proxy/dnscrypt-proxy.toml && sleep 15;
done

