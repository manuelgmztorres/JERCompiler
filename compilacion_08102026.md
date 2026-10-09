# 0. Ir a la raíz del proyecto
cd "C:\Users\Luis Hernandez\Desktop\JERCompiler"

# 1. (Opcional) Borrar los .class viejos para no mezclar versiones
Remove-Item build\*.class -ErrorAction SilentlyContinue

# 2. JJTree: lee la gramática y genera el .jj y los nodos AST en build\
jjtree analizador\JERCompiler_JJTree.jjt

# 3. JavaCC: genera el parser, el token manager, Token, ParseException, etc.
javacc build\JERCompiler_JJTree.jj

# 4. Compilar todo: lo generado en build\ y lo tuyo en analizador\
javac -encoding UTF-8 -d build -cp build build\*.java analizador\*.java

# 5. Consola en UTF-8 para ver los acentos
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 6. Ejecutar
java -cp build JERCompiler pruebas\prueba_luis.txt