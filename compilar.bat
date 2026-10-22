@echo off
rem Compila el compilador JER completo: jjtree -> javacc -> javac (ver README, "Si modificaste codigo").
rem Funciona desde cualquier carpeta: se para en la raiz del proyecto (JERCompiler\).
rem JavaCC: usa jjtree/javacc del PATH si existen; si no, el .jar de JAVACC_JAR
rem (por defecto C:\javacc\javacc-7.0.13.jar).
setlocal
cd /d "%~dp0"

if not defined JAVACC_JAR set "JAVACC_JAR=C:\javacc\javacc-7.0.13.jar"
where jjtree >nul 2>nul && where javacc >nul 2>nul
if errorlevel 1 (
    if not exist "%JAVACC_JAR%" (
        echo [ERROR] No se encontro JavaCC: no hay jjtree/javacc en el PATH ni existe "%JAVACC_JAR%".
        echo         Define JAVACC_JAR con la ruta al .jar, por ejemplo: set JAVACC_JAR=D:\javacc\javacc-7.0.13.jar
        exit /b 1
    )
    set "JJTREE=java -cp "%JAVACC_JAR%" jjtree"
    set "JAVACC=java -cp "%JAVACC_JAR%" javacc"
) else (
    set "JJTREE=call jjtree"
    set "JAVACC=call javacc"
)

echo [1/3] jjtree analizador\JERCompiler_JJTree.jjt
%JJTREE% analizador\JERCompiler_JJTree.jjt
if errorlevel 1 goto :error

echo.
echo [2/3] javacc build\JERCompiler_JJTree.jj
%JAVACC% build\JERCompiler_JJTree.jj
if errorlevel 1 goto :error

echo.
echo [3/3] javac -d build build\*.java analizador\*.java
javac -d build build\*.java analizador\*.java
if errorlevel 1 goto :error

echo.
echo Compilador listo en build\. Prueba: java -cp build JERCompiler pruebas\prueba_valida.txt
exit /b 0

:error
echo.
echo [ERROR] Fallo la compilacion del compilador.
exit /b 1
