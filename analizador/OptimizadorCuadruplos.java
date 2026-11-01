import java.util.*;
import java.util.regex.Pattern;

/**
 * Optimizacion local del codigo intermedio: FOLDING (plegado de constantes) y PROPAGACION DE
 * CONSTANTES dentro de cada bloque basico (ver FOLDING_PROPAGACION.pdf).
 *
 * No modifica la tabla original: recibe la tabla ya CERRADA (con sus filas ETIQ) y devuelve una
 * tabla nueva con las mismas filas y las mismas etiquetas, para poder mostrar las dos versiones.
 * No elimina ni agrega filas, asi que los saltos y sus etiquetas siguen siendo validos.
 *
 * Algoritmo (una sola pasada por fila, en orden):
 *   1. Un bloque basico empieza en una fila FUN, en una fila ETIQ (destino de salto) y en la fila
 *      que sigue a un SALTO* o a un RET. En cada inicio de bloque la tabla const[] se vacia.
 *   2. Propagacion: cada operando que sea una variable o temporal presente en const[] se
 *      reemplaza por su valor.
 *   3. Folding: si la fila es una operacion (binaria, NEG o NOT) con todos sus operandos
 *      constantes, se evalua y la fila pasa a ser `ASIG valor -> resultado`.
 *   4. Si la fila es `ASIG constante -> x`, se registra const[x]; cualquier otra fila que escriba
 *      x lo elimina de const[]. Un LLAMAR elimina tambien los globales (el callee puede cambiarlos).
 *
 * Arreglos y matrices: ademas de las variables, la tabla recuerda elementos con indice constante
 * (`v[1] -> 10`, `m[1][2] -> 7`) y las filas de matriz (`t4 = m[1]` anota que t4 es la fila m[1]).
 * Se olvidan cuando una escritura con indice desconocido pudo cambiarlos, cuando hay un LLAMAR, un
 * RESERVAR del mismo arreglo, o una escritura en un arreglo que podria ser alias de otro (parametro o
 * global). Todo sigue siendo LOCAL: la tabla se vacia en cada bloque basico.
 *
 * Lo que NO se pliega (para no cambiar el comportamiento del programa): division o modulo entre
 * cero, desbordamiento de ENT, el operador `//` (raiz), y cualquier operando que no sea un
 * literal reconocido.
 */
public final class OptimizadorCuadruplos {

  private static final Pattern ENT = Pattern.compile("-?\\d+");
  private static final Pattern DEC = Pattern.compile("-?\\d+\\.\\d+");

  private final Set<String> globales;
  private int plegados = 0;
  private int propagados = 0;

  // Estado del bloque basico en curso (se vacia en cada lider).
  private final Map<String, String> constantes = new HashMap<String, String>();  // x -> 6
  private final Map<String, String> elementos = new HashMap<String, String>();   // v[1] -> 10, m[1][2] -> 7
  private final Map<String, String> referencias = new HashMap<String, String>(); // t4 -> m[1] (fila de una matriz)
  // Arreglos reservados en la funcion en curso: solo ellos estan garantizados como no-alias de otros.
  private final Set<String> arreglosLocales = new HashSet<String>();

  /** `globales`: nombres del ambito global (GeneradorCodigo.nombresGlobales()). */
  public OptimizadorCuadruplos(Set<String> globales) { this.globales = globales; }

  /** Operaciones evaluadas en tiempo de compilacion en la ultima llamada a optimizar(). */
  public int plegados() { return plegados; }

  /** Operandos reemplazados por su constante en la ultima llamada a optimizar(). */
  public int propagados() { return propagados; }

