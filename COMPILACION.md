# Cómo compilar y ejecutar JERCompiler

Esta guía documenta el proceso de compilación del proyecto tras la migración de
`JERCompiler.jj` (JavaCC puro) a `JERCompiler_JJTree.jjt` (JJTree), y las
correcciones aplicadas para evitar que se creen carpetas duplicadas.

## Estructura del proyecto

```
JERCompiler/
├── analizador/
│   ├── JERCompiler_JJTree.jjt   ← gramática con JJTree (fuente principal)
│   ├── JERCompiler.jj           ← gramática original (JavaCC puro, ya no se usa)
│   ├── ManejadorErrores.java    ← manejo de errores y tabla de tokens
│   ├── TablaSimbolos.java       ← tabla de simbolos (scopes, declarar/resolver)
│   └── AnalizadorSemantico.java ← analizador semantico (visitor de dos pasadas)
├── build/                       ← toda la salida generada y compilada (ver abajo)
├── gramaticas/
└── pruebas/
    ├── prueba_codigo.txt
    ├── prueba_errores_lexicos.txt
    ├── prueba_semantica_valida.txt    ← casos semanticos validos (0 errores)
    ├── prueba_semantica_invalida.txt  ← casos semanticos invalidos, documentados
    ├── ...
    └── tabla_tokens.txt         ← generado automáticamente al ejecutar
```

`build` vive en la **raíz** del proyecto, como carpeta hermana de `analizador`,
`gramaticas` y `pruebas`. Todos los comandos de esta guía se ejecutan desde
dentro de `analizador`, apuntando a `..\build` y `..\pruebas`.

## Requisitos

- JDK instalado (`javac`, `java` en el PATH).
- JavaCC/JJTree: no están instalados como comandos `jjtree`/`javacc` en el
  PATH. Se usa el jar directamente (`C:\JavaCC\javacc-7.0.13.jar` en esta
  máquina): `java -cp C:\JavaCC\javacc-7.0.13.jar jjtree ...` /
  `java -cp C:\JavaCC\javacc-7.0.13.jar javacc ...`.

## Pasos de compilación

Parado en `JERCompiler\analizador`:

### 1. JJTree: traducir `.jjt` a `.jj` anotado

```powershell
java -cp C:\JavaCC\javacc-7.0.13.jar jjtree "-OUTPUT_DIRECTORY:..\build" JERCompiler_JJTree.jjt
```

Genera en `..\build`:
- `JERCompiler_JJTree.jj` (la gramática ya anotada para JavaCC).
- Las clases del árbol: `Node.java`, `SimpleNode.java`, un `AST*.java` por
  cada nodo anotado con `#Nombre` en la gramática, `JERCompilerVisitor.java`,
  `JERCompilerDefaultVisitor.java`, `JERCompilerTreeConstants.java` y
  `JJTJERCompilerState.java`.

> **Importante:** JJTree **no regenera** los archivos `AST*.java` ni
> `SimpleNode.java` si ya existen en el directorio de salida — así puedes
> editarlos a mano sin que una nueva corrida los pise. Si cambias la
> estructura de nodos en el `.jjt` y no ves el cambio reflejado, borra esos
> archivos de `build` antes de volver a correr `jjtree`.

### 2. JavaCC: generar el parser en Java

```powershell
java -cp C:\JavaCC\javacc-7.0.13.jar javacc "-OUTPUT_DIRECTORY:..\build" ..\build\JERCompiler_JJTree.jj
```

Genera en `..\build` el resto de las clases del parser: `JERCompiler.java`,
`JERCompilerTokenManager.java`, `JERCompilerConstants.java`, `Token.java`,
`ParseException.java`, `SimpleCharStream.java`, `TokenMgrError.java`.

### 3. Compilar todo con `javac`

```powershell
javac -d ..\build ..\build\*.java ManejadorErrores.java TablaSimbolos.java AnalizadorSemantico.java
```

Compila todos los `.java` generados junto con `ManejadorErrores.java`,
`TablaSimbolos.java` y `AnalizadorSemantico.java` (los tres viven en
`analizador`, no en `build`), dejando los `.class` en `..\build`.

### 4. Ejecutar

```powershell
java -cp ..\build JERCompiler ..\pruebas\prueba_codigo.txt
```

