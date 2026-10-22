# JERCompiler

JERCompiler es un compilador desarrollado con JavaCC/JJTree para un lenguaje general en español. Hace el análisis léxico, sintáctico y semántico (tipos, ámbitos y demás reglas sobre el AST) y, cuando el programa no tiene errores, genera el código intermedio: una tabla de cuádruplos y su versión como código de tres direcciones. No genera código objeto.

> **En desarrollo:** además del compilador, `ide/` tiene un IDE mínimo en Swing para escribir y compilar código JER sin terminal (se abre con `ide\ejecutar.bat`) — ver `ide/README.md`. Sigue en progreso, no es el foco de esta guía.

## Sintaxis principal

```jer
# Declaraciones globales de constantes y arreglos
CONST DEC PI -> 3.14159;
ENT edades[3] -> [18, 20, 21];
DEC notas[2][2] -> [[9.5, 8.0], [7.0, 10.0]];
ENT contador; # las variables ya no requieren valor inicial

FUN DEC promedio(DEC total, ENT cantidad) {
    DEC resultado -> total / cantidad;
    RET resultado;
}

FUN VACIO principal() {
    ENT indice -> 0;
    BOO indiceValido -> indice >= 0; # una comparacion tambien es un valor
    notas[indice][1] -> 10.0;
    SI indiceValido { # y una expresion sola tambien es una condicion valida
        IMP indiceValido;
    } SINO {
        OBT indice;
    }
}
```

### Características del Lenguaje:
* **Tipos de datos:** `ENT` (entero), `DEC` (decimal), `CAD` (cadena), `CAR` (carácter) y `BOO` (booleano).
* **Operadores de asignación:** `->` (asignación directa), `+->` (suma y asigna), `-->` (resta y asigna).
* **Operadores aritméticos:** `+`, `-`, `*`, `/`, `%`, `**` (potencia), `//` (raíz: `a // b` es la raíz de índice `b` de `a`, inversa de `**`), `++` (incremento), `--` (decremento).
* **Comparaciones:** `=` (igualdad), `!=` (diferente), `<`, `<=`, `>`, `>=`. Una comparación (o una combinación con `AND`/`OR`/`NOT`) también es un valor válido para inicializar, reasignar o retornar una variable/función `BOO` — no solo `VERDADERO`/`FALSO`.
* **Lógica:** `AND`, `OR`, `NOT`.
* **Control de flujo:** `SI`/`SINO`, `MIENTRAS`, `REPETIR`, `EVALUAR`/`CUANDO`/`PRED`, `TERMINAR`. La condición de `SI`/`MIENTRAS`/`REPETIR`/`HACER...MIENTRAS` también acepta una expresión sola (`SI bandera { ... }`), no solo una comparación explícita.
* **Funciones y E/S:** `FUN` requiere declarar su tipo de retorno antes del nombre (`ENT`/`DEC`/`CAD`/`CAR`/`BOO`, o `VACIO` si no retorna valor), `RET`, `IMP`, `OBT`.
* **Variables:** `CONST` sigue requiriendo valor inicial; una variable normal puede declararse sin inicializar (`ENT contador;`).

## Análisis semántico

Después de parsear, `AnalizadorSemantico` recorre el AST (vía el visitor `JERCompilerVisitor` que genera JJTree) en dos pasadas: la primera registra en una tabla de símbolos las variables, constantes y funciones globales (con su firma completa), para que una función pueda llamar a otra declarada más abajo en el archivo; la segunda entra a cada función y valida su cuerpo. Los errores que encuentra se imprimen como `[ERROR SEMÁNTICO]`, junto con los léxicos/sintácticos. Mientras valida, deja anotado en cada nodo su tipo y su símbolo para que el generador de código intermedio no tenga que recalcularlos.

