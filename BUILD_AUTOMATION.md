# ZDT-D build automation

## Быстрый старт в Termux

```bash
make bootstrap
make doctor
make
```

`make bootstrap` делает:
- устанавливает Termux-пакеты (`openjdk-17`, `rust`, `clang`, `curl`, `wget`, `aria2`, `zip`, `unzip`, `make`, `git`, `aapt2`)
- скачивает локальный Gradle 9.4.1
- скачивает Android cmdline-tools
- устанавливает `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`
- для Termux и `compileSdk 35+` автоматически использует lzhiyong `aapt2`
- создаёт `application/local.properties`

## Ручные режимы

```bash
./build.sh setup-termux
./build.sh setup-gradle
./build.sh setup-android
./build.sh patch-paths
./build.sh module
./build.sh apk
```

## lzhiyong aapt2 для Termux

Стандартный Termux `aapt2` может не читать `android-35/android.jar` и `android-36/android.jar`.
Для сборки на SDK 36 `build.sh` автоматически ищет или скачивает:

```text
$HOME/android-sdk-tools-lzhiyong-35.0.2/build-tools/aapt2
```

Можно задать свой путь вручную:

```bash
AAPT2_OVERRIDE=/path/to/aapt2 make debug
```

Если `sdkmanager` обрывает загрузку `platforms;android-36`, скрипт пробует прямую загрузку `platform-36_r01.zip` и устанавливает platform вручную.

## Куда складывать внешние бинарники

```text
prebuilt/bin/arm64-v8a/
```

Обязательные имена:
- byedpi
- dnscrypt
- dpitunnel-cli
- nfqws
- nfqws2
- opera-proxy
- sing-box

## Автособираемые бинарники

- zdtd
- t2s

## Выходные артефакты

- `out/dist/zdt_module.zip`
- `out/dist/app-release.apk`

По умолчанию APK собирается через Debug, но итоговый файл называется `app-release.apk` и подписывается базовым keystore `android` (v1/v2/v3).

## Compile SDK

По умолчанию проект собирается с:

```text
compileSdk 36
targetSdk 36
build-tools 36.0.0
```

`targetSdk` обновлён до 36 вместе с `compileSdk`, поэтому перед релизом стоит отдельно проверить runtime-поведение на Android 15/16.
`compileSdk` можно переопределить:

```bash
ZDT_COMPILE_SDK=36 make debug
# или
./gradlew :app:assembleDebug -PzdtCompileSdk=36
```

## AndroidX / Compose dependencies

Проект снова использует новые зависимости под `compileSdk 36`:

- `core-ktx 1.18.0`
- `appcompat 1.7.1`
- `activity-compose 1.13.0`
- `lifecycle 2.10.0`
- `compose-bom 2026.05.00`
- `okhttp 5.3.2`
- `libsu 6.0.0`

## World Map geolocation component

The APK does not bundle the DB-IP MMDB file.

When the user opens the World Map and accepts the warning dialog, the application downloads the optional runtime component on demand:

```text
https://github.com/GAME-OVER-op/ZDT-D/releases/download/Technical_Assets/dbip-city-lite.mmdb.gz
```

Runtime flow:

```text
files/geo/dbip-city-lite.mmdb.gz  -> downloaded temporary archive
files/geo/dbip-city-lite.mmdb     -> unpacked local database
```

After successful unpacking and verification, the `.gz` archive is deleted to save storage.

Source and attribution:

```text
DB-IP Lite City MMDB
IP geolocation data by DB-IP.com
```

The World Map no longer sends IP addresses to external geolocation API services. The only network request is the optional component download from the ZDT-D GitHub release.
