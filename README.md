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
    SI indice >= 0 {
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
* **Control de flujo:** `SI`/`SINO`, `MIENTRAS`, `REPETIR`, `EVALUAR`/`CUANDO`/`PRED`, `TERMINAR`.
* **Funciones y E/S:** `FUN` requiere declarar su tipo de retorno antes del nombre (`ENT`/`DEC`/`CAD`/`CAR`/`BOO`, o `VACIO` si no retorna valor), `RET`, `IMP`, `OBT`.
* **Variables:** `CONST` sigue requiriendo valor inicial; una variable normal puede declararse sin inicializar (`ENT contador;`).

---

## Compilación y Ejecución

Todos los comandos se ejecutan **desde la raíz del proyecto** (no dentro de `build\`), para que la tabla de tokens se guarde en `pruebas\tabla_tokens.txt` y no en una copia separada dentro de `build\`.

### Solo quiero probar archivos (no voy a tocar el código)

El repositorio ya trae las clases compiladas en `build\`. Basta con ejecutar el analizador sobre el archivo que quieras revisar:

```powershell
java -cp build JERCompiler pruebas\prueba_valida.txt
```

Esto imprime los errores léxicos/sintácticos encontrados (si los hay) y actualiza `pruebas\tabla_tokens.txt` con la tabla de tokens del archivo analizado.

### Voy a modificar el código (gramática o manejador de errores)

Requiere JavaCC instalado (`javacc` en el PATH) y el JDK. Tras cada cambio en `analizador\JERCompiler.jj` o `analizador\ManejadorErrores.java`, hay que regenerar y recompilar antes de ejecutar:

```powershell
# 1. Regenerar el analizador a partir de la gramática JavaCC
javacc -OUTPUT_DIRECTORY=build analizador\JERCompiler.jj

# 2. Compilar todas las clases (incluye ManejadorErrores) hacia build\
javac -encoding UTF-8 -d build build\*.java analizador\ManejadorErrores.java

# 3. Ejecutar sobre un archivo de prueba
java -cp build JERCompiler pruebas\prueba_valida.txt
```

El `-d build` en el paso 2 es importante: sin él, `ManejadorErrores.class` se compila junto a su fuente en `analizador\` en vez de `build\`, y el paso 3 no lo encuentra en el classpath.
