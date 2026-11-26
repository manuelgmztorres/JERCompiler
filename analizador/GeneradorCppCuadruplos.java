import java.util.*;
import java.util.regex.Pattern;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * Codigo objeto a partir del codigo intermedio OPTIMIZADO: lee la tabla de cuadruplos (ya cerrada, con
 * sus filas FUN y ETIQ) y escribe C++ para compilarlo con MinGW (.s, .o y .exe). No mira el AST: lo que
 * el optimizador plego o propago es lo que llega al ejecutable.
 *
 * Cada fila se vuelve una instruccion de C++ y los saltos son `goto` a una etiqueta `_L1:`. C++ no deja
 * saltar por encima de una declaracion con valor inicial, asi que todas las variables y temporales de una
 * funcion se declaran juntos al inicio (con su tipo, que sale de la columna `tipo` de la tabla) y el cuerpo
 * solo asigna. Un temporal que es una FILA de una matriz (`t1 = m[i]`) es un puntero al original, porque
 * una referencia no se puede declarar antes de saber a que apunta.
 *
 * Los textos de las funciones auxiliares (division entre cero, indices, OBT...) estan en RecursosCpp;
 * los errores de ejecucion dicen la linea del fuente gracias al campo `linea` de cada cuadruplo.
 */
public final class GeneradorCppCuadruplos {

  private static final Pattern ENT = Pattern.compile("-?\\d+");
  private static final Pattern DEC = Pattern.compile("-?\\d+\\.\\d+");
  private static final String SANGRIA = RecursosCpp.SANGRIA;

  private final Map<String, TablaSimbolos.Simbolo> funcionesSimbolo = new HashMap<String, TablaSimbolos.Simbolo>();
  private final Map<String, TablaSimbolos.TipoDato> tiposGlobales = new HashMap<String, TablaSimbolos.TipoDato>();
  private final Map<String, Integer> dimsGlobales = new HashMap<String, Integer>();
  private final List<TablaSimbolos.Simbolo> globales = new ArrayList<TablaSimbolos.Simbolo>();

  // Estado de la funcion en curso.
  private final Map<String, TablaSimbolos.TipoDato> tipos = new HashMap<String, TablaSimbolos.TipoDato>();
  private final Map<String, Integer> dims = new HashMap<String, Integer>();
  private final Set<String> filasPuntero = new HashSet<String>();            // temporales que apuntan a la fila de una matriz
  private final List<TablaCuadruplos.Cuadruplo> parametrosPendientes = new ArrayList<TablaCuadruplos.Cuadruplo>();
  private final List<String> dimensionesPendientes = new ArrayList<String>();
  private boolean funcionConValor = false;

  private final StringBuilder salida = new StringBuilder();
  private final Set<String> includes = new TreeSet<String>();
  private final Set<String> auxiliares = new HashSet<String>();
  private final Set<String> noSoportado = new LinkedHashSet<String>();
  private boolean usaUtf8 = false;

  public GeneradorCppCuadruplos(TablaSimbolos simbolos) {
    for (TablaSimbolos.Simbolo s : simbolos.todos()) {
      if (s.categoria == TablaSimbolos.Categoria.FUNCION) funcionesSimbolo.put(s.nombre, s);
      else if ("GLOBAL".equals(s.ambito)) {
        globales.add(s);
        tiposGlobales.put(s.nombre, s.tipo);
        dimsGlobales.put(s.nombre, s.aridadArreglo);
      }
    }
  }

  /** Construcciones que el codigo intermedio trae y esta version no sabe traducir (vacio = traduccion completa). */
  public Set<String> noSoportado() { return Collections.unmodifiableSet(noSoportado); }

