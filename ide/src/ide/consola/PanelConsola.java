package ide.consola;

import ide.Tema;
import ide.compilacion.ResultadoCompilacion;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consola inferior del IDE, con las pestanas en el orden de las etapas del compilador: "Salida" (todo
 * lo que imprimio el compilador), "Errores" (solo los errores; clic en uno salta a su linea y
 * columna), "Tokens" y "Tabla de símbolos" (PanelTablas) y "Cuádruplos" y "Tres direcciones"
 * (PanelCodigoIntermedio).
 */
public class PanelConsola extends JTabbedPane {
    public static final int TAB_SALIDA = 0, TAB_ERRORES = 1, TAB_TOKENS = 2, TAB_SIMBOLOS = 3,
            TAB_CUADRUPLOS = 4, TAB_TRES_DIR = 5;
    /** "Linea N" o "Linea N, columna M" en un mensaje del compilador. */
    private static final Pattern PATRON_POSICION = Pattern.compile("L[ií]nea (\\d+)(?:, columna (\\d+))?");

    private final JTextArea consola = new JTextArea();
    private final JTextArea consolaErrores = new JTextArea();
    private final PanelTablas tablas;
    private final PanelCodigoIntermedio codigoIntermedio;
    private final BiConsumer<Integer, Integer> alIrA;

    /**
     * `carpetaSalida` es donde el compilador deja sus archivos; `alIrA` recibe la linea y la columna
     * (1-based) a las que hay que llevar el editor; `alEstado`, los mensajes para la barra de estado.
     */
    public PanelConsola(File carpetaSalida, BiConsumer<Integer, Integer> alIrA, Consumer<String> alEstado) {
        this.alIrA = alIrA;
        this.tablas = new PanelTablas(carpetaSalida, alIrA);
        this.codigoIntermedio = new PanelCodigoIntermedio(carpetaSalida, alEstado);
        for (JTextArea area : new JTextArea[] {consola, consolaErrores}) {
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            area.setEditable(false);
            instalarSaltoAError(area);
        }
        addTab("Salida", new JScrollPane(consola));
        addTab("Errores", new JScrollPane(consolaErrores));
        tablas.agregarA(this);
        codigoIntermedio.agregarA(this);
        aplicarTema(Tema.CLARO);
    }

    public PanelTablas tablas() { return tablas; }

    public PanelCodigoIntermedio codigoIntermedio() { return codigoIntermedio; }

    /** Muestra un mensaje (error del IDE, de guardado...) en "Salida" y selecciona esa pestana. */
    public void mostrarMensaje(String mensaje) {
        consola.setText(mensaje);
        setSelectedIndex(TAB_SALIDA);
    }

    /** Vuelca la salida y los errores de la compilacion (no selecciona pestana). */
    public void mostrarResultado(ResultadoCompilacion r) {
        consola.setText(r.salida);
        consola.setCaretPosition(0);
        consolaErrores.setText(r.errores);
        consolaErrores.setCaretPosition(0);
    }

    /** Vacia todas las pestanas. */
    public void limpiar() {
        consola.setText("");
        consolaErrores.setText("");
        tablas.limpiar();
        codigoIntermedio.limpiar("");
    }

    public void aplicarTema(Tema tema) {
        setBackground(tema.fondoPanel);
        setForeground(tema.textoPanel);
        setOpaque(true);
        for (JTextArea area : new JTextArea[] {consola, consolaErrores}) {
            area.setBackground(tema.fondoConsola);
            area.setForeground(tema.textoConsola);
        }
        tablas.aplicarTema(tema);
        codigoIntermedio.aplicarTema(tema);
    }

    /** Clic sobre una linea con "Linea N[, columna M]" lleva el editor a esa posicion. */
    private void instalarSaltoAError(JTextArea area) {
        area.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int[] posicion = posicionEn(area, e.getPoint());
                if (posicion != null) alIrA.accept(posicion[0], posicion[1]);
            }
        });
        area.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                area.setCursor(Cursor.getPredefinedCursor(posicionEn(area, e.getPoint()) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        });
    }

    /** {linea, columna} del mensaje bajo el punto (columna 1 si el mensaje no la trae), o null. */
    private static int[] posicionEn(JTextArea area, Point p) {
        try {
            int n = area.getLineOfOffset(area.viewToModel2D(p));
            Matcher m = PATRON_POSICION.matcher(area.getText(area.getLineStartOffset(n), area.getLineEndOffset(n) - area.getLineStartOffset(n)));
            if (!m.find()) return null;
            return new int[] { Integer.parseInt(m.group(1)), m.group(2) == null ? 1 : Integer.parseInt(m.group(2)) };
        } catch (BadLocationException | NumberFormatException e) {
            return null;
        }
    }
}
