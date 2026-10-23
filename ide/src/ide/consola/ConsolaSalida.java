package ide.consola;

import ide.Tema;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pestana "Salida": la consola del sistema (cmd.exe en Windows, /bin/sh en otros) corriendo dentro
 * del IDE, en la raiz del proyecto. Lo que se escribe despues del prompt se manda al shell con Enter;
 * la compilacion tambien se ejecuta en este mismo shell (ejecutar), asi que su comando y su salida
 * aparecen aqui como en una terminal. Ctrl+C sin seleccion detiene el programa en curso; Ctrl+L
 * (o "cls") limpia. Clic sobre una linea con "Linea N[, columna M]" lleva el editor a esa posicion.
 */
public class ConsolaSalida extends JPanel {
    /** "Linea N" o "Linea N, columna M" en un mensaje del compilador. */
    static final Pattern PATRON_POSICION = Pattern.compile("L[ií]nea (\\d+)(?:, columna (\\d+))?");
    /** Marca que se imprime al terminar un comando lanzado por el IDE; sus lineas no se muestran. */
    private static final String MARCA = "__JER_FIN__";
    private static final Pattern PATRON_MARCA = Pattern.compile(MARCA + "\\s+(-?\\d+)\\s*");
    /** Prompt de cmd ("C:\\ruta>") al inicio de una linea. */
    private static final Pattern PATRON_PROMPT = Pattern.compile("^([A-Za-z]:\\\\[^>]*>)");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String FIN_LINEA = WINDOWS ? "\r\n" : "\n";
    private static final int TIMEOUT_SEG = 30;

    /** Lo devuelve el detector de comandos para que el comando escrito no se mande al shell. */
    public static final AlTerminar NO_EJECUTAR = (salida, codigo, duracion, cancelado) -> { };

    /** Resultado de un comando lanzado con ejecutar(). */
    public interface AlTerminar {
        void accept(String salida, int codigoSalida, long duracionMs, boolean cancelado);
    }

