import java.util.*;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * Analizador semantico de dos pasadas sobre el AST que devuelve JERCompiler.Programa().
 *
 * Pasada 1 (registrarDeclaracionesGlobales): recorre los hijos directos de ASTPrograma y
 * registra variables, constantes y funciones globales, para que una funcion pueda llamar a
 * otra declarada mas abajo en el archivo.
 *
 * Pasada 2 (el visitor, disparado desde el mismo jjtAccept que arranca la pasada 1 en
 * visit(ASTPrograma)): entra a cada ASTDeclaracionFuncion y revisa su cuerpo.
 *
 * Cada visit() de una expresion devuelve su TipoDato calculado, que sube por el arbol.
 * `data` esta reservado para contexto hacia abajo (por ejemplo el tipo esperado de un
 * literal de arreglo) y todavia no se usa. Los nodos sin implementar (grupos 2-4) siguen
 * bajando con childrenAccept().
 */
public final class AnalizadorSemantico implements JERCompilerVisitor, JERCompilerConstants {

  private final TablaSimbolos tabla = new TablaSimbolos();

  /**
   * `data` que procesarHijosDeDeclaracion() le pasa a un literal de arreglo inicializador,
   * para comparar su cantidad de elementos contra el tamano declarado (ver Simbolo.tamanios).
   * `nivel` es 1-indexado, solo para el mensaje de error; `tamanios` es la cola de
   * Simbolo.tamanios desde este nivel (tamanios[0] es el tamano esperado aqui).
   */
  private static final class ContextoTamanioArreglo {
    final int[] tamanios;
    final int nivel;
    final String nombre; // nombre del simbolo, solo para que los mensajes de error lo mencionen
    ContextoTamanioArreglo(int[] tamanios, int nivel, String nombre) { this.tamanios = tamanios; this.nivel = nivel; this.nombre = nombre; }
  }

  // Contexto de la funcion/bucle/caso actual, igual que profundidadFuncion en el parser.
  // No vive en TablaSimbolos.
  private TablaSimbolos.TipoDato tipoRetornoFuncionActual;
  private int profundidadBucle = 0;
  private int profundidadCasoEvaluar = 0;

  // Cuenta cuantos entrarScope() propios estan abiertos (los de la pasada 1 no cuentan).
  // Le sirve a visit(ASTDeclaracionVariable/Constante) para saber si esta dentro de una
  // funcion o bloque, donde debe declarar porque es una declaracion local real, o a nivel
  // global, donde no debe declarar de nuevo porque la pasada 1 ya la registro y aqui solo
  // toca seguir bajando por sus hijos.
  private int profundidadAnidamiento = 0;

  // Declaracion global cuyas dimensiones/inicializador se estan visitando ahora; null fuera de
  // ellas y dentro de funciones. Sirve para rechazar un global usado antes de su declaracion:
  // los globales se inicializan en el orden en que se declaran.
  private Token declaracionGlobalEnCurso = null;

  private void entrarScope() { tabla.entrarScope(); profundidadAnidamiento++; }
  private void entrarScope(String nombreFuncion) { tabla.entrarScopeDeFuncion(nombreFuncion); profundidadAnidamiento++; }
  private void salirScope() { tabla.salirScope(); profundidadAnidamiento--; }

  public void analizar(ASTPrograma raiz) {
    raiz.jjtAccept(this, null);
  }

  /**
   * El operador aritmetico (o AND/OR) que sigue al hijo `hijo` de una cadena de operadores. Los
   * operadores no tienen nodo propio: es el token que viene despues del ultimo token del hijo, salvo
   * que ese hijo estuviera entre parentesis (`(a + b) * c`): el ')' no pertenece al nodo, asi que
   * hay que saltarlo. Tambien lo usa el generador de codigo intermedio.
   */
  static Token operadorDespuesDe(Node hijo) {
    Token t = ((SimpleNode) hijo).jjtGetLastToken().next;
    while (t != null && t.kind == CIERRE_PAREN) t = t.next;
    return t;
  }

  /** Para reportar la tabla de tipos (ManejadorErrores.guardarTablaDeTipos()) tras un analisis sin errores. */
  public TablaSimbolos obtenerTabla() { return tabla; }

  /**
   * El programa necesita un punto de entrada: una funcion 'principal' sin parametros, de tipo
   * VACIO (principal no devuelve valor, asi que su RET final lo agrega el generador). El codigo intermedio se genera
   * automaticamente cuando hay 0 errores, asi que sin 'principal' no habria desde donde empezar.
   * Debe llamarse despues de analizar(). Reporta y devuelve false si no se cumple.
   */
  public boolean validarPuntoDeEntrada() {
    TablaSimbolos.Simbolo principal = tabla.resolver("principal");
    if (principal == null) { ManejadorErrores.reportarFaltaPrincipal(); return false; }
    boolean valida = principal.categoria == TablaSimbolos.Categoria.FUNCION
        && principal.tipo == TablaSimbolos.TipoDato.VACIO
        && principal.tiposParametros.isEmpty();
    if (!valida) { ManejadorErrores.reportarPrincipalInvalida(principal.declaracion); return false; }
    return true;
  }

  /**
   * En una AsignacionOLlamada, la posicion del hijo OperadorAsignacion (->, +-> o -->), que separa los
   * indices de arreglo del valor asignado; -1 si no hay (la sentencia termina en ++ o --). Tambien la
   * usa el generador de codigo intermedio.
   */
  static int posicionOperadorAsignacion(Node asignacion) {
    for (int i = 0; i < asignacion.jjtGetNumChildren(); i++) {
      if (asignacion.jjtGetChild(i) instanceof ASTOperadorAsignacion) return i;
    }
    return -1;
  }

  /**
   * Valor de un literal entero escrito tal cual (con un '-' delante o sin el); null si el nodo es
   * cualquier otra cosa. No se evalua nada mas (no hay plegado de constantes): `0 - 1` no cuenta.
   */
  private static Long enteroLiteral(Node nodo) {
    boolean negativo = false;
    if (nodo instanceof ASTUnaryMinus && nodo.jjtGetNumChildren() == 1) { negativo = true; nodo = nodo.jjtGetChild(0); }
    if (!(nodo instanceof ASTValorSimple) || nodo.jjtGetNumChildren() != 0) return null;
    Token t = ((SimpleNode) nodo).jjtGetFirstToken();
    if (t.kind != NUMERO_ENTERO) return null;
    try { long valor = Long.parseLong(t.image); return negativo ? -valor : valor; } catch (NumberFormatException e) { return null; }
  }

  // Mensajes de tipos incompatibles

  /** Largo maximo con el que se cita un operando en un mensaje; uno mas largo se recorta con "...". */
  private static final int LARGO_MAXIMO_CITA = 40;

  /** El texto del nodo tal como se escribio: sus tokens, con un espacio donde el fuente los separaba. */
  private static String textoDe(Node nodo) {
    Token t = ((SimpleNode) nodo).jjtGetFirstToken(), ultimo = ((SimpleNode) nodo).jjtGetLastToken();
    StringBuilder s = new StringBuilder();
    Token anterior = null;
    for (int i = 0; t != null && i < 200; i++) {
      if (anterior != null && (t.beginLine != anterior.endLine || t.beginColumn > anterior.endColumn + 1)) s.append(' ');
      s.append(t.image);
      if (t == ultimo) break;
      anterior = t;
      t = t.next;
    }
    return s.length() <= LARGO_MAXIMO_CITA ? s.toString() : s.substring(0, LARGO_MAXIMO_CITA - 3) + "...";
  }

  /** true si el nodo es solo el nombre de una variable o constante (sin indices ni llamada). */
  private static boolean esNombreSimple(Node nodo) {
    return nodo instanceof ASTValorSimple && nodo.jjtGetNumChildren() == 0
        && ((SimpleNode) nodo).jjtGetFirstToken().kind == IDENTIFICADOR;
  }

  /** `valor` si es un entero literal; si es un literal de arreglo, su primer entero literal; si no, null. */
  private static Node enteroLiteralDentro(Node valor) {
    if (enteroLiteral(valor) != null) return valor;
    if (valor instanceof ASTLiteralArreglo) {
      for (int i = 0; i < valor.jjtGetNumChildren(); i++) {
        Node encontrado = enteroLiteralDentro(valor.jjtGetChild(i));
        if (encontrado != null) return encontrado;
      }
    }
    return null;
  }

