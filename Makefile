.PHONY: all auto apk module clean clear doctor keystore setup bootstrap setup-termux setup-gradle setup-android patch-paths

all: apk
auto: apk

apk:
	./build.sh

module:
	./build.sh module

doctor:
	./build.sh doctor

keystore:
	./build.sh keystore

setup: bootstrap

bootstrap:
	./build.sh setup-all

setup-termux:
	./build.sh setup-termux

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
