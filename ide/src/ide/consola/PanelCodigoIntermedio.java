package ide.consola;

import ide.Tema;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Las dos vistas del codigo intermedio: "Cuadruplos" (tabla, pestana de la consola) y el documento de
 * tres direcciones (DocumentoTresDirecciones, a la derecha del editor).
 * Se llenan con los archivos que el compilador deja en salida/ (carpeta hermana de build/).
 */
public class PanelCodigoIntermedio {
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
    private final DocumentoTresDirecciones documento;

    /**
     * `alEstado` recibe los mensajes para la barra de estado; `alCerrarDocumento` se llama con la "x"
     * del documento de tres direcciones.
     */
    public PanelCodigoIntermedio(File carpetaSalida, Consumer<String> alEstado, Runnable alCerrarDocumento) {
        this.carpetaSalida = carpetaSalida;
        this.alEstado = alEstado;
        this.documento = new DocumentoTresDirecciones(alEstado, alCerrarDocumento);

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

        aplicarTema(Tema.CLARO);
    }

    /** Agrega la pestana Cuadruplos (el codigo de tres direcciones va aparte, en documento()). */
    public void agregarA(JTabbedPane pestanas) {
        pestanas.addTab("Cuádruplos", panelCuadruplos);
    }

    /** El documento de tres direcciones, para colocarlo junto al editor. */
    public DocumentoTresDirecciones documento() { return documento; }

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
        documento.aplicarTema(tema);
        tabla.repaint();
    }

    /** Vacia las dos vistas y deja `mensaje` sobre la tabla (vacio: sin mensaje). */
    public void limpiar(String mensaje) {
        modelo.setRowCount(0);
        aviso.setText(mensaje);
        aviso.setVisible(!mensaje.isBlank());
        documento.limpiar();
    }

    /**
     * Carga salida/cuadruplos.txt y salida/cod_tres_dir.txt. El compilador
     * solo los reescribe con 0 errores; con errores conservan los de una compilacion anterior, asi que
     * se cargan solo si la compilacion termino sin errores y los archivos son de esta compilacion.
     * `fuente` es el nombre del archivo compilado (subtitulo del documento).
     * Devuelve true si hay codigo intermedio para mostrar.
     */
    public boolean cargar(boolean sinErrores, long inicio, String fuente) {
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
            documento.mostrar(LectorCuadruplos.leerTexto(tresDir), "salida" + File.separator + tresDir.getName() + "  ·  " + fuente);
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
