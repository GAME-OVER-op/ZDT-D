#!/system/bin/sh

BASE="/data/adb/modules/ZDT-D/strategic/strategicvar"
CFG_DIR="$BASE/nfqws2"
LOG_DIR="$BASE/log"

NFQWS_BIN="/data/adb/modules/ZDT-D/bin/nfqws2"
QNUM="200"

mkdir -p "$LOG_DIR"

kill_nfqws() {
    pkill -9 -f "nfqws2" 2>/dev/null
    sleep 1
}

ask_start() {
    echo
    echo "========================================"
    echo " NFQWS config tester"
    echo " Config dir: $CFG_DIR"
    echo " Log dir:    $LOG_DIR"
    echo " NFQWS bin:  $NFQWS_BIN"
    echo " QNUM:       $QNUM"
    echo "========================================"
    echo

    if [ ! -f "$NFQWS_BIN" ]; then
        echo "[ERROR] nfqws binary not found: $NFQWS_BIN"
        exit 1
    fi

    if [ ! -x "$NFQWS_BIN" ]; then
        echo "[WARN] nfqws binary is not executable, trying chmod 755..."
        chmod 755 "$NFQWS_BIN" 2>/dev/null
    fi

    if [ ! -x "$NFQWS_BIN" ]; then
        echo "[ERROR] nfqws binary is still not executable: $NFQWS_BIN"
        exit 1
    fi

    if [ ! -d "$CFG_DIR" ]; then
        echo "[ERROR] config dir not found: $CFG_DIR"
        exit 1
    fi

    printf "Начать проверку? [Y/N]: "
    read ans
    case "$ans" in
        Y|y) echo "Старт проверки...";;
        *) echo "Отмена."; exit 0;;
    esac
}

prepare_args() {
    CFG="$1"
    # Убираем пустые строки, комментарии и переносные слеши \
    # Склеиваем многострочный конфиг в одну строку
    grep -v '^[[:space:]]*$' "$CFG" \
        | grep -v '^[[:space:]]*#' \
        | sed 's/[[:space:]]*#.*$//' \
        | sed 's/[[:space:]]*\\[[:space:]]*$//' \
        | tr '\n' ' ' \
        | sed 's/[[:space:]][[:space:]]*/ /g' \
        | sed 's/^[[:space:]]*//' \
        | sed 's/[[:space:]]*$//'
}

run_config() {
    CFG="$1"
    NAME="$(basename "$CFG" .txt)"
    LOG="$LOG_DIR/$NAME.log"

    echo
    echo "========================================"
    echo " Проверка: $NAME"
    echo " Файл: $CFG"
    echo " Лог: $LOG"
    echo "========================================"

    kill_nfqws

    rm -f "$LOG"
    touch "$LOG"

    ARGS="$(prepare_args "$CFG")"

    {
        echo "========================================"
        echo "NFQWS TEST LOG"
        echo "CONFIG: $NAME"
        echo "FILE: $CFG"
        echo "LOG: $LOG"
        echo "QNUM: $QNUM"
        echo "TIME: $(date)"
        echo "========================================"
        echo
        echo "----------- CONFIG CONTENT START --------"
        cat "$CFG"
        echo
        echo "------------ CONFIG CONTENT END ---------"
        echo
        echo "----------- PARSED ARGS START -----------"
        echo "$ARGS"
        echo
        echo "------------ PARSED ARGS END ------------"
        echo
        echo "----------- FULL COMMAND START ----------"
        echo "$NFQWS_BIN --uid=0:0 --qnum=$QNUM $ARGS"
        echo "------------ FULL COMMAND END -----------"
        echo
        echo "----------- NFQWS OUTPUT START ----------"
    } >> "$LOG"

    sh -c "\"$NFQWS_BIN\" --uid=0:0 --qnum=$QNUM $ARGS" >> "$LOG" 2>&1 &
    PID="$!"

    sleep 1

    echo "------------ NFQWS OUTPUT END -----------" >> "$LOG"

    echo
    echo "----------- CONFIG CONTENT START --------"
    cat "$CFG"
    echo
    echo "------------ CONFIG CONTENT END ---------"

    echo
    echo "----------- PARSED ARGS START -----------"
    echo "$ARGS"
    echo
    echo "------------ PARSED ARGS END ------------"

    echo
    echo "----------- NFQWS LOG START -------------"
    if [ -s "$LOG" ]; then
        cat "$LOG"
    else
        echo "[empty log]"
    fi
    echo "------------ NFQWS LOG END --------------"
    echo

    if kill -0 "$PID" 2>/dev/null; then
        echo "[STATUS] nfqws process is running. PID=$PID"
    else
        echo "[STATUS] nfqws process is NOT running or crashed."
    fi

    echo
    echo "Y = работает, удалить лог"
    echo "N = не работает, оставить лог"
    echo "S = пропустить, оставить лог"
    echo "Q = выйти"
    printf "Решение [Y/N/S/Q]: "
    read decision

    case "$decision" in
        Y|y)
            echo "$NAME" >> "$LOG_DIR/working.txt"
            rm -f "$LOG"
            echo "[OK] $NAME, лог удалён"
            ;;
        N|n)
            echo "$NAME" >> "$LOG_DIR/failed.txt"
            echo "[BAD] $NAME, лог сохранён: $LOG"
            ;;
        S|s)
            echo "$NAME" >> "$LOG_DIR/skipped.txt"
            echo "[SKIP] $NAME, лог сохранён: $LOG"
            ;;
        Q|q)
            echo "Выход..."
            kill_nfqws
            exit 0
            ;;
        *)
            echo "$NAME" >> "$LOG_DIR/unknown.txt"
            echo "[UNKNOWN] Ответ не распознан, лог сохранён: $LOG"
            ;;
    esac

    kill_nfqws
}

trap '
echo
echo "CTRL+C detected. Killing nfqws..."
pkill -9 -f "nfqws" 2>/dev/null
exit 130
' INT TERM

ask_start

rm -f "$LOG_DIR/working.txt" "$LOG_DIR/failed.txt" "$LOG_DIR/skipped.txt" "$LOG_DIR/unknown.txt"

COUNT=0

for CFG in "$CFG_DIR"/*.txt; do
    [ -f "$CFG" ] || continue
    COUNT=$((COUNT + 1))
    run_config "$CFG"
done

kill_nfqws

echo
echo "========================================"
echo " Проверка завершена"
echo "========================================"
echo

echo "Рабочие:"
if [ -f "$LOG_DIR/working.txt" ]; then
    cat "$LOG_DIR/working.txt"
else
    echo "-"
fi

echo
echo "Не рабочие:"
if [ -f "$LOG_DIR/failed.txt" ]; then
    cat "$LOG_DIR/failed.txt"
else
    echo "-"
fi

echo
echo "Пропущенные:"
if [ -f "$LOG_DIR/skipped.txt" ]; then
    cat "$LOG_DIR/skipped.txt"
else
    echo "-"
fi

echo
echo "Всего проверено: $COUNT"