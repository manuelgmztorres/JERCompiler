package ide.consola;

import ide.Tema;
import ide.editor.NumeroLineaGutter;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El codigo de tres direcciones como documento de solo lectura, a la derecha del editor: encabezado
 * con titulo y origen, numeros de linea y resaltado (FUN, etiquetas "L3:", saltos, IMP/RET, temporales).
 * Doble clic sobre una etiqueta (L3) lleva a la linea "L3:".
 */
public class DocumentoTresDirecciones extends JPanel {
    /** Una etiqueta de salto (L1, L2...). */
    private static final Pattern PATRON_ETIQUETA = Pattern.compile("\\bL\\d+\\b");
    private static final Pattern PATRON_TEMPORAL = Pattern.compile("\\bt\\d+\\b");
    private static final Pattern PATRON_PALABRA = Pattern.compile("\\b(FUN|RET|IMP|LEE|SALTO|SALTO_VER|SALTO_FAL|si|ir_a|goto|if|ifFalse|param|call|return)\\b");
    private static final Pattern PATRON_CADENA = Pattern.compile("\"[^\"]*\"|'[^']*'");

    private final JTextPane texto = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() {
            return getUI().getPreferredSize(this).width <= getParent().getSize().width;
        }
    };
    private final NumeroLineaGutter numeros = new NumeroLineaGutter(texto);
    private final JScrollPane scroll = new JScrollPane(texto);
    private final JPanel encabezado = new JPanel(new BorderLayout());
    private final JLabel titulo = new JLabel("Código de tres direcciones");
    private final JLabel subtitulo = new JLabel(" ");
    private final JButton cerrar = new JButton("x");
    private final Consumer<String> alEstado;
    private Tema tema = Tema.CLARO;

    private final SimpleAttributeSet estNormal = new SimpleAttributeSet();
    private final SimpleAttributeSet estFun = new SimpleAttributeSet();
    private final SimpleAttributeSet estEtiqueta = new SimpleAttributeSet();
    private final SimpleAttributeSet estPalabra = new SimpleAttributeSet();
    private final SimpleAttributeSet estTemporal = new SimpleAttributeSet();
    private final SimpleAttributeSet estCadena = new SimpleAttributeSet();
    private final SimpleAttributeSet estComentario = new SimpleAttributeSet();
    private boolean hayCodigo = false;

    /** `alEstado` recibe los mensajes para la barra de estado; `alCerrar` se llama con el boton "x". */
    public DocumentoTresDirecciones(Consumer<String> alEstado, Runnable alCerrar) {
        super(new BorderLayout());
        this.alEstado = alEstado;
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        texto.setFont(mono);
        texto.setEditable(false);
        texto.setMargin(new Insets(6, 10, 6, 10));
        numeros.setFont(mono);
        scroll.setRowHeaderView(numeros);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 13f));
        subtitulo.setFont(subtitulo.getFont().deriveFont(Font.PLAIN, 11f));
        JPanel textos = new JPanel(new GridLayout(2, 1));
        textos.setOpaque(false);
        textos.add(titulo);
        textos.add(subtitulo);
        cerrar.setMargin(new Insets(0, 6, 0, 6));
        cerrar.setFocusable(false);
        cerrar.setBorderPainted(false);
        cerrar.setContentAreaFilled(false);
        cerrar.setToolTipText("Ocultar el código de tres direcciones");
        cerrar.addActionListener(e -> alCerrar.run());
        encabezado.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 4));
        encabezado.add(textos, BorderLayout.CENTER);
        encabezado.add(cerrar, BorderLayout.EAST);

        add(encabezado, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        instalarSaltoAEtiqueta();
        aplicarTema(Tema.CLARO);
        limpiar();
    }

    /** Muestra el codigo (`origen` va en el subtitulo: el archivo fuente). */
    public void mostrar(String codigo, String origen) {
        hayCodigo = true;
        subtitulo.setText(origen == null || origen.isBlank() ? " " : origen);
        texto.setText(codigo);
        resaltar();
        texto.setCaretPosition(0);
    }

    /** Vacia el documento. */
    public void limpiar() {
        hayCodigo = false;
        subtitulo.setText(" ");
        texto.setText("");
    }

    public void aplicarTema(Tema tema) {
        this.tema = tema;
        setBackground(tema.fondoDocumento);
        texto.setBackground(tema.fondoDocumento);
        texto.setCaretColor(tema.textoDocumento);
        scroll.getViewport().setBackground(tema.fondoDocumento);
        numeros.setColores(tema.fondoNumeros, tema.textoNumeros);
        encabezado.setBackground(tema.fondoEncabezadoDoc);
        encabezado.setOpaque(true);
        titulo.setForeground(tema.textoEncabezadoDoc);
        subtitulo.setForeground(tema.subtituloDoc);
        cerrar.setForeground(tema.textoEncabezadoDoc);
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, tema.rejillaTabla));

        StyleConstants.setForeground(estNormal, tema.textoDocumento);
        StyleConstants.setForeground(estFun, tema.sintaxisPalabraClave);
        StyleConstants.setBold(estFun, true);
        StyleConstants.setForeground(estEtiqueta, tema.textoFilaEtiq);
        StyleConstants.setBold(estEtiqueta, true);
        StyleConstants.setForeground(estPalabra, tema.textoSalto);
        StyleConstants.setForeground(estTemporal, tema.sintaxisBooleano);
        StyleConstants.setForeground(estCadena, tema.sintaxisCadena);
        StyleConstants.setForeground(estComentario, tema.sintaxisComentario);
        if (hayCodigo) resaltar();
    }

    /** El texto tal cual esta en el modelo (solo '\n'), para que los offsets coincidan. */
    private String textoDocumento() {
        Document d = texto.getDocument();
        try {
            return d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    // ---------- resaltado ----------

    private void resaltar() {
        StyledDocument d = texto.getStyledDocument();
        String s = textoDocumento();
        d.setCharacterAttributes(0, d.getLength(), estNormal, true);
        int inicio = 0;
        for (String linea : s.split("\n", -1)) {
            String t = linea.trim();
            if (t.startsWith("FUN ")) {
                d.setCharacterAttributes(inicio, linea.length(), estFun, true);
            } else if (t.matches("L\\d+:.*")) {
                int desde = inicio + linea.indexOf(t);
                d.setCharacterAttributes(desde, t.indexOf(':') + 1, estEtiqueta, true);
                pintar(d, linea, inicio, t.indexOf(':') + 1 + linea.indexOf(t));
            } else if (t.startsWith("---") || t.startsWith("CÓDIGO") || t.startsWith("CODIGO") || t.startsWith("Archivo:")) {
                d.setCharacterAttributes(inicio, linea.length(), estComentario, true);
            } else {
                pintar(d, linea, inicio, 0);
            }
            inicio += linea.length() + 1;
        }
    }

    /** Colorea palabras, temporales, etiquetas y cadenas de `linea` a partir de la columna `desde`. */
    private void pintar(StyledDocument d, String linea, int inicio, int desde) {
        String resto = linea.substring(Math.min(desde, linea.length()));
        Object[][] reglas = { {PATRON_PALABRA, estPalabra}, {PATRON_TEMPORAL, estTemporal},
                {PATRON_ETIQUETA, estEtiqueta}, {PATRON_CADENA, estCadena} };
        for (Object[] r : reglas) {
            Matcher m = ((Pattern) r[0]).matcher(resto);
            while (m.find()) d.setCharacterAttributes(inicio + desde + m.start(), m.end() - m.start(), (AttributeSet) r[1], true);
        }
    }

    // ---------- navegacion de etiquetas ----------

    private void instalarSaltoAEtiqueta() {
        texto.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !hayCodigo) return;
                String etiqueta = etiquetaEn(e.getPoint());
                if (etiqueta == null) return;
                Matcher destino = Pattern.compile("(?m)^[ \\t]*" + etiqueta + ":").matcher(textoDocumento());
                if (!destino.find()) { alEstado.accept(" No se encontró " + etiqueta + ":"); return; }
                int desde = destino.end() - etiqueta.length() - 1;
                SwingUtilities.invokeLater(() -> { // despues de la seleccion de palabra del propio doble clic
                    texto.setCaretPosition(desde);
                    texto.moveCaretPosition(destino.end());
                    try {
                        java.awt.geom.Rectangle2D r = texto.modelToView2D(desde);
                        if (r != null) texto.scrollRectToVisible(r.getBounds());
                    } catch (BadLocationException ignorada) { }
                });
                alEstado.accept(" Etiqueta " + etiqueta);
            }
        });
    }

    /** La etiqueta (L1, L2...) bajo el punto `p`, o null. */
    private String etiquetaEn(Point p) {
        try {
            int offset = texto.viewToModel2D(p);
            if (offset < 0) return null;
            Element raiz = texto.getDocument().getDefaultRootElement();
            Element linea = raiz.getElement(raiz.getElementIndex(offset));
            int fin = Math.min(linea.getEndOffset(), texto.getDocument().getLength());
            String s = texto.getText(linea.getStartOffset(), fin - linea.getStartOffset());
            Matcher m = PATRON_ETIQUETA.matcher(s);
            int columna = offset - linea.getStartOffset();
            while (m.find()) {
                if (columna >= m.start() && columna <= m.end()) return m.group();
            }
            return null;
        } catch (BadLocationException e) {
            return null;
        }
    }
}
