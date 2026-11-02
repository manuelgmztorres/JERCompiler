#!/usr/bin/env bash
# Compila el IDE: ide/src/ -> ide/clases/. Funciona desde cualquier carpeta.
# javac sigue solo las dependencias de JERIde gracias a -sourcepath.
cd "$(dirname "$(readlink -f "$0")")/.."
if ! javac -encoding UTF-8 -sourcepath ide/src -d ide/clases ide/src/ide/JERIde.java; then
    echo
    echo "[ERROR] No se pudo compilar el IDE."
    exit 1
fi
echo "IDE compilado en ide/clases/"
