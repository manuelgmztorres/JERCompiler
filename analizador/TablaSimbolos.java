import java.util.*;

/**
 * Colaboradores: Manuel Gomez, Luis Eduardo Hernandez Morales, Angel Horacio.
 *
 * Tabla de simbolos con scope por bloque (ver memoria de diseno: decision-scoping-tabla-simbolos).
 *
 * Un unico Scope global comparte namespace entre funciones y variables/constantes; cada
 * funcion y cada bloque anidado dentro de ella abre su propio Scope hijo. declarar() solo
 * choca contra el scope actual, nunca contra un padre, asi que el shadowing de un global por
 * un local siempre es valido.
 *
 * No sabe nada de control de flujo, es decir en que funcion o bucle esta el visitor en un
 * momento dado: eso lo rastrea el propio analizador semantico con campos simples, igual que
 * el parser ya hace con profundidadFuncion en JERCompiler_JJTree.jjt. Tampoco hace chequeo de
 * tipos, solo declara y resuelve simbolos por nombre; el chequeo de tipos vive en la segunda
 * pasada del visitor.
 */
public final class TablaSimbolos {

  public enum TipoDato { ENT, DEC, CAD, CAR, BOO, VACIO }
  public enum Categoria { VARIABLE, CONSTANTE, PARAMETRO, FUNCION }

  public static final class Simbolo {
    public final String nombre;
    public final Categoria categoria;
    public final TipoDato tipo;
    public final int aridadArreglo;               // 0 = escalar
    public final List<TipoDato> tiposParametros;   // solo si categoria == FUNCION; null en otro caso
    public final Token declaracion;                // para linea/columna en diagnosticos
    // Tamano literal de cada dimension (longitud == aridadArreglo), o -1 en una posicion cuando
    // esa dimension no es un literal ENT (variable/expresion dinamica: no se puede saber su
    // tamano en tiempo de compilacion). null cuando aridadArreglo == 0 (escalar).
    public final int[] tamanios;
    // Nombre de la funcion que contenia el scope donde se declaro este simbolo, o "GLOBAL" si
    // se declaro a nivel de programa (fuera de cualquier funcion). Se captura una sola vez, al
    // declarar, porque para cuando se arma la tabla de tipos al final del analisis ya se salio
    // de todos los scopes (ver entrarScope()/entrarScopeDeFuncion() mas abajo).
    public final String ambito;
    // No es final: arranca en false y se pone en true la primera vez que AnalizadorSemantico
    // resuelve este simbolo desde un uso real (no desde el procesamiento de su propia
    // declaracion) - ver comentario en marcarUsado().
    public boolean usado = false;

    public Simbolo(String nombre, Categoria categoria, TipoDato tipo, int aridadArreglo,
                    List<TipoDato> tiposParametros, Token declaracion, int[] tamanios, String ambito) {
      this.nombre = nombre; this.categoria = categoria; this.tipo = tipo;
      this.aridadArreglo = aridadArreglo; this.tiposParametros = tiposParametros;
      this.declaracion = declaracion; this.tamanios = tamanios; this.ambito = ambito;
    }

    /** Ver el comentario de `usado` arriba: solo debe llamarse desde un sitio de uso genuino. */
    public void marcarUsado() { usado = true; }
  }

  private static final class Scope {
    final Scope padre;
    final String funcionContenedora;               // null = ambito global
    final Map<String, Simbolo> simbolos = new HashMap<String, Simbolo>();
    Scope(Scope padre, String funcionContenedora) { this.padre = padre; this.funcionContenedora = funcionContenedora; }
  }

  private final Scope global = new Scope(null, null);
  private Scope actual = global;

  // Cada scope local (funcion/bloque) se descarta al salir de el (salirScope() solo vuelve al
  // padre), asi que declarar() tambien copia aqui cada simbolo aceptado, en orden de declaracion,
  // para poder listar la tabla completa (globales + locales) al final del analisis via todos().
  private final List<Simbolo> todos = new ArrayList<Simbolo>();

  /** Bloque, bucle, EVALUAR, etc.: el scope hijo hereda la funcion contenedora del padre. */
  public void entrarScope() { actual = new Scope(actual, actual.funcionContenedora); }

  /** Cuerpo de una funcion: el scope hijo (y todo lo anidado dentro) queda marcado con su nombre. */
  public void entrarScopeDeFuncion(String nombreFuncion) { actual = new Scope(actual, nombreFuncion); }

  public void salirScope() {
    if (actual.padre == null) throw new IllegalStateException("no hay scope que cerrar");
    actual = actual.padre;
  }

  /** Ambito del scope actual, tal como se guardaria en un Simbolo declarado en este momento. */
  public String ambitoActual() { return actual.funcionContenedora == null ? "GLOBAL" : actual.funcionContenedora; }

  /**
   * Intenta declarar en el scope actual. Devuelve el simbolo previo si ya existia en ese
   * mismo scope, un conflicto real que quien llama debe reportar, por ejemplo con
   * ManejadorErrores.reportarSimboloDuplicado(), o null si se registro con exito. Nunca
   * choca contra un scope padre, porque eso es shadowing valido, no conflicto.
   */
  public Simbolo declarar(Simbolo nuevo) {
    Simbolo previo = actual.simbolos.get(nuevo.nombre);
    if (previo != null) return previo;
    actual.simbolos.put(nuevo.nombre, nuevo);
    todos.add(nuevo);
    return null;
  }

  /** Todos los simbolos aceptados durante el analisis (globales y locales), en orden de declaracion. */
  public List<Simbolo> todos() { return Collections.unmodifiableList(todos); }

  /** Busca subiendo por la cadena de scopes hasta el global; null si no existe en ninguno. */
  public Simbolo resolver(String nombre) {
    for (Scope s = actual; s != null; s = s.padre) {
      Simbolo encontrado = s.simbolos.get(nombre);
      if (encontrado != null) return encontrado;
    }
    return null;
  }
}
