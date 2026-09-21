# JERCompiler

JERCompiler es un analizador léxico, sintáctico y semántico desarrollado con JavaCC/JJTree para un lenguaje general en español. Valida la estructura gramatical del programa, genera una tabla de tokens y verifica tipos, ámbitos y demás reglas semánticas sobre el AST resultante; no genera código ejecutable.

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

### Solo quiero probar archivos (no voy a tocar el código)

Los comandos de esta sección se ejecutan **desde la raíz del proyecto** (no dentro de `build\`), para que la tabla de tokens se guarde en `pruebas\tabla_tokens.txt` y no en una copia separada dentro de `build\`.

El repositorio ya trae las clases compiladas en `build\`. Basta con ejecutar el analizador sobre el archivo que quieras revisar:

```powershell
java -cp build JERCompiler pruebas\prueba_valida.txt
```

Esto imprime los errores léxicos/sintácticos/semánticos encontrados (si los hay) y actualiza `pruebas\tabla_tokens.txt` con la tabla de tokens del archivo analizado. `pruebas\prueba_semantica_valida.txt` (0 errores esperados) y `pruebas\prueba_semantica_invalida.txt` (errores documentados, uno por bloque) sirven como referencia rápida de qué reglas semánticas aplica el analizador.

### Voy a modificar el código (gramática, manejador de errores o analizador semántico)

Desde la migración a JJTree, la gramática fuente es `analizador\JERCompiler_JJTree.jjt` (el antiguo `analizador\JERCompiler.jj` ya no se usa). Regenerar requiere tres pasos, no uno solo — ver `COMPILACION.md` para el detalle completo (por qué son tres pasos, notas de PowerShell, cuándo hace falta borrar los `AST*.java` generados). Resumen, parado en `analizador\`:

```powershell
jjtree -OUTPUT_DIRECTORY:..\build JERCompiler_JJTree.jjt
javacc -OUTPUT_DIRECTORY:..\build ..\build\JERCompiler_JJTree.jj
javac -d ..\build ..\build\*.java ManejadorErrores.java TablaSimbolos.java AnalizadorSemantico.java
java -cp ..\build JERCompiler ..\pruebas\prueba_valida.txt
```

Si el cambio es solo en `TablaSimbolos.java`, `AnalizadorSemantico.java` o `ManejadorErrores.java` (no en la gramática), alcanza con el paso de `javac` de arriba.
