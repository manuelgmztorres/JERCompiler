# JERCompiler

JERCompiler es un analizador léxico y sintáctico desarrollado con JavaCC para un lenguaje general en español. Valida la estructura gramatical del programa y genera una tabla de tokens; no realiza análisis semántico ni genera código ejecutable.

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
