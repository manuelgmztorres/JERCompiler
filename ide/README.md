# JER IDE

IDE minimo en Swing para escribir y compilar codigo JER sin usar la terminal.

## Compilar y ejecutar

Desde la raiz del proyecto (`JERCompiler\`), porque el IDE busca `build\` y `pruebas\` como carpetas relativas al directorio de trabajo:

```
javac -d ide ide/JERSyntaxHighlighter.java ide/NumeroLineaGutter.java ide/EditorTab.java ide/JERIde.java
java -cp ide JERIde
```

Requiere que `build/` ya tenga compilado el compilador JER (`JERCompiler.class`, etc.) — si no, corre primero los pasos de `COMPILACION.md`.

## Que hace

- **Arbol de proyecto** (izquierda): navega el proyecto, doble clic abre un archivo en una pestana nueva.
- **Pestanas multi-archivo**: cada una con resaltado de sintaxis JER, numeros de linea, boton "x" para cerrar (confirma si hay cambios sin guardar).
- **Autocompletado de `() {} "" ''`**: al escribir el caracter de apertura se inserta el cierre; si el cierre ya sigue, lo salta en vez de duplicarlo.
- **Auto-indentado**: Enter copia la indentacion de la linea actual y agrega un nivel si termina en `{`; escribir `}` en una linea solo con espacios le quita un nivel.
- **Ctrl+Z / Ctrl+Y**: deshacer / rehacer.
- **Ctrl+Shift+Up/Down**: extiende la seleccion linea por linea.
- **Ctrl+S / boton Guardar**: guarda el archivo. Si es nuevo, pide nombre — el guardado esta restringido a la carpeta `pruebas/` con extension `.txt`.
- **Compilar**: guarda el archivo (si hace falta) y corre `java -cp build JERCompiler <archivo>` como subproceso; la salida (tokens, errores lexicos/sintacticos/semanticos, tabla de tipos) se muestra en la consola de abajo.
- **Modo oscuro**: boton en la barra de herramientas, afecta editor, numeros de linea, arbol y resaltado de sintaxis.

## Archivos

| Archivo | Rol |
|---|---|
| `JERIde.java` | Ventana principal: arbol, pestanas, toolbar, guardar/compilar. |
| `EditorTab.java` | Una pestana: `JTextPane`, undo/redo, autocompletado, auto-indentado. |
| `NumeroLineaGutter.java` | Gutter de numeros de linea, alineado con la geometria real del editor. |
| `JERSyntaxHighlighter.java` | Coloreado de palabras clave, cadenas, comentarios y numeros. |
