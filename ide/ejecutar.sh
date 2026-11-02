#!/usr/bin/env bash
# Abre el IDE. Se para en la raiz del proyecto (JERCompiler/) porque el IDE busca build/,
# pruebas/ y salida/ relativas al directorio de trabajo. Funciona desde cualquier carpeta.
cd "$(dirname "$(readlink -f "$0")")/.."
if [ ! -f ide/clases/ide/JERIde.class ]; then
    echo "El IDE no esta compilado: ejecuta primero ide/compilar.sh"
    exit 1
fi
if [ ! -f build/JERCompiler.class ]; then
    echo "Aviso: falta build/JERCompiler.class; el IDE abre, pero no podra compilar. Ver el README de la raiz."
fi
exec java -cp ide/clases ide.JERIde
