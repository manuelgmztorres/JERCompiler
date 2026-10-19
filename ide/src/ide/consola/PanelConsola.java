package ide.consola;

import ide.Tema;
import ide.compilacion.ResultadoCompilacion;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consola inferior del IDE: pestanas "Salida" (todo lo que imprimio el compilador), "Errores" (solo
 * los errores; clic en uno salta a su linea) y las del codigo intermedio (PanelCodigoIntermedio).
 */
public class PanelConsola extends JTabbedPane {
    public static final int TAB_SALIDA = 0, TAB_ERRORES = 1, TAB_CUADRUPLOS = 2, TAB_TRES_DIR = 3, TAB_SIMBOLOS = 4;
    private static final Pattern PATRON_LINEA = Pattern.compile("L[ií]nea (\\d+)");

    private final JTextArea consola = new JTextArea();
    private final JTextArea consolaErrores = new JTextArea();
    private final PanelCodigoIntermedio codigoIntermedio;
    private final IntConsumer alIrALinea;

    /**
     * `carpetaSalida` es donde el compilador deja sus archivos; `alIrALinea` recibe la linea (1-based) de
     * un error al que se le hizo clic; `alEstado`, los mensajes para la barra de estado.
     */
    public PanelConsola(File carpetaSalida, IntConsumer alIrALinea, Consumer<String> alEstado) {
        this.alIrALinea = alIrALinea;
        this.codigoIntermedio = new PanelCodigoIntermedio(carpetaSalida, alEstado);
        for (JTextArea area : new JTextArea[] {consola, consolaErrores}) {
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            area.setEditable(false);
            instalarSaltoAError(area);
        }
        addTab("Salida", new JScrollPane(consola));
        addTab("Errores", new JScrollPane(consolaErrores));
        codigoIntermedio.agregarA(this);
        aplicarTema(Tema.CLARO);
    }

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

    public void limpiar() {
        consola.setText("");
        consolaErrores.setText("");
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
        codigoIntermedio.aplicarTema(tema);
    }

    /** Clic sobre una linea "[ERROR ...] Linea N" (o su sugerencia "->") salta a esa linea del editor. */
    private void instalarSaltoAError(JTextArea area) {
        area.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int linea = lineaDeErrorEn(area, e.getPoint());
                if (linea > 0) alIrALinea.accept(linea);
            }
        });
        area.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                area.setCursor(Cursor.getPredefinedCursor(lineaDeErrorEn(area, e.getPoint()) > 0 ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        });
    }

    private static int lineaDeErrorEn(JTextArea area, Point p) {
        try {
            int n = area.getLineOfOffset(area.viewToModel2D(p));
            Matcher m = PATRON_LINEA.matcher(area.getText(area.getLineStartOffset(n), area.getLineEndOffset(n) - area.getLineStartOffset(n)));
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (BadLocationException | NumberFormatException e) {
            return 0;
        }
    }
}
