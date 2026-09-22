import java.util.*;

/**
 * Analizador semantico de dos pasadas sobre el AST que devuelve JERCompiler.Programa().
 * Pasada 1 (registrarDeclaracionesGlobales): recorre solo los hijos directos de ASTPrograma
 * (sin bajar a cuerpos de funcion) y registra variables/constantes/funciones globales en la
 * tabla, para que una funcion pueda llamar a otra declarada mas abajo en el archivo.
 * Pasada 2 (el visitor en si, disparado por el mismo jjtAccept que hace la pasada 1 desde
 * visit(ASTPrograma)): entra a cada ASTDeclaracionFuncion y revisa su cuerpo.
 *
 * Convencion: el Object que devuelve cada visit() de un nodo de expresion es su
 * TablaSimbolos.TipoDato calculado (la informacion de tipo "sube" por el arbol); el
 * parametro `data` no se usa aun (reservado para contexto hacia abajo, p. ej. el tipo
 * esperado de un literal de arreglo). Los nodos aun no implementados (grupos 2-4) siguen
 * bajando con childrenAccept() para no cortar el recorrido de lo que si esta implementado.
 */
public final class AnalizadorSemantico implements JERCompilerVisitor, JERCompilerConstants {

  private final TablaSimbolos tabla = new TablaSimbolos();

  /**
   * `data` que procesarHijosDeDeclaracion() le pasa a un ASTLiteralArreglo inicializador, para
   * que visit(ASTLiteralArreglo) pueda comparar su cantidad de elementos contra el tamano
   * declarado de la dimension correspondiente (ver Simbolo.tamanios). `nivel` es 1-indexado,
   * solo para el mensaje de error; `tamanios` es la cola de Simbolo.tamanios a partir de este
   * nivel (tamanios[0] = tamano esperado en ESTE nivel, el resto son los niveles mas internos).
   */
  private static final class ContextoTamanioArreglo {
    final int[] tamanios;
    final int nivel;
    final String nombre; // nombre del simbolo, solo para que los mensajes de error lo mencionen
    ContextoTamanioArreglo(int[] tamanios, int nivel, String nombre) { this.tamanios = tamanios; this.nivel = nivel; this.nombre = nombre; }
  }

  // Contexto de la funcion/bucle/caso actual (igual que profundidadFuncion en el parser):
  // no vive en TablaSimbolos, ver decision-scoping-tabla-simbolos.
  private TablaSimbolos.TipoDato tipoRetornoFuncionActual;
  private int profundidadBucle = 0;
  private int profundidadCasoEvaluar = 0;

  // Cuenta cuantos entrarScope() propios (no los de la pasada 1) estan abiertos. Sirve para
  // que visit(ASTDeclaracionVariable/Constante) sepa si esta dentro de una funcion/bloque
  // (debe declarar, es una declaracion local real) o a nivel global (NO debe declarar de
  // nuevo: la pasada 1 ya la registro; aqui solo se sigue bajando por sus hijos).
  private int profundidadAnidamiento = 0;

  private void entrarScope() { tabla.entrarScope(); profundidadAnidamiento++; }
  private void salirScope() { tabla.salirScope(); profundidadAnidamiento--; }

  public void analizar(ASTPrograma raiz) {
    raiz.jjtAccept(this, null);
  }

  /** Para reportar la tabla de tipos (ManejadorErrores.guardarTablaDeTipos()) tras un analisis sin errores. */
  public TablaSimbolos obtenerTabla() { return tabla; }