  /**
   * `valor` (de tipo `encontrado`) va donde se esperaba `esperado`: una inicializacion, asignacion,
   * retorno, argumento o caso de EVALUAR. Si se esperaba DEC y el valor es un entero literal (o un
   * literal de arreglo de enteros), el mensaje dice como escribirlo en DEC; `queSeEsperaba` explica
   * ese DEC ("'n3' es DEC"). En cualquier otro caso, el mensaje general de tipos incompatibles.
   */
  private static void reportarValorDeOtroTipo(Node valor, TablaSimbolos.TipoDato esperado, TablaSimbolos.TipoDato encontrado, String queSeEsperaba) {
    Node literal = esperado == TablaSimbolos.TipoDato.DEC && encontrado == TablaSimbolos.TipoDato.ENT ? enteroLiteralDentro(valor) : null;
    if (literal != null) {
      ManejadorErrores.reportarEnteroDondeSeEsperaDecimal(((SimpleNode) literal).jjtGetFirstToken(), String.valueOf(enteroLiteral(literal)), queSeEsperaba);
    } else {
      ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) valor).jjtGetFirstToken(), esperado, encontrado);
    }
  }

  /**
   * Los operandos de una cadena (`a + b - c`, los dos lados de una comparacion, los elementos de un
   * literal de arreglo) deben ser del mismo tipo, sin conversion ENT<->DEC. El tipo de referencia es
   * el de la mayoria; en un empate, el del primero, salvo que el primero sea un entero literal frente
   * a un DEC (`2 + x`): ahi lo distinto es el literal. Cada operando de otro tipo se reporta por su
   * nombre, o, si es un entero literal donde va DEC, con la forma correcta de escribirlo. `resto` dice
   * contra que se compara ("el resto de la operación"). Devuelve el tipo comun, o null si no lo hay.
   */
  private static TablaSimbolos.TipoDato exigirMismoTipo(List<Node> operandos, List<TablaSimbolos.TipoDato> tipos, String resto) {
    if (tipos.isEmpty()) return null;
    Map<TablaSimbolos.TipoDato, Integer> cuenta = new EnumMap<TablaSimbolos.TipoDato, Integer>(TablaSimbolos.TipoDato.class);
    for (TablaSimbolos.TipoDato t : tipos) cuenta.merge(t, 1, Integer::sum);
    TablaSimbolos.TipoDato referencia = tipos.get(0);
    if (cuenta.size() == 1) return referencia;
    for (TablaSimbolos.TipoDato t : tipos) if (cuenta.get(t) > cuenta.get(referencia)) referencia = t;
    if (referencia == TablaSimbolos.TipoDato.ENT && enteroLiteral(operandos.get(0)) != null
        && cuenta.getOrDefault(TablaSimbolos.TipoDato.DEC, 0).equals(cuenta.get(TablaSimbolos.TipoDato.ENT))) {
      referencia = TablaSimbolos.TipoDato.DEC;
    }
    for (int i = 0; i < operandos.size(); i++) {
      TablaSimbolos.TipoDato tipo = tipos.get(i);
      if (tipo == referencia) continue;
      Node operando = operandos.get(i);
      Token donde = ((SimpleNode) operando).jjtGetFirstToken();
      if (referencia == TablaSimbolos.TipoDato.DEC && tipo == TablaSimbolos.TipoDato.ENT && enteroLiteral(operando) != null) {
        ManejadorErrores.reportarEnteroDondeSeEsperaDecimal(donde, String.valueOf(enteroLiteral(operando)), resto + " es DEC");
      } else {
        ManejadorErrores.reportarOperandoDeOtroTipo(donde, textoDe(operando), tipo, resto, referencia, esNombreSimple(operando));
      }
    }
    return null;
  }

  /** true si el nodo es el literal 0 o 0.0 (con o sin signo). */
  private static boolean esCeroLiteral(Node nodo) {
    if (nodo instanceof ASTUnaryMinus && nodo.jjtGetNumChildren() == 1) nodo = nodo.jjtGetChild(0);
    if (!(nodo instanceof ASTValorSimple) || nodo.jjtGetNumChildren() != 0) return false;
    Token t = ((SimpleNode) nodo).jjtGetFirstToken();
    return (t.kind == NUMERO_ENTERO || t.kind == NUMERO_DECIMAL) && t.image.replace(".", "").matches("0+");
  }

  /**
   * Errores de ejecucion que ya se ven al compilar porque el operando derecho es un literal: divisor
   * 0 en '/' y '%', raiz de indice 0 en '//' y exponente negativo en una potencia entre ENT. Solo mira
   * el literal escrito; los casos que dependen de valores calculados se dejan para la ejecucion.
   */
  private void validarOperandoDerechoLiteral(Node izquierdo, Node derecho, TablaSimbolos.TipoDato tipo) {
    Token operador = operadorDespuesDe(izquierdo);
    if (operador == null) return;
    switch (operador.kind) {
      case DIV: case MODULO:
        if (esCeroLiteral(derecho)) ManejadorErrores.reportarDivisionPorCero(operador);
        break;
      case RAIZ:
        if (esCeroLiteral(derecho)) ManejadorErrores.reportarRaizDeIndiceCero(operador);
        break;
      case POTENCIA: {
        Long exponente = enteroLiteral(derecho);
        if (tipo == TablaSimbolos.TipoDato.ENT && exponente != null && exponente < 0) ManejadorErrores.reportarExponenteNegativoEnEntero(operador);
        break;
      }
      default:
        break;
    }
  }

  /**
   * Indice literal de un acceso a arreglo: negativo, o mayor o igual que el tamano literal declarado
   * para esa dimension. Si el tamano no es literal (se calcula al ejecutar) no se puede saber aqui.
   */
  private void validarIndiceLiteral(TablaSimbolos.Simbolo arreglo, int dimension, Node indice) {
    Long valor = enteroLiteral(indice);
    if (valor == null) return;
    Token donde = ((SimpleNode) indice).jjtGetFirstToken();
    if (valor < 0) { ManejadorErrores.reportarIndiceNegativo(donde, arreglo.nombre); return; }
    int[] tamanios = arreglo.tamanios;
    if (tamanios != null && dimension < tamanios.length && tamanios[dimension] > 0 && valor >= tamanios[dimension]) {
      ManejadorErrores.reportarIndiceFueraDeRango(donde, arreglo.nombre, valor, tamanios[dimension], dimension + 1, tamanios.length);
    }
  }

  /** true si el token `a` queda despues del token `b` en el archivo. */
  private static boolean despuesDe(Token a, Token b) {
    return a.beginLine > b.beginLine || (a.beginLine == b.beginLine && a.beginColumn > b.beginColumn);
  }

  // Utilidades de tokens y tipos

  private static Token siguiente(Token t) { return t == null ? null : t.next; }

  /** null si kind no es un token de tipo de dato valido (header roto por un error ya reportado). */
  private static TablaSimbolos.TipoDato tipoDeToken(int kind) {
    if (kind == TIPO_ENT) return TablaSimbolos.TipoDato.ENT;
    if (kind == TIPO_DEC) return TablaSimbolos.TipoDato.DEC;
    if (kind == TIPO_CAD) return TablaSimbolos.TipoDato.CAD;
    if (kind == TIPO_CAR) return TablaSimbolos.TipoDato.CAR;
    if (kind == TIPO_BOO) return TablaSimbolos.TipoDato.BOO;
    if (kind == TIPO_VACIO) return TablaSimbolos.TipoDato.VACIO;
    return null;
  }

  /**
   * Tamano de una dimension de arreglo (el `N` dentro de `[N]` en una declaracion), solo cuando
   * es un literal ENT simple: -1 si es cualquier otra cosa (variable, expresion, arreglo vacio
   * por un error sintactico), ya que en ese caso el tamano no se puede conocer en tiempo de
   * compilacion (ver decision de acotar el chequeo de tamanio de arreglo a este caso).
   */
  private static int tamanioLiteralDeDimension(Node dimension) {
    if (!(dimension instanceof ASTValorSimple) || dimension.jjtGetNumChildren() != 0) return -1;
    Token t = ((SimpleNode) dimension).jjtGetFirstToken();
    if (t.kind != NUMERO_ENTERO) return -1;
    try { return Integer.parseInt(t.image); } catch (NumberFormatException e) { return -1; }
  }

  /**
   * Declara una ASTDeclaracionVariable/ASTDeclaracionConstante en el scope actual de `tabla`.
   * Ambas producciones tienen la misma forma de hijos (un ASTDimensiones opcional primero, el
   * inicializador despues); solo cambia el token inicial (CONST antepone un token mas).
   */
  private void declararVariableOConstante(SimpleNode nodo, TablaSimbolos.Categoria categoria, boolean esConstante) {
    Token tipoToken = esConstante ? siguiente(nodo.jjtGetFirstToken()) : nodo.jjtGetFirstToken();
    Token idToken = siguiente(tipoToken);
    if (idToken == null) return; // header roto por un error sintactico ya reportado
    TablaSimbolos.TipoDato tipo = tipoDeToken(tipoToken.kind);
    if (tipo == null) return;
    Node dimensiones = nodo.jjtGetNumChildren() > 0 && nodo.jjtGetChild(0) instanceof ASTDimensiones
        ? nodo.jjtGetChild(0) : null;
    int aridad = dimensiones == null ? 0 : dimensiones.jjtGetNumChildren();
    int[] tamanios = null;
    if (aridad > 0) {
      tamanios = new int[aridad];
      for (int i = 0; i < aridad; i++) tamanios[i] = tamanioLiteralDeDimension(dimensiones.jjtGetChild(i));
    }
    TablaSimbolos.Simbolo simbolo = new TablaSimbolos.Simbolo(idToken.image, categoria, tipo, aridad, null, idToken, tamanios, tabla.ambitoActual());
    TablaSimbolos.Simbolo previo = tabla.declarar(simbolo);
    if (previo != null) ManejadorErrores.reportarSimboloDuplicado(idToken, categoria, previo);
  }

  /**
   * Visita los hijos restantes de una declaracion que declararVariableOConstante() no toca:
   * el ASTDimensiones, si es arreglo valida que cada dimension sea ENT, y el inicializador,
   * si lo hay compara su tipo contra el tipo declarado (antes de esto, "ENT x -> "hola";" no
   * daba ningun error). Resuelve el tipo declarado desde la tabla de simbolos en vez de
   * volver a derivarlo de los tokens, para no duplicar esa logica en dos lugares.
   */
  private void procesarHijosDeDeclaracion(SimpleNode nodo, boolean esConstante, Object data) {
    Token tipoToken = esConstante ? siguiente(nodo.jjtGetFirstToken()) : nodo.jjtGetFirstToken();
    Token idToken = siguiente(tipoToken);
    if (idToken == null) return; // header roto por un error sintactico ya reportado
    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    // simbolo.declaracion != idToken: esta declaracion perdio el choque contra una anterior,
    // ya reportado por declararVariableOConstante, y no es la duena del simbolo en la tabla.
    // Comparar su inicializador contra el tipo (o el tamanio de arreglo) de la otra
    // declaracion no aporta nada, solo duplica el error.
    boolean esDuenaDelSimbolo = simbolo != null && simbolo.declaracion == idToken;
    if (esDuenaDelSimbolo) anotar(nodo, simbolo.tipo, simbolo);

    // Mientras se visitan las dimensiones y el inicializador, el simbolo no puede leerse a si
    // mismo y (a nivel global) tampoco puede leer un global que se declara despues de el.
    Token globalAnterior = declaracionGlobalEnCurso;
    if (esDuenaDelSimbolo) {
      simbolo.enInicializacion = true;
      if (profundidadAnidamiento == 0) declaracionGlobalEnCurso = idToken;
    }
    try {
      visitarDimensionesEInicializador(nodo, simbolo, esDuenaDelSimbolo, data);
    } finally {
      if (esDuenaDelSimbolo) simbolo.enInicializacion = false;
      declaracionGlobalEnCurso = globalAnterior;
    }
  }

  private void visitarDimensionesEInicializador(SimpleNode nodo, TablaSimbolos.Simbolo simbolo, boolean esDuenaDelSimbolo, Object data) {
    int n = nodo.jjtGetNumChildren();
    int indice = 0;
    if (indice < n && nodo.jjtGetChild(indice) instanceof ASTDimensiones) {
      nodo.jjtGetChild(indice).jjtAccept(this, data); // valida que cada dimension sea ENT
      indice++;
    }
    if (indice >= n) return; // sin inicializador (variable sin valor; CONST no llega aqui, siempre lo exige)
    Node inicializador = nodo.jjtGetChild(indice);
    Object dataInicializador = esDuenaDelSimbolo && simbolo.aridadArreglo > 0 && inicializador instanceof ASTLiteralArreglo
        ? new ContextoTamanioArreglo(simbolo.tamanios, 1, simbolo.nombre) : data;
    TablaSimbolos.TipoDato tipoInicializador = (TablaSimbolos.TipoDato) inicializador.jjtAccept(this, dataInicializador);
    // Un arreglo solo se inicializa con un literal '[...]' y una variable simple nunca con uno.
    // Si la forma no coincide, el choque de tipos de abajo seria un segundo error por lo mismo.
    boolean esLiteralArreglo = inicializador instanceof ASTLiteralArreglo;
    if (esDuenaDelSimbolo && simbolo.aridadArreglo > 0 && !esLiteralArreglo) {
      ManejadorErrores.reportarArregloRequiereLiteral(((SimpleNode) inicializador).jjtGetFirstToken(), simbolo.nombre, simbolo.aridadArreglo);
      return;
    }
    if (esDuenaDelSimbolo && simbolo.aridadArreglo == 0 && esLiteralArreglo) {
      ManejadorErrores.reportarLiteralArregloEnEscalar(((SimpleNode) inicializador).jjtGetFirstToken(), simbolo.nombre);
      return;
    }
    if (esDuenaDelSimbolo && tipoInicializador != null && tipoInicializador != simbolo.tipo) {
      reportarValorDeOtroTipo(inicializador, simbolo.tipo, tipoInicializador, "'" + simbolo.nombre + "' es " + simbolo.tipo);
    }
  }

  /** Texto con el que los mensajes de una cadena aritmetica nombran a los demas operandos. */
  private static final String RESTO_OPERACION = "el resto de la operación";

  /**
   * Regla comun a ASTExpresion/ASTTermino/ASTFactor (+/-, * / %, ** //): todos los operandos
   * deben ser del mismo tipo (sin coercion ENT<->DEC; ver exigirMismoTipo(), que nombra el operando
   * distinto) y ese tipo debe admitir aritmetica (ENT o DEC). Si algun operando ya devolvio null
   * (error mas abajo), no se reporta un segundo error encima: se corta en silencio.
   */
  private TablaSimbolos.TipoDato tipoCadenaAritmetica(SimpleNode node, Object data) {
    List<Node> operandos = new ArrayList<Node>();
    List<TablaSimbolos.TipoDato> tipos = new ArrayList<TablaSimbolos.TipoDato>();
    boolean huboError = false;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoHijo = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, data);
      if (tipoHijo == null) { huboError = true; continue; }
      operandos.add(hijo);
      tipos.add(tipoHijo);
      if (i > 0) validarOperandoDerechoLiteral(node.jjtGetChild(i - 1), hijo, tipoHijo);
    }
    TablaSimbolos.TipoDato tipoComun = exigirMismoTipo(operandos, tipos, RESTO_OPERACION);
    if (huboError || tipoComun == null) return null;
    if (tipoComun != TablaSimbolos.TipoDato.ENT && tipoComun != TablaSimbolos.TipoDato.DEC) {
      ManejadorErrores.reportarOperandoNoNumerico(node.jjtGetFirstToken(), tipoComun);
      return null;
    }
    return tipoComun;
  }

  /**
   * Regla de ASTExpresion (+/-): igual que tipoCadenaAritmetica(), pero '+' ademas concatena
   * si cualquiera de los dos lados es CAD; el resultado de esa
   * concatenacion es CAD, sea cual sea el otro tipo. '-' nunca acepta CAD, ni tampoco '+' cuando
   * ninguno de los dos lados es CAD (ahi rige la regla numerica estricta de siempre). Primero se
   * visitan todos los operandos; sin ningun CAD, la cadena se revisa como una aritmetica cualquiera.
   */
  private TablaSimbolos.TipoDato tipoExpresion(ASTExpresion node, Object data) {
    List<Node> operandos = new ArrayList<Node>();
    List<Integer> posiciones = new ArrayList<Integer>(); // indice de cada operando entre los hijos del nodo
    List<TablaSimbolos.TipoDato> tipos = new ArrayList<TablaSimbolos.TipoDato>();
    boolean huboError = false;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoHijo = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, data);
      if (tipoHijo == null) { huboError = true; continue; }
      operandos.add(hijo);
      posiciones.add(i);
      tipos.add(tipoHijo);
    }
    if (operandos.isEmpty()) return null;

    if (!tipos.contains(TablaSimbolos.TipoDato.CAD)) {
      TablaSimbolos.TipoDato tipoComun = exigirMismoTipo(operandos, tipos, RESTO_OPERACION);
      if (tipoComun == null) return null;
      if (tipoComun != TablaSimbolos.TipoDato.ENT && tipoComun != TablaSimbolos.TipoDato.DEC) {
        for (int k = 1; k < operandos.size(); k++) {
          ManejadorErrores.reportarOperandoNoNumerico(operadorDespuesDe(node.jjtGetChild(posiciones.get(k) - 1)), tipoComun);
        }
        return null;
      }
      return huboError ? null : tipoComun;
    }

    // Con algun CAD: de izquierda a derecha, '+' con un CAD de cualquier lado pasa a ser CAD.
    TablaSimbolos.TipoDato acumulado = tipos.get(0);
    for (int k = 1; k < operandos.size(); k++) {
      Node hijo = operandos.get(k);
      TablaSimbolos.TipoDato tipoHijo = tipos.get(k);
      Token operador = operadorDespuesDe(node.jjtGetChild(posiciones.get(k) - 1));
      if (operador.kind == SUMA && (acumulado == TablaSimbolos.TipoDato.CAD || tipoHijo == TablaSimbolos.TipoDato.CAD)) {
        acumulado = TablaSimbolos.TipoDato.CAD;
        continue;
      }
      if (acumulado == TablaSimbolos.TipoDato.CAD || tipoHijo == TablaSimbolos.TipoDato.CAD) {
        // Un CAD fuera de '+' (p. ej. "x" + 1 - d): el problema es el operador, no el otro tipo.
        ManejadorErrores.reportarOperandoNoNumerico(operador, TablaSimbolos.TipoDato.CAD);
        huboError = true;
        continue;
      }
      if (acumulado != tipoHijo) {
        ManejadorErrores.reportarOperandoDeOtroTipo(((SimpleNode) hijo).jjtGetFirstToken(), textoDe(hijo), tipoHijo,
            RESTO_OPERACION, acumulado, esNombreSimple(hijo));
        huboError = true;
        continue;
      }
      if (acumulado != TablaSimbolos.TipoDato.ENT && acumulado != TablaSimbolos.TipoDato.DEC) {
        ManejadorErrores.reportarOperandoNoNumerico(operador, acumulado);
        huboError = true;
      }
    }
    return huboError ? null : acumulado;
  }

  /**
   * Lo que el analisis deja colgado de un nodo del AST (con SimpleNode.jjtSetValue) para que el
   * generador de codigo intermedio no tenga que recalcularlo. `tipo` esta en los nodos de expresion
   * y `simbolo` en los que nombran algo; cualquiera de los dos puede ser null. Solo es confiable
   * cuando el analisis termino con 0 errores.
   */
  public static final class Anotacion {
    public final TablaSimbolos.TipoDato tipo;
    public final TablaSimbolos.Simbolo simbolo;

    public Anotacion(TablaSimbolos.TipoDato tipo, TablaSimbolos.Simbolo simbolo) {
      this.tipo = tipo; this.simbolo = simbolo;
    }

    /** Anotacion de un nodo ya analizado, o null si el analisis no paso por el. */
    public static Anotacion de(Node nodo) {
      Object valor = ((SimpleNode) nodo).jjtGetValue();
      return valor instanceof Anotacion ? (Anotacion) valor : null;
    }
  }

  /**
   * Cuelga de un nodo su Anotacion y devuelve `tipo`, para poder escribir
   * `return anotar(node, tipo, null);` sin cambiar lo que sube por el arbol.
   */
  private static TablaSimbolos.TipoDato anotar(SimpleNode nodo, TablaSimbolos.TipoDato tipo, TablaSimbolos.Simbolo simbolo) {
    nodo.jjtSetValue(new Anotacion(tipo, simbolo));
    return tipo;
  }

  /** Regla comun a SI/MIENTRAS/REPETIR/HACER/ExpresionLogica: la condicion debe ser BOO estricto (sin truthy). */
  private void exigirBoo(TablaSimbolos.TipoDato tipo, Token token) {
    if (tipo != null && tipo != TablaSimbolos.TipoDato.BOO) {
      ManejadorErrores.reportarCondicionNoBooleana(token, tipo);
    }
  }

  /**
   * EVALUAR no envuelve cada caso CUANDO/PRED en un nodo propio: la expresion de un CUANDO y
   * las Sentencia() de su cuerpo quedan como hermanas directas de ASTEstructuraEvaluar. Para
   * saber si `actual` es la expresion de un CUANDO nuevo o una sentencia mas del caso
   * anterior, mira los tokens crudos entre ambos: si aparece un CUANDO o un PRED en el medio,
   * `actual` empieza un caso nuevo. Devuelve el kind del marcador, o -1 si no hay ninguno.
   */
  static int marcadorEntre(Node anterior, Node actual) {
    Token t = ((SimpleNode) anterior).jjtGetLastToken().next;
    Token limite = ((SimpleNode) actual).jjtGetFirstToken();
    while (t != null && t != limite) {
      if (t.kind == CUANDO || t.kind == PRED) return t.kind;
      t = t.next;
    }
    return -1;
  }

  /**
   * Las cuatro partes de un REPETIR. Un header roto por un error sintactico puede dejar cualquier
   * subconjunto de ellas, asi que se clasifican por tipo de nodo; Condicion() es "lo que no es
   * ninguno de los otros tres", ya que puede resolver a muchos tipos de nodo y no tiene uno propio.
   */
  static final class PartesRepetir {
    Node declaracion, condicion, paso, cuerpo;
  }

  static PartesRepetir partesDeRepetir(Node repetir) {
    PartesRepetir partes = new PartesRepetir();
    for (int i = 0; i < repetir.jjtGetNumChildren(); i++) {
      Node hijo = repetir.jjtGetChild(i);
      if (hijo instanceof ASTDeclaracionVariable) partes.declaracion = hijo;
      else if (hijo instanceof ASTPasoRepetir) partes.paso = hijo;
      else if (hijo instanceof ASTBloque) partes.cuerpo = hijo;
      else partes.condicion = hijo;
    }
    return partes;
  }

  /** {cuerpo, condicion} de un HACER; cada uno es null si el header roto no lo dejo. */
  static Node[] partesDeHacer(Node hacer) {
    Node cuerpo = null, condicion = null;
    for (int i = 0; i < hacer.jjtGetNumChildren(); i++) {
      Node hijo = hacer.jjtGetChild(i);
      if (hijo instanceof ASTBloque) cuerpo = hijo; else condicion = hijo;
    }
    return new Node[] { cuerpo, condicion };
  }

  /**
   * Un caso de EVALUAR: `valor` es la expresion de su CUANDO (null en el PRED) y `sentencias` las que
   * le siguen. `esPred` distingue el PRED de unas sentencias sueltas antes del primer caso, que solo
   * pueden existir con un header roto.
   */
  static final class CasoEvaluar {
    final Node valor;
    final boolean esPred;
    final List<Node> sentencias = new ArrayList<Node>();
    CasoEvaluar(Node valor, boolean esPred) { this.valor = valor; this.esPred = esPred; }
  }

  /**
   * EVALUAR no agrupa los casos en el arbol: la expresion de cada CUANDO y las sentencias quedan como
   * hijos planos (el primer hijo es la expresion evaluada), y marcadorEntre() dice donde empieza cada
   * caso. Un PRED sin sentencias no deja ningun hijo, asi que se busca tambien mirando los tokens que
   * quedan despues del ultimo hijo. Tambien lo usa el generador de codigo intermedio.
   */
  static List<CasoEvaluar> casosDeEvaluar(Node evaluar) {
    List<CasoEvaluar> casos = new ArrayList<CasoEvaluar>();
    int n = evaluar.jjtGetNumChildren();
    if (n == 0) return casos;
    CasoEvaluar actual = null;
    for (int i = 1; i < n; i++) {
      Node hijo = evaluar.jjtGetChild(i);
      int marcador = marcadorEntre(evaluar.jjtGetChild(i - 1), hijo);
      if (marcador == CUANDO) {
        actual = new CasoEvaluar(hijo, false);
        casos.add(actual);
        continue; // este hijo es la expresion del caso, no una sentencia
      }
      if (marcador == PRED) {
        actual = new CasoEvaluar(null, true);
        casos.add(actual);
      } else if (actual == null) {
        actual = new CasoEvaluar(null, false);
        casos.add(actual);
      }
      actual.sentencias.add(hijo);
    }
    boolean ultimoEsPred = !casos.isEmpty() && casos.get(casos.size() - 1).esPred;
    if (!ultimoEsPred) {
      Token fin = ((SimpleNode) evaluar).jjtGetLastToken();
      for (Token t = ((SimpleNode) evaluar.jjtGetChild(n - 1)).jjtGetLastToken().next; t != null && t != fin; t = t.next) {
        if (t.kind == PRED) { casos.add(new CasoEvaluar(null, true)); break; }
      }
    }
    return casos;
  }

  /**
   * Clave comparable para detectar CUANDO duplicados. Solo aplica cuando la expresion del
   * caso es un literal simple con un token de tipo ENT/DEC/CAD/CAR/BOO; un caso con variable
   * o expresion arbitraria no se puede comparar en tiempo de compilacion y se deja pasar sin
   * marcar. Se antepone el kind del token para no confundir la CADENA "1" con el NUMERO_ENTERO 1.
   */
  private static String valorLiteralDeCaso(Node nodo) {
    if (!(nodo instanceof ASTValorSimple) || nodo.jjtGetNumChildren() != 0) return null;
    Token t = ((SimpleNode) nodo).jjtGetFirstToken();
    switch (t.kind) {
      case NUMERO_ENTERO: case NUMERO_DECIMAL: case CADENA: case CARACTER: case VERDADERO: case FALSO:
        return t.kind + ":" + t.image;
      default:
        return null;
    }
  }

  /**
   * Valida una llamada a funcion, usada como sentencia (ASTAsignacionOLlamada, ignorando el
   * retorno) o como valor (ASTLlamadaFuncion). Los argumentos son los hijos de `nodo`, porque
   * LlamadaFuncionSinId() es fontaneria y sus Expresion() caen directo en el padre. Devuelve
   * el tipo de retorno si todo esta bien, o null si hubo error, pero siempre visita todos los
   * argumentos para no perder otros errores dentro de ellos.
   */
  private TablaSimbolos.TipoDato validarLlamadaFuncion(Token idToken, SimpleNode nodo, Object data) {
    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(idToken); nodo.childrenAccept(this, data); return null; }
    simbolo.marcarUsado();
    if (simbolo.categoria != TablaSimbolos.Categoria.FUNCION) {
      ManejadorErrores.reportarLlamadaANoFuncion(idToken, simbolo.categoria);
      nodo.childrenAccept(this, data);
      return null;
    }
    anotar(nodo, simbolo.tipo, simbolo); // la funcion llamada y su tipo de retorno
    int numArgs = nodo.jjtGetNumChildren();
    int numParams = simbolo.tiposParametros.size();
    boolean huboError = numArgs != numParams;
    if (huboError) ManejadorErrores.reportarNumeroArgumentosIncorrecto(idToken, simbolo.nombre, numArgs, numParams);
    int limite = Math.min(numArgs, numParams);
    for (int i = 0; i < limite; i++) {
      Node argumento = nodo.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoArgumento = (TablaSimbolos.TipoDato) argumento.jjtAccept(this, data);
      TablaSimbolos.TipoDato tipoEsperado = simbolo.tiposParametros.get(i);
      if (tipoArgumento != null && tipoArgumento != tipoEsperado) {
        reportarValorDeOtroTipo(argumento, tipoEsperado, tipoArgumento, "el parámetro " + (i + 1) + " de '" + simbolo.nombre + "' es " + tipoEsperado);
        huboError = true;
      }
    }
    for (int i = limite; i < numArgs; i++) nodo.jjtGetChild(i).jjtAccept(this, data); // argumentos sobrantes: igual se visitan
    return huboError ? null : simbolo.tipo;
  }

  // Pasada 1: registro global

  private void registrarDeclaracionesGlobales(ASTPrograma raiz) {
    for (int i = 0; i < raiz.jjtGetNumChildren(); i++) {
      Node hijo = raiz.jjtGetChild(i);
      if (hijo instanceof ASTDeclaracionVariable) {
        declararVariableOConstante((ASTDeclaracionVariable) hijo, TablaSimbolos.Categoria.VARIABLE, false);
      } else if (hijo instanceof ASTDeclaracionConstante) {
        declararVariableOConstante((ASTDeclaracionConstante) hijo, TablaSimbolos.Categoria.CONSTANTE, true);
      } else if (hijo instanceof ASTDeclaracionFuncion) {
        registrarFirmaFuncion((ASTDeclaracionFuncion) hijo);
      }
    }
  }

  private void registrarFirmaFuncion(ASTDeclaracionFuncion nodo) {
    Token retornoToken = siguiente(nodo.jjtGetFirstToken());
    Token nombreToken = siguiente(retornoToken);
    if (nombreToken == null) return; // header roto por un error sintactico ya reportado
    TablaSimbolos.TipoDato tipoRetorno = tipoDeToken(retornoToken.kind);
    if (tipoRetorno == null) return;
    List<TablaSimbolos.TipoDato> tiposParametros = new ArrayList<TablaSimbolos.TipoDato>();
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) {
      Node hijo = nodo.jjtGetChild(i);
      if (!(hijo instanceof ASTParametro)) break; // los ASTParametro siempre preceden al ASTBloque del cuerpo
      Token tipoParametro = ((ASTParametro) hijo).jjtGetFirstToken();
      TablaSimbolos.TipoDato tipo = tipoDeToken(tipoParametro.kind);
      if (tipo == null) return; // parametro roto por un error sintactico ya reportado
      tiposParametros.add(tipo);
    }
    TablaSimbolos.Simbolo simbolo = new TablaSimbolos.Simbolo(nombreToken.image, TablaSimbolos.Categoria.FUNCION,
        tipoRetorno, 0, tiposParametros, nombreToken, null, tabla.ambitoActual());
    TablaSimbolos.Simbolo previo = tabla.declarar(simbolo);
    if (previo != null) ManejadorErrores.reportarSimboloDuplicado(nombreToken, TablaSimbolos.Categoria.FUNCION, previo);
  }

  // Pasada 2: visitor

  @Override public Object visit(SimpleNode node, Object data) {
    return node.childrenAccept(this, data);
  }

  @Override public Object visit(ASTPrograma node, Object data) {
    registrarDeclaracionesGlobales(node);
    return node.childrenAccept(this, data); // ahora si baja a cada ASTDeclaracionFuncion (y a los inicializadores globales)
  }

  @Override public Object visit(ASTElementoGlobalInvalido node, Object data) { return null; } // ya reportado por el parser, sin contenido util

  @Override public Object visit(ASTDeclaracionVariable node, Object data) {
    // A nivel global la pasada 1 ya la declaro; declarar de nuevo aqui la haria chocar
    // consigo misma. profundidadAnidamiento > 0 == estamos dentro de una funcion/bloque.
    if (profundidadAnidamiento > 0) declararVariableOConstante(node, TablaSimbolos.Categoria.VARIABLE, false);
    procesarHijosDeDeclaracion(node, false, data);
    return null;
  }

  @Override public Object visit(ASTDeclaracionConstante node, Object data) {
    if (profundidadAnidamiento > 0) declararVariableOConstante(node, TablaSimbolos.Categoria.CONSTANTE, true);
    procesarHijosDeDeclaracion(node, true, data);
    return null;
  }

  @Override public Object visit(ASTDimensiones node, Object data) {
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipo = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, data);
      if (tipo != null && tipo != TablaSimbolos.TipoDato.ENT) {
        ManejadorErrores.reportarExpresionDebeSerEntera(((SimpleNode) hijo).jjtGetFirstToken(), tipo, "una dimensión de arreglo");
      }
      Long tamano = enteroLiteral(hijo);
      if (tamano != null && tamano <= 0) ManejadorErrores.reportarTamanioDeArregloNoPositivo(((SimpleNode) hijo).jjtGetFirstToken());
    }
    return null;
  }

  @Override public Object visit(ASTDeclaracionFuncion node, Object data) {
    Token retornoToken = siguiente(node.jjtGetFirstToken());
    Token nombreToken = siguiente(retornoToken);
    TablaSimbolos.TipoDato tipoRetornoAnterior = tipoRetornoFuncionActual;
    tipoRetornoFuncionActual = retornoToken == null ? null : tipoDeToken(retornoToken.kind);
    if (nombreToken != null) entrarScope(nombreToken.image); else entrarScope(); // header roto: sin nombre, hereda el ambito de afuera
    node.childrenAccept(this, data); // declara cada ASTParametro y luego visita el ASTBloque del cuerpo
    salirScope();
    if (nombreToken != null) {
      TablaSimbolos.Simbolo funcion = tabla.resolver(nombreToken.image);
      if (funcion != null && funcion.declaracion == nombreToken) anotar(node, funcion.tipo, funcion);
    }
    // Ningun camino de ejecucion garantiza un RET (ver bloqueSiempreTermina): solo tiene sentido
    // exigirlo cuando se conoce el tipo de retorno declarado (header no roto) y no es VACIO.
    if (tipoRetornoFuncionActual != null && tipoRetornoFuncionActual != TablaSimbolos.TipoDato.VACIO && nombreToken != null) {
      Node cuerpo = cuerpoDeFuncion(node);
      if (cuerpo != null && !bloqueSiempreTermina(cuerpo)) {
        ManejadorErrores.reportarFuncionSinRetornoGarantizado(nombreToken, tipoRetornoFuncionActual);
      }
    }
    tipoRetornoFuncionActual = tipoRetornoAnterior;
    return null;
  }

  private static Node cuerpoDeFuncion(Node declaracionFuncion) {
    for (int i = 0; i < declaracionFuncion.jjtGetNumChildren(); i++) {
      Node hijo = declaracionFuncion.jjtGetChild(i);
      if (hijo instanceof ASTBloque) return hijo;
    }
    return null; // header roto por un error sintactico ya reportado, sin cuerpo util
  }

  /**
   * `true` si `bloque` (un ASTBloque, o las sentencias de un caso de EVALUAR) garantiza
   * alcanzar un RET antes del final. Basta con que una sola sentencia lo garantice; el
   * codigo muerto que venga despues es un hueco aparte, no cubierto aqui.
   */
  private boolean bloqueSiempreTermina(Node bloque) {
    for (int i = 0; i < bloque.jjtGetNumChildren(); i++) {
      if (sentenciaSiempreTermina(bloque.jjtGetChild(i))) return true;
    }
    return false;
  }

  /**
   * `true` si esta sentencia por si sola garantiza terminar en un RET. JER tiene control de
   * flujo estructurado, asi que esto es un predicado recursivo sobre la forma del AST, sin
   * grafo de flujo real. MIENTRAS/REPETIR siempre son `false` (no se garantiza ni una
   * iteracion; no se agrega el caso especial de "MIENTRAS VERDADERO" para no ampliar el
   * alcance). TERMINAR tambien es `false`: solo sale del bucle o del EVALUAR.
   */
  private boolean sentenciaSiempreTermina(Node sentencia) {
    if (sentencia instanceof ASTSentenciaRetorno) return true;
    if (sentencia instanceof ASTBloque) return bloqueSiempreTermina(sentencia);
    if (sentencia instanceof ASTEstructuraSi) return siSiempreTermina((ASTEstructuraSi) sentencia);
    if (sentencia instanceof ASTEstructuraHacer) return hacerSiempreTermina((ASTEstructuraHacer) sentencia);
    if (sentencia instanceof ASTEstructuraEvaluar) return evaluarSiempreTermina((ASTEstructuraEvaluar) sentencia);
    return false;
  }

  /**
   * SI garantiza terminar solo si tiene un SINO final y todas las ramas garantizan terminar.
   * Usa el mismo patron de clasificar el primer hijo por tipo que visit(ASTEstructuraSi),
   * para tolerar un header roto por recuperacion de errores.
   */
  private boolean siSiempreTermina(ASTEstructuraSi node) {
    int n = node.jjtGetNumChildren();
    if (n == 0) return false;
    int i = 0;
    if (!(node.jjtGetChild(0) instanceof ASTBloque)) i = 1; // salta la condicion
    if (i >= n) return false; // header roto, ni siquiera quedo el bloque "then"
    if (!sentenciaSiempreTermina(node.jjtGetChild(i++))) return false; // then
    if (i >= n) return false; // sin SINO: el camino "condicion falsa" nunca se puede garantizar
    return sentenciaSiempreTermina(node.jjtGetChild(i)); // ASTBloque (sino) o ASTEstructuraSi (sino si)
  }

  /** HACER ... MIENTRAS ejecuta su cuerpo al menos una vez: garantiza terminar si el cuerpo lo garantiza. */
  private boolean hacerSiempreTermina(ASTEstructuraHacer node) {
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      if (hijo instanceof ASTBloque) return bloqueSiempreTermina(hijo);
    }
    return false; // header roto, no se encontro el cuerpo
  }

  /**
   * EVALUAR garantiza terminar solo si tiene PRED y cada caso (cada CUANDO mas el PRED)
   * garantiza terminar (los casos salen de casosDeEvaluar()).
   */
  private boolean evaluarSiempreTermina(ASTEstructuraEvaluar node) {
    boolean tienePred = false;
    boolean todosLosCasosTerminan = true;
    for (CasoEvaluar caso : casosDeEvaluar(node)) {
      if (caso.valor == null && !caso.esPred) continue; // sentencias sueltas de un header roto: no son un caso
      if (caso.esPred) tienePred = true;
      boolean termina = false;
      for (Node sentencia : caso.sentencias) if (sentenciaSiempreTermina(sentencia)) termina = true;
      todosLosCasosTerminan &= termina;
    }
    return tienePred && todosLosCasosTerminan;
  }

  @Override public Object visit(ASTParametro node, Object data) {
    Token tipoToken = node.jjtGetFirstToken();
    Token idToken = siguiente(tipoToken);
    if (idToken == null) return null; // roto por un error sintactico ya reportado
    TablaSimbolos.TipoDato tipo = tipoDeToken(tipoToken.kind);
    if (tipo == null) return null;
    TablaSimbolos.Simbolo simbolo = new TablaSimbolos.Simbolo(idToken.image, TablaSimbolos.Categoria.PARAMETRO, tipo, 0, null, idToken, null, tabla.ambitoActual());
    TablaSimbolos.Simbolo previo = tabla.declarar(simbolo);
    if (previo != null) ManejadorErrores.reportarSimboloDuplicado(idToken, TablaSimbolos.Categoria.PARAMETRO, previo);
    else anotar(node, tipo, simbolo);
    return null;
  }

  @Override public Object visit(ASTBloque node, Object data) {
    entrarScope();
    Object resultado = node.childrenAccept(this, data);
    salirScope();
    return resultado;
  }

  @Override public Object visit(ASTSentenciaImprimir node, Object data) {
    return node.childrenAccept(this, data); // sin restriccion de tipo por ahora; solo visita su expresion
  }

  @Override public Object visit(ASTSentenciaObtener node, Object data) {
    Token idToken = siguiente(node.jjtGetFirstToken()); // OBT -> identificador
    if (idToken == null || idToken.kind != JERCompilerConstants.IDENTIFICADOR) return null; // roto por un error sintactico ya reportado ("OBT ;")
    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(idToken); return null; }
    simbolo.marcarUsado();
    anotar(node, simbolo.tipo, simbolo);
    if (simbolo.categoria == TablaSimbolos.Categoria.FUNCION) { ManejadorErrores.reportarUsoDeFuncionComoValor(idToken); return null; }
    if (simbolo.categoria == TablaSimbolos.Categoria.CONSTANTE) { ManejadorErrores.reportarAsignacionAConstante(idToken, simbolo.nombre); return null; }
    if (simbolo.aridadArreglo > 0) { ManejadorErrores.reportarAridadArregloIncorrecta(idToken, simbolo.nombre, 0, simbolo.aridadArreglo); return null; }
    return null;
  }

  @Override public Object visit(ASTSentenciaTerminar node, Object data) {
    if (profundidadBucle == 0 && profundidadCasoEvaluar == 0) {
      ManejadorErrores.reportarTerminarFueraDeContexto(node.jjtGetFirstToken());
    }
    return null;
  }

  @Override public Object visit(ASTSentenciaRetorno node, Object data) {
    Node valor = node.jjtGetChild(0); // Condicion() es obligatoria en la gramatica: siempre hay exactamente 1 hijo
    TablaSimbolos.TipoDato tipoValor = (TablaSimbolos.TipoDato) valor.jjtAccept(this, data);
    if (tipoRetornoFuncionActual == null || tipoValor == null) return null; // tipo de retorno desconocido por un error ya reportado
    if (tipoRetornoFuncionActual == TablaSimbolos.TipoDato.VACIO) {
      ManejadorErrores.reportarRetornoConValorEnFuncionVacio(node.jjtGetFirstToken());
      return null;
    }
    if (tipoValor != tipoRetornoFuncionActual) {
      reportarValorDeOtroTipo(valor, tipoRetornoFuncionActual, tipoValor, "la función devuelve " + tipoRetornoFuncionActual);
    }
    return null;
  }

  @Override public Object visit(ASTSentenciaInvalida node, Object data) { return null; } // ya reportado por el parser, sin contenido util

  @Override public Object visit(ASTAsignacionOLlamada node, Object data) {
    // El token que sigue al identificador dice, sin ambiguedad, cual de las 4 formas de
    // AsignacionOLlamada() es esta.
    // '(' es una llamada usada como sentencia.
    // '[' es un acceso a arreglo, que a su vez termina en ++/--/asignacion.
    // INC/DEC_OP es un incremento o decremento de un escalar.
    // Cualquier otro (ASIGNACION/ASIG_INC/ASIG_DEC) es una asignacion directa a un escalar.
    Token idToken = node.jjtGetFirstToken();
    Token siguienteToken = siguiente(idToken);
    if (siguienteToken == null) return null; // roto por un error sintactico ya reportado

    if (siguienteToken.kind == APERTURA_PAREN) {
      validarLlamadaFuncion(idToken, node, data); // sentencia de llamada: se ignora el valor de retorno
      return null;
    }

    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(idToken); node.childrenAccept(this, data); return null; }
    simbolo.marcarUsado();
    anotar(node, simbolo.tipo, simbolo);
    if (simbolo.categoria == TablaSimbolos.Categoria.FUNCION) { ManejadorErrores.reportarUsoDeFuncionComoValor(idToken); node.childrenAccept(this, data); return null; }

    // Un ASTOperadorAsignacion entre los hijos marca donde termina la lista de indices de
    // arreglo y empieza el valor asignado. Si no aparece ninguno, todos los hijos son indices
    // y la sentencia termina en ++/-- (0 hijos es "i++;" sin arreglo de por medio).
    int indiceOperador = posicionOperadorAsignacion(node);
    int numIndices = indiceOperador >= 0 ? indiceOperador : node.jjtGetNumChildren();

    for (int i = 0; i < numIndices; i++) {
      Node indice = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoIndice = (TablaSimbolos.TipoDato) indice.jjtAccept(this, data);
      if (tipoIndice != null && tipoIndice != TablaSimbolos.TipoDato.ENT) {
        ManejadorErrores.reportarExpresionDebeSerEntera(((SimpleNode) indice).jjtGetFirstToken(), tipoIndice, "un índice de arreglo");
      }
      validarIndiceLiteral(simbolo, i, indice);
    }
    boolean aridadOk = numIndices == simbolo.aridadArreglo;
    if (!aridadOk) ManejadorErrores.reportarAridadArregloIncorrecta(idToken, simbolo.nombre, numIndices, simbolo.aridadArreglo);

    boolean esConstante = simbolo.categoria == TablaSimbolos.Categoria.CONSTANTE;
    if (esConstante) ManejadorErrores.reportarAsignacionAConstante(idToken, simbolo.nombre);

    if (indiceOperador < 0) {
      // Termina en ++/--: el elemento/variable debe ser numerico.
      if (aridadOk && !esConstante && simbolo.tipo != TablaSimbolos.TipoDato.ENT && simbolo.tipo != TablaSimbolos.TipoDato.DEC) {
        ManejadorErrores.reportarOperandoNoNumerico(idToken, simbolo.tipo);
      }
      return null;
    }

    Node valor = node.jjtGetChild(indiceOperador + 1);
    TablaSimbolos.TipoDato tipoValor = (TablaSimbolos.TipoDato) valor.jjtAccept(this, data);
    if (aridadOk && !esConstante && tipoValor != null && tipoValor != simbolo.tipo) {
      reportarValorDeOtroTipo(valor, simbolo.tipo, tipoValor, "'" + simbolo.nombre + "' es " + simbolo.tipo);
    }
    return null;
  }

  @Override public Object visit(ASTOperadorAsignacion node, Object data) { return null; } // fontaneria, sin hijos ni chequeo propio

  @Override public Object visit(ASTEstructuraSi node, Object data) {
    // Si la condicion fallo y la recuperacion no encontro '{', el header entero se descarta
    // y el nodo puede quedar con menos hijos de los que la gramatica "feliz" sugiere (0, 1,
    // 2 o 3). Igual que en ASTEstructuraRepetir/Hacer, se clasifica el primer hijo por tipo
    // en vez de asumir una posicion fija: si no es un ASTBloque, es la condicion, y en ese
    // caso, por como esta escrita la gramatica, el ASTBloque "then" siempre le sigue
    // inmediatamente.
    //
    // El tercer hijo, si existe, puede ser un ASTBloque (SINO simple) o otro ASTEstructuraSi
    // (SINO SI encadenado). No hace falta distinguirlos: jjtAccept() ya despacha
    // polimorficamente al visit() correcto sin necesitar instanceof ni node.jjtGetValue().
    int n = node.jjtGetNumChildren();
    if (n == 0) return null; // header roto sin recuperacion util
    int i = 0;
    if (!(node.jjtGetChild(0) instanceof ASTBloque)) {
      Node condicion = node.jjtGetChild(0);
      exigirBoo((TablaSimbolos.TipoDato) condicion.jjtAccept(this, data), ((SimpleNode) condicion).jjtGetFirstToken());
      i = 1;
    }
    if (i < n) node.jjtGetChild(i++).jjtAccept(this, data); // ASTBloque (then)
    if (i < n) node.jjtGetChild(i).jjtAccept(this, data); // ASTBloque (sino) o ASTEstructuraSi (sino si)
    return null;
  }

  @Override public Object visit(ASTEstructuraMientras node, Object data) {
    // Mismo razonamiento que ASTEstructuraSi: sin SINO que confunda el conteo, aqui solo hace
    // falta distinguir "hay condicion" de "el header fallo y solo quedo el ASTBloque".
    int n = node.jjtGetNumChildren();
    if (n == 0) return null;
    int i = 0;
    if (!(node.jjtGetChild(0) instanceof ASTBloque)) {
      Node condicion = node.jjtGetChild(0);
      exigirBoo((TablaSimbolos.TipoDato) condicion.jjtAccept(this, data), ((SimpleNode) condicion).jjtGetFirstToken());
      i = 1;
    }
    if (i < n) {
      profundidadBucle++;
      node.jjtGetChild(i).jjtAccept(this, data);
      profundidadBucle--;
    }
    return null;
  }

  @Override public Object visit(ASTEstructuraRepetir node, Object data) {
    PartesRepetir partes = partesDeRepetir(node); // un header roto puede dejar solo algunas
    Node declaracion = partes.declaracion, condicion = partes.condicion, paso = partes.paso, cuerpo = partes.cuerpo;
    entrarScope(); // la variable de control vive en su propio scope, junto con el cuerpo
    if (declaracion != null) declaracion.jjtAccept(this, data);
    if (condicion != null) {
      exigirBoo((TablaSimbolos.TipoDato) condicion.jjtAccept(this, data), ((SimpleNode) condicion).jjtGetFirstToken());
    }
    if (paso != null) paso.jjtAccept(this, data);
    if (cuerpo != null) {
      profundidadBucle++;
      cuerpo.jjtAccept(this, data);
      profundidadBucle--;
    }
    salirScope();
    return null;
  }

  @Override public Object visit(ASTPasoRepetir node, Object data) {
    Token idToken = node.jjtGetFirstToken();
    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(idToken); return null; }
    simbolo.marcarUsado();
    anotar(node, simbolo.tipo, simbolo);
    // El paso modifica su variable: no puede ser una constante, una funcion ni un arreglo completo.
    if (simbolo.categoria == TablaSimbolos.Categoria.CONSTANTE) {
      ManejadorErrores.reportarAsignacionAConstante(idToken, simbolo.nombre);
      node.childrenAccept(this, data);
      return null;
    }
    if (simbolo.categoria == TablaSimbolos.Categoria.FUNCION) {
      ManejadorErrores.reportarUsoDeFuncionComoValor(idToken);
      node.childrenAccept(this, data);
      return null;
    }
    if (simbolo.aridadArreglo > 0) {
      ManejadorErrores.reportarAridadArregloIncorrecta(idToken, simbolo.nombre, 0, simbolo.aridadArreglo);
      node.childrenAccept(this, data);
      return null;
    }
    if (simbolo.tipo != TablaSimbolos.TipoDato.ENT && simbolo.tipo != TablaSimbolos.TipoDato.DEC) {
      ManejadorErrores.reportarOperandoNoNumerico(idToken, simbolo.tipo);
      return null;
    }
    if (node.jjtGetNumChildren() > 0) { // rama +->/--> con expresion; ++/-- no tiene hijos
      Node expresion = node.jjtGetChild(0);
      TablaSimbolos.TipoDato tipoExpr = (TablaSimbolos.TipoDato) expresion.jjtAccept(this, data);
      if (tipoExpr != null && tipoExpr != simbolo.tipo) {
        reportarValorDeOtroTipo(expresion, simbolo.tipo, tipoExpr, "'" + simbolo.nombre + "' es " + simbolo.tipo);
      }
    }
    return null;
  }

  @Override public Object visit(ASTEstructuraEvaluar node, Object data) {
    int n = node.jjtGetNumChildren();
    if (n == 0) return null; // header roto por un error sintactico ya reportado
    TablaSimbolos.TipoDato tipoEvaluado = (TablaSimbolos.TipoDato) node.jjtGetChild(0).jjtAccept(this, data);
    entrarScope(); // EVALUAR { ... } es un bloque como cualquier otro aunque no pase por Bloque()
    profundidadCasoEvaluar++;
    Map<String, Token> valoresVistos = new HashMap<String, Token>();
    for (CasoEvaluar caso : casosDeEvaluar(node)) {
      if (caso.valor != null) { // un CUANDO (el PRED no tiene expresion propia, no hay nada comparable)
        Node hijo = caso.valor;
        TablaSimbolos.TipoDato tipoCaso = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, data);
        if (tipoEvaluado != null && tipoCaso != null && tipoEvaluado != tipoCaso) {
          reportarValorDeOtroTipo(hijo, tipoEvaluado, tipoCaso, "la expresión de EVALUAR es " + tipoEvaluado);
        }
        String clave = valorLiteralDeCaso(hijo);
        if (clave != null) {
          Token anterior = valoresVistos.get(clave);
          Token actual = ((SimpleNode) hijo).jjtGetFirstToken();
          if (anterior != null) ManejadorErrores.reportarCasoDuplicado(actual, actual.image, anterior);
          else valoresVistos.put(clave, actual);
        }
      }
      for (Node sentencia : caso.sentencias) sentencia.jjtAccept(this, data);
    }
    profundidadCasoEvaluar--;
    salirScope();
    return null;
  }

  @Override public Object visit(ASTEstructuraHacer node, Object data) {
    Node[] partes = partesDeHacer(node); // un header roto puede dejar 0, 1 o 2 hijos
    Node cuerpo = partes[0], condicion = partes[1];
    if (cuerpo != null) {
      profundidadBucle++;
      cuerpo.jjtAccept(this, data);
      profundidadBucle--;
    }
    if (condicion != null) {
      exigirBoo((TablaSimbolos.TipoDato) condicion.jjtAccept(this, data), ((SimpleNode) condicion).jjtGetFirstToken());
    }
    return null;
  }

  @Override public Object visit(ASTExpresionLogica node, Object data) {
    boolean huboError = false;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipo = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, data);
      if (tipo == null) { huboError = true; continue; }
      if (tipo != TablaSimbolos.TipoDato.BOO) {
        ManejadorErrores.reportarCondicionNoBooleana(((SimpleNode) hijo).jjtGetFirstToken(), tipo);
        huboError = true;
      }
    }
    return huboError ? null : anotar(node, TablaSimbolos.TipoDato.BOO, null);
  }

  @Override public Object visit(ASTExpresionRelacional node, Object data) {
    if (node.jjtGetNumChildren() == 1) {
      // Solo se llega aqui cuando hubo NOT sin operador relacional (la gramatica solo crea
      // este nodo si hay NOT o un operador relacional): sin NOT, un
      // Expresion() unico burbujea directo al padre y este visit() nunca se ejecuta.
      // NOT no cambia el tipo (sigue siendo BOO), pero su operando si debe serlo.
      Node operando = node.jjtGetChild(0);
      TablaSimbolos.TipoDato tipo = (TablaSimbolos.TipoDato) operando.jjtAccept(this, data);
      exigirBoo(tipo, ((SimpleNode) operando).jjtGetFirstToken());
      return tipo == null ? null : anotar(node, TablaSimbolos.TipoDato.BOO, null);
    }
    // Con operador: hijos = [Expresion, OperadorRelacional, Expresion] (con o sin NOT antepuesto).
    TablaSimbolos.TipoDato tipoIzq = (TablaSimbolos.TipoDato) node.jjtGetChild(0).jjtAccept(this, data);
    Token operador = ((SimpleNode) node.jjtGetChild(1)).jjtGetFirstToken();
    TablaSimbolos.TipoDato tipoDer = (TablaSimbolos.TipoDato) node.jjtGetChild(2).jjtAccept(this, data);
    if (tipoIzq == null || tipoDer == null) return null;
    if (tipoIzq != tipoDer) {
      exigirMismoTipo(Arrays.asList(node.jjtGetChild(0), node.jjtGetChild(2)), Arrays.asList(tipoIzq, tipoDer), "el otro lado de la comparación");
      return null;
    }
    boolean esIgualdad = operador.kind == IGUAL || operador.kind == DIFERENTE;
    if (!esIgualdad && tipoIzq != TablaSimbolos.TipoDato.ENT && tipoIzq != TablaSimbolos.TipoDato.DEC) {
      ManejadorErrores.reportarOperadorOrdenNoNumerico(operador, tipoIzq);
      return null;
    }
    return anotar(node, TablaSimbolos.TipoDato.BOO, null);
  }

  @Override public Object visit(ASTOperadorRelacional node, Object data) { return null; } // fontaneria, sin hijos ni chequeo propio

  @Override public Object visit(ASTExpresion node, Object data) { return anotar(node, tipoExpresion(node, data), null); }

  @Override public Object visit(ASTTermino node, Object data) { return anotar(node, tipoCadenaAritmetica(node, data), null); }

  @Override public Object visit(ASTFactor node, Object data) { return anotar(node, tipoCadenaAritmetica(node, data), null); }

  @Override public Object visit(ASTUnaryMinus node, Object data) {
    Node operando = node.jjtGetChild(0);
    TablaSimbolos.TipoDato tipo = (TablaSimbolos.TipoDato) operando.jjtAccept(this, data);
    if (tipo == null) return null;
    if (tipo != TablaSimbolos.TipoDato.ENT && tipo != TablaSimbolos.TipoDato.DEC) {
      ManejadorErrores.reportarOperandoNoNumerico(node.jjtGetFirstToken(), tipo);
      return null;
    }
    return anotar(node, tipo, null);
  }

  @Override public Object visit(ASTLiteralArreglo node, Object data) {
    // Si `data` trae un contexto de tamanios (ver ContextoTamanioArreglo, hilado desde
    // procesarHijosDeDeclaracion), compara el numero de elementos de este nivel contra el
    // tamano declarado, cuando ese tamano es conocido y no es -1, y arma el contexto del
    // siguiente nivel para pasarselo a los hijos en vez de reenviarles el mismo `data` de
    // este nivel. Asi un literal anidado (arreglo multidimensional) valida cada nivel contra
    // su propia dimension declarada.
    ContextoTamanioArreglo ctx = data instanceof ContextoTamanioArreglo ? (ContextoTamanioArreglo) data : null;
    Object dataHijos = null;
    if (ctx != null) {
      int esperado = ctx.tamanios[0];
      if (esperado != -1 && node.jjtGetNumChildren() != esperado) {
        ManejadorErrores.reportarTamanioArregloIncorrecto(node.jjtGetFirstToken(), ctx.nombre, ctx.nivel, esperado, node.jjtGetNumChildren());
      }
      if (ctx.tamanios.length > 1) {
        dataHijos = new ContextoTamanioArreglo(Arrays.copyOfRange(ctx.tamanios, 1, ctx.tamanios.length), ctx.nivel + 1, ctx.nombre);
      }
    }
    List<Node> elementos = new ArrayList<Node>();
    List<TablaSimbolos.TipoDato> tipos = new ArrayList<TablaSimbolos.TipoDato>();
    boolean huboError = false;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      // Con contexto de tamanios conocido, cada hijo debe ser un sub-arreglo exactamente
      // cuando quedan mas dimensiones por debajo de este nivel (ctx.tamanios.length > 1).
      // Si no coincide, ya sea demasiada profundidad o el literal se quedo corto, es un
      // error de forma independiente del error de tamano de arriba: los dos pueden
      // coexistir en el mismo literal.
      if (ctx != null) {
        boolean esSubArreglo = hijo instanceof ASTLiteralArreglo;
        boolean seEsperaSubArreglo = ctx.tamanios.length > 1;
        if (esSubArreglo != seEsperaSubArreglo) {
          ManejadorErrores.reportarFormaArregloIncorrecta(((SimpleNode) hijo).jjtGetFirstToken(), ctx.nombre, ctx.nivel, seEsperaSubArreglo);
        }
      }
      TablaSimbolos.TipoDato tipoHijo = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, dataHijos);
      if (tipoHijo == null) { huboError = true; continue; }
      elementos.add(hijo);
      tipos.add(tipoHijo);
    }
    // Todos los elementos del mismo tipo; si uno difiere se nombra (o se dice como escribirlo en DEC).
    TablaSimbolos.TipoDato tipoElemento = exigirMismoTipo(elementos, tipos, "el resto del arreglo");
    if (!tipos.isEmpty() && tipoElemento == null) huboError = true;
    return huboError ? null : anotar(node, tipoElemento, null); // tipoElemento queda null si el literal esta vacio "[]"
  }

  @Override public Object visit(ASTValorSimple node, Object data) {
    Token primero = node.jjtGetFirstToken();
    if (primero.kind == NUMERO_ENTERO) return anotar(node, TablaSimbolos.TipoDato.ENT, null);
    if (primero.kind == NUMERO_DECIMAL) return anotar(node, TablaSimbolos.TipoDato.DEC, null);
    if (primero.kind == CADENA) return anotar(node, TablaSimbolos.TipoDato.CAD, null);
    if (primero.kind == CARACTER) return anotar(node, TablaSimbolos.TipoDato.CAR, null);
    if (primero.kind == VERDADERO || primero.kind == FALSO) return anotar(node, TablaSimbolos.TipoDato.BOO, null);

    // IDENTIFICADOR: variable/constante/parametro, con 0+ accesos de arreglo entre corchetes.
    TablaSimbolos.Simbolo simbolo = tabla.resolver(primero.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(primero); return null; }
    simbolo.marcarUsado();
    if (simbolo.categoria == TablaSimbolos.Categoria.FUNCION) { ManejadorErrores.reportarUsoDeFuncionComoValor(primero); return null; }
    if (simbolo.enInicializacion) {
      ManejadorErrores.reportarUsoEnPropioInicializador(primero);
      node.childrenAccept(this, data); // los indices pueden tener sus propios errores
      return null;
    }
    if (declaracionGlobalEnCurso != null && "GLOBAL".equals(simbolo.ambito) && despuesDe(simbolo.declaracion, declaracionGlobalEnCurso)) {
      ManejadorErrores.reportarGlobalUsadoAntesDeDeclarar(primero, simbolo.declaracion.beginLine);
      node.childrenAccept(this, data);
      return null;
    }

    int accesos = node.jjtGetNumChildren();
    for (int i = 0; i < accesos; i++) {
      Node indice = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoIndice = (TablaSimbolos.TipoDato) indice.jjtAccept(this, data);
      if (tipoIndice != null && tipoIndice != TablaSimbolos.TipoDato.ENT) {
        ManejadorErrores.reportarExpresionDebeSerEntera(((SimpleNode) indice).jjtGetFirstToken(), tipoIndice, "un índice de arreglo");
      }
      validarIndiceLiteral(simbolo, i, indice);
    }
    if (accesos != simbolo.aridadArreglo) {
      ManejadorErrores.reportarAridadArregloIncorrecta(primero, simbolo.nombre, accesos, simbolo.aridadArreglo);
      return null;
    }
    return anotar(node, simbolo.tipo, simbolo);
  }

  @Override public Object visit(ASTLlamadaFuncion node, Object data) {
    Token idToken = node.jjtGetFirstToken();
    // declaracionGlobalEnCurso solo es no-null mientras se visita el inicializador (o las
    // dimensiones) de un global; la llamada se sigue validando para no perder otros errores.
    if (declaracionGlobalEnCurso != null) ManejadorErrores.reportarLlamadaEnInicializadorGlobal(idToken);
    TablaSimbolos.TipoDato tipoRetorno = validarLlamadaFuncion(idToken, node, data);
    if (tipoRetorno == null) return null;
    if (tipoRetorno == TablaSimbolos.TipoDato.VACIO) {
      ManejadorErrores.reportarUsoDeFuncionVacioComoValor(idToken);
      return null;
    }
    return tipoRetorno;
  }
}
