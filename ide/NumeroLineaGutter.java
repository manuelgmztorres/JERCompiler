import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;

/**
 * Gutter de numeros de linea que se pinta usando la posicion real de cada linea dentro del
 * JTextPane (via modelToView), no un JTextArea paralelo: asi no se desalinea aunque las lineas
 * con palabras clave en negrita midan un poco mas alto que las lineas normales.
 */
class NumeroLineaGutter extends JComponent {
    private final JTextPane textPane;
    private Color colorFondo = new Color(0xF0F0F0);
    private Color colorTexto = Color.GRAY;

    NumeroLineaGutter(JTextPane textPane) {
        this.textPane = textPane;
        setFont(textPane.getFont());
        textPane.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refrescar(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refrescar(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refrescar(); }
        });
    }

    private void refrescar() {
        SwingUtilities.invokeLater(() -> {
            revalidate();
            repaint();
        });
    }

    void setColores(Color fondo, Color texto) {
        this.colorFondo = fondo;
        this.colorTexto = texto;
        repaint();
    }

    private int numeroDeLineas() {
        return textPane.getDocument().getDefaultRootElement().getElementCount();
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(textPane.getFont());
        int digitos = Math.max(2, String.valueOf(numeroDeLineas()).length());
        int ancho = fm.stringWidth("0") * digitos + 16;
        int alto = textPane.getPreferredSize().height;
        return new Dimension(ancho, alto);
    }

    @Override protected void paintComponent(Graphics g) {
        g.setColor(colorFondo);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(colorTexto);
        g.setFont(textPane.getFont());
        FontMetrics fm = g.getFontMetrics();

        Element raiz = textPane.getDocument().getDefaultRootElement();
        Rectangle clip = g.getClipBounds();
        for (int i = 0; i < raiz.getElementCount(); i++) {
            int offset = raiz.getElement(i).getStartOffset();
            try {
                Rectangle r = textPane.modelToView(offset);
                if (r == null) continue;
                if (r.y + r.height < clip.y || r.y > clip.y + clip.height) continue;
                String numero = String.valueOf(i + 1);
                int x = getWidth() - fm.stringWidth(numero) - 6;
                int y = r.y + fm.getAscent();
                g.drawString(numero, x, y);
            } catch (BadLocationException ignored) {
            }
        }
    }
}