  public TablaCuadruplos optimizar(TablaCuadruplos original) {
    plegados = 0; propagados = 0;
    arreglosLocales.clear();
    nuevoBloque();
    TablaCuadruplos salida = new TablaCuadruplos();

    for (TablaCuadruplos.Cuadruplo f : original.filas()) {
      String op = f.operador;
      if (op.equals("FUN") || op.equals("ETIQ")) {          // inicio de bloque
        nuevoBloque();
        if (op.equals("FUN")) arreglosLocales.clear();
        salida.emitir(op, f.arg1, f.arg2, f.resultado, f.tipo);
        continue;
      }

      // 1. Propagacion de variables y temporales
      String a = f.arg1, b = f.arg2;
      if (leeArg1(op)) a = sustituir(a);
      if (leeArg2(op)) b = sustituir(b);

      // 2. Folding
      String opSalida = op;
      TablaSimbolos.TipoDato tipo = f.tipo;
      if (esOperacion(op)) {
        String valor = plegar(op, a, b);
        if (valor != null) {
          opSalida = "ASIG"; a = valor; b = null; tipo = tipoDeLiteral(valor);
          plegados++;
        }
      }

      // 3. Actualizar el estado del bloque
      String destino = f.resultado;
      if (opSalida.equals("ASIG")) {
        String c = valorRegistrable(a, tipo);
        referencias.remove(destino);
        if (c != null) constantes.put(destino, c); else constantes.remove(destino);
      } else if (op.equals("RESERVAR")) {
        arreglosLocales.add(destino);
        olvidarArreglo(destino);
        constantes.remove(destino);
      } else if (op.equals("LEER_ELEM")) {
        // arg1 es el arreglo (o una fila ya anotada), b el indice. Si el elemento esta en la tabla, es un valor.
        String clave = rutaDe(f.arg1) + "[" + (esEnt(b) ? b : "?") + "]";
        String c = elementos.containsKey(clave) ? valorRegistrable(elementos.get(clave), tipo) : null;
        constantes.remove(destino); referencias.remove(destino);
        if (c != null) { opSalida = "ASIG"; a = c; b = null; constantes.put(destino, c); propagados++; }
        else referencias.put(destino, clave); // posible fila de matriz
      } else if (op.equals("ASIG_ELEM")) {
        escribirElemento(destino, b, a, tipo);
      } else if (op.equals("LLAMAR")) {
        elementos.clear();                                 // la funcion puede modificar arreglos
        constantes.keySet().removeAll(globales);           // y variables globales
        if (destino != null) { constantes.remove(destino); referencias.remove(destino); }
      } else if (escribeResultado(op) && destino != null) {
        constantes.remove(destino); referencias.remove(destino);
      }

      salida.emitir(opSalida, a, b, destino, tipo);

      // El fin de bloque vacia el estado: la fila siguiente es lider.
      if (op.startsWith("SALTO") || op.equals("RET")) nuevoBloque();
    }
    return salida;
  }

  private void nuevoBloque() { constantes.clear(); elementos.clear(); referencias.clear(); }

  // Arreglos

  /** Ruta canonica de un arreglo o fila: `v`, `m[1]`, `m[1][2]`; un `?` marca un indice desconocido. */
  private String rutaDe(String nombre) {
    String ruta = referencias.get(nombre);
    return ruta != null ? ruta : nombre;
  }

  private static String raizDe(String ruta) {
    int i = ruta.indexOf('[');
    return i < 0 ? ruta : ruta.substring(0, i);
  }

  /** Un arreglo que no se reservo en esta funcion (parametro o global) puede ser alias de otro. */
  private boolean podriaTenerAlias(String raiz) { return globales.contains(raiz) || !arreglosLocales.contains(raiz); }

  /** `base[indice] = valor`: olvida lo que la escritura pudo cambiar y registra el elemento si es constante. */
  private void escribirElemento(String base, String indice, String valor, TablaSimbolos.TipoDato tipo) {
    String ruta = rutaDe(base), raiz = raizDe(ruta);
    if (podriaTenerAlias(raiz)) elementos.clear();
    if (esEnt(indice) && !ruta.contains("?")) {
      final String clave = ruta + "[" + indice + "]";
      elementos.keySet().removeIf(k -> k.equals(clave) || k.startsWith(clave + "["));
      String c = valorRegistrable(valor, tipo);
      if (c != null) elementos.put(clave, c);
    } else {
      final String prefijo = raiz + "[";
      elementos.keySet().removeIf(k -> k.startsWith(prefijo)); // indice desconocido: pudo ser cualquiera
    }
  }

