package ide;

import javax.swing.*;
import java.awt.*;

/**
 * FlowLayout que, cuando los componentes no caben a lo ancho, los pasa a una fila nueva y avisa el
 * alto real (FlowLayout tambien los parte, pero sigue pidiendo una sola fila de alto y la segunda
 * queda oculta). Se usa en la barra de herramientas para que ningun boton desaparezca al angostar
 * la ventana.
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout(int alineacion, int hgap, int vgap) { super(alineacion, hgap, vgap); }

    @Override public Dimension preferredLayoutSize(Container destino) { return medir(destino, true); }

    @Override public Dimension minimumLayoutSize(Container destino) {
        Dimension d = medir(destino, false);
        d.width -= getHgap() + 1;
        return d;
    }

    private Dimension medir(Container destino, boolean preferido) {
        synchronized (destino.getTreeLock()) {
            // El ancho disponible es el del contenedor; si aun no tiene, el de su padre o "sin limite".
            int anchoDestino = destino.getSize().width;
            Container c = destino;
            while (anchoDestino == 0 && c.getParent() != null) {
                c = c.getParent();
                anchoDestino = c.getSize().width;
            }
            if (anchoDestino == 0) anchoDestino = Integer.MAX_VALUE;

            Insets bordes = destino.getInsets();
            int horizontal = bordes.left + bordes.right + getHgap() * 2;
            int anchoMaximo = anchoDestino - horizontal;

            Dimension total = new Dimension(0, 0);
            int anchoFila = 0, altoFila = 0;
            for (Component m : destino.getComponents()) {
                if (!m.isVisible()) continue;
                Dimension d = preferido ? m.getPreferredSize() : m.getMinimumSize();
                if (anchoFila + d.width > anchoMaximo && anchoFila > 0) {
                    agregarFila(total, anchoFila, altoFila);
                    anchoFila = 0;
                    altoFila = 0;
                }
                if (anchoFila != 0) anchoFila += getHgap();
                anchoFila += d.width;
                altoFila = Math.max(altoFila, d.height);
            }
            agregarFila(total, anchoFila, altoFila);
            total.width += horizontal;
            total.height += bordes.top + bordes.bottom + getVgap() * 2;

            // Dentro de un JScrollPane el ancho no se debe reducir solo por pedirlo.
            Container scroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, destino);
            if (scroll != null && destino.isValid()) total.width -= getHgap() + 1;
            return total;
        }
    }

    private void agregarFila(Dimension total, int anchoFila, int altoFila) {
        total.width = Math.max(total.width, anchoFila);
        if (total.height > 0) total.height += getVgap();
        total.height += altoFila;
    }
}
