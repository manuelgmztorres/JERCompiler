import java.util.*;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * Genera la tabla de cuadruplos recorriendo el AST que ya paso el analisis semantico sin errores.
 * Lee las Anotacion que dejo el analizador (tipo y simbolo de cada nodo), asi que no recalcula
 * nada. Tiene tres partes: el programa (arranque `_inicio`, globales `_global`, funciones, sentencias y nombres), las
 * expresiones (con llamadas, arreglos y AND/OR) y el control de flujo (SI, bucles, EVALUAR y
 * TERMINAR).
 *
 * expresion() devuelve el OPERANDO donde queda el valor: una constante, un nombre o un temporal.
 * Una constante o un nombre no genera filas. Si recibe un `destino`, la ultima fila de la
 * expresion escribe directo en el (sin pasar por un temporal); si no genero ninguna fila, quien
 * llama debe copiar el operando con un ASIG.
 *
 * Un salto hacia atras (al inicio de un bucle) conoce su destino al emitirse; uno hacia adelante
 * se emite con el destino vacio, su numero de fila se guarda en una lista y se rellena cuando se
 * llega al destino (TablaCuadruplos.rellenar). `salidas` es la pila de listas de salida de los
 * bucles y EVALUAR abiertos: el SALTO_FAL de la condicion y cada TERMINAR agregan su salto a la
 * lista del bloque mas interno, que se rellena cuando ese bloque termina.
 *
 * Una construccion que no reconozca la anota en noSoportado() en vez de fallar, y la tabla queda
 * incompleta en ese punto.
 */
public final class GeneradorCodigo implements JERCompilerConstants {

  static final String FUNCION_GLOBAL = "_global";
  /** Punto de entrada de la tabla: llama a `_global` (si hay globales) y a `principal`; su RET termina el programa. */
  static final String FUNCION_INICIO = "_inicio";
  /** Nombre de la funcion principal; el analizador semantico ya garantizo que existe, es VACIO y no tiene parametros. */
  static final String FUNCION_PRINCIPAL = "principal";

  final TablaCuadruplos tabla = new TablaCuadruplos();
  private final Deque<List<Integer>> salidas = new ArrayDeque<List<Integer>>(); // salidas de los bloques abiertos (bucles y EVALUAR)

  // Nombres de simbolos del ambito global (variables, constantes y funciones): un local que se
  // llame igual no puede conservar el nombre, o la tabla seria ambigua.
  private final Set<String> nombresGlobales = new HashSet<String>();
  // Todos los nombres que el programador uso: los temporales t1, t2... se saltan los que coincidan
  // (un identificador como `t1` es valido en JER).
  private final Set<String> nombresDelPrograma = new HashSet<String>();
  // Nombre final de cada local (distinto del original cuando se renombra a x_2, x_3...). La
  // identidad la decide el simbolo, no el texto.
  private final Map<TablaSimbolos.Simbolo, String> renombres = new IdentityHashMap<TablaSimbolos.Simbolo, String>();
  // Nombres ya tomados dentro de la funcion en curso (parametros y locales).
  private final Set<String> usadosEnFuncion = new HashSet<String>();
  private final Set<String> noSoportado = new LinkedHashSet<String>();

  public GeneradorCodigo(TablaSimbolos simbolos) {
    for (TablaSimbolos.Simbolo s : simbolos.todos()) {
      nombresDelPrograma.add(s.nombre);
      if ("GLOBAL".equals(s.ambito)) nombresGlobales.add(s.nombre);
    }
  }

  /** Construcciones que el programa usa y esta version todavia no traduce (vacio = tabla completa). */
  public Set<String> noSoportado() { return Collections.unmodifiableSet(noSoportado); }

  public TablaCuadruplos generar(ASTPrograma raiz) {
    List<Node> globales = declaracionesGlobales(raiz);
    generarInicio(!globales.isEmpty());
    generarGlobales(globales);
    for (int i = 0; i < raiz.jjtGetNumChildren(); i++) {
      Node hijo = raiz.jjtGetChild(i);
      if (hijo instanceof ASTDeclaracionFuncion) funcion((ASTDeclaracionFuncion) hijo);
    }
    if (tabla.saltosSinDestino() > 0) noSoportado("saltos sin destino (error interno del generador)");
    tabla.cerrar(); // los saltos pasan de numero de fila a etiqueta, con su fila ETIQ
    return tabla;
  }