Reglas que aplica:
* **Tipos estrictos, sin conversión implícita:** `ENT` y `DEC` nunca se mezclan (ni entre sí ni con ningún otro tipo) en aritmética, comparaciones, asignaciones o retornos — el programador debe declarar el tipo correcto de antemano. La única excepción es `+`, que concatena si cualquiera de los dos lados es `CAD` (el resultado es `CAD`, sea cual sea el otro tipo); `-`, `*`, `/`, `%`, `**`, `//` nunca aceptan `CAD`. Cuando los tipos no coinciden, el mensaje nombra el operando distinto (el tipo de referencia es el de la mayoría de los operandos: en `n1 + n2 + n3` con `n3` `DEC`, señala a `n3`), y si donde va un `DEC` se escribió un entero literal (`CONST DEC n3 -> 2;`, `d + 2`, `mitad(3)`) indica que un valor `DEC` lleva punto decimal (`2.0`).
* **Condición estrictamente `BOO`:** la condición de `SI`/`MIENTRAS`/`REPETIR`/`HACER...MIENTRAS` (incluida una expresión sola o negada con `NOT`) debe ser de tipo `BOO`; no hay semántica "truthy" para `ENT`/`DEC`.
* **Ámbitos (scopes):** cada bloque `{ }` (incluidos los de `SI`/`MIENTRAS`/`REPETIR`/`HACER`/`EVALUAR`) abre su propio ámbito anidado dentro de la función que lo contiene. Un parámetro o variable local puede ocultar (*shadowing*) a una variable/constante global del mismo nombre. Funciones y variables/constantes globales comparten un único espacio de nombres (no puede haber una función y una variable global con el mismo nombre).
* **Arreglos:** las dimensiones de la declaración y los índices de acceso deben ser `ENT`; el número de índices usados debe coincidir exactamente con la aridad declarada (los arreglos se modifican elemento por elemento, no se puede reemplazar uno completo con `->`); los elementos de un literal de arreglo deben ser todos del mismo tipo.
* **Funciones:** una llamada debe usar el número y tipo de argumentos exactos de la firma; una función `VACIO` no puede usarse como valor dentro de una expresión; `RET` debe coincidir con el tipo de retorno declarado (y una función `VACIO` no puede usar `RET` con valor); toda función con valor debe terminar en `RET` por todos sus caminos.
* **`TERMINAR`** solo es válido dentro de un bucle (`MIENTRAS`/`REPETIR`/`HACER`) o de un caso de `EVALUAR` (`CUANDO`/`PRED`).
* **`CONST` y `OBT`:** no se puede reasignar ni leer (`OBT`) hacia una constante, ni usar un identificador de función como si fuera una variable.
* **Inicializadores:** un arreglo solo se inicializa con un literal `[...]` y una variable simple nunca con uno; una variable no puede usarse en su propio inicializador (`ENT x -> x;`); y un global no puede usar en su inicializador otro global declarado más abajo, porque los globales se inicializan en el orden en que se declaran. Tampoco puede llamar a una función en su inicializador (`ENT x -> f();` a nivel global es error); si necesita ese valor, asígnelo dentro de `principal`.
* **Paso de `REPETIR`:** la variable que modifica no puede ser una constante, una función ni un arreglo completo.
* **Errores de ejecución evidentes al compilar:** cuando el operando es un literal escrito tal cual se rechazan `/` o `%` con divisor `0` (o `0.0`), `//` con índice de raíz `0`, `**` entre `ENT` con exponente literal negativo, un índice literal negativo o mayor o igual que el tamaño literal declarado de esa dimensión (`v[3]` en `ENT v[3]`), y un tamaño de arreglo literal menor o igual que 0. No se calculan constantes: `10 / (3 - 3)` o `v[0 - 1]` compilan y se deciden al ejecutar.
* **Punto de entrada obligatorio:** todo programa debe declarar `FUN VACIO principal()`, sin parámetros. Se comprueba al final del análisis, y solo si el archivo no tuvo errores léxicos ni sintácticos (con esos errores el árbol puede estar incompleto y el aviso sería un falso positivo).

## Generación de código intermedio

