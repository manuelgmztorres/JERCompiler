import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * Ejecucion, diagnosticos y tabla de tokens del analizador JER.
 *
 * Principio de diseno: preferir siempre un reporte directo con contexto explicito, ya que la
 * gramatica o el chequeo que detecta el problema ya sabe que paso (ver
 * reportarRetornoFueraFuncion(), reportarColaHacerIncompleta(),
 * reportarCabeceraRepetirIncompleta()), en vez de reconstruirlo adivinando desde una
 * excepcion generica. diagnosticar() es el ultimo recurso para cuando no hay un chequeo
 * especifico: toda regla ahi debe corroborar contra expectedTokenSequences (via espera())
 * antes de afirmar que un token esta mal ubicado, nunca disparar solo por identidad de token.
 */
public final class ManejadorErrores implements JERCompilerConstants {
  private static final String ARCHIVO_TABLA = resolverRutaTabla();

  /**
   * Calcula la ruta de tabla_tokens.txt independiente del cwd desde el que se invoque "java".
   * En vez de una ruta relativa "pruebas/..." (que Java resuelve contra el cwd del proceso),
   * ubica el directorio de las clases compiladas ("build") y asume que "pruebas" es su
   * carpeta hermana en la raiz del proyecto. Si no se puede determinar, cae a la ruta relativa.
   */
  private static String resolverRutaTabla() {
    try {
      File origenClases = new File(
          ManejadorErrores.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      File raizProyecto = origenClases.isDirectory() ? origenClases.getParentFile() : null;
      if (raizProyecto != null) {
        return new File(raizProyecto, "pruebas" + File.separator + "tabla_tokens.txt").getPath();
      }
    } catch (Exception ignorada) {
      // Fallback abajo.
    }
    return "pruebas" + File.separator + "tabla_tokens.txt";
  }

  /** Maximo de errores reportados antes de detener el diagnostico. */
  private static final int MAX_ERRORES = 100;
  /** Tokens que el parser debe consumir con exito antes de aceptar otro diagnostico generico. */
  private static final int UMBRAL_PANICO = 2;

  private static final List<RegistroToken> tabla = new ArrayList<RegistroToken>();
  private static final List<ErrorJER> errores = new ArrayList<ErrorJER>();
  private static final Set<String> firmas = new HashSet<String>();
  private static final Map<Long, Integer> indicePorPosicion = new HashMap<Long, Integer>();
  private static int ultimoIndiceReportado = -1;
  private static boolean limiteAlcanzado = false;

  /** Palabras reservadas de JER, para detectarlas escritas en minusculas. */
  private static final Set<String> RESERVADAS = new HashSet<String>(Arrays.asList(
    "ENT", "DEC", "CAD", "CAR", "BOO", "VACIO", "CONST", "SI", "SINO", "MIENTRAS", "REPETIR", "HACER",
    "EVALUAR", "CUANDO", "PRED", "TERMINAR", "FUN", "RET", "OBT", "IMP",
    "VERDADERO", "FALSO", "AND", "OR", "NOT"));

  private ManejadorErrores() { }

  /**
   * Elige la codificación de salida para que los acentos se vean sin tocar la consola. Si la
   * salida es una terminal (cmd, PowerShell), se usa el charset de esa consola (en Windows suele
   * ser la página OEM 850/437, no UTF-8, y forzar UTF-8 ahí mostraría basura salvo con "chcp 65001").
   * Si la salida va redirigida (p. ej. el IDE lee el proceso por una tubería) se usa UTF-8.
   */
  private static void configurarSalida() {
    Charset charset = StandardCharsets.UTF_8;
    Console consola = System.console();
    if (consola != null && consola.isTerminal()) charset = consola.charset();
    System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset));
  }

  public static void ejecutar(String[] args) {
    configurarSalida();
    InputStream fuente = System.in; String nombre = "<stdin>";
    reiniciar();
    if (args.length > 0) {
      nombre = args[0]; File archivo = new File(nombre);
      if (!archivo.isFile()) { System.out.println("[ERROR] El archivo no existe o no es válido: " + nombre); return; }
      try { fuente = new FileInputStream(archivo); }
      catch (FileNotFoundException e) { System.out.println("[ERROR] No se pudo abrir el archivo: " + nombre); return; }
    }
    byte[] contenido;
    try { contenido = fuente.readAllBytes(); }
    catch (IOException e) { System.out.println("[ERROR] No se pudo leer la entrada: " + e.getMessage()); return; }

    // La pasada lexica va primero: construye el indice de tokens que usa el modo panico.
    recolectarTokens(contenido);
    validarComentariosBloque(contenido);
    System.out.println("JERCompiler -- " + nombre);
    AnalizadorSemantico analizador = null;
    try {
      ASTPrograma raiz = new JERCompiler(new ByteArrayInputStream(contenido), "UTF-8").Programa();
      // El modo panico (ultimoIndiceReportado) solo debe suprimir cascadas DENTRO de la fase
      // sintactica que acaba de terminar. La fase semantica es un recorrido nuevo, separado, del
      // AST completo: puede (y suele) reportar errores en tokens anteriores al ultimo error
      // sintactico visto (p. ej. una declaracion global con tipo incompatible, seguida mas abajo
      // de un error de sintaxis) y esos NO son una cascada retrograda que haya que descartar.
      ultimoIndiceReportado = -1;
      analizador = new AnalizadorSemantico();
      analizador.analizar(raiz);
    }
    catch (ParseException e) { reportarError(e); }
    catch (TokenMgrError e) { reportarErrorLexico(e); }
    volcarErrores();
    JERCompiler.totalErrores = errores.size();
    System.out.println("--------------------------------------------");
    System.out.println("Errores: " + JERCompiler.totalErrores);
    System.out.println(JERCompiler.totalErrores == 0 ? "ANÁLISIS FINALIZADO" : "ANÁLISIS CON ERRORES");
    System.out.println("--------------------------------------------");
    guardarTabla(nombre);
    // Solo con 0 errores: una iteracion con errores puede haber dejado la tabla de simbolos a
    // medio llenar (declaraciones nunca alcanzadas tras un error de sintaxis), y publicarla
    // igual daria una falsa sensacion de "esto es lo que declaraste" cuando no lo es.
    if (JERCompiler.totalErrores == 0 && analizador != null) guardarTablaDeTipos(nombre, analizador.obtenerTabla());
  }

  private static void reiniciar() {
    tabla.clear(); errores.clear(); firmas.clear(); indicePorPosicion.clear();
    ultimoIndiceReportado = -1; limiteAlcanzado = false;
    JERCompiler.reiniciarContadores();
  }

  // Entradas publicas de reporte

  public static void reportarError(ParseException error) { registrar(diagnosticar(error)); }

  /** Traduce el error crudo del TokenManager de JavaCC al formato de JER. */
  public static void reportarErrorLexico(TokenMgrError error) {
    String texto = error.getMessage() == null ? "" : error.getMessage();
    int linea = -1, columna = -1;
    int posLinea = texto.indexOf("line "), posColumna = texto.indexOf("column ");
    if (posLinea >= 0 && posColumna > posLinea) {
      try {
        linea = Integer.parseInt(texto.substring(posLinea + 5, texto.indexOf(',', posLinea)).trim());
        columna = Integer.parseInt(texto.substring(posColumna + 7, finDeNumero(texto, posColumna + 7)).trim());
      } catch (RuntimeException ignorado) { linea = -1; columna = -1; }
    }
    if (linea < 0) { registrar(new ErrorJER("ERROR LÉXICO", 1, 1, "error léxico: " + texto, "Revise el texto cerca de ese punto y elimine el carácter no válido.", true)); return; }
    if (texto.contains("<EOF>")) {
      registrar(new ErrorJER("ERROR LÉXICO", linea, columna,
        "fin de archivo inesperado dentro de un comentario de bloque.", "Agregue '**/' para cerrar el comentario.", true));
      return;
    }
    registrar(caracterNoReconocido(linea, columna, entreComillas(texto)));
  }

  /**
   * Error lexico por un caracter que JER no admite. Si es una letra con acento (o una enie),
   * se explica la causa real: los identificadores de JER solo aceptan a-z/A-Z, pero esas letras
   * si pueden ir dentro de cadenas, literales de caracter y comentarios.
   */
  private static ErrorJER caracterNoReconocido(int linea, int columna, String caracter) {
    String detalle = "carácter no reconocido" + (caracter == null ? "." : " '" + caracter + "'.");
    String sugerencia = "Elimine el carácter o escríbalo dentro de una cadena entre comillas dobles.";
    if (caracter != null && caracter.length() == 1 && Character.isLetter(caracter.charAt(0))) {
      sugerencia = "Los identificadores de JER solo admiten letras sin acento (a-z, A-Z), dígitos y '_'; "
        + "las letras con acento solo pueden ir dentro de cadenas, literales de carácter o comentarios.";
    }
    return new ErrorJER("ERROR LÉXICO", linea, columna, detalle, sugerencia, true);
  }

  public static void reportarRetornoFueraFuncion(Token token) {
    registrar(new ErrorJER("ERROR SINTÁCTICO", token.beginLine, token.beginColumn,
      "RET solo puede usarse dentro de una función.", "Mueva el RET al interior de una función (FUN) o elimínelo.", true));
  }

  public static void reportarTokenFueraDeContexto(Token token, String contexto) {
    // Los tokens de error lexico merecen su propio diagnostico, no un "token inesperado".
    if (token.kind == ERROR_LEXICO) {
      registrar(caracterNoReconocido(token.beginLine, token.beginColumn, token.image));
      return;
    }
    if (token.kind == STRING_NO_CERRADA) { registrar(alta(token, "cadena sin cerrar; falta '\"'.", "Agregue '\"' al final de la cadena (en la misma línea).")); return; }
    if (token.kind == CARACTER_INVALIDO) { registrar(alta(token, "literal de carácter inválido.", "Un literal de carácter lleva exactamente un carácter entre comillas simples, por ejemplo 'a'; para texto use comillas dobles.")); return; }
    if (token.kind == FUN) { registrar(alta(token, "las funciones no pueden anidarse; FUN solo se declara a nivel global.", "Cierre con '}' la función anterior antes de declarar otra; lo más probable es que le falte su '}'.")); return; }
    if (token.kind == SINO) { registrar(alta(token, "SINO sin un SI previo.", "SINO debe ir justo después del bloque '}' de un SI; revise que ese SI esté bien cerrado.")); return; }
    if (token.kind == CUANDO) { registrar(alta(token, "CUANDO solo puede usarse dentro de EVALUAR.", "Escriba CUANDO dentro de 'EVALUAR expresión { ... }'.")); return; }
    if (token.kind == PRED) { registrar(alta(token, "PRED solo puede usarse dentro de EVALUAR.", "Escriba PRED al final de un 'EVALUAR expresión { ... }', después de sus CUANDO.")); return; }
    ErrorJER especifico = palabraReservadaMalEscrita(indiceDe(token.beginLine, token.beginColumn));
    if (especifico != null) { registrar(especifico); return; }
    registrar(baja(token, "token inesperado '" + token.image + "' en " + contexto + ".", sugerenciaPorContexto(contexto)));
  }

  private static String sugerenciaPorContexto(String contexto) {
    if ("nivel global".equals(contexto))
      return "En el nivel global solo se permiten declaraciones (ENT, DEC, CAD, CAR, BOO, CONST) y funciones (FUN).";
    return "Revise que la sentencia anterior esté completa (¿falta un ';' o una '}'?).";
  }

  /**
   * Punto de entrada generico para reportes semanticos sin metodo dedicado propio. El reporte
   * siempre debe venir con contexto explicito (linea, columna, detalle concreto), nunca
   * reconstruido adivinando desde una excepcion generica.
   */
  public static void reportarErrorSemantico(int linea, int columna, String detalle, String sugerencia) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", linea, columna, detalle, sugerencia, true));
  }

  /**
   * Reporte centralizado para TablaSimbolos.declarar(): arma el mensaje segun las categorias
   * en conflicto. tokenNuevo es el identificador que se intento declarar; previo es el simbolo
   * ya existente en ese scope que devolvio declarar().
   */
  public static void reportarSimboloDuplicado(Token tokenNuevo, TablaSimbolos.Categoria categoriaNueva, TablaSimbolos.Simbolo previo) {
    String nombre = tokenNuevo.image;
    String comoPrevio = descripcionCategoria(previo.categoria);
    String comoNuevo = descripcionCategoria(categoriaNueva);
    registrar(new ErrorJER("ERROR SEMÁNTICO", tokenNuevo.beginLine, tokenNuevo.beginColumn,
      "'" + nombre + "' ya fue declarado como " + comoPrevio + " en la línea " + previo.declaracion.beginLine
        + "; no puede declararse de nuevo como " + comoNuevo + " en este mismo scope.",
      "Use un nombre distinto o elimine la declaración duplicada.", true));
  }

  public static void reportarVariableNoDeclarada(Token id) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", id.beginLine, id.beginColumn,
      "'" + id.image + "' no está declarado en este scope.",
      "Declárelo antes de usarlo, o revise que el nombre esté bien escrito.", true));
  }

  public static void reportarUsoDeFuncionComoValor(Token id) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", id.beginLine, id.beginColumn,
      "'" + id.image + "' es una función; no puede usarse como un valor sin llamarla.",
      "Llame a la función con paréntesis, por ejemplo '" + id.image + "(argumentos)'; si buscaba un valor, use una variable.", true));
  }

  /** Dos tipos que debian coincidir (cadena aritmetica, elementos de un literal de arreglo) no coinciden. */
  public static void reportarTipoIncompatibleEnOperacion(Token token, TablaSimbolos.TipoDato tipoA, TablaSimbolos.TipoDato tipoB) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "tipos incompatibles: " + tipoA + " y " + tipoB + " en la misma operación.",
      "JER no convierte tipos automáticamente; use valores del mismo tipo.", true));
  }

  /** Un operando de +,-,*,/,%,**,// (o el '-' unario) no es ENT ni DEC. */
  public static void reportarOperandoNoNumerico(Token token, TablaSimbolos.TipoDato tipo) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "el tipo " + tipo + " no admite operadores aritméticos.",
      "Los operadores +, -, *, /, %, **, // solo se aplican a ENT o DEC.", true));
  }

  /** Una dimension de arreglo o un indice de acceso no dio tipo ENT. `contexto` ya viene en minusculas, p. ej. "un índice de arreglo". */
  public static void reportarExpresionDebeSerEntera(Token token, TablaSimbolos.TipoDato tipo, String contexto) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      contexto + " debe ser de tipo ENT (se encontró " + tipo + ").",
      "Use un número entero o una variable ENT en su lugar, por ejemplo 'v[2]'.", true));
  }

  public static void reportarAridadArregloIncorrecta(Token token, String nombre, int usada, int declarada) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "'" + nombre + "' se declaró con " + declarada + " dimensión(es), pero se está accediendo con " + usada + ".",
      "Use exactamente " + declarada + " índice(s): " + nombre + corchetes(declarada) + ".", true));
  }

  /** La condicion de SI/MIENTRAS/REPETIR/HACER/una cadena AND-OR no dio tipo BOO (ver decision-condicion-boo-estricta: sin truthy). */
  public static void reportarCondicionNoBooleana(Token token, TablaSimbolos.TipoDato tipo) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "la condición debe ser de tipo BOO (se encontró " + tipo + ").",
      "JER no trata otros tipos como verdadero/falso; use una comparación o una variable BOO.", true));
  }

  /** <, <=, > o >= se uso entre dos valores del mismo tipo, pero ese tipo no es ENT ni DEC. */
  public static void reportarOperadorOrdenNoNumerico(Token token, TablaSimbolos.TipoDato tipo) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "'" + token.image + "' solo compara ENT o DEC (se encontró " + tipo + ").",
      "Para igualdad entre otros tipos use '=' o '!='.", true));
  }

  public static void reportarTerminarFueraDeContexto(Token token) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "TERMINAR solo puede usarse dentro de un bucle (MIENTRAS/REPETIR/HACER) o de un caso de EVALUAR.",
      "Mueva el TERMINAR dentro de uno de esos bloques o elimínelo.", true));
  }

  /** Se intento llamar (con '(argumentos)') a un simbolo que existe pero no es una funcion. */
  public static void reportarLlamadaANoFuncion(Token id, TablaSimbolos.Categoria categoriaReal) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", id.beginLine, id.beginColumn,
      "'" + id.image + "' es " + descripcionCategoria(categoriaReal) + "; no se puede llamar como función.",
      "Quite los paréntesis para usar el valor de '" + id.image + "'.", true));
  }

  public static void reportarNumeroArgumentosIncorrecto(Token id, String nombre, int dados, int esperados) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", id.beginLine, id.beginColumn,
      "'" + nombre + "' espera " + esperados + " argumento(s), se dieron " + dados + ".",
      (dados < esperados ? "Faltan " + (esperados - dados) : "Sobran " + (dados - esperados))
        + " argumento(s); revise la declaración de '" + nombre + "'.", true));
  }

  public static void reportarAsignacionAConstante(Token id, String nombre) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", id.beginLine, id.beginColumn,
      "no se puede modificar '" + nombre + "': es una constante (CONST).",
      "Si necesita cambiar su valor, declare '" + nombre + "' sin CONST.", true));
  }

  public static void reportarUsoDeFuncionVacioComoValor(Token id) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", id.beginLine, id.beginColumn,
      "'" + id.image + "' es VACIO (no retorna ningún valor); no puede usarse dentro de una expresión.",
      "Llame a '" + id.image + "' como instrucción aparte ('" + id.image + "(...);') o cambie su tipo de retorno.", true));
  }

  public static void reportarRetornoConValorEnFuncionVacio(Token token) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "esta función es VACIO; RET no puede devolver un valor aquí.",
      "Quite la sentencia RET completa; una función VACIO termina sola al llegar al final del bloque.", true));
  }

  /** Una funcion no-VACIO tiene al menos un camino de ejecucion que no pasa por un RET. */
  public static void reportarFuncionSinRetornoGarantizado(Token nombreToken, TablaSimbolos.TipoDato tipoRetorno) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", nombreToken.beginLine, nombreToken.beginColumn,
      "'" + nombreToken.image + "' declara tipo de retorno " + tipoRetorno + ", pero no todos los caminos terminan en un RET.",
      "Asegúrese de que cada rama (SI/SINO, EVALUAR con PRED, etc.) termine en RET, o agregue un RET al final del bloque.", true));
  }

  /** Dos CUANDO de un mismo EVALUAR comparten el mismo valor literal (solo se detecta entre literales, ver valorLiteralDeCaso()). */
  public static void reportarCasoDuplicado(Token token, String valor, Token anterior) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "el valor '" + valor + "' ya aparece en un CUANDO anterior (línea " + anterior.beginLine + ").",
      "Elimine el caso repetido o cambie su valor.", true));
  }

  /** El literal de arreglo de un nivel de anidamiento no tiene el numero de elementos que su dimension declarada exige. */
  public static void reportarTamanioArregloIncorrecto(Token token, String nombre, int nivelDimension, int esperado, int encontrado) {
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn,
      "'" + nombre + "' esperaba " + esperado + " elemento(s) en la dimensión " + nivelDimension + ", pero el literal tiene " + encontrado + ".",
      "Ajuste el literal a " + esperado + " elemento(s) o cambie la dimensión declarada.", true));
  }

  /** La profundidad de anidamiento del literal en este punto no coincide con las dimensiones declaradas del arreglo. */
  public static void reportarFormaArregloIncorrecta(Token token, String nombre, int nivel, boolean seEsperabaSubArreglo) {
    String detalle = seEsperabaSubArreglo
      ? "'" + nombre + "': en la dimensión " + nivel + " se esperaba un sub-arreglo (el arreglo declarado tiene más dimensiones), pero se encontró un valor simple."
      : "'" + nombre + "': en la dimensión " + nivel + " se encontró un sub-arreglo, pero esa es la última dimensión declarada (se esperaba un valor simple).";
    String sugerencia = seEsperabaSubArreglo
      ? "Escriba este elemento como un sub-arreglo, por ejemplo [1, 2]."
      : "Escriba un valor simple en lugar de un sub-arreglo.";
    registrar(new ErrorJER("ERROR SEMÁNTICO", token.beginLine, token.beginColumn, detalle, sugerencia, true));
  }

  /** "[...][...]" con n pares de corchetes, para ejemplos de acceso a arreglos. */
  private static String corchetes(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) sb.append("[...]");
    return sb.toString();
  }

  private static String descripcionCategoria(TablaSimbolos.Categoria categoria) {
    switch (categoria) {
      case VARIABLE: return "variable";
      case CONSTANTE: return "constante";
      case PARAMETRO: return "parámetro";
      case FUNCION: return "función";
      default: return "símbolo";
    }
  }

  public static void reportarBloqueFaltanteHacer(Token hacer, Token siguiente) {
    registrar(faltante(hacer, siguiente,
      "la estructura HACER requiere un bloque '{' inmediatamente después de HACER.",
      "Abra el bloque con '{' justo después de HACER: 'HACER { ... } MIENTRAS (condición);'."));
  }

  /**
   * Diagnostico dedicado para la cola 'MIENTRAS ( condicion ) ;' de HACER...MIENTRAS. Se
   * distingue por etapa, no por token anterior/actual, porque el hueco "MIENTRAS (" es
   * indistinguible por adyacencia de tokens del inicio de un MIENTRAS corriente.
   */
  public static void reportarColaHacerIncompleta(int etapa, ParseException error) {
    Token token = error.currentToken != null && error.currentToken.next != null ? error.currentToken.next : error.currentToken;
    Token anterior = error.currentToken;
    switch (etapa) {
      case 0:
        registrar(faltante(anterior, token, "la estructura HACER...MIENTRAS requiere 'MIENTRAS' seguido de la condición entre paréntesis.", "Agregue 'MIENTRAS (condición);' después del '}' del bloque."));
        break;
      case 1:
        registrar(faltante(anterior, token, "la condición de HACER...MIENTRAS debe ir entre paréntesis.", "Agregue '(' después de MIENTRAS."));
        break;
      case 2:
        registrar(faltante(anterior, token, "falta la condición de HACER...MIENTRAS.", "Escriba una condición entre los paréntesis, por ejemplo 'MIENTRAS (i < 10);'."));
        break;
      case 3:
        registrar(faltante(anterior, token, "falta ')' para cerrar la condición de HACER...MIENTRAS.", agregue(")", anterior)));
        break;
      default:
        registrar(faltante(anterior, token, "falta ';' al final de la instrucción HACER...MIENTRAS.", agregue(";", anterior)));
        break;
    }
  }

  /**
   * Diagnostico dedicado para la cabecera '( declaracion ; condicion ; paso )' de REPETIR.
   * Igual que reportarColaHacerIncompleta(), se distingue por etapa porque la gramatica ya
   * sabe exactamente que pieza de la cabecera fallo.
   */
  public static void reportarCabeceraRepetirIncompleta(int etapa, ParseException error) {
    Token token = error.currentToken != null && error.currentToken.next != null ? error.currentToken.next : error.currentToken;
    Token anterior = error.currentToken;
    switch (etapa) {
      case 0:
        registrar(faltante(anterior, token, "la estructura REPETIR requiere una cabecera '(declaración; condición; paso)'.", "Agregue '(' después de REPETIR, por ejemplo 'REPETIR (ENT i -> 0; i < 10; i++) { ... }'."));
        break;
      case 1:
        registrar(faltante(anterior, token, "falta la declaración de la variable de control de REPETIR.", "Escriba una declaración, por ejemplo 'ENT i -> 0'."));
        break;
      case 2:
        registrar(faltante(anterior, token, "falta ';' después de la declaración de la variable de control de REPETIR.", agregue(";", anterior)));
        break;
      case 3:
        registrar(faltante(anterior, token, "falta la condición de REPETIR.", "Escriba una condición, por ejemplo 'i < 10'."));
        break;
      case 4:
        registrar(faltante(anterior, token, "falta ';' después de la condición de REPETIR.", agregue(";", anterior)));
        break;
      case 5:
        registrar(faltante(anterior, token, "falta el paso de REPETIR.", "Escriba un paso, por ejemplo 'i +-> 1' o 'i++'."));
        break;
      default:
        registrar(faltante(anterior, token, "falta ')' para cerrar la cabecera de REPETIR.", agregue(")", anterior)));
        break;
    }
  }

  /**
   * Cuenta el desbalance de '{'/'}' entre el FUN de una funcion (exclusive) y el siguiente
   * FUN global (o EOF): a diferencia de "cuál '{' le falta su '}'", que es ambiguo, esto si
   * tiene respuesta cierta. Sirve para decidir si vale la pena seguir recuperando sentencia
   * por sentencia, o rendirse con un solo diagnostico.
   */
  public static boolean hayDesbalanceDeLlavesEnFuncion(Token inicioFuncion) {
    int indiceInicio = indiceDe(inicioFuncion.beginLine, inicioFuncion.beginColumn);
    if (indiceInicio < 0) return false;
    return balanceDeLlaves(indiceInicio) != 0;
  }

  /** '{' menos '}' entre el FUN en indiceFun (exclusive) y el siguiente FUN o EOF. */
  private static int balanceDeLlaves(int indiceFun) {
    int balance = 0;
    for (int i = indiceFun + 1; i < tabla.size(); i++) {
      int kind = tabla.get(i).kind;
      if (kind == FUN) break;
      if (kind == APERTURA_BLOQUE) balance++;
      else if (kind == CIERRE_BLOQUE) balance--;
    }
    return balance;
  }

  /**
   * Desbalance de la funcion que contiene a 'referencia'. Si la referencia ya cayo dentro de
   * un FUN anidado (por una '}' faltante antes), se retrocede por los FUN anteriores hasta
   * dar con el primero desbalanceado. 0 si no se puede determinar.
   */
  private static int balanceDeLlavesEnFuncionDe(Token referencia) {
    int indice = indiceDe(referencia.beginLine, referencia.beginColumn);
    if (indice < 0) return 0;
    for (int i = indice; i >= 0; i--) {
      if (tabla.get(i).kind != FUN) continue;
      int balance = balanceDeLlaves(i);
      if (balance != 0) return balance;
    }
    return 0;
  }

  /** '{' menos '}' en todo el archivo; positivo = quedaron bloques sin cerrar. */
  private static int llavesSinCerrarEnArchivo() {
    int balance = 0;
    for (RegistroToken registro : tabla) {
      if (registro.kind == APERTURA_BLOQUE) balance++;
      else if (registro.kind == CIERRE_BLOQUE) balance--;
    }
    return balance;
  }

  public static void reportarDesbalanceDeLlaves(Token referencia) {
    int balance = balanceDeLlavesEnFuncionDe(referencia);
    String detalle, sugerencia;
    if (balance > 0) {
      detalle = "la cantidad de '{' y '}' en esta función no cuadra: faltan " + balance + " '}' de cierre.";
      sugerencia = "Agregue " + balance + " '}' al final de la función; no se puede saber con certeza cuál bloque quedó abierto.";
    } else if (balance < 0) {
      detalle = "la cantidad de '{' y '}' en esta función no cuadra: sobran " + (-balance) + " '}'.";
      sugerencia = "Elimine la(s) '}' sobrante(s) o agregue la '{' que falta.";
    } else {
      detalle = "la cantidad de '{' y '}' en esta función no cuadra; revise que cada bloque tenga su '}' de cierre.";
      sugerencia = "No se puede determinar con certeza cuál bloque específico quedó sin cerrar.";
    }
    registrar(new ErrorJER("ERROR SINTÁCTICO", referencia.beginLine, referencia.beginColumn, detalle, sugerencia, true));
  }

  /** Mensaje formateado de un ParseException. Se conserva por compatibilidad. */
  public static String obtenerMensajeError(ParseException error) {
    ErrorJER diagnostico = diagnosticar(error);
    return diagnostico == null ? "[ERROR SINTÁCTICO] Error de sintaxis al final del archivo." : diagnostico.formato();
  }

  // Diagnostico

  private static ErrorJER diagnosticar(ParseException error) {
    Token token = error.currentToken != null && error.currentToken.next != null ? error.currentToken.next : error.currentToken;
    if (token == null) return new ErrorJER("ERROR SINTÁCTICO", 1, 1, "error de sintaxis al final del archivo.", "Revise que la última instrucción esté completa y que cada '{' tenga su '}'.", true);
    Token anterior = error.currentToken;
    int indice = indiceDe(token.beginLine, token.beginColumn);

    // Capa 0: confusiones tipicas de quien viene de C o Java
    if (anterior != null && anterior.kind == IGUAL && token.kind == IGUAL)
      return alta(token, "en JER la comparación se escribe con un solo '='.", "Use '=' en lugar de '=='.");
    if (token.kind == IGUAL && (espera(error, ASIGNACION) || espera(error, ASIG_INC) || espera(error, ASIG_DEC)))
      return alta(token, "'=' es el operador de comparación; para asignar se usa '->'.", "Use '->' para asignar un valor, por ejemplo 'x -> 5;'.");

    // Capa 1: errores lexicos criticos
    if (token.kind == ERROR_LEXICO)
      return caracterNoReconocido(token.beginLine, token.beginColumn, token.image);
    if (token.kind == STRING_NO_CERRADA) return alta(token, "cadena sin cerrar; falta '\"'.", "Agregue '\"' al final de la cadena (en la misma línea).");
    if (token.kind == CARACTER_INVALIDO) return alta(token, "literal de carácter inválido.", "Un literal de carácter lleva exactamente un carácter entre comillas simples, por ejemplo 'a'; para texto use comillas dobles.");
    if (token.kind == EOF) {
      int sinCerrar = llavesSinCerrarEnArchivo();
      if (sinCerrar > 0)
        return faltante(anterior, token, "fin de archivo inesperado; faltan " + sinCerrar + " '}' para cerrar bloque(s) abierto(s).",
          "Revise que cada '{' tenga su '}' de cierre.");
      return faltante(anterior, token, "fin de archivo inesperado; falta cerrar una instrucción o bloque.", sugerenciaAutomatica(error));
    }

    // La capa 2 (contexto estructural de RET/SINO/CUANDO/PRED) se elimino: esos 4 casos ya
    // se reportan por llamada directa desde la gramatica (reportarRetornoFueraFuncion() /
    // reportarTokenFueraDeContexto(), ver SentenciaInvalida()/ElementoGlobalInvalido()/
    // SentenciaRetorno() en JERCompiler.jj) antes de que puedan generar una ParseException
    // real. Si uno de estos tokens llega aqui, es victima colateral de otra excepcion, por
    // ejemplo un bloque sin cerrar, y la capa 4 (expectedTokenSequences) ya lo diagnostica
    // correctamente sin adivinar por identidad de token.

    // Capa 2.5: palabra reservada mal escrita al inicio de la sentencia. Va antes de la
    // capa 3 porque una reservada en minusculas, por ejemplo 'si x > 0', tambien encaja en
    // reglas genericas como "falta un operador"; el diagnostico especifico debe ganar.
    ErrorJER reservada = palabraReservadaMalEscrita(indice);
    if (reservada != null) return reservada;

    // Capa 3: diagnosticos por doble factor (token anterior + token actual)
    if (anterior != null) {
      // 3a: instrucciones de E/S y control incompletas. IMP/RET usan esInicioDeValor(), no
      // solo FIN_INSTRUCCION, para cubrir tanto "no había nada" (IMP;) como "habia un token
      // que no puede empezar una expresion" (IMP PRED;): antes, ese segundo caso caia hasta
      // la capa 4 generica ("falta un identificador"), que no explica que en realidad
      // faltaba cualquier expresion, no un identificador puntual. OBT no necesita este
      // tratamiento: ya tiene su propia regla amplia en 3g (solo acepta un identificador,
      // nunca un valor o expresion), con su propio mensaje.
      if (anterior.kind == IMP && !esInicioDeValor(token.kind))
        return alta(token, "instrucción IMP incompleta; se esperaba una expresión para imprimir.", "Escriba qué imprimir después de IMP, por ejemplo 'IMP \"Hola\";' o 'IMP x;'.");
      if (anterior.kind == OBT && token.kind == FIN_INSTRUCCION)
        return alta(token, "instrucción OBT incompleta; se esperaba el identificador de la variable a leer.", "Escriba la variable donde guardar el dato, por ejemplo 'OBT x;'.");
      if (anterior.kind == RET && !esInicioDeValor(token.kind))
        return alta(token, "instrucción RET incompleta; se esperaba una expresión de retorno.", "Escriba el valor a devolver después de RET, por ejemplo 'RET resultado;'.");
      if (anterior.kind == TERMINAR && token.kind != FIN_INSTRUCCION && token.kind != EOF)
        return alta(token, "la instrucción TERMINAR no recibe argumentos; use únicamente 'TERMINAR;'.", "Quite lo que sigue a TERMINAR y deje solo 'TERMINAR;'.");

      // 3b: cabeceras de control de flujo vacias
      if (anterior.kind == SI && (token.kind == APERTURA_BLOQUE || esInicioDeSentencia(token.kind)))
        return alta(token, "la estructura SI requiere una condición antes del bloque '{'.", "Agregue una condición entre SI y '{', por ejemplo 'SI x > 0 {'.");
      if (anterior.kind == MIENTRAS && (token.kind == APERTURA_BLOQUE || esInicioDeSentencia(token.kind)))
        return alta(token, "la estructura MIENTRAS requiere una condición antes del bloque '{'.", "Agregue una condición entre MIENTRAS y '{', por ejemplo 'MIENTRAS x < 10 {'.");
      if (anterior.kind == EVALUAR && (token.kind == APERTURA_BLOQUE || esInicioDeSentencia(token.kind)))
        return alta(token, "la estructura EVALUAR requiere la expresión a evaluar antes de '{'.", "Agregue la expresión a evaluar entre EVALUAR y '{', por ejemplo 'EVALUAR x {'.");

      // 3c: asignaciones incompletas
      if (anterior.kind == ASIGNACION && token.kind == FIN_INSTRUCCION)
        return alta(token, "asignación incompleta; falta el valor o expresión a asignar después de '->'.", "Escriba el valor después de '->', por ejemplo 'x -> 5;', o elimine la asignación.");
      if ((anterior.kind == ASIG_INC || anterior.kind == ASIG_DEC) && token.kind == FIN_INSTRUCCION)
        return alta(token, "falta el valor a incrementar/decrementar después de '" + anterior.image + "'.", "Escriba el valor después de '" + anterior.image + "', por ejemplo 'x " + anterior.image + " 1;'.");

      // 3d: operadores aritmeticos colgados o dobles
      if (esOperadorAritmetico(anterior.kind) && token.kind == FIN_INSTRUCCION)
        return alta(token, "expresión aritmética incompleta; falta el operando derecho después de '" + anterior.image + "'.", "Agregue el operando que falta (por ejemplo 'a + b;') o elimine el operador.");
      if (esOperadorAritmetico(anterior.kind) && esOperadorAritmetico(token.kind))
        return alta(token, "operador '" + token.image + "' inesperado; no se permiten operadores aritméticos consecutivos.", "Elimine uno de los dos operadores ('" + anterior.image + "' o '" + token.image + "') o escriba un operando entre ellos.");

      // 3e: conectores logicos colgados
      if ((anterior.kind == AND || anterior.kind == OR) && (token.kind == FIN_INSTRUCCION || token.kind == APERTURA_BLOQUE))
        return alta(token, "condición incompleta; falta la expresión después de '" + anterior.image + "'.", "Agregue la comparación que falta, por ejemplo 'x > 0 AND y < 5'.");

      // 3f: casos CUANDO/PRED
      if (anterior.kind == CUANDO && token.kind == DOS_PUNTOS)
        return alta(token, "el caso CUANDO requiere una expresión o valor a comparar antes de ':'.", "Escriba el valor a comparar entre CUANDO y ':', por ejemplo 'CUANDO 1:'.");

      // 3g: OBT usado con algo que no es una variable
      if (anterior.kind == OBT && token.kind != IDENTIFICADOR && token.kind != FIN_INSTRUCCION)
        return alta(token, "OBT solo puede leer datos hacia una variable; no admite valores literales ni expresiones.", "Escriba el nombre de una variable después de OBT, por ejemplo 'OBT x;'.");

      // 3h: CONST sin tipo de dato (mensaje distinto al de un parametro)
      if (anterior.kind == CONST && token.kind == IDENTIFICADOR)
        return alta(token, "CONST requiere un tipo de dato antes del nombre de la constante.", "Agregue el tipo antes del nombre, por ejemplo 'CONST ENT NOMBRE -> 1;' (ENT, DEC, CAD, CAR o BOO).");

      // 3h.1: FUN sin tipo de retorno (mensaje distinto al de un parametro/CONST)
      if (anterior.kind == FUN && token.kind == IDENTIFICADOR)
        return alta(token, "FUN requiere un tipo de retorno antes del nombre de la función.", "Agregue el tipo de retorno antes del nombre, por ejemplo 'FUN ENT nombre(...)' (o VACIO si no devuelve nada).");

      // 3i: parametro de funcion sin nombre despues del tipo
      if (esTipoDato(anterior.kind) && (token.kind == SEPARADOR || token.kind == CIERRE_PAREN))
        return alta(token, "el parámetro requiere un nombre después del tipo '" + anterior.image + "'.", "Escriba el nombre del parámetro después del tipo, por ejemplo '" + anterior.image + " valor'.");

      // 3j: coma sobrante al final de una lista (arreglo, argumentos o parametros)
      if (anterior.kind == SEPARADOR && (token.kind == CIERRE_PAREN || token.kind == CIERRE_CORCHETE))
        return alta(token, "sobra la ',' antes de '" + token.image + "'; no se permite una coma al final de una lista.", "Quite la ',' sobrante.");

      // 3k: operadores relacionales colgados o dobles
      if (esOperadorRelacional(anterior.kind) && token.kind == FIN_INSTRUCCION)
        return alta(token, "condición incompleta; falta el valor a comparar después de '" + anterior.image + "'.", "Escriba el valor a comparar después de '" + anterior.image + "', por ejemplo 'x " + anterior.image + " 5'.");
      if (esOperadorRelacional(anterior.kind) && esOperadorRelacional(token.kind))
        return alta(token, "operador '" + token.image + "' inesperado; no se permiten operadores relacionales consecutivos.", "Elimine uno de los dos operadores ('" + anterior.image + "' o '" + token.image + "') o escriba un valor entre ellos.");

      // 3l: dos valores seguidos sin operador entre ellos (falta un operador, o una llamada
      // sin parentesis). Unico solape real con la capa 4 (falta ';'): esInicioDeValor y
      // esInicioDeSentencia solo comparten IDENTIFICADOR. Si el identificador que sigue
      // arranca a su vez una AsignacionOLlamada valida (ver JERCompiler_JJTree.jjt:
      // IDENTIFICADOR seguido de '->'/'+->'/'-->'/'('/'++'/'--'/'['), no es un valor suelto:
      // es la sentencia siguiente, a la que solo le falta el ';' anterior. En ese caso se
      // cede el diagnostico a la capa 4.
      if (anterior.kind == IDENTIFICADOR && esInicioDeValor(token.kind)
          && !(token.kind == IDENTIFICADOR && pareceInicioDeSentenciaNueva(indice)))
        return alta(token, "falta un operador entre '" + anterior.image + "' y '" + token.image + "'.",
          "Si buscaba llamar a una función, se escribe '" + anterior.image + "(argumentos)'.");
    }

    // Capa 4: reglas generales de fallback
    boolean puntoComa = espera(error, FIN_INSTRUCCION);
    boolean asignacion = espera(error, ASIGNACION) || espera(error, ASIG_INC) || espera(error, ASIG_DEC);
    boolean coma = espera(error, SEPARADOR);
    if (puntoComa && esInicioDeSentencia(token.kind)) return faltante(anterior, token, "falta ';' al final de la instrucción anterior.", agregue(";", anterior));
    if (asignacion) return faltante(anterior, token, "falta el operador de asignación '->'.", agregue("->", anterior));
    if (token.kind == CIERRE_CORCHETE && esperaExpresion(error))
      return alta(token, "falta una expresión dentro de la dimensión, el índice o el literal de arreglo.", "Escriba un valor dentro de los corchetes, por ejemplo 'v[0]', o elimine los '[]' si sobran.");
    if (token.kind == IDENTIFICADOR && esperaTipoDato(error))
      return alta(token, "parámetro sin tipo de dato; se esperaba ENT, DEC, CAD, CAR o BOO.", "Agregue el tipo (ENT, DEC, CAD, CAR o BOO) antes de '" + token.image + "', por ejemplo 'ENT " + token.image + "'.");
    // El token nunca es EOF aqui, la capa 1 ya lo intercepto arriba, asi que siempre hay una imagen util que mostrar.
    if (espera(error, IDENTIFICADOR)) return faltante(anterior, token, "falta un identificador (se encontró '" + token.image + "').", "Reemplace '" + token.image + "' por un nombre de variable o función, o elimínelo si sobra.");
    if (esperaOperadorRelacional(error)) return faltante(anterior, token, "condición incompleta; falta un operador relacional (=, !=, <, <=, > o >=).", "Agregue un operador relacional entre los dos valores, por ejemplo 'x < 10' o 'x = 5'.");
    if (espera(error, CIERRE_CORCHETE)) return faltante(anterior, token, "falta ']' para cerrar un índice, dimensión o literal de arreglo.", agregue("]", anterior));
    if (espera(error, CIERRE_PAREN)) return faltante(anterior, token, "falta ')' para cerrar la expresión o llamada.", agregue(")", anterior));
    if (espera(error, CIERRE_BLOQUE)) return faltante(anterior, token, "falta '}' para cerrar el bloque.", agregue("}", anterior));
    if (espera(error, APERTURA_BLOQUE)) return faltante(anterior, token, "falta '{' para iniciar el bloque.", agregue("{", anterior));
    if (espera(error, DOS_PUNTOS)) return faltante(anterior, token, "falta ':' después de CUANDO o PRED.", agregue(":", anterior));
    if (espera(error, CUANDO)) return faltante(anterior, token, "EVALUAR requiere al menos un caso CUANDO.", "Agregue al menos un caso dentro del EVALUAR, por ejemplo 'CUANDO 1: ...'.");
    if (coma && token.kind != CIERRE_PAREN && token.kind != CIERRE_CORCHETE)
      return faltante(anterior, token, "falta ',' entre elementos o argumentos.", agregue(",", anterior));
    if (puntoComa) return faltante(anterior, token, "falta ';' al final de la instrucción.", agregue(";", anterior));
    if (token.kind == SEPARADOR) return baja(token, "coma fuera de lugar o elemento faltante.", "Elimine la ',' sobrante o escriba el elemento que falta antes de ella.");
    if (token.kind == CIERRE_CORCHETE) return baja(token, "']' inesperado.", "Revise que cada '[' tenga su ']' y que no sobre ninguno.");
    if (token.kind == CIERRE_PAREN) return baja(token, "')' inesperado.", "Revise que cada '(' tenga su ')' y que no sobre ninguno.");
    if (token.kind == CIERRE_BLOQUE) return baja(token, "'}' inesperado.", "Revise que cada '{' tenga su '}' y que no sobre ninguna.");
    return baja(token, "token inesperado '" + token.image + "'.", sugerenciaAutomatica(error));
  }

  /**
   * Detecta una palabra reservada de JER escrita en minusculas al inicio de la sentencia que
   * contiene al token del error. Reubica el error sobre esa palabra.
   */
  private static ErrorJER palabraReservadaMalEscrita(int indiceError) {
    int inicio = indiceInicioDeSentencia(indiceError);
    if (inicio < 0) return null;
    RegistroToken candidato = tabla.get(inicio);
    if (candidato.kind != IDENTIFICADOR) return null;
    if (inicio + 1 < tabla.size()) {
      int siguiente = tabla.get(inicio + 1).kind;
      // Seguido de un operador de asignacion es una variable normal: no opinar.
      if (siguiente == ASIGNACION || siguiente == ASIG_INC || siguiente == ASIG_DEC
          || siguiente == INC || siguiente == DEC_OP) return null;
    }
    String mayusculas = candidato.lexema.toUpperCase();
    if (RESERVADAS.contains(mayusculas))
      return new ErrorJER("ERROR SINTÁCTICO", candidato.linea, candidato.columna,
        "'" + candidato.lexema + "' no se reconoce; las palabras reservadas de JER se escriben en MAYÚSCULAS.",
        "Escríbala en mayúsculas: '" + mayusculas + "'.", true);
    return null;
  }

  /** Indice del primer token de la sentencia que contiene al token dado. */
  private static int indiceInicioDeSentencia(int indice) {
    if (indice < 0 || indice >= tabla.size()) return -1;
    int i = indice, pasos = 0;
    while (i > 0 && pasos < 24) {
      int anterior = tabla.get(i - 1).kind;
      if (anterior == FIN_INSTRUCCION || anterior == APERTURA_BLOQUE
          || anterior == CIERRE_BLOQUE || anterior == DOS_PUNTOS) break;
      i--; pasos++;
    }
    return i;
  }

  /** Si solo se esperaba un token concreto, lo nombra como sugerencia. */
  private static String sugerenciaAutomatica(ParseException error) {
    if (error.expectedTokenSequences == null) return null;
    // Solo el primer token de cada secuencia esperada: es el que puede escribirse en este punto.
    Set<Integer> tipos = new LinkedHashSet<Integer>();
    for (int[] secuencia : error.expectedTokenSequences)
      if (secuencia.length > 0 && secuencia[0] > 0) tipos.add(Integer.valueOf(secuencia[0]));
    List<String> nombres = new ArrayList<String>();
    for (Integer tipo : tipos) {
      String nombre = nombreAmigable(tipo.intValue());
      if (nombre != null && !nombres.contains(nombre)) nombres.add(nombre);
    }
    if (nombres.isEmpty() || nombres.size() > 5) return null;
    if (nombres.size() == 1) return "Agregue " + nombres.get(0) + " en este punto.";
    StringBuilder sb = new StringBuilder("Agregue alguno de estos en este punto: ");
    for (int i = 0; i < nombres.size(); i++) sb.append(i > 0 ? ", " : "").append(nombres.get(i));
    return sb.append('.').toString();
  }

  /** Sugerencia accionable "Agregue 'x' después de 'y'." para los diagnosticos de algo ausente. */
  private static String agregue(String simbolo, Token anterior) {
    String previo = anterior == null ? null : anterior.image;
    if (previo != null && previo.length() > 24) previo = previo.substring(0, 21) + "...";
    return previo == null ? "Agregue '" + simbolo + "'." : "Agregue '" + simbolo + "' después de '" + previo + "'.";
  }

  /** Nombre legible de un token para los mensajes; null si no vale la pena mencionarlo. */
  private static String nombreAmigable(int tipo) {
    if (tipo == IDENTIFICADOR) return "un identificador";
    if (tipo == NUMERO_ENTERO) return "un número entero";
    if (tipo == NUMERO_DECIMAL) return "un número decimal";
    if (tipo == CADENA) return "una cadena entre comillas dobles";
    if (tipo == CARACTER) return "un carácter entre comillas simples";
    String nombre = nombreToken(tipo);
    // Los tokens sin lexema fijo se muestran como <NOMBRE>: no aportan nada al usuario.
    return nombre.startsWith("<") ? null : "'" + nombre + "'";
  }

  // Registro y supresion (modo panico)

  private static ErrorJER alta(Token token, String detalle, String sugerencia) {
    return new ErrorJER("ERROR SINTÁCTICO", token.beginLine, token.beginColumn, detalle, sugerencia, true);
  }
  private static ErrorJER baja(Token token, String detalle, String sugerencia) {
    return new ErrorJER("ERROR SINTÁCTICO", token.beginLine, token.beginColumn, detalle, sugerencia, false);
  }
  /**
   * Para diagnosticos de algo ausente ("falta ..."): el token donde JavaCC detecto la falla
   * suele caer varias lineas despues del lugar real. Se reporta sobre el ultimo token valido
   * (anterior) en su lugar. Toda regla que agregue un mensaje "falta X" debe usar este
   * metodo, no alta(), para no reintroducir el desfase de linea/columna.
   */
  private static ErrorJER faltante(Token anterior, Token token, String detalle, String sugerencia) {
    Token referencia = anterior != null ? anterior : token;
    return new ErrorJER("ERROR SINTÁCTICO", referencia.beginLine, referencia.beginColumn, detalle, sugerencia, true);
  }

  /**
   * Descarta duplicados exactos, errores retrogrados producidos al re-parsear, y
   * diagnosticos genericos que caen a menos de UMBRAL_PANICO tokens del ultimo error
   * realmente reportado.
   */
  private static void registrar(ErrorJER error) {
    if (error == null || limiteAlcanzado) return;
    String firma = error.tipo + "|" + error.linea + "|" + error.columna + "|" + error.detalle;
    if (firmas.contains(firma)) return;
    int indice = error.indiceToken;
    if (indice >= 0) {
      if (indice < ultimoIndiceReportado) return;
      if (!error.altaConfianza && ultimoIndiceReportado >= 0 && indice - ultimoIndiceReportado < UMBRAL_PANICO) return;
    }
    if (errores.size() >= MAX_ERRORES) {
      limiteAlcanzado = true;
      errores.add(new ErrorJER("ERROR SINTÁCTICO", error.linea, error.columna,
        "demasiados errores; se detuvo el reporte.", "Corrija primero los errores anteriores y vuelva a compilar; muchos de los siguientes suelen ser consecuencia de ellos.", true));
      return;
    }
    firmas.add(firma);
    errores.add(error);
    if (indice >= 0) ultimoIndiceReportado = indice;
  }

  private static void volcarErrores() {
    Collections.sort(errores, new Comparator<ErrorJER>() {
      public int compare(ErrorJER a, ErrorJER b) {
        if (a.linea != b.linea) return a.linea - b.linea;
        return a.columna - b.columna;
      }
    });
    for (ErrorJER error : errores) {
      System.out.println(error.formato());
      if (error.sugerencia != null) System.out.println("   -> " + error.sugerencia);
    }
  }

  // Utilidades de diagnostico

  private static boolean espera(ParseException error, int esperado) {
    if (error.expectedTokenSequences == null) return false;
    for (int[] secuencia : error.expectedTokenSequences) for (int token : secuencia) if (token == esperado) return true;
    return false;
  }
  private static boolean esperaExpresion(ParseException error) {
    return espera(error, IDENTIFICADOR) || espera(error, NUMERO_ENTERO) || espera(error, NUMERO_DECIMAL)
      || espera(error, CADENA) || espera(error, CARACTER) || espera(error, VERDADERO)
      || espera(error, FALSO) || espera(error, APERTURA_PAREN) || espera(error, APERTURA_CORCHETE);
  }
  private static boolean esperaTipoDato(ParseException error) {
    return espera(error, TIPO_ENT) || espera(error, TIPO_DEC) || espera(error, TIPO_CAD)
      || espera(error, TIPO_CAR) || espera(error, TIPO_BOO);
  }
  private static boolean esperaOperadorRelacional(ParseException error) {
    return espera(error, IGUAL) || espera(error, DIFERENTE) || espera(error, MAYOR_QUE)
      || espera(error, MAYOR_IGUAL) || espera(error, MENOR_QUE) || espera(error, MENOR_IGUAL);
  }
  private static boolean esOperadorAritmetico(int tipo) {
    return tipo == SUMA || tipo == RESTA || tipo == MULT || tipo == DIV || tipo == MODULO || tipo == POTENCIA || tipo == RAIZ;
  }
  private static boolean esOperadorRelacional(int tipo) {
    return tipo == IGUAL || tipo == DIFERENTE || tipo == MAYOR_QUE || tipo == MAYOR_IGUAL || tipo == MENOR_QUE || tipo == MENOR_IGUAL;
  }
  private static boolean esTipoDato(int tipo) {
    return tipo == TIPO_ENT || tipo == TIPO_DEC || tipo == TIPO_CAD || tipo == TIPO_CAR || tipo == TIPO_BOO;
  }
  private static boolean esInicioDeValor(int tipo) {
    return tipo == IDENTIFICADOR || tipo == NUMERO_ENTERO || tipo == NUMERO_DECIMAL || tipo == CADENA
      || tipo == CARACTER || tipo == VERDADERO || tipo == FALSO || tipo == APERTURA_PAREN || tipo == APERTURA_CORCHETE;
  }
  /**
   * true si el token en indiceToken es un IDENTIFICADOR seguido de una continuacion valida
   * de AsignacionOLlamada() ('->', '+->', '-->', '(', '++', '--' o '['), senal de que ese
   * identificador arranca una sentencia nueva completa, no un valor suelto. Ver la capa 3l.
   */
  private static boolean pareceInicioDeSentenciaNueva(int indiceToken) {
    if (indiceToken < 0 || indiceToken + 1 >= tabla.size()) return false;
    int siguiente = tabla.get(indiceToken + 1).kind;
    return siguiente == ASIGNACION || siguiente == ASIG_INC || siguiente == ASIG_DEC
      || siguiente == APERTURA_PAREN || siguiente == INC || siguiente == DEC_OP || siguiente == APERTURA_CORCHETE;
  }
  public static boolean esInicioDeSentencia(int tipo) {
    return tipo == CONST || tipo == TIPO_ENT || tipo == TIPO_DEC || tipo == TIPO_CAD || tipo == TIPO_CAR || tipo == TIPO_BOO || tipo == SI || tipo == MIENTRAS || tipo == REPETIR || tipo == EVALUAR || tipo == HACER || tipo == IMP || tipo == OBT || tipo == TERMINAR || tipo == RET || tipo == IDENTIFICADOR;
  }

  private static void validarComentariosBloque(byte[] contenido) {
    String fuente = new String(contenido, StandardCharsets.UTF_8);
    int inicio = fuente.indexOf("/**");
    while (inicio >= 0) {
      int cierre = fuente.indexOf("**/", inicio + 3);
      if (cierre < 0) {
        int linea = 1, ultimaNuevaLinea = -1;
        for (int i = 0; i < inicio; i++) if (fuente.charAt(i) == '\n') { linea++; ultimaNuevaLinea = i; }
        registrar(new ErrorJER("ERROR LÉXICO", linea, inicio - ultimaNuevaLinea,
          "comentario de bloque sin cerrar; falta '**/'.", "Agregue '**/' al final del comentario.", true));
        return;
      }
      inicio = fuente.indexOf("/**", cierre + 3);
    }
  }

  private static int finDeNumero(String texto, int desde) {
    int i = desde;
    while (i < texto.length() && Character.isDigit(texto.charAt(i))) i++;
    return i;
  }
  private static String entreComillas(String texto) {
    int abre = texto.indexOf('"');
    if (abre < 0) return null;
    int cierra = texto.indexOf('"', abre + 1);
    return cierra > abre ? texto.substring(abre + 1, cierra) : null;
  }

  // Tabla de tokens

  private static long clave(int linea, int columna) { return linea * 100000L + columna; }

  private static int indiceDe(int linea, int columna) {
    Integer indice = indicePorPosicion.get(Long.valueOf(clave(linea, columna)));
    return indice == null ? -1 : indice.intValue();
  }

  private static void recolectarTokens(byte[] contenido) {
    try {
      JERCompilerTokenManager analizador = new JERCompilerTokenManager(new SimpleCharStream(new ByteArrayInputStream(contenido), "UTF-8", 1, 1));
      Token token;
      do {
        token = analizador.getNextToken();
        if (token.kind != EOF) {
          indicePorPosicion.put(Long.valueOf(clave(token.beginLine, token.beginColumn)), Integer.valueOf(tabla.size()));
          tabla.add(new RegistroToken(token.image, nombreToken(token.kind), token.kind, token.beginLine, token.beginColumn));
        }
      } while (token.kind != EOF);
    } catch (TokenMgrError e) {
      // La tabla queda con los tokens leidos hasta el fallo; el error se reporta igual.
      reportarErrorLexico(e);
    } catch (UnsupportedEncodingException e) {
      // UTF-8 siempre esta disponible en la JVM; solo existe porque el constructor lo declara.
    }
  }

  private static void guardarTabla(String nombreArchivo) {
    File salida = new File(ARCHIVO_TABLA), directorio = salida.getParentFile(); if (directorio != null && !directorio.exists()) directorio.mkdirs();
    try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(salida), StandardCharsets.UTF_8))) {
      writer.println("TABLA DE TOKENS - JERCompiler"); writer.println("Archivo: " + nombreArchivo);
      writer.printf("%-5s | %-20s | %-28s | %-6s | %s%n", "No.", "Lexema", "Token", "Línea", "Columna");
      writer.println("------+----------------------+------------------------------+--------+--------");
      int numero = 1;
      for (RegistroToken registro : tabla)
        writer.printf("%-5d | %-20s | %-28s | %-6d | %d%n", numero++, registro.lexema, registro.tipo, registro.linea, registro.columna);
    } catch (IOException e) { System.out.println("[ADVERTENCIA] No se pudo guardar la tabla de tokens: " + e.getMessage()); }
  }

  // Tabla de tipos

  private static final String ARCHIVO_TABLA_TIPOS = ARCHIVO_TABLA.replace("tabla_tokens.txt", "tabla_tipos.txt");

  private static void guardarTablaDeTipos(String nombreArchivo, TablaSimbolos tabla) {
    String texto = formatearTablaDeTipos(nombreArchivo, tabla);
    System.out.println(texto);

    File salida = new File(ARCHIVO_TABLA_TIPOS), directorio = salida.getParentFile(); if (directorio != null && !directorio.exists()) directorio.mkdirs();
    try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(salida), StandardCharsets.UTF_8))) {
      writer.print(texto);
    } catch (IOException e) { System.out.println("[ADVERTENCIA] No se pudo guardar la tabla de tipos: " + e.getMessage()); }
  }

  private static String formatearTablaDeTipos(String nombreArchivo, TablaSimbolos tabla) {
    StringWriter buffer = new StringWriter();
    PrintWriter w = new PrintWriter(buffer);
    w.println("TABLA DE TIPOS - JERCompiler"); w.println("Archivo: " + nombreArchivo);
    w.printf("%-20s | %-10s | %-6s | %-11s | %-16s | %-6s | %-5s | %s%n",
      "Nombre", "Categoría", "Tipo", "Dimensiones", "Ámbito", "Línea", "Usado", "Parámetros");
    w.println("----------------------+------------+--------+-------------+------------------+--------+-------+------------------------");
    for (TablaSimbolos.Simbolo simbolo : tabla.todos()) {
      String parametros = simbolo.tiposParametros == null ? "" : simbolo.tiposParametros.toString();
      w.printf("%-20s | %-10s | %-6s | %-11d | %-16s | %-6d | %-5s | %s%n",
        simbolo.nombre, descripcionCategoria(simbolo.categoria), simbolo.tipo,
        simbolo.aridadArreglo, simbolo.ambito, simbolo.declaracion.beginLine,
        simbolo.usado ? "sí" : "no", parametros);
    }
    w.println("--------------------------------------------");
    return buffer.toString();
  }

  private static String nombreToken(int tipo) {
    String imagen = tokenImage[tipo];
    if (imagen.startsWith("\"") && imagen.endsWith("\"")) return imagen.substring(1, imagen.length() - 1);
    return imagen;
  }

  // Estructuras internas

  private static final class ErrorJER {
    final String tipo, detalle, sugerencia;
    final int linea, columna, indiceToken;
    final boolean altaConfianza;
    ErrorJER(String tipo, int linea, int columna, String detalle, String sugerencia, boolean altaConfianza) {
      this.tipo = tipo; this.linea = linea; this.columna = columna;
      this.detalle = detalle; this.sugerencia = sugerencia; this.altaConfianza = altaConfianza;
      this.indiceToken = indiceDe(linea, columna);
    }
    String formato() { return "[" + tipo + "] Línea " + linea + ", columna " + columna + ": " + detalle; }
  }

  private static final class RegistroToken {
    final String lexema, tipo;
    final int kind, linea, columna;
    RegistroToken(String lexema, String tipo, int kind, int linea, int columna) {
      this.lexema = lexema; this.tipo = tipo; this.kind = kind; this.linea = linea; this.columna = columna;
    }
  }
}