  /**
   * Primera funcion de la tabla y punto de entrada del programa: el orden de arranque (globales y
   * despues principal) queda escrito en el codigo intermedio en vez de ser una regla implicita.
   * Los nombres que empiezan con '_' no pueden chocar con los del programa: un identificador de
   * JER empieza siempre con letra.
   */
  private void generarInicio(boolean hayGlobales) {
    iniciarFuncion(FUNCION_INICIO);
    if (hayGlobales) tabla.emitir("LLAMAR", FUNCION_GLOBAL, "0", null, TablaSimbolos.TipoDato.VACIO);
    tabla.emitir("LLAMAR", FUNCION_PRINCIPAL, "0", null, TablaSimbolos.TipoDato.VACIO);
    tabla.emitir("RET", null, null, null, null);
  }

  /** Las declaraciones de variables y constantes globales, en orden de declaracion. */
  private static List<Node> declaracionesGlobales(ASTPrograma raiz) {
    List<Node> globales = new ArrayList<Node>();
    for (int i = 0; i < raiz.jjtGetNumChildren(); i++) {
      Node hijo = raiz.jjtGetChild(i);
      if (esDeclaracionDeVariable(hijo)) globales.add(hijo);
    }
    return globales;
  }

  /** Los globales se inicializan en orden de declaracion dentro de la funcion sintetica `_global`; sin globales no se genera. */
  private void generarGlobales(List<Node> globales) {
    if (globales.isEmpty()) return;
    iniciarFuncion(FUNCION_GLOBAL);
    for (Node global : globales) declaracion(global, true);
    tabla.emitir("RET", null, null, null, null);
  }

  private static boolean esDeclaracionDeVariable(Node nodo) {
    return nodo instanceof ASTDeclaracionVariable || nodo instanceof ASTDeclaracionConstante;
  }

  // Funciones

  private void iniciarFuncion(String nombre) {
    tabla.reiniciarTemporales();
    usadosEnFuncion.clear();
    tabla.emitir("FUN", nombre, null, null, null);
  }