  /** Despues de un RESERVAR el arreglo es nuevo: no vale nada de lo anotado sobre el anterior. */
  private void olvidarArreglo(final String raiz) {
    final String prefijo = raiz + "[";
    elementos.keySet().removeIf(k -> k.startsWith(prefijo));
    referencias.values().removeIf(v -> v.startsWith(prefijo));
    referencias.remove(raiz);
  }

  // Clasificacion de operadores

  private static boolean leeArg1(String op) {
    switch (op) {
      case "SALTO": case "RECIBIR": case "OBT": case "LLAMAR": case "RESERVAR": case "LEER_ELEM": return false; // LEER_ELEM: arg1 es el arreglo
      default: return true;
    }
  }

  private static boolean leeArg2(String op) {
    switch (op) {
      case "ASIG": case "NEG": case "NOT": case "SALTO": case "SALTO_VER": case "SALTO_FAL": case "RECIBIR":
      case "PARAM": case "LLAMAR": case "RET": case "IMP": case "OBT": case "DIM": case "RESERVAR": return false;
      default: return true; // binarias, LEER_ELEM (indice) y ASIG_ELEM (indice)
    }
  }

  /** Operaciones que calculan un valor a partir de operandos: aritmeticas, relacionales, NEG y NOT. */
  private static boolean esOperacion(String op) {
    switch (op) {
      case "+": case "-": case "*": case "/": case "%": case "**": case "//":
      case "==": case "!=": case "<": case "<=": case ">": case ">=": case "NEG": case "NOT": return true;
      default: return false;
    }
  }

  /** Filas cuyo `resultado` es una variable o temporal que se escribe (no una etiqueta ni un arreglo). */
  private static boolean escribeResultado(String op) {
    switch (op) {
      case "SALTO": case "SALTO_VER": case "SALTO_FAL": case "ASIG_ELEM": case "ETIQ": case "FUN": return false;
      default: return true; // ASIG, operaciones, LEER_ELEM, RECIBIR, OBT, LLAMAR, RESERVAR
    }
  }

  private String sustituir(String operando) {
    if (operando == null) return null;
    String c = constantes.get(operando);
    if (c == null) return operando;
    propagados++;
    return c;
  }

  // Literales

