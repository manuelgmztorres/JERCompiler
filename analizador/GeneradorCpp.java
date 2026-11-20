import java.util.*;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * Traduce a C++ el AST que ya paso el analisis semantico sin errores, para compilarlo con MinGW (g++)
 * y obtener codigo ensamblador (.s), objeto (.o) y ejecutable (.exe). Igual que GeneradorCodigo,
 * lee las Anotacion del analizador (tipo y simbolo de cada nodo) y no recalcula nada.
 *
 * El resultado es C++ estandar como lo escribiria una persona: std::cout, std::cin, int, if,
 * while, for... Cada #include y cada funcion auxiliar solo aparece si el programa la necesita.
 * Lo unico que C++ no hace igual que JER se resuelve en linea donde aparece: BOO se imprime como
 * VERDADERO/FALSO, DEC siempre con punto decimal (funcion `decimal`, solo si se usa) y '+' con un
 * CAD convierte el otro lado a texto.
 *
 * shortcut: no se revisan los errores de ejecucion del README (division entre cero, indice fuera
 * de rango, valor de OBT de otro tipo...): C++ hace lo suyo. Si el equipo los necesita, agregar
 * las comprobaciones aqui.
 *
 * expresion() devuelve el texto C++ de una expresion SIN parentesis exteriores; operando() lo
 * envuelve cuando el nodo es compuesto, porque el AST no guarda los parentesis del fuente.
 */
public final class GeneradorCpp implements JERCompilerConstants {

  // Palabras que un identificador de JER (empieza con letra) puede escribir pero C++ no acepta
  // como nombre: palabras clave, macros de los encabezados incluidos y la funcion auxiliar `decimal`.
  // shortcut: lista fija; si aparece otra macro de <cmath>/<iostream> que choque, agregarla aqui.
  private static final Set<String> RESERVADAS_CPP = new HashSet<String>(Arrays.asList((
      "alignas alignof and and_eq asm auto bitand bitor bool break case catch char char16_t char32_t class "
    + "compl const constexpr const_cast continue decltype default delete do double dynamic_cast else enum "
    + "explicit export extern false float for friend goto if inline int long mutable namespace new noexcept "
    + "not not_eq nullptr operator or or_eq private protected public register reinterpret_cast return short "
    + "signed sizeof static static_assert static_cast struct switch template this thread_local throw true try "
    + "typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq "
    + "main std decimal NULL EOF errno assert stdin stdout stderr INFINITY NAN HUGE_VAL EXIT_SUCCESS EXIT_FAILURE").split(" ")));

  private static final String SANGRIA = "    ";

  private final StringBuilder salida = new StringBuilder();
  private int nivel = 0;      // sangria actual
  private int evaluares = 0;  // para nombrar el valor y la etiqueta de cada EVALUAR
  private final Set<String> funciones = new HashSet<String>();
  private final Set<String> noSoportado = new LinkedHashSet<String>();
  private final Set<String> definidas = new HashSet<String>();   // funciones ya escritas
  private final Set<String> adelantadas = new HashSet<String>(); // llamadas antes de su definicion: necesitan prototipo
  private final Set<String> includes = new TreeSet<String>();
  private boolean usaDecimal = false;
  // A donde salta TERMINAR: "" = bucle (break); un nombre = la etiqueta final de un EVALUAR (goto).
  private final Deque<String> salidas = new ArrayDeque<String>();
  private final Set<String> etiquetasUsadas = new HashSet<String>();

  public GeneradorCpp(TablaSimbolos simbolos) {
    for (TablaSimbolos.Simbolo s : simbolos.todos()) {
      if (s.categoria == TablaSimbolos.Categoria.FUNCION) funciones.add(s.nombre);
    }
  }

  /** Construcciones que el programa usa y esta version todavia no traduce (vacio = traduccion completa). */
  public Set<String> noSoportado() { return Collections.unmodifiableSet(noSoportado); }