    private final File directorio;
    private final BiConsumer<Integer, Integer> alIrA;
    private final JTextPane area = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
    };
    private final JScrollPane scroll = new JScrollPane(area);

    private Process shell;
    private Writer entradaShell;

    /** Offset donde termina la salida del shell y empieza lo que esta escribiendo el usuario. */
    private int finSalida = 0;
    /** Offset donde empieza la linea de salida que aun no termina (con '\n'). */
    private int inicioLinea = 0;
    private final StringBuilder lineaActual = new StringBuilder();
    /** Caracteres de lineaActual que ya estan en el documento. */
    private int mostrados = 0;
    /** La linea actual trae la marca: no se muestra. */
    private boolean lineaOculta = false;
    /** true mientras el propio IDE modifica el documento (el filtro deja pasar todo). */
    private boolean interno = false;

    private Captura captura;
    /**
     * Revisa cada comando que el usuario escribe a mano: si devuelve un AlTerminar, el comando se
     * captura igual que los del IDE (para refrescar errores, tablas y cuadruplos al terminar).
     */
    private Function<String, AlTerminar> detectorComandos;
    private final List<String> historial = new ArrayList<>();
    private int posHistorial = 0;

    private final SimpleAttributeSet estNormal = new SimpleAttributeSet();
    private final SimpleAttributeSet estPrompt = new SimpleAttributeSet();
    private final SimpleAttributeSet estError = new SimpleAttributeSet();
    private final SimpleAttributeSet estSugerencia = new SimpleAttributeSet();
    private final SimpleAttributeSet estExito = new SimpleAttributeSet();

    /** Un comando lanzado por el IDE cuya salida se esta juntando hasta ver la marca. */
    private static final class Captura {
        final StringBuilder salida = new StringBuilder();
        final long inicio = System.currentTimeMillis();
        final AlTerminar alTerminar;
        Timer timeout;
        boolean cancelado;
        Captura(AlTerminar alTerminar) { this.alTerminar = alTerminar; }
    }

    /**
     * `directorio` es la carpeta donde arranca el shell (la raiz del proyecto); `alIrA` recibe la
     * linea y la columna (1-based) a las que hay que llevar el editor.
     */
    public ConsolaSalida(File directorio, BiConsumer<Integer, Integer> alIrA) {
        super(new BorderLayout());
        this.directorio = directorio;
        this.alIrA = alIrA;
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setMargin(new Insets(4, 6, 4, 6));
        ((AbstractDocument) area.getDocument()).setDocumentFilter(new FiltroEntrada());
        instalarTeclas();
        instalarSaltoAError();
        add(scroll, BorderLayout.CENTER);
        aplicarTema(Tema.CLARO);
        SwingUtilities.invokeLater(() -> {
            try {
                iniciarShell();
            } catch (IOException e) {
                escribirLinea("No se pudo abrir la consola del sistema: " + e.getMessage(), estError);
            }
        });
    }

    // ---------- shell ----------

    private boolean shellVivo() { return shell != null && shell.isAlive(); }

    private void iniciarShell() throws IOException {
        List<String> comando = WINDOWS ? List.of("cmd.exe", "/K", "chcp 65001>nul") : List.of("/bin/sh", "-i");
        ProcessBuilder pb = new ProcessBuilder(comando).directory(directorio).redirectErrorStream(true);
        // Que "java" sea la misma JVM con la que corre el IDE.
        String binJava = System.getProperty("java.home") + File.separator + "bin";
        // En Windows la variable suele llamarse "Path" y este mapa distingue mayusculas: se busca
        // la clave real para no crear un "PATH" aparte que deje al shell sin System32.
        java.util.Map<String, String> env = pb.environment();
        String clave = "PATH";
        for (String k : env.keySet()) {
            if (k.equalsIgnoreCase("PATH")) { clave = k; break; }
        }
        String path = env.getOrDefault(clave, "");
        env.put(clave, path.isEmpty() ? binJava : binJava + File.pathSeparator + path);
        Process p = pb.start();
        shell = p;
        entradaShell = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
        Thread lector = new Thread(() -> {
            char[] buf = new char[4096];
            try (Reader in = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    String trozo = new String(buf, 0, n);
                    SwingUtilities.invokeLater(() -> recibir(trozo));
                }
            } catch (IOException ignorada) { }
            SwingUtilities.invokeLater(this::shellTerminado);
        }, "lector-consola");
        lector.setDaemon(true);
        lector.start();
    }

    /** El shell termino ("exit" o se cerro): se reabre con el siguiente comando. */
    private void shellTerminado() {
        if (captura != null) terminarCaptura(-1);
        if (lineaActual.length() > 0) completarLinea();
    }

    private void enviar(String texto) throws IOException {
        if (!shellVivo()) iniciarShell();
        entradaShell.write(texto + FIN_LINEA);
        entradaShell.flush();
    }

    /**
     * Ejecuta `comando` en la consola, como si se hubiera escrito, y llama a `alTerminar` con su
     * salida cuando acaba. Si pasa de 30 s se detiene el programa. Lanza IOException si no se pudo
     * abrir el shell, e IllegalStateException si ya hay un comando del IDE en curso.
     */
    public void ejecutar(String comando, AlTerminar alTerminar) throws IOException {
        ejecutar(comando, TIMEOUT_SEG, alTerminar);
    }

    /** Como ejecutar(comando, alTerminar), pero con un tiempo limite de `timeoutSeg` segundos. */
    public void ejecutar(String comando, int timeoutSeg, AlTerminar alTerminar) throws IOException {
        if (captura != null) throw new IllegalStateException("ya hay una compilación en curso");
        if (!WINDOWS) escribirEntrada(comando); // sh sin terminal no repite el comando
        capturar(comando, timeoutSeg, alTerminar);
    }

    /** Ver detectorComandos. */
    public void setDetectorComandos(Function<String, AlTerminar> detector) { this.detectorComandos = detector; }

    /** Manda `comando` al shell seguido de la marca y junta su salida hasta verla. */
    private void capturar(String comando, int timeoutSeg, AlTerminar alTerminar) throws IOException {
        Captura c = new Captura(alTerminar);
        captura = c;
        try {
            enviar(comando);
            enviar("echo " + MARCA + " " + (WINDOWS ? "%errorlevel%" : "$?"));
        } catch (IOException e) {
            captura = null;
            throw e;
        }
        c.timeout = new Timer(timeoutSeg * 1000, e -> { c.cancelado = true; detenerPrograma(); });
        c.timeout.setRepeats(false);
        c.timeout.start();
    }

    /** Detiene lo que este corriendo dentro del shell (equivale a Ctrl+C). */
    public void detenerPrograma() {
        if (shellVivo()) shell.descendants().forEach(ProcessHandle::destroyForcibly);
    }

    /** Cierra el shell y lo que este corriendo (al salir del IDE). */
    public void cerrar() {
        if (shell != null) {
            shell.descendants().forEach(ProcessHandle::destroyForcibly);
            shell.destroyForcibly();
        }
    }

    private void terminarCaptura(int codigo) {
        Captura c = captura;
        captura = null;
        if (c.timeout != null) c.timeout.stop();
        c.alTerminar.accept(c.salida.toString(), codigo, System.currentTimeMillis() - c.inicio, c.cancelado);
    }

    // ---------- salida del shell ----------

    /** Un trozo de salida del shell (en el hilo de Swing). */
    private void recibir(String trozo) {
        for (int i = 0; i < trozo.length(); i++) {
            char ch = trozo.charAt(i);
            if (ch == '\r') continue;
            if (ch == '\f') { limpiar(); continue; } // "cls" de cmd
            if (ch == '\n') completarLinea();
            else lineaActual.append(ch);
        }
        mostrarPendiente();
    }

    /** Pasa al documento lo que falta de la linea actual (si no trae la marca) y la colorea. */
    private void mostrarPendiente() {
        if (lineaOculta) return;
        if (lineaActual.indexOf(MARCA) >= 0) { // se quita lo que ya se mostro de esta linea
            quitar(inicioLinea, finSalida - inicioLinea);
            mostrados = 0;
            lineaOculta = true;
            return;
        }
        if (mostrados < lineaActual.length()) {
            insertarSalida(lineaActual.substring(mostrados), estNormal);
            mostrados = lineaActual.length();
        }
        colorear(inicioLinea, lineaActual.toString());
    }

    private void completarLinea() {
        String linea = lineaActual.toString();
        mostrarPendiente();
        if (lineaOculta) {
            Matcher m = PATRON_MARCA.matcher(linea);
            if (m.find() && captura != null) terminarCaptura(Integer.parseInt(m.group(1)));
        } else {
            insertarSalida("\n", estNormal);
            if (captura != null) captura.salida.append(linea).append('\n');
        }
        lineaActual.setLength(0);
        mostrados = 0;
        lineaOculta = false;
        inicioLinea = finSalida;
    }

    /** Colorea la linea de salida que empieza en `inicio` segun su contenido. */
    private void colorear(int inicio, String linea) {
        StyledDocument d = area.getStyledDocument();
        AttributeSet est = estNormal;
        if (linea.matches("Errores: 0\\s*")) est = estExito;
        else if (linea.startsWith("[ERROR") || linea.matches("Errores: \\d+\\s*")) est = estError;
        else if (linea.startsWith("   ->")) est = estSugerencia;
        interno = true;
        try {
            d.setCharacterAttributes(inicio, linea.length(), est, true);
            Matcher m = PATRON_PROMPT.matcher(linea);
            if (m.find()) d.setCharacterAttributes(inicio, m.end(1), estPrompt, true);
        } finally {
            interno = false;
        }
    }

    private void insertarSalida(String texto, AttributeSet estilo) {
        boolean alFinal = estaAlFinal();
        interno = true;
        try {
            area.getDocument().insertString(finSalida, texto, estilo);
            finSalida += texto.length();
        } catch (BadLocationException ignorada) {
        } finally {
            interno = false;
        }
        if (alFinal) SwingUtilities.invokeLater(() -> area.setCaretPosition(area.getDocument().getLength()));
    }

    private void quitar(int desde, int largo) {
        if (largo <= 0) return;
        interno = true;
        try {
            area.getDocument().remove(desde, largo);
            finSalida -= largo;
        } catch (BadLocationException ignorada) {
        } finally {
            interno = false;
        }
    }

    /** Una linea completa escrita por el IDE (error al abrir el shell, de guardado...). */
    private void escribirLinea(String texto, AttributeSet estilo) {
        if (lineaActual.length() > 0 && !lineaOculta) insertarSalida("\n", estNormal);
        insertarSalida(texto + "\n", estilo);
        inicioLinea = finSalida;
        lineaActual.setLength(0);
        mostrados = 0;
        lineaOculta = false;
    }

    /** Muestra como entrada un comando que el shell no repite (sh sin terminal). */
    private void escribirEntrada(String comando) {
        insertarSalida(comando + "\n", estNormal);
        inicioLinea = finSalida;
        lineaActual.setLength(0);
        mostrados = 0;
    }

    /** Un error del IDE en rojo. */
    public void error(String texto) { escribirLinea(texto, estError); }

    /**
     * Equivale a escribir "cls": el shell limpia la pantalla y vuelve a mostrar su prompt. Si hay un
     * comando en curso (o no es Windows), solo se vacia la consola.
     */
    public void cls() {
        if (WINDOWS && shellVivo() && captura == null) {
            try {
                enviar("cls");
                return;
            } catch (IOException ignorada) { }
        }
        limpiar();
    }

    /** Vacia la consola, conservando lo que el usuario estaba escribiendo. */
    public void limpiar() {
        String entrada = textoEntrada();
        interno = true;
        try {
            Document d = area.getDocument();
            d.remove(0, d.getLength());
            d.insertString(0, entrada, estNormal);
        } catch (BadLocationException ignorada) {
        } finally {
            interno = false;
        }
        finSalida = 0;
        inicioLinea = 0;
        mostrados = 0;
        lineaActual.setLength(0);
        lineaOculta = false;
    }

    private boolean estaAlFinal() {
        JScrollBar barra = scroll.getVerticalScrollBar();
        return barra.getValue() + barra.getVisibleAmount() >= barra.getMaximum() - 20;
    }

    // ---------- entrada del usuario ----------

    private String textoEntrada() {
        Document d = area.getDocument();
        try {
            return d.getText(finSalida, d.getLength() - finSalida);
        } catch (BadLocationException e) {
            return "";
        }
    }

    private void reemplazarEntrada(String texto) {
        interno = true;
        try {
            Document d = area.getDocument();
            d.remove(finSalida, d.getLength() - finSalida);
            d.insertString(finSalida, texto, estNormal);
        } catch (BadLocationException ignorada) {
        } finally {
            interno = false;
        }
        area.setCaretPosition(area.getDocument().getLength());
    }

    private void enter() {
        String comando = textoEntrada();
        reemplazarEntrada(""); // el shell repite el comando junto a su prompt
        if (!comando.isBlank()) {
            historial.remove(comando);
            historial.add(comando);
        }
        posHistorial = historial.size();
        AlTerminar alTerminar = captura == null && detectorComandos != null ? detectorComandos.apply(comando) : null;
        if (alTerminar == NO_EJECUTAR) { // se cancelo: el comando vuelve a la linea del prompt
            reemplazarEntrada(comando);
            return;
        }
        if (!WINDOWS) escribirEntrada(comando);
        try {
            if (alTerminar != null) capturar(comando, TIMEOUT_SEG, alTerminar);
            else enviar(comando);
        } catch (IOException e) {
            escribirLinea("No se pudo enviar el comando a la consola: " + e.getMessage(), estError);
        }
    }

    private void navegarHistorial(int delta) {
        if (historial.isEmpty()) return;
        posHistorial = Math.max(0, Math.min(historial.size(), posHistorial + delta));
        reemplazarEntrada(posHistorial < historial.size() ? historial.get(posHistorial) : "");
    }

    private void instalarTeclas() {
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        InputMap im = area.getInputMap();
        ActionMap am = area.getActionMap();
        accion(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "consolaEnter", this::enter);
        accion(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "consolaArriba", () -> navegarHistorial(-1));
        accion(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "consolaAbajo", () -> navegarHistorial(1));
        accion(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_L, ctrl), "consolaLimpiar", this::cls);
        accion(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_C, ctrl), "consolaCtrlC", () -> {
            if (area.getSelectionStart() != area.getSelectionEnd()) area.copy();
            else detenerPrograma();
        });
        accion(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "consolaInicio",
                () -> area.setCaretPosition(Math.min(finSalida, area.getDocument().getLength())));
    }

    private static void accion(InputMap im, ActionMap am, KeyStroke tecla, String nombre, Runnable r) {
        im.put(tecla, nombre);
        am.put(nombre, new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        });
    }

    /** Solo se puede editar despues de la salida (la linea del prompt); lo demas es de solo lectura. */
    private class FiltroEntrada extends DocumentFilter {
        @Override public void insertString(FilterBypass fb, int offset, String texto, AttributeSet a) throws BadLocationException {
            if (interno) { super.insertString(fb, offset, texto, a); return; }
            int destino = Math.max(offset, finSalida);
            super.insertString(fb, destino, texto.replace("\r", "").replace("\n", " "), estNormal);
            if (destino != offset) SwingUtilities.invokeLater(() -> area.setCaretPosition(area.getDocument().getLength()));
        }

        @Override public void remove(FilterBypass fb, int offset, int largo) throws BadLocationException {
            if (interno) { super.remove(fb, offset, largo); return; }
            int desde = Math.max(offset, finSalida);
            int hasta = offset + largo;
            if (hasta > desde) super.remove(fb, desde, hasta - desde);
        }

        @Override public void replace(FilterBypass fb, int offset, int largo, String texto, AttributeSet a) throws BadLocationException {
            if (interno) { super.replace(fb, offset, largo, texto, a); return; }
            if (offset < finSalida) { // seleccion sobre la salida: se escribe al final
                insertString(fb, fb.getDocument().getLength(), texto == null ? "" : texto, a);
                return;
            }
            super.replace(fb, offset, largo, texto == null ? "" : texto.replace("\r", "").replace("\n", " "), estNormal);
        }
    }

    // ---------- tema ----------

    public void aplicarTema(Tema tema) {
        setBackground(tema.fondoConsola);
        area.setBackground(tema.fondoConsola);
        area.setCaretColor(tema.textoConsola);
        scroll.getViewport().setBackground(tema.fondoConsola);
        StyleConstants.setForeground(estNormal, tema.textoConsola);
        StyleConstants.setForeground(estPrompt, tema.consolaPrompt);
        StyleConstants.setBold(estPrompt, true);
        StyleConstants.setForeground(estError, tema.consolaError);
        StyleConstants.setForeground(estSugerencia, tema.consolaSugerencia);
        StyleConstants.setForeground(estExito, tema.consolaExito);
        StyleConstants.setBold(estExito, true);
        recolorear();
    }

    /** Reaplica los colores a todo el texto (al cambiar de tema). */
    private void recolorear() {
        Document d = area.getDocument();
        Element raiz = d.getDefaultRootElement();
        for (int i = 0; i < raiz.getElementCount(); i++) {
            Element e = raiz.getElement(i);
            int fin = Math.min(e.getEndOffset(), d.getLength());
            try {
                String linea = d.getText(e.getStartOffset(), fin - e.getStartOffset()).replace("\n", "");
                colorear(e.getStartOffset(), linea);
            } catch (BadLocationException ignorada) { }
        }
    }

    // ---------- salto a errores ----------

    private void instalarSaltoAError() {
        area.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int[] posicion = posicionEn(area, e.getPoint());
                if (posicion != null) alIrA.accept(posicion[0], posicion[1]);
            }
        });
        area.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                area.setCursor(Cursor.getPredefinedCursor(posicionEn(area, e.getPoint()) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        });
    }

    /** {linea, columna} del mensaje bajo el punto (columna 1 si el mensaje no la trae), o null. */
    static int[] posicionEn(JTextComponent area, Point p) {
        try {
            int offset = area.viewToModel2D(p);
            if (offset < 0) return null;
            Element raiz = area.getDocument().getDefaultRootElement();
            Element linea = raiz.getElement(raiz.getElementIndex(offset));
            int fin = Math.min(linea.getEndOffset(), area.getDocument().getLength());
            String texto = area.getText(linea.getStartOffset(), fin - linea.getStartOffset());
            Matcher m = PATRON_POSICION.matcher(texto);
            if (!m.find()) return null;
            return new int[] { Integer.parseInt(m.group(1)), m.group(2) == null ? 1 : Integer.parseInt(m.group(2)) };
        } catch (BadLocationException | NumberFormatException e) {
            return null;
        }
    }
}