  private void funcion(ASTDeclaracionFuncion nodo) {
    TablaSimbolos.Simbolo funcion = AnalizadorSemantico.Anotacion.de(nodo).simbolo;
    iniciarFuncion(funcion.nombre);
    int primeraFila = tabla.siguiente(); // para revisar, al cerrar, los saltos que caen al final de la funcion
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) {
      Node hijo = nodo.jjtGetChild(i);
      if (hijo instanceof ASTParametro) {
        TablaSimbolos.Simbolo parametro = AnalizadorSemantico.Anotacion.de(hijo).simbolo;
        usadosEnFuncion.add(parametro.nombre); // los parametros conservan su nombre
        tabla.emitir("RECIBIR", null, null, parametro.nombre, parametro.tipo);
      } else {
        sentencia(hijo);
      }
    }
    // En una funcion VACIO el programador no escribe RET, y sin esta fila la ejecucion seguiria
    // hacia la funcion siguiente. Una funcion con valor ya termina con su propio RET, salvo que un
    // salto apunte a la fila que vendria justo despues (p. ej. un SI/SINO donde cada rama hace su
    // propio RET): entonces se agrega un RET de cierre para que ese destino exista.
    if (funcion.tipo == TablaSimbolos.TipoDato.VACIO || haySaltoAlFinal(primeraFila)) tabla.emitir("RET", null, null, null, null);
  }

  private boolean haySaltoAlFinal(int primeraFila) {
    String destinoFinal = String.valueOf(tabla.siguiente());
    for (int fila = primeraFila; fila <= tabla.tamano(); fila++) {
      TablaCuadruplos.Cuadruplo c = tabla.fila(fila);
      if (c.operador.startsWith("SALTO") && destinoFinal.equals(c.resultado)) return true;
    }
    return false;
  }

  // Sentencias

  void sentencia(Node nodo) {
    if (nodo instanceof ASTBloque) {
      for (int i = 0; i < nodo.jjtGetNumChildren(); i++) sentencia(nodo.jjtGetChild(i));
    } else if (esDeclaracionDeVariable(nodo)) {
      declaracion(nodo, false);
    } else if (nodo instanceof ASTAsignacionOLlamada) {
      asignacionOLlamada((ASTAsignacionOLlamada) nodo);
    } else if (nodo instanceof ASTSentenciaImprimir) {
      String valor = expresion(nodo.jjtGetChild(0), null);
      tabla.emitir("IMP", valor, null, null, null);
    } else if (nodo instanceof ASTSentenciaObtener) {
      TablaSimbolos.Simbolo variable = AnalizadorSemantico.Anotacion.de(nodo).simbolo;
      tabla.emitir("OBT", null, null, nombre(variable), variable.tipo);
    } else if (nodo instanceof ASTSentenciaRetorno) {
      String valor = expresion(nodo.jjtGetChild(0), null);
      tabla.emitir("RET", valor, null, null, null);
    } else if (nodo instanceof ASTEstructuraSi) {
      si((ASTEstructuraSi) nodo);
    } else if (nodo instanceof ASTEstructuraMientras) {
      mientras((ASTEstructuraMientras) nodo);
    } else if (nodo instanceof ASTEstructuraHacer) {
      hacer((ASTEstructuraHacer) nodo);
    } else if (nodo instanceof ASTEstructuraRepetir) {
      repetir((ASTEstructuraRepetir) nodo);
    } else if (nodo instanceof ASTEstructuraEvaluar) {
      evaluar((ASTEstructuraEvaluar) nodo);
    } else if (nodo instanceof ASTSentenciaTerminar) {
      terminar();
    } else {
      noSoportado(nombreDeConstruccion(nodo));
    }
  }

  void declaracion(Node nodo, boolean global) {
    TablaSimbolos.Simbolo variable = AnalizadorSemantico.Anotacion.de(nodo).simbolo;
    String nombre = global ? variable.nombre : registrarLocal(variable);
    if (variable.aridadArreglo > 0) { declararArreglo(nodo, variable, nombre); return; }
    if (nodo.jjtGetNumChildren() > 0) asignar(nombre, nodo.jjtGetChild(0), variable.tipo);
    else tabla.emitir("ASIG", valorPorDefecto(variable.tipo), null, nombre, variable.tipo);
  }

  private void asignacionOLlamada(ASTAsignacionOLlamada nodo) {
    Token siguiente = nodo.jjtGetFirstToken().next;
    if (siguiente.kind == APERTURA_PAREN) { llamar(nodo, null, false); return; }
    TablaSimbolos.Simbolo variable = AnalizadorSemantico.Anotacion.de(nodo).simbolo;
    if (siguiente.kind == APERTURA_CORCHETE) { asignarElemento(nodo, variable); return; }
    if (siguiente.kind == INC || siguiente.kind == DEC_OP) { actualizar(variable, siguiente.kind, null); return; }
    int operador = ((SimpleNode) nodo.jjtGetChild(0)).jjtGetFirstToken().kind; // ->, +-> o -->
    Node valor = nodo.jjtGetChild(1);
    if (operador == ASIGNACION) asignar(nombre(variable), valor, variable.tipo);
    else actualizar(variable, operador, valor);
  }

  /**
   * `x++`, `x--`, `x +-> e` y `x --> e` sobre una variable (tambien el paso de un REPETIR): una sola
   * fila `x = x + valor` o `x = x - valor`. Con `++` y `--` no hay expresion y el valor es 1 (1.0 si es DEC).
   */
  private void actualizar(TablaSimbolos.Simbolo variable, int operador, Node valor) {
    String nombre = nombre(variable);
    String operando = valor == null ? unoDe(variable.tipo) : expresion(valor, null);
    tabla.emitir(signo(operador), nombre, operando, nombre, variable.tipo);
  }

  /** "+" para `++` y `+->`; "-" para `--` y `-->`. */
  private static String signo(int operador) { return operador == INC || operador == ASIG_INC ? "+" : "-"; }

  /**
   * `destino -> expresion`. La ultima fila de la expresion escribe directo en el destino; solo si la
   * expresion no genero ninguna fila (una constante o una variable) hace falta un ASIG.
   */
  private void asignar(String destino, Node expresion, TablaSimbolos.TipoDato tipo) {
    int antes = tabla.tamano();
    String valor = expresion(expresion, destino);
    if (tabla.tamano() == antes) tabla.emitir("ASIG", valor, null, destino, tipo);
  }

  // Nombres y temporales

  /** Nombre con el que aparece `simbolo` en la tabla (el renombrado si es un local repetido). */
  String nombre(TablaSimbolos.Simbolo simbolo) {
    String renombrado = renombres.get(simbolo);
    return renombrado != null ? renombrado : simbolo.nombre;
  }

  /**
   * Primer local con un nombre lo conserva; si ya lo tiene un global, una funcion, un parametro u
   * otro local de esta funcion, pasa a x_2, x_3...
   */
  private String registrarLocal(TablaSimbolos.Simbolo simbolo) {
    String candidato = simbolo.nombre;
    for (int k = 2; nombresGlobales.contains(candidato) || usadosEnFuncion.contains(candidato); k++) {
      candidato = simbolo.nombre + "_" + k;
    }
    usadosEnFuncion.add(candidato);
    if (!candidato.equals(simbolo.nombre)) renombres.put(simbolo, candidato);
    return candidato;
  }

  /** Temporal nuevo (t1, t2...) que no choque con un nombre del programa. */
  String temporal() {
    String t;
    do { t = tabla.nuevoTemporal(); } while (nombresDelPrograma.contains(t));
    return t;
  }

  void noSoportado(String construccion) { noSoportado.add(construccion); }

  static TablaSimbolos.TipoDato tipoDe(Node nodo) {
    AnalizadorSemantico.Anotacion a = AnalizadorSemantico.Anotacion.de(nodo);
    return a == null ? null : a.tipo;
  }

  // Constantes del lenguaje

  private static String valorPorDefecto(TablaSimbolos.TipoDato tipo) {
    switch (tipo) {
      case ENT: return "0";
      case DEC: return "0.0";
      case CAD: return "\"\"";
      case CAR: return "'\\0'";
      default: return "FALSO"; // BOO
    }
  }

  static String unoDe(TablaSimbolos.TipoDato tipo) { return tipo == TablaSimbolos.TipoDato.DEC ? "1.0" : "1"; }

  private static String nombreDeConstruccion(Node nodo) {
    if (nodo instanceof ASTEstructuraSi) return "SI";
    if (nodo instanceof ASTEstructuraMientras) return "MIENTRAS";
    if (nodo instanceof ASTEstructuraRepetir) return "REPETIR";
    if (nodo instanceof ASTEstructuraHacer) return "HACER";
    if (nodo instanceof ASTEstructuraEvaluar) return "EVALUAR";
    if (nodo instanceof ASTSentenciaTerminar) return "TERMINAR";
    return nodo.getClass().getSimpleName();
  }

  // Expresiones, llamadas y arreglos

  String expresion(Node nodo, String destino) {
    if (nodo instanceof ASTValorSimple) return valorSimple((ASTValorSimple) nodo, destino);
    if (nodo instanceof ASTExpresion || nodo instanceof ASTTermino || nodo instanceof ASTFactor) return cadena((SimpleNode) nodo, destino);
    if (nodo instanceof ASTUnaryMinus) return negativo((ASTUnaryMinus) nodo, destino);
    if (nodo instanceof ASTExpresionRelacional) return relacional((ASTExpresionRelacional) nodo, destino);
    if (nodo instanceof ASTLlamadaFuncion) return llamar((SimpleNode) nodo, destino, true);
    if (nodo instanceof ASTExpresionLogica) return logica((ASTExpresionLogica) nodo, destino);
    noSoportado(nodo.getClass().getSimpleName());
    return "?";
  }

  // AND / OR

  /**
   * `a AND b` / `a OR b` en cortocircuito. El resultado se junta siempre
   * en un temporal nuevo, nunca directo en el destino: se escribe dos veces (por el camino corto y
   * por el largo) y el segundo operando podria leer la variable destino. Despues de calcular el
   * primer operando, cada operador emite un salto (SALTO_FAL para AND, SALTO_VER para OR) que se
   * salta el siguiente operando cuando el resultado ya esta decidido; si no, el operando se copia al
   * temporal. La cadena es plana y se evalua de izquierda a derecha, sin precedencia entre AND y OR:
   * los saltos de una racha del mismo operador caen al final de la racha, y asi `a OR b AND c`
   * (= `(a OR b) AND c`) no se salta el `AND c`.
   */
  private String logica(ASTExpresionLogica nodo, String destino) {
    int antes = tabla.tamano();
    String acumulado = expresion(nodo.jjtGetChild(0), null);
    if (tabla.tamano() == antes) {
      // Una variable o constante no tiene temporal propio donde juntar el resultado: se copia antes.
      String copia = temporal();
      tabla.emitir("ASIG", acumulado, null, copia, TablaSimbolos.TipoDato.BOO);
      acumulado = copia;
    }
    List<Integer> pendientes = new ArrayList<Integer>();
    int racha = -1; // AND o OR de los saltos que estan pendientes
    for (int i = 1; i < nodo.jjtGetNumChildren(); i++) {
      int operador = AnalizadorSemantico.operadorDespuesDe(nodo.jjtGetChild(i - 1)).kind;
      if (operador != racha) {
        tabla.rellenar(pendientes, tabla.siguiente()); // termina la racha anterior
        racha = operador;
      }
      pendientes.add(tabla.emitir(operador == AND ? "SALTO_FAL" : "SALTO_VER", acumulado, null, null, null));
      String operando = expresion(nodo.jjtGetChild(i), null);
      tabla.emitir("ASIG", operando, null, acumulado, TablaSimbolos.TipoDato.BOO);
    }
    tabla.rellenar(pendientes, tabla.siguiente());
    if (destino == null) return acumulado;
    tabla.emitir("ASIG", acumulado, null, destino, TablaSimbolos.TipoDato.BOO);
    return destino;
  }

  private String valorSimple(ASTValorSimple nodo, String destino) {
    Token primero = nodo.jjtGetFirstToken();
    if (primero.kind != IDENTIFICADOR) return primero.image; // constante tal como se escribio en JER
    TablaSimbolos.Simbolo simbolo = AnalizadorSemantico.Anotacion.de(nodo).simbolo;
    if (nodo.jjtGetNumChildren() > 0) return leerElemento(nodo, simbolo, destino);
    return nombre(simbolo);
  }

  // Llamadas

  /**
   * `f(a, b)`: se evaluan TODOS los argumentos primero y despues se emiten los PARAM, para que una
   * llamada anidada no mezcle sus filas con las de la externa. `nodo` es una LlamadaFuncion o una
   * AsignacionOLlamada usada como sentencia (en los dos casos sus hijos son los argumentos).
   * `conValor` es false cuando la llamada es una sentencia: si la funcion devuelve algo, se descarta.
   */
  String llamar(SimpleNode nodo, String destino, boolean conValor) {
    TablaSimbolos.Simbolo funcion = AnalizadorSemantico.Anotacion.de(nodo).simbolo;
    int n = nodo.jjtGetNumChildren();
    String[] argumentos = new String[n];
    for (int i = 0; i < n; i++) argumentos[i] = expresion(nodo.jjtGetChild(i), null);
    for (int i = 0; i < n; i++) tabla.emitir("PARAM", argumentos[i], null, null, funcion.tiposParametros.get(i));
    String resultado = null;
    if (conValor && funcion.tipo != TablaSimbolos.TipoDato.VACIO) resultado = destino != null ? destino : temporal();
    tabla.emitir("LLAMAR", funcion.nombre, String.valueOf(n), resultado, funcion.tipo);
    return resultado;
  }

  // Arreglos

  /**
   * `ENT v[2][3] -> [[..], [..]];`: se calculan las dimensiones, una fila DIM por cada una, y
   * RESERVAR; despues cada elemento del literal se escribe con `ASIG_ELEM`. `nodo` es la declaracion, cuyo
   * primer hijo es el nodo Dimensiones y el segundo, si existe, el literal.
   */
  void declararArreglo(Node nodo, TablaSimbolos.Simbolo arreglo, String nombre) {
    Node dimensiones = nodo.jjtGetChild(0);
    int n = dimensiones.jjtGetNumChildren();
    String[] tamanios = new String[n];
    for (int i = 0; i < n; i++) tamanios[i] = expresion(dimensiones.jjtGetChild(i), null);
    for (int i = 0; i < n; i++) tabla.emitir("DIM", tamanios[i], null, null, TablaSimbolos.TipoDato.ENT);
    tabla.emitir("RESERVAR", arreglo.tipo.name(), String.valueOf(n), nombre, arreglo.tipo);
    if (nodo.jjtGetNumChildren() > 1) inicializar(nombre, arreglo.tipo, nodo.jjtGetChild(1));
  }

  /**
   * Escribe los elementos de un literal; un sub-literal se baja primero a su fila con `LEER_ELEM`.
   * Esa fila es una REFERENCIA (ver descender()): escribir en ella llena el arreglo original.
   */
  private void inicializar(String arreglo, TablaSimbolos.TipoDato tipo, Node literal) {
    for (int i = 0; i < literal.jjtGetNumChildren(); i++) {
      Node elemento = literal.jjtGetChild(i);
      if (elemento instanceof ASTLiteralArreglo) {
        String fila = temporal();
        tabla.emitir("LEER_ELEM", arreglo, String.valueOf(i), fila, tipo);
        inicializar(fila, tipo, elemento);
      } else {
        String valor = expresion(elemento, null);
        tabla.emitir("ASIG_ELEM", valor, String.valueOf(i), arreglo, tipo);
      }
    }
  }

  /** `v[i]` o `m[i][j]` como valor: los indices se evaluan primero; `m[i][j]` es `LEER_ELEM m i t1` y `LEER_ELEM t1 j destino`. */
  private String leerElemento(SimpleNode nodo, TablaSimbolos.Simbolo arreglo, String destino) {
    String[] indices = evaluarIndices(nodo, nodo.jjtGetNumChildren());
    String fila = descender(nombre(arreglo), indices, arreglo.tipo);
    String resultado = destino != null ? destino : temporal();
    tabla.emitir("LEER_ELEM", fila, indices[indices.length - 1], resultado, arreglo.tipo);
    return resultado;
  }

  /**
   * Sentencia que escribe un elemento: `v[i] -> e`, `v[i] +-> e`, `v[i] --> e`, `v[i]++`, `v[i]--`.
   * Con `->` es un `ASIG_ELEM`; con las demas se lee el elemento, se opera y se vuelve a escribir.
   */
  void asignarElemento(ASTAsignacionOLlamada nodo, TablaSimbolos.Simbolo arreglo) {
    int posicionOperador = AnalizadorSemantico.posicionOperadorAsignacion(nodo);
    int numIndices = posicionOperador >= 0 ? posicionOperador : nodo.jjtGetNumChildren();
    String[] indices = evaluarIndices(nodo, numIndices);
    String ultimo = indices[numIndices - 1];
    TablaSimbolos.TipoDato tipo = arreglo.tipo;

    int operador = operadorDeElemento(nodo, posicionOperador);
    String valor = posicionOperador >= 0 ? expresion(nodo.jjtGetChild(posicionOperador + 1), null) : unoDe(tipo);
    String fila = descender(nombre(arreglo), indices, tipo);
    if (operador != ASIGNACION) {
      // Operacion compuesta: se lee el elemento, se opera y se vuelve a escribir.
      String actual = temporal();
      tabla.emitir("LEER_ELEM", fila, ultimo, actual, tipo);
      String nuevo = temporal();
      tabla.emitir(signo(operador), actual, valor, nuevo, tipo);
      valor = nuevo;
    }
    tabla.emitir("ASIG_ELEM", valor, ultimo, fila, tipo);
  }

  /** El operador de una asignacion a un elemento: `->`, `+->` o `-->`; si no hay, el `++` o `--` de la sentencia. */
  private static int operadorDeElemento(ASTAsignacionOLlamada nodo, int posicionOperador) {
    if (posicionOperador >= 0) return ((SimpleNode) nodo.jjtGetChild(posicionOperador)).jjtGetFirstToken().kind;
    Token t = nodo.jjtGetFirstToken();
    while (t.kind != INC && t.kind != DEC_OP) t = t.next;
    return t.kind;
  }

  private String[] evaluarIndices(SimpleNode nodo, int cantidad) {
    String[] indices = new String[cantidad];
    for (int i = 0; i < cantidad; i++) indices[i] = expresion(nodo.jjtGetChild(i), null);
    return indices;
  }

  /**
   * Aplica todos los indices menos el ultimo (`LEER_ELEM m i t1`) y devuelve el operando de la fila
   * resultante. Semantica de LEER_ELEM: si lo leido es una fila de un arreglo de varias dimensiones
   * (faltan indices por aplicar), el destino es una REFERENCIA a esa fila, no una copia; por eso
   * `t1 = m[i]` seguido de `t1[j] = v` modifica `m`. Solo con el ultimo indice se obtiene un valor.
   */
  private String descender(String arreglo, String[] indices, TablaSimbolos.TipoDato tipo) {
    for (int i = 0; i < indices.length - 1; i++) {
      String fila = temporal();
      tabla.emitir("LEER_ELEM", arreglo, indices[i], fila, tipo);
      arreglo = fila;
    }
    return arreglo;
  }

  /**
   * Expresion / Termino / Factor: se genera el primer operando y, por cada operador, el siguiente
   * operando y una fila con un temporal nuevo (o con el destino, en la ultima).
   */
  private String cadena(SimpleNode nodo, String destino) {
    Node primero = nodo.jjtGetChild(0);
    String acumulado = expresion(primero, null);
    TablaSimbolos.TipoDato tipoAcumulado = tipoDe(primero);
    int n = nodo.jjtGetNumChildren();
    for (int i = 1; i < n; i++) {
      Node hijo = nodo.jjtGetChild(i);
      String operando = expresion(hijo, null);
      String operador = simboloAritmetico(AnalizadorSemantico.operadorDespuesDe(nodo.jjtGetChild(i - 1)));
      TablaSimbolos.TipoDato tipoHijo = tipoDe(hijo);
      // '+' con un CAD de cualquier lado es concatenacion y el resultado es CAD.
      if (operador.equals("+") && (tipoAcumulado == TablaSimbolos.TipoDato.CAD || tipoHijo == TablaSimbolos.TipoDato.CAD)) {
        tipoAcumulado = TablaSimbolos.TipoDato.CAD;
      }
      String resultado = (i == n - 1 && destino != null) ? destino : temporal();
      tabla.emitir(operador, acumulado, operando, resultado, tipoAcumulado);
      acumulado = resultado;
    }
    return acumulado;
  }

  private String negativo(ASTUnaryMinus nodo, String destino) {
    String operando = expresion(nodo.jjtGetChild(0), null);
    String resultado = destino != null ? destino : temporal();
    tabla.emitir("NEG", operando, null, resultado, tipoDe(nodo));
    return resultado;
  }

  /**
   * Con un hijo es `NOT e`; con tres es `e1 operador e2`, y puede llevar un NOT antepuesto que el
   * arbol no marca aparte: se reconoce porque el nodo empieza con el token NOT.
   */
  private String relacional(ASTExpresionRelacional nodo, String destino) {
    boolean negado = nodo.jjtGetFirstToken().kind == NOT;
    if (nodo.jjtGetNumChildren() == 1) return negar(expresion(nodo.jjtGetChild(0), null), destino);
    String izquierda = expresion(nodo.jjtGetChild(0), null);
    String derecha = expresion(nodo.jjtGetChild(2), null);
    String operador = simboloRelacional(((SimpleNode) nodo.jjtGetChild(1)).jjtGetFirstToken());
    String resultado = (!negado && destino != null) ? destino : temporal();
    tabla.emitir(operador, izquierda, derecha, resultado, TablaSimbolos.TipoDato.BOO);
    return negado ? negar(resultado, destino) : resultado;
  }

  private String negar(String operando, String destino) {
    String resultado = destino != null ? destino : temporal();
    tabla.emitir("NOT", operando, null, resultado, TablaSimbolos.TipoDato.BOO);
    return resultado;
  }

  private static String simboloAritmetico(Token operador) {
    switch (operador.kind) {
      case SUMA: return "+";
      case RESTA: return "-";
      case MULT: return "*";
      case DIV: return "/";
      case MODULO: return "%";
      case POTENCIA: return "**";
      case RAIZ: return "//";
      default: throw new IllegalStateException("operador aritmetico inesperado: " + operador.image);
    }
  }

  private static String simboloRelacional(Token operador) {
    switch (operador.kind) {
      case IGUAL: return "==";
      case DIFERENTE: return "!=";
      case MAYOR_QUE: return ">";
      case MENOR_QUE: return "<";
      case MAYOR_IGUAL: return ">=";
      case MENOR_IGUAL: return "<=";
      default: throw new IllegalStateException("operador relacional inesperado: " + operador.image);
    }
  }

  // Control de flujo

  /**
   * `SI c { A } SINO { B }`: SALTO_FAL al SINO (o al final si no hay), A, SALTO al final para esquivar
   * B, B. Un `SINO SI` es otro SI dentro del SINO, que se traduce con esta misma funcion.
   */
  void si(ASTEstructuraSi nodo) {
    Node condicion = nodo.jjtGetChild(0);
    Node entonces = nodo.jjtGetChild(1);
    Node sino = nodo.jjtGetNumChildren() > 2 ? nodo.jjtGetChild(2) : null;

    List<Integer> haciaElSino = new ArrayList<Integer>();
    haciaElSino.add(saltoSiFalso(condicion));
    sentencia(entonces);
    if (sino == null) {
      tabla.rellenar(haciaElSino, tabla.siguiente());
      return;
    }
    List<Integer> haciaElFinal = new ArrayList<Integer>();
    haciaElFinal.add(tabla.emitir("SALTO", null, null, null, null));
    tabla.rellenar(haciaElSino, tabla.siguiente());
    sentencia(sino);
    tabla.rellenar(haciaElFinal, tabla.siguiente());
  }

  /** `MIENTRAS c { A }`: condicion, SALTO_FAL a la salida, A, SALTO de vuelta a la condicion. */
  void mientras(ASTEstructuraMientras nodo) {
    Node condicion = nodo.jjtGetChild(0);
    Node cuerpo = nodo.jjtGetChild(1);
    int inicio = tabla.siguiente();
    bucle(inicio, condicion, cuerpo, null);
  }

  /** `HACER { A } MIENTRAS (c)`: A, condicion, SALTO_VER de vuelta al inicio de A. */
  void hacer(ASTEstructuraHacer nodo) {
    Node[] partes = AnalizadorSemantico.partesDeHacer(nodo);
    int inicio = tabla.siguiente();
    List<Integer> lista = new ArrayList<Integer>();
    salidas.push(lista);
    sentencia(partes[0]);
    String valor = expresion(partes[1], null);
    tabla.emitir("SALTO_VER", valor, null, String.valueOf(inicio), null);
    tabla.rellenar(salidas.pop(), tabla.siguiente());
  }

  /**
   * `REPETIR (D; c; p) { A }`: la declaracion D, luego igual que MIENTRAS pero con el paso p justo
   * antes del SALTO de vuelta a la condicion.
   */
  void repetir(ASTEstructuraRepetir nodo) {
    AnalizadorSemantico.PartesRepetir partes = AnalizadorSemantico.partesDeRepetir(nodo);
    declaracion(partes.declaracion, false);
    bucle(tabla.siguiente(), partes.condicion, partes.cuerpo, partes.paso);
  }

  /** Parte comun de MIENTRAS y REPETIR; `inicio` es la fila donde empieza la condicion. */
  private void bucle(int inicio, Node condicion, Node cuerpo, Node paso) {
    List<Integer> lista = new ArrayList<Integer>();
    lista.add(saltoSiFalso(condicion));
    salidas.push(lista);
    sentencia(cuerpo);
    if (paso != null) paso((ASTPasoRepetir) paso);
    tabla.emitir("SALTO", null, null, String.valueOf(inicio), null);
    tabla.rellenar(salidas.pop(), tabla.siguiente());
  }

  /**
   * `EVALUAR e { CUANDO v1: A  CUANDO v2: B  PRED: C }` (sin caida). La expresion se calcula una sola
   * vez. Por cada CUANDO se compara y se salta con SALTO_VER al cuerpo de su caso; despues del ultimo
   * se salta con SALTO al PRED (o al final si no hay). Cada cuerpo termina con SALTO al final, salvo
   * que sea el ultimo (el PRED), que ya cae ahi.
   */
  void evaluar(ASTEstructuraEvaluar nodo) {
    List<AnalizadorSemantico.CasoEvaluar> casos = AnalizadorSemantico.casosDeEvaluar(nodo);
    String valor = expresion(nodo.jjtGetChild(0), null);
    List<Integer> haciaCuerpo = compararCasos(casos, valor);

    List<Integer> lista = new ArrayList<Integer>(); // salidas: los SALTO al final y cada TERMINAR
    salidas.push(lista);
    boolean hayPred = casos.get(casos.size() - 1).esPred;
    int haciaPred = tabla.emitir("SALTO", null, null, null, null); // si ningun CUANDO coincide
    if (!hayPred) lista.add(haciaPred);
    emitirCuerpos(casos, haciaCuerpo, haciaPred, lista);
    tabla.rellenar(salidas.pop(), tabla.siguiente());
  }

  /** Por cada CUANDO compara con `valor` y salta al cuerpo de su caso (destino pendiente); devuelve esos saltos en orden. */
  private List<Integer> compararCasos(List<AnalizadorSemantico.CasoEvaluar> casos, String valor) {
    List<Integer> haciaCuerpo = new ArrayList<Integer>();
    for (AnalizadorSemantico.CasoEvaluar caso : casos) {
      if (caso.esPred) continue;
      String comparado = expresion(caso.valor, null);
      String resultado = temporal();
      tabla.emitir("==", valor, comparado, resultado, TablaSimbolos.TipoDato.BOO);
      haciaCuerpo.add(tabla.emitir("SALTO_VER", resultado, null, null, null));
    }
    return haciaCuerpo;
  }

  /**
   * Emite el cuerpo de cada caso, rellenando el salto que llega a el. Cada cuerpo termina con un SALTO
   * al final del EVALUAR (se agrega a `haciaElFinal`), salvo el ultimo si es el PRED, que ya cae ahi.
   */
  private void emitirCuerpos(List<AnalizadorSemantico.CasoEvaluar> casos, List<Integer> haciaCuerpo, int haciaPred, List<Integer> haciaElFinal) {
    int siguienteCuando = 0;
    for (int i = 0; i < casos.size(); i++) {
      AnalizadorSemantico.CasoEvaluar caso = casos.get(i);
      if (!caso.esPred) tabla.rellenar(haciaCuerpo.get(siguienteCuando++), tabla.siguiente());
      else tabla.rellenar(haciaPred, tabla.siguiente());
      for (Node sentencia : caso.sentencias) sentencia(sentencia);
      boolean ultimoYPred = i == casos.size() - 1 && caso.esPred;
      if (!ultimoYPred) haciaElFinal.add(tabla.emitir("SALTO", null, null, null, null));
    }
  }

  /** `TERMINAR`: salto al final del bucle o EVALUAR mas interno, que se rellena cuando ese bloque termina. */
  void terminar() {
    if (salidas.isEmpty()) { noSoportado("TERMINAR fuera de un bucle (error interno del generador)"); return; }
    salidas.peek().add(tabla.emitir("SALTO", null, null, null, null));
  }

  /** Calcula la condicion y emite `SALTO_FAL c` con el destino pendiente; devuelve el numero de esa fila. */
  private int saltoSiFalso(Node condicion) {
    String valor = expresion(condicion, null);
    return tabla.emitir("SALTO_FAL", valor, null, null, null);
  }

  /** El paso de un REPETIR: `i++`, `i--`, `i +-> e` o `i --> e`. */
  private void paso(ASTPasoRepetir nodo) {
    TablaSimbolos.Simbolo variable = AnalizadorSemantico.Anotacion.de(nodo).simbolo;
    int operador = nodo.jjtGetFirstToken().next.kind;
    actualizar(variable, operador, nodo.jjtGetNumChildren() > 0 ? nodo.jjtGetChild(0) : null);
  }
}
