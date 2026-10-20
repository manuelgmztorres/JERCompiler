package ide.consola;

import ide.Tema;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Una tabla de solo lectura para mostrar un archivo de salida/ (tokens, simbolos) con un aviso
 * opcional arriba. Doble clic en una fila llama a `alDobleClic` con su indice.
 */
public class TablaTexto extends JPanel {
    private final DefaultTableModel modelo;
    private final JTable tabla;
    private final JScrollPane scroll;
    private final JLabel aviso = new JLabel();

    public TablaTexto(String[] columnas, int[] anchos, IntConsumer alDobleClic) {
        super(new BorderLayout());
        modelo = new DefaultTableModel(columnas, 0) {
            @Override public boolean isCellEditable(int fila, int columna) { return false; }
        };
        tabla = new JTable(modelo);
        scroll = new JScrollPane(tabla);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        tabla.setFont(mono);
        tabla.setRowHeight(tabla.getFontMetrics(mono).getHeight() + 4);
        tabla.setFillsViewportHeight(true);
        tabla.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabla.getTableHeader().setReorderingAllowed(false);
        for (int c = 0; c < anchos.length && c < columnas.length; c++) tabla.getColumnModel().getColumn(c).setPreferredWidth(anchos[c]);
        tabla.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int fila = tabla.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && fila >= 0) alDobleClic.accept(fila);
            }
        });
        aviso.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        aviso.setVisible(false);
        add(aviso, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    public void cargar(List<String[]> filas, String mensaje) {
        modelo.setRowCount(0);
        for (String[] fila : filas) modelo.addRow(fila);
        avisar(mensaje);
    }

    /** Vacia la tabla y deja `mensaje` arriba (vacio: sin mensaje). */
    public void limpiar(String mensaje) { cargar(List.of(), mensaje); }

    public String valor(int fila, int columna) { return (String) modelo.getValueAt(fila, columna); }

    public int filas() { return modelo.getRowCount(); }

    private void avisar(String mensaje) {
        aviso.setText(mensaje);
        aviso.setVisible(!mensaje.isBlank());
    }

    public void aplicarTema(Tema tema) {
        tabla.setBackground(tema.fondoTabla);
        tabla.setForeground(tema.textoTabla);
        tabla.setGridColor(tema.rejillaTabla);
        tabla.setSelectionBackground(tema.fondoSeleccion);
        tabla.setSelectionForeground(tema.textoSeleccion);
        tabla.getTableHeader().setBackground(tema.fondoEncabezado);
        tabla.getTableHeader().setForeground(tema.textoEncabezado);
        scroll.getViewport().setBackground(tema.fondoTabla);
        setBackground(tema.fondoPanel);
        aviso.setForeground(tema.textoPanel);
        tabla.repaint();
    }
}