  public String generar(TablaCuadruplos tabla) {
    List<List<TablaCuadruplos.Cuadruplo>> funciones = new ArrayList<List<TablaCuadruplos.Cuadruplo>>();
    for (TablaCuadruplos.Cuadruplo f : tabla.filas()) {
      if (f.operador.equals("FUN")) funciones.add(new ArrayList<TablaCuadruplos.Cuadruplo>());
      funciones.get(funciones.size() - 1).add(f);
    }

    StringBuilder prototipos = new StringBuilder();
    for (List<TablaCuadruplos.Cuadruplo> filas : funciones) prototipos.append(cabecera(filas, false)).append(";\n");
    for (TablaSimbolos.Simbolo g : globales) {
      salida.append(tipoCpp(g.tipo, g.aridadArreglo)).append(' ').append(nombre(g.nombre));
      if (g.aridadArreglo == 0) salida.append(" = ").append(valorPorDefecto(g.tipo));
      salida.append(";\n");
    }
    if (!globales.isEmpty()) salida.append('\n');
    for (List<TablaCuadruplos.Cuadruplo> filas : funciones) funcion(filas);

    StringBuilder texto = new StringBuilder();
    String auxiliar = textoAuxiliares(); // agrega sus propios #include: va antes de escribirlos
    for (String inc : includes) texto.append("#include <").append(inc).append(">\n");
    texto.append('\n');
    if (usaUtf8) texto.append(RecursosCpp.DECLARACION_UTF8).append('\n');
    texto.append(auxiliar);
    texto.append("namespace jer {\n\n");
    texto.append(prototipos).append('\n');
    texto.append(salida);
    texto.append("} // namespace jer\n\n");
    texto.append("int main() {\n");
    if (usaUtf8) texto.append(RecursosCpp.ACTIVAR_UTF8);
    texto.append(SANGRIA).append("jer::").append(GeneradorCodigo.FUNCION_INICIO).append("();\n");
    texto.append(SANGRIA).append("return 0;\n");
    texto.append("}\n");
    return texto.toString();
  }

  // Funciones

  /** `int f(int a, std::vector<int>& v)`; con `conNombres` false (para el prototipo) solo los tipos. */
  private String cabecera(List<TablaCuadruplos.Cuadruplo> filas, boolean conNombres) {
    String nombre = filas.get(0).arg1;
    TablaSimbolos.Simbolo f = funcionesSimbolo.get(nombre); // null en _inicio y _global
    List<String> parametros = new ArrayList<String>();
    int k = 0;
    for (TablaCuadruplos.Cuadruplo c : filas) {
      if (!c.operador.equals("RECIBIR")) continue;
      int d = f != null && f.aridadesParametros != null ? f.aridadesParametros.get(k) : 0;
      k++;
      parametros.add(tipoCpp(c.tipo, d) + (d > 0 ? "&" : "") + (conNombres ? " " + nombre(c.resultado) : ""));
    }
    return tipoCpp(f == null ? TablaSimbolos.TipoDato.VACIO : f.tipo, 0) + " " + nombreFuncion(nombre) + "(" + String.join(", ", parametros) + ")";
  }

  private void funcion(List<TablaCuadruplos.Cuadruplo> filas) {
    tipos.clear(); dims.clear(); filasPuntero.clear(); parametrosPendientes.clear(); dimensionesPendientes.clear();
    String nombre = filas.get(0).arg1;
    TablaSimbolos.Simbolo f = funcionesSimbolo.get(nombre);
    funcionConValor = f != null && f.tipo != TablaSimbolos.TipoDato.VACIO;

    // Parametros primero (tienen nombre propio), despues lo que se defina en el cuerpo, en orden de aparicion.
    int k = 0;
    for (TablaCuadruplos.Cuadruplo c : filas) {
      if (!c.operador.equals("RECIBIR")) continue;
      tipos.put(c.resultado, c.tipo);
      dims.put(c.resultado, f != null && f.aridadesParametros != null ? f.aridadesParametros.get(k) : 0);
      k++;
    }
    Set<String> parametros = new HashSet<String>(tipos.keySet());
    List<String> locales = new ArrayList<String>();
    for (TablaCuadruplos.Cuadruplo c : filas) definir(c, locales);

    salida.append(cabecera(filas, true)).append(" {\n");
    for (String local : locales) {
      if (parametros.contains(local)) continue;
      TablaSimbolos.TipoDato t = tipos.get(local);
      int d = dims.get(local);
      String declaracion;
      if (filasPuntero.contains(local)) declaracion = tipoCpp(t, d) + "* " + nombre(local) + " = nullptr";
      else if (d > 0) declaracion = tipoCpp(t, d) + " " + nombre(local);
      else declaracion = tipoCpp(t, 0) + " " + nombre(local) + " = " + valorPorDefecto(t);
      salida.append(SANGRIA).append(declaracion).append(";\n");
    }
    if (!locales.isEmpty()) salida.append('\n');
    for (TablaCuadruplos.Cuadruplo c : filas) fila(c);
    salida.append("}\n\n");
  }

