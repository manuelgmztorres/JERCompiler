package ide.consola;

import ide.Tema;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las dos vistas del codigo intermedio: "Cuadruplos" (tabla) y "Tres direcciones".
 * Se llenan con los archivos que el compilador deja en salida/ (carpeta hermana de build/).
 */
public class PanelCodigoIntermedio {
    /** Una etiqueta de salto (L1, L2...) dentro del codigo de tres direcciones. */
    private static final Pattern PATRON_ETIQUETA = Pattern.compile("\\bL\\d+\\b");
    /** Tolerancia al comparar la fecha de los archivos de salida con el inicio de la compilacion (resolucion del sistema de archivos). */
    static final long TOLERANCIA_FECHA_MS = 2000;
    private static final int COL_OPERADOR = 0, COL_RESULTADO = 3;

    private final File carpetaSalida;
    private final Consumer<String> alEstado;
    private Tema tema = Tema.CLARO;

    private final DefaultTableModel modelo = new DefaultTableModel(LectorCuadruplos.COLUMNAS, 0) {
        @Override public boolean isCellEditable(int fila, int columna) { return false; }
    };
    private final JTable tabla = new JTable(modelo);
    private final JScrollPane scrollTabla = new JScrollPane(tabla);
    private final JLabel aviso = new JLabel(); // cantidad de cuadruplos o por que no hay; oculto si esta vacio
    private final JPanel panelCuadruplos = new JPanel(new BorderLayout());
    private final JTextArea areaTresDir = new JTextArea();