  /**
   * Las globales van primero (C++ exige declarar antes de usar) y se inicializan en orden de
   * declaracion, igual que `_global` en el codigo intermedio. Los #include, la funcion `decimal` y
   * los prototipos se deciden al terminar, cuando ya se sabe que se uso.
   */
  public String generar(ASTPrograma raiz, String nombreArchivo) {
    List<ASTDeclaracionFuncion> funcionesDecl = new ArrayList<ASTDeclaracionFuncion>();
    boolean hayGlobales = false;
    for (int i = 0; i < raiz.jjtGetNumChildren(); i++) {
      Node hijo = raiz.jjtGetChild(i);
      if (hijo instanceof ASTDeclaracionFuncion) funcionesDecl.add((ASTDeclaracionFuncion) hijo);
      else if (esDeclaracion(hijo)) { sentencia(hijo); hayGlobales = true; }
    }
    if (hayGlobales) linea("");
    for (ASTDeclaracionFuncion f : funcionesDecl) funcion(f);
    linea("int main() {");
    linea(SANGRIA + GeneradorCodigo.FUNCION_PRINCIPAL + "();");
    linea(SANGRIA + "return 0;");
    linea("}");

    if (usaDecimal) { includes.add("sstream"); includes.add("string"); }
    StringBuilder inicio = new StringBuilder();
    for (String inc : includes) inicio.append("#include <").append(inc).append(">\n");
    inicio.append('\n');
    if (usaDecimal) inicio.append(DECIMAL).append('\n');
    // Prototipos solo de las funciones que se usan antes de su definicion: en JER el orden no importa, en C++ si.
    for (ASTDeclaracionFuncion f : funcionesDecl) {
      if (adelantadas.contains(simbolo(f).nombre)) inicio.append(cabecera(f)).append(";\n");
    }
    if (!adelantadas.isEmpty()) inicio.append('\n');
    return inicio.append(salida).toString();
  }

  // Funciones

