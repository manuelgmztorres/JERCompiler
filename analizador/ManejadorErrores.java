import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ejecución, diagnósticos y tabla de tokens del analizador JER. */
public final class ManejadorErrores implements JERCompilerConstants {
  private static final String ARCHIVO_TABLA = "pruebas" + File.separator + "tabla_tokens.txt";

  /** Máximo de errores reportados antes de detener el diagnóstico. */
  private static final int MAX_ERRORES = 100;
  /** Tokens que el parser debe consumir con éxito antes de aceptar otro diagnóstico genérico. */
  private static final int UMBRAL_PANICO = 2;

  private static final List<RegistroToken> tabla = new ArrayList<RegistroToken>();
  private static final List<ErrorJER> errores = new ArrayList<ErrorJER>();
  private static final Set<String> firmas = new HashSet<String>();
  private static final Map<Long, Integer> indicePorPosicion = new HashMap<Long, Integer>();
  private static int ultimoIndiceReportado = -1;
  private static boolean limiteAlcanzado = false;

  /** Palabras reservadas de JER, para detectarlas escritas en minúsculas. */
  private static final Set<String> RESERVADAS = new HashSet<String>(Arrays.asList(
    "ENT", "DEC", "CAD", "CAR", "BOO", "CONST", "SI", "SINO", "MIENTRAS", "REPETIR",
    "EVALUAR", "CUANDO", "PRED", "TERMINAR", "FUN", "RET", "OBT", "IMP",
    "VERDADERO", "FALSO", "AND", "OR", "NOT"));

  private ManejadorErrores() { }

  /**
   * Fuerza la salida en UTF-8. En Windows la consola suele estar en UTF-8 (chcp 65001) mientras
   * que Java asume Cp1252, así que sin esto los acentos y la flecha de las sugerencias salen
   * como caracteres corruptos.
   */
  private static void configurarSalidaUTF8() {
    try {
      System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      // Sin UTF-8 disponible se conserva la salida por defecto.
    }
  }

  public static void ejecutar(String[] args) {
    configurarSalidaUTF8();
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

    // La pasada léxica va primero: construye el índice de tokens que usa el modo pánico.
    recolectarTokens(contenido);
    validarComentariosBloque(contenido);
    System.out.println("JERCompiler -- " + nombre);
    try { new JERCompiler(new ByteArrayInputStream(contenido)).Programa(); }
    catch (ParseException e) { reportarError(e); }
    catch (TokenMgrError e) { reportarErrorLexico(e); }
    volcarErrores();
    JERCompiler.totalErrores = errores.size();
    System.out.println("--------------------------------------------");
    System.out.println("Errores: " + JERCompiler.totalErrores);
    System.out.println(JERCompiler.totalErrores == 0 ? "ANÁLISIS FINALIZADO" : "ANÁLISIS CON ERRORES");
    System.out.println("--------------------------------------------");
    guardarTabla(nombre);
  }

  private static void reiniciar() {
    tabla.clear(); errores.clear(); firmas.clear(); indicePorPosicion.clear();
    ultimoIndiceReportado = -1; limiteAlcanzado = false;
    JERCompiler.reiniciarContadores();
  }

