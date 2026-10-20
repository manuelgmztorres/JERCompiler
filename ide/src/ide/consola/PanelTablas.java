package ide.consola;

import ide.Tema;

import javax.swing.JTabbedPane;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Pestanas "Tokens" (salida/tabla_tokens.txt) y "Tabla de símbolos" (salida/tabla_tipos.txt). La de
 * tokens se escribe en cada compilacion, aun con errores; la de simbolos solo con 0 errores. Doble
 * clic en un token lleva a su linea y columna; en un simbolo, a la linea donde se declaro.
 */
public class PanelTablas {
    private final File carpetaSalida;
    private final TablaTexto tokens;
    private final TablaTexto simbolos;
    private final BiConsumer<Integer, Integer> alIrA;

    /** `alIrA` recibe linea y columna (1-based) del editor a las que hay que ir. */
    public PanelTablas(File carpetaSalida, BiConsumer<Integer, Integer> alIrA) {
        this.carpetaSalida = carpetaSalida;
        this.alIrA = alIrA;
        tokens = new TablaTexto(LectorTablas.COLUMNAS_TOKENS, new int[] {60, 220, 200, 70, 70}, this::irAToken);
        simbolos = new TablaTexto(LectorTablas.COLUMNAS_SIMBOLOS, new int[] {150, 90, 60, 90, 140, 60, 60, 200}, this::irASimbolo);
        limpiar();
    }

    private void irAToken(int fila) {
        alIrA.accept(entero(tokens.valor(fila, LectorTablas.TOKEN_LINEA)), entero(tokens.valor(fila, LectorTablas.TOKEN_COLUMNA)));
    }

    private void irASimbolo(int fila) {
        alIrA.accept(entero(simbolos.valor(fila, LectorTablas.SIMBOLO_LINEA)), 1);
    }

    /** Agrega las dos vistas como pestanas, en orden: Tokens, Tabla de simbolos. */
    public void agregarA(JTabbedPane pestanas) {
        pestanas.addTab("Tokens", tokens);
        pestanas.addTab("Tabla de símbolos", simbolos);
    }

    public void limpiar() {
        tokens.limpiar("");
        simbolos.limpiar("");
    }

    /** Carga las dos tablas si sus archivos son de esta compilacion (`inicio`). */
    public void cargar(boolean sinErrores, long inicio) {
        File archivoTokens = new File(carpetaSalida, "tabla_tokens.txt");
        File archivoSimbolos = new File(carpetaSalida, "tabla_tipos.txt");
        try {
            if (PanelCodigoIntermedio.esDeEstaCompilacion(archivoTokens, inicio)) {
                List<String[]> filas = LectorTablas.leerTokens(archivoTokens);
                tokens.cargar(filas, " " + filas.size() + " token(s).");
            } else {
                tokens.limpiar(" Sin tabla de tokens: el compilador no la generó en esta compilación.");
            }
        } catch (IOException e) {
            tokens.limpiar(" No se pudo leer la tabla de tokens: " + e.getMessage());
        }
        if (!sinErrores) {
            simbolos.limpiar(" Sin tabla de símbolos: el programa tiene errores.");
            return;
        }
        try {
            if (PanelCodigoIntermedio.esDeEstaCompilacion(archivoSimbolos, inicio)) {
                List<String[]> filas = LectorTablas.leerSimbolos(archivoSimbolos);
                simbolos.cargar(filas, " " + filas.size() + " símbolo(s).");
            } else {
                simbolos.limpiar(" Sin tabla de símbolos: el compilador no la generó en esta compilación.");
            }
        } catch (IOException e) {
            simbolos.limpiar(" No se pudo leer la tabla de símbolos: " + e.getMessage());
        }
    }

    public void aplicarTema(Tema tema) {
        tokens.aplicarTema(tema);
        simbolos.aplicarTema(tema);
    }

    private static int entero(String texto) {
        try { return Integer.parseInt(texto.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
