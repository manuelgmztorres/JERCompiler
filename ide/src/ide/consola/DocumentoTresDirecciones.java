package ide.consola;

import ide.Tema;
import ide.editor.NumeroLineaGutter;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El codigo intermedio como documento de solo lectura, a la derecha del editor, en cuatro vistas que
 * se eligen con los botones de la barra (ver Vista): el codigo de tres direcciones sin optimizar,
 * el optimizado, el codigo dividido en bloques basicos y el grafo de flujo de esos bloques.
 * Encabezado con titulo y origen, numeros de linea y resaltado (FUN, etiquetas "L3:", saltos, IMP/RET,
 * temporales, encabezados de bloque "[B3]"). Doble clic sobre una etiqueta (L3) lleva a la linea "L3:";
 * sobre un bloque (B3), a su encabezado "[B3]". La cuarta vista dibuja esos bloques como grafo de flujo
 * de control (GrafoFlujo); doble clic en una caja abre ese bloque en la vista de bloques.
 */
public class DocumentoTresDirecciones extends JPanel {

    /** Las vistas del documento, cada una con el archivo de salida/ que muestra. */
    public enum Vista {
        SIN_OPTIMIZAR("Código de tres direcciones", "cod_tres_dir.txt"),
        OPTIMIZADO("Código de tres direcciones optimizado", "cod_tres_dir_opt.txt"),
        BLOQUES("Bloques básicos", "bloques_basicos.txt"),
        GRAFO("Grafo de flujo de control", "bloques_basicos.txt");

        final String titulo;
        public final String archivo;

        Vista(String titulo, String archivo) { this.titulo = titulo; this.archivo = archivo; }

        /** Las vistas de texto con una linea por cuadruplo (las mismas lineas en las dos). */
        boolean esListado() { return this == SIN_OPTIMIZAR || this == OPTIMIZADO; }
    }

    /** Una etiqueta de salto (L1, L2...). */
    private static final Pattern PATRON_ETIQUETA = Pattern.compile("\\bL\\d+\\b");
    /** Un bloque basico (B1, B2...) en la vista de bloques. */
    private static final Pattern PATRON_BLOQUE = Pattern.compile("\\bB\\d+\\b");
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
    private final GrafoFlujo grafo = new GrafoFlujo(this::abrirBloque);
    private final JScrollPane scrollGrafo = new JScrollPane(grafo);
    private final CardLayout tarjetas = new CardLayout();
    private final JPanel centro = new JPanel(tarjetas);
    private Consumer<Vista> alCambiarVista = v -> { };
    private final JPanel encabezado = new JPanel(new BorderLayout());
    private final JLabel titulo = new JLabel(Vista.SIN_OPTIMIZAR.titulo);
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
    private final SimpleAttributeSet estBloque = new SimpleAttributeSet();

