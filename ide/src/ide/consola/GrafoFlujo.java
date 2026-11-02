package ide.consola;

import ide.Tema;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El grafo de flujo de control dibujado a partir de bloques_basicos.txt: cada funcion es una columna
 * de cajas (sus bloques basicos, en el orden del programa) y cada arista va de un bloque a uno de
 * sus sucesores. La arista al bloque siguiente es una flecha recta hacia abajo; un salto hacia
 * adelante se dibuja por la derecha y un salto hacia atras (un ciclo) por la izquierda, cada uno
 * en su propio carril para que no se encimen. En los saltos condicionales, V y F marcan que arista
 * se toma cuando la condicion es verdadera o falsa.
 * Ctrl+rueda acerca o aleja; doble clic sobre un bloque lo avisa a `alElegirBloque`.
 */
public class GrafoFlujo extends JComponent {

    private static final Pattern PATRON_ENCABEZADO = Pattern.compile("^\\s*\\[B(\\d+)\\]\\s*->\\s*(.*)$");
    private static final Pattern PATRON_ETIQUETA = Pattern.compile("^\\s*(L\\d+):\\s*(.*)$");
    private static final Pattern PATRON_NUMERO = Pattern.compile("B(\\d+)");

    private static final int MARGEN = 16, SEPARACION = 30, ENTRE_FUNCIONES = 34, CARRIL = 12, SALIDA_CARRIL = 18;
    private static final int RELLENO = 8, ANCHO_MINIMO = 130;

    /** Un bloque basico leido del archivo, con su caja ya calculada. */
    private static final class Bloque {
        final int numero;
        final String funcion;
        final List<String> lineas = new ArrayList<>();
        final List<Integer> sucesores = new ArrayList<>();
        String etiquetas = "";
        Rectangle caja;
        Bloque(int numero, String funcion) { this.numero = numero; this.funcion = funcion; }

        String ultima() { return lineas.isEmpty() ? "" : lineas.get(lineas.size() - 1); }
    }

    /** Una arista ya enrutada: la polilinea y, si es de un salto condicional, su V/F. */
    private static final class Arista {
        final int[] xs, ys;
        final String rotulo;
        final int tipo; // 0 siguiente, 1 salto adelante, 2 salto atras
        Arista(int[] xs, int[] ys, String rotulo, int tipo) { this.xs = xs; this.ys = ys; this.rotulo = rotulo; this.tipo = tipo; }
    }

    private final List<Bloque> bloques = new ArrayList<>();
    private final List<Arista> aristas = new ArrayList<>();
    /** Titulo de cada funcion y su posicion (x, y de la linea base). */
    private final List<Object[]> titulos = new ArrayList<>();
    private final Font fuente = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private final Font fuenteNegrita = fuente.deriveFont(Font.BOLD);
    private final IntConsumer alElegirBloque;
    private Dimension tamanoBase = new Dimension(0, 0);
    private double zoom = 1.0;
    private Tema tema = Tema.CLARO;

