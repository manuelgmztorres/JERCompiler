import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * La tabla de cuadruplos de todo el programa: filas numeradas desde 1 mientras se genera,
 * los temporales t1, t2... de la funcion en curso y el relleno de saltos hacia adelante. No sabe
 * nada del arbol: el generador le pide que emita filas. Se puede mostrar de dos maneras con la
 * misma informacion: la tabla en columnas (formatear) y el codigo de tres direcciones, que es la
 * misma tabla escrita como instrucciones (formatearListado).
 */
public final class TablaCuadruplos {

  /**
   * Una fila de la tabla: operador, dos argumentos y un resultado. Una celda en null es una celda
   * vacia y se imprime en blanco. `tipo` es el tipo del resultado: se guarda en memoria para el
   * futuro generador de codigo objeto, pero la tabla impresa no lo muestra. `resultado` no es final
   * porque el destino de un salto hacia adelante se escribe despues, cuando se llega a el.
   */
  public static final class Cuadruplo {
    public final String operador;
    public final String arg1;
    public final String arg2;
    public String resultado;
    public final TablaSimbolos.TipoDato tipo;

    public Cuadruplo(String operador, String arg1, String arg2, String resultado, TablaSimbolos.TipoDato tipo) {
      this.operador = operador; this.arg1 = arg1; this.arg2 = arg2; this.resultado = resultado; this.tipo = tipo;
    }
  }

  private final List<Cuadruplo> filas = new ArrayList<Cuadruplo>();
  private int contadorTemporales = 0;
  private boolean cerrada = false;

  /** Agrega una fila y devuelve su numero (el primero es 1). */
  public int emitir(String operador, String arg1, String arg2, String resultado, TablaSimbolos.TipoDato tipo) {
    filas.add(new Cuadruplo(operador, arg1, arg2, resultado, tipo));
    return filas.size();
  }

  /** Numero que tendra la proxima fila que se emita; es el destino de un salto hacia atras o hacia "lo que viene". */
  public int siguiente() { return filas.size() + 1; }

  public int tamano() { return filas.size(); }

  /** La fila `numero` (desde 1). */
  public Cuadruplo fila(int numero) { return filas.get(numero - 1); }

  public List<Cuadruplo> filas() { return Collections.unmodifiableList(filas); }

  /** Escribe `destino` como resultado de cada salto pendiente (numeros de fila) de la lista. */
  public void rellenar(List<Integer> pendientes, int destino) {
    for (int numero : pendientes) fila(numero).resultado = String.valueOf(destino);
    pendientes.clear();
  }

  /** Igual, para un solo salto. */
  public void rellenar(int fila, int destino) { fila(fila).resultado = String.valueOf(destino); }

  /** Cantidad de saltos que quedaron sin destino (un relleno olvidado); debe ser 0 al terminar. */
  public int saltosSinDestino() {
    int cantidad = 0;
    for (Cuadruplo f : filas) if (f.operador.startsWith("SALTO") && f.resultado == null) cantidad++;
    return cantidad;
  }

  /** Temporal nuevo de la funcion actual: t1, t2... */
  public String nuevoTemporal() { return "t" + (++contadorTemporales); }

  /** Los temporales reinician en cada funcion. */
  public void reiniciarTemporales() { contadorTemporales = 0; }

  /**
   * Cierra la tabla cuando ya no se van a emitir mas filas: el destino de cada salto, que hasta ahora
   * era un numero de fila, pasa a ser una etiqueta (L1, L2... por orden de aparicion) y antes de cada
   * fila destino se inserta una fila `ETIQ` con esa etiqueta, que es un cuadruplo mas (sin argumentos).
   * Despues de esto los numeros de fila ya no valen: no se debe llamar a rellenar() ni a siguiente().
   */
  public void cerrar() {
    if (cerrada) return;
    SortedSet<Integer> destinos = new TreeSet<Integer>();
    for (Cuadruplo f : filas) {
      if (f.operador.startsWith("SALTO") && f.resultado != null) destinos.add(Integer.parseInt(f.resultado));
    }
    Map<Integer, String> etiquetas = new HashMap<Integer, String>();
    int k = 1;
    for (int destino : destinos) etiquetas.put(destino, "L" + (k++));
    List<Cuadruplo> nuevas = new ArrayList<Cuadruplo>();
    for (int numero = 1; numero <= filas.size(); numero++) {
      if (etiquetas.containsKey(numero)) nuevas.add(new Cuadruplo("ETIQ", null, null, etiquetas.get(numero), null));
      Cuadruplo f = filas.get(numero - 1);
      if (f.operador.startsWith("SALTO") && f.resultado != null) f.resultado = etiquetas.get(Integer.parseInt(f.resultado));
      nuevas.add(f);
    }
    if (etiquetas.containsKey(filas.size() + 1)) nuevas.add(new Cuadruplo("ETIQ", null, null, etiquetas.get(filas.size() + 1), null));
    filas.clear();
    filas.addAll(nuevas);
    cerrada = true;
  }

  // Tabla en columnas

