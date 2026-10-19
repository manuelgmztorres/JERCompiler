@echo off
rem Abre el IDE. Se para en la raiz del proyecto (JERCompiler\) porque el IDE busca build\,
rem pruebas\ y salida\ relativas al directorio de trabajo. Funciona desde cualquier carpeta.
setlocal
cd /d "%~dp0.."
if not exist ide\clases\ide\JERIde.class (
    echo El IDE no esta compilado: ejecuta primero ide\compilar.bat
    exit /b 1
)
if not exist build\JERCompiler.class (
    echo Aviso: falta build\JERCompiler.class; el IDE abre, pero no podra compilar. Ver el README de la raiz.
)
java -cp ide\clases ide.JERIde
