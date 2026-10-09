# JER IDE

IDE minimo en Swing para escribir y compilar codigo JER sin usar la terminal.

## Compilar y ejecutar

Desde la raiz del proyecto (`JERCompiler\`), porque el IDE busca `build\` y `pruebas\` como carpetas relativas al directorio de trabajo:

```
javac -d ide ide/JERSyntaxHighlighter.java ide/NumeroLineaGutter.java ide/EditorTab.java ide/JERIde.java
java -cp ide JERIde
```

Requiere que `build/` ya tenga compilado el compilador JER (`JERCompiler.class`, etc.) — ver el README de la raiz.

## Que hace

- **Arbol de proyecto** (izquierda): doble clic abre un archivo en una pestana (si ya estaba abierto, lo selecciona). Se refresca al guardar y al volver a enfocar la ventana.
- **Pestanas multi-archivo**: resaltado de sintaxis JER (con debounce), numeros de linea, boton "x" para cerrar (confirma si hay cambios sin guardar). Al cerrar la ventana tambien se confirma.
- **Autocompletado de `() {} "" ''`** y **auto-indentado** (Enter copia la indentacion y agrega un nivel tras `{`; `}` en una linea en blanco quita un nivel).
- **Atajos de edicion**: Ctrl+Z / Ctrl+Y deshacer-rehacer, Ctrl+Shift+Up/Down extiende la seleccion por linea, **Ctrl+/** (o Ctrl+7) comenta/descomenta con `#`, **Tab / Shift+Tab** indentan/desindentan las lineas seleccionadas, **Ctrl+rueda** cambia el tamano de la fuente.
- **Buscar y reemplazar** (Ctrl+F / Ctrl+H): siguiente, anterior, reemplazar y reemplazar todo (un solo Ctrl+Z lo deshace), sin distinguir mayusculas.
- **Guardar** (Ctrl+S) y **Guardar como** (Ctrl+Shift+S): el archivo nuevo sugiere `pruebas/` pero se puede elegir cualquier carpeta; se agrega `.txt` si no trae `.txt` ni `.jer`.
- **Compilar**: guarda si hace falta y corre `java -cp build JERCompiler <archivo>` en segundo plano (timeout de 30 s, la ventana no se congela). La consola tiene dos pestanas: **Salida** (completa) y **Errores** (solo errores). Clic en un error salta a esa linea y las lineas con error se marcan en rojo en el editor.
- **Barra de estado**: estado de la compilacion y `Ln/Col` del cursor. **Limpiar consola** vacia la salida y las marcas.
- **Modo oscuro**: editor, numeros de linea, arbol, pestanas, consola y resaltado. El tema y los archivos abiertos se recuerdan entre sesiones.

## Archivos

| Archivo | Rol |
|---|---|
| `JERIde.java` | Ventana principal: arbol, pestanas, toolbar, guardar/compilar, buscar, sesion. |
| `EditorTab.java` | Una pestana: `JTextPane`, undo/redo, autocompletado, indentado, comentar, buscar, zoom. |
| `NumeroLineaGutter.java` | Gutter de numeros de linea, alineado con la geometria real del editor. |
| `JERSyntaxHighlighter.java` | Coloreado de palabras clave, cadenas, comentarios y numeros. |
