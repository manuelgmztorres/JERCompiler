# JERCompiler

JERCompiler es un analizador léxico y sintáctico desarrollado con JavaCC para un lenguaje educativo general en español. Valida la estructura gramatical del programa y genera una tabla de tokens; no realiza análisis semántico ni genera código ejecutable.

## Sintaxis principal

```jer
# Declaraciones globales de constantes y arreglos
CONST DEC PI -> 3.14159;
ENT edades[3] -> [18, 20, 21];
DEC notas[2][2] -> [[9.5, 8.0], [7.0, 10.0]];

FUN promedio(DEC total, ENT cantidad) {
    DEC resultado -> total / cantidad;
    RET resultado;
}

FUN principal() {
    ENT indice -> 0;
    notas[indice][1] -> 10.0;
    SI indice >= 0 {
        IMP "Índice válido";
    } SINO {
        OBT indice;
    }
}
```

### Características del Lenguaje:
* **Tipos de datos:** `ENT` (entero), `DEC` (decimal), `CAD` (cadena), `CAR` (carácter) y `BOO` (booleano).
* **Operadores de asignación:** `->` (asignación directa), `+->` (suma y asigna), `-->` (resta y asigna).
* **Operadores aritméticos:** `+`, `-`, `*`, `/`, `%`, `**` (potencia), `//` (raíz cuadrada), `++` (incremento), `--` (decremento).
* **Comparaciones:** `=` (igualdad), `!=` (diferente), `<`, `<=`, `>`, `>=`.
* **Lógica:** `AND`, `OR`, `NOT`.
* **Control de flujo:** `SI`/`SINO`, `MIENTRAS`, `REPETIR`, `EVALUAR`/`CUANDO`/`PRED`, `TERMINAR`.
* **Funciones y E/S:** `FUN`, `RET`, `IMP`, `OBT`.

---

## Compilación y Ejecución

Desde la raíz del proyecto en PowerShell:

```powershell
# 1. Generar analizador con JavaCC
javacc -OUTPUT_DIRECTORY=build analizador\JERCompiler.jj

# 2. Compilar clases Java
javac -encoding UTF-8 -d build build\*.java analizador\ManejadorErrores.java

# 3. Ejecutar archivo de prueba
java -cp build JERCompiler pruebas\prueba_general_correcta.txt
```

---

## Suite de Pruebas Incluidas

1. **`prueba_general_correcta.txt`**:
   * Valida un programa estándar completo con funciones, control de flujo, arreglos y matrices.
2. **`prueba_valida_completa.txt`**:
   * Prueba exhaustiva de todas las construcciones sintácticas válidas del lenguaje (todas las operaciones matemáticas, bucles `REPETIR`, estructura `EVALUAR` con `CUANDO`/`PRED`, asignaciones compuestas y llamadas anidadas).
3. **`prueba_recuperacion_avanzada.txt`**:
   * Valida la recuperación avanzada: instrucciones consecutivas rotas sin `;` (`ENT -> 2` y `ENT - 3;`), múltiples errores en la misma línea diferenciados por columna (`ENT a -> ; DEC b -> ;`), estructuras `SI` sin llave `{` y funciones con firma rota cuyos errores internos siguen siendo detectados.
4. **`prueba_errores_robustos.txt`**:
   * Batería de errores sintácticos y léxicos encadenados (dimensiones incompletas, cadenas sin cerrar, caracteres inválidos como `$`).
5. **`prueba_contexto_errores.txt`**:
   * Diagnósticos de sentencias fuera de lugar (`RET` a nivel global, `SINO` sin `SI`, `CUANDO` o `PRED` fuera de `EVALUAR`).
6. **`prueba_comentario_sin_cierre.txt`**:
   * Detección de comentarios de bloque sin el cierre `**/`.
7. **`prueba_errores_semanticos_no_detectados.txt`**:
   * Documenta casos con errores semánticos (incompatibilidad de tipos, variables no declaradas, modificación de constantes, etc.) que pasan con 0 errores por ser una fase exclusivamente léxico-sintáctica.

---

## Mecanismo de Recuperación de Errores

El compilador implementa un modo pánico resiliente basado en tres principios:

1. **`try / catch` nativos de JavaCC:** Cada regla gramatical maneja sus excepciones en el punto exacto donde se originan.
2. **Sincronización por `FIRST(Sentencia)` con Avance Garantizado:** Evita bucles infinitos (`zero-advance loops`) y frena el descarte de tokens al toparse con el inicio de una nueva sentencia (`ENT`, `DEC`, `SI`, `IMP`, etc.) o delimitadores (`;`, `}`, `SINO`, `CUANDO`, `PRED`, `EOF`).
3. **Desacoplamiento Jerárquico:** Si una cabecera de función o estructura de control falla, se reporta y el analizador ingresa al cuerpo para marcar los errores de las sentencias internas.
4. **Granularidad por Columna:** Permite reportar múltiples errores reales sobre una misma línea señalando su columna correspondiente.

---

## Salidas y Diagnósticos

* **Consola:** Emite reportes detallados con categoría (`[ERROR SINTACTICO]`, `[ERROR LEXICO]`), número de línea, número de columna y mensaje explicativo.
* **`pruebas/tabla_tokens.txt`:** Se genera automáticamente tras cada ejecución, listando todos los tokens reconocidos con su lexema, tipo, línea y columna.
* **BNF formal:** Disponible en `gramaticas/JERCompiler_BNF.txt`.
