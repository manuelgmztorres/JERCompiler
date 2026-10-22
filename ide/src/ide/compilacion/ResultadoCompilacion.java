package ide.compilacion;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lo que interesa de la salida de una compilacion: el texto completo, solo las lineas de error (con
 * sus sugerencias "   ->"), los numeros de linea con error y si termino con "Errores: 0".
 * No depende de Swing.
 */
public final class ResultadoCompilacion {
    /** "[ERROR ...] Linea N" en la salida del compilador. */
    private static final Pattern PATRON_ERROR = Pattern.compile("^\\[ERROR[^\\]]*\\]\\s*L[ií]nea (\\d+)");
    /** Linea "Errores: 0" que imprime el compilador al terminar sin errores. */
    private static final Pattern PATRON_SIN_ERRORES = Pattern.compile("(?m)^Errores: 0\\s*$");

    public final String salida;
    public final String errores;
    public final Set<Integer> lineasConError;
    public final boolean sinErrores;
    /** Momento (System.currentTimeMillis) en que empezo la compilacion. */
    public final long inicio;
    /** Codigo de salida del proceso del compilador (-1 si se cancelo por timeout). */
    public final int codigoSalida;
    /** Duracion de la compilacion en milisegundos. */
    public final long duracionMs;

    private ResultadoCompilacion(String salida, String errores, Set<Integer> lineas, boolean sinErrores, long inicio,
            int codigoSalida, long duracionMs) {
        this.salida = salida;
        this.errores = errores;
        this.lineasConError = Collections.unmodifiableSet(lineas);
        this.sinErrores = sinErrores;
        this.inicio = inicio;
        this.codigoSalida = codigoSalida;
        this.duracionMs = duracionMs;
    }

    public static ResultadoCompilacion analizar(String salida, long inicio, int codigoSalida, long duracionMs) {
        StringBuilder errores = new StringBuilder();
        Set<Integer> lineas = new TreeSet<>();
        boolean enError = false;
        for (String linea : salida.split("\n")) {
            Matcher m = PATRON_ERROR.matcher(linea);
            if (m.find()) {
                lineas.add(Integer.parseInt(m.group(1)));
                enError = true;
            } else if (!linea.startsWith("   ->")) {
                enError = false;
            }
            if (enError) errores.append(linea).append('\n');
        }
        return new ResultadoCompilacion(salida, errores.toString(), lineas, PATRON_SIN_ERRORES.matcher(salida).find(), inicio,
                codigoSalida, duracionMs);
    }
}