Cuando el análisis termina con **0 errores**, el compilador genera el código intermedio de todo el programa (declaraciones, expresiones, llamadas, arreglos, `SI`, bucles, `EVALUAR`, `TERMINAR` y `AND`/`OR`) y lo guarda en dos formas que describen exactamente lo mismo:

* **`salida\cuadruplos.txt`** (también se imprime en consola): la tabla de cuádruplos, con las cuatro columnas `Operador | Arg1 | Arg2 | Resultado`. Las celdas que no aplican quedan en blanco.
* **`salida\cod_tres_dir.txt`**: el código de tres direcciones, la misma tabla escrita como instrucciones (`t1 = i < 3`, `SALTO_FAL t1, L2`). Cada fila de la tabla es una línea del listado.

Ejemplo (`pruebas\prueba_revision2.txt`):

```jer
FUN VACIO principal() {
    ENT n -> 5;
    SI n > 3 { IMP "n es mayor que 3"; } SINO { IMP "n es menor o igual que 3"; }
    ENT i -> 1;
    MIENTRAS i <= n { IMP i; i++; }
}
```

| Operador | Arg1 | Arg2 | Resultado |
|---|---|---|---|
| `FUN` | _inicio |  |  |
| `LLAMAR` | principal | 0 |  |
| `RET` |  |  |  |
| `FUN` | principal |  |  |
| `ASIG` | 5 |  | n |
| `>` | n | 3 | t1 |
| `SALTO_FAL` | t1 |  | L1 |
| `IMP` | "n es mayor que 3" |  |  |
| `SALTO` |  |  | L2 |
| `ETIQ` |  |  | L1 |
| `IMP` | "n es menor o igual que 3" |  |  |
| `ETIQ` |  |  | L2 |
| `ASIG` | 1 |  | i |
| `ETIQ` |  |  | L3 |
| `<=` | i | n | t2 |
| `SALTO_FAL` | t2 |  | L4 |
| `IMP` | i |  |  |
| `+` | i | 1 | i |
| `SALTO` |  |  | L3 |
| `ETIQ` |  |  | L4 |
| `RET` |  |  |  |

```
FUN _inicio
        LLAMAR principal, 0
        RET

FUN principal
        n = 5
        t1 = n > 3
        SALTO_FAL t1, L1
        IMP "n es mayor que 3"
        SALTO L2
  L1:   IMP "n es menor o igual que 3"
  L2:   i = 1
  L3:   t2 = i <= n
        SALTO_FAL t2, L4
        IMP i
        i = i + 1
        SALTO L3
  L4:   RET
```

Cómo se arma:
* **Operandos:** una constante tal como se escribe en JER, un nombre de variable o parámetro, o un temporal `t1`, `t2`… (reinician en cada función). Una expresión compuesta se descompone en varias filas.
* **Arranque:** la tabla siempre empieza con la función `_inicio`, que es el punto de entrada: llama a `_global` (solo si hay variables globales) y después a `principal`; cuando `_inicio` llega a su `RET`, el programa termina. Así el orden de arranque queda escrito en el código intermedio. Los nombres con `_` no chocan con los del programa porque en JER un identificador empieza con letra.
* **Funciones:** cada una empieza con `FUN nombre` y una fila `RECIBIR` por parámetro, y termina en `RET`. Si el programa tiene variables globales, se inicializan en orden de declaración dentro de la función `_global`.
* **Saltos:** el destino de un salto es una etiqueta (`L1`, `L2`…) que se marca con una fila `ETIQ` justo antes de la fila destino; así la tabla conserva solo las cuatro columnas de un cuádruplo.
* **Llamadas:** una fila `PARAM` por argumento (todos se evalúan antes) y una fila `LLAMAR` con la función y la cantidad de argumentos.
* **Arreglos:** una fila `DIM` por dimensión y una `RESERVAR`; los elementos se leen con `LEER_ELEM` y se escriben con `ASIG_ELEM`. En un arreglo de varias dimensiones se aplica un índice por fila: `m[i][j]` es `LEER_ELEM m i t1` y luego `LEER_ELEM t1 j destino`. El `t1` intermedio es una **referencia** a la fila `m[i]`, no una copia, así que `t1[j] = v` (`ASIG_ELEM v j t1`) modifica `m`.
* **Variables locales con el mismo nombre** en bloques distintos (o que ocultan a un global) se renombran `x`, `x_2`, `x_3` para que no choquen en el listado plano.
* **Sin optimizaciones** (por ejemplo, sin plegado de constantes), para que el código intermedio refleje el programa fuente. Cada fila guarda en memoria el tipo de su resultado, aunque la tabla impresa no lo muestre.

