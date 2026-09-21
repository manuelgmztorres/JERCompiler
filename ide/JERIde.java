import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.file.*;
import java.util.Map;

/** IDE minimo para JER: arbol de proyecto, pestanas multi-archivo con resaltado de sintaxis y compilacion. */
public class JERIde extends JFrame {
    private final JTabbedPane pestanas = new JTabbedPane();
    private final JTextArea consola = new JTextArea();
    private final JFileChooser chooser = new JFileChooser();
    private final FileNameExtensionFilter filtroAbrir = new FileNameExtensionFilter("Archivos JER (*.jer, *.txt)", "jer", "txt");
    private final FileNameExtensionFilter filtroGuardarTxt = new FileNameExtensionFilter("Archivos de texto (*.txt)", "txt");
    private final File raizProyecto = new File(".").getAbsoluteFile();
    private final File carpetaPruebas = new File(raizProyecto, "pruebas");
    private final JTree arbol = construirArbolProyecto();
    private final JScrollPane scrollArbol = new JScrollPane(arbol);
    private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private boolean modoOscuro = false;
    private final Map<EditorTab, JLabel> etiquetasPestana = new java.util.IdentityHashMap<>();

    public JERIde() {
        super("JER IDE");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 750);

        carpetaPruebas.mkdirs();
        chooser.setFileFilter(filtroAbrir);

        consola.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        consola.setEditable(false);
        consola.setBackground(Color.BLACK);
        consola.setForeground(Color.GREEN);

        arbol.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) abrirDesdeArbol(arbol);
            }
        });

        JButton btnNuevo = new JButton("Nuevo");
        JButton btnAbrir = new JButton("Abrir");
        JButton btnGuardar = new JButton("Guardar");
        JButton btnCompilar = new JButton("Compilar");
        JToggleButton btnModoOscuro = new JToggleButton("Modo oscuro");
        btnNuevo.addActionListener(e -> conRedDeSeguridad(() -> nuevaPestana(null)));
        btnAbrir.addActionListener(e -> conRedDeSeguridad(this::abrir));
        btnGuardar.addActionListener(e -> conRedDeSeguridad(this::guardar));
        btnCompilar.addActionListener(e -> conRedDeSeguridad(this::compilar));
        btnModoOscuro.addActionListener(e -> conRedDeSeguridad(() -> alternarTema(btnModoOscuro.isSelected())));

        toolbar.add(btnNuevo);
        toolbar.add(btnAbrir);
        toolbar.add(btnGuardar);
        toolbar.add(btnCompilar);
        toolbar.add(btnModoOscuro);

        JSplitPane splitVertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                pestanas, new JScrollPane(consola));
        splitVertical.setResizeWeight(0.7);

        JSplitPane splitHorizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                scrollArbol, splitVertical);
        splitHorizontal.setDividerLocation(220);

        setLayout(new BorderLayout());
        add(toolbar, BorderLayout.NORTH);
        add(splitHorizontal, BorderLayout.CENTER);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "guardarAtajo");
        getRootPane().getActionMap().put("guardarAtajo", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { conRedDeSeguridad(JERIde.this::guardar); }
        });

        nuevaPestana(null);
    }

    /** Corre una accion de la UI atrapando cualquier excepcion para mostrarla en la consola en vez de que desaparezca en silencio. */
    private void conRedDeSeguridad(Runnable accion) {
        try {
            accion.run();
        } catch (Exception ex) {
            consola.setText("Error inesperado: " + ex);
        }
    }

    private void alternarTema(boolean oscuro) {
        modoOscuro = oscuro;
        JERSyntaxHighlighter.setModoOscuro(oscuro);

        Color fondo = oscuro ? new Color(0x3C3F41) : UIManager.getColor("Panel.background");
        toolbar.setBackground(fondo);
        toolbar.setOpaque(true);
        arbol.setBackground(oscuro ? new Color(0x2B2B2B) : Color.WHITE);
        arbol.setForeground(oscuro ? Color.WHITE : Color.BLACK);
        DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) arbol.getCellRenderer();
        renderer.setTextNonSelectionColor(oscuro ? Color.WHITE : Color.BLACK);
        renderer.setBackgroundNonSelectionColor(oscuro ? new Color(0x2B2B2B) : Color.WHITE);
        arbol.repaint();

        for (int i = 0; i < pestanas.getTabCount(); i++) {
            ((EditorTab) pestanas.getComponentAt(i)).aplicarTema(oscuro);
        }
    }

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
        java.util.Arrays.sort(hijos, (a, b) -> {
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

    private void abrirDesdeArbol(JTree arbol) {
        DefaultMutableTreeNode nodo = (DefaultMutableTreeNode) arbol.getLastSelectedPathComponent();
        if (nodo == null) return;
        Object obj = nodo.getUserObject();
        if (obj instanceof File && ((File) obj).isFile()) nuevaPestana((File) obj);
    }

    private void nuevaPestana(File archivo) {
        EditorTab tab = new EditorTab(archivo);
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
        pestanas.setSelectedIndex(indice);
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

    private void abrir() {
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            nuevaPestana(chooser.getSelectedFile());
        }
    }

    private void guardar() {
        EditorTab tab = pestanaActual();
        if (tab == null) return;
        guardarTab(tab);
    }

    /** Guarda la pestaña (pidiendo nombre si aun no tiene archivo). Devuelve false si el usuario cancelo o fallo. */
    private boolean guardarTab(EditorTab tab) {
        File destino = tab.getArchivo();
        if (destino == null) {
            destino = elegirDestinoEnPruebas();
            if (destino == null) return false;
        }
        try {
            tab.guardar(destino);
            actualizarTitulo(tab);
            return true;
        } catch (IOException e) {
            consola.setText("Error al guardar: " + e.getMessage());
            return false;
        }
    }

    /** Pide un nombre de archivo y lo fuerza a caer dentro de pruebas/ con extension .txt. */
    private File elegirDestinoEnPruebas() {
        chooser.setFileFilter(filtroGuardarTxt);
        chooser.setCurrentDirectory(carpetaPruebas);
        chooser.setSelectedFile(new File(carpetaPruebas, "sin_titulo.txt"));

        int resultado = chooser.showSaveDialog(this);
        File seleccionado = chooser.getSelectedFile();
        chooser.setFileFilter(filtroAbrir);
        if (resultado != JFileChooser.APPROVE_OPTION || seleccionado == null) return null;

        String nombre = seleccionado.getName();
        if (!nombre.toLowerCase().endsWith(".txt")) nombre = nombre + ".txt";
        return new File(carpetaPruebas, nombre);
    }

    private void compilar() {
        EditorTab tab = pestanaActual();
        if (tab == null) return;
        if (tab.getArchivo() == null || tab.isModificado()) {
            if (!guardarTab(tab)) return;
        }
        try {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
            String buildDir = new File(raizProyecto, "build").getAbsolutePath();

            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", buildDir, "JERCompiler", tab.getArchivo().getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proceso = pb.start();

            StringBuilder salida = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proceso.getInputStream()))) {
                String linea;
                while ((linea = br.readLine()) != null) salida.append(linea).append('\n');
            }
            proceso.waitFor();
            consola.setText(salida.toString());
        } catch (IOException | InterruptedException e) {
            consola.setText("Error al compilar: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new JERIde().setVisible(true));
    }
}
