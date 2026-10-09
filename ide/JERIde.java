import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.tree.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** IDE minimo para JER: arbol de proyecto, pestanas multi-archivo con resaltado de sintaxis y compilacion. */
public class JERIde extends JFrame {
    private static final int TIMEOUT_COMPILACION_SEG = 30;
    /** "[ERROR ...] Linea N" en la salida del compilador. */
    private static final Pattern PATRON_ERROR = Pattern.compile("^\\[ERROR[^\\]]*\\]\\s*Linea (\\d+)");
    private static final Pattern PATRON_LINEA = Pattern.compile("Linea (\\d+)");

    private final Preferences prefs = Preferences.userNodeForPackage(JERIde.class);
    private final JTabbedPane pestanas = new JTabbedPane();
    private final JTextArea consola = new JTextArea();
    private final JTextArea consolaErrores = new JTextArea();
    private final JTabbedPane pestanasConsola = new JTabbedPane();
    private final JFileChooser chooser = new JFileChooser();
    private final FileNameExtensionFilter filtroAbrir = new FileNameExtensionFilter("Archivos JER (*.jer, *.txt)", "jer", "txt");
    private final File raizProyecto = new File(".").getAbsoluteFile();
    private final File carpetaPruebas = new File(raizProyecto, "pruebas");
    private final JTree arbol = construirArbolProyecto();
    private final JScrollPane scrollArbol = new JScrollPane(arbol);
    private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JPanel barraEstado = new JPanel(new BorderLayout());
    private final JLabel etiquetaEstado = new JLabel(" Listo");
    private final JLabel etiquetaPosicion = new JLabel("");
    private final JButton btnCompilar = new JButton("Compilar");
    private final JToggleButton btnModoOscuro = new JToggleButton("Modo oscuro");
    private JDialog dialogoBuscar;
    private final JTextField campoBuscar = new JTextField(18);
    private final JTextField campoReemplazar = new JTextField(18);
    private boolean modoOscuro = false;
    private final Map<EditorTab, JLabel> etiquetasPestana = new IdentityHashMap<>();

    public JERIde() {
        super("JER IDE");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1100, 750);
        setIconImage(crearIcono());

        carpetaPruebas.mkdirs();
        chooser.setFileFilter(filtroAbrir);

        for (JTextArea area : new JTextArea[] {consola, consolaErrores}) {
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            area.setEditable(false);
            area.setBackground(Color.BLACK);
            area.setForeground(Color.GREEN);
            instalarSaltoAError(area);
        }
        pestanasConsola.addTab("Salida", new JScrollPane(consola));
        pestanasConsola.addTab("Errores", new JScrollPane(consolaErrores));

