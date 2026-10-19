@echo off
rem Compila el IDE: ide\src\ -> ide\clases\. Funciona desde cualquier carpeta.
rem javac sigue solo las dependencias de JERIde gracias a -sourcepath.
setlocal
cd /d "%~dp0.."
javac -encoding UTF-8 -sourcepath ide\src -d ide\clases ide\src\ide\JERIde.java
if errorlevel 1 (
    echo.
    echo [ERROR] No se pudo compilar el IDE.
    exit /b 1
)
echo IDE compilado en ide\clases\
