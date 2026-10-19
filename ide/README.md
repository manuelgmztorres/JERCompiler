# JER IDE

IDE minimo en Swing para escribir y compilar codigo JER sin usar la terminal.

## Compilar y ejecutar

Con doble clic o desde cualquier carpeta (los scripts se paran solos en la raiz del proyecto, `JERCompiler\`, porque el IDE busca `build\`, `pruebas\` y `salida\` relativas al directorio de trabajo):

```
ide\compilar.bat
ide\ejecutar.bat
```

Equivalente a mano, desde la raiz del proyecto:

```
javac -encoding UTF-8 -sourcepath ide\src -d ide\clases ide\src\ide\JERIde.java
java -cp ide\clases ide.JERIde
```

Con `-sourcepath`, `javac` compila `JERIde` y todo lo que usa; no hace falta listar los archivos. Requiere que `build\` ya tenga compilado el compilador JER (`JERCompiler.class`, etc.) — ver el README de la raiz. Recompila el IDE despues de cualquier cambio en `ide\src\`.

## Que hace

- **Arbol de proyecto** (izquierda): doble clic abre un archivo en una pestana (si ya estaba abierto, lo selecciona). Se refresca al guardar y al volver a enfocar la ventana.
- **Pestanas multi-archivo**: resaltado de sintaxis JER (con debounce), numeros de linea, boton "x" para cerrar (confirma si hay cambios sin guardar). Al cerrar la ventana tambien se confirma.
- **Autocompletado de `() {} "" ''`** y **auto-indentado** (Enter copia la indentacion y agrega un nivel tras `{`; `}` en una linea en blanco quita un nivel).
- **Atajos de edicion**: Ctrl+Z / Ctrl+Y deshacer-rehacer, Ctrl+Shift+Up/Down extiende la seleccion por linea, **Ctrl+/** (o Ctrl+7) comenta/descomenta con `#`, **Tab / Shift+Tab** indentan/desindentan las lineas seleccionadas, **Ctrl+rueda** cambia el tamano de la fuente.
- **Buscar y reemplazar** (Ctrl+F / Ctrl+H): siguiente, anterior, reemplazar y reemplazar todo (un solo Ctrl+Z lo deshace), sin distinguir mayusculas.
- **Guardar** (Ctrl+S) y **Guardar como** (Ctrl+Shift+S): el archivo nuevo sugiere `pruebas/` pero se puede elegir cualquier carpeta; se agrega `.txt` si no trae `.txt` ni `.jer`.
- **Compilar**: guarda si hace falta y corre `java -cp build JERCompiler <archivo>` en segundo plano (timeout de 30 s, la ventana no se congela). La consola tiene las pestanas **Salida** (completa) y **Errores** (solo errores). Clic en un error salta a esa linea y las lineas con error se marcan en rojo en el editor.
- **Codigo intermedio**: si la compilacion termina con `Errores: 0`, la consola carga lo que el compilador dejo en `salida\`, solo si esos archivos son de esta compilacion (con errores, las pestanas se vacian en vez de mostrar los de una compilacion anterior):
  - **Cuadruplos**: la tabla `Operador | Arg1 | Arg2 | Resultado` leida de `cuadruplos.txt`, con la cantidad de cuadruplos arriba, las filas `FUN` y `ETIQ` resaltadas y los saltos en azul. Doble clic en un `SALTO`/`SALTO_VER`/`SALTO_FAL` selecciona su `ETIQ`; doble clic en una `ETIQ` va al siguiente salto que llega a ella.
  - **Tres direcciones**: `cod_tres_dir.txt`; doble clic sobre una etiqueta (`L3`) lleva a la linea `L3:`.
  - **Tabla de simbolos**: `tabla_tipos.txt`.
- **Barra de estado**: estado de la compilacion (con el numero de cuadruplos) y `Ln/Col` del cursor. **Limpiar consola** vacia la salida, las vistas del codigo intermedio y las marcas.
- **Modo oscuro**: editor, numeros de linea, arbol, pestanas, consola, tabla de cuadruplos y resaltado. El tema y los archivos abiertos se recuerdan entre sesiones.

## Estructura

```
ide/
├─ README.md
├─ compilar.bat / ejecutar.bat
├─ clases/                         salida de javac (no se edita a mano)
└─ src/ide/
   ├─ JERIde.java                  ventana principal
   ├─ Tema.java
   ├─ editor/                      EditorTab, NumeroLineaGutter, JERSyntaxHighlighter, DialogoBuscar
   ├─ proyecto/                    ArbolProyecto
   ├─ compilacion/                 EjecutorCompilador, ResultadoCompilacion
   └─ consola/                     PanelConsola, PanelCodigoIntermedio, LectorCuadruplos
```

| Archivo | Rol |
|---|---|
| `JERIde.java` | Ventana principal: toolbar, atajos, pestanas de editor, sesion, tema, abrir/guardar y la orquestacion de la compilacion. |
| `Tema.java` | Paleta clara y oscura en un solo lugar; cada componente toma de aqui sus colores en `aplicarTema(Tema)`. |
| `editor/EditorTab.java` | Una pestana: `JTextPane`, undo/redo, autocompletado, indentado, comentar, buscar, zoom. |
| `editor/NumeroLineaGutter.java` | Gutter de numeros de linea, alineado con la geometria real del editor. |
| `editor/JERSyntaxHighlighter.java` | Coloreado de palabras clave, cadenas, comentarios y numeros. |
| `editor/DialogoBuscar.java` | Dialogo de buscar y reemplazar sobre la pestana activa. |
| `proyecto/ArbolProyecto.java` | Arbol de archivos del proyecto; se refresca conservando las carpetas abiertas. |
| `compilacion/EjecutorCompilador.java` | Lanza el compilador en un proceso aparte, con timeout, sin congelar la ventana. |
| `compilacion/ResultadoCompilacion.java` | Separa de la salida los errores, sus lineas y si termino con `Errores: 0` (sin Swing). |
| `consola/PanelConsola.java` | Pestanas Salida y Errores (clic en un error salta a su linea) y aloja las del codigo intermedio. |
| `consola/PanelCodigoIntermedio.java` | Pestanas Cuadruplos, Tres direcciones y Tabla de simbolos, con la navegacion de saltos. |
| `consola/LectorCuadruplos.java` | Lee `cuadruplos.txt` cortando las columnas por posicion (sin Swing). |

### Donde tocar

- **Un color**: solo `Tema.java`.
- **Una pestana nueva en la consola**: `PanelConsola` (y su indice `TAB_...`).
- **Cambia el formato de `salida\`**: `LectorCuadruplos` / `PanelCodigoIntermedio`.
- **Cambia como se invoca el compilador o su salida**: `EjecutorCompilador` / `ResultadoCompilacion`.