  // ======================= Entradas públicas de reporte =======================

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
    if (linea < 0) { registrar(new ErrorJER("ERROR LEXICO", 1, 1, "error léxico: " + texto, null, true)); return; }
    if (texto.contains("<EOF>")) {
      registrar(new ErrorJER("ERROR LEXICO", linea, columna,
        "fin de archivo inesperado dentro de un comentario de bloque.", "Se esperaba '**/' para cerrarlo.", true));
      return;
    }
    String caracter = entreComillas(texto);
    registrar(new ErrorJER("ERROR LEXICO", linea, columna,
      "carácter no reconocido" + (caracter == null ? "." : " '" + caracter + "'."), null, true));
  }

  public static void reportarRetornoFueraFuncion(Token token) {
    registrar(new ErrorJER("ERROR SINTACTICO", token.beginLine, token.beginColumn,
      "RET sólo puede usarse dentro de una función.", "Se esperaba que RET estuviera dentro de un bloque FUN.", true));
  }

  public static void reportarTokenFueraDeContexto(Token token, String contexto) {
    // Los tokens de error léxico merecen su propio diagnóstico, no un "token inesperado".
    if (token.kind == ERROR_LEXICO) {
      registrar(new ErrorJER("ERROR LEXICO", token.beginLine, token.beginColumn,
        "carácter no reconocido '" + token.image + "'.", null, true));
      return;
    }
    if (token.kind == STRING_NO_CERRADA) { registrar(alta(token, "cadena sin cerrar; falta '\"'.", "Se esperaba '\"' para cerrar la cadena.")); return; }
    if (token.kind == CARACTER_INVALIDO) { registrar(alta(token, "literal de carácter inválido.", "Se esperaba un único carácter entre comillas simples.")); return; }
    if (token.kind == FUN) { registrar(alta(token, "las funciones no pueden anidarse; FUN sólo se declara a nivel global.", "Se esperaba cerrar la función actual antes de declarar otra.")); return; }
    if (token.kind == SINO) { registrar(alta(token, "SINO sin un SI previo.", null)); return; }
    if (token.kind == CUANDO) { registrar(alta(token, "CUANDO sólo puede usarse dentro de EVALUAR.", null)); return; }
    if (token.kind == PRED) { registrar(alta(token, "PRED sólo puede usarse dentro de EVALUAR.", null)); return; }
    ErrorJER especifico = palabraReservadaMalEscrita(indiceDe(token.beginLine, token.beginColumn));
    if (especifico != null) { registrar(especifico); return; }
    registrar(baja(token, "token inesperado '" + token.image + "' en " + contexto + ".", null));
  }

  /** Mensaje formateado de un ParseException. Se conserva por compatibilidad. */
  public static String obtenerMensajeError(ParseException error) {
    ErrorJER diagnostico = diagnosticar(error);
    return diagnostico == null ? "[ERROR SINTACTICO] Error de sintaxis al final del archivo." : diagnostico.formato();
  }

  // ======================= Diagnóstico =======================

  private static ErrorJER diagnosticar(ParseException error) {
    Token token = error.currentToken != null && error.currentToken.next != null ? error.currentToken.next : error.currentToken;
    if (token == null) return new ErrorJER("ERROR SINTACTICO", 1, 1, "error de sintaxis al final del archivo.", null, true);
    Token anterior = error.currentToken;
    int indice = indiceDe(token.beginLine, token.beginColumn);

    // === CAPA 0: Confusiones típicas de quien viene de C/Java ===
    if (anterior != null && anterior.kind == IGUAL && token.kind == IGUAL)
      return alta(token, "en JER la comparación se escribe con un solo '='.", "Se esperaba '=' en lugar de '=='.");
    if (token.kind == IGUAL && (espera(error, ASIGNACION) || espera(error, ASIG_INC) || espera(error, ASIG_DEC)))
      return alta(token, "'=' es el operador de comparación; para asignar se usa '->'.", "Se esperaba '->' para asignar un valor.");

    // === CAPA 1: Errores léxicos críticos ===
    if (token.kind == ERROR_LEXICO)
      return new ErrorJER("ERROR LEXICO", token.beginLine, token.beginColumn,
        "carácter no reconocido '" + token.image + "'.", null, true);
    if (token.kind == STRING_NO_CERRADA) return alta(token, "cadena sin cerrar; falta '\"'.", "Se esperaba '\"' para cerrar la cadena.");
    if (token.kind == CARACTER_INVALIDO) return alta(token, "literal de carácter inválido.", "Se esperaba un único carácter entre comillas simples.");
    if (token.kind == EOF) return alta(token, "fin de archivo inesperado; falta cerrar una instrucción o bloque.", sugerenciaAutomatica(error));

    // === CAPA 2: Contexto estructural ===
    if (token.kind == RET) return alta(token, "RET sólo puede usarse dentro de una función.", null);
    if (token.kind == SINO) return alta(token, "SINO sin un SI previo.", null);
    if (token.kind == CUANDO) return alta(token, "CUANDO sólo puede usarse dentro de EVALUAR.", null);
    if (token.kind == PRED) return alta(token, "PRED sólo puede usarse dentro de EVALUAR.", null);

    // === CAPA 2.5: Palabra reservada mal escrita al inicio de la sentencia ===
    // Va antes de la Capa 3 porque una reservada en minúsculas (p. ej. 'si x > 0') también
    // encaja en reglas genéricas como "falta un operador"; el diagnóstico específico debe ganar.
    ErrorJER reservada = palabraReservadaMalEscrita(indice);
    if (reservada != null) return reservada;

    // === CAPA 3: Diagnósticos por doble factor (token anterior + token actual) ===
    if (anterior != null) {
      // --- 3a: Instrucciones de E/S y control incompletas ---
      if (anterior.kind == IMP && token.kind == FIN_INSTRUCCION)
        return alta(token, "instrucción IMP incompleta; se esperaba una expresión para imprimir.", "Se esperaba un valor, variable o cadena después de IMP.");
      if (anterior.kind == OBT && token.kind == FIN_INSTRUCCION)
        return alta(token, "instrucción OBT incompleta; se esperaba el identificador de la variable a leer.", "Se esperaba un identificador después de OBT.");
      if (anterior.kind == RET && token.kind == FIN_INSTRUCCION)
        return alta(token, "instrucción RET incompleta; se esperaba una expresión de retorno.", "Se esperaba un valor o expresión después de RET.");
      if (anterior.kind == TERMINAR && token.kind != FIN_INSTRUCCION && token.kind != EOF)
        return alta(token, "la instrucción TERMINAR no recibe argumentos; use únicamente 'TERMINAR;'.", "Se esperaba ';'.");

      // --- 3b: Cabeceras de control de flujo vacías ---
      if (anterior.kind == SI && token.kind == APERTURA_BLOQUE)
        return alta(token, "la estructura SI requiere una condición antes del bloque '{'.", "Se esperaba una condición, por ejemplo 'SI x > 0 {'.");
      if (anterior.kind == MIENTRAS && token.kind == APERTURA_BLOQUE)
        return alta(token, "la estructura MIENTRAS requiere una condición antes del bloque '{'.", "Se esperaba una condición, por ejemplo 'MIENTRAS x < 10 {'.");
      if (anterior.kind == REPETIR && (token.kind == APERTURA_BLOQUE || token.kind == FIN_INSTRUCCION))
        return alta(token, "la estructura REPETIR requiere el número de repeticiones.", "Se esperaba una expresión, por ejemplo 'REPETIR 5 {'.");
      if (anterior.kind == EVALUAR && token.kind == APERTURA_BLOQUE)
        return alta(token, "la estructura EVALUAR requiere la expresión a evaluar antes de '{'.", "Se esperaba una expresión después de EVALUAR.");

      // --- 3c: Asignaciones incompletas ---
      if (anterior.kind == ASIGNACION && token.kind == FIN_INSTRUCCION)
        return alta(token, "asignación incompleta; falta el valor o expresión a asignar después de '->'.", "Se esperaba un valor o expresión.");
      if ((anterior.kind == ASIG_INC || anterior.kind == ASIG_DEC) && token.kind == FIN_INSTRUCCION)
        return alta(token, "falta el valor a incrementar/decrementar después de '" + anterior.image + "'.", "Se esperaba un valor o expresión.");

      // --- 3d: Operadores aritméticos colgados o dobles ---
      if (esOperadorAritmetico(anterior.kind) && token.kind == FIN_INSTRUCCION)
        return alta(token, "expresión aritmética incompleta; falta el operando derecho después de '" + anterior.image + "'.", "Se esperaba un operando.");
      if (esOperadorAritmetico(anterior.kind) && esOperadorAritmetico(token.kind))
        return alta(token, "operador '" + token.image + "' inesperado; no se permiten operadores aritméticos consecutivos.", "Se esperaba un operando entre '" + anterior.image + "' y '" + token.image + "'.");

      // --- 3e: Conectores lógicos colgados ---
      if ((anterior.kind == AND || anterior.kind == OR) && (token.kind == FIN_INSTRUCCION || token.kind == APERTURA_BLOQUE))
        return alta(token, "condición incompleta; falta la expresión después de '" + anterior.image + "'.", "Se esperaba una comparación.");

      // --- 3f: Casos CUANDO/PRED ---
      if (anterior.kind == CUANDO && token.kind == DOS_PUNTOS)
        return alta(token, "el caso CUANDO requiere una expresión o valor a comparar antes de ':'.", "Se esperaba un valor después de CUANDO.");

      // --- 3g: OBT usado con algo que no es una variable ---
      if (anterior.kind == OBT && token.kind != IDENTIFICADOR && token.kind != FIN_INSTRUCCION)
        return alta(token, "OBT sólo puede leer datos hacia una variable; no admite valores literales ni expresiones.", "Se esperaba un identificador después de OBT.");

      // --- 3h: CONST sin tipo de dato (mensaje distinto al de un parámetro) ---
      if (anterior.kind == CONST && token.kind == IDENTIFICADOR)
        return alta(token, "CONST requiere un tipo de dato antes del nombre de la constante.", "Se esperaba ENT, DEC, CAD, CAR o BOO.");

      // --- 3i: Parámetro de función sin nombre después del tipo ---
      if (esTipoDato(anterior.kind) && (token.kind == SEPARADOR || token.kind == CIERRE_PAREN))
        return alta(token, "el parámetro requiere un nombre después del tipo '" + anterior.image + "'.", "Se esperaba un identificador.");

      // --- 3j: Coma sobrante al final de una lista (arreglo, argumentos o parámetros) ---
      if (anterior.kind == SEPARADOR && (token.kind == CIERRE_PAREN || token.kind == CIERRE_CORCHETE))
        return alta(token, "sobra la ',' antes de '" + token.image + "'; no se permite una coma al final de una lista.", "Quite la ',' sobrante.");

      // --- 3k: Operadores relacionales colgados o dobles ---
      if (esOperadorRelacional(anterior.kind) && token.kind == FIN_INSTRUCCION)
        return alta(token, "condición incompleta; falta el valor a comparar después de '" + anterior.image + "'.", "Se esperaba un valor o expresión.");
      if (esOperadorRelacional(anterior.kind) && esOperadorRelacional(token.kind))
        return alta(token, "operador '" + token.image + "' inesperado; no se permiten operadores relacionales consecutivos.", "Se esperaba un valor entre '" + anterior.image + "' y '" + token.image + "'.");

      // --- 3l: Dos valores seguidos sin operador entre ellos (falta un operador, o una llamada sin paréntesis) ---
      if (anterior.kind == IDENTIFICADOR && esInicioDeValor(token.kind))
        return alta(token, "falta un operador entre '" + anterior.image + "' y '" + token.image + "'.",
          "Si buscaba llamar a una función, se escribe '" + anterior.image + "(argumentos)'.");
    }

    // === CAPA 4: Reglas generales de fallback ===
    boolean puntoComa = espera(error, FIN_INSTRUCCION);
    boolean asignacion = espera(error, ASIGNACION) || espera(error, ASIG_INC) || espera(error, ASIG_DEC);
    boolean coma = espera(error, SEPARADOR);
    if (puntoComa && esInicioDeSentencia(token.kind)) return alta(token, "falta ';' al final de la instrucción anterior.", "Se esperaba ';'.");
    if (asignacion) return alta(token, "falta el operador de asignación '->'.", "Se esperaba '->'.");
    if (token.kind == CIERRE_CORCHETE && esperaExpresion(error))
      return alta(token, "falta una expresión dentro de la dimensión, el índice o el literal de arreglo.", "Se esperaba un valor o expresión antes de ']'.");
    if (token.kind == IDENTIFICADOR && esperaTipoDato(error))
      return alta(token, "parámetro sin tipo de dato; se esperaba ENT, DEC, CAD, CAR o BOO.", "Se esperaba un tipo antes de '" + token.image + "'.");
    if (espera(error, IDENTIFICADOR)) return alta(token, "falta un identificador.", "Se esperaba un nombre de variable o función.");
    if (esperaOperadorRelacional(error)) return alta(token, "condición incompleta; falta un operador relacional (=, !=, <, <=, > o >=).", "Se esperaba un operador relacional.");
    if (espera(error, CIERRE_CORCHETE)) return alta(token, "falta ']' para cerrar un índice, dimensión o literal de arreglo.", "Se esperaba ']'.");
    if (espera(error, CIERRE_PAREN)) return alta(token, "falta ')' para cerrar la expresión o llamada.", "Se esperaba ')'.");
    if (espera(error, CIERRE_BLOQUE)) return alta(token, "falta '}' para cerrar el bloque.", "Se esperaba '}'.");
    if (espera(error, APERTURA_BLOQUE)) return alta(token, "falta '{' para iniciar el bloque.", "Se esperaba '{'.");
    if (espera(error, DOS_PUNTOS)) return alta(token, "falta ':' después de CUANDO o PRED.", "Se esperaba ':'.");
    if (espera(error, CUANDO)) return alta(token, "EVALUAR requiere al menos un caso CUANDO.", "Se esperaba 'CUANDO'.");
    if (coma && token.kind != CIERRE_PAREN && token.kind != CIERRE_CORCHETE)
      return alta(token, "falta ',' entre elementos o argumentos.", "Se esperaba ','.");
    if (puntoComa) return alta(token, "falta ';' al final de la instrucción.", "Se esperaba ';'.");
    if (token.kind == SEPARADOR) return baja(token, "coma fuera de lugar o elemento faltante.", "Se esperaba un elemento antes de ','.");
    if (token.kind == CIERRE_CORCHETE) return baja(token, "']' inesperado.", null);
    if (token.kind == CIERRE_PAREN) return baja(token, "')' inesperado.", null);
    if (token.kind == CIERRE_BLOQUE) return baja(token, "'}' inesperado.", null);
    return baja(token, "token inesperado '" + token.image + "'.", sugerenciaAutomatica(error));
  }

  /**
   * Detecta una palabra reservada de JER escrita en minúsculas al inicio de la sentencia que
   * contiene al token del error. Reubica el error sobre esa palabra.
   */
  private static ErrorJER palabraReservadaMalEscrita(int indiceError) {
    int inicio = indiceInicioDeSentencia(indiceError);
    if (inicio < 0) return null;
    RegistroToken candidato = tabla.get(inicio);
    if (candidato.kind != IDENTIFICADOR) return null;
    if (inicio + 1 < tabla.size()) {
      int siguiente = tabla.get(inicio + 1).kind;
      // Seguido de un operador de asignación es una variable normal: no opinar.
      if (siguiente == ASIGNACION || siguiente == ASIG_INC || siguiente == ASIG_DEC
          || siguiente == INC || siguiente == DEC_OP) return null;
    }
    String mayusculas = candidato.lexema.toUpperCase();
    if (RESERVADAS.contains(mayusculas))
      return new ErrorJER("ERROR SINTACTICO", candidato.linea, candidato.columna,
        "'" + candidato.lexema + "' no se reconoce; las palabras reservadas de JER se escriben en MAYÚSCULAS.",
        "Se esperaba '" + mayusculas + "'.", true);
    return null;
  }

  /** Índice del primer token de la sentencia que contiene al token dado. */
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

  /** Si sólo se esperaba un token concreto, lo nombra como sugerencia. */
  private static String sugerenciaAutomatica(ParseException error) {
    if (error.expectedTokenSequences == null) return null;
    int unico = -1;
    for (int[] secuencia : error.expectedTokenSequences)
      for (int tipo : secuencia) {
        if (unico == -1) unico = tipo;
        else if (unico != tipo) return null;
      }
    if (unico <= 0) return null;
    String nombre = nombreAmigable(unico);
    return nombre == null ? null : "Se esperaba " + nombre + ".";
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

  // ======================= Registro y supresión (modo pánico) =======================

  private static ErrorJER alta(Token token, String detalle, String sugerencia) {
    return new ErrorJER("ERROR SINTACTICO", token.beginLine, token.beginColumn, detalle, sugerencia, true);
  }
  private static ErrorJER baja(Token token, String detalle, String sugerencia) {
    return new ErrorJER("ERROR SINTACTICO", token.beginLine, token.beginColumn, detalle, sugerencia, false);
  }

  /**
   * Descarta duplicados exactos, errores retrógrados producidos al re-parsear, y diagnósticos
   * genéricos que caen a menos de UMBRAL_PANICO tokens del último error realmente reportado.
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
      errores.add(new ErrorJER("ERROR SINTACTICO", error.linea, error.columna,
        "demasiados errores; se detuvo el reporte.", null, true));
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
      if (error.sugerencia != null) System.out.println("   → " + error.sugerencia);
    }
  }

  // ======================= Utilidades de diagnóstico =======================

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
  public static boolean esInicioDeSentencia(int tipo) {
    return tipo == CONST || tipo == TIPO_ENT || tipo == TIPO_DEC || tipo == TIPO_CAD || tipo == TIPO_CAR || tipo == TIPO_BOO || tipo == SI || tipo == MIENTRAS || tipo == REPETIR || tipo == EVALUAR || tipo == IMP || tipo == OBT || tipo == TERMINAR || tipo == RET || tipo == IDENTIFICADOR;
  }

  private static void validarComentariosBloque(byte[] contenido) {
    String fuente = new String(contenido, StandardCharsets.UTF_8);
    int inicio = fuente.indexOf("/**");
    while (inicio >= 0) {
      int cierre = fuente.indexOf("**/", inicio + 3);
      if (cierre < 0) {
        int linea = 1, ultimaNuevaLinea = -1;
        for (int i = 0; i < inicio; i++) if (fuente.charAt(i) == '\n') { linea++; ultimaNuevaLinea = i; }
        registrar(new ErrorJER("ERROR LEXICO", linea, inicio - ultimaNuevaLinea,
          "comentario de bloque sin cerrar; falta '**/'.", "Se esperaba '**/' para cerrar el comentario.", true));
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

  // ======================= Tabla de tokens =======================

  private static long clave(int linea, int columna) { return linea * 100000L + columna; }

  private static int indiceDe(int linea, int columna) {
    Integer indice = indicePorPosicion.get(Long.valueOf(clave(linea, columna)));
    return indice == null ? -1 : indice.intValue();
  }

  private static void recolectarTokens(byte[] contenido) {
    try {
      JERCompilerTokenManager analizador = new JERCompilerTokenManager(new SimpleCharStream(new ByteArrayInputStream(contenido)));
      Token token;
      do {
        token = analizador.getNextToken();
        if (token.kind != EOF) {
          indicePorPosicion.put(Long.valueOf(clave(token.beginLine, token.beginColumn)), Integer.valueOf(tabla.size()));
          tabla.add(new RegistroToken(token.image, nombreToken(token.kind), token.kind, token.beginLine, token.beginColumn));
        }
      } while (token.kind != EOF);
    } catch (TokenMgrError e) {
      // La tabla queda con los tokens leídos hasta el fallo; el error se reporta igual.
      reportarErrorLexico(e);
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

  private static String nombreToken(int tipo) {
    String imagen = tokenImage[tipo];
    if (imagen.startsWith("\"") && imagen.endsWith("\"")) return imagen.substring(1, imagen.length() - 1);
    return imagen;
  }

  // ======================= Estructuras internas =======================

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
