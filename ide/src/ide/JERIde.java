package ide;

import ide.compilacion.EjecutorCompilador;
import ide.compilacion.ResultadoCompilacion;
import ide.consola.PanelConsola;
import ide.editor.DialogoBuscar;
import ide.editor.EditorTab;
import ide.editor.JERSyntaxHighlighter;
import ide.proyecto.ArbolProyecto;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Ventana principal del IDE: toolbar, atajos, pestanas de editor, sesion, tema, abrir/guardar y la
 * orquestacion de la compilacion. El resto vive en sus paquetes: editor/ (pestana, resaltado,
 * buscar), proyecto/ (arbol), compilacion/ (proceso del compilador) y consola/ (salida, errores y
 * codigo intermedio).
 */
public class JERIde extends JFrame {
    /**
     * Nodo de preferencias de la version sin paquete (Preferences.userNodeForPackage de una clase sin
     * paquete da "/<unnamed>"): se conserva para no perder el tema y los archivos abiertos guardados.
     */
    private static final String NODO_PREFERENCIAS = "/<unnamed>";

    private final Preferences prefs = Preferences.userRoot().node(NODO_PREFERENCIAS);
    private final File raizProyecto = new File(".").getAbsoluteFile();
    private final File carpetaPruebas = new File(raizProyecto, "pruebas");

    private final JTabbedPane pestanas = new JTabbedPane();
    private final Map<EditorTab, JLabel> etiquetasPestana = new IdentityHashMap<>();
    private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JPanel barraEstado = new JPanel(new BorderLayout());
    private final JLabel etiquetaEstado = new JLabel(" Listo");
    private final JLabel etiquetaPosicion = new JLabel("");
    private final JButton btnCompilar = new JButton("Compilar");
    private final JToggleButton btnModoOscuro = new JToggleButton("Modo oscuro");
    private final JFileChooser chooser = new JFileChooser();
    private final FileNameExtensionFilter filtroAbrir = new FileNameExtensionFilter("Archivos JER (*.jer, *.txt)", "jer", "txt");

    private final ArbolProyecto arbol = new ArbolProyecto(raizProyecto, this::nuevaPestana);
    private final PanelConsola consola = new PanelConsola(new File(raizProyecto, "salida"), this::irA, this::estado);
    private final EjecutorCompilador compilador = new EjecutorCompilador(new File(raizProyecto, "build"));
    private DialogoBuscar dialogoBuscar; // se crea la primera vez que se abre
    private Tema tema = Tema.CLARO;

    public JERIde() {
        super("JER IDE");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1100, 750);
        setIconImage(crearIcono());

        carpetaPruebas.mkdirs();
        chooser.setFileFilter(filtroAbrir);

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
        btnCompilar.setToolTipText("Compilar (F5)");
        btnLimpiar.addActionListener(e -> limpiarConsola());
        btnModoOscuro.addActionListener(e -> conRedDeSeguridad(() -> aplicarTema(Tema.de(btnModoOscuro.isSelected()))));

        for (JComponent c : new JComponent[] {btnNuevo, btnAbrir, btnGuardar, btnGuardarComo, btnBuscar,
                btnCompilar, btnLimpiar, btnModoOscuro}) {
            toolbar.add(c);
        }

        etiquetaPosicion.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        barraEstado.add(etiquetaEstado, BorderLayout.WEST);
        barraEstado.add(etiquetaPosicion, BorderLayout.EAST);

