# ZDT-D build automation

## Быстрый старт в Termux

```bash
make bootstrap
make doctor
make
```

`make bootstrap` делает:
- устанавливает Termux-пакеты (`openjdk-17`, `rust`, `clang`, `curl`, `wget`, `zip`, `unzip`, `make`, `git`)
- скачивает локальный Gradle 8.2
- скачивает Android cmdline-tools
- устанавливает `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`
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
- `out/dist/zdt-app.apk`


- По умолчанию APK собирается через Debug, но итоговый файл называется `app-release.apk` и подписывается базовым keystore `android` (v1/v2/v3).

> Примечание: AGP в проекте может быть `8.2.2`, но скачиваемый дистрибутив Gradle должен быть `8.2`, потому что `gradle-8.2.2-bin.zip` официально не существует.