    /** `alEstado` recibe los mensajes para la barra de estado. */
    public PanelCodigoIntermedio(File carpetaSalida, Consumer<String> alEstado) {
        this.carpetaSalida = carpetaSalida;
        this.alEstado = alEstado;

        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        tabla.setFont(mono);
        tabla.setRowHeight(tabla.getFontMetrics(mono).getHeight() + 4);
        tabla.setFillsViewportHeight(true);
        tabla.setShowVerticalLines(true);
        tabla.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.getColumnModel().getColumn(COL_OPERADOR).setPreferredWidth(110);
        tabla.getColumnModel().getColumn(1).setPreferredWidth(260);
        tabla.getColumnModel().getColumn(2).setPreferredWidth(140);
        tabla.getColumnModel().getColumn(COL_RESULTADO).setPreferredWidth(140);
        tabla.setDefaultRenderer(Object.class, new RenderCuadruplo());
        tabla.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) seguirSalto(tabla.rowAtPoint(e.getPoint()));
            }
        });
        tabla.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                String op = operadorEn(tabla.rowAtPoint(e.getPoint()));
                boolean navegable = op != null && (op.startsWith("SALTO") || op.equals("ETIQ"));
                tabla.setCursor(Cursor.getPredefinedCursor(navegable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });

        aviso.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        aviso.setVisible(false);
        panelCuadruplos.add(aviso, BorderLayout.NORTH);
        panelCuadruplos.add(scrollTabla, BorderLayout.CENTER);

        areaTresDir.setFont(mono);
        areaTresDir.setEditable(false);
        instalarSaltoAEtiqueta(areaTresDir);
        aplicarTema(Tema.CLARO);
    }

    /** Agrega las dos vistas como pestanas, en orden: Cuadruplos, Tres direcciones. */
    public void agregarA(JTabbedPane pestanas) {
        pestanas.addTab("Cuádruplos", panelCuadruplos);
        pestanas.addTab("Tres direcciones", new JScrollPane(areaTresDir));
    }

    public int cantidadCuadruplos() { return modelo.getRowCount(); }

    public void aplicarTema(Tema tema) {
        this.tema = tema;
        tabla.setBackground(tema.fondoTabla);
        tabla.setForeground(tema.textoTabla);
        tabla.setGridColor(tema.rejillaTabla);
        tabla.setSelectionBackground(tema.fondoSeleccion);
        tabla.setSelectionForeground(tema.textoSeleccion);
        tabla.getTableHeader().setBackground(tema.fondoEncabezado);
        tabla.getTableHeader().setForeground(tema.textoEncabezado);
        scrollTabla.getViewport().setBackground(tema.fondoTabla);
        panelCuadruplos.setBackground(tema.fondoPanel);
        aviso.setForeground(tema.textoPanel);
        areaTresDir.setBackground(tema.fondoConsola);
        areaTresDir.setForeground(tema.textoConsola);
        areaTresDir.setCaretColor(tema.textoConsola);
        tabla.repaint();
    }

    /** Vacia las dos vistas y deja `mensaje` sobre la tabla (vacio: sin mensaje). */
    public void limpiar(String mensaje) {
        modelo.setRowCount(0);
        aviso.setText(mensaje);
        aviso.setVisible(!mensaje.isBlank());
        areaTresDir.setText("");
    }

    /**
     * Carga salida/cuadruplos.txt y salida/cod_tres_dir.txt. El compilador
     * solo los reescribe con 0 errores; con errores conservan los de una compilacion anterior, asi que
     * se cargan solo si la compilacion termino sin errores y los archivos son de esta compilacion.
     * Devuelve true si hay codigo intermedio para mostrar.
     */
    public boolean cargar(boolean sinErrores, long inicio) {
        if (!sinErrores) {
            limpiar(" Sin código intermedio: el programa tiene errores.");
            return false;
        }
        File cuadruplos = new File(carpetaSalida, "cuadruplos.txt");
        File tresDir = new File(carpetaSalida, "cod_tres_dir.txt");
        if (!esDeEstaCompilacion(cuadruplos, inicio) || !esDeEstaCompilacion(tresDir, inicio)) {
            limpiar(" Sin código intermedio: el compilador no generó salida" + File.separator + " en esta compilación.");
            return false;
        }
        try {
            List<String[]> filas = LectorCuadruplos.leer(cuadruplos);
            limpiar(" " + filas.size() + " cuádruplo(s).");
            for (String[] fila : filas) modelo.addRow(fila);
            areaTresDir.setText(LectorCuadruplos.leerTexto(tresDir));
            areaTresDir.setCaretPosition(0);
            return true;
        } catch (IOException e) {
            limpiar(" No se pudo leer el código intermedio: " + e.getMessage());
            return false;
        }
    }

    static boolean esDeEstaCompilacion(File f, long inicio) {
        return f.isFile() && f.lastModified() >= inicio - TOLERANCIA_FECHA_MS;
    }

    // ---------- navegacion de saltos ----------

    private String operadorEn(int fila) {
        if (fila < 0 || fila >= modelo.getRowCount()) return null;
        return (String) modelo.getValueAt(fila, COL_OPERADOR);
    }

    /**
     * Doble clic en la tabla: en un SALTO/SALTO_VER/SALTO_FAL selecciona la fila ETIQ de su etiqueta;
     * en una ETIQ, el siguiente salto que llega a ella (vuelve a empezar al llegar al final).
     */
    void seguirSalto(int fila) {
        String op = operadorEn(fila);
        if (op == null) return;
        String etiqueta = (String) modelo.getValueAt(fila, COL_RESULTADO);
        if (etiqueta == null || etiqueta.isEmpty()) return;
        int n = modelo.getRowCount();
        if (op.startsWith("SALTO")) {
            for (int i = 0; i < n; i++) {
                if ("ETIQ".equals(operadorEn(i)) && etiqueta.equals(modelo.getValueAt(i, COL_RESULTADO))) {
                    seleccionarFila(i);
                    alEstado.accept(" " + op + " -> " + etiqueta);
                    return;
                }
            }
            alEstado.accept(" No se encontró la etiqueta " + etiqueta);
        } else if (op.equals("ETIQ")) {
            for (int k = 1; k < n; k++) {
                int i = (fila + k) % n;
                if (operadorEn(i).startsWith("SALTO") && etiqueta.equals(modelo.getValueAt(i, COL_RESULTADO))) {
                    seleccionarFila(i);
                    alEstado.accept(" Salto hacia " + etiqueta);
                    return;
                }
            }
            alEstado.accept(" Ningún salto llega a " + etiqueta);
        }
    }

    private void seleccionarFila(int fila) {
        tabla.setRowSelectionInterval(fila, fila);
        tabla.scrollRectToVisible(tabla.getCellRect(fila, 0, true));
    }

    /** Doble clic sobre una etiqueta (L3) en el codigo de tres direcciones lleva a la linea "L3:". */
    private void instalarSaltoAEtiqueta(JTextArea area) {
        area.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                String etiqueta = etiquetaEn(area, e.getPoint());
                if (etiqueta == null) return;
                Matcher destino = Pattern.compile("(?m)^[ \\t]*" + etiqueta + ":").matcher(area.getText());
                if (!destino.find()) { alEstado.accept(" No se encontró " + etiqueta + ":"); return; }
                int desde = destino.end() - etiqueta.length() - 1;
                SwingUtilities.invokeLater(() -> { // despues de la seleccion de palabra del propio doble clic
                    area.setCaretPosition(desde);
                    area.moveCaretPosition(destino.end());
                    try {
                        java.awt.geom.Rectangle2D r = area.modelToView2D(desde);
                        if (r != null) area.scrollRectToVisible(r.getBounds());
                    } catch (BadLocationException ignorada) { }
                });
                alEstado.accept(" Etiqueta " + etiqueta);
            }
        });
    }

    /** La etiqueta (L1, L2...) bajo el punto `p`, o null. */
    private static String etiquetaEn(JTextArea area, Point p) {
        try {
            int offset = area.viewToModel2D(p);
            int n = area.getLineOfOffset(offset);
            int inicioLinea = area.getLineStartOffset(n);
            String linea = area.getText(inicioLinea, area.getLineEndOffset(n) - inicioLinea);
            Matcher m = PATRON_ETIQUETA.matcher(linea);
            int columna = offset - inicioLinea;
            while (m.find()) {
                if (columna >= m.start() && columna <= m.end()) return m.group();
            }
            return null;
        } catch (BadLocationException e) {
            return null;
        }
    }

    /** Resalta las filas FUN y ETIQ y colorea los saltos (SALTO, SALTO_VER, SALTO_FAL). */
    private class RenderCuadruplo extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object valor, boolean seleccionada,
                boolean foco, int fila, int columna) {
            super.getTableCellRendererComponent(t, valor, seleccionada, foco, fila, columna);
            String op = operadorEn(t.convertRowIndexToModel(fila));
            boolean esEtiq = "ETIQ".equals(op), esFun = "FUN".equals(op);
            boolean esSalto = op != null && op.startsWith("SALTO");
            setFont(t.getFont().deriveFont(esEtiq || esFun ? Font.BOLD : Font.PLAIN));
            if (seleccionada) return this;
            Color fondo = t.getBackground(), texto = t.getForeground();
            if (esFun) fondo = tema.fondoFilaFun;
            else if (esEtiq) { fondo = tema.fondoFilaEtiq; texto = tema.textoFilaEtiq; }
            else if (esSalto) texto = tema.textoSalto;
            setBackground(fondo);
            setForeground(texto);
            return this;
        }
    }
}
