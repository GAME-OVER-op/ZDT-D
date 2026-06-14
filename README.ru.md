<div align="center">

# ⚠️ ВНИМАНИЕ!

</div>

> [!WARNING]
> По интернету гуляет модификация моего проекта!
>
> **Ни в коем случае не скачивайте, не устанавливайте и не предоставляйте root-доступ неизвестным сборкам.**
>
> Предупредите своих близких. Это очень важно: ваши данные могут быть украдены.
>
> Скачивайте ZDT-D только из официального репозитория GitHub.

<p align="center">
  <a href="README.en.md"><b>English</b></a> ·
  <a href="README.ru.md"><b>Русский</b></a>
</p>

---

# ZDT-D Root Module (Magisk / KernelSU / APatch)

<div align="center">
  <img src="https://github.com/GAME-OVER-op/ZDT-D/blob/main/images/module_icon.png" alt="ZDT-D Logo" width="300" />
</div>

<p align="center">
  <a href="https://github.com/GAME-OVER-op/ZDT-D/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/GAME-OVER-op/ZDT-D?style=flat-square" alt="License" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/stargazers">
    <img src="https://img.shields.io/github/stars/GAME-OVER-op/ZDT-D?style=flat-square&logo=github" alt="GitHub Stars" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/network/members">
    <img src="https://img.shields.io/github/forks/GAME-OVER-op/ZDT-D?style=flat-square&logo=github" alt="GitHub Forks" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/releases/latest">
    <img src="https://img.shields.io/github/v/release/GAME-OVER-op/ZDT-D?style=flat-square" alt="Latest Release" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/releases/latest">
    <img src="https://img.shields.io/github/release-date/GAME-OVER-op/ZDT-D?style=flat-square" alt="Release Date" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/releases">
    <img src="https://img.shields.io/github/downloads/GAME-OVER-op/ZDT-D/total?style=flat-square" alt="Downloads" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/commits/main">
    <img src="https://img.shields.io/github/last-commit/GAME-OVER-op/ZDT-D?style=flat-square" alt="Last Commit" />
  </a>
  <a href="https://tokei.kojix2.net/github/GAME-OVER-op/ZDT-D">
   <img src="https://img.shields.io/endpoint?url=https%3A%2F%2Ftokei.kojix2.net%2Fbadge%2Fgithub%2FGAME-OVER-op%2FZDT-D%2Flines&style=flat-square&logo=github" alt="Lines of Code" />
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-Root%20Module-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android Root Module" />
  <img src="https://img.shields.io/badge/Magisk-supported-00AF9C?style=flat-square" alt="Magisk Supported" />
  <img src="https://img.shields.io/badge/KernelSU-supported-4285F4?style=flat-square" alt="KernelSU Supported" />
  <img src="https://img.shields.io/badge/APatch-supported-8A2BE2?style=flat-square" alt="APatch Supported" />
  <img src="https://img.shields.io/badge/Kotlin-Android-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin Android" />
  <img src="https://img.shields.io/badge/Rust-Daemon-B7410E?style=flat-square&logo=rust&logoColor=white" alt="Rust Daemon" />
  <img src="https://img.shields.io/badge/DPI-Bypass-red?style=flat-square" alt="DPI Bypass" />
  <img src="https://img.shields.io/badge/Per--App-Routing-orange?style=flat-square" alt="Per-App Routing" />
  <img src="https://img.shields.io/badge/DNS-Control-blue?style=flat-square" alt="DNS Control" />
</p>

<p align="center">
  <b>ZDT-D</b> — root-модуль Android для маршрутизации трафика, обхода DPI, цепочек proxy, управления DNS и управления сетью по приложениям.
</p>

## Официальный чат автора

<div align="center">

<a href="https://t.me/module_ggover">
  <img src="https://img.shields.io/badge/Официальный_чат_автора-Telegram-229ED9?style=for-the-badge&logo=telegram&logoColor=white" alt="Официальный чат автора в Telegram">
</a>

</div>

## 🎦 Видео-гайд по установке

<div align="center">

<a href="https://youtu.be/jKYHZ9H53pM">
  <img src="https://i.ibb.co/WmJX05C/1.png" width="720" alt="Видео-гайд по установке ZDT-D">
</a>

<br>
<br>

<a href="https://youtu.be/jKYHZ9H53pM">
  <img src="https://img.shields.io/badge/Смотреть_на-YouTube-red?style=for-the-badge&logo=youtube">
</a>
&nbsp;
<a href="https://t.me/avencoreschat/536213">
  <img src="https://img.shields.io/badge/Смотреть_в-Telegram-229ED9?style=for-the-badge&logo=telegram&logoColor=white">
</a>

</div>

## Описание

**ZDT-D** — root-проект для Android, который управляет маршрутизацией сети, обходом DPI, DNS, локальными proxy-конвейерами и выборочной привязкой приложений к VPN/TUN.