  /** Anota el tipo del resultado de la fila si es la primera vez que aparece (ya no es global ni parametro). */
  private void definir(TablaCuadruplos.Cuadruplo f, List<String> locales) {
    String r = f.resultado;
    if (r == null) return;
    switch (f.operador) {
      case "ASIG": case "OBT": case "LLAMAR": case "NEG": case "NOT":
      case "+": case "-": case "*": case "/": case "%": case "**": case "//":
      case "==": case "!=": case "<": case "<=": case ">": case ">=":
        definir(r, f.tipo, 0, false, locales); break;
      case "RESERVAR": definir(r, f.tipo, Integer.parseInt(f.arg2), false, locales); break;
      case "LEER_ELEM": {
        int d = dimsDe(f.arg1);
        if (d > 1) definir(r, f.tipo, d - 1, true, locales); else definir(r, f.tipo, 0, false, locales);
        break;
      }
      default: break;
    }
  }

  private void definir(String nombre, TablaSimbolos.TipoDato tipo, int d, boolean puntero, List<String> locales) {
    if (tipos.containsKey(nombre) || tiposGlobales.containsKey(nombre) || tipo == null) return;
    tipos.put(nombre, tipo);
    dims.put(nombre, d);
    if (puntero) filasPuntero.add(nombre);
    locales.add(nombre);
  }

  // Cada fila, una instruccion

  private void fila(TablaCuadruplos.Cuadruplo f) {
    String a = f.arg1, b = f.arg2, r = f.resultado;
    switch (f.operador) {
      case "FUN": case "RECIBIR": case "DIM": break; // DIM se junta para el RESERVAR que sigue
      case "ETIQ": salida.append(etiqueta(r)).append(":;\n"); break;
      case "ASIG": linea(destino(r) + " = " + valor(a) + ";"); break;
      case "NEG": linea(destino(r) + " = -(" + valor(a) + ");"); break;
      case "NOT": linea(destino(r) + " = !" + valor(a) + ";"); break;
      case "SALTO": linea("goto " + etiqueta(r) + ";"); break;
      case "SALTO_VER": linea("if (" + valor(a) + ") goto " + etiqueta(r) + ";"); break;
      case "SALTO_FAL": linea("if (!" + valor(a) + ") goto " + etiqueta(r) + ";"); break;
      case "PARAM": parametrosPendientes.add(f); break;
      case "LLAMAR": llamar(f); break;
      // Un RET sin valor en una funcion con valor es el cierre que agrega el generador (nunca se ejecuta): `return {};` vale para cualquier tipo.
      case "RET": linea(a != null ? "return " + valor(a) + ";" : funcionConValor ? "return {};" : "return;"); break;
      case "IMP": imprimir(a); break;
      case "OBT": obtener(r, f.tipo, f.linea); break;
      case "RESERVAR": reservar(f); break;
      case "LEER_ELEM": leerElemento(f); break;
      case "ASIG_ELEM": linea(elemento(r, b, f.linea) + " = " + valor(a) + ";"); break;
      case "+": case "-": case "*": case "/": case "%": case "**": case "//":
      case "==": case "!=": case "<": case "<=": case ">": case ">=": linea(destino(r) + " = " + operacion(f) + ";"); break;
      default: noSoportado.add("operador " + f.operador);
    }
    if (f.operador.equals("DIM")) dimensionesPendientes.add(a);
  }

