import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ejecución, diagnósticos y tabla de tokens del analizador JER. */
public final class ManejadorErrores implements JERCompilerConstants {
  private static final String ARCHIVO_TABLA = "pruebas" + File.separator + "tabla_tokens.txt";
  private static final List<RegistroToken> tabla = new ArrayList<RegistroToken>();
  private static boolean recolectandoTokens = false;
  private static int ultimoErrorLinea = -1;
  private static int ultimoErrorColumna = -1;
  private static String ultimoErrorDetalle = "";
  private ManejadorErrores() { }

  public static void ejecutar(String[] args) {
    InputStream fuente = System.in; String nombre = "<stdin>";
    tabla.clear(); ultimoErrorLinea = -1; ultimoErrorColumna = -1; ultimoErrorDetalle = "";
    JERCompiler.reiniciarContadores();
    if (args.length > 0) {
      nombre = args[0]; File archivo = new File(nombre);
      if (!archivo.isFile()) { System.err.println("[ERROR] El archivo no existe o no es válido: " + nombre); return; }
      try { fuente = new FileInputStream(archivo); }
      catch (FileNotFoundException e) { System.err.println("[ERROR] No se pudo abrir el archivo: " + nombre); return; }
    }
    byte[] contenido;
    try { contenido = fuente.readAllBytes(); }
    catch (IOException e) { System.err.println("[ERROR] No se pudo leer la entrada: " + e.getMessage()); return; }

    validarComentariosBloque(contenido);
    recolectarTokens(contenido);
    System.out.println("JERCompiler -- " + nombre);
    try { new JERCompiler(new ByteArrayInputStream(contenido)).Programa(); }
    catch (ParseException e) { reportarError(e); }
    catch (TokenMgrError e) { JERCompiler.totalErrores++; System.err.println("[ERROR LEXICO] " + e.getMessage()); }
    System.out.println("--------------------------------------------");
    System.out.println("Errores: " + JERCompiler.totalErrores);
    System.out.println(JERCompiler.totalErrores == 0 ? "ANÁLISIS FINALIZADO" : "ANÁLISIS CON ERRORES");
    System.out.println("--------------------------------------------");
    guardarTabla(nombre);
  }
  public static void reportarLexico(int linea, int columna, Object imagen) {
    if (recolectandoTokens) return;
    JERCompiler.totalErrores++;
    System.err.println("[ERROR LEXICO] Línea " + linea + ", columna " + columna + ": carácter no reconocido '" + imagen + "'.");
  }
  public static void reportarError(ParseException error) { reportar(obtenerMensajeError(error)); }
  public static void reportarTokenInesperado(Token token) { reportar(mensaje(token, "token inesperado '" + token.image + "'.")); }
  public static void reportarRetornoFueraFuncion(Token token) { reportar(mensaje(token, "RET sólo puede usarse dentro de una función.")); }
  public static void reportarTokenFueraDeContexto(Token token, String contexto) {
    if (token.kind == SINO) reportar(mensaje(token, "SINO sin un SI previo."));
    else if (token.kind == CUANDO) reportar(mensaje(token, "CUANDO sólo puede usarse dentro de EVALUAR."));
    else if (token.kind == PRED) reportar(mensaje(token, "PRED sólo puede usarse dentro de EVALUAR."));
    else reportar(mensaje(token, "token inesperado '" + token.image + "' en " + contexto + "."));
  }
  public static String obtenerMensajeError(ParseException error) {
    Token token = error.currentToken != null && error.currentToken.next != null ? error.currentToken.next : error.currentToken;
    if (token == null) return "[ERROR SINTACTICO] Error de sintaxis al final del archivo.";

    // === CAPA 1: Errores léxicos críticos ===
    if (token.kind == ERROR_LEXICO) return "[ERROR LEXICO] Línea " + token.beginLine + ", columna " + token.beginColumn + ": carácter no reconocido '" + token.image + "'.";
    if (token.kind == STRING_NO_CERRADA) return mensaje(token, "cadena sin cerrar; falta '\"'.");
    if (token.kind == CARACTER_INVALIDO) return mensaje(token, "literal de carácter inválido.");
    if (token.kind == EOF) return mensaje(token, "fin de archivo inesperado; falta cerrar una instrucción o bloque.");

    // === CAPA 2: Contexto estructural existente ===
    if (token.kind == RET) return mensaje(token, "RET sólo puede usarse dentro de una función.");
    if (token.kind == SINO) return mensaje(token, "SINO sin un SI previo.");
    if (token.kind == CUANDO) return mensaje(token, "CUANDO sólo puede usarse dentro de EVALUAR.");
    if (token.kind == PRED) return mensaje(token, "PRED sólo puede usarse dentro de EVALUAR.");

    // === CAPA 3: Diagnósticos hiperespecíficos por doble factor (anterior + actual) ===
    Token anterior = error.currentToken;
    if (anterior != null) {
      // --- 3a: Instrucciones de E/S y control incompletas ---
      if (anterior.kind == IMP && token.kind == FIN_INSTRUCCION)
        return mensaje(token, "instrucción IMP incompleta; se esperaba una expresión para imprimir.");
      if (anterior.kind == OBT && token.kind == FIN_INSTRUCCION)
        return mensaje(token, "instrucción OBT incompleta; se esperaba el identificador de la variable a leer.");
      if (anterior.kind == RET && token.kind == FIN_INSTRUCCION)
        return mensaje(token, "instrucción RET incompleta; se esperaba una expresión de retorno.");
      if (anterior.kind == TERMINAR && token.kind != FIN_INSTRUCCION && token.kind != EOF)
        return mensaje(token, "la instrucción TERMINAR no recibe argumentos; use únicamente 'TERMINAR;'.");

      // --- 3b: Cabeceras de control de flujo vacías ---
      if (anterior.kind == SI && token.kind == APERTURA_BLOQUE)
        return mensaje(token, "la estructura SI requiere una condición antes del bloque '{'.");
      if (anterior.kind == MIENTRAS && token.kind == APERTURA_BLOQUE)
        return mensaje(token, "la estructura MIENTRAS requiere una condición antes del bloque '{'.");
      if (anterior.kind == REPETIR && (token.kind == APERTURA_BLOQUE || token.kind == FIN_INSTRUCCION))
        return mensaje(token, "la estructura REPETIR requiere el número de repeticiones.");
      if (anterior.kind == EVALUAR && token.kind == APERTURA_BLOQUE)
        return mensaje(token, "la estructura EVALUAR requiere la expresión a evaluar antes de '{'.");

      // --- 3c: Asignaciones incompletas ---
      if (anterior.kind == ASIGNACION && token.kind == FIN_INSTRUCCION)
        return mensaje(token, "asignación incompleta; falta el valor o expresión a asignar después de '->'.");
      if ((anterior.kind == ASIG_INC || anterior.kind == ASIG_DEC) && token.kind == FIN_INSTRUCCION)
        return mensaje(token, "falta el valor a incrementar/decrementar después de '" + anterior.image + "'.");

      // --- 3d: Operadores aritméticos colgados o dobles ---
      if (esOperadorAritmetico(anterior.kind) && token.kind == FIN_INSTRUCCION)
        return mensaje(token, "expresión aritmética incompleta; falta el operando derecho después de '" + anterior.image + "'.");
      if (esOperadorAritmetico(anterior.kind) && esOperadorAritmetico(token.kind))
        return mensaje(token, "operador '" + token.image + "' inesperado; no se permiten operadores aritméticos consecutivos.");

      // --- 3e: Conectores lógicos colgados ---
      if ((anterior.kind == AND || anterior.kind == OR) && (token.kind == FIN_INSTRUCCION || token.kind == APERTURA_BLOQUE))
        return mensaje(token, "condición incompleta; falta la expresión después de '" + anterior.image + "'.");

      // --- 3f: Casos CUANDO/PRED ---
      if (anterior.kind == CUANDO && token.kind == DOS_PUNTOS)
        return mensaje(token, "el caso CUANDO requiere una expresión o valor a comparar antes de ':'.");
    }

    // === CAPA 4: Reglas generales de fallback (lógica previa intacta) ===
    boolean puntoComa = espera(error, FIN_INSTRUCCION);
    boolean asignacion = espera(error, ASIGNACION) || espera(error, ASIG_INC) || espera(error, ASIG_DEC);
    boolean coma = espera(error, SEPARADOR);
    if (puntoComa && esInicioDeSentencia(token.kind)) return mensaje(token, "falta ';' al final de la instrucción anterior.");
    if (asignacion) return mensaje(token, "falta el operador de asignación '->'.");
    if (token.kind == CIERRE_CORCHETE && esperaExpresion(error))
      return mensaje(token, "falta una expresión dentro de la dimensión, el índice o el literal de arreglo.");
    if (token.kind == IDENTIFICADOR && esperaTipoDato(error))
      return mensaje(token, "parámetro sin tipo de dato; se esperaba ENT, DEC, CAD, CAR o BOO.");
    if (espera(error, IDENTIFICADOR)) return mensaje(token, "falta un identificador.");
    if (esperaOperadorRelacional(error)) return mensaje(token, "condición incompleta; falta un operador relacional (=, !=, <, <=, > o >=).");
    if (espera(error, CIERRE_CORCHETE)) return mensaje(token, "falta ']' para cerrar un índice, dimensión o literal de arreglo.");
    if (espera(error, CIERRE_PAREN)) return mensaje(token, "falta ')' para cerrar la expresión o llamada.");
    if (espera(error, CIERRE_BLOQUE)) return mensaje(token, "falta '}' para cerrar el bloque.");
    if (espera(error, APERTURA_BLOQUE)) return mensaje(token, "falta '{' para iniciar el bloque.");
    if (espera(error, DOS_PUNTOS)) return mensaje(token, "falta ':' después de CUANDO o PRED.");
    if (espera(error, CUANDO)) return mensaje(token, "EVALUAR requiere al menos un caso CUANDO.");
    if (coma && token.kind != CIERRE_PAREN && token.kind != CIERRE_CORCHETE)
      return mensaje(token, "falta ',' entre elementos o argumentos.");
    if (puntoComa) return mensaje(token, "falta ';' al final de la instrucción.");
    if (token.kind == SEPARADOR) return mensaje(token, "coma fuera de lugar o elemento faltante.");
    if (token.kind == CIERRE_CORCHETE) return mensaje(token, "']' inesperado.");
    if (token.kind == CIERRE_PAREN) return mensaje(token, "')' inesperado.");
    if (token.kind == CIERRE_BLOQUE) return mensaje(token, "'}' inesperado.");
    return mensaje(token, "token inesperado '" + token.image + "'.");
  }
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
  private static boolean esOperadorAritmetico(int tipo) {
    return tipo == SUMA || tipo == RESTA || tipo == MULT || tipo == DIV || tipo == MODULO || tipo == POTENCIA || tipo == RAIZ;
  }
  private static void validarComentariosBloque(byte[] contenido) {
    String fuente = new String(contenido, StandardCharsets.UTF_8);
    int inicio = fuente.indexOf("/**");
    while (inicio >= 0) {
      int cierre = fuente.indexOf("**/", inicio + 3);
      if (cierre < 0) {
        int linea = 1;
        int ultimaNuevaLinea = -1;
        for (int i = 0; i < inicio; i++) if (fuente.charAt(i) == '\n') { linea++; ultimaNuevaLinea = i; }
        reportar("[ERROR LEXICO] Línea " + linea + ", columna " + (inicio - ultimaNuevaLinea) + ": comentario de bloque sin cerrar; falta '**/'.");
        return;
      }
      inicio = fuente.indexOf("/**", cierre + 3);
    }
  }
  private static boolean esperaOperadorRelacional(ParseException error) {
    return espera(error, IGUAL) || espera(error, DIFERENTE) || espera(error, MAYOR_QUE)
      || espera(error, MAYOR_IGUAL) || espera(error, MENOR_QUE) || espera(error, MENOR_IGUAL);
  }
  public static boolean esInicioDeSentencia(int tipo) {
    return tipo == CONST || tipo == TIPO_ENT || tipo == TIPO_DEC || tipo == TIPO_CAD || tipo == TIPO_CAR || tipo == TIPO_BOO || tipo == SI || tipo == MIENTRAS || tipo == REPETIR || tipo == EVALUAR || tipo == IMP || tipo == OBT || tipo == TERMINAR || tipo == RET || tipo == IDENTIFICADOR;
  }
  private static String mensaje(Token token, String detalle) { return "[ERROR SINTACTICO] Línea " + token.beginLine + ", columna " + token.beginColumn + ": " + detalle; }
  private static void reportar(String mensaje) {
    int inicio = mensaje.indexOf("Línea ");
    int separador = mensaje.indexOf(", columna ");
    int dosPuntos = mensaje.indexOf(": ", separador);
    if (inicio >= 0 && separador > inicio && dosPuntos > separador) {
      try {
        int linea = Integer.parseInt(mensaje.substring(inicio + 6, separador));
        int columna = Integer.parseInt(mensaje.substring(separador + 10, dosPuntos));
        String detalle = mensaje.substring(dosPuntos + 2);
        if (linea == ultimoErrorLinea && columna == ultimoErrorColumna && detalle.equals(ultimoErrorDetalle)) return;
        ultimoErrorLinea = linea; ultimoErrorColumna = columna; ultimoErrorDetalle = detalle;
      } catch (NumberFormatException ignorado) { }
    }
    JERCompiler.totalErrores++;
    System.err.println(mensaje);
  }
  private static void guardarTabla(String nombreArchivo) {
    File salida = new File(ARCHIVO_TABLA), directorio = salida.getParentFile(); if (directorio != null && !directorio.exists()) directorio.mkdirs();
    try (PrintWriter writer = new PrintWriter(new FileWriter(salida))) {
      writer.println("TABLA DE TOKENS - JERCompiler"); writer.println("Archivo: " + nombreArchivo);
      writer.printf("%-5s | %-20s | %-28s | %-6s | %s%n", "No.", "Lexema", "Token", "Línea", "Columna");
      writer.println("------+----------------------+------------------------------+--------+--------");
      int numero = 1;
      for (RegistroToken registro : tabla)
        writer.printf("%-5d | %-20s | %-28s | %-6d | %d%n", numero++, registro.lexema, registro.tipo, registro.linea, registro.columna);
    } catch (IOException e) { System.err.println("[ADVERTENCIA] No se pudo guardar la tabla de símbolos: " + e.getMessage()); }
  }
  private static void recolectarTokens(byte[] contenido) {
    recolectandoTokens = true;
    try {
      JERCompilerTokenManager analizador = new JERCompilerTokenManager(new SimpleCharStream(new ByteArrayInputStream(contenido)));
      Token token;
      do {
        token = analizador.getNextToken();
        if (token.kind != EOF)
          tabla.add(new RegistroToken(token.image, nombreToken(token.kind), token.beginLine, token.beginColumn));
      } while (token.kind != EOF);
    } finally { recolectandoTokens = false; }
  }
  private static String nombreToken(int tipo) {
    String imagen = tokenImage[tipo];
    if (imagen.startsWith("\"") && imagen.endsWith("\"")) return imagen.substring(1, imagen.length() - 1);
    return imagen;
  }
  private static final class RegistroToken {
    final String lexema, tipo;
    final int linea, columna;
    RegistroToken(String lexema, String tipo, int linea, int columna) {
      this.lexema = lexema; this.tipo = tipo; this.linea = linea; this.columna = columna;
    }
  }
}