`ManejadorErrores.ejecutar()` corre el análisis semántico automáticamente
después de parsear (crea un `AnalizadorSemantico` y lo llama con el
`ASTPrograma` que devuelve `JERCompiler.Programa()`) — no hace falta ningún
paso ni flag aparte para activarlo, y corre siempre, incluso si hubo errores
léxicos/sintácticos antes (ver el comentario junto a esa llamada en
`ManejadorErrores.java` para el porqué).

## Notas sobre `-OUTPUT_DIRECTORY`

En **PowerShell**, la sintaxis `-OUTPUT_DIRECTORY=..\build` (con `=`) **no
funciona**: PowerShell separa el argumento en el signo `=` antes de
entregarlo a `jjtree`/`javacc`, que entonces lo interpreta mal. Hay dos
formas correctas de pasarlo:

- Con dos puntos (recomendado, sin necesidad de comillas):
  ```powershell
  jjtree -OUTPUT_DIRECTORY:..\build JERCompiler_JJTree.jjt
  ```
- Con `=` pero entre comillas, para que PowerShell no lo parta:
  ```powershell
  jjtree "-OUTPUT_DIRECTORY=..\build" JERCompiler_JJTree.jjt
  ```

## `tabla_tokens.txt`: por qué antes se duplicaba y cómo se arregló

`ManejadorErrores.java` escribe una tabla de tokens en cada ejecución. La
versión original definía la ruta de salida así:

```java
private static final String ARCHIVO_TABLA = "pruebas" + File.separator + "tabla_tokens.txt";
```

Es una ruta **relativa**, y Java resuelve las rutas relativas contra el
directorio de trabajo (`cwd`) del proceso `java` en el momento de la
ejecución — no contra la ubicación del proyecto ni de las clases. Esto
causaba una carpeta `pruebas` distinta según desde dónde se ejecutara el
programa:

| Ejecutado desde...            | Escribía en...                                  |
|--------------------------------|--------------------------------------------------|
| `JERCompiler\`                 | `JERCompiler\pruebas\tabla_tokens.txt` ✅         |
| `JERCompiler\analizador\`      | `JERCompiler\analizador\pruebas\tabla_tokens.txt` ❌ (carpeta duplicada) |

Se comprobó este comportamiento ejecutando el programa desde ambos
directorios y observando dónde aparecía el archivo en cada caso.

**Corrección aplicada:** `ARCHIVO_TABLA` ahora se calcula en
`resolverRutaTabla()`, ubicando el directorio donde están las clases
compiladas (`build`, vía `getProtectionDomain().getCodeSource()`) y armando
`pruebas\tabla_tokens.txt` como carpeta hermana de `build`, en la raíz del
proyecto. Así el archivo siempre cae en `JERCompiler\pruebas\tabla_tokens.txt`,
sin importar desde qué directorio se invoque `java`. Si el proyecto se
empaqueta algún día en un `.jar`, ese cálculo no aplica y el código hace
*fallback* a la ruta relativa original.

## Resumen de comandos (copiar y pegar)

Desde `JERCompiler\analizador`:

```powershell
java -cp C:\JavaCC\javacc-7.0.13.jar jjtree -OUTPUT_DIRECTORY:..\build JERCompiler_JJTree.jjt
java -cp C:\JavaCC\javacc-7.0.13.jar javacc -OUTPUT_DIRECTORY:..\build ..\build\JERCompiler_JJTree.jj
javac -d ..\build ..\build\*.java ManejadorErrores.java TablaSimbolos.java AnalizadorSemantico.java
java -cp ..\build JERCompiler ..\pruebas\prueba_codigo.txt
```

## Recompilar tras cambios

- **Cambios solo en `ManejadorErrores.java`, `TablaSimbolos.java` o
  `AnalizadorSemantico.java`:** repite únicamente el paso 3 (`javac`).
- **Cambios en la gramática (`.jjt`) que NO tocan la estructura de nodos
  (`#Nombre`):** repite los pasos 1 a 3. Esto incluye agregar una condición
  de creación de nodo distinta de `>N` (p. ej. `#Nombre(condicion)`, como el
  caso de `ExpresionRelacional` con `NOT`) — no agrega ni quita nodos, solo
  cambia cuándo se crea el mismo nodo.
- **Cambios en la gramática que SÍ agregan/quitan/renombran nodos:** borra
  primero los `AST*.java` y `SimpleNode.java` afectados en `build` (JJTree no
  los regenera si ya existen), y luego repite los pasos 1 a 3.
