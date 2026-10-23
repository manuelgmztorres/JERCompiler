package ide.consola;

import ide.Tema;
import ide.compilacion.ResultadoCompilacion;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Consola inferior del IDE, con las pestanas en el orden de las etapas del compilador: "Salida" (la
 * consola del sistema: ConsolaSalida), "Errores" (solo los errores; clic en uno salta a su linea y columna),
 * "Tokens" y "Tabla de símbolos" (PanelTablas) y "Cuádruplos" (PanelCodigoIntermedio). El codigo de
 * tres direcciones no va aqui: es un documento a la derecha del editor (codigoIntermedio().documento()).
 */
public class PanelConsola extends JTabbedPane {
    public static final int TAB_SALIDA = 0, TAB_ERRORES = 1, TAB_TOKENS = 2, TAB_SIMBOLOS = 3, TAB_CUADRUPLOS = 4;

    private final ConsolaSalida consola;
    private final JTextArea consolaErrores = new JTextArea();
    private final PanelTablas tablas;
    private final PanelCodigoIntermedio codigoIntermedio;
    private final BiConsumer<Integer, Integer> alIrA;

    /**
     * `carpetaSalida` es donde el compilador deja sus archivos; `alIrA` recibe la linea y la columna
     * (1-based) a las que hay que llevar el editor; `alEstado`, los mensajes para la barra de estado;
     * `alCerrarDocumento`, la "x" del documento de tres direcciones.
     */
    public PanelConsola(File carpetaSalida, BiConsumer<Integer, Integer> alIrA, Consumer<String> alEstado,
            Runnable alCerrarDocumento) {
        this.alIrA = alIrA;
        this.consola = new ConsolaSalida(carpetaSalida.getParentFile(), alIrA);
        this.tablas = new PanelTablas(carpetaSalida, alIrA);
        this.codigoIntermedio = new PanelCodigoIntermedio(carpetaSalida, alEstado, alCerrarDocumento);
        consolaErrores.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        consolaErrores.setEditable(false);
        instalarSaltoAError(consolaErrores);
        addTab("Salida", consola);
        addTab("Errores", new JScrollPane(consolaErrores));
        tablas.agregarA(this);
        codigoIntermedio.agregarA(this);
        aplicarTema(Tema.CLARO);
    }

    public ConsolaSalida salida() { return consola; }

    public PanelTablas tablas() { return tablas; }

    public PanelCodigoIntermedio codigoIntermedio() { return codigoIntermedio; }

    /** Muestra un error del IDE (de guardado, inesperado...) en la consola y selecciona "Salida". */
    public void mostrarMensaje(String mensaje) {
        consola.error(mensaje);
        setSelectedIndex(TAB_SALIDA);
    }

    /** Vuelca los errores de la compilacion en "Errores" (la salida ya llego en vivo a la consola). */
    public void mostrarResultado(ResultadoCompilacion r) {
        consolaErrores.setText(r.errores);
        consolaErrores.setCaretPosition(0);
    }

    /** Vacia Errores, las tablas y el codigo intermedio, y hace "cls" en Salida (deja el prompt). */
    public void limpiar() {
        consola.cls();
        consolaErrores.setText("");
        tablas.limpiar();
        codigoIntermedio.limpiar("");
    }

    public void aplicarTema(Tema tema) {
        setBackground(tema.fondoPanel);
        setForeground(tema.textoPanel);
        setOpaque(true);
        consola.aplicarTema(tema);
        consolaErrores.setBackground(tema.fondoConsola);
        consolaErrores.setForeground(tema.textoConsola);
        tablas.aplicarTema(tema);
        codigoIntermedio.aplicarTema(tema);
    }

    /** Clic sobre una linea con "Linea N[, columna M]" lleva el editor a esa posicion. */
    private void instalarSaltoAError(JTextArea area) {
        area.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int[] posicion = ConsolaSalida.posicionEn(area, e.getPoint());
                if (posicion != null) alIrA.accept(posicion[0], posicion[1]);
            }
        });
        area.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                area.setCursor(Cursor.getPredefinedCursor(ConsolaSalida.posicionEn(area, e.getPoint()) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        });
    }
}
