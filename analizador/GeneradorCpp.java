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
 * Lo que C++ no hace igual que JER se resuelve donde aparece: BOO se imprime como VERDADERO/FALSO,
 * DEC siempre con punto decimal (funcion `decimal`), '+' con un CAD convierte el otro lado a texto,
 * un arreglo se pasa por referencia y CONV(tipo, dato) se vuelve una conversion de C++.
 *
 * Errores de ejecucion: lo que el analizador no pudo ver al compilar (division entre cero, indice
 * fuera de rango, tamano de arreglo <= 0, potencia ENT con exponente negativo, raiz invalida, un
 * valor de OBT o de CONV que no es del tipo esperado) se revisa con funciones auxiliares `jer_...`
 * que imprimen `ERROR DE EJECUCION: <causa> (linea N)` y terminan el programa. Los mensajes van sin
 * acentos para que se lean igual en cualquier consola.
 *
 * Todo lo que declara el programa (globales y funciones) va dentro de `namespace jer`, para que un
 * nombre de JER como `abs`, `log`, `time` o `y1` no choque con las funciones de la biblioteca de C.
 *
 * expresion() devuelve el texto C++ de una expresion SIN parentesis exteriores; operando() lo
 * envuelve cuando el nodo es compuesto, porque el AST no guarda los parentesis del fuente.
 */
public final class GeneradorCpp implements JERCompilerConstants {

  // Palabras que un identificador de JER (empieza con letra) puede escribir pero C++ no acepta
  // como nombre: palabras clave, macros de los encabezados incluidos, el namespace `jer` y la funcion
  // auxiliar `decimal`. Los nombres de funciones de la biblioteca de C (abs, log...) no hacen falta:
  // el namespace `jer` los separa. Un nombre que empiece con `jer_` tambien se renombra (ver nombre()).
  private static final Set<String> RESERVADAS_CPP = new HashSet<String>(Arrays.asList((
      "alignas alignof and and_eq asm auto bitand bitor bool break case catch char char16_t char32_t class "
    + "compl const constexpr const_cast continue decltype default delete do double dynamic_cast else enum "
    + "explicit export extern false float for friend goto if inline int long mutable namespace new noexcept "
    + "not not_eq nullptr operator or or_eq private protected public register reinterpret_cast return short "
    + "signed sizeof static static_assert static_cast struct switch template this thread_local throw true try "
    + "typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq "
    + "main std jer decimal NULL EOF errno assert stdin stdout stderr INFINITY NAN HUGE_VAL EXIT_SUCCESS EXIT_FAILURE").split(" ")));

  private static final String SANGRIA = "    ";

  private final StringBuilder salida = new StringBuilder();
  private int nivel = 0;      // sangria actual
  private int evaluares = 0;  // para nombrar el valor y la etiqueta de cada EVALUAR
  private final Set<String> funciones = new HashSet<String>();
  private final Set<String> noSoportado = new LinkedHashSet<String>();
  private final Set<String> definidas = new HashSet<String>();   // funciones ya escritas
  private final Set<String> adelantadas = new HashSet<String>(); // llamadas antes de su definicion: necesitan prototipo
  private final Set<String> includes = new TreeSet<String>();
  private final Set<String> auxiliares = new HashSet<String>();  // funciones auxiliares que el programa usa
  private boolean usaUtf8 = false;                               // hay texto con acentos o ñ: la consola de Windows debe usar UTF-8
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
   * declaracion, igual que `_global` en el codigo intermedio. Los #include, las funciones auxiliares
   * y los prototipos se deciden al terminar, cuando ya se sabe que se uso.
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