        arbol.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) abrirDesdeArbol(arbol);
            }
        });
        arbol.expandRow(0);

        JButton btnNuevo = new JButton("Nuevo");
        JButton btnAbrir = new JButton("Abrir");
        JButton btnGuardar = new JButton("Guardar");
        JButton btnGuardarComo = new JButton("Guardar como");
        JButton btnBuscar = new JButton("Buscar");
        JButton btnLimpiar = new JButton("Limpiar consola");
        btnNuevo.addActionListener(e -> conRedDeSeguridad(() -> nuevaPestana(null)));
        btnAbrir.addActionListener(e -> conRedDeSeguridad(this::abrir));
        btnGuardar.addActionListener(e -> conRedDeSeguridad(this::guardar));
        btnGuardarComo.addActionListener(e -> conRedDeSeguridad(this::guardarComo));
        btnBuscar.addActionListener(e -> conRedDeSeguridad(this::mostrarBuscar));
        btnCompilar.addActionListener(e -> conRedDeSeguridad(this::compilar));
        btnLimpiar.addActionListener(e -> limpiarConsola());
        btnModoOscuro.addActionListener(e -> conRedDeSeguridad(() -> alternarTema(btnModoOscuro.isSelected())));

        for (JComponent c : new JComponent[] {btnNuevo, btnAbrir, btnGuardar, btnGuardarComo, btnBuscar,
                btnCompilar, btnLimpiar, btnModoOscuro}) {
            toolbar.add(c);
        }

        etiquetaPosicion.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        barraEstado.add(etiquetaEstado, BorderLayout.WEST);
        barraEstado.add(etiquetaPosicion, BorderLayout.EAST);

        JSplitPane splitVertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pestanas, pestanasConsola);
        splitVertical.setResizeWeight(0.7);

        JSplitPane splitHorizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollArbol, splitVertical);
        splitHorizontal.setDividerLocation(220);

        setLayout(new BorderLayout());
        add(toolbar, BorderLayout.NORTH);
        add(splitHorizontal, BorderLayout.CENTER);
        add(barraEstado, BorderLayout.SOUTH);

        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        atajo(KeyEvent.VK_S, ctrl, "guardarAtajo", this::guardar);
        atajo(KeyEvent.VK_S, ctrl | KeyEvent.SHIFT_DOWN_MASK, "guardarComoAtajo", this::guardarComo);
        atajo(KeyEvent.VK_F, ctrl, "buscarAtajo", this::mostrarBuscar);
        atajo(KeyEvent.VK_H, ctrl, "reemplazarAtajo", this::mostrarBuscar);

        pestanas.addChangeListener(e -> actualizarPosicion());

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { salir(); }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) { refrescarArbol(); }
        });

        restaurarSesion();
    }

    private static Image crearIcono() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x2A6DB8));
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString("J", 10, 24);
        g.dispose();
        return img;
    }

    private void atajo(int tecla, int mascara, String nombre, Runnable accion) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(tecla, mascara), nombre);
        getRootPane().getActionMap().put(nombre, new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { conRedDeSeguridad(accion); }
        });
    }

    /** Corre una accion de la UI atrapando cualquier excepcion para mostrarla en la consola en vez de que desaparezca en silencio. */
    private void conRedDeSeguridad(Runnable accion) {
        try {
            accion.run();
        } catch (Exception ex) {
            consola.setText("Error inesperado: " + ex);
            pestanasConsola.setSelectedIndex(0);
        }
    }

    // ---------- sesion (tema + archivos abiertos) ----------

    private void restaurarSesion() {
        if (prefs.getBoolean("oscuro", false)) {
            btnModoOscuro.setSelected(true);
            alternarTema(true);
        }
        for (String ruta : prefs.get("abiertos", "").split("\\|")) {
            File f = new File(ruta);
            if (!ruta.isEmpty() && f.isFile()) nuevaPestana(f);
        }
        if (pestanas.getTabCount() == 0) nuevaPestana(null);
    }

    private void guardarSesion() {
        StringBuilder abiertos = new StringBuilder();
        for (int i = 0; i < pestanas.getTabCount(); i++) {
            File f = ((EditorTab) pestanas.getComponentAt(i)).getArchivo();
            if (f != null) abiertos.append(abiertos.length() > 0 ? "|" : "").append(f.getAbsolutePath());
        }
        prefs.putBoolean("oscuro", modoOscuro);
        prefs.put("abiertos", abiertos.toString());
    }

    private void salir() {
        boolean pendientes = false;
        for (int i = 0; i < pestanas.getTabCount(); i++) {
            pendientes |= ((EditorTab) pestanas.getComponentAt(i)).isModificado();
        }
        if (pendientes && JOptionPane.showConfirmDialog(this,
                "Hay archivos con cambios sin guardar. ¿Salir de todos modos?",
                "Cambios sin guardar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        guardarSesion();
        dispose();
        System.exit(0);
    }

    // ---------- tema ----------

    private void alternarTema(boolean oscuro) {
        modoOscuro = oscuro;
        JERSyntaxHighlighter.setModoOscuro(oscuro);

        Color fondo = oscuro ? new Color(0x3C3F41) : UIManager.getColor("Panel.background");
        Color texto = oscuro ? Color.WHITE : UIManager.getColor("Label.foreground");
        for (JComponent c : new JComponent[] {toolbar, barraEstado, pestanas, pestanasConsola}) {
            c.setBackground(fondo);
            c.setOpaque(true);
        }
        pestanas.setForeground(texto);
        pestanasConsola.setForeground(texto);
        etiquetaEstado.setForeground(texto);
        etiquetaPosicion.setForeground(texto);
        arbol.setBackground(oscuro ? new Color(0x2B2B2B) : Color.WHITE);
        arbol.setForeground(oscuro ? Color.WHITE : Color.BLACK);
        DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) arbol.getCellRenderer();
        renderer.setTextNonSelectionColor(oscuro ? Color.WHITE : Color.BLACK);
        renderer.setBackgroundNonSelectionColor(oscuro ? new Color(0x2B2B2B) : Color.WHITE);
        Color fondoConsola = oscuro ? new Color(0x1E1E1E) : Color.BLACK;
        consola.setBackground(fondoConsola);
        consolaErrores.setBackground(fondoConsola);
        arbol.repaint();

        for (int i = 0; i < pestanas.getTabCount(); i++) {
            ((EditorTab) pestanas.getComponentAt(i)).aplicarTema(oscuro);
        }
    }

    // ---------- arbol ----------

    private JTree construirArbolProyecto() {
        DefaultMutableTreeNode raiz = new DefaultMutableTreeNode(raizProyecto);
        poblarArbol(raiz, raizProyecto);
        JTree arbol = new JTree(raiz);
        arbol.setRootVisible(true);
        arbol.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                Object obj = ((DefaultMutableTreeNode) value).getUserObject();
                if (obj instanceof File) setText(((File) obj).getName());
                return this;
            }
        });
        return arbol;
    }

    private void poblarArbol(DefaultMutableTreeNode nodo, File dir) {
        File[] hijos = dir.listFiles();
        if (hijos == null) return;
        Arrays.sort(hijos, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File hijo : hijos) {
            if (hijo.getName().startsWith(".") || hijo.getName().equals("build")) continue;
            DefaultMutableTreeNode nodoHijo = new DefaultMutableTreeNode(hijo);
            nodo.add(nodoHijo);
            if (hijo.isDirectory()) poblarArbol(nodoHijo, hijo);
        }
    }

    /** Reconstruye el arbol desde disco conservando las carpetas expandidas. */
    private void refrescarArbol() {
        Set<File> expandidos = new HashSet<>();
        Enumeration<TreePath> abiertos = arbol.getExpandedDescendants(new TreePath(arbol.getModel().getRoot()));
        while (abiertos != null && abiertos.hasMoreElements()) {
            Object obj = ((DefaultMutableTreeNode) abiertos.nextElement().getLastPathComponent()).getUserObject();
            if (obj instanceof File) expandidos.add((File) obj);
        }

        DefaultMutableTreeNode raiz = new DefaultMutableTreeNode(raizProyecto);
        poblarArbol(raiz, raizProyecto);
        arbol.setModel(new DefaultTreeModel(raiz));

        @SuppressWarnings("unchecked")
        Enumeration<TreeNode> nodos = raiz.breadthFirstEnumeration();
        while (nodos.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodos.nextElement();
            if (n == raiz || expandidos.contains((File) n.getUserObject())) arbol.expandPath(new TreePath(n.getPath()));
        }
    }

    private void abrirDesdeArbol(JTree arbol) {
        DefaultMutableTreeNode nodo = (DefaultMutableTreeNode) arbol.getLastSelectedPathComponent();
        if (nodo == null) return;
        Object obj = nodo.getUserObject();
        if (obj instanceof File && ((File) obj).isFile()) nuevaPestana((File) obj);
    }

    // ---------- pestanas ----------

    private void nuevaPestana(File archivo) {
        if (archivo != null) {
            for (int i = 0; i < pestanas.getTabCount(); i++) {
                File abierto = ((EditorTab) pestanas.getComponentAt(i)).getArchivo();
                if (archivo.getAbsoluteFile().equals(abierto)) {
                    pestanas.setSelectedIndex(i);
                    return;
                }
            }
        }
        EditorTab tab;
        try {
            tab = new EditorTab(archivo);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo abrir " + archivo.getName() + ": " + e.getMessage(),
                    "Error al abrir", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (modoOscuro) tab.aplicarTema(true);
        int indice = pestanas.getTabCount();
        pestanas.addTab(tab.nombrePestana(), tab);

        JLabel titulo = new JLabel(tab.nombrePestana());
        titulo.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        JButton cerrar = new JButton("x");
        cerrar.setMargin(new Insets(0, 4, 0, 4));
        cerrar.setFocusable(false);
        cerrar.setBorderPainted(false);
        cerrar.setContentAreaFilled(false);
        cerrar.addActionListener(e -> cerrarPestana(tab));

        JPanel componentePestana = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        componentePestana.setOpaque(false);
        componentePestana.add(titulo);
        componentePestana.add(cerrar);
        pestanas.setTabComponentAt(indice, componentePestana);
        etiquetasPestana.put(tab, titulo);

        tab.setOnChange(() -> actualizarTitulo(tab));
        tab.setOnCaret(() -> { if (tab == pestanaActual()) actualizarPosicion(); });
        pestanas.setSelectedIndex(indice);
    }

    private void actualizarPosicion() {
        EditorTab tab = pestanaActual();
        etiquetaPosicion.setText(tab == null ? "" : tab.posicion());
    }

    private void actualizarTitulo(EditorTab tab) {
        JLabel titulo = etiquetasPestana.get(tab);
        if (titulo != null) titulo.setText(tab.nombrePestana());
        int i = pestanas.indexOfComponent(tab);
        if (i >= 0) pestanas.setTitleAt(i, tab.nombrePestana());
    }

    private void cerrarPestana(EditorTab tab) {
        if (tab.isModificado()) {
            int opcion = JOptionPane.showConfirmDialog(this,
                    "\"" + tab.nombrePestana().replace(" *", "") + "\" tiene cambios sin guardar. ¿Cerrar de todos modos?",
                    "Cambios sin guardar", JOptionPane.YES_NO_OPTION);
            if (opcion != JOptionPane.YES_OPTION) return;
        }
        pestanas.remove(tab);
        etiquetasPestana.remove(tab);
    }

    private EditorTab pestanaActual() {
        return (EditorTab) pestanas.getSelectedComponent();
    }

    // ---------- abrir / guardar ----------

    private void abrir() {
        chooser.setFileFilter(filtroAbrir);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            nuevaPestana(chooser.getSelectedFile());
        }
    }

    private void guardar() {
        EditorTab tab = pestanaActual();
        if (tab != null) guardarTab(tab, false);
    }

    private void guardarComo() {
        EditorTab tab = pestanaActual();
        if (tab != null) guardarTab(tab, true);
    }

    /** Guarda la pestana (pidiendo nombre si aun no tiene archivo o si 'como'). Devuelve false si el usuario cancelo o fallo. */
    private boolean guardarTab(EditorTab tab, boolean como) {
        File destino = tab.getArchivo();
        if (destino == null || como) {
            destino = elegirDestino(destino);
            if (destino == null) return false;
        }
        try {
            tab.guardar(destino);
            actualizarTitulo(tab);
            refrescarArbol();
            return true;
        } catch (IOException e) {
            consola.setText("Error al guardar: " + e.getMessage());
            pestanasConsola.setSelectedIndex(0);
            return false;
        }
    }

    /** Pide un nombre de archivo (por defecto en pruebas/) y le agrega .txt si no trae .txt ni .jer. */
    private File elegirDestino(File actual) {
        chooser.setFileFilter(filtroAbrir);
        chooser.setCurrentDirectory(actual != null ? actual.getParentFile() : carpetaPruebas);
        chooser.setSelectedFile(actual != null ? actual : new File(carpetaPruebas, "sin_titulo.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) return null;

        File f = chooser.getSelectedFile();
        String nombre = f.getName().toLowerCase();
        if (!nombre.endsWith(".txt") && !nombre.endsWith(".jer")) f = new File(f.getParentFile(), f.getName() + ".txt");
        if (f.exists() && !f.equals(actual) && JOptionPane.showConfirmDialog(this,
                "\"" + f.getName() + "\" ya existe. ¿Reemplazarlo?", "Guardar como",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null;
        return f;
    }

    // ---------- buscar / reemplazar ----------

    private void mostrarBuscar() {
        if (dialogoBuscar == null) {
            dialogoBuscar = new JDialog(this, "Buscar y reemplazar", false);
            JButton siguiente = new JButton("Siguiente");
            JButton anterior = new JButton("Anterior");
            JButton reemplazar = new JButton("Reemplazar");
            JButton todo = new JButton("Reemplazar todo");
            siguiente.addActionListener(e -> buscar(true));
            campoBuscar.addActionListener(e -> buscar(true));
            anterior.addActionListener(e -> buscar(false));
            reemplazar.addActionListener(e -> {
                EditorTab tab = pestanaActual();
                if (tab != null && !tab.reemplazar(campoBuscar.getText(), campoReemplazar.getText())) etiquetaEstado.setText(" Sin coincidencias");
            });
            todo.addActionListener(e -> {
                EditorTab tab = pestanaActual();
                if (tab != null) etiquetaEstado.setText(" " + tab.reemplazarTodo(campoBuscar.getText(), campoReemplazar.getText()) + " reemplazo(s)");
            });

            JPanel campos = new JPanel(new GridLayout(2, 2, 6, 6));
            campos.add(new JLabel("Buscar:"));
            campos.add(campoBuscar);
            campos.add(new JLabel("Reemplazar por:"));
            campos.add(campoReemplazar);
            JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            for (JButton b : new JButton[] {anterior, siguiente, reemplazar, todo}) botones.add(b);

            JPanel contenido = new JPanel(new BorderLayout(0, 6));
            contenido.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
            contenido.add(campos, BorderLayout.CENTER);
            contenido.add(botones, BorderLayout.SOUTH);
            dialogoBuscar.setContentPane(contenido);
            dialogoBuscar.getRootPane().registerKeyboardAction(e -> dialogoBuscar.setVisible(false),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialogoBuscar.pack();
            dialogoBuscar.setLocationRelativeTo(this);
        }
        dialogoBuscar.setVisible(true);
        campoBuscar.requestFocusInWindow();
        campoBuscar.selectAll();
    }

    private void buscar(boolean adelante) {
        EditorTab tab = pestanaActual();
        if (tab != null && !tab.buscar(campoBuscar.getText(), adelante)) etiquetaEstado.setText(" Sin coincidencias");
    }

    // ---------- consola / errores ----------

    private void limpiarConsola() {
        consola.setText("");
        consolaErrores.setText("");
        EditorTab tab = pestanaActual();
        if (tab != null) tab.marcarLineas(null);
        etiquetaEstado.setText(" Listo");
    }

    /** Clic sobre una linea "[ERROR ...] Linea N" (o su sugerencia "->") salta a esa linea del editor. */
    private void instalarSaltoAError(JTextArea area) {
        area.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int linea = lineaDeErrorEn(area, e.getPoint());
                EditorTab tab = pestanaActual();
                if (linea > 0 && tab != null) tab.irALinea(linea);
            }
        });
        area.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                area.setCursor(Cursor.getPredefinedCursor(lineaDeErrorEn(area, e.getPoint()) > 0 ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        });
    }

    private static int lineaDeErrorEn(JTextArea area, Point p) {
        try {
            int n = area.getLineOfOffset(area.viewToModel2D(p));
            Matcher m = PATRON_LINEA.matcher(area.getText(area.getLineStartOffset(n), area.getLineEndOffset(n) - area.getLineStartOffset(n)));
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (BadLocationException | NumberFormatException e) {
            return 0;
        }
    }

    // ---------- compilar ----------

    private void compilar() {
        EditorTab tab = pestanaActual();
        if (tab == null) return;
        if ((tab.getArchivo() == null || tab.isModificado()) && !guardarTab(tab, false)) return;

        btnCompilar.setEnabled(false);
        etiquetaEstado.setText(" Compilando...");
        File archivo = tab.getArchivo();
        File buildDir = new File(raizProyecto, "build");

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                File tmp = File.createTempFile("jer", ".out");
                try {
                    Process p = new ProcessBuilder(javaBin, "-cp", buildDir.getAbsolutePath(), "JERCompiler", archivo.getAbsolutePath())
                            .redirectErrorStream(true).redirectOutput(tmp).start();
                    if (!p.waitFor(TIMEOUT_COMPILACION_SEG, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        return "El compilador no respondio en " + TIMEOUT_COMPILACION_SEG + " s; se cancelo.";
                    }
                    return new String(Files.readAllBytes(tmp.toPath()), Charset.defaultCharset());
                } finally {
                    tmp.delete();
                }
            }

            @Override protected void done() {
                btnCompilar.setEnabled(true);
                try {
                    mostrarResultado(tab, get());
                } catch (Exception e) {
                    consola.setText("Error al compilar: " + e.getMessage());
                    pestanasConsola.setSelectedIndex(0);
                    etiquetaEstado.setText(" Error al compilar");
                }
            }
        }.execute();
    }

    /** Vuelca la salida en "Salida", copia solo los errores a "Errores" y marca sus lineas en el editor. */
    private void mostrarResultado(EditorTab tab, String salida) {
        consola.setText(salida);
        consola.setCaretPosition(0);

        StringBuilder errores = new StringBuilder();
        Set<Integer> lineas = new TreeSet<>();
        boolean enError = false;
        for (String linea : salida.split("\n")) {
            Matcher m = PATRON_ERROR.matcher(linea);
            if (m.find()) {
                lineas.add(Integer.parseInt(m.group(1)));
                enError = true;
            } else if (!linea.startsWith("   ->")) {
                enError = false;
            }
            if (enError) errores.append(linea).append('\n');
        }
        consolaErrores.setText(errores.toString());
        consolaErrores.setCaretPosition(0);
        tab.marcarLineas(lineas);

        pestanasConsola.setSelectedIndex(lineas.isEmpty() ? 0 : 1);
        etiquetaEstado.setText(lineas.isEmpty() ? " Compilacion terminada" : " " + lineas.size() + " linea(s) con error");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new JERIde().setVisible(true));
    }
}