  // ======================= Utilidades de tokens/tipos =======================

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
    TablaSimbolos.Simbolo simbolo = new TablaSimbolos.Simbolo(idToken.image, categoria, tipo, aridad, null, idToken, tamanios);
    TablaSimbolos.Simbolo previo = tabla.declarar(simbolo);
    if (previo != null) ManejadorErrores.reportarSimboloDuplicado(idToken, categoria, previo);
  }

  /**
   * Visita los hijos restantes de una ASTDeclaracionVariable/ASTDeclaracionConstante que
   * declararVariableOConstante() no toca: el ASTDimensiones (si es arreglo, valida que cada
   * dimension sea ENT) y el inicializador (si lo hay, compara su tipo contra el tipo declarado
   * — el hueco que faltaba: antes de esto, "ENT x -> "hola";" no daba ningun error). Resuelve
   * el tipo declarado desde la tabla de simbolos (ya declarado en este punto, sea localmente por
   * declararVariableOConstante() o globalmente por la pasada 1) en vez de volver a derivarlo de
   * los tokens, para no duplicar esa logica en dos lugares que podrian desincronizarse.
   */
  private void procesarHijosDeDeclaracion(SimpleNode nodo, boolean esConstante, Object data) {
    Token tipoToken = esConstante ? siguiente(nodo.jjtGetFirstToken()) : nodo.jjtGetFirstToken();
    Token idToken = siguiente(tipoToken);
    if (idToken == null) return; // header roto por un error sintactico ya reportado
    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    // simbolo.declaracion != idToken: esta declaracion perdio el choque contra una anterior (ya
    // reportado por declararVariableOConstante) y no es la duena del simbolo en la tabla; comparar
    // su inicializador contra el tipo (o el tamanio de arreglo) de la OTRA declaracion no aporta
    // nada, solo duplica el error.
    boolean esDuenaDelSimbolo = simbolo != null && simbolo.declaracion == idToken;

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
    if (esDuenaDelSimbolo && tipoInicializador != null && tipoInicializador != simbolo.tipo) {
      ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) inicializador).jjtGetFirstToken(), simbolo.tipo, tipoInicializador);
    }
  }

  /**
   * Regla comun a ASTExpresion/ASTTermino/ASTFactor (+/-, * / %, ** //): todos los operandos
   * deben ser del mismo tipo (sin coercion ENT<->DEC, ver decision-reglas-semanticas-fase1) y
   * ese tipo debe admitir aritmetica (ENT o DEC). Si algun operando ya devolvio null (error mas
   * abajo), no se reporta un segundo error encima: se corta en silencio.
   */
  private TablaSimbolos.TipoDato tipoCadenaAritmetica(SimpleNode node, Object data) {
    TablaSimbolos.TipoDato tipoAcumulado = null;
    boolean huboError = false;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoHijo = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, data);
      if (tipoHijo == null) { huboError = true; continue; }
      if (tipoAcumulado == null) {
        tipoAcumulado = tipoHijo;
      } else if (tipoAcumulado != tipoHijo) {
        ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) hijo).jjtGetFirstToken(), tipoAcumulado, tipoHijo);
        huboError = true;
      }
    }
    if (huboError || tipoAcumulado == null) return null;
    if (tipoAcumulado != TablaSimbolos.TipoDato.ENT && tipoAcumulado != TablaSimbolos.TipoDato.DEC) {
      ManejadorErrores.reportarOperandoNoNumerico(node.jjtGetFirstToken(), tipoAcumulado);
      return null;
    }
    return tipoAcumulado;
  }

  /**
   * Regla de ASTExpresion (+/-): igual que tipoCadenaAritmetica(), pero '+' ademas concatena
   * si cualquiera de los dos lados es CAD (ver decision-concatenacion-cad); el resultado de esa
   * concatenacion es CAD, sea cual sea el otro tipo. '-' nunca acepta CAD, ni tampoco '+' cuando
   * ninguno de los dos lados es CAD (ahi rige la regla numerica estricta de siempre).
   */
  private TablaSimbolos.TipoDato tipoExpresion(ASTExpresion node, Object data) {
    TablaSimbolos.TipoDato acumulado = null;
    boolean huboError = false;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoHijo = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, data);
      if (tipoHijo == null) { huboError = true; continue; }
      if (acumulado == null) { acumulado = tipoHijo; continue; }
      Token operador = ((SimpleNode) node.jjtGetChild(i - 1)).jjtGetLastToken().next;
      if (operador.kind == SUMA && (acumulado == TablaSimbolos.TipoDato.CAD || tipoHijo == TablaSimbolos.TipoDato.CAD)) {
        acumulado = TablaSimbolos.TipoDato.CAD;
        continue;
      }
      if (acumulado != tipoHijo) {
        ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) hijo).jjtGetFirstToken(), acumulado, tipoHijo);
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

  /** Regla comun a SI/MIENTRAS/REPETIR/HACER/ExpresionLogica: la condicion debe ser BOO estricto (sin truthy, ver decision-condicion-boo-estricta). */
  private void exigirBoo(TablaSimbolos.TipoDato tipo, Token token) {
    if (tipo != null && tipo != TablaSimbolos.TipoDato.BOO) {
      ManejadorErrores.reportarCondicionNoBooleana(token, tipo);
    }
  }

  /**
   * EVALUAR no envuelve cada caso CUANDO/PRED en un nodo propio (ver JERCompiler_JJTree.jjt):
   * la expresion de un CUANDO y las Sentencia() de su cuerpo quedan todas como hermanas directas
   * de ASTEstructuraEvaluar. Para saber si `actual` es "la expresion de un nuevo CUANDO" o
   * "una sentencia mas del caso anterior" hay que mirar los tokens crudos entre el fin de
   * `anterior` y el inicio de `actual`: si aparece un CUANDO o un PRED en el medio, `actual`
   * empieza un caso nuevo (y ese token dice cual). Devuelve el kind del marcador encontrado, o -1
   * si no hay ninguno (es una sentencia mas del caso que ya estaba abierto).
   */
  private int marcadorEntre(Node anterior, Node actual) {
    Token t = ((SimpleNode) anterior).jjtGetLastToken().next;
    Token limite = ((SimpleNode) actual).jjtGetFirstToken();
    while (t != null && t != limite) {
      if (t.kind == CUANDO || t.kind == PRED) return t.kind;
      t = t.next;
    }
    return -1;
  }

  /**
   * Clave comparable para detectar CUANDO duplicados: solo cuando la expresion del caso es un
   * literal simple (ASTValorSimple sin hijos, p. ej. no un acceso a arreglo ni una variable) con
   * un token de tipo ENT/DEC/CAD/CAR/BOO. Un caso con variable o expresion arbitraria (la
   * gramatica lo permite) no se puede comparar en tiempo de compilacion, asi que se deja pasar
   * sin marcarlo como visto ni como duplicado. Se antepone el kind del token para no confundir,
   * p. ej., el CADENA "1" con el NUMERO_ENTERO 1.
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
   * Valida una llamada a funcion: usada como sentencia (ASTAsignacionOLlamada, ignorando el
   * valor de retorno) o como valor (ASTLlamadaFuncion). En ambos casos los argumentos son
   * directamente los hijos de `nodo`: LlamadaFuncionSinId() es fontaneria, sus Expresion()
   * resultantes caen directo en el padre, sin nodo propio. Devuelve el tipo de retorno de la
   * funcion si todo esta bien, o null si hubo algun error (no existe, no es funcion, aridad o
   * tipos de argumentos incorrectos) — pero siempre visita todos los argumentos igual, para no
   * perder otros errores que puedan estar dentro de ellos (ver decision-reglas-semanticas-fase1).
   */
  private TablaSimbolos.TipoDato validarLlamadaFuncion(Token idToken, SimpleNode nodo, Object data) {
    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(idToken); nodo.childrenAccept(this, data); return null; }
    if (simbolo.categoria != TablaSimbolos.Categoria.FUNCION) {
      ManejadorErrores.reportarLlamadaANoFuncion(idToken, simbolo.categoria);
      nodo.childrenAccept(this, data);
      return null;
    }
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
        ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) argumento).jjtGetFirstToken(), tipoEsperado, tipoArgumento);
        huboError = true;
      }
    }
    for (int i = limite; i < numArgs; i++) nodo.jjtGetChild(i).jjtAccept(this, data); // argumentos sobrantes: igual se visitan
    return huboError ? null : simbolo.tipo;
  }

  // ======================= Pasada 1: registro global =======================

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
        tipoRetorno, 0, tiposParametros, nombreToken, null);
    TablaSimbolos.Simbolo previo = tabla.declarar(simbolo);
    if (previo != null) ManejadorErrores.reportarSimboloDuplicado(nombreToken, TablaSimbolos.Categoria.FUNCION, previo);
  }

  // ======================= Pasada 2: visitor =======================

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
        ManejadorErrores.reportarExpresionDebeSerEntera(((SimpleNode) hijo).jjtGetFirstToken(), tipo, "una dimension de arreglo");
      }
    }
    return null;
  }

  @Override public Object visit(ASTDeclaracionFuncion node, Object data) {
    Token retornoToken = siguiente(node.jjtGetFirstToken());
    Token nombreToken = siguiente(retornoToken);
    TablaSimbolos.TipoDato tipoRetornoAnterior = tipoRetornoFuncionActual;
    tipoRetornoFuncionActual = retornoToken == null ? null : tipoDeToken(retornoToken.kind);
    entrarScope();
    node.childrenAccept(this, data); // declara cada ASTParametro y luego visita el ASTBloque del cuerpo
    salirScope();
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
   * `true` si la ejecucion de `bloque` (un ASTBloque, o la lista de sentencias de un caso de
   * EVALUAR) garantiza alcanzar un RET antes de llegar al final. Basta con que UNA sentencia lo
   * garantice: lo que venga despues seria codigo muerto (hueco aparte, no cubierto aqui).
   */
  private boolean bloqueSiempreTermina(Node bloque) {
    for (int i = 0; i < bloque.jjtGetNumChildren(); i++) {
      if (sentenciaSiempreTermina(bloque.jjtGetChild(i))) return true;
    }
    return false;
  }

  /**
   * `true` si esta sentencia por si sola garantiza terminar en un RET. JER tiene control de flujo
   * estructurado (sin goto), asi que esto es un predicado recursivo sobre la forma del AST, sin
   * necesidad de un grafo de flujo real. MIENTRAS/REPETIR siempre son `false` (no se puede
   * garantizar al menos una iteracion; no se agrega el caso especial de "MIENTRAS VERDADERO"
   * que si tiene, p. ej., Java, para no ampliar el alcance). TERMINAR tambien es `false`: solo
   * sale del bucle/EVALUAR, no de la funcion.
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
   * SI garantiza terminar solo si tiene un SINO final Y todas las ramas (then, cada SINO SI
   * encadenado, el SINO final) garantizan terminar. Mismo patron de clasificar el primer hijo
   * por tipo que usa visit(ASTEstructuraSi) para tolerar un header roto por recuperacion de
   * errores (0, 1, 2 o 3 hijos segun cuanto se pudo recuperar).
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
   * EVALUAR garantiza terminar solo si tiene PRED (sin el, ningun caso cubre el valor por
   * defecto) Y cada caso (cada CUANDO + el PRED) garantiza terminar. Reutiliza marcadorEntre()
   * para agrupar los hijos planos en casos, igual que visit(ASTEstructuraEvaluar) y el chequeo
   * de CUANDO duplicado.
   */
  private boolean evaluarSiempreTermina(ASTEstructuraEvaluar node) {
    int n = node.jjtGetNumChildren();
    if (n == 0) return false;
    boolean tienePred = false;
    boolean casoAbierto = false;
    boolean casoActualTermina = false;
    boolean todosLosCasosTerminan = true;
    for (int i = 1; i < n; i++) {
      Node hijo = node.jjtGetChild(i);
      int marcador = marcadorEntre(node.jjtGetChild(i - 1), hijo);
      if (marcador == CUANDO || marcador == PRED) {
        if (casoAbierto) todosLosCasosTerminan &= casoActualTermina;
        casoAbierto = true;
        casoActualTermina = false;
        if (marcador == PRED) tienePred = true;
        if (marcador == CUANDO) continue; // este hijo es la expresion del caso, no una sentencia
      }
      if (sentenciaSiempreTermina(hijo)) casoActualTermina = true;
    }
    if (casoAbierto) todosLosCasosTerminan &= casoActualTermina;
    return tienePred && todosLosCasosTerminan;
  }

  @Override public Object visit(ASTParametro node, Object data) {
    Token tipoToken = node.jjtGetFirstToken();
    Token idToken = siguiente(tipoToken);
    if (idToken == null) return null; // roto por un error sintactico ya reportado
    TablaSimbolos.TipoDato tipo = tipoDeToken(tipoToken.kind);
    if (tipo == null) return null;
    TablaSimbolos.Simbolo simbolo = new TablaSimbolos.Simbolo(idToken.image, TablaSimbolos.Categoria.PARAMETRO, tipo, 0, null, idToken, null);
    TablaSimbolos.Simbolo previo = tabla.declarar(simbolo);
    if (previo != null) ManejadorErrores.reportarSimboloDuplicado(idToken, TablaSimbolos.Categoria.PARAMETRO, previo);
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
    if (idToken == null) return null; // roto por un error sintactico ya reportado
    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(idToken); return null; }
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
      ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) valor).jjtGetFirstToken(), tipoRetornoFuncionActual, tipoValor);
    }
    return null;
  }

  @Override public Object visit(ASTSentenciaInvalida node, Object data) { return null; } // ya reportado por el parser, sin contenido util

  @Override public Object visit(ASTAsignacionOLlamada node, Object data) {
    // El token que sigue al identificador dice, sin ambiguedad, cual de las 4 formas de
    // AsignacionOLlamada() es esta (ver JERCompiler_JJTree.jjt): '(' = llamada usada como
    // sentencia; '[' = acceso a arreglo (que a su vez termina en ++/--/asignacion); INC/DEC_OP =
    // incremento/decremento de un escalar; cualquier otro (ASIGNACION/ASIG_INC/ASIG_DEC) =
    // asignacion directa a un escalar.
    Token idToken = node.jjtGetFirstToken();
    Token siguienteToken = siguiente(idToken);
    if (siguienteToken == null) return null; // roto por un error sintactico ya reportado

    if (siguienteToken.kind == APERTURA_PAREN) {
      validarLlamadaFuncion(idToken, node, data); // sentencia de llamada: se ignora el valor de retorno
      return null;
    }

    TablaSimbolos.Simbolo simbolo = tabla.resolver(idToken.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(idToken); node.childrenAccept(this, data); return null; }
    if (simbolo.categoria == TablaSimbolos.Categoria.FUNCION) { ManejadorErrores.reportarUsoDeFuncionComoValor(idToken); node.childrenAccept(this, data); return null; }

    // Un ASTOperadorAsignacion entre los hijos marca donde termina la lista de indices de
    // arreglo y empieza el valor asignado; si no aparece ninguno, todos los hijos son indices
    // y la sentencia termina en ++/-- (0 hijos == "i++;" sin arreglo de por medio).
    int indiceOperador = -1;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      if (node.jjtGetChild(i) instanceof ASTOperadorAsignacion) { indiceOperador = i; break; }
    }
    int numIndices = indiceOperador >= 0 ? indiceOperador : node.jjtGetNumChildren();

    for (int i = 0; i < numIndices; i++) {
      Node indice = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoIndice = (TablaSimbolos.TipoDato) indice.jjtAccept(this, data);
      if (tipoIndice != null && tipoIndice != TablaSimbolos.TipoDato.ENT) {
        ManejadorErrores.reportarExpresionDebeSerEntera(((SimpleNode) indice).jjtGetFirstToken(), tipoIndice, "un indice de arreglo");
      }
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
      ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) valor).jjtGetFirstToken(), simbolo.tipo, tipoValor);
    }
    return null;
  }

  @Override public Object visit(ASTOperadorAsignacion node, Object data) { return null; } // fontaneria, sin hijos ni chequeo propio

  @Override public Object visit(ASTEstructuraSi node, Object data) {
    // Si la condicion fallo y la recuperacion no encontro '{', el header entero se descarta y
    // el nodo puede quedar con menos hijos de los que la gramatica "feliz" sugiere (0, 1, 2 o 3).
    // Igual que en ASTEstructuraRepetir/Hacer, se clasifica el primer hijo por tipo en vez de
    // asumir una posicion fija: si NO es un ASTBloque, es la condicion (y en ese caso, por como
    // esta escrita la gramatica, el ASTBloque "then" siempre le sigue inmediatamente). El tercer
    // hijo (si existe) puede ser un ASTBloque (SINO simple) o otro ASTEstructuraSi (SINO SI
    // encadenado); no hace falta distinguirlos, jjtAccept() ya despacha polimorficamente al
    // visit() correcto sin necesitar instanceof ni node.jjtGetValue().
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
    // Header roto por un error sintactico puede dejar cualquier subconjunto de estos 4 hijos;
    // se clasifican por tipo (Condicion() es "lo que no es ninguno de los otros tres", ya que
    // puede resolver a muchos tipos de nodo distintos y no tiene uno propio identificable).
    Node declaracion = null, condicion = null, paso = null, cuerpo = null;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      if (hijo instanceof ASTDeclaracionVariable) declaracion = hijo;
      else if (hijo instanceof ASTPasoRepetir) paso = hijo;
      else if (hijo instanceof ASTBloque) cuerpo = hijo;
      else condicion = hijo;
    }
    entrarScope(); // la variable de control vive en su propio scope, junto con el cuerpo (ver decision-scoping-tabla-simbolos)
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
    if (simbolo.tipo != TablaSimbolos.TipoDato.ENT && simbolo.tipo != TablaSimbolos.TipoDato.DEC) {
      ManejadorErrores.reportarOperandoNoNumerico(idToken, simbolo.tipo);
      return null;
    }
    if (node.jjtGetNumChildren() > 0) { // rama +->/--> con expresion; ++/-- no tiene hijos
      Node expresion = node.jjtGetChild(0);
      TablaSimbolos.TipoDato tipoExpr = (TablaSimbolos.TipoDato) expresion.jjtAccept(this, data);
      if (tipoExpr != null && tipoExpr != simbolo.tipo) {
        ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) expresion).jjtGetFirstToken(), simbolo.tipo, tipoExpr);
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
    for (int i = 1; i < n; i++) {
      Node hijo = node.jjtGetChild(i);
      if (marcadorEntre(node.jjtGetChild(i - 1), hijo) == CUANDO) {
        // Es la expresion de un CUANDO nuevo (PRED no tiene expresion propia: lo que sigue a
        // un marcador PRED ya es la primera sentencia de ese caso, no algo comparable).
        TablaSimbolos.TipoDato tipoCaso = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, data);
        if (tipoEvaluado != null && tipoCaso != null && tipoEvaluado != tipoCaso) {
          ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) hijo).jjtGetFirstToken(), tipoEvaluado, tipoCaso);
        }
        String clave = valorLiteralDeCaso(hijo);
        if (clave != null) {
          Token anterior = valoresVistos.get(clave);
          Token actual = ((SimpleNode) hijo).jjtGetFirstToken();
          if (anterior != null) ManejadorErrores.reportarCasoDuplicado(actual, actual.image, anterior);
          else valoresVistos.put(clave, actual);
        }
      } else {
        hijo.jjtAccept(this, data); // sentencia normal del caso abierto (incluye la primera de un PRED)
      }
    }
    profundidadCasoEvaluar--;
    salirScope();
    return null;
  }

  @Override public Object visit(ASTEstructuraHacer node, Object data) {
    // Header roto puede dejar 0, 1 o 2 hijos; se clasifican por tipo ya que Condicion() no
    // tiene un unico tipo de nodo identificable (ver comentario en ASTEstructuraRepetir).
    Node cuerpo = null, condicion = null;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      if (hijo instanceof ASTBloque) cuerpo = hijo; else condicion = hijo;
    }
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
    return huboError ? null : TablaSimbolos.TipoDato.BOO;
  }

  @Override public Object visit(ASTExpresionRelacional node, Object data) {
    if (node.jjtGetNumChildren() == 1) {
      // Solo se llega aqui cuando hubo NOT sin operador relacional (ver la anotacion
      // #ExpresionRelacional(negado || ...) en JERCompiler_JJTree.jjt): sin NOT, un
      // Expresion() unico burbujea directo al padre y este visit() nunca se ejecuta.
      // NOT no cambia el tipo (sigue siendo BOO), pero su operando si debe serlo.
      Node operando = node.jjtGetChild(0);
      TablaSimbolos.TipoDato tipo = (TablaSimbolos.TipoDato) operando.jjtAccept(this, data);
      exigirBoo(tipo, ((SimpleNode) operando).jjtGetFirstToken());
      return tipo == null ? null : TablaSimbolos.TipoDato.BOO;
    }
    // Con operador: hijos = [Expresion, OperadorRelacional, Expresion] (con o sin NOT antepuesto).
    TablaSimbolos.TipoDato tipoIzq = (TablaSimbolos.TipoDato) node.jjtGetChild(0).jjtAccept(this, data);
    Token operador = ((SimpleNode) node.jjtGetChild(1)).jjtGetFirstToken();
    TablaSimbolos.TipoDato tipoDer = (TablaSimbolos.TipoDato) node.jjtGetChild(2).jjtAccept(this, data);
    if (tipoIzq == null || tipoDer == null) return null;
    if (tipoIzq != tipoDer) {
      ManejadorErrores.reportarTipoIncompatibleEnOperacion(operador, tipoIzq, tipoDer);
      return null;
    }
    boolean esIgualdad = operador.kind == IGUAL || operador.kind == DIFERENTE;
    if (!esIgualdad && tipoIzq != TablaSimbolos.TipoDato.ENT && tipoIzq != TablaSimbolos.TipoDato.DEC) {
      ManejadorErrores.reportarOperadorOrdenNoNumerico(operador, tipoIzq);
      return null;
    }
    return TablaSimbolos.TipoDato.BOO;
  }

  @Override public Object visit(ASTOperadorRelacional node, Object data) { return null; } // fontaneria, sin hijos ni chequeo propio

  @Override public Object visit(ASTExpresion node, Object data) { return tipoExpresion(node, data); }

  @Override public Object visit(ASTTermino node, Object data) { return tipoCadenaAritmetica(node, data); }

  @Override public Object visit(ASTFactor node, Object data) { return tipoCadenaAritmetica(node, data); }

  @Override public Object visit(ASTUnaryMinus node, Object data) {
    Node operando = node.jjtGetChild(0);
    TablaSimbolos.TipoDato tipo = (TablaSimbolos.TipoDato) operando.jjtAccept(this, data);
    if (tipo == null) return null;
    if (tipo != TablaSimbolos.TipoDato.ENT && tipo != TablaSimbolos.TipoDato.DEC) {
      ManejadorErrores.reportarOperandoNoNumerico(node.jjtGetFirstToken(), tipo);
      return null;
    }
    return tipo;
  }

  @Override public Object visit(ASTLiteralArreglo node, Object data) {
    // Si `data` trae un contexto de tamanios (ver ContextoTamanioArreglo, hilado desde
    // procesarHijosDeDeclaracion), compara el numero de elementos de ESTE nivel contra el tamano
    // declarado (si ese tamano es conocido: no es -1) y arma el contexto del siguiente nivel para
    // pasarselo a los hijos, en vez de reenviarles el mismo `data` de este nivel. Asi un literal
    // anidado (arreglo multidimensional) valida cada nivel contra su propia dimension declarada.
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
    TablaSimbolos.TipoDato tipoElemento = null;
    boolean huboError = false;
    for (int i = 0; i < node.jjtGetNumChildren(); i++) {
      Node hijo = node.jjtGetChild(i);
      // Con contexto de tamanios conocido, cada hijo debe ser un sub-arreglo exactamente cuando
      // quedan mas dimensiones por debajo de este nivel (ctx.tamanios.length > 1); si no coincide
      // (demasiada profundidad, o el literal se quedo corto), es un error de forma independiente
      // del error de tamano de arriba (los dos pueden coexistir en el mismo literal).
      if (ctx != null) {
        boolean esSubArreglo = hijo instanceof ASTLiteralArreglo;
        boolean seEsperaSubArreglo = ctx.tamanios.length > 1;
        if (esSubArreglo != seEsperaSubArreglo) {
          ManejadorErrores.reportarFormaArregloIncorrecta(((SimpleNode) hijo).jjtGetFirstToken(), ctx.nombre, ctx.nivel, seEsperaSubArreglo);
        }
      }
      TablaSimbolos.TipoDato tipoHijo = (TablaSimbolos.TipoDato) hijo.jjtAccept(this, dataHijos);
      if (tipoHijo == null) { huboError = true; continue; }
      if (tipoElemento == null) {
        tipoElemento = tipoHijo;
      } else if (tipoElemento != tipoHijo) {
        ManejadorErrores.reportarTipoIncompatibleEnOperacion(((SimpleNode) hijo).jjtGetFirstToken(), tipoElemento, tipoHijo);
        huboError = true;
      }
    }
    return huboError ? null : tipoElemento; // tipoElemento queda null si el literal esta vacio "[]"
  }

  @Override public Object visit(ASTValorSimple node, Object data) {
    Token primero = node.jjtGetFirstToken();
    if (primero.kind == NUMERO_ENTERO) return TablaSimbolos.TipoDato.ENT;
    if (primero.kind == NUMERO_DECIMAL) return TablaSimbolos.TipoDato.DEC;
    if (primero.kind == CADENA) return TablaSimbolos.TipoDato.CAD;
    if (primero.kind == CARACTER) return TablaSimbolos.TipoDato.CAR;
    if (primero.kind == VERDADERO || primero.kind == FALSO) return TablaSimbolos.TipoDato.BOO;

    // IDENTIFICADOR: variable/constante/parametro, con 0+ accesos de arreglo entre corchetes.
    TablaSimbolos.Simbolo simbolo = tabla.resolver(primero.image);
    if (simbolo == null) { ManejadorErrores.reportarVariableNoDeclarada(primero); return null; }
    if (simbolo.categoria == TablaSimbolos.Categoria.FUNCION) { ManejadorErrores.reportarUsoDeFuncionComoValor(primero); return null; }

    int accesos = node.jjtGetNumChildren();
    for (int i = 0; i < accesos; i++) {
      Node indice = node.jjtGetChild(i);
      TablaSimbolos.TipoDato tipoIndice = (TablaSimbolos.TipoDato) indice.jjtAccept(this, data);
      if (tipoIndice != null && tipoIndice != TablaSimbolos.TipoDato.ENT) {
        ManejadorErrores.reportarExpresionDebeSerEntera(((SimpleNode) indice).jjtGetFirstToken(), tipoIndice, "un indice de arreglo");
      }
    }
    if (accesos != simbolo.aridadArreglo) {
      ManejadorErrores.reportarAridadArregloIncorrecta(primero, simbolo.nombre, accesos, simbolo.aridadArreglo);
      return null;
    }
    return simbolo.tipo;
  }

  @Override public Object visit(ASTLlamadaFuncion node, Object data) {
    Token idToken = node.jjtGetFirstToken();
    TablaSimbolos.TipoDato tipoRetorno = validarLlamadaFuncion(idToken, node, data);
    if (tipoRetorno == null) return null;
    if (tipoRetorno == TablaSimbolos.TipoDato.VACIO) {
      ManejadorErrores.reportarUsoDeFuncionVacioComoValor(idToken);
      return null;
    }
    return tipoRetorno;
  }
}