    /** Texto de cada vista; null si el compilador no genero su archivo (se muestra el aviso). */
    private final Map<Vista, String> textos = new EnumMap<>(Vista.class);
    private final Map<Vista, String> avisos = new EnumMap<>(Vista.class);
    private String fuente = "";
    private boolean hayCodigo = false;
    private Vista vista = Vista.SIN_OPTIMIZAR;

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
        scrollGrafo.setBorder(BorderFactory.createEmptyBorder());
        scrollGrafo.getVerticalScrollBar().setUnitIncrement(16);
        scrollGrafo.getHorizontalScrollBar().setUnitIncrement(16);
        centro.add(scroll, "texto");
        centro.add(scrollGrafo, "grafo");

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
        add(centro, BorderLayout.CENTER);
        instalarNavegacion();
        aplicarTema(Tema.CLARO);
        limpiar();
    }

    /**
     * Carga las vistas. `textos` trae el contenido de cada archivo; una vista ausente o null se
     * muestra con `avisos` (por que no hay). `fuente` es el archivo compilado (va en el subtitulo).
     */
    public void mostrar(Map<Vista, String> textos, Map<Vista, String> avisos, String fuente) {
        hayCodigo = true;
        this.fuente = fuente == null ? "" : fuente;
        this.textos.clear();
        this.textos.putAll(textos);
        this.avisos.clear();
        this.avisos.putAll(avisos);
        pintarVista(false);
    }

    /** Vacia el documento (conserva la vista elegida). */
    public void limpiar() {
        hayCodigo = false;
        tarjetas.show(centro, "texto");
        textos.clear();
        avisos.clear();
        fuente = "";
        titulo.setText(vista.titulo);
        subtitulo.setText(" ");
        texto.setText("");
    }

    public Vista vista() { return vista; }

    /** `accion` recibe la vista cada vez que cambia (setVista). */
    public void setAlCambiarVista(Consumer<Vista> accion) { this.alCambiarVista = accion; }

    /** Cambia la vista (botones de la barra, sesion guardada o doble clic en el grafo). */
    public void setVista(Vista nueva) {
        if (nueva == null || nueva == vista) return;
        // Sin optimizar y optimizado tienen las mismas lineas: al alternar se conserva la posicion.
        boolean mismaForma = vista.esListado() && nueva.esListado();
        vista = nueva;
        titulo.setText(vista.titulo);
        if (hayCodigo) pintarVista(mismaForma);
        else tarjetas.show(centro, "texto");
        alCambiarVista.accept(vista);
    }

    private void pintarVista(boolean conservarPosicion) {
        Point posicion = scroll.getViewport().getViewPosition();
        titulo.setText(vista.titulo);
        String contenido = textos.get(vista);
        tarjetas.show(centro, vista == Vista.GRAFO && contenido != null ? "grafo" : "texto");
        if (contenido == null) {
            String aviso = avisos.getOrDefault(vista, "El compilador no generó salida" + File.separator + vista.archivo + ".");
            subtitulo.setText(fuente.isBlank() ? " " : fuente);
            texto.setText(aviso);
            StyledDocument d = texto.getStyledDocument();
            d.setCharacterAttributes(0, d.getLength(), estComentario, true);
            texto.setCaretPosition(0);
            return;
        }
        String detalle = "salida" + File.separator + vista.archivo + (fuente.isBlank() ? "" : "  ·  " + fuente);
        if (vista == Vista.GRAFO) {
            grafo.cargar(contenido);
            subtitulo.setText(detalle + "  ·  " + grafo.cantidadBloques() + " bloque(s)");
            if (!conservarPosicion) SwingUtilities.invokeLater(() -> scrollGrafo.getViewport().setViewPosition(new Point(0, 0)));
            return;
        }
        subtitulo.setText(detalle);
        texto.setText(contenido);
        resaltar();
        if (conservarPosicion) {
            SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(posicion));
        } else {
            texto.setCaretPosition(0);
        }
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
        StyleConstants.setForeground(estBloque, tema.textoSalto);
        StyleConstants.setBackground(estBloque, tema.fondoFilaFun);
        StyleConstants.setBold(estBloque, true);
        grafo.aplicarTema(tema);
        scrollGrafo.getViewport().setBackground(tema.fondoDocumento);
        centro.setBackground(tema.fondoDocumento);
        if (hayCodigo && textos.get(vista) != null && vista != Vista.GRAFO) resaltar();
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
            } else if (t.matches("\\[B\\d+\\].*")) {
                int desde = linea.indexOf('[');
                d.setCharacterAttributes(inicio + desde, linea.length() - desde, estBloque, true);
            } else if (t.matches("L\\d+:.*")) {
                int desde = inicio + linea.indexOf(t);
                d.setCharacterAttributes(desde, t.indexOf(':') + 1, estEtiqueta, true);
                pintar(d, linea, inicio, t.indexOf(':') + 1 + linea.indexOf(t));
            } else if (t.startsWith("---") || t.startsWith("CÓDIGO") || t.startsWith("CODIGO") || t.startsWith("BLOQUES")
                    || t.startsWith("Archivo:") || t.startsWith("Bloques:")) {
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

    // ---------- navegacion de etiquetas y bloques ----------

    private void instalarNavegacion() {
        texto.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !hayCodigo || textos.get(vista) == null) return;
                String etiqueta = palabraEn(e.getPoint(), PATRON_ETIQUETA);
                if (etiqueta != null) {
                    irA(Pattern.compile("(?m)^[ \\t]*(" + etiqueta + ":)"), etiqueta + ":", " Etiqueta " + etiqueta);
                    return;
                }
                String bloque = vista == Vista.BLOQUES ? palabraEn(e.getPoint(), PATRON_BLOQUE) : null;
                if (bloque != null) irA(Pattern.compile("(?m)^[ \\t]*(\\[" + bloque + "\\])"), "[" + bloque + "]", " Bloque " + bloque);
            }
        });
    }

    /** Doble clic en una caja del grafo: abre la vista de bloques en ese bloque. */
    private void abrirBloque(int numero) {
        setVista(Vista.BLOQUES);
        irA(Pattern.compile("(?m)^[ \\t]*(\\[B" + numero + "\\])"), "[B" + numero + "]", " Bloque B" + numero);
    }

    /** Selecciona el grupo 1 de la primera coincidencia de `destino` y la hace visible. */
    private void irA(Pattern destino, String nombre, String estado) {
        Matcher m = destino.matcher(textoDocumento());
        if (!m.find()) { alEstado.accept(" No se encontró " + nombre); return; }
        int desde = m.start(1), hasta = m.end(1);
        SwingUtilities.invokeLater(() -> { // despues de la seleccion de palabra del propio doble clic
            texto.setCaretPosition(desde);
            texto.moveCaretPosition(hasta);
            try {
                java.awt.geom.Rectangle2D r = texto.modelToView2D(desde);
                if (r != null) texto.scrollRectToVisible(r.getBounds());
            } catch (BadLocationException ignorada) { }
        });
        alEstado.accept(estado);
    }

    /** La coincidencia de `patron` (L3, B3...) bajo el punto `p`, o null. */
    private String palabraEn(Point p, Pattern patron) {
        try {
            int offset = texto.viewToModel2D(p);
            if (offset < 0) return null;
            Element raiz = texto.getDocument().getDefaultRootElement();
            Element linea = raiz.getElement(raiz.getElementIndex(offset));
            int fin = Math.min(linea.getEndOffset(), texto.getDocument().getLength());
            String s = texto.getText(linea.getStartOffset(), fin - linea.getStartOffset());
            Matcher m = patron.matcher(s);
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