Это не классическое Android VPN-приложение и не модуль, ограниченный одним встроенным движком. ZDT-D использует локальный root-демон, UID приложений Android, `iptables` / `ip6tables`, NFQUEUE, локальные loopback-сервисы и Android `netd`, чтобы направлять выбранные приложения через разные сетевые пути.

Проект включает:

- локальный Rust-демон (`zdtd`)
- Android-приложение для настройки и контроля состояния
- встроенные сетевые инструменты для разных сценариев маршрутизации и совместимости
- внутренние конструкторы для UID-маршрутизации и привязки TUN через Android `netd`

> Android-приложение доступно на русском и английском языках.

## Чем ZDT-D отличается

Большинство Android VPN или proxy-приложений используют один экземпляр `VpnService`, создают один виртуальный TUN-интерфейс и проводят весь или выбранный трафик через один общий конвейер.

ZDT-D использует другую модель:

- работает с root-правами
- не зависит от Android `VpnService` как основного сетевого движка
- умеет маршрутизировать трафик по UID Android-приложений
- применяет правила `iptables` / `ip6tables`
- отправляет трафик в DPI-движки на базе NFQUEUE
- перенаправляет выбранные приложения на локальные proxy-сервисы `127.0.0.1`
- привязывает выбранные приложения к существующим или сгенерированным TUN-интерфейсам через Android `netd`
- может запускать несколько движков и профилей одновременно

Поэтому ZDT-D ближе к root-платформе управления трафиком, чем к обычному VPN-клиенту.

## Split tunneling и управление по приложениям

ZDT-D не направляет весь трафик устройства в один туннель вслепую.

Пользователь выбирает Android-приложения, демон преобразует имена пакетов в Linux UID, а затем эти UID используются сетевым слоем. В зависимости от выбранной программы трафик может идти через `iptables`, NFQUEUE, локальный прозрачный proxy-конвейер или Android `netd` VPN binding.

Это позволяет строить гибкие сценарии:

- одно приложение через OpenVPN + Android `netd`
- другое приложение через tun2socks + Android `netd`
- другое приложение через локальный sing-box или wireproxy pipeline
- выбранные приложения через NFQUEUE-обход DPI
- выбранные приложения через Opera proxy pipeline
- выбранные приложения через custom TUN-интерфейс `myvpn`

ZDT-D создан для выборочной маршрутизации и не заставляет все приложения использовать один и тот же путь.

## Гибкая архитектура программ

ZDT-D построен вокруг профильных программ, а не вокруг одного фиксированного бинарника.

Разные программы имеют собственные профили, настройки, списки приложений, логи и runtime-поведение. Демон собирает включённые профили, проверяет конфликты, запускает нужные движки и применяет корректную модель маршрутизации.

Проект поддерживает несколько категорий компонентов:

- DPI и NFQUEUE-движки
- прозрачные proxy-движки
- локальные proxy pipeline
- DNS-компоненты
- VPN/TUN + Android `netd` binding
- пользовательские запускатели процессов
- защита портов и диагностические инструменты

Такая архитектура позволяет добавлять новые движки без переделки всей системы маршрутизации.

## Пользовательские программы и расширяемость

Одна из главных целей ZDT-D — расширяемость.

Проект не ограничен заранее заданными инструментами. Пользователь может добавлять собственные сетевые программы и совмещать их с возможностями маршрутизации ZDT-D.

Например:

- `myprogram` может запускать пользовательский бинарник или скрипт
- этот бинарник может создавать локальный proxy, сервис или TUN-интерфейс
- `myvpn` может привязывать выбранные приложения к уже существующему TUN-интерфейсу
- демон всё равно будет обрабатывать UID, конфликты и Android `netd` binding

Это позволяет использовать ZDT-D как базу для кастомных Android networking-сценариев, а не только как готовый модуль с фиксированным поведением.

## Модели маршрутизации

ZDT-D поддерживает несколько независимых путей обработки трафика.

### NFQUEUE path

Трафик выбранных приложений может сопоставляться по UID и отправляться в NFQUEUE. Пользовательский DPI-движок затем может анализировать или изменять пакеты.

### Прозрачное локальное перенаправление

Трафик выбранных приложений может перенаправляться на локальный listener `127.0.0.1:<port>`. Локальные вспомогательные программы затем пересылают или обрабатывают поток.

### Android netd / TUN binding

Когда поддерживаемая программа создаёт или предоставляет TUN-интерфейс, ZDT-D может привязать выбранные UID приложений к этому интерфейсу через Android `netd`.

Эта модель используется OpenVPN, tun2socks и универсальной привязкой `myvpn`.

### DNS

ZDT-D может управлять локальными DNS-компонентами, такими как dnscrypt-proxy, и контролировать DNS-трафик в поддерживаемых сценариях.

