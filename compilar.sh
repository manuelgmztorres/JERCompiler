#!/usr/bin/env bash
# Compila el compilador JER completo: jjtree -> javacc -> javac (equivalente Linux de compilar.bat).
# Funciona desde cualquier carpeta: se para en la raiz del proyecto (JERCompiler/).
# JavaCC: usa jjtree/javacc del PATH si existen; si no, el .jar de JAVACC_JAR
# (por defecto ~/javacc/javacc-7.0.13.jar).
set -u
cd "$(dirname "$(readlink -f "$0")")"

JAVACC_JAR="${JAVACC_JAR:-$HOME/javacc/javacc-7.0.13.jar}"

if command -v jjtree >/dev/null 2>&1 && command -v javacc >/dev/null 2>&1; then
    JJTREE=(jjtree)
    JAVACC=(javacc)
else
    if [ ! -f "$JAVACC_JAR" ]; then
        echo "[ERROR] No se encontro JavaCC: no hay jjtree/javacc en el PATH ni existe \"$JAVACC_JAR\"."
        echo "        Define JAVACC_JAR con la ruta al .jar, por ejemplo: export JAVACC_JAR=/opt/javacc/javacc-7.0.13.jar"
        exit 1
    fi
    JJTREE=(java -cp "$JAVACC_JAR" jjtree)
    JAVACC=(java -cp "$JAVACC_JAR" javacc)
fi

error() {
    echo
    echo "[ERROR] Fallo la compilacion del compilador."
    exit 1
}

echo "[1/3] jjtree analizador/JERCompiler_JJTree.jjt"
"${JJTREE[@]}" analizador/JERCompiler_JJTree.jjt || error

echo
echo "[2/3] javacc build/JERCompiler_JJTree.jj"
"${JAVACC[@]}" build/JERCompiler_JJTree.jj || error

echo
echo "[3/3] javac -d build build/*.java analizador/*.java"
javac -d build build/*.java analizador/*.java || error

echo
echo "Compilador listo en build/. Prueba: java -cp build JERCompiler pruebas/prueba_valida.txt"
