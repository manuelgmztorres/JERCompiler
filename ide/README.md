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
- **Compilar** (boton o **F5**): guarda si hace falta y corre `java -cp build JERCompiler <archivo>` en la consola del sistema (timeout de 30 s, la ventana no se congela). Con errores se abre la pestana **Errores**; sin errores la consola se queda en la pestana que estaba abierta (si era Errores, pasa a Salida). Clic en un error lleva el cursor a su linea y columna, y las lineas con error se marcan en rojo en el editor.
- **Consola**, en el orden de las etapas del compilador: **Salida**, **Errores** (solo errores), **Tokens**, **Tabla de simbolos** y **Cuadruplos**.
- **Salida = consola del sistema**: la pestana Salida es un `cmd.exe` real (en otros sistemas `/bin/sh`) abierto en la raiz del proyecto, con `chcp 65001` para UTF-8 y el `java` del IDE primero en el `PATH`. Se escribe despues del prompt y Enter manda el comando (flechas arriba/abajo: historial; Ctrl+C sin seleccion detiene el programa en curso; Ctrl+L o `cls` limpia). **Compilar** escribe `java -cp build JERCompiler <archivo>` en ese mismo shell, asi que el comando y su salida se ven ahi; los errores salen en rojo, las sugerencias `->` en amarillo y `Errores: 0` en verde. Clic en una linea con `Linea N, columna M` lleva el cursor ahi. Si el shell se cierra (`exit`), se reabre con el siguiente comando.
- **Codigo de tres direcciones como documento**: el editor se parte en dos y a la derecha se muestra `cod_tres_dir.txt` con encabezado, numeros de linea y resaltado (`FUN`, etiquetas, saltos, temporales, cadenas). Se muestra/oculta con el boton **Tres direcciones**, **Ctrl+T** o la `x` del encabezado; el estado se recuerda entre sesiones.
- **Tokens**: la tabla `No. | Lexema | Token | Linea | Columna` de `tabla_tokens.txt`; se carga en cada compilacion, aun con errores. Doble clic en un token lleva el cursor a su posicion.
- **Tabla de simbolos y codigo intermedio**: si la compilacion termina con `Errores: 0`, la consola carga lo que el compilador dejo en `salida\`, solo si esos archivos son de esta compilacion (con errores, las pestanas se vacian en vez de mostrar los de una compilacion anterior):
  - **Tabla de simbolos**: la tabla `Nombre | Categoria | Tipo | Dimensiones | Ambito | Linea | Usado | Parametros` de `tabla_tipos.txt`. Doble clic en un simbolo lleva el cursor a la linea donde se declaro.
  - **Cuadruplos**: la tabla `Operador | Arg1 | Arg2 | Resultado` leida de `cuadruplos.txt`, con la cantidad de cuadruplos arriba, las filas `FUN` y `ETIQ` resaltadas y los saltos en azul. Doble clic en un `SALTO`/`SALTO_VER`/`SALTO_FAL` selecciona su `ETIQ`; doble clic en una `ETIQ` va al siguiente salto que llega a ella.
  - **Tres direcciones** (documento a la derecha del editor): `cod_tres_dir.txt`; doble clic sobre una etiqueta (`L3`) lleva a la linea `L3:`.
- **Barra de estado**: estado de la compilacion (con el numero de cuadruplos) y `Ln/Col` del cursor. **Limpiar consola** vacia todas las pestanas y las marcas.
- **Modo oscuro**: editor, numeros de linea, arbol, pestanas, consola, tablas y resaltado. El tema y los archivos abiertos se recuerdan entre sesiones.

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
   └─ consola/                     PanelConsola, ConsolaSalida, PanelTablas, TablaTexto, LectorTablas,
                                   PanelCodigoIntermedio, DocumentoTresDirecciones, LectorCuadruplos
```

| Archivo | Rol |
|---|---|
| `JERIde.java` | Ventana principal: toolbar, atajos, pestanas de editor partidas con el documento de tres direcciones, sesion, tema, abrir/guardar y la orquestacion de la compilacion. |
| `Tema.java` | Paleta clara y oscura en un solo lugar; cada componente toma de aqui sus colores en `aplicarTema(Tema)`. |
| `editor/EditorTab.java` | Una pestana: `JTextPane`, undo/redo, autocompletado, indentado, comentar, buscar, zoom. |
| `editor/NumeroLineaGutter.java` | Gutter de numeros de linea, alineado con la geometria real del editor. |
| `editor/JERSyntaxHighlighter.java` | Coloreado de palabras clave, cadenas, comentarios y numeros. |
| `editor/DialogoBuscar.java` | Dialogo de buscar y reemplazar sobre la pestana activa. |
| `proyecto/ArbolProyecto.java` | Arbol de archivos del proyecto; se refresca conservando las carpetas abiertas. |
| `compilacion/EjecutorCompilador.java` | Arma el comando del compilador y lo ejecuta en la consola del sistema; entrega el resultado al terminar. |
| `compilacion/ResultadoCompilacion.java` | Separa de la salida los errores, sus lineas y si termino con `Errores: 0`; guarda codigo de salida y duracion (sin Swing). |
| `consola/PanelConsola.java` | Pestanas Salida y Errores (clic en un error lleva a su linea y columna) y aloja las demas, en el orden de las etapas. |
| `consola/ConsolaSalida.java` | Pestana Salida: `cmd.exe` del sistema embebido (entrada, salida coloreada, historial, Ctrl+C) y ejecucion de la compilacion en el. |
| `consola/PanelTablas.java` | Pestanas Tokens y Tabla de simbolos; doble clic lleva a la posicion del token o a la declaracion del simbolo. |
| `consola/TablaTexto.java` | Tabla de solo lectura con aviso y tema, usada por Tokens y Tabla de simbolos. |
| `consola/LectorTablas.java` | Lee `tabla_tokens.txt` y `tabla_tipos.txt` (sin Swing). |
| `consola/PanelCodigoIntermedio.java` | Pestana Cuadruplos (con la navegacion de saltos) y carga del documento de tres direcciones. |
| `consola/DocumentoTresDirecciones.java` | Documento de solo lectura a la derecha del editor: encabezado, numeros de linea, resaltado y salto a etiquetas. |
| `consola/LectorCuadruplos.java` | Lee `cuadruplos.txt` cortando las columnas por posicion (sin Swing). |

### Donde tocar

- **Un color**: solo `Tema.java`.
- **Una pestana nueva en la consola**: `PanelConsola` (y su indice `TAB_...`).
- **Cambia el formato de `salida\`**: `LectorTablas` / `PanelTablas` (tokens y simbolos) o `LectorCuadruplos` / `PanelCodigoIntermedio`.
- **Cambia como se invoca el compilador o su salida**: `EjecutorCompilador` / `ResultadoCompilacion`.
