# JERCompiler

JERCompiler es un analizador léxico, sintáctico y semántico desarrollado con JavaCC/JJTree para un lenguaje general en español. Valida la estructura gramatical del programa, genera una tabla de tokens y verifica tipos, ámbitos y demás reglas semánticas sobre el AST resultante; no genera código ejecutable.

> **En desarrollo:** además del compilador, `ide/` tiene un IDE mínimo en Swing para escribir y compilar código JER sin terminal — ver `ide/README.md`. Sigue en progreso, no es el foco de esta guía.

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
* **Operadores aritméticos:** `+`, `-`, `*`, `/`, `%`, `**` (potencia), `//` (raíz cuadrada), `++` (incremento), `--` (decremento).
* **Comparaciones:** `=` (igualdad), `!=` (diferente), `<`, `<=`, `>`, `>=`. Una comparación (o una combinación con `AND`/`OR`/`NOT`) también es un valor válido para inicializar, reasignar o retornar una variable/función `BOO` — no solo `VERDADERO`/`FALSO`.
* **Lógica:** `AND`, `OR`, `NOT`.
* **Control de flujo:** `SI`/`SINO`, `MIENTRAS`, `REPETIR`, `EVALUAR`/`CUANDO`/`PRED`, `TERMINAR`. La condición de `SI`/`MIENTRAS`/`REPETIR`/`HACER...MIENTRAS` también acepta una expresión sola (`SI bandera { ... }`), no solo una comparación explícita.
* **Funciones y E/S:** `FUN` requiere declarar su tipo de retorno antes del nombre (`ENT`/`DEC`/`CAD`/`CAR`/`BOO`, o `VACIO` si no retorna valor), `RET`, `IMP`, `OBT`.
* **Variables:** `CONST` sigue requiriendo valor inicial; una variable normal puede declararse sin inicializar (`ENT contador;`).

## Análisis semántico

Después de parsear, `AnalizadorSemantico` recorre el AST (vía el visitor `JERCompilerVisitor` que genera JJTree) en dos pasadas: la primera registra en una tabla de símbolos las variables, constantes y funciones globales (con su firma completa), para que una función pueda llamar a otra declarada más abajo en el archivo; la segunda entra a cada función y valida su cuerpo. Los errores que encuentra se imprimen como `[ERROR SEMANTICO]`, junto con los léxicos/sintácticos.

Reglas que aplica:
* **Tipos estrictos, sin conversión implícita:** `ENT` y `DEC` nunca se mezclan (ni entre sí ni con ningún otro tipo) en aritmética, comparaciones, asignaciones o retornos — el programador debe declarar el tipo correcto de antemano. La única excepción es `+`, que concatena si cualquiera de los dos lados es `CAD` (el resultado es `CAD`, sea cual sea el otro tipo); `-`, `*`, `/`, `%`, `**`, `//` nunca aceptan `CAD`.
* **Condición estrictamente `BOO`:** la condición de `SI`/`MIENTRAS`/`REPETIR`/`HACER...MIENTRAS` (incluida una expresión sola o negada con `NOT`) debe ser de tipo `BOO`; no hay semántica "truthy" para `ENT`/`DEC`.
* **Ámbitos (scopes):** cada bloque `{ }` (incluidos los de `SI`/`MIENTRAS`/`REPETIR`/`HACER`/`EVALUAR`) abre su propio ámbito anidado dentro de la función que lo contiene. Un parámetro o variable local puede ocultar (*shadowing*) a una variable/constante global del mismo nombre. Funciones y variables/constantes globales comparten un único espacio de nombres (no puede haber una función y una variable global con el mismo nombre).
* **Arreglos:** las dimensiones de la declaración y los índices de acceso deben ser `ENT`; el número de índices usados debe coincidir exactamente con la aridad declarada (los arreglos se modifican elemento por elemento, no se puede reemplazar uno completo con `->`); los elementos de un literal de arreglo deben ser todos del mismo tipo.
* **Funciones:** una llamada debe usar el número y tipo de argumentos exactos de la firma; una función `VACIO` no puede usarse como valor dentro de una expresión; `RET` debe coincidir con el tipo de retorno declarado (y una función `VACIO` no puede usar `RET` con valor).
* **`TERMINAR`** solo es válido dentro de un bucle (`MIENTRAS`/`REPETIR`/`HACER`) o de un caso de `EVALUAR` (`CUANDO`/`PRED`).
* **`CONST` y `OBT`:** no se puede reasignar ni leer (`OBT`) hacia una constante, ni usar un identificador de función como si fuera una variable.
* La función `principal` no es obligatoria: el analizador no exige un punto de entrada con ese nombre.

---

## Compilación y Ejecución

**Todo se ejecuta parado en la raíz del proyecto (`JERCompiler\`)** — nunca dentro de `analizador\` ni `build\`. La carpeta de salida (`build\`) y la ruta de `pruebas\tabla_tokens.txt`/`tabla_tipos.txt` están resueltas relativas a ese directorio, así que correr algo desde otro lado produce carpetas duplicadas o rutas rotas (ver el detalle en `COMPILACION.md`).

### Ejecutar el compilador sobre un archivo

Este es el único comando de ejecución que existe, y es siempre el mismo — no importa si acabas de clonar el repo o si acabas de recompilar algo:

```powershell
java -cp build JERCompiler pruebas\prueba_valida.txt
```

Imprime los errores léxicos/sintácticos/semánticos encontrados (si los hay) y actualiza `pruebas\tabla_tokens.txt`. Si el archivo compiló con **0 errores**, además imprime en consola y guarda en `pruebas\tabla_tipos.txt` la tabla de símbolos (nombre, categoría, tipo, aridad, línea, parámetros) que armó el analizador semántico — con errores no se publica, porque el análisis pudo haberse cortado a medias (declaraciones nunca alcanzadas) y mostrarla daría una idea equivocada de lo que el programa realmente declara. `pruebas\prueba_semantica_valida.txt` (0 errores esperados) y `pruebas\prueba_semantica_invalida.txt` (errores documentados, uno por bloque) sirven como referencia rápida de qué reglas semánticas aplica el analizador.

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

Cualquier cambio en el código —gramática o los tres `.java` de mano— se recompila corriendo siempre estos tres pasos completos, en este orden: primero los dos que regeneran el parser y el AST a partir de la gramática (`jjtree`, `javacc`), y al final el que compila todo con `javac`. No hace falta decidir si tu cambio "califica" para saltarte algún paso — es más simple y más seguro repetir el proceso completo que arriesgarte a que un paso salteado deje algo desactualizado (`COMPILACION.md` documenta qué pasos se pueden omitir en casos específicos, para quien quiera optimizarlo):

```powershell
jjtree analizador\JERCompiler_JJTree.jjt
javacc build\JERCompiler_JJTree.jj
javac -d build build\*.java analizador\*.java
```

Después de cualquiera de los dos casos de arriba, vuelve a **"Ejecutar el compilador sobre un archivo"** para probar el resultado.
