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
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Una pestaña de editor: JTextPane con resaltado de sintaxis JER y su archivo asociado (si tiene). */
class EditorTab extends JScrollPane {
    private final JTextPane textPane = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() {
            return getUI().getPreferredSize(this).width <= getParent().getSize().width;
        }
    };
    private final NumeroLineaGutter numerosLinea = new NumeroLineaGutter(textPane);
    private final UndoManager undoManager = new UndoManager();
    private static final int FUENTE_MIN = 8, FUENTE_MAX = 40;
    private static int tamanioFuente = 14;
    private static final Highlighter.HighlightPainter PINTOR_ERROR =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0xFF, 0x50, 0x50, 0x55));
    private final Timer timerResaltado = new Timer(80, e -> resaltar());
    private File archivo;
    private boolean modificado = false;

    private Runnable onChange = () -> {};
    private Runnable onCaret = () -> {};

    EditorTab(File archivo) throws IOException {
        super();
        timerResaltado.setRepeats(false);
        setViewportView(textPane);
        textPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, tamanioFuente));

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
        instalarEdicionDeLineas();
        instalarZoom();
        textPane.addCaretListener(e -> onCaret.run());

        doc.addUndoableEditListener(undoManager);
        textPane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "deshacer");
        textPane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "rehacer");
        textPane.getActionMap().put("deshacer", new AbstractActionSimple(() -> { if (undoManager.canUndo()) undoManager.undo(); }));
        textPane.getActionMap().put("rehacer", new AbstractActionSimple(() -> { if (undoManager.canRedo()) undoManager.redo(); }));

        if (archivo != null) {
            textPane.setText(Files.readString(archivo.toPath()));
            this.archivo = archivo;
        }
        modificado = false;
        JERSyntaxHighlighter.aplicar(doc);
        undoManager.discardAllEdits();
    }

    private void cambio() {
        modificado = true;
        timerResaltado.restart();
        onChange.run();
    }

    /** Re-resalta sin que los cambios de atributos entren al historial de deshacer. */
    private void resaltar() {
        StyledDocument doc = textPane.getStyledDocument();
        doc.removeUndoableEditListener(undoManager);
        JERSyntaxHighlighter.aplicar(doc);
        doc.addUndoableEditListener(undoManager);
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

    /** Ctrl+/ comenta/descomenta lineas con '#'; Tab / Shift+Tab indentan / desindentan las lineas seleccionadas. */
    private void instalarEdicionDeLineas() {
        int ctrl = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        // Tab y Shift+Tab dejan de ser teclas de foco para poder usarse como indentar / desindentar
        textPane.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
                Set.of(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, ctrl)));
        textPane.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
                Set.of(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, ctrl | KeyEvent.SHIFT_DOWN_MASK)));

        textPane.getActionMap().put("comentar", new AbstractActionSimple(this::alternarComentario));
        textPane.getActionMap().put("indentar", new AbstractActionSimple(this::indentar));
        textPane.getActionMap().put("desindentar", new AbstractActionSimple(
                () -> transformarLineas(l -> l.startsWith("\t") ? l.substring(1) : l.replaceFirst("^ {1,4}", ""))));
        // Ctrl+/ no existe como tecla directa en teclados en espanol (es Shift+7), por eso tambien Ctrl+7
        for (int k : new int[] {KeyEvent.VK_SLASH, KeyEvent.VK_DIVIDE, KeyEvent.VK_7}) {
            textPane.getInputMap().put(KeyStroke.getKeyStroke(k, ctrl), "comentar");
        }
        textPane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "indentar");
        textPane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "desindentar");
    }

    private void indentar() {
        if (textPane.getSelectionStart() == textPane.getSelectionEnd()) {
            textPane.replaceSelection(UNIDAD_INDENTACION);
        } else {
            transformarLineas(l -> l.isBlank() ? l : UNIDAD_INDENTACION + l);
        }
    }

    private void alternarComentario() {
        boolean todasComentadas = true;
        try {
            for (String l : lineasSeleccionadas().split("\n", -1)) {
                if (!l.isBlank() && !l.stripLeading().startsWith("#")) todasComentadas = false;
            }
        } catch (BadLocationException e) {
            return;
        }
        if (todasComentadas) transformarLineas(l -> l.replaceFirst("^(\\s*)# ?", "$1"));
        else transformarLineas(l -> l.isBlank() ? l : l.replaceFirst("^(\\s*)", "$1# "));
    }

    /** Rango [desde, hasta) de las lineas completas tocadas por la seleccion (o por el caret). */
    private int[] rangoLineas() {
        Document doc = textPane.getDocument();
        Element raiz = doc.getDefaultRootElement();
        int ini = textPane.getSelectionStart(), fin = textPane.getSelectionEnd();
        int primera = raiz.getElementIndex(ini);
        int ultima = raiz.getElementIndex(fin);
        if (fin > ini && fin == raiz.getElement(ultima).getStartOffset()) ultima--;
        return new int[] {raiz.getElement(primera).getStartOffset(),
                Math.min(raiz.getElement(ultima).getEndOffset(), doc.getLength())};
    }

    private String lineasSeleccionadas() throws BadLocationException {
        int[] r = rangoLineas();
        return textPane.getDocument().getText(r[0], r[1] - r[0]);
    }

    /** Aplica f a cada linea de la seleccion; una sola edicion reemplaza el bloque. */
    private void transformarLineas(UnaryOperator<String> f) {
        try {
            int[] r = rangoLineas();
            int ini = textPane.getSelectionStart(), fin = textPane.getSelectionEnd();
            String viejo = textPane.getDocument().getText(r[0], r[1] - r[0]);
            String[] lineas = viejo.split("\n", -1);
            for (int i = 0; i < lineas.length; i++) lineas[i] = f.apply(lineas[i]);
            String nuevo = String.join("\n", lineas);
            if (nuevo.equals(viejo)) return;
            ((AbstractDocument) textPane.getDocument()).replace(r[0], r[1] - r[0], nuevo, null);
            if (ini == fin) textPane.setCaretPosition(Math.max(r[0], ini + nuevo.length() - viejo.length()));
            else textPane.select(r[0], r[0] + nuevo.length());
        } catch (BadLocationException e) {
            // el rango sale del propio documento, no deberia ocurrir
        }
    }

    /** Ctrl + rueda del mouse cambia el tamano de la fuente; sin Ctrl la rueda sigue desplazando el scroll. */
    private void instalarZoom() {
        textPane.addMouseWheelListener(e -> {
            if (!e.isControlDown()) {
                EditorTab.this.dispatchEvent(SwingUtilities.convertMouseEvent(textPane, e, EditorTab.this));
                return;
            }
            tamanioFuente = Math.max(FUENTE_MIN, Math.min(FUENTE_MAX, tamanioFuente - e.getWheelRotation()));
            textPane.setFont(textPane.getFont().deriveFont((float) tamanioFuente));
            numerosLinea.revalidate();
            numerosLinea.repaint();
        });
    }

    private static class AbstractActionSimple extends AbstractAction {
        private final Runnable accion;
        AbstractActionSimple(Runnable accion) { this.accion = accion; }
        public void actionPerformed(java.awt.event.ActionEvent e) { accion.run(); }
    }

    void setOnChange(Runnable onChange) { this.onChange = onChange; }

    void setOnCaret(Runnable onCaret) { this.onCaret = onCaret; }

    /** "Ln x, Col y" de la posicion del caret. */
    String posicion() {
        int pos = textPane.getCaretPosition();
        Element raiz = textPane.getDocument().getDefaultRootElement();
        int linea = raiz.getElementIndex(pos);
        return "Ln " + (linea + 1) + ", Col " + (pos - raiz.getElement(linea).getStartOffset() + 1);
    }

    /** Lleva el caret al inicio de la linea (1-based) y le da el foco al editor. */
    void irALinea(int linea) {
        Element raiz = textPane.getDocument().getDefaultRootElement();
        if (linea < 1 || linea > raiz.getElementCount()) return;
        textPane.setCaretPosition(raiz.getElement(linea - 1).getStartOffset());
        textPane.requestFocusInWindow();
    }

    /** Pinta de rojo las lineas dadas (1-based); null solo limpia las marcas anteriores. */
    void marcarLineas(Set<Integer> lineas) {
        Highlighter h = textPane.getHighlighter();
        h.removeAllHighlights();
        if (lineas == null) return;
        Element raiz = textPane.getDocument().getDefaultRootElement();
        for (int n : lineas) {
            if (n < 1 || n > raiz.getElementCount()) continue;
            Element el = raiz.getElement(n - 1);
            try {
                h.addHighlight(el.getStartOffset(), Math.max(el.getStartOffset() + 1, el.getEndOffset() - 1), PINTOR_ERROR);
            } catch (BadLocationException ignored) {
            }
        }
    }

    /** Selecciona la siguiente (o anterior) coincidencia, sin distinguir mayusculas, dando la vuelta al documento. */
    boolean buscar(String texto, boolean adelante) {
        if (texto.isEmpty()) return false;
        String contenido = textPane.getText().toLowerCase();
        String q = texto.toLowerCase();
        int i;
        if (adelante) {
            i = contenido.indexOf(q, textPane.getSelectionEnd());
            if (i < 0) i = contenido.indexOf(q);
        } else {
            i = textPane.getSelectionStart() == 0 ? -1 : contenido.lastIndexOf(q, textPane.getSelectionStart() - 1);
            if (i < 0) i = contenido.lastIndexOf(q);
        }
        if (i < 0) return false;
        textPane.select(i, i + q.length());
        textPane.getCaret().setSelectionVisible(true);
        return true;
    }

    /** Reemplaza la seleccion si coincide con 'texto' y pasa a la siguiente coincidencia. */
    boolean reemplazar(String texto, String por) {
        String sel = textPane.getSelectedText();
        if (sel != null && sel.equalsIgnoreCase(texto)) textPane.replaceSelection(por);
        return buscar(texto, true);
    }

    /** Reemplaza todas las coincidencias en una sola edicion (un Ctrl+Z las deshace); devuelve cuantas fueron. */
    int reemplazarTodo(String texto, String por) {
        if (texto.isEmpty()) return 0;
        String viejo = textPane.getText();
        Matcher m = Pattern.compile(Pattern.quote(texto), Pattern.CASE_INSENSITIVE).matcher(viejo);
        int n = 0;
        while (m.find()) n++;
        if (n == 0) return 0;
        String nuevo = m.reset().replaceAll(Matcher.quoteReplacement(por));
        try {
            ((AbstractDocument) textPane.getDocument()).replace(0, viejo.length(), nuevo, null);
        } catch (BadLocationException e) {
            return 0;
        }
        return n;
    }

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