Operadores de la tabla:

| Operador | Arg1 | Arg2 | Resultado | Qué hace |
|---|---|---|---|---|
| `+ - * / % ** //` | operando | operando | destino | Aritmética. `+` también concatena si hay un `CAD`; `//` es la raíz de índice `b` de `a`. |
| `== != < <= > >=` | operando | operando | destino (`BOO`) | Comparación (la igualdad es `==` para no confundirla con el `=` de JER). |
| `NOT` | operando |  | destino | Negación lógica. |
| `NEG` | operando |  | destino | Cambio de signo. |
| `ASIG` | operando |  | variable | Copia un valor. |
| `SALTO` |  |  | etiqueta | Salta siempre. |
| `SALTO_VER` | operando `BOO` |  | etiqueta | Salta si es verdadero. |
| `SALTO_FAL` | operando `BOO` |  | etiqueta | Salta si es falso. |
| `ETIQ` |  |  | etiqueta | Marca el destino de un salto; no hace nada al ejecutarse. |
| `FUN` | nombre |  |  | Inicio de una función. |
| `RECIBIR` |  |  | parámetro | Declara el siguiente parámetro. |
| `PARAM` | operando |  |  | Un argumento de la llamada que sigue. |
| `LLAMAR` | función | cantidad de argumentos | destino | Llama a una función (sin destino si es `VACIO` o se descarta el valor). |
| `RET` | operando |  |  | Sale de la función, con valor si lo devuelve. |
| `IMP` | operando |  |  | Imprime. |
| `OBT` |  |  | variable | Lee un valor. |
| `DIM` | operando |  |  | Tamaño de una dimensión del arreglo que se reserva a continuación. |
| `RESERVAR` | tipo | número de dimensiones | arreglo | Reserva el arreglo; sus elementos toman el valor por defecto del tipo. |
| `LEER_ELEM` | arreglo | índice | destino | Lee un elemento: `destino = arreglo[índice]`. Si al arreglo le quedan dimensiones (una fila de una matriz), `destino` es una referencia a esa fila, no una copia. |
| `ASIG_ELEM` | valor | índice | arreglo | Escribe un elemento: `arreglo[índice] = valor`. |

## Semántica de ejecución

Todavía no hay una etapa que ejecute el código intermedio. Estas son las decisiones que el generador respeta y que cualquier ejecución futura debe cumplir:

* **Punto de entrada:** `FUN VACIO principal()`, sin parámetros, obligatoria. La ejecución empieza en `_inicio` (la primera función de la tabla), que inicializa los globales con `_global` y luego llama a `principal`; el `RET` de `_inicio` termina el programa.
* **Filas de matrices por referencia:** `LEER_ELEM` sobre una fila de un arreglo de varias dimensiones devuelve una referencia a ella; escribir en esa referencia modifica el arreglo original.
* **`EVALUAR` sin caída:** solo se ejecuta el caso que coincide y luego se sale del `EVALUAR`. `TERMINAR` dentro de un caso es válido pero redundante.
* **Variables sin inicializar:** toman un valor por defecto fijo: `ENT` 0, `DEC` 0.0, `CAD` vacía, `CAR` carácter nulo y `BOO` `FALSO`.
* **`AND` y `OR` en cortocircuito:** en `a AND b`, si `a` es falso no se evalúa `b`; en `a OR b`, si `a` es verdadero no se evalúa `b`. No hay precedencia entre ellos: se evalúan de izquierda a derecha (`a OR b AND c` es `(a OR b) AND c`).
* **Orden de inicialización de globales:** el de declaración, antes de ejecutar `principal`.
* **Tamaño de arreglo:** puede ser cualquier expresión `ENT` (por ejemplo, un valor leído con `OBT`); la reserva de memoria es dinámica.
* **Errores de ejecución** (asumido, pendiente de confirmar con el equipo): lo que no se pudo ver al compilar detiene el programa con un mensaje `ERROR DE EJECUCIÓN: <causa>`. Son división o módulo entre cero (también con `DEC`), índice negativo o fuera de rango, tamaño de arreglo calculado menor o igual que 0, `**` entre `ENT` con exponente negativo, raíz con índice 0 o de base negativa con índice par, y un valor leído con `OBT` que no corresponde al tipo de la variable (no se reintenta). El desbordamiento de `ENT` queda sin definir.