  /**
   * La tabla en columnas, igual que la tabla de tipos: Operador | Arg1 | Arg2 | Resultado. Son los cuatro
   * campos de un cuadruplo: sin columna de numero de fila ni de etiqueta. El destino de un salto va en
   * Resultado como etiqueta (L1...), y la etiqueta es una fila ETIQ antes del destino (ver cerrar()).
   */
  public String formatear(String nombreArchivo) {
    String[] encabezado = { "Operador", "Arg1", "Arg2", "Resultado" };
    int[] ancho = new int[encabezado.length];
    for (int c = 0; c < ancho.length; c++) ancho[c] = encabezado[c].length();
    String[][] celdas = new String[filas.size()][];
    for (int i = 0; i < filas.size(); i++) {
      Cuadruplo f = filas.get(i);
      celdas[i] = new String[] { f.operador, vacia(f.arg1), vacia(f.arg2), vacia(f.resultado) };
      for (int c = 0; c < ancho.length; c++) ancho[c] = Math.max(ancho[c], celdas[i][c].length());
    }
    StringWriter buffer = new StringWriter();
    PrintWriter w = new PrintWriter(buffer);
    w.println("TABLA DE CUÁDRUPLOS - JERCompiler"); w.println("Archivo: " + nombreArchivo);
    w.println(linea(encabezado, ancho));
    StringBuilder separador = new StringBuilder();
    for (int c = 0; c < ancho.length; c++) {
      if (c > 0) separador.append("-+-");
      for (int k = 0; k < ancho[c]; k++) separador.append('-');
    }
    w.println(separador);
    for (String[] fila : celdas) w.println(linea(fila, ancho));
    w.println("--------------------------------------------");
    return buffer.toString();
  }

  private static String vacia(String celda) { return celda == null ? "" : celda; }

  private static String linea(String[] celdas, int[] ancho) {
    StringBuilder s = new StringBuilder();
    for (int c = 0; c < celdas.length; c++) {
      if (c > 0) s.append(" | ");
      s.append(celdas[c]);
      if (c < celdas.length - 1) for (int k = celdas[c].length(); k < ancho[c]; k++) s.append(' ');
    }
    return s.toString().stripTrailing(); // la ultima columna puede estar vacia
  }

  // Codigo de tres direcciones

  private static final int ANCHO_SANGRIA = 8;

  /**
   * El codigo de tres direcciones: la MISMA tabla escrita como instrucciones y sin columnas. Cada
   * fila de la tabla es una linea, en el mismo orden, salvo las filas ETIQ: una etiqueta no genera
   * linea propia, sino que se escribe (L1:) al lado de la instruccion que le sigue. Los calculos se
   * escriben `resultado = a operador b`; todo lo demas, como `OPERADOR datos`. Cada funcion se ve
   * completa y se separa de la siguiente con una linea en blanco. La tabla debe estar cerrada.
   */
  public String formatearListado(String nombreArchivo) {
    StringWriter buffer = new StringWriter();
    PrintWriter w = new PrintWriter(buffer);
    w.println("CÓDIGO DE TRES DIRECCIONES - JERCompiler"); w.println("Archivo: " + nombreArchivo);
    w.println();
    String etiquetaPendiente = null;
    boolean primera = true;
    for (Cuadruplo f : filas) {
      if (f.operador.equals("ETIQ")) { etiquetaPendiente = f.resultado; continue; }
      if (f.operador.equals("FUN")) {
        if (!primera) w.println();
        w.println("FUN " + f.arg1);
        primera = false;
        continue;
      }
      primera = false;
      w.println(margen(etiquetaPendiente) + instruccion(f));
      etiquetaPendiente = null;
    }
    if (etiquetaPendiente != null) w.println(margen(etiquetaPendiente).stripTrailing()); // etiqueta al final, sin instruccion
    w.println("--------------------------------------------");
    return buffer.toString();
  }

  /** Ocho espacios, o `  L1:   ` cuando la linea es destino de un salto. */
  private static String margen(String etiqueta) {
    if (etiqueta == null) return " ".repeat(ANCHO_SANGRIA);
    return String.format("  %-" + (ANCHO_SANGRIA - 2) + "s", etiqueta + ":");
  }

  private static String instruccion(Cuadruplo f) {
    String a = f.arg1, b = f.arg2, r = f.resultado;
    switch (f.operador) {
      case "ASIG": return r + " = " + a;
      case "NEG": return r + " = - " + a;
      case "NOT": return r + " = NOT " + a;
      case "SALTO": return "SALTO " + r;
      case "SALTO_VER":
      case "SALTO_FAL": return f.operador + " " + a + ", " + r;
      case "RECIBIR": return "RECIBIR " + r;
      case "PARAM": return "PARAM " + a;
      case "LLAMAR": return (r == null ? "" : r + " = ") + "LLAMAR " + a + ", " + b;
      case "RET": return a == null ? "RET" : "RET " + a;
      case "IMP": return "IMP " + a;
      case "OBT": return "OBT " + r;
      case "DIM": return "DIM " + a;
      case "RESERVAR": return r + " = RESERVAR " + a + ", " + b;
      case "LEER_ELEM": return r + " = " + a + "[" + b + "]";
      case "ASIG_ELEM": return r + "[" + b + "] = " + a;
      default: return r + " = " + a + " " + f.operador + " " + b; // + - * / % ** // == != < <= > >=
    }
  }
}
