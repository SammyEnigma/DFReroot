#!/bin/sh

set -eux
if [ -e app/keystore.jks ]; then
    echo Already exist.
else
    keytool -genkey -v -keystore app/keystore.jks -keyalg RSA -storetype JKS -keysize 2048 -validity 10000 -alias dfreroot -storepass dfreroot -keypass dfreroot
    echo Generated on app/keystore.jks
fi