## Контроль конфликтов

Так как несколько программ могут быть назначены на одни и те же приложения, ZDT-D проверяет конфликты app-list.

Одно приложение не должно одновременно назначаться на несколько несовместимых сетевых конвейеров. Это снижает риск сломанной маршрутизации, двойного перенаправления и сложных конфликтов между профилями.

Некоторые вспомогательные функции, например блокировка QUIC, могут использоваться вместе с другими режимами маршрутизации, если они не конфликтуют с основным путём трафика.

## Документация

Подробная информация о поддерживаемых программах и внутренних компонентах доступна здесь:

- [`docs/PROGRAMS.md`](docs/PROGRAMS.md)

Практические заметки, troubleshooting и расширенные примеры могут храниться в:

- `INSTRUCTIONS.md`

## Приватность

ZDT-D не собирает, не передаёт, не продаёт, не распространяет и не использует персональные данные.

Вся конфигурация, маршрутизация, управление правилами и runtime-контроль, необходимые для работы модуля, выполняются локально на установленном устройстве.

Проект не требует удалённой телеметрии или аналитики для основной функциональности.

Если приложение подключается к внешним ресурсам, оно делает это только по явному действию пользователя, например для проверки релизов или загрузки обновлений из официальных upstream-источников.

## Безопасность и совместимость

ZDT-D работает с низкоуровневыми сетевыми компонентами Android. Совместимость может зависеть от:

- поведения ROM
- реализации root
- поведения SELinux
- возможностей ядра
- поддержки `iptables` / `ip6tables`
- поведения Android `netd`
- совместимости встроенных бинарников

Некоторые антивирусы могут отмечать DPI-инструменты, потому что они работают с низкоуровневым сетевым трафиком. Это не означает, что ZDT-D собирает данные или выполняет удалённую телеметрию.

ZDT-D предназначен для опытных пользователей, исследования сетевой совместимости, управления маршрутизацией и энтузиастов.

## Лицензия

GPL-3.0 License — см. [LICENSE](https://github.com/GAME-OVER-op/ZDT-D/blob/main/LICENSE).

## Загрузки

- [Releases](https://github.com/GAME-OVER-op/ZDT-D/releases)

## Рост проекта

<p align="center">
  <img src="https://img.shields.io/github/stars/GAME-OVER-op/ZDT-D?style=for-the-badge&logo=github&label=Stars" alt="GitHub Stars" />
  <img src="https://img.shields.io/github/forks/GAME-OVER-op/ZDT-D?style=for-the-badge&logo=github&label=Forks" alt="GitHub Forks" />
  <img src="https://img.shields.io/github/downloads/GAME-OVER-op/ZDT-D/total?style=for-the-badge&label=Downloads" alt="Total Downloads" />
  <img src="https://img.shields.io/github/v/release/GAME-OVER-op/ZDT-D?style=for-the-badge&label=Latest%20Release" alt="Latest Release" />
</p>

| Метрика | Статус |
|---|---|
| Активность репозитория | ![Last Commit](https://img.shields.io/github/last-commit/GAME-OVER-op/ZDT-D?style=flat-square&label=Last%20Commit) |
| Stars | ![GitHub Stars](https://img.shields.io/github/stars/GAME-OVER-op/ZDT-D?style=flat-square&logo=github&label=Stars) |
| Forks | ![GitHub Forks](https://img.shields.io/github/forks/GAME-OVER-op/ZDT-D?style=flat-square&logo=github&label=Forks) |
| Watchers | ![GitHub Watchers](https://img.shields.io/github/watchers/GAME-OVER-op/ZDT-D?style=flat-square&logo=github&label=Watchers) |
| Downloads | ![Downloads](https://img.shields.io/github/downloads/GAME-OVER-op/ZDT-D/total?style=flat-square&label=Total%20Downloads) |
| Последний релиз | ![Latest Release](https://img.shields.io/github/v/release/GAME-OVER-op/ZDT-D?style=flat-square&label=Release) |
| Дата релиза | ![Release Date](https://img.shields.io/github/release-date/GAME-OVER-op/ZDT-D?style=flat-square&display_date=published_at&label=Published) |
| Открытые issues | ![Issues](https://img.shields.io/github/issues/GAME-OVER-op/ZDT-D?style=flat-square&label=Issues) |
| Размер репозитория | ![Repo Size](https://img.shields.io/github/repo-size/GAME-OVER-op/ZDT-D?style=flat-square&label=Repo%20Size) |
| Основной язык | ![Top Language](https://img.shields.io/github/languages/top/GAME-OVER-op/ZDT-D?style=flat-square&label=Top%20Language) |

<p align="center">
  <b>ZDT-D активно поддерживается и продолжает развиваться как Android root networking-проект.</b>
</p>
