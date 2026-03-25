Сюда положи готовые arm64 бинарники, которые НЕ собираются автоматически:
- byedpi
- dnscrypt
- dpitunnel-cli
- nfqws
- nfqws2
- opera-proxy
- sing-box

Эти два бинарника build.sh собирает сам из Rust и кладет в итоговый bin/:
- zdtd
- t2s

Важно:
- в module_template/ бинарников быть не должно
- если хоть одного внешнего бинарника не хватает, build.sh завершится с ошибкой