  private static boolean esEnt(String s) { return s != null && ENT.matcher(s).matches(); }
  private static boolean esDec(String s) { return s != null && DEC.matcher(s).matches(); }
  private static boolean esBoo(String s) { return "VERDADERO".equals(s) || "FALSO".equals(s); }
  private static boolean esCad(String s) { return s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""); }
  private static boolean esCar(String s) { return s != null && s.length() >= 3 && s.startsWith("'") && s.endsWith("'"); }

  private static TablaSimbolos.TipoDato tipoDeLiteral(String s) {
    if (esEnt(s)) return TablaSimbolos.TipoDato.ENT;
    if (esDec(s)) return TablaSimbolos.TipoDato.DEC;
    if (esBoo(s)) return TablaSimbolos.TipoDato.BOO;
    if (esCad(s)) return TablaSimbolos.TipoDato.CAD;
    return TablaSimbolos.TipoDato.CAR;
  }

  /**
   * El valor que se guarda en const[] para `ASIG literal -> x`, o null si no hay que propagarlo.
   * Si x es DEC y el literal es ENT (`DEC x -> 5`), se propaga 5.0 para no perder el tipo de x.
   */
  private static String valorRegistrable(String literal, TablaSimbolos.TipoDato tipoDestino) {
    if (literal == null) return null;
    if (esEnt(literal)) {
      if (tipoDestino == TablaSimbolos.TipoDato.ENT) return literal;
      if (tipoDestino == TablaSimbolos.TipoDato.DEC) return literal + ".0";
      return null;
    }
    if (esDec(literal)) return tipoDestino == TablaSimbolos.TipoDato.DEC ? literal : null;
    if (esBoo(literal)) return tipoDestino == TablaSimbolos.TipoDato.BOO ? literal : null;
    if (esCad(literal)) return tipoDestino == TablaSimbolos.TipoDato.CAD ? literal : null;
    if (esCar(literal)) return tipoDestino == TablaSimbolos.TipoDato.CAR ? literal : null;
    return null;
  }

  // Folding

  /** Evalua `a op b` (b es null en NEG y NOT). Devuelve el literal resultado, o null si no se puede o no conviene plegar. */
  private static String plegar(String op, String a, String b) {
    if (a == null) return null;

    if (op.equals("NOT")) return esBoo(a) ? booleano(a.equals("FALSO")) : null;
    if (op.equals("NEG")) {
      if (esEnt(a)) { Integer x = entero(a); return x == null || x == Integer.MIN_VALUE ? null : String.valueOf(-x); }
      if (esDec(a)) return decimal(-Double.parseDouble(a));
      return null;
    }
    if (b == null || op.equals("//")) return null;

    if (op.equals("+") && esCad(a) && esCad(b)) return a.substring(0, a.length() - 1) + b.substring(1); // concatenacion

    if (esBoo(a) && esBoo(b)) {
      if (op.equals("==")) return booleano(a.equals(b));
      if (op.equals("!=")) return booleano(!a.equals(b));
      return null;
    }

    if (esEnt(a) && esEnt(b)) {
      Integer x = entero(a), y = entero(b);
      if (x == null || y == null) return null;
      return plegarEnteros(op, x, y);
    }
    if ((esEnt(a) || esDec(a)) && (esEnt(b) || esDec(b))) {
      return plegarDecimales(op, Double.parseDouble(a), Double.parseDouble(b));
    }
    return null;
  }

  private static String plegarEnteros(String op, int x, int y) {
    try {
      switch (op) {
        case "+": return String.valueOf(Math.addExact(x, y));
        case "-": return String.valueOf(Math.subtractExact(x, y));
        case "*": return String.valueOf(Math.multiplyExact(x, y));
        case "/": return (y == 0 || (x == Integer.MIN_VALUE && y == -1)) ? null : String.valueOf(x / y); // cociente entero, truncado
        case "%": return (y == 0 || x < 0 || y < 0) ? null : String.valueOf(x % y);                    // solo no negativos: sin ambiguedad de signo
        case "**": return potencia(x, y);
        case "==": return booleano(x == y);
        case "!=": return booleano(x != y);
        case "<": return booleano(x < y);
        case "<=": return booleano(x <= y);
        case ">": return booleano(x > y);
        case ">=": return booleano(x >= y);
        default: return null;
      }
    } catch (ArithmeticException desbordamiento) {
      return null; // el resultado no cabe en ENT: se deja la operacion para tiempo de ejecucion
    }
  }

  private static String potencia(int base, int exponente) {
    if (exponente < 0) return null;
    if (base == 0 || base == 1) return exponente == 0 ? "1" : String.valueOf(base);
    if (base == -1) return exponente % 2 == 0 ? "1" : "-1";
    if (exponente > 31) return null;
    int r = 1;
    for (int i = 0; i < exponente; i++) r = Math.multiplyExact(r, base);
    return String.valueOf(r);
  }

  private static String plegarDecimales(String op, double x, double y) {
    switch (op) {
      case "+": return decimal(x + y);
      case "-": return decimal(x - y);
      case "*": return decimal(x * y);
      case "/": return y == 0.0 ? null : decimal(x / y);
      case "**": return decimal(Math.pow(x, y));
      case "==": return booleano(x == y);
      case "!=": return booleano(x != y);
      case "<": return booleano(x < y);
      case "<=": return booleano(x <= y);
      case ">": return booleano(x > y);
      case ">=": return booleano(x >= y);
      default: return null; // % con DEC: sin plegar
    }
  }

  private static Integer entero(String s) {
    try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
  }

  private static String booleano(boolean v) { return v ? "VERDADERO" : "FALSO"; }

  /** DEC siempre con punto decimal y sin notacion cientifica; NaN o infinito no se pliegan. */
  private static String decimal(double v) {
    if (Double.isNaN(v) || Double.isInfinite(v)) return null;
    String s = new java.math.BigDecimal(Double.toString(v)).toPlainString();
    return s.contains(".") ? s : s + ".0";
  }
}