Valores asumidos para la ejecución, pendientes de confirmar con el equipo: `IMP` imprime con salto de línea al final; `BOO` se imprime como `VERDADERO`/`FALSO` y `DEC` siempre con punto decimal (`3.0`); concatenar con `CAD` convierte el otro operando a texto con esos formatos; escapes de cadena y carácter `\n`, `\t`, `\"`, `\'` y `\\`; `OBT` lee según el tipo (`CAD` toma la línea completa, los demás una palabra); la división entera trunca hacia cero y `%` toma el signo del dividendo.

## Programas de prueba

`pruebas\` tiene 13 programas, sin aserciones automáticas: se ejecutan con el comando de más abajo y se compara la cantidad de errores.

| Programa | Errores esperados | Qué cubre |
|---|---|---|
| `prueba_revision1` | 0 | Variables, aritmética, una función con retorno y `IMP`. |
| `prueba_revision2` | 0 | `SI`/`SINO` y `MIENTRAS`. |
| `prueba_revision3` | 0 | Arreglo global, `REPETIR` y una función con valor. |
| `prueba_valida` | 0 | Todos los componentes del lenguaje. |
| `prueba_codigo_intermedio` | 0 | Un grupo por función de todo lo que traduce el generador (expresiones, llamadas, arreglos, control de flujo, `EVALUAR`/`TERMINAR`, `AND`/`OR`). |
| `prueba_errores_lexicos` | 4 | Errores léxicos. |
| `prueba_errores_sintacticos` | 22 | Un mensaje propio por cada tipo de error sintáctico. |
| `prueba_recuperacion` | 24 | Modo pánico: cada bloque tiene un error real y el código válido que lo rodea no genera ruido. |
| `prueba_estructura` | 13 | Errores por no conocer las reglas propias de JER (comparación, asignación, reservadas). |
| `prueba_hacer` | 6 | `HACER...MIENTRAS`, válidos y errores. |
| `prueba_repetir` | 9 | `REPETIR`, válidos y errores. |
| `prueba_semantica_invalida` | 45 | Errores semánticos, uno por bloque, incluidos los errores de ejecución evidentes. |
| `prueba_decisiones_previas` | 8 | Un error por cada decisión semántica (globales, inicializadores, `principal`...). |

---

## Compilación y Ejecución

**Requisito:** JDK 22 o superior. No hace falta configurar la codificación: desde JDK 18 el predeterminado es UTF-8 (por eso `javac` no necesita `-encoding`), y el compilador adapta solo su salida a la consola, así que tampoco hay que ejecutar `chcp 65001` ni pasar `-Dfile.encoding`.

**Todo se ejecuta parado en la raíz del proyecto (`JERCompiler\`)** — nunca dentro de `analizador\` ni `build\`. La carpeta de salida (`build\`) y la carpeta `salida\` están resueltas relativas a ese directorio, así que correr algo desde otro lado produce carpetas duplicadas o rutas rotas.

### Ejecutar el compilador sobre un archivo

Este es el único comando de ejecución que existe, y es siempre el mismo — no importa si acabas de clonar el repo o si acabas de recompilar algo:

```powershell
java -cp build JERCompiler pruebas\prueba_valida.txt
```

Imprime los errores léxicos/sintácticos/semánticos encontrados (si los hay) y actualiza `salida\tabla_tokens.txt` (la carpeta `salida\` se crea sola y reúne los resultados de cada compilación; se sobrescriben en cada ejecución). Si el archivo compiló con **0 errores**, además:
* imprime en consola y guarda en `salida\tabla_tipos.txt` la tabla de símbolos (nombre, categoría, tipo, aridad, línea, parámetros) que armó el analizador semántico — con errores no se publica, porque el análisis pudo haberse cortado a medias (declaraciones nunca alcanzadas) y mostrarla daría una idea equivocada de lo que el programa realmente declara;
* imprime en consola y guarda en `salida\cuadruplos.txt` la tabla de cuádruplos, y guarda en `salida\cod_tres_dir.txt` el código de tres direcciones (ver "Generación de código intermedio").

`pruebas\prueba_semantica_invalida.txt` (errores documentados, uno por bloque) sirve como referencia rápida de qué reglas semánticas aplica el analizador.

El repositorio ya trae las clases compiladas en `build\`, así que **si no vas a tocar el código, esto es todo lo que necesitas** — no hace falta nada de lo que sigue abajo.

### Si modificaste código, recompila antes de ejecutar

Los comandos de abajo usan `jjtree ...`/`javacc ...` directo, como si fueran programas instalados — pero JavaCC/JJTree en realidad se distribuyen como un solo `.jar` (`javacc-7.0.13.jar`), sin ningún `jjtree.exe`/`javacc.exe` de por medio. Windows no sabe qué hacer con `jjtree` a secas a menos que exista, en alguna carpeta de tu PATH, un archivo `jjtree.bat` que internamente llame al `.jar` correcto. Ese archivo es un wrapper de una sola línea:

```bat
@echo off
java -cp C:\javacc\javacc-7.0.13.jar jjtree %*
```

(y otro `javacc.bat` igual, cambiando `jjtree` por `javacc`). En esta máquina esos dos `.bat` ya existen en `C:\javacc`, y esa carpeta está en el PATH — por eso `jjtree ...`/`javacc ...` funcionan directo más abajo.

**Es puramente una comodidad, no un requisito.** En cualquier máquina donde esos `.bat` no existan (o donde JavaCC esté instalado en otra ruta), usa siempre la forma larga en su lugar — funciona igual, sin necesidad de crear nada:

```powershell
java -cp C:\javacc\javacc-7.0.13.jar jjtree analizador\JERCompiler_JJTree.jjt
java -cp C:\javacc\javacc-7.0.13.jar javacc build\JERCompiler_JJTree.jj
```

Si quieres los comandos cortos ahí también, crea los dos `.bat` de arriba (ajustando la ruta al `.jar` si está en otro lado) en cualquier carpeta que ya esté en tu PATH.

Cualquier cambio en el código —la gramática o los `.java` escritos a mano en `analizador\` (`AnalizadorSemantico`, `ManejadorErrores`, `TablaSimbolos`, `TablaCuadruplos` y `GeneradorCodigo`)— se recompila corriendo siempre estos tres pasos completos, en este orden: primero los dos que regeneran el parser y el AST a partir de la gramática (`jjtree`, `javacc`), y al final el que compila todo con `javac`. No hace falta decidir si tu cambio "califica" para saltarte algún paso — es más simple y más seguro repetir el proceso completo que arriesgarte a que un paso salteado deje algo desactualizado:

```powershell
jjtree analizador\JERCompiler_JJTree.jjt
javacc build\JERCompiler_JJTree.jj
javac -d build build\*.java analizador\*.java
```

Los tres pasos están juntos en **`compilar.bat`** (raíz del proyecto; doble clic o desde cualquier carpeta). Usa los `jjtree.bat`/`javacc.bat` del PATH y se detiene en el primer paso que falle.

Después de cualquiera de los dos casos de arriba, vuelve a **"Ejecutar el compilador sobre un archivo"** para probar el resultado.