  private String cabecera(ASTDeclaracionFuncion nodo) {
    TablaSimbolos.Simbolo f = simbolo(nodo);
    List<String> parametros = new ArrayList<String>();
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) {
      Node hijo = nodo.jjtGetChild(i);
      if (hijo instanceof ASTParametro) {
        TablaSimbolos.Simbolo p = simbolo(hijo);
        parametros.add(tipoCpp(p.tipo, 0) + " " + nombre(p));
      }
    }
    return tipoCpp(f.tipo, 0) + " " + nombre(f) + "(" + String.join(", ", parametros) + ")";
  }

  private void funcion(ASTDeclaracionFuncion nodo) {
    definidas.add(simbolo(nodo).nombre); // antes del cuerpo: la recursion no necesita prototipo
    linea(cabecera(nodo) + " {");
    nivel++;
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) {
      Node hijo = nodo.jjtGetChild(i);
      if (hijo instanceof ASTBloque) sentencias(hijo);
    }
    nivel--;
    linea("}");
    linea("");
  }

  // Sentencias

  private void sentencias(Node bloque) {
    for (int i = 0; i < bloque.jjtGetNumChildren(); i++) sentencia(bloque.jjtGetChild(i));
  }

  private void sentencia(Node nodo) {
    if (nodo instanceof ASTBloque) {
      linea("{"); nivel++; sentencias(nodo); nivel--; linea("}");
    } else if (esDeclaracion(nodo)) {
      linea(declaracion(nodo) + ";");
    } else if (nodo instanceof ASTAsignacionOLlamada) {
      asignacionOLlamada((ASTAsignacionOLlamada) nodo);
    } else if (nodo instanceof ASTSentenciaImprimir) {
      includes.add("iostream");
      linea("std::cout << " + imprimible(nodo.jjtGetChild(0)) + " << std::endl;");
    } else if (nodo instanceof ASTSentenciaObtener) {
      obtener(simbolo(nodo));
    } else if (nodo instanceof ASTSentenciaRetorno) {
      linea("return " + expresion(nodo.jjtGetChild(0)) + ";");
    } else if (nodo instanceof ASTSentenciaTerminar) {
      terminar();
    } else if (nodo instanceof ASTEstructuraSi) {
      si((ASTEstructuraSi) nodo, "");
    } else if (nodo instanceof ASTEstructuraMientras) {
      linea("while (" + expresion(nodo.jjtGetChild(0)) + ") {");
      bucle(nodo.jjtGetChild(1), "}");
    } else if (nodo instanceof ASTEstructuraHacer) {
      Node[] partes = AnalizadorSemantico.partesDeHacer(nodo);
      linea("do {");
      bucle(partes[0], "} while (" + expresion(partes[1]) + ");");
    } else if (nodo instanceof ASTEstructuraRepetir) {
      repetir((ASTEstructuraRepetir) nodo);
    } else if (nodo instanceof ASTEstructuraEvaluar) {
      evaluar((ASTEstructuraEvaluar) nodo);
    } else {
      noSoportado.add(nodo.getClass().getSimpleName());
    }
  }

  /** Sentencias de un bloque ya abierto con `{`, con un nivel mas de sangria, y la linea de cierre. */
  private void cuerpo(Node bloque, String cierre) {
    nivel++; sentencias(bloque); nivel--;
    linea(cierre);
  }

  /** Cuerpo de un bucle: dentro, TERMINAR es `break`. */
  private void bucle(Node bloque, String cierre) {
    salidas.push("");
    cuerpo(bloque, cierre);
    salidas.pop();
  }

  private static boolean esDeclaracion(Node nodo) {
    return nodo instanceof ASTDeclaracionVariable || nodo instanceof ASTDeclaracionConstante;
  }

  /**
   * La declaracion sin `;` (tambien sirve para la cabecera de un REPETIR): `const int x = 5`,
   * `std::vector<int> v(3)` o, con literal, `std::vector<int> v = {1, 2, 3}`.
   */
  private String declaracion(Node nodo) {
    TablaSimbolos.Simbolo v = simbolo(nodo);
    String texto = (nodo instanceof ASTDeclaracionConstante ? "const " : "") + tipoCpp(v.tipo, v.aridadArreglo) + " " + nombre(v);
    if (v.aridadArreglo == 0) {
      return texto + " = " + (nodo.jjtGetNumChildren() > 0 ? expresion(nodo.jjtGetChild(0)) : valorPorDefecto(v.tipo));
    }
    // shortcut: con literal se toma su tamano; el analizador ya exige que coincida si el tamano es literal,
    // pero un tamano calculado (`ENT v[n] -> [...]`) no se compara al ejecutar.
    if (nodo.jjtGetNumChildren() > 1) return texto + " = " + literal(nodo.jjtGetChild(1));
    return texto + "(" + tamanios(nodo.jjtGetChild(0), 0, v.tipo) + ")";
  }

  /** `[[1, 2], [3, 4]]` -> `{{1, 2}, {3, 4}}`. */
  private String literal(Node nodo) {
    List<String> elementos = new ArrayList<String>();
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) {
      Node e = nodo.jjtGetChild(i);
      elementos.add(e instanceof ASTLiteralArreglo ? literal(e) : expresion(e));
    }
    return "{" + String.join(", ", elementos) + "}";
  }

  /** Argumentos del constructor de un arreglo de varias dimensiones: `2, std::vector<int>(3)`. */
  private String tamanios(Node dimensiones, int i, TablaSimbolos.TipoDato tipo) {
    String tam = expresion(dimensiones.jjtGetChild(i));
    int restantes = dimensiones.jjtGetNumChildren() - i - 1;
    if (restantes == 0) return tam;
    return tam + ", " + tipoCpp(tipo, restantes) + "(" + tamanios(dimensiones, i + 1, tipo) + ")";
  }

  /** OBT: CAD toma la linea completa; BOO lee la palabra VERDADERO/FALSO; los demas, `std::cin >>`. */
  private void obtener(TablaSimbolos.Simbolo v) {
    includes.add("iostream");
    String n = nombre(v);
    if (v.tipo == TablaSimbolos.TipoDato.CAD) {
      linea("std::getline(std::cin >> std::ws, " + n + ");");
    } else if (v.tipo == TablaSimbolos.TipoDato.BOO) {
      includes.add("string");
      linea("{ std::string _palabra; std::cin >> _palabra; " + n + " = _palabra == \"VERDADERO\"; }");
    } else {
      linea("std::cin >> " + n + ";");
    }
  }

  private void asignacionOLlamada(ASTAsignacionOLlamada nodo) {
    Token siguiente = nodo.jjtGetFirstToken().next;
    if (siguiente.kind == APERTURA_PAREN) { linea(llamada(nodo) + ";"); return; }
    TablaSimbolos.Simbolo v = simbolo(nodo);
    String destino = nombre(v);
    int operador;
    Node valor = null;
    if (siguiente.kind == APERTURA_CORCHETE) {
      int posicion = AnalizadorSemantico.posicionOperadorAsignacion(nodo); // -1 con ++ / --
      int indices = posicion >= 0 ? posicion : nodo.jjtGetNumChildren();
      for (int i = 0; i < indices; i++) destino += "[" + expresion(nodo.jjtGetChild(i)) + "]";
      if (posicion >= 0) {
        operador = ((SimpleNode) nodo.jjtGetChild(posicion)).jjtGetFirstToken().kind;
        valor = nodo.jjtGetChild(posicion + 1);
      } else {
        Token t = nodo.jjtGetFirstToken();
        while (t.kind != INC && t.kind != DEC_OP) t = t.next;
        operador = t.kind;
      }
    } else if (siguiente.kind == INC || siguiente.kind == DEC_OP) {
      operador = siguiente.kind;
    } else {
      operador = ((SimpleNode) nodo.jjtGetChild(0)).jjtGetFirstToken().kind; // ->, +-> o -->
      valor = nodo.jjtGetChild(1);
    }
    linea(asignacion(destino, v.tipo, operador, valor) + ";");
  }

  /** `x = e`, `x += e`, `x -= e`, `x++` o `x--` (sin `;`). */
  private String asignacion(String destino, TablaSimbolos.TipoDato tipo, int operador, Node valor) {
    switch (operador) {
      case INC: return destino + "++";
      case DEC_OP: return destino + "--";
      case ASIGNACION: return destino + " = " + expresion(valor);
      case ASIG_DEC: return destino + " -= " + expresion(valor);
      default: // ASIG_INC; a un CAD se le concatena el valor convertido a texto, como con '+'
        if (tipo == TablaSimbolos.TipoDato.CAD) return destino + " += " + aTexto(operando(valor), tipoDe(valor), false);
        return destino + " += " + expresion(valor);
    }
  }

  // Control de flujo

  /** `SINO SI` se escribe como `} else if (...) {` reutilizando esta misma funcion. */
  private void si(ASTEstructuraSi nodo, String prefijo) {
    linea(prefijo + "if (" + expresion(nodo.jjtGetChild(0)) + ") {");
    nivel++; sentencias(nodo.jjtGetChild(1)); nivel--;
    if (nodo.jjtGetNumChildren() < 3) { linea("}"); return; }
    Node sino = nodo.jjtGetChild(2);
    if (sino instanceof ASTEstructuraSi) { si((ASTEstructuraSi) sino, "} else "); return; }
    linea("} else {");
    cuerpo(sino, "}");
  }

  private void repetir(ASTEstructuraRepetir nodo) {
    AnalizadorSemantico.PartesRepetir partes = AnalizadorSemantico.partesDeRepetir(nodo);
    if (simbolo(partes.declaracion).aridadArreglo > 0) { noSoportado.add("arreglo declarado en la cabecera de REPETIR"); return; }
    ASTPasoRepetir paso = (ASTPasoRepetir) partes.paso;
    TablaSimbolos.Simbolo variable = simbolo(paso);
    String incremento = asignacion(nombre(variable), variable.tipo, paso.jjtGetFirstToken().next.kind,
        paso.jjtGetNumChildren() > 0 ? paso.jjtGetChild(0) : null);
    linea("for (" + declaracion(partes.declaracion) + "; " + expresion(partes.condicion) + "; " + incremento + ") {");
    bucle(partes.cuerpo, "}");
  }

  /**
   * EVALUAR es una cadena if / else if / else (switch no sirve: el valor puede ser CAD o DEC). Si el
   * valor no es una variable o constante, se calcula una sola vez en `_valorN`. Un TERMINAR dentro
   * sale solo del EVALUAR, como en el codigo intermedio: `goto _fin_evaluarN`, etiqueta que solo se
   * escribe si se usa. Los nombres con '_' no chocan: los de JER empiezan con letra.
   */
  private void evaluar(ASTEstructuraEvaluar nodo) {
    int n = ++evaluares;
    Node expresion = nodo.jjtGetChild(0);
    String valor = expresion(expresion);
    if (!(expresion instanceof ASTValorSimple && expresion.jjtGetNumChildren() == 0)) {
      linea(tipoCpp(tipoDe(expresion), 0) + " _valor" + n + " = " + valor + ";");
      valor = "_valor" + n;
    }
    String etiqueta = "_fin_evaluar" + n;
    salidas.push(etiqueta);
    String prefijo = "";
    for (AnalizadorSemantico.CasoEvaluar caso : AnalizadorSemantico.casosDeEvaluar(nodo)) {
      linea(caso.esPred ? "} else {" : prefijo + "if (" + valor + " == " + operando(caso.valor) + ") {");
      nivel++;
      for (Node s : caso.sentencias) sentencia(s);
      nivel--;
      prefijo = "} else ";
    }
    linea("}");
    salidas.pop();
    if (etiquetasUsadas.contains(etiqueta)) linea(etiqueta + ":;");
  }

  private void terminar() {
    if (salidas.isEmpty()) { noSoportado.add("TERMINAR fuera de un bucle"); return; }
    String destino = salidas.peek();
    if (destino.isEmpty()) { linea("break;"); return; }
    etiquetasUsadas.add(destino);
    linea("goto " + destino + ";");
  }

  // Expresiones

  private String expresion(Node nodo) {
    if (nodo instanceof ASTValorSimple) return valorSimple((ASTValorSimple) nodo);
    if (nodo instanceof ASTExpresion || nodo instanceof ASTTermino || nodo instanceof ASTFactor) return cadena((SimpleNode) nodo);
    if (nodo instanceof ASTUnaryMinus) return "-" + operando(nodo.jjtGetChild(0));
    if (nodo instanceof ASTExpresionRelacional) return relacional((ASTExpresionRelacional) nodo);
    if (nodo instanceof ASTExpresionLogica) return logica((ASTExpresionLogica) nodo);
    if (nodo instanceof ASTLlamadaFuncion) return llamada((SimpleNode) nodo);
    noSoportado.add(nodo.getClass().getSimpleName());
    return "0";
  }

  /** La expresion entre parentesis si es compuesta, para conservar el orden que tenia en el arbol. */
  private String operando(Node nodo) {
    boolean compuesta = nodo instanceof ASTExpresion || nodo instanceof ASTTermino || nodo instanceof ASTFactor
        || nodo instanceof ASTUnaryMinus || nodo instanceof ASTExpresionRelacional || nodo instanceof ASTExpresionLogica;
    return compuesta ? "(" + expresion(nodo) + ")" : expresion(nodo);
  }

  /** Lo que va despues de `std::cout <<`: BOO como VERDADERO/FALSO y DEC con punto decimal. */
  private String imprimible(Node nodo) {
    TablaSimbolos.TipoDato tipo = tipoDe(nodo);
    if (tipo == TablaSimbolos.TipoDato.BOO) return "(" + operando(nodo) + " ? \"VERDADERO\" : \"FALSO\")";
    if (tipo == TablaSimbolos.TipoDato.DEC) { usaDecimal = true; return "decimal(" + expresion(nodo) + ")"; }
    return expresion(nodo);
  }

  private String valorSimple(ASTValorSimple nodo) {
    Token t = nodo.jjtGetFirstToken();
    if (t.kind == VERDADERO) return "true";
    if (t.kind == FALSO) return "false";
    if (t.kind != IDENTIFICADOR) return t.image; // numeros, cadenas y caracteres se escriben igual en C++
    String texto = nombre(simbolo(nodo));
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) texto += "[" + expresion(nodo.jjtGetChild(i)) + "]";
    return texto;
  }

  private static boolean esCadenaLiteral(Node nodo) {
    return nodo instanceof ASTValorSimple && ((SimpleNode) nodo).jjtGetFirstToken().kind == CADENA;
  }

  /** `f(a, b)`; `nodo` es una LlamadaFuncion o una AsignacionOLlamada usada como sentencia. */
  private String llamada(SimpleNode nodo) {
    List<String> argumentos = new ArrayList<String>();
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) argumentos.add(expresion(nodo.jjtGetChild(i)));
    TablaSimbolos.Simbolo f = simbolo(nodo);
    if (!definidas.contains(f.nombre)) adelantadas.add(f.nombre);
    return nombre(f) + "(" + String.join(", ", argumentos) + ")";
  }

  /**
   * Expresion / Termino / Factor, de izquierda a derecha. `+` con un CAD de cualquier lado pasa la
   * cadena a CAD (regla del analizador): desde ahi todo se convierte a std::string con aTexto().
   */
  private String cadena(SimpleNode nodo) {
    Node primero = nodo.jjtGetChild(0);
    String acumulado = operando(primero);
    TablaSimbolos.TipoDato tipo = tipoDe(primero);
    boolean literal = esCadenaLiteral(primero); // el acumulado sigue siendo solo ese literal
    boolean esTexto = false;
    for (int i = 1; i < nodo.jjtGetNumChildren(); i++) {
      Node hijo = nodo.jjtGetChild(i);
      String derecho = operando(hijo);
      TablaSimbolos.TipoDato tipoHijo = tipoDe(hijo);
      int operador = AnalizadorSemantico.operadorDespuesDe(nodo.jjtGetChild(i - 1)).kind;
      if (operador == SUMA && (tipo == TablaSimbolos.TipoDato.CAD || tipoHijo == TablaSimbolos.TipoDato.CAD)) {
        // Solo el primer operando necesita ser std::string: "a" + "b" entre literales no compila.
        if (!esTexto) { acumulado = aTexto(acumulado, tipo, true, literal); esTexto = true; }
        acumulado += " + " + aTexto(derecho, tipoHijo, false);
        tipo = TablaSimbolos.TipoDato.CAD;
        continue;
      }
      literal = false;
      boolean dec = tipo == TablaSimbolos.TipoDato.DEC;
      switch (operador) {
        case SUMA: acumulado += " + " + derecho; break;
        case RESTA: acumulado += " - " + derecho; break;
        case MULT: acumulado += " * " + derecho; break;
        case DIV: acumulado += " / " + derecho; break;
        case MODULO:
          if (dec) { includes.add("cmath"); acumulado = "std::fmod(" + acumulado + ", " + derecho + ")"; }
          else acumulado += " % " + derecho;
          break;
        case POTENCIA:
          includes.add("cmath");
          acumulado = (dec ? "" : "(int) ") + "std::pow(" + acumulado + ", " + derecho + ")";
          break;
        case RAIZ: // a // n es la raiz de indice n de a; entre ENT se redondea para que 64 // 3 de 4 y no 3.9999
          includes.add("cmath");
          acumulado = dec ? "std::pow(" + acumulado + ", 1.0 / " + derecho + ")"
                          : "(int) std::round(std::pow(" + acumulado + ", 1.0 / " + derecho + "))";
          break;
        default: throw new IllegalStateException("operador aritmetico inesperado: " + operador);
      }
    }
    return acumulado;
  }

  private String aTexto(String expr, TablaSimbolos.TipoDato tipo, boolean primero) { return aTexto(expr, tipo, primero, false); }

  /** Un operando de una concatenacion como texto; `primero` lo vuelve std::string si hiciera falta. */
  private String aTexto(String expr, TablaSimbolos.TipoDato tipo, boolean primero, boolean cadenaLiteral) {
    includes.add("string");
    switch (tipo) {
      case CAD: return primero && cadenaLiteral ? "std::string(" + expr + ")" : expr;
      case ENT: return "std::to_string(" + expr + ")";
      case CAR: return "std::string(1, " + expr + ")";
      case DEC: usaDecimal = true; return "decimal(" + expr + ")";
      default: // BOO
        String texto = expr + " ? \"VERDADERO\" : \"FALSO\"";
        return primero ? "std::string(" + texto + ")" : "(" + texto + ")";
    }
  }

  /** `a > b`, `NOT a > b` o `NOT a`. Un literal CAD a la izquierda se vuelve std::string ("a" = "b" compararia direcciones). */
  private String relacional(ASTExpresionRelacional nodo) {
    boolean negado = nodo.jjtGetFirstToken().kind == NOT;
    if (nodo.jjtGetNumChildren() == 1) return "!" + operando(nodo.jjtGetChild(0));
    Node izquierdo = nodo.jjtGetChild(0);
    String izquierda = esCadenaLiteral(izquierdo) ? "std::string(" + expresion(izquierdo) + ")" : operando(izquierdo);
    String comparacion = izquierda + " " + simboloRelacional(((SimpleNode) nodo.jjtGetChild(1)).jjtGetFirstToken().kind)
        + " " + operando(nodo.jjtGetChild(2));
    return negado ? "!(" + comparacion + ")" : comparacion;
  }

  /** AND / OR sin precedencia entre ellos, de izquierda a derecha como en JER: `a OR b AND c` es `(a || b) && c`. */
  private String logica(ASTExpresionLogica nodo) {
    String acumulado = operando(nodo.jjtGetChild(0));
    for (int i = 1; i < nodo.jjtGetNumChildren(); i++) {
      int operador = AnalizadorSemantico.operadorDespuesDe(nodo.jjtGetChild(i - 1)).kind;
      if (i > 1) acumulado = "(" + acumulado + ")";
      acumulado += (operador == AND ? " && " : " || ") + operando(nodo.jjtGetChild(i));
    }
    return acumulado;
  }

  private static String simboloRelacional(int operador) {
    switch (operador) {
      case IGUAL: return "==";
      case DIFERENTE: return "!=";
      case MAYOR_QUE: return ">";
      case MENOR_QUE: return "<";
      case MAYOR_IGUAL: return ">=";
      case MENOR_IGUAL: return "<=";
      default: throw new IllegalStateException("operador relacional inesperado: " + operador);
    }
  }

  // Nombres y tipos

  private static TablaSimbolos.Simbolo simbolo(Node nodo) { return AnalizadorSemantico.Anotacion.de(nodo).simbolo; }

  private static TablaSimbolos.TipoDato tipoDe(Node nodo) { return GeneradorCodigo.tipoDe(nodo); }

  /**
   * El nombre de JER, salvo dos casos que C++ no acepta: una palabra reservada de C++ (`int` -> `int_`)
   * y una variable que se llama igual que una funcion, que en C++ ocultaria la funcion (`suma` -> `suma_v`).
   */
  private String nombre(TablaSimbolos.Simbolo s) {
    String n = s.nombre;
    if (s.categoria != TablaSimbolos.Categoria.FUNCION && funciones.contains(n)) n += "_v";
    return RESERVADAS_CPP.contains(n) ? n + "_" : n;
  }

  private String tipoCpp(TablaSimbolos.TipoDato tipo, int dimensiones) {
    String t;
    switch (tipo) {
      case ENT: t = "int"; break;
      case DEC: t = "double"; break;
      case CAD: t = "std::string"; includes.add("string"); break;
      case CAR: t = "char"; break;
      case BOO: t = "bool"; break;
      default: t = "void"; break; // VACIO
    }
    if (dimensiones > 0) includes.add("vector");
    for (int i = 0; i < dimensiones; i++) t = "std::vector<" + t + ">";
    return t;
  }

  private static String valorPorDefecto(TablaSimbolos.TipoDato tipo) {
    switch (tipo) {
      case ENT: return "0";
      case DEC: return "0.0";
      case CAD: return "\"\"";
      case CAR: return "'\\0'";
      default: return "false"; // BOO
    }
  }

  private void linea(String texto) {
    if (!texto.isEmpty()) for (int i = 0; i < nivel; i++) salida.append(SANGRIA);
    salida.append(texto).append('\n');
  }

  /** Solo se agrega si el programa imprime o concatena un DEC: C++ mostraria 3 en vez de 3.0. */
  private static final String DECIMAL =
      "// Un DEC siempre con punto decimal, como en JER (3.0 y no 3).\n"
    + "std::string decimal(double d) {\n"
    + "    std::ostringstream texto;\n"
    + "    texto << d;\n"
    + "    std::string s = texto.str();\n"
    + "    if (s.find_first_of(\".ein\") == std::string::npos) s += \".0\";\n"
    + "    return s;\n"
    + "}\n";
}
