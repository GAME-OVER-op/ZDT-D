.PHONY: all auto apk debug module clean clear doctor rust-doctor keystore setup bootstrap setup-termux setup-rust setup-gradle setup-android patch-paths

all: apk
auto: apk

apk:
	./build.sh

debug:
	./build.sh debug

module:
	./build.sh module

doctor:
	./build.sh doctor

rust-doctor:
	./build.sh doctor-rust

keystore:
	./build.sh keystore

setup: bootstrap

bootstrap:
	./build.sh setup-all

setup-termux:
	./build.sh setup-termux

setup-rust:
	./build.sh setup-rust

setup-gradle:
	./build.sh setup-gradle

setup-android:
	./build.sh setup-android

patch-paths:
	./build.sh patch-paths

clean:
	./build.sh clean

clear:
	./build.sh clear
