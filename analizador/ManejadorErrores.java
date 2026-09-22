import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * Ejecucion, diagnosticos y tabla de tokens del analizador JER.
 *
 * Principio de diseno para cualquier reporte de error, sintactico o semantico: preferir
 * siempre un reporte directo con contexto explicito, ya que la gramatica o el chequeo que
 * detecta el problema ya sabe exactamente que paso (ver reportarRetornoFueraFuncion(),
 * reportarColaHacerIncompleta(), reportarCabeceraRepetirIncompleta(), reportarErrorSemantico()),
 * en vez de intentar reconstruirlo adivinando desde una excepcion generica.
 *
 * diagnosticar(), el diagnostico por ParseException, es el ultimo recurso para cuando no hay
 * un chequeo especifico escrito. Toda regla ahi que reclame "el token X esta mal ubicado" debe
 * corroborarlo contra expectedTokenSequences (via espera()) antes de afirmarlo, nunca disparar
 * solo por identidad de token. La Capa 2 original, RET/SINO/CUANDO/PRED por identidad de
 * token, violaba esto y se elimino; ver el comentario en diagnosticar().
 */
public final class ManejadorErrores implements JERCompilerConstants {
  private static final String ARCHIVO_TABLA = resolverRutaTabla();

  /**
   * Calcula la ruta de tabla_tokens.txt de forma independiente del directorio de trabajo
   * (cwd) desde el que se invoque "java". En vez de usar una ruta relativa "pruebas/..."
   * (que Java resuelve contra el cwd del proceso, no contra la ubicacion del proyecto),
   * se ubica el directorio que contiene las clases compiladas (normalmente "build") y se
   * asume que "pruebas" es una carpeta hermana de ese directorio, en la raiz del proyecto.
   * Si por algun motivo no se puede determinar (por ejemplo, empaquetado en un .jar), se
   * hace fallback a la ruta relativa original.
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
   * Fuerza la salida en UTF-8. Los mensajes propios del compilador ya son ASCII puro, pero un
   * identificador o literal con acentos escrito por el usuario puede aparecer citado dentro de
   * un error; sin esto, ese texto saldria corrupto en consolas que no usan UTF-8 por defecto.
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
      if (!archivo.isFile()) { System.out.println("[ERROR] El archivo no existe o no es valido: " + nombre); return; }
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
      ASTPrograma raiz = new JERCompiler(new ByteArrayInputStream(contenido)).Programa();
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
    System.out.println(JERCompiler.totalErrores == 0 ? "ANALISIS FINALIZADO" : "ANALISIS CON ERRORES");
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
    if (linea < 0) { registrar(new ErrorJER("ERROR LEXICO", 1, 1, "error lexico: " + texto, null, true)); return; }
    if (texto.contains("<EOF>")) {
      registrar(new ErrorJER("ERROR LEXICO", linea, columna,
        "fin de archivo inesperado dentro de un comentario de bloque.", "Se esperaba '**/' para cerrarlo.", true));
      return;
    }
    String caracter = entreComillas(texto);
    registrar(new ErrorJER("ERROR LEXICO", linea, columna,
      "caracter no reconocido" + (caracter == null ? "." : " '" + caracter + "'."), null, true));
  }

  public static void reportarRetornoFueraFuncion(Token token) {
    registrar(new ErrorJER("ERROR SINTACTICO", token.beginLine, token.beginColumn,
      "RET solo puede usarse dentro de una funcion.", "Se esperaba que RET estuviera dentro de un bloque FUN.", true));
  }

  public static void reportarTokenFueraDeContexto(Token token, String contexto) {
    // Los tokens de error lexico merecen su propio diagnostico, no un "token inesperado".
    if (token.kind == ERROR_LEXICO) {
      registrar(new ErrorJER("ERROR LEXICO", token.beginLine, token.beginColumn,
        "caracter no reconocido '" + token.image + "'.", null, true));
      return;
    }
    if (token.kind == STRING_NO_CERRADA) { registrar(alta(token, "cadena sin cerrar; falta '\"'.", "Se esperaba '\"' para cerrar la cadena.")); return; }
    if (token.kind == CARACTER_INVALIDO) { registrar(alta(token, "literal de caracter invalido.", "Se esperaba un unico caracter entre comillas simples.")); return; }
    if (token.kind == FUN) { registrar(alta(token, "las funciones no pueden anidarse; FUN solo se declara a nivel global.", "Se esperaba cerrar la funcion actual antes de declarar otra.")); return; }
    if (token.kind == SINO) { registrar(alta(token, "SINO sin un SI previo.", null)); return; }
    if (token.kind == CUANDO) { registrar(alta(token, "CUANDO solo puede usarse dentro de EVALUAR.", null)); return; }
    if (token.kind == PRED) { registrar(alta(token, "PRED solo puede usarse dentro de EVALUAR.", null)); return; }
    ErrorJER especifico = palabraReservadaMalEscrita(indiceDe(token.beginLine, token.beginColumn));
    if (especifico != null) { registrar(especifico); return; }
    registrar(baja(token, "token inesperado '" + token.image + "' en " + contexto + ".", null));
  }

  /**
   * Punto de entrada generico para reportes semanticos que aun no tienen su propio metodo
   * dedicado. Igual que reportarRetornoFueraFuncion() o reportarCabeceraRepetirIncompleta(),
   * el reporte siempre debe venir con contexto explicito (linea y columna reales del
   * nodo o token involucrado, detalle concreto), nunca reconstruido adivinando desde una
   * excepcion generica.
   */
  public static void reportarErrorSemantico(int linea, int columna, String detalle, String sugerencia) {
    registrar(new ErrorJER("ERROR SEMANTICO", linea, columna, detalle, sugerencia, true));
  }

  /**
   * Reporte centralizado para TablaSimbolos.declarar(): arma el mensaje segun las categorias
   * en conflicto (variable, constante, parametro, funcion) en vez de dejar que cada visitor
   * semantico redacte su propio texto. tokenNuevo es el identificador que se intento declarar;
   * previo es el simbolo ya existente en ese mismo scope que devolvio declarar().
   */
  public static void reportarSimboloDuplicado(Token tokenNuevo, TablaSimbolos.Categoria categoriaNueva, TablaSimbolos.Simbolo previo) {
    String nombre = tokenNuevo.image;
    String comoPrevio = descripcionCategoria(previo.categoria);
    String comoNuevo = descripcionCategoria(categoriaNueva);
    registrar(new ErrorJER("ERROR SEMANTICO", tokenNuevo.beginLine, tokenNuevo.beginColumn,
      "'" + nombre + "' ya fue declarado como " + comoPrevio + " en la linea " + previo.declaracion.beginLine
        + "; no puede declararse de nuevo como " + comoNuevo + " en este mismo scope.",
      "Use un nombre distinto o elimine la declaracion duplicada.", true));
  }

  public static void reportarVariableNoDeclarada(Token id) {
    registrar(new ErrorJER("ERROR SEMANTICO", id.beginLine, id.beginColumn,
      "'" + id.image + "' no esta declarado en este scope.",
      "Declarelo antes de usarlo, o revise que el nombre este bien escrito.", true));
  }

  public static void reportarUsoDeFuncionComoValor(Token id) {
    registrar(new ErrorJER("ERROR SEMANTICO", id.beginLine, id.beginColumn,
      "'" + id.image + "' es una funcion; no puede usarse como un valor sin llamarla.",
      "Se esperaba '" + id.image + "(argumentos)'.", true));
  }

  /** Dos tipos que debian coincidir (cadena aritmetica, elementos de un literal de arreglo) no coinciden. */
  public static void reportarTipoIncompatibleEnOperacion(Token token, TablaSimbolos.TipoDato tipoA, TablaSimbolos.TipoDato tipoB) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "tipos incompatibles: " + tipoA + " y " + tipoB + " en la misma operacion.",
      "JER no convierte tipos automaticamente; use valores del mismo tipo.", true));
  }

  /** Un operando de +,-,*,/,%,**,// (o el '-' unario) no es ENT ni DEC. */
  public static void reportarOperandoNoNumerico(Token token, TablaSimbolos.TipoDato tipo) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "el tipo " + tipo + " no admite operadores aritmeticos.",
      "Los operadores +, -, *, /, %, **, // solo se aplican a ENT o DEC.", true));
  }

  /** Una dimension de arreglo o un indice de acceso no dio tipo ENT. `contexto` ya viene en minusculas, p. ej. "un indice de arreglo". */
  public static void reportarExpresionDebeSerEntera(Token token, TablaSimbolos.TipoDato tipo, String contexto) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      contexto + " debe ser de tipo ENT (se encontro " + tipo + ").",
      "Se esperaba un valor entero.", true));
  }

  public static void reportarAridadArregloIncorrecta(Token token, String nombre, int usada, int declarada) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "'" + nombre + "' se declaro con " + declarada + " dimension(es), pero se esta accediendo con " + usada + ".",
      null, true));
  }

  /** La condicion de SI/MIENTRAS/REPETIR/HACER/una cadena AND-OR no dio tipo BOO (ver decision-condicion-boo-estricta: sin truthy). */
  public static void reportarCondicionNoBooleana(Token token, TablaSimbolos.TipoDato tipo) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "la condicion debe ser de tipo BOO (se encontro " + tipo + ").",
      "JER no trata otros tipos como verdadero/falso; use una comparacion o una variable BOO.", true));
  }

  /** <, <=, > o >= se uso entre dos valores del mismo tipo, pero ese tipo no es ENT ni DEC. */
  public static void reportarOperadorOrdenNoNumerico(Token token, TablaSimbolos.TipoDato tipo) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "'" + token.image + "' solo compara ENT o DEC (se encontro " + tipo + ").",
      "Para igualdad entre otros tipos use '=' o '!='.", true));
  }

  public static void reportarTerminarFueraDeContexto(Token token) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "TERMINAR solo puede usarse dentro de un bucle (MIENTRAS/REPETIR/HACER) o de un caso de EVALUAR.",
      null, true));
  }

  /** Se intento llamar (con '(argumentos)') a un simbolo que existe pero no es una funcion. */
  public static void reportarLlamadaANoFuncion(Token id, TablaSimbolos.Categoria categoriaReal) {
    registrar(new ErrorJER("ERROR SEMANTICO", id.beginLine, id.beginColumn,
      "'" + id.image + "' es " + descripcionCategoria(categoriaReal) + "; no se puede llamar como funcion.",
      null, true));
  }

  public static void reportarNumeroArgumentosIncorrecto(Token id, String nombre, int dados, int esperados) {
    registrar(new ErrorJER("ERROR SEMANTICO", id.beginLine, id.beginColumn,
      "'" + nombre + "' espera " + esperados + " argumento(s), se dieron " + dados + ".",
      null, true));
  }

  public static void reportarAsignacionAConstante(Token id, String nombre) {
    registrar(new ErrorJER("ERROR SEMANTICO", id.beginLine, id.beginColumn,
      "no se puede modificar '" + nombre + "': es una constante (CONST).",
      null, true));
  }

  public static void reportarUsoDeFuncionVacioComoValor(Token id) {
    registrar(new ErrorJER("ERROR SEMANTICO", id.beginLine, id.beginColumn,
      "'" + id.image + "' es VACIO (no retorna ningun valor); no puede usarse dentro de una expresion.",
      null, true));
  }

  public static void reportarRetornoConValorEnFuncionVacio(Token token) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "esta funcion es VACIO; RET no puede devolver un valor aqui.",
      "Quite la sentencia RET completa; una funcion VACIO termina sola al llegar al final del bloque.", true));
  }

  /** Una funcion no-VACIO tiene al menos un camino de ejecucion que no pasa por un RET. */
  public static void reportarFuncionSinRetornoGarantizado(Token nombreToken, TablaSimbolos.TipoDato tipoRetorno) {
    registrar(new ErrorJER("ERROR SEMANTICO", nombreToken.beginLine, nombreToken.beginColumn,
      "'" + nombreToken.image + "' declara tipo de retorno " + tipoRetorno + ", pero no todos los caminos terminan en un RET.",
      "Asegurese de que cada rama (SI/SINO, EVALUAR con PRED, etc.) termine en RET, o agregue un RET al final del bloque.", true));
  }

  /** Dos CUANDO de un mismo EVALUAR comparten el mismo valor literal (solo se detecta entre literales, ver valorLiteralDeCaso()). */
  public static void reportarCasoDuplicado(Token token, String valor, Token anterior) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "el valor '" + valor + "' ya aparece en un CUANDO anterior (linea " + anterior.beginLine + ").",
      "Elimine el caso repetido o cambie su valor.", true));
  }

  /** El literal de arreglo de un nivel de anidamiento no tiene el numero de elementos que su dimension declarada exige. */
  public static void reportarTamanioArregloIncorrecto(Token token, String nombre, int nivelDimension, int esperado, int encontrado) {
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn,
      "'" + nombre + "' esperaba " + esperado + " elemento(s) en la dimension " + nivelDimension + ", pero el literal tiene " + encontrado + ".",
      null, true));
  }

  /** La profundidad de anidamiento del literal en este punto no coincide con las dimensiones declaradas del arreglo. */
  public static void reportarFormaArregloIncorrecta(Token token, String nombre, int nivel, boolean seEsperabaSubArreglo) {
    String detalle = seEsperabaSubArreglo
      ? "'" + nombre + "': en la dimension " + nivel + " se esperaba un sub-arreglo (el arreglo declarado tiene mas dimensiones), pero se encontro un valor simple."
      : "'" + nombre + "': en la dimension " + nivel + " se encontro un sub-arreglo, pero esa es la ultima dimension declarada (se esperaba un valor simple).";
    registrar(new ErrorJER("ERROR SEMANTICO", token.beginLine, token.beginColumn, detalle, null, true));
  }

  private static String descripcionCategoria(TablaSimbolos.Categoria categoria) {
    switch (categoria) {
      case VARIABLE: return "variable";
      case CONSTANTE: return "constante";
      case PARAMETRO: return "parametro";
      case FUNCION: return "funcion";
      default: return "simbolo";
    }
  }

  public static void reportarBloqueFaltanteHacer(Token hacer, Token siguiente) {
    registrar(faltante(hacer, siguiente,
      "la estructura HACER requiere un bloque '{' inmediatamente despues de HACER.",
      "Se esperaba '{' despues de HACER."));
  }

  /**
   * Diagnostico dedicado para la cola 'MIENTRAS ( condicion ) ;' de HACER...MIENTRAS.
   * Se distingue por etapa (no por patron de token anterior/actual) porque el hueco
   * "MIENTRAS (" es indistinguible por adyacencia de tokens del inicio de una
   * EstructuraMientras corriente; JERCompiler.jj ya sabe en que pieza fallo.
   */
  public static void reportarColaHacerIncompleta(int etapa, ParseException error) {
    Token token = error.currentToken != null && error.currentToken.next != null ? error.currentToken.next : error.currentToken;
    Token anterior = error.currentToken;
    switch (etapa) {
      case 0:
        registrar(faltante(anterior, token, "la estructura HACER...MIENTRAS requiere 'MIENTRAS' seguido de la condicion entre parentesis.", "Se esperaba 'MIENTRAS (condicion);'."));
        break;
      case 1:
        registrar(faltante(anterior, token, "la condicion de HACER...MIENTRAS debe ir entre parentesis.", "Se esperaba '(' despues de MIENTRAS."));
        break;
      case 2:
        registrar(faltante(anterior, token, "falta la condicion de HACER...MIENTRAS.", "Se esperaba una condicion antes de ')'."));
        break;
      case 3:
        registrar(faltante(anterior, token, "falta ')' para cerrar la condicion de HACER...MIENTRAS.", "Se esperaba ')'."));
        break;
      default:
        registrar(faltante(anterior, token, "falta ';' al final de la instruccion HACER...MIENTRAS.", "Se esperaba ';'."));
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
        registrar(faltante(anterior, token, "la estructura REPETIR requiere una cabecera '(declaracion; condicion; paso)'.", "Se esperaba '(' despues de REPETIR."));
        break;
      case 1:
        registrar(faltante(anterior, token, "falta la declaracion de la variable de control de REPETIR.", "Se esperaba una declaracion, por ejemplo 'ENT i -> 0'."));
        break;
      case 2:
        registrar(faltante(anterior, token, "falta ';' despues de la declaracion de la variable de control de REPETIR.", "Se esperaba ';'."));
        break;
      case 3:
        registrar(faltante(anterior, token, "falta la condicion de REPETIR.", "Se esperaba una condicion, por ejemplo 'i < 10'."));
        break;
      case 4:
        registrar(faltante(anterior, token, "falta ';' despues de la condicion de REPETIR.", "Se esperaba ';'."));
        break;
      case 5:
        registrar(faltante(anterior, token, "falta el paso de REPETIR.", "Se esperaba un incremento, por ejemplo 'i +-> 1' o 'i++'."));
        break;
      default:
        registrar(faltante(anterior, token, "falta ')' para cerrar la cabecera de REPETIR.", "Se esperaba ')'."));
        break;
    }
  }

  /**
   * Cuenta el desbalance de '{'/'}' entre el token dado (el propio FUN de una funcion,
   * exclusive) y el siguiente FUN de nivel global (o EOF). Es una pregunta que si tiene
   * respuesta cierta, a diferencia de "cual '{' especifica le falta su '}'", que es ambiguo
   * cuando el conteo no cuadra.
   *
   * Sirve para decidir si vale la pena seguir intentando recuperar sentencia por sentencia,
   * o si es mas honesto rendirse con un solo diagnostico.
   */
  public static boolean hayDesbalanceDeLlavesEnFuncion(Token inicioFuncion) {
    int indiceInicio = indiceDe(inicioFuncion.beginLine, inicioFuncion.beginColumn);
    if (indiceInicio < 0) return false;
    int balance = 0;
    for (int i = indiceInicio + 1; i < tabla.size(); i++) {
      int kind = tabla.get(i).kind;
      if (kind == FUN) break;
      if (kind == APERTURA_BLOQUE) balance++;
      else if (kind == CIERRE_BLOQUE) balance--;
    }
    return balance != 0;
  }

  public static void reportarDesbalanceDeLlaves(Token referencia) {
    registrar(new ErrorJER("ERROR SINTACTICO", referencia.beginLine, referencia.beginColumn,
      "la cantidad de '{' y '}' en esta funcion no cuadra; revisa que cada bloque tenga su '}' de cierre.",
      "No se puede determinar con certeza cual bloque especifico quedo sin cerrar.", true));
  }

  /** Mensaje formateado de un ParseException. Se conserva por compatibilidad. */
  public static String obtenerMensajeError(ParseException error) {
    ErrorJER diagnostico = diagnosticar(error);
    return diagnostico == null ? "[ERROR SINTACTICO] Error de sintaxis al final del archivo." : diagnostico.formato();
  }

  // Diagnostico

  private static ErrorJER diagnosticar(ParseException error) {
    Token token = error.currentToken != null && error.currentToken.next != null ? error.currentToken.next : error.currentToken;
    if (token == null) return new ErrorJER("ERROR SINTACTICO", 1, 1, "error de sintaxis al final del archivo.", null, true);
    Token anterior = error.currentToken;
    int indice = indiceDe(token.beginLine, token.beginColumn);

    // Capa 0: confusiones tipicas de quien viene de C o Java
    if (anterior != null && anterior.kind == IGUAL && token.kind == IGUAL)
      return alta(token, "en JER la comparacion se escribe con un solo '='.", "Se esperaba '=' en lugar de '=='.");
    if (token.kind == IGUAL && (espera(error, ASIGNACION) || espera(error, ASIG_INC) || espera(error, ASIG_DEC)))
      return alta(token, "'=' es el operador de comparacion; para asignar se usa '->'.", "Se esperaba '->' para asignar un valor.");

    // Capa 1: errores lexicos criticos
    if (token.kind == ERROR_LEXICO)
      return new ErrorJER("ERROR LEXICO", token.beginLine, token.beginColumn,
        "caracter no reconocido '" + token.image + "'.", null, true);
    if (token.kind == STRING_NO_CERRADA) return alta(token, "cadena sin cerrar; falta '\"'.", "Se esperaba '\"' para cerrar la cadena.");
    if (token.kind == CARACTER_INVALIDO) return alta(token, "literal de caracter invalido.", "Se esperaba un unico caracter entre comillas simples.");
    if (token.kind == EOF) return faltante(anterior, token, "fin de archivo inesperado; falta cerrar una instruccion o bloque.", sugerenciaAutomatica(error));

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
      // solo FIN_INSTRUCCION, para cubrir tanto "no habia nada" (IMP;) como "habia un token
      // que no puede empezar una expresion" (IMP PRED;): antes, ese segundo caso caia hasta
      // la capa 4 generica ("falta un identificador"), que no explica que en realidad
      // faltaba cualquier expresion, no un identificador puntual. OBT no necesita este
      // tratamiento: ya tiene su propia regla amplia en 3g (solo acepta un identificador,
      // nunca un valor o expresion), con su propio mensaje.
      if (anterior.kind == IMP && !esInicioDeValor(token.kind))
        return alta(token, "instruccion IMP incompleta; se esperaba una expresion para imprimir.", "Se esperaba un valor, variable o cadena despues de IMP.");
      if (anterior.kind == OBT && token.kind == FIN_INSTRUCCION)
        return alta(token, "instruccion OBT incompleta; se esperaba el identificador de la variable a leer.", "Se esperaba un identificador despues de OBT.");
      if (anterior.kind == RET && !esInicioDeValor(token.kind))
        return alta(token, "instruccion RET incompleta; se esperaba una expresion de retorno.", "Se esperaba un valor o expresion despues de RET.");
      if (anterior.kind == TERMINAR && token.kind != FIN_INSTRUCCION && token.kind != EOF)
        return alta(token, "la instruccion TERMINAR no recibe argumentos; use unicamente 'TERMINAR;'.", "Se esperaba ';'.");

      // 3b: cabeceras de control de flujo vacias
      if (anterior.kind == SI && (token.kind == APERTURA_BLOQUE || esInicioDeSentencia(token.kind)))
        return alta(token, "la estructura SI requiere una condicion antes del bloque '{'.", "Se esperaba una condicion, por ejemplo 'SI x > 0 {'.");
      if (anterior.kind == MIENTRAS && (token.kind == APERTURA_BLOQUE || esInicioDeSentencia(token.kind)))
        return alta(token, "la estructura MIENTRAS requiere una condicion antes del bloque '{'.", "Se esperaba una condicion, por ejemplo 'MIENTRAS x < 10 {'.");
      if (anterior.kind == EVALUAR && (token.kind == APERTURA_BLOQUE || esInicioDeSentencia(token.kind)))
        return alta(token, "la estructura EVALUAR requiere la expresion a evaluar antes de '{'.", "Se esperaba una expresion despues de EVALUAR.");

      // 3c: asignaciones incompletas
      if (anterior.kind == ASIGNACION && token.kind == FIN_INSTRUCCION)
        return alta(token, "asignacion incompleta; falta el valor o expresion a asignar despues de '->'.", "Se esperaba un valor o expresion.");
      if ((anterior.kind == ASIG_INC || anterior.kind == ASIG_DEC) && token.kind == FIN_INSTRUCCION)
        return alta(token, "falta el valor a incrementar/decrementar despues de '" + anterior.image + "'.", "Se esperaba un valor o expresion.");

      // 3d: operadores aritmeticos colgados o dobles
      if (esOperadorAritmetico(anterior.kind) && token.kind == FIN_INSTRUCCION)
        return alta(token, "expresion aritmetica incompleta; falta el operando derecho despues de '" + anterior.image + "'.", "Se esperaba un operando.");
      if (esOperadorAritmetico(anterior.kind) && esOperadorAritmetico(token.kind))
        return alta(token, "operador '" + token.image + "' inesperado; no se permiten operadores aritmeticos consecutivos.", "Se esperaba un operando entre '" + anterior.image + "' y '" + token.image + "'.");

      // 3e: conectores logicos colgados
      if ((anterior.kind == AND || anterior.kind == OR) && (token.kind == FIN_INSTRUCCION || token.kind == APERTURA_BLOQUE))
        return alta(token, "condicion incompleta; falta la expresion despues de '" + anterior.image + "'.", "Se esperaba una comparacion.");

      // 3f: casos CUANDO/PRED
      if (anterior.kind == CUANDO && token.kind == DOS_PUNTOS)
        return alta(token, "el caso CUANDO requiere una expresion o valor a comparar antes de ':'.", "Se esperaba un valor despues de CUANDO.");

      // 3g: OBT usado con algo que no es una variable
      if (anterior.kind == OBT && token.kind != IDENTIFICADOR && token.kind != FIN_INSTRUCCION)
        return alta(token, "OBT solo puede leer datos hacia una variable; no admite valores literales ni expresiones.", "Se esperaba un identificador despues de OBT.");

      // 3h: CONST sin tipo de dato (mensaje distinto al de un parametro)
      if (anterior.kind == CONST && token.kind == IDENTIFICADOR)
        return alta(token, "CONST requiere un tipo de dato antes del nombre de la constante.", "Se esperaba ENT, DEC, CAD, CAR o BOO.");

      // 3h.1: FUN sin tipo de retorno (mensaje distinto al de un parametro/CONST)
      if (anterior.kind == FUN && token.kind == IDENTIFICADOR)
        return alta(token, "FUN requiere un tipo de retorno antes del nombre de la funcion.", "Se esperaba ENT, DEC, CAD, CAR, BOO o VACIO.");

      // 3i: parametro de funcion sin nombre despues del tipo
      if (esTipoDato(anterior.kind) && (token.kind == SEPARADOR || token.kind == CIERRE_PAREN))
        return alta(token, "el parametro requiere un nombre despues del tipo '" + anterior.image + "'.", "Se esperaba un identificador.");

      // 3j: coma sobrante al final de una lista (arreglo, argumentos o parametros)
      if (anterior.kind == SEPARADOR && (token.kind == CIERRE_PAREN || token.kind == CIERRE_CORCHETE))
        return alta(token, "sobra la ',' antes de '" + token.image + "'; no se permite una coma al final de una lista.", "Quite la ',' sobrante.");

      // 3k: operadores relacionales colgados o dobles
      if (esOperadorRelacional(anterior.kind) && token.kind == FIN_INSTRUCCION)
        return alta(token, "condicion incompleta; falta el valor a comparar despues de '" + anterior.image + "'.", "Se esperaba un valor o expresion.");
      if (esOperadorRelacional(anterior.kind) && esOperadorRelacional(token.kind))
        return alta(token, "operador '" + token.image + "' inesperado; no se permiten operadores relacionales consecutivos.", "Se esperaba un valor entre '" + anterior.image + "' y '" + token.image + "'.");

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
          "Si buscaba llamar a una funcion, se escribe '" + anterior.image + "(argumentos)'.");
    }

    // Capa 4: reglas generales de fallback
    boolean puntoComa = espera(error, FIN_INSTRUCCION);
    boolean asignacion = espera(error, ASIGNACION) || espera(error, ASIG_INC) || espera(error, ASIG_DEC);
    boolean coma = espera(error, SEPARADOR);
    if (puntoComa && esInicioDeSentencia(token.kind)) return faltante(anterior, token, "falta ';' al final de la instruccion anterior.", "Se esperaba ';'.");
    if (asignacion) return faltante(anterior, token, "falta el operador de asignacion '->'.", "Se esperaba '->'.");
    if (token.kind == CIERRE_CORCHETE && esperaExpresion(error))
      return alta(token, "falta una expresion dentro de la dimension, el indice o el literal de arreglo.", "Se esperaba un valor o expresion antes de ']'.");
    if (token.kind == IDENTIFICADOR && esperaTipoDato(error))
      return alta(token, "parametro sin tipo de dato; se esperaba ENT, DEC, CAD, CAR o BOO.", "Se esperaba un tipo antes de '" + token.image + "'.");
    // El token nunca es EOF aqui, la capa 1 ya lo intercepto arriba, asi que siempre hay una imagen util que mostrar.
    if (espera(error, IDENTIFICADOR)) return faltante(anterior, token, "falta un identificador (se encontro '" + token.image + "').", "Se esperaba un nombre de variable o funcion.");
    if (esperaOperadorRelacional(error)) return faltante(anterior, token, "condicion incompleta; falta un operador relacional (=, !=, <, <=, > o >=).", "Se esperaba un operador relacional.");
    if (espera(error, CIERRE_CORCHETE)) return faltante(anterior, token, "falta ']' para cerrar un indice, dimension o literal de arreglo.", "Se esperaba ']'.");
    if (espera(error, CIERRE_PAREN)) return faltante(anterior, token, "falta ')' para cerrar la expresion o llamada.", "Se esperaba ')'.");
    if (espera(error, CIERRE_BLOQUE)) return faltante(anterior, token, "falta '}' para cerrar el bloque.", "Se esperaba '}'.");
    if (espera(error, APERTURA_BLOQUE)) return faltante(anterior, token, "falta '{' para iniciar el bloque.", "Se esperaba '{'.");
    if (espera(error, DOS_PUNTOS)) return faltante(anterior, token, "falta ':' despues de CUANDO o PRED.", "Se esperaba ':'.");
    if (espera(error, CUANDO)) return faltante(anterior, token, "EVALUAR requiere al menos un caso CUANDO.", "Se esperaba 'CUANDO'.");
    if (coma && token.kind != CIERRE_PAREN && token.kind != CIERRE_CORCHETE)
      return faltante(anterior, token, "falta ',' entre elementos o argumentos.", "Se esperaba ','.");
    if (puntoComa) return faltante(anterior, token, "falta ';' al final de la instruccion.", "Se esperaba ';'.");
    if (token.kind == SEPARADOR) return baja(token, "coma fuera de lugar o elemento faltante.", "Se esperaba un elemento antes de ','.");
    if (token.kind == CIERRE_CORCHETE) return baja(token, "']' inesperado.", null);
    if (token.kind == CIERRE_PAREN) return baja(token, "')' inesperado.", null);
    if (token.kind == CIERRE_BLOQUE) return baja(token, "'}' inesperado.", null);
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
      return new ErrorJER("ERROR SINTACTICO", candidato.linea, candidato.columna,
        "'" + candidato.lexema + "' no se reconoce; las palabras reservadas de JER se escriben en MAYUSCULAS.",
        "Se esperaba '" + mayusculas + "'.", true);
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
    if (tipo == NUMERO_ENTERO) return "un numero entero";
    if (tipo == NUMERO_DECIMAL) return "un numero decimal";
    if (tipo == CADENA) return "una cadena entre comillas dobles";
    if (tipo == CARACTER) return "un caracter entre comillas simples";
    String nombre = nombreToken(tipo);
    // Los tokens sin lexema fijo se muestran como <NOMBRE>: no aportan nada al usuario.
    return nombre.startsWith("<") ? null : "'" + nombre + "'";
  }

  // Registro y supresion (modo panico)

  private static ErrorJER alta(Token token, String detalle, String sugerencia) {
    return new ErrorJER("ERROR SINTACTICO", token.beginLine, token.beginColumn, detalle, sugerencia, true);
  }
  private static ErrorJER baja(Token token, String detalle, String sugerencia) {
    return new ErrorJER("ERROR SINTACTICO", token.beginLine, token.beginColumn, detalle, sugerencia, false);
  }
  /**
   * Para diagnosticos de algo ausente ("falta ..."): el token donde JavaCC detecto la falla
   * suele ser el primer token de la sentencia siguiente, que puede caer varias lineas mas
   * abajo del lugar real donde faltaba el simbolo.
   *
   * Se reporta sobre el ultimo token valido (anterior) en su lugar, que es donde el simbolo
   * ausente debia haber ido. Toda regla nueva que agregue un mensaje "falta X" debe usar
   * este metodo, no alta(), para no reintroducir el desfase de linea/columna.
   */
  private static ErrorJER faltante(Token anterior, Token token, String detalle, String sugerencia) {
    Token referencia = anterior != null ? anterior : token;
    return new ErrorJER("ERROR SINTACTICO", referencia.beginLine, referencia.beginColumn, detalle, sugerencia, true);
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

  // Tabla de tokens

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
      // La tabla queda con los tokens leidos hasta el fallo; el error se reporta igual.
      reportarErrorLexico(e);
    }
  }

  private static void guardarTabla(String nombreArchivo) {
    File salida = new File(ARCHIVO_TABLA), directorio = salida.getParentFile(); if (directorio != null && !directorio.exists()) directorio.mkdirs();
    try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(salida), StandardCharsets.UTF_8))) {
      writer.println("TABLA DE TOKENS - JERCompiler"); writer.println("Archivo: " + nombreArchivo);
      writer.printf("%-5s | %-20s | %-28s | %-6s | %s%n", "No.", "Lexema", "Token", "Linea", "Columna");
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
      "Nombre", "Categoria", "Tipo", "Dimensiones", "Ambito", "Linea", "Usado", "Parametros");
    w.println("----------------------+------------+--------+-------------+------------------+--------+-------+------------------------");
    for (TablaSimbolos.Simbolo simbolo : tabla.todos()) {
      String parametros = simbolo.tiposParametros == null ? "" : simbolo.tiposParametros.toString();
      w.printf("%-20s | %-10s | %-6s | %-11d | %-16s | %-6d | %-5s | %s%n",
        simbolo.nombre, descripcionCategoria(simbolo.categoria), simbolo.tipo,
        simbolo.aridadArreglo, simbolo.ambito, simbolo.declaracion.beginLine,
        simbolo.usado ? "si" : "no", parametros);
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
    String formato() { return "[" + tipo + "] Linea " + linea + ", columna " + columna + ": " + detalle; }
  }

  private static final class RegistroToken {
    final String lexema, tipo;
    final int kind, linea, columna;
    RegistroToken(String lexema, String tipo, int kind, int linea, int columna) {
      this.lexema = lexema; this.tipo = tipo; this.kind = kind; this.linea = linea; this.columna = columna;
    }
  }
}