  private String operacion(TablaCuadruplos.Cuadruplo f) {
    String op = f.operador, a = f.arg1, b = f.arg2;
    TablaSimbolos.TipoDato t = f.tipo;
    String x = valor(a), y = valor(b);
    switch (op) {
      case "+":
        if (t == TablaSimbolos.TipoDato.CAD) return aTexto(a, true) + " + " + aTexto(b, false);
        return x + " + " + y;
      case "-": return x + " - " + y;
      case "*": return x + " * " + y;
      case "/":
        if (divisorSeguro(b)) return x + " / " + y;
        auxiliar("jer_dividir"); return "jer_dividir(" + x + ", " + y + ", " + f.linea + ")";
      case "%":
        if (t != TablaSimbolos.TipoDato.DEC && divisorSeguro(b)) return x + " % " + y;
        auxiliar("jer_modulo"); return "jer_modulo(" + x + ", " + y + ", " + f.linea + ")";
      case "**":
        if (t == TablaSimbolos.TipoDato.DEC) { includes.add("cmath"); return "std::pow(" + x + ", " + y + ")"; }
        auxiliar("jer_potencia"); return "jer_potencia(" + x + ", " + y + ", " + f.linea + ")";
      case "//": auxiliar("jer_raiz"); return "jer_raiz(" + x + ", " + y + ", " + f.linea + ")";
      default: // comparaciones: un literal CAD a la izquierda se vuelve std::string ("a" == "b" compararia direcciones)
        if (a.startsWith("\"")) { includes.add("string"); x = "std::string(" + x + ")"; }
        return x + " " + op + " " + y;
    }
  }

  /** Un divisor que es un literal numerico distinto de 0 no puede fallar: no necesita la revision en ejecucion. */
  private static boolean divisorSeguro(String b) {
    return (ENT.matcher(b).matches() || DEC.matcher(b).matches()) && !b.replace("-", "").replace(".", "").matches("0+");
  }

  /** Un operando de una concatenacion como texto; `primero` lo vuelve std::string si hiciera falta. */
  private String aTexto(String operando, boolean primero) {
    includes.add("string");
    TablaSimbolos.TipoDato t = tipoDe(operando);
    String x = valor(operando);
    if (t == null) { noSoportado.add("tipo desconocido de " + operando); return x; }
    switch (t) {
      case CAD: return primero && operando.startsWith("\"") ? "std::string(" + x + ")" : x;
      case ENT: return "std::to_string(" + x + ")";
      case CAR: return "std::string(1, " + x + ")";
      case DEC: auxiliar("decimal"); return "decimal(" + x + ")";
      default: // BOO
        String texto = x + " ? \"VERDADERO\" : \"FALSO\"";
        return primero ? "std::string(" + texto + ")" : "(" + texto + ")";
    }
  }

  private void imprimir(String a) {
    includes.add("iostream");
    TablaSimbolos.TipoDato t = tipoDe(a);
    String x = valor(a);
    if (t == TablaSimbolos.TipoDato.BOO) x = "(" + x + " ? \"VERDADERO\" : \"FALSO\")";
    else if (t == TablaSimbolos.TipoDato.DEC) { auxiliar("decimal"); x = "decimal(" + x + ")"; }
    linea("std::cout << " + x + " << std::endl;");
  }