    StringBuilder texto = new StringBuilder();
    String auxiliar = textoAuxiliares(); // agrega sus propios #include: va antes de escribirlos
    for (String inc : includes) texto.append("#include <").append(inc).append(">\n");
    texto.append('\n');
    if (usaUtf8) texto.append(DECLARACION_UTF8).append('\n');
    texto.append(auxiliar);
    texto.append("namespace jer {\n\n");
    // Prototipos solo de las funciones que se usan antes de su definicion: en JER el orden no importa, en C++ si.
    for (ASTDeclaracionFuncion f : funcionesDecl) {
      if (adelantadas.contains(simbolo(f).nombre)) texto.append(cabecera(f)).append(";\n");
    }
    if (!adelantadas.isEmpty()) texto.append('\n');
    texto.append(salida);
    texto.append("} // namespace jer\n\n");
    texto.append("int main() {\n");
    if (usaUtf8) texto.append(ACTIVAR_UTF8);
    texto.append(SANGRIA).append("jer::").append(GeneradorCodigo.FUNCION_PRINCIPAL).append("();\n");
    texto.append(SANGRIA).append("return 0;\n");
    texto.append("}\n");
    return texto.toString();
  }

  // Funciones

  /** Un parametro arreglo se recibe por referencia (`std::vector<int>& v`): la funcion trabaja sobre el original. */
  private String cabecera(ASTDeclaracionFuncion nodo) {
    TablaSimbolos.Simbolo f = simbolo(nodo);
    List<String> parametros = new ArrayList<String>();
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) {
      Node hijo = nodo.jjtGetChild(i);
      if (hijo instanceof ASTParametro) {
        TablaSimbolos.Simbolo p = simbolo(hijo);
        parametros.add(tipoCpp(p.tipo, p.aridadArreglo) + (p.aridadArreglo > 0 ? "& " : " ") + nombre(p));
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
      obtener(simbolo(nodo), lineaDe(nodo));
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
   * `std::vector<int> v(3)` o, con literal, `std::vector<int> v = {1, 2, 3}`. Un arreglo CONST se
   * escribe sin `const`: el analizador ya impide modificarlo, y asi se puede leer con jer_elem().
   */
  private String declaracion(Node nodo) {
    TablaSimbolos.Simbolo v = simbolo(nodo);
    boolean constante = nodo instanceof ASTDeclaracionConstante && v.aridadArreglo == 0;
    String texto = (constante ? "const " : "") + tipoCpp(v.tipo, v.aridadArreglo) + " " + nombre(v);
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

  /**
   * Argumentos del constructor de un arreglo de varias dimensiones: `2, std::vector<int>(3)`. Un
   * tamano que no es un literal positivo se revisa al ejecutar con jer_tamanio().
   */
  private String tamanios(Node dimensiones, int i, TablaSimbolos.TipoDato tipo) {
    Node dimension = dimensiones.jjtGetChild(i);
    String tam = expresion(dimension);
    if (enteroLiteral(dimension) == null) { auxiliar("jer_tamanio"); tam = "jer_tamanio(" + tam + ", " + lineaDe(dimension) + ")"; }
    int restantes = dimensiones.jjtGetNumChildren() - i - 1;
    if (restantes == 0) return tam;
    return tam + ", " + tipoCpp(tipo, restantes) + "(" + tamanios(dimensiones, i + 1, tipo) + ")";
  }

  /**
   * OBT: CAD toma la linea completa; los demas leen una palabra y la revisan (un ENT que no es un
   * numero entero, un CAR de mas de una letra o un BOO que no es VERDADERO/FALSO son error de ejecucion).
   */
  private void obtener(TablaSimbolos.Simbolo v, int linea) {
    includes.add("iostream");
    String n = nombre(v);
    switch (v.tipo) {
      case CAD:
        auxiliar("jer_error");
        linea("if (!std::getline(std::cin >> std::ws, " + n + ")) jer_error(\"no hay mas datos para leer\", " + linea + ");");
        break;
      case ENT: auxiliar("jer_aEntero"); linea(n + " = jer_aEntero(jer_palabra(" + linea + "), " + linea + ");"); break;
      case DEC: auxiliar("jer_aDecimal"); linea(n + " = jer_aDecimal(jer_palabra(" + linea + "), " + linea + ");"); break;
      case CAR: auxiliar("jer_leerCaracter"); linea(n + " = jer_leerCaracter(" + linea + ");"); break;
      default: auxiliar("jer_leerBooleano"); linea(n + " = jer_leerBooleano(" + linea + ");"); break; // BOO
    }
  }

  private void asignacionOLlamada(ASTAsignacionOLlamada nodo) {
    Token siguiente = nodo.jjtGetFirstToken().next;
    if (siguiente.kind == APERTURA_PAREN) { linea(llamada(nodo) + ";"); return; }
    TablaSimbolos.Simbolo v = simbolo(nodo);
    String destino = nombre(v);
    TablaSimbolos.TipoDato tipo = v.tipo;
    int operador;
    Node valor = null;
    if (siguiente.kind == APERTURA_CORCHETE) {
      int posicion = AnalizadorSemantico.posicionOperadorAsignacion(nodo); // -1 con ++ / --
      int indices = posicion >= 0 ? posicion : nodo.jjtGetNumChildren();
      destino = accesos(destino, v, nodo, indices);
      if (AnalizadorSemantico.esIndiceDeCadena(v, indices)) tipo = TablaSimbolos.TipoDato.CAR;
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
    linea(asignacion(destino, tipo, operador, valor) + ";");
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

  /**
   * `v[i][j]` con los primeros `cantidad` hijos de `nodo` como indices. Cada indice se revisa al
   * ejecutar con jer_elem(), salvo un literal en una dimension de tamano literal: ese ya lo reviso el
   * analizador, y queda como `v[2]`.
   */
  private String accesos(String base, TablaSimbolos.Simbolo arreglo, Node nodo, int cantidad) {
    String texto = base;
    for (int i = 0; i < cantidad; i++) {
      Node indice = nodo.jjtGetChild(i);
      Long literal = enteroLiteral(indice);
      boolean revisado = literal != null && arreglo.tamanios != null && i < arreglo.tamanios.length && arreglo.tamanios[i] > 0;
      if (revisado) {
        texto += "[" + literal + "]";
      } else {
        auxiliar("jer_elem");
        texto = "jer_elem(" + texto + ", " + expresion(indice) + ", " + lineaDe(indice) + ")";
      }
    }
    return texto;
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
   * sale del bucle que rodea al EVALUAR (`break`, que funciona porque el if no es un switch); solo si
   * no hay bucle sale del EVALUAR con `goto _fin_evaluarN`, etiqueta que solo se escribe si se usa.
   * Los nombres con '_' no chocan: los de JER empiezan con letra.
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

  /** `break` si hay un bucle abierto (aunque este dentro de un EVALUAR); si no, sale del EVALUAR. */
  private void terminar() {
    if (salidas.isEmpty()) { noSoportado.add("TERMINAR fuera de un bucle"); return; }
    if (salidas.contains("")) { linea("break;"); return; }
    String destino = salidas.peek();
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
    if (nodo instanceof ASTConversion) return conversion((ASTConversion) nodo);
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
    if (tipo == TablaSimbolos.TipoDato.DEC) { auxiliar("decimal"); return "decimal(" + expresion(nodo) + ")"; }
    return expresion(nodo);
  }

  private String valorSimple(ASTValorSimple nodo) {
    Token t = nodo.jjtGetFirstToken();
    if (t.kind == VERDADERO) return "true";
    if (t.kind == FALSO) return "false";
    if (t.kind == CADENA && !t.image.chars().allMatch(c -> c < 128)) usaUtf8 = true;
    if (t.kind != IDENTIFICADOR) return t.image; // numeros, cadenas y caracteres se escriben igual en C++
    TablaSimbolos.Simbolo s = simbolo(nodo);
    return accesos(nombre(s), s, nodo, nodo.jjtGetNumChildren());
  }

  private static boolean esCadenaLiteral(Node nodo) {
    return nodo instanceof ASTValorSimple && ((SimpleNode) nodo).jjtGetFirstToken().kind == CADENA;
  }

  /**
   * `f(a, b)`; `nodo` es una LlamadaFuncion o una AsignacionOLlamada usada como sentencia. Un
   * argumento arreglo es solo su nombre (la funcion lo recibe por referencia). LONG(x) es `x.size()`.
   */
  private String llamada(SimpleNode nodo) {
    TablaSimbolos.Simbolo f = simbolo(nodo);
    if (f.predefinida) { // LONG: cantidad de caracteres de una CAD o tamano de un arreglo
      Node argumento = nodo.jjtGetChild(0);
      String texto = esCadenaLiteral(argumento) ? "std::string(" + expresion(argumento) + ")" : operando(argumento);
      return "(int) " + texto + ".size()";
    }
    List<String> argumentos = new ArrayList<String>();
    for (int i = 0; i < nodo.jjtGetNumChildren(); i++) argumentos.add(expresion(nodo.jjtGetChild(i)));
    if (!definidas.contains(f.nombre)) adelantadas.add(f.nombre);
    return nombre(f) + "(" + String.join(", ", argumentos) + ")";
  }

  /**
   * CONV(tipo, dato): ENT -> DEC `(double) x`; DEC -> ENT `(int) x` (C++ tambien trunca hacia cero);
   * CAR <-> ENT `(int) c` / `(char) n`; a CAD con el mismo texto que da la concatenacion; y CAD -> ENT/DEC
   * con jer_aEntero()/jer_aDecimal(), que revisan al ejecutar que el texto sea un numero.
   */
  private String conversion(ASTConversion nodo) {
    TablaSimbolos.TipoDato destino = tipoDe(nodo);
    Node dato = nodo.jjtGetChild(0);
    TablaSimbolos.TipoDato origen = tipoDe(dato);
    if (destino == TablaSimbolos.TipoDato.CAD) return aTexto(operando(dato), origen, true, esCadenaLiteral(dato));
    if (origen == TablaSimbolos.TipoDato.CAD) {
      String funcion = destino == TablaSimbolos.TipoDato.ENT ? "jer_aEntero" : "jer_aDecimal";
      auxiliar(funcion);
      return funcion + "(" + expresion(dato) + ", " + lineaDe(nodo) + ")";
    }
    return "(" + tipoCpp(destino, 0) + ") " + operando(dato);
  }

  /**
   * Expresion / Termino / Factor, de izquierda a derecha. `+` con un CAD de cualquier lado pasa la
   * cadena a CAD (regla del analizador): desde ahi todo se convierte a std::string con aTexto().
   * `/` y `%` revisan el divisor al ejecutar salvo que sea un literal distinto de 0; `**` entre ENT es
   * jer_potencia() (exacta, sin pasar por double) y `//` es jer_raiz().
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
      Token operadorTok = AnalizadorSemantico.operadorDespuesDe(nodo.jjtGetChild(i - 1));
      int operador = operadorTok.kind;
      int linea = operadorTok.beginLine;
      if (operador == SUMA && (tipo == TablaSimbolos.TipoDato.CAD || tipoHijo == TablaSimbolos.TipoDato.CAD)) {
        // Solo el primer operando necesita ser std::string: "a" + "b" entre literales no compila.
        if (!esTexto) { acumulado = aTexto(acumulado, tipo, true, literal); esTexto = true; }
        acumulado += " + " + aTexto(derecho, tipoHijo, false);
        tipo = TablaSimbolos.TipoDato.CAD;
        continue;
      }
      literal = false;
      boolean dec = tipo == TablaSimbolos.TipoDato.DEC;
      boolean divisorSeguro = esNumeroLiteralNoCero(hijo);
      switch (operador) {
        case SUMA: acumulado += " + " + derecho; break;
        case RESTA: acumulado += " - " + derecho; break;
        case MULT: acumulado += " * " + derecho; break;
        case DIV:
          if (divisorSeguro) acumulado += " / " + derecho;
          else { auxiliar("jer_dividir"); acumulado = "jer_dividir(" + acumulado + ", " + derecho + ", " + linea + ")"; }
          break;
        case MODULO:
          if (dec) { auxiliar("jer_modulo"); acumulado = "jer_modulo(" + acumulado + ", " + derecho + ", " + linea + ")"; }
          else if (divisorSeguro) acumulado += " % " + derecho;
          else { auxiliar("jer_modulo"); acumulado = "jer_modulo(" + acumulado + ", " + derecho + ", " + linea + ")"; }
          break;
        case POTENCIA:
          if (dec) { includes.add("cmath"); acumulado = "std::pow(" + acumulado + ", " + derecho + ")"; }
          else { auxiliar("jer_potencia"); acumulado = "jer_potencia(" + acumulado + ", " + derecho + ", " + linea + ")"; }
          break;
        case RAIZ: // a // n es la raiz de indice n de a
          auxiliar("jer_raiz");
          acumulado = "jer_raiz(" + acumulado + ", " + derecho + ", " + linea + ")";
          break;
        default: throw new IllegalStateException("operador aritmetico inesperado: " + operador);
      }
    }
    return acumulado;
  }

  /** true si el nodo es un literal ENT o DEC distinto de 0: como divisor no puede fallar. */
  private static boolean esNumeroLiteralNoCero(Node nodo) {
    if (!(nodo instanceof ASTValorSimple) || nodo.jjtGetNumChildren() != 0) return false;
    Token t = ((SimpleNode) nodo).jjtGetFirstToken();
    return (t.kind == NUMERO_ENTERO || t.kind == NUMERO_DECIMAL) && !t.image.replace(".", "").matches("0+");
  }

  private String aTexto(String expr, TablaSimbolos.TipoDato tipo, boolean primero) { return aTexto(expr, tipo, primero, false); }

  /** Un operando de una concatenacion como texto; `primero` lo vuelve std::string si hiciera falta. */
  private String aTexto(String expr, TablaSimbolos.TipoDato tipo, boolean primero, boolean cadenaLiteral) {
    includes.add("string");
    switch (tipo) {
      case CAD: return primero && cadenaLiteral ? "std::string(" + expr + ")" : expr;
      case ENT: return "std::to_string(" + expr + ")";
      case CAR: return "std::string(1, " + expr + ")";
      case DEC: auxiliar("decimal"); return "decimal(" + expr + ")";
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

  // Nombres, tipos y lineas

  private static TablaSimbolos.Simbolo simbolo(Node nodo) { return AnalizadorSemantico.Anotacion.de(nodo).simbolo; }

  private static TablaSimbolos.TipoDato tipoDe(Node nodo) { return GeneradorCodigo.tipoDe(nodo); }

  /** Linea del fuente JER donde empieza el nodo, para los mensajes de error de ejecucion. */
  private static int lineaDe(Node nodo) { return ((SimpleNode) nodo).jjtGetFirstToken().beginLine; }

  /** Valor de un literal entero escrito tal cual (sin signo); null si el nodo es otra cosa. */
  private static Long enteroLiteral(Node nodo) {
    if (!(nodo instanceof ASTValorSimple) || nodo.jjtGetNumChildren() != 0) return null;
    Token t = ((SimpleNode) nodo).jjtGetFirstToken();
    if (t.kind != NUMERO_ENTERO) return null;
    try { return Long.parseLong(t.image); } catch (NumberFormatException e) { return null; }
  }

  /**
   * El nombre de JER, salvo tres casos que C++ no acepta: una palabra reservada de C++ (`int` -> `int_`),
   * un nombre que empieza con `jer_` (lo usan las funciones auxiliares) y una variable que se llama igual
   * que una funcion, que en C++ ocultaria la funcion (`suma` -> `suma_v`).
   */
  private String nombre(TablaSimbolos.Simbolo s) {
    String n = s.nombre;
    if (s.categoria != TablaSimbolos.Categoria.FUNCION && funciones.contains(n)) n += "_v";
    return RESERVADAS_CPP.contains(n) || n.startsWith("jer_") ? n + "_" : n;
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

  // Funciones auxiliares (solo se escriben las que el programa usa, y las que estas necesitan)

  /** Marca una funcion auxiliar como usada, junto con las que ella llama. */
  private void auxiliar(String nombre) {
    if (!auxiliares.add(nombre)) return;
    for (String requerida : REQUIERE.getOrDefault(nombre, new String[0])) auxiliar(requerida);
  }

  private static final Map<String, String[]> REQUIERE = new HashMap<String, String[]>();
  static {
    REQUIERE.put("jer_dividir", new String[] { "jer_error" });
    REQUIERE.put("jer_modulo", new String[] { "jer_error" });
    REQUIERE.put("jer_potencia", new String[] { "jer_error" });
    REQUIERE.put("jer_raiz", new String[] { "jer_error" });
    REQUIERE.put("jer_elem", new String[] { "jer_error" });
    REQUIERE.put("jer_tamanio", new String[] { "jer_error" });
    REQUIERE.put("jer_aEntero", new String[] { "jer_error", "jer_palabra" });
    REQUIERE.put("jer_aDecimal", new String[] { "jer_error", "jer_palabra" });
    REQUIERE.put("jer_leerCaracter", new String[] { "jer_error", "jer_palabra" });
    REQUIERE.put("jer_leerBooleano", new String[] { "jer_error", "jer_palabra" });
    REQUIERE.put("jer_palabra", new String[] { "jer_error" });
  }

  /** El texto de las auxiliares usadas, en un orden fijo en que cada una ya conoce a las que llama. */
  private String textoAuxiliares() {
    StringBuilder texto = new StringBuilder();
    for (String[] aux : AUXILIARES) {
      if (!auxiliares.contains(aux[0])) continue;
      for (String inc : aux[1].split(" ")) if (!inc.isEmpty()) includes.add(inc);
      texto.append(aux[2]).append('\n');
    }
    return texto.toString();
  }

  /** {nombre, #include que necesita, codigo}. */
  private static final String[][] AUXILIARES = {
    { "decimal", "iomanip sstream string",
        "// Un DEC siempre con punto decimal, como en JER (3.0 y no 3), y con hasta 15 cifras.\n"
      + "std::string decimal(double d) {\n"
      + "    std::ostringstream texto;\n"
      + "    texto << std::setprecision(15) << d;\n"
      + "    std::string s = texto.str();\n"
      + "    if (s.find_first_of(\".ein\") == std::string::npos) s += \".0\";\n"
      + "    return s;\n"
      + "}\n" },
    { "jer_error", "cstdlib iostream string",
        "// Error de ejecucion: avisa en que linea del programa JER ocurrio y termina el programa.\n"
      + "[[noreturn]] void jer_error(const std::string& causa, int linea) {\n"
      + "    std::cout.flush();\n"
      + "    std::cerr << \"ERROR DE EJECUCION: \" << causa << \" (linea \" << linea << \")\" << std::endl;\n"
      + "    std::exit(1);\n"
      + "}\n" },
    { "jer_dividir", "",
        "int jer_dividir(int a, int b, int linea) {\n"
      + "    if (b == 0) jer_error(\"division entre cero\", linea);\n"
      + "    return a / b;\n"
      + "}\n\n"
      + "double jer_dividir(double a, double b, int linea) {\n"
      + "    if (b == 0.0) jer_error(\"division entre cero\", linea);\n"
      + "    return a / b;\n"
      + "}\n" },
    { "jer_modulo", "cmath",
        "int jer_modulo(int a, int b, int linea) {\n"
      + "    if (b == 0) jer_error(\"modulo entre cero\", linea);\n"
      + "    return a % b;\n"
      + "}\n\n"
      + "double jer_modulo(double a, double b, int linea) {\n"
      + "    if (b == 0.0) jer_error(\"modulo entre cero\", linea);\n"
      + "    return std::fmod(a, b);\n"
      + "}\n" },
    { "jer_potencia", "",
        "// Potencia entre ENT, exacta (std::pow trabaja con double y puede dar 99 en vez de 100).\n"
      + "int jer_potencia(int base, int exponente, int linea) {\n"
      + "    if (exponente < 0) jer_error(\"potencia entre ENT con exponente negativo\", linea);\n"
      + "    int resultado = 1;\n"
      + "    for (int i = 0; i < exponente; i++) resultado *= base;\n"
      + "    return resultado;\n"
      + "}\n" },
    { "jer_raiz", "cmath",
        "// a // n: raiz de indice n de a. Con base negativa solo hay raiz si el indice es entero e impar.\n"
      + "double jer_raiz(double a, double n, int linea) {\n"
      + "    if (n == 0.0) jer_error(\"raiz de indice 0\", linea);\n"
      + "    if (a < 0.0) {\n"
      + "        if (n != std::floor(n) || std::fmod(n, 2.0) == 0.0) jer_error(\"raiz de indice par de un numero negativo\", linea);\n"
      + "        return -std::pow(-a, 1.0 / n);\n"
      + "    }\n"
      + "    return std::pow(a, 1.0 / n);\n"
      + "}\n\n"
      + "// Entre ENT se redondea para que 64 // 3 de 4 y no 3.\n"
      + "int jer_raiz(int a, int n, int linea) {\n"
      + "    return (int) std::round(jer_raiz((double) a, (double) n, linea));\n"
      + "}\n" },
    { "jer_elem", "string vector",
        "// Elemento i de un arreglo (o caracter i de una CAD), revisando que el indice exista.\n"
      + "template <typename T>\n"
      + "T& jer_elem(std::vector<T>& v, int i, int linea) {\n"
      + "    if (i < 0 || i >= (int) v.size()) jer_error(\"indice \" + std::to_string(i) + \" fuera de rango (tamano \" + std::to_string(v.size()) + \")\", linea);\n"
      + "    return v[i];\n"
      + "}\n\n"
      + "std::vector<bool>::reference jer_elem(std::vector<bool>& v, int i, int linea) {\n"
      + "    if (i < 0 || i >= (int) v.size()) jer_error(\"indice \" + std::to_string(i) + \" fuera de rango (tamano \" + std::to_string(v.size()) + \")\", linea);\n"
      + "    return v[i];\n"
      + "}\n\n"
      + "char& jer_elem(std::string& s, int i, int linea) {\n"
      + "    if (i < 0 || i >= (int) s.size()) jer_error(\"indice \" + std::to_string(i) + \" fuera de la cadena (longitud \" + std::to_string(s.size()) + \")\", linea);\n"
      + "    return s[i];\n"
      + "}\n\n"
      + "char jer_elem(const std::string& s, int i, int linea) {\n"
      + "    if (i < 0 || i >= (int) s.size()) jer_error(\"indice \" + std::to_string(i) + \" fuera de la cadena (longitud \" + std::to_string(s.size()) + \")\", linea);\n"
      + "    return s[i];\n"
      + "}\n" },
    { "jer_tamanio", "string",
        "int jer_tamanio(int n, int linea) {\n"
      + "    if (n <= 0) jer_error(\"tamano de arreglo \" + std::to_string(n) + \" (debe ser mayor que 0)\", linea);\n"
      + "    return n;\n"
      + "}\n" },
    { "jer_palabra", "iostream string",
        "// Siguiente palabra de la entrada (OBT de ENT, DEC, CAR o BOO).\n"
      + "std::string jer_palabra(int linea) {\n"
      + "    std::string palabra;\n"
      + "    if (!(std::cin >> palabra)) jer_error(\"no hay mas datos para leer\", linea);\n"
      + "    return palabra;\n"
      + "}\n" },
    { "jer_aEntero", "string",
        "// Texto a ENT (OBT y CONV(ENT, texto)): todo el texto debe ser un numero entero.\n"
      + "int jer_aEntero(const std::string& texto, int linea) {\n"
      + "    std::size_t usados = 0;\n"
      + "    int valor = 0;\n"
      + "    try { valor = std::stoi(texto, &usados); } catch (...) { usados = 0; }\n"
      + "    if (usados == 0 || usados != texto.size()) jer_error(\"'\" + texto + \"' no es un ENT\", linea);\n"
      + "    return valor;\n"
      + "}\n" },
    { "jer_aDecimal", "string",
        "// Texto a DEC (OBT y CONV(DEC, texto)): todo el texto debe ser un numero.\n"
      + "double jer_aDecimal(const std::string& texto, int linea) {\n"
      + "    std::size_t usados = 0;\n"
      + "    double valor = 0.0;\n"
      + "    try { valor = std::stod(texto, &usados); } catch (...) { usados = 0; }\n"
      + "    if (usados == 0 || usados != texto.size()) jer_error(\"'\" + texto + \"' no es un DEC\", linea);\n"
      + "    return valor;\n"
      + "}\n" },
    { "jer_leerCaracter", "string",
        "char jer_leerCaracter(int linea) {\n"
      + "    std::string palabra = jer_palabra(linea);\n"
      + "    if (palabra.size() != 1) jer_error(\"'\" + palabra + \"' no es un CAR (debe ser un solo caracter)\", linea);\n"
      + "    return palabra[0];\n"
      + "}\n" },
    { "jer_leerBooleano", "string",
        "bool jer_leerBooleano(int linea) {\n"
      + "    std::string palabra = jer_palabra(linea);\n"
      + "    if (palabra == \"VERDADERO\") return true;\n"
      + "    if (palabra == \"FALSO\") return false;\n"
      + "    jer_error(\"'\" + palabra + \"' no es un BOO (escriba VERDADERO o FALSO)\", linea);\n"
      + "}\n" },
  };

  /**
   * La consola de Windows no usa UTF-8 por defecto y mostraria mal los acentos y la ñ. Se declaran
   * solo las dos funciones de Windows que hacen falta, sin incluir <windows.h> (trae macros como
   * min/max que chocarian con nombres del programa).
   */
  private static final String DECLARACION_UTF8 =
      "#ifdef _WIN32\n"
    + "// Para que la consola de Windows muestre bien los acentos y la ñ.\n"
    + "extern \"C\" __declspec(dllimport) int __stdcall SetConsoleOutputCP(unsigned int);\n"
    + "extern \"C\" __declspec(dllimport) int __stdcall SetConsoleCP(unsigned int);\n"
    + "#endif\n";

  private static final String ACTIVAR_UTF8 =
      "#ifdef _WIN32\n"
    + SANGRIA + "SetConsoleOutputCP(65001);\n"
    + SANGRIA + "SetConsoleCP(65001);\n"
    + "#endif\n";
}