        JSplitPane splitVertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pestanas, consola);
        splitVertical.setResizeWeight(0.7);

        JSplitPane splitHorizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(arbol), splitVertical);
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
        atajo(KeyEvent.VK_F5, 0, "compilarAtajo", () -> { if (btnCompilar.isEnabled()) compilar(); });

        pestanas.addChangeListener(e -> actualizarPosicion());

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { salir(); }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) { arbol.refrescar(); }
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
            consola.mostrarMensaje("Error inesperado: " + ex);
        }
    }

    private void estado(String texto) { etiquetaEstado.setText(texto); }

    // ---------- sesion (tema + archivos abiertos) ----------

    private void restaurarSesion() {
        if (prefs.getBoolean("oscuro", false)) {
            btnModoOscuro.setSelected(true);
            aplicarTema(Tema.OSCURO);
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
        prefs.putBoolean("oscuro", tema.oscuro);
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

    private void aplicarTema(Tema nuevo) {
        tema = nuevo;
        JERSyntaxHighlighter.aplicarTema(tema);
        for (JComponent c : new JComponent[] {toolbar, barraEstado, pestanas}) {
            c.setBackground(tema.fondoPanel);
            c.setOpaque(true);
        }
        pestanas.setForeground(tema.textoPanel);
        etiquetaEstado.setForeground(tema.textoPanel);
        etiquetaPosicion.setForeground(tema.textoPanel);
        arbol.aplicarTema(tema);
        consola.aplicarTema(tema);
        for (int i = 0; i < pestanas.getTabCount(); i++) {
            ((EditorTab) pestanas.getComponentAt(i)).aplicarTema(tema);
        }
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
        if (tema.oscuro) tab.aplicarTema(tema);
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

    /** Lleva el editor activo a una linea y columna (1-based): desde un error, un token o un simbolo. */
    private void irA(int linea, int columna) {
        EditorTab tab = pestanaActual();
        if (tab != null) tab.irA(linea, columna);
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
            arbol.refrescar();
            return true;
        } catch (IOException e) {
            consola.mostrarMensaje("Error al guardar: " + e.getMessage());
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

    // ---------- buscar ----------

    private void mostrarBuscar() {
        if (dialogoBuscar == null) dialogoBuscar = new DialogoBuscar(this, this::pestanaActual, this::estado);
        dialogoBuscar.mostrar();
    }

    // ---------- consola / compilar ----------

    private void limpiarConsola() {
        consola.limpiar();
        EditorTab tab = pestanaActual();
        if (tab != null) tab.marcarLineas(null);
        estado(" Listo");
    }

    private void compilar() {
        EditorTab tab = pestanaActual();
        if (tab == null) return;
        if ((tab.getArchivo() == null || tab.isModificado()) && !guardarTab(tab, false)) return;

        btnCompilar.setEnabled(false);
        estado(" Compilando...");
        compilador.compilar(tab.getArchivo(),
                resultado -> { btnCompilar.setEnabled(true); mostrarResultado(tab, resultado); },
                error -> {
                    btnCompilar.setEnabled(true);
                    consola.mostrarMensaje("Error al compilar: " + error.getMessage());
                    consola.codigoIntermedio().limpiar(" Sin código intermedio: la compilación falló.");
                    estado(" Error al compilar");
                });
    }

    /**
     * Vuelca la salida y los errores, marca sus lineas en el editor y carga las tablas de salida/: la
     * de tokens siempre; la de simbolos y el codigo intermedio, solo si compilo sin errores.
     * Con errores se muestra la pestana Errores; sin errores se queda la pestana que estaba abierta
     * (salvo Errores, que quedaria vacia: ahi se pasa a Salida).
     */
    private void mostrarResultado(EditorTab tab, ResultadoCompilacion r) {
        consola.mostrarResultado(r);
        tab.marcarLineas(r.lineasConError);

        consola.tablas().cargar(r.sinErrores, r.inicio);
        boolean hayCodigo = consola.codigoIntermedio().cargar(r.sinErrores, r.inicio);
        boolean hayErrores = !r.lineasConError.isEmpty();
        if (hayErrores) consola.setSelectedIndex(PanelConsola.TAB_ERRORES);
        else if (consola.getSelectedIndex() == PanelConsola.TAB_ERRORES) consola.setSelectedIndex(PanelConsola.TAB_SALIDA);
        if (hayErrores) estado(" " + r.lineasConError.size() + " linea(s) con error");
        else if (hayCodigo) estado(" Compilacion terminada: " + consola.codigoIntermedio().cantidadCuadruplos() + " cuadruplo(s)");
        else estado(" Compilacion terminada");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new JERIde().setVisible(true));
    }
}