  /**
   * OBT: CAD toma la linea completa; los demas leen una palabra y la revisan (un ENT que no es un
   * numero entero, un CAR de mas de una letra o un BOO que no es VERDADERO/FALSO son error de ejecucion).
   */
  private void obtener(String variable, TablaSimbolos.TipoDato tipo, int linea) {
    includes.add("iostream");
    String n = destino(variable);
    switch (tipo) {
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

  // Llamadas

  /** Los `n` PARAM que preceden a la fila son los argumentos, en orden. */
  private void llamar(TablaCuadruplos.Cuadruplo f) {
    int n = Integer.parseInt(f.arg2);
    List<TablaCuadruplos.Cuadruplo> lista = parametrosPendientes.subList(parametrosPendientes.size() - n, parametrosPendientes.size());
    List<TablaCuadruplos.Cuadruplo> argumentos = new ArrayList<TablaCuadruplos.Cuadruplo>(lista);
    lista.clear();
    String llamada;
    TablaSimbolos.Simbolo funcion = funcionesSimbolo.get(f.arg1);
    if (f.arg1.equals(GeneradorCodigo.FUNCION_CONVERSION)) {
      llamada = conversion(f.tipo, argumentos.get(1).arg1, f.linea);
    } else if (funcion != null && funcion.predefinida) { // LONG: cantidad de caracteres de una CAD o tamano de un arreglo
      String x = argumentos.get(0).arg1;
      llamada = "(int) " + (x.startsWith("\"") ? "std::string(" + x + ")" : objeto(x)) + ".size()";
      includes.add("string");
    } else {
      List<String> textos = new ArrayList<String>();
      for (TablaCuadruplos.Cuadruplo p : argumentos) textos.add(dimsDe(p.arg1) > 0 ? objeto(p.arg1) : valor(p.arg1));
      llamada = nombreFuncion(f.arg1) + "(" + String.join(", ", textos) + ")";
    }
    linea((f.resultado == null ? "" : destino(f.resultado) + " = ") + llamada + ";");
  }

  /**
   * CONV(tipo, dato): ENT -> DEC `(double) x`; DEC -> ENT `(int) x` (C++ tambien trunca hacia cero);
   * CAR <-> ENT; a CAD con el mismo texto que da la concatenacion; y CAD -> ENT/DEC con
   * jer_aEntero()/jer_aDecimal(), que revisan al ejecutar que el texto sea un numero.
   */
  private String conversion(TablaSimbolos.TipoDato destino, String dato, int linea) {
    TablaSimbolos.TipoDato origen = tipoDe(dato);
    if (destino == TablaSimbolos.TipoDato.CAD) return aTexto(dato, true);
    if (origen == TablaSimbolos.TipoDato.CAD) {
      String funcion = destino == TablaSimbolos.TipoDato.ENT ? "jer_aEntero" : "jer_aDecimal";
      auxiliar(funcion);
      return funcion + "(" + valor(dato) + ", " + linea + ")";
    }
    return "(" + tipoCpp(destino, 0) + ") (" + valor(dato) + ")";
  }

  // Arreglos

  /** `r = RESERVAR tipo, n` con las `n` filas DIM que lo preceden: `std::vector<int>(2, std::vector<int>(3))`. */
  private void reservar(TablaCuadruplos.Cuadruplo f) {
    int n = Integer.parseInt(f.arg2);
    List<String> tamanios = new ArrayList<String>(dimensionesPendientes.subList(dimensionesPendientes.size() - n, dimensionesPendientes.size()));
    dimensionesPendientes.subList(dimensionesPendientes.size() - n, dimensionesPendientes.size()).clear();
    linea(destino(f.resultado) + " = " + tipoCpp(f.tipo, n) + "(" + tamanios(f.tipo, n, tamanios, 0, f.linea) + ");");
  }

  /** Un tamano que no es un literal positivo se revisa al ejecutar con jer_tamanio(). */
  private String tamanios(TablaSimbolos.TipoDato tipo, int n, List<String> tamanios, int i, int linea) {
    String t = tamanios.get(i);
    boolean literalPositivo = ENT.matcher(t).matches() && !t.startsWith("-") && !t.matches("0+");
    String tam = literalPositivo ? t : jerTamanio(valor(t), linea);
    int restantes = n - i - 1;
    if (restantes == 0) return tam;
    return tam + ", " + tipoCpp(tipo, restantes) + "(" + tamanios(tipo, n, tamanios, i + 1, linea) + ")";
  }

  private String jerTamanio(String x, int linea) { auxiliar("jer_tamanio"); return "jer_tamanio(" + x + ", " + linea + ")"; }

  /** `jer_elem(v, i, linea)`: el elemento i de un arreglo (o el caracter i de una CAD), revisando el indice. */
  private String elemento(String arreglo, String indice, int linea) {
    auxiliar("jer_elem");
    return "jer_elem(" + objeto(arreglo) + ", " + valor(indice) + ", " + linea + ")";
  }

  /** Si lo leido es una fila de un arreglo de varias dimensiones, el temporal apunta a ella (no es una copia). */
  private void leerElemento(TablaCuadruplos.Cuadruplo f) {
    if (filasPuntero.contains(f.resultado)) linea(f.resultado + " = &" + elemento(f.arg1, f.arg2, f.linea) + ";");
    else linea(destino(f.resultado) + " = " + elemento(f.arg1, f.arg2, f.linea) + ";");
  }

  // Operandos

  /** Un operando que es un valor: un literal, una variable o un temporal. */
  private String valor(String operando) {
    if (operando.equals("VERDADERO")) return "true";
    if (operando.equals("FALSO")) return "false";
    if (operando.startsWith("\"")) {
      if (!operando.chars().allMatch(c -> c < 128)) usaUtf8 = true;
      return operando;
    }
    if (operando.startsWith("'") || ENT.matcher(operando).matches() || DEC.matcher(operando).matches()) return operando;
    return nombre(operando);
  }

  /** Un operando que es un arreglo o una CAD: si es un temporal que apunta a una fila, el objeto al que apunta. */
  private String objeto(String operando) {
    return filasPuntero.contains(operando) ? "(*" + operando + ")" : valor(operando);
  }

  private String destino(String variable) { return nombre(variable); }

  private TablaSimbolos.TipoDato tipoDe(String operando) {
    if (operando.equals("VERDADERO") || operando.equals("FALSO")) return TablaSimbolos.TipoDato.BOO;
    if (operando.startsWith("\"")) return TablaSimbolos.TipoDato.CAD;
    if (operando.startsWith("'")) return TablaSimbolos.TipoDato.CAR;
    if (ENT.matcher(operando).matches()) return TablaSimbolos.TipoDato.ENT;
    if (DEC.matcher(operando).matches()) return TablaSimbolos.TipoDato.DEC;
    TablaSimbolos.TipoDato t = tipos.get(operando);
    return t != null ? t : tiposGlobales.get(operando);
  }

  private int dimsDe(String operando) {
    Integer d = dims.get(operando);
    if (d == null) d = dimsGlobales.get(operando);
    return d == null ? 0 : d;
  }

  // Nombres y tipos de C++

  private static String etiqueta(String e) { return "_" + e; } // un identificador de JER empieza con letra: no choca

  /**
   * El nombre de JER, salvo tres casos que C++ no acepta: una palabra reservada de C++ (`int` -> `int_`),
   * un nombre que empieza con `jer_` (lo usan las funciones auxiliares) y una variable que se llama igual
   * que una funcion, que en C++ ocultaria la funcion (`suma` -> `suma_v`).
   */
  private String nombre(String n) {
    if (funcionesSimbolo.containsKey(n)) n += "_v";
    return RecursosCpp.RESERVADAS_CPP.contains(n) || n.startsWith("jer_") ? n + "_" : n;
  }

  private String nombreFuncion(String n) {
    return RecursosCpp.RESERVADAS_CPP.contains(n) || n.startsWith("jer_") ? n + "_" : n;
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

  private void linea(String texto) { salida.append(SANGRIA).append(texto).append('\n'); }

  // Funciones auxiliares (solo se escriben las que el programa usa, y las que estas necesitan)

  private void auxiliar(String nombre) {
    if (!auxiliares.add(nombre)) return;
    for (String requerida : RecursosCpp.REQUIERE.getOrDefault(nombre, new String[0])) auxiliar(requerida);
  }

  private String textoAuxiliares() {
    StringBuilder texto = new StringBuilder();
    for (String[] aux : RecursosCpp.AUXILIARES) {
      if (!auxiliares.contains(aux[0])) continue;
      for (String inc : aux[1].split(" ")) if (!inc.isEmpty()) includes.add(inc);
      texto.append(aux[2]).append('\n');
    }
    return texto.toString();
  }
}
