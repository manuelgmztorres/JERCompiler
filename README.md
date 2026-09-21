# JERCompiler

JERCompiler es un analizador léxico y sintáctico desarrollado con JavaCC para un lenguaje general en español. Valida la estructura gramatical del programa y genera una tabla de tokens; no realiza análisis semántico ni genera código ejecutable.

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

---

## Compilación y Ejecución

### Solo quiero probar archivos (no voy a tocar el código)

Los comandos de esta sección se ejecutan **desde la raíz del proyecto** (no dentro de `build\`), para que la tabla de tokens se guarde en `pruebas\tabla_tokens.txt` y no en una copia separada dentro de `build\`.

El repositorio ya trae las clases compiladas en `build\`. Basta con ejecutar el analizador sobre el archivo que quieras revisar:

```powershell
java -cp build JERCompiler pruebas\prueba_valida.txt
```

Esto imprime los errores léxicos/sintácticos encontrados (si los hay) y actualiza `pruebas\tabla_tokens.txt` con la tabla de tokens del archivo analizado.

### Voy a modificar el código (gramática o manejador de errores)

Desde la migración a JJTree, la gramática fuente es `analizador\JERCompiler_JJTree.jjt` (el antiguo `analizador\JERCompiler.jj` ya no se usa). Regenerar requiere tres pasos, no uno solo — ver `COMPILACION.md` para el detalle completo (por qué son tres pasos, notas de PowerShell, cuándo hace falta borrar los `AST*.java` generados). Resumen, parado en `analizador\`:

```powershell
jjtree -OUTPUT_DIRECTORY:..\build JERCompiler_JJTree.jjt
javacc -OUTPUT_DIRECTORY:..\build ..\build\JERCompiler_JJTree.jj
javac -d ..\build ..\build\*.java ManejadorErrores.java
java -cp ..\build JERCompiler ..\pruebas\prueba_valida.txt
```