    public GrafoFlujo(IntConsumer alElegirBloque) {
        this.alElegirBloque = alElegirBloque;
        setOpaque(true);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                Bloque b = bloqueEn(e.getPoint());
                if (b != null) alElegirBloque.accept(b.numero);
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                Bloque b = bloqueEn(e.getPoint());
                setCursor(Cursor.getPredefinedCursor(b != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                setToolTipText(b == null ? null : "B" + b.numero + " — doble clic para verlo en Bloques básicos");
            }
        });
        addMouseWheelListener(this::rueda);
    }

    public int cantidadBloques() { return bloques.size(); }

    public void aplicarTema(Tema tema) {
        this.tema = tema;
        setBackground(tema.fondoDocumento);
        repaint();
    }

    // ---------- lectura ----------

    /** Lee el texto de bloques_basicos.txt y recalcula el dibujo. */
    public void cargar(String texto) {
        bloques.clear();
        String funcion = null;
        Bloque actual = null;
        for (String linea : texto.split("\n")) {
            String t = linea.trim();
            if (t.startsWith("---")) break;
            if (t.startsWith("FUN ")) { funcion = t.substring(4).trim(); actual = null; continue; }
            Matcher m = PATRON_ENCABEZADO.matcher(linea);
            if (m.matches()) {
                actual = new Bloque(Integer.parseInt(m.group(1)), funcion);
                Matcher n = PATRON_NUMERO.matcher(m.group(2));
                while (n.find()) actual.sucesores.add(Integer.parseInt(n.group(1)));
                bloques.add(actual);
                continue;
            }
            if (actual == null || t.isEmpty()) continue;
            Matcher e = PATRON_ETIQUETA.matcher(linea);
            if (e.matches()) {
                actual.etiquetas += (actual.etiquetas.isEmpty() ? "" : " ") + e.group(1);
                if (!e.group(2).isBlank()) actual.lineas.add(e.group(2).trim());
            } else {
                actual.lineas.add(t);
            }
        }
        calcular();
        revalidate();
        repaint();
    }

    // ---------- distribucion ----------

    private void calcular() {
        aristas.clear();
        titulos.clear();
        FontMetrics fm = getFontMetrics(fuente);
        int alto = fm.getHeight();
        int y = MARGEN, anchoTotal = 0;
        int i = 0;
        while (i < bloques.size()) {
            // Los bloques de una misma funcion son consecutivos.
            int fin = i;
            while (fin < bloques.size() && java.util.Objects.equals(bloques.get(fin).funcion, bloques.get(i).funcion)) fin++;
            List<Bloque> grupo = bloques.subList(i, fin);

            int ancho = ANCHO_MINIMO;
            for (Bloque b : grupo) {
                ancho = Math.max(ancho, getFontMetrics(fuenteNegrita).stringWidth(encabezado(b)) + 2 * RELLENO + 40);
                for (String l : b.lineas) ancho = Math.max(ancho, fm.stringWidth(l) + 2 * RELLENO);
            }

            // Carriles: un salto ocupa el tramo vertical entre su origen y su destino.
            List<int[]> saltos = new ArrayList<>(); // {origen, destino, lado(0 izq,1 der), carril}
            for (int k = 0; k < grupo.size(); k++) {
                for (int s : grupo.get(k).sucesores) {
                    int d = s - grupo.get(0).numero;
                    if (d < 0 || d >= grupo.size() || d == k + 1) continue;
                    saltos.add(new int[] { k, d, d <= k ? 0 : 1, -1 });
                }
            }
            saltos.sort((a, b) -> Integer.compare(Math.abs(a[1] - a[0]), Math.abs(b[1] - b[0])));
            int[] carriles = new int[2];
            List<List<int[]>> ocupados = new ArrayList<>();
            ocupados.add(new ArrayList<>()); ocupados.add(new ArrayList<>());
            for (int[] s : saltos) {
                int lado = s[2], lo = Math.min(s[0], s[1]), hi = Math.max(s[0], s[1]), c = 0;
                while (true) {
                    boolean libre = true;
                    for (int[] o : ocupados.get(lado)) {
                        if (o[2] == c && lo <= o[1] && o[0] <= hi) { libre = false; break; }
                    }
                    if (libre) break;
                    c++;
                }
                s[3] = c;
                ocupados.get(lado).add(new int[] { lo, hi, c });
                carriles[lado] = Math.max(carriles[lado], c + 1);
            }

            int x0 = MARGEN + (carriles[0] > 0 ? SALIDA_CARRIL + carriles[0] * CARRIL : 0);
            titulos.add(new Object[] { "FUN " + grupo.get(0).funcion, MARGEN, y + alto });
            y += alto + 10;
            for (Bloque b : grupo) {
                int h = alto + 6 + Math.max(1, b.lineas.size()) * alto + RELLENO;
                b.caja = new Rectangle(x0, y, ancho, h);
                y += h + SEPARACION;
            }
            y += ENTRE_FUNCIONES - SEPARACION;
            anchoTotal = Math.max(anchoTotal, x0 + ancho + (carriles[1] > 0 ? SALIDA_CARRIL + carriles[1] * CARRIL : 0) + MARGEN);

            // Aristas
            for (int k = 0; k < grupo.size(); k++) {
                Bloque b = grupo.get(k);
                String ultima = b.ultima();
                boolean condicional = ultima.startsWith("SALTO_VER") || ultima.startsWith("SALTO_FAL");
                String alSaltar = ultima.startsWith("SALTO_VER") ? "V" : "F";
                String alSeguir = ultima.startsWith("SALTO_VER") ? "F" : "V";
                if (k + 1 < grupo.size() && b.sucesores.contains(grupo.get(k + 1).numero)) {
                    Rectangle o = b.caja, d = grupo.get(k + 1).caja;
                    int cx = o.x + o.width / 2;
                    // El salto condicional al bloque siguiente (raro) y la caida coinciden: una sola flecha.
                    boolean saltaAlSiguiente = condicional && ultima.matches(".*\\b" + grupo.get(k + 1).etiquetas.split(" ")[0] + "\\b.*")
                            && !grupo.get(k + 1).etiquetas.isEmpty();
                    aristas.add(new Arista(new int[] { cx, cx }, new int[] { o.y + o.height, d.y },
                            condicional && !saltaAlSiguiente ? alSeguir : null, 0));
                }
                for (int[] s : saltos) {
                    if (s[0] != k) continue;
                    Rectangle o = b.caja, d = grupo.get(s[1]).caja;
                    int ys = o.y + o.height - RELLENO - alto / 2, yd = d.y + (alto + 6) / 2;
                    String rotulo = condicional ? alSaltar : null;
                    if (s[2] == 1) {
                        int xc = o.x + o.width + SALIDA_CARRIL + s[3] * CARRIL;
                        aristas.add(new Arista(new int[] { o.x + o.width, xc, xc, d.x + d.width },
                                new int[] { ys, ys, yd, yd }, rotulo, 1));
                    } else {
                        int xc = o.x - SALIDA_CARRIL - s[3] * CARRIL;
                        aristas.add(new Arista(new int[] { o.x, xc, xc, d.x }, new int[] { ys, ys, yd, yd }, rotulo, 2));
                    }
                }
            }
            i = fin;
        }
        tamanoBase = new Dimension(Math.max(anchoTotal, 200), y + MARGEN);
    }

    private static String encabezado(Bloque b) {
        return "B" + b.numero + (b.etiquetas.isEmpty() ? "" : "  " + b.etiquetas);
    }

    // ---------- zoom ----------

    @Override public Dimension getPreferredSize() {
        return new Dimension((int) Math.ceil(tamanoBase.width * zoom), (int) Math.ceil(tamanoBase.height * zoom));
    }

    private void rueda(MouseWheelEvent e) {
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        if ((e.getModifiersEx() & ctrl) == 0) { // sin Ctrl: desplazamiento normal
            getParent().dispatchEvent(SwingUtilities.convertMouseEvent(this, e, getParent()));
            return;
        }
        double anterior = zoom;
        zoom = Math.max(0.4, Math.min(2.5, zoom * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1)));
        if (zoom == anterior) return;
        // Mantener bajo el cursor el mismo punto del dibujo.
        JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        Point enVista = SwingUtilities.convertPoint(this, e.getPoint(), vp);
        double fx = e.getX() / anterior, fy = e.getY() / anterior;
        revalidate();
        if (vp != null) {
            vp.validate();
            int nx = (int) (fx * zoom) - enVista.x, ny = (int) (fy * zoom) - enVista.y;
            Dimension tam = getPreferredSize(), ext = vp.getExtentSize();
            vp.setViewPosition(new Point(Math.max(0, Math.min(nx, tam.width - ext.width)), Math.max(0, Math.min(ny, tam.height - ext.height))));
        }
        repaint();
    }

    private Bloque bloqueEn(Point p) {
        int x = (int) (p.x / zoom), y = (int) (p.y / zoom);
        for (Bloque b : bloques) if (b.caja != null && b.caja.contains(x, y)) return b;
        return null;
    }

    // ---------- dibujo ----------

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(zoom, zoom);
        FontMetrics fm = g.getFontMetrics(fuente);
        int alto = fm.getHeight();

        g.setFont(fuenteNegrita);
        g.setColor(tema.sintaxisPalabraClave);
        for (Object[] t : titulos) g.drawString((String) t[0], (int) t[1], (int) t[2]);

        for (Arista a : aristas) dibujarArista(g, a);

        for (Bloque b : bloques) {
            Rectangle r = b.caja;
            int altoEnc = alto + 6;
            g.setColor(tema.fondoTabla);
            g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
            g.setColor(tema.fondoFilaFun);
            g.setClip(r.x, r.y, r.width + 1, altoEnc);
            g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
            g.setClip(null);
            g.setColor(tema.rejillaTabla);
            g.drawLine(r.x, r.y + altoEnc, r.x + r.width, r.y + altoEnc);
            boolean esFin = b.sucesores.isEmpty();
            g.setStroke(new BasicStroke(esFin ? 2f : 1f));
            g.setColor(esFin ? tema.textoSalto : tema.rejillaTabla);
            g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
            g.setStroke(new BasicStroke(1f));

            int base = r.y + (altoEnc + fm.getAscent() - fm.getDescent()) / 2;
            g.setFont(fuenteNegrita);
            g.setColor(tema.textoSalto);
            g.drawString("B" + b.numero, r.x + RELLENO, base);
            if (!b.etiquetas.isEmpty()) {
                g.setColor(tema.textoFilaEtiq);
                g.drawString(b.etiquetas, r.x + RELLENO + g.getFontMetrics().stringWidth("B" + b.numero + "  "), base);
            }
            if (esFin) {
                g.setFont(fuente);
                g.setColor(tema.sintaxisComentario);
                String fin = "fin";
                g.drawString(fin, r.x + r.width - RELLENO - fm.stringWidth(fin), base);
            }

            g.setFont(fuente);
            int yl = r.y + altoEnc + fm.getAscent() + 2;
            if (b.lineas.isEmpty()) {
                g.setColor(tema.sintaxisComentario);
                g.drawString("(vacío)", r.x + RELLENO, yl);
            }
            for (String l : b.lineas) {
                g.setColor(l.startsWith("SALTO") || l.startsWith("RET") ? tema.textoSalto : tema.textoDocumento);
                g.drawString(l, r.x + RELLENO, yl);
                yl += alto;
            }
        }
        g.dispose();
    }

    private void dibujarArista(Graphics2D g, Arista a) {
        Color c = a.tipo == 0 ? tema.textoNumeros : a.tipo == 1 ? tema.textoSalto : tema.textoFilaEtiq;
        g.setColor(c);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Double();
        p.moveTo(a.xs[0], a.ys[0]);
        for (int k = 1; k < a.xs.length; k++) p.lineTo(a.xs[k], a.ys[k]);
        g.draw(p);
        int n = a.xs.length;
        flecha(g, a.xs[n - 2], a.ys[n - 2], a.xs[n - 1], a.ys[n - 1]);
        if (a.rotulo != null) {
            g.setFont(fuenteNegrita.deriveFont(11f));
            g.setColor(a.rotulo.equals("V") ? tema.sintaxisCadena : tema.consolaError);
            int x = a.tipo == 0 ? a.xs[0] + 5 : (a.tipo == 1 ? a.xs[0] + 4 : a.xs[0] - 12);
            int y = a.tipo == 0 ? a.ys[0] + 13 : a.ys[0] - 4;
            g.drawString(a.rotulo, x, y);
        }
        g.setStroke(new BasicStroke(1f));
    }

    private static void flecha(Graphics2D g, int x1, int y1, int x2, int y2) {
        double ang = Math.atan2(y2 - y1, x2 - x1);
        AffineTransform previa = g.getTransform();
        g.translate(x2, y2);
        g.rotate(ang);
        g.fill(new Polygon(new int[] { 0, -9, -9 }, new int[] { 0, -4, 4 }, 3));
        g.setTransform(previa);
    }
}
