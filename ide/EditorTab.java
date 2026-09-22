import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/** Una pestaña de editor: JTextPane con resaltado de sintaxis JER y su archivo asociado (si tiene). */
class EditorTab extends JScrollPane {
    private final JTextPane textPane = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() {
            return getUI().getPreferredSize(this).width <= getParent().getSize().width;
        }
    };
    private final NumeroLineaGutter numerosLinea = new NumeroLineaGutter(textPane);
    private final UndoManager undoManager = new UndoManager();
    private File archivo;
    private boolean modificado = false;

    private Runnable onChange = () -> {};

    EditorTab(File archivo) {
        super();
        setViewportView(textPane);
        textPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        setRowHeaderView(numerosLinea);

        StyledDocument doc = textPane.getStyledDocument();
        doc.addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { cambio(); }
            public void removeUpdate(DocumentEvent e) { cambio(); }
            public void changedUpdate(DocumentEvent e) { /* disparado por el propio resaltado: ignorar */ }
        });

        instalarAutocompletadoParejas();
        instalarAutoIndentado();
        instalarSeleccionPorLinea();

        doc.addUndoableEditListener(undoManager);
        textPane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "deshacer");
        textPane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "rehacer");
        textPane.getActionMap().put("deshacer", new AbstractActionSimple(() -> { if (undoManager.canUndo()) undoManager.undo(); }));
        textPane.getActionMap().put("rehacer", new AbstractActionSimple(() -> { if (undoManager.canRedo()) undoManager.redo(); }));

        if (archivo != null) {
            try {
                textPane.setText(Files.readString(archivo.toPath()));
                this.archivo = archivo;
            } catch (IOException e) {
                textPane.setText("");
            }
        }
        modificado = false;
        JERSyntaxHighlighter.aplicar(doc);
        undoManager.discardAllEdits();
    }

    private void cambio() {
        modificado = true;
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = textPane.getStyledDocument();
            doc.removeUndoableEditListener(undoManager);
            JERSyntaxHighlighter.aplicar(doc);
            doc.addUndoableEditListener(undoManager);
        });
        onChange.run();
    }

    private static final Map<Character, Character> PAREJAS = Map.of(
            '(', ')', '{', '}', '"', '"', '\'', '\'');

    /** Al escribir ( { " ' inserta el cierre y deja el caret en medio; si el cierre ya sigue, lo salta en vez de duplicar. */
    private void instalarAutocompletadoParejas() {
        textPane.addKeyListener(new KeyAdapter() {
            @Override public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                Document doc = textPane.getDocument();
                int pos = textPane.getCaretPosition();

                if (esCierre(c)) {
                    char siguiente = charEn(doc, pos);
                    if (siguiente == c) {
                        e.consume();
                        textPane.setCaretPosition(pos + 1);
                    }
                    return;
                }

                Character cierre = PAREJAS.get(c);
                if (cierre == null) return;

                if (cierre == c) {
                    char siguiente = charEn(doc, pos);
                    if (siguiente == c) {
                        e.consume();
                        textPane.setCaretPosition(pos + 1);
                        return;
                    }
                }

                e.consume();
                try {
                    doc.insertString(pos, String.valueOf(c) + cierre, null);
                    textPane.setCaretPosition(pos + 1);
                } catch (BadLocationException ex) {
                    // no debería ocurrir con una posicion valida del caret
                }
            }
        });
    }

    private static boolean esCierre(char c) { return c == ')' || c == '}'; }

    private static char charEn(Document doc, int pos) {
        try {
            return pos < doc.getLength() ? doc.getText(pos, 1).charAt(0) : '\0';
        } catch (BadLocationException e) {
            return '\0';
        }
    }

    private static final String UNIDAD_INDENTACION = "    ";

    /** Enter copia la indentacion de la linea actual y agrega un nivel si esta termina en '{'. */
    private void instalarAutoIndentado() {
        textPane.getActionMap().put(DefaultEditorKit.insertBreakAction, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                Document doc = textPane.getDocument();
                try {
                    int pos = textPane.getCaretPosition();
                    Element linea = doc.getDefaultRootElement().getElement(doc.getDefaultRootElement().getElementIndex(pos));
                    int inicioLinea = linea.getStartOffset();
                    String antesDelCaret = doc.getText(inicioLinea, pos - inicioLinea);

                    int i = 0;
                    while (i < antesDelCaret.length() && (antesDelCaret.charAt(i) == ' ' || antesDelCaret.charAt(i) == '\t')) i++;
                    String indentacion = antesDelCaret.substring(0, i);

                    boolean abreBloque = antesDelCaret.stripTrailing().endsWith("{");
                    boolean cierraJusto = charEn(doc, pos) == '}';

                    if (abreBloque && cierraJusto) {
                        String indentacionInterna = indentacion + UNIDAD_INDENTACION;
                        doc.insertString(pos, "\n" + indentacionInterna + "\n" + indentacion, null);
                        textPane.setCaretPosition(pos + 1 + indentacionInterna.length());
                        return;
                    }

                    if (abreBloque) indentacion += UNIDAD_INDENTACION;
                    doc.insertString(pos, "\n" + indentacion, null);
                } catch (BadLocationException ex) {
                    // no debería ocurrir con una posicion valida del caret
                }
            }
        });

        // dedent: si '}' se escribe con solo espacios antes en la linea, le quita un nivel de indentacion
        textPane.addKeyListener(new KeyAdapter() {
            @Override public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() != '}') return;
                Document doc = textPane.getDocument();
                int pos = textPane.getCaretPosition();
                try {
                    Element linea = doc.getDefaultRootElement().getElement(doc.getDefaultRootElement().getElementIndex(pos));
                    int inicioLinea = linea.getStartOffset();
                    String antesDelCaret = doc.getText(inicioLinea, pos - inicioLinea);
                    if (!antesDelCaret.isBlank()) return;
                    if (antesDelCaret.endsWith(UNIDAD_INDENTACION)) {
                        doc.remove(pos - UNIDAD_INDENTACION.length(), UNIDAD_INDENTACION.length());
                    }
                } catch (BadLocationException ex) {
                    // no debería ocurrir con una posicion valida del caret
                }
            }
        });
    }

    /** Ctrl+Shift+Up/Down extiende la seleccion linea por linea (Shift+Up/Down ya lo hace sin Ctrl). */
    private void instalarSeleccionPorLinea() {
        int ctrlShift = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK;
        textPane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, ctrlShift), DefaultEditorKit.selectionUpAction);
        textPane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, ctrlShift), DefaultEditorKit.selectionDownAction);
    }

    private static class AbstractActionSimple extends AbstractAction {
        private final Runnable accion;
        AbstractActionSimple(Runnable accion) { this.accion = accion; }
        public void actionPerformed(java.awt.event.ActionEvent e) { accion.run(); }
    }

    void setOnChange(Runnable onChange) { this.onChange = onChange; }

    void aplicarTema(boolean oscuro) {
        Color fondo = oscuro ? new Color(0x2B2B2B) : Color.WHITE;
        Color fondoNumeros = oscuro ? new Color(0x333333) : new Color(0xF0F0F0);
        Color textoNumeros = oscuro ? new Color(0x808080) : Color.GRAY;
        textPane.setBackground(fondo);
        textPane.setCaretColor(oscuro ? Color.WHITE : Color.BLACK);
        numerosLinea.setColores(fondoNumeros, textoNumeros);
        JERSyntaxHighlighter.aplicar(textPane.getStyledDocument());
    }

    String getTexto() { return textPane.getText(); }

    File getArchivo() { return archivo; }

    void setArchivo(File f) { this.archivo = f; }

    boolean isModificado() { return modificado; }

    void guardar(File destino) throws IOException {
        Files.writeString(destino.toPath(), getTexto());
        this.archivo = destino;
        modificado = false;
    }

    String nombrePestana() {
        String base = archivo != null ? archivo.getName() : "Sin titulo";
        return modificado ? base + " *" : base;
    }
}
