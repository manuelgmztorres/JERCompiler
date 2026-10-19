package ide.proyecto;

import ide.Tema;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Arbol de archivos del proyecto (sin ocultos ni build/); doble clic sobre un archivo lo abre. */
public class ArbolProyecto extends JTree {
    private final File raiz;

    /** `alAbrir` recibe el archivo al que se le hizo doble clic. */
    public ArbolProyecto(File raiz, Consumer<File> alAbrir) {
        super(construirModelo(raiz));
        this.raiz = raiz;
        setRootVisible(true);
        setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                Object obj = ((DefaultMutableTreeNode) value).getUserObject();
                if (obj instanceof File) setText(((File) obj).getName());
                return this;
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                DefaultMutableTreeNode nodo = (DefaultMutableTreeNode) getLastSelectedPathComponent();
                if (nodo == null) return;
                Object obj = nodo.getUserObject();
                if (obj instanceof File && ((File) obj).isFile()) alAbrir.accept((File) obj);
            }
        });
        expandRow(0);
    }

    private static DefaultTreeModel construirModelo(File raiz) {
        DefaultMutableTreeNode nodo = new DefaultMutableTreeNode(raiz);
        poblar(nodo, raiz);
        return new DefaultTreeModel(nodo);
    }

    private static void poblar(DefaultMutableTreeNode nodo, File dir) {
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
            if (hijo.isDirectory()) poblar(nodoHijo, hijo);
        }
    }

    /** Reconstruye el arbol desde disco conservando las carpetas expandidas. */
    public void refrescar() {
        Set<File> expandidos = new HashSet<>();
        Enumeration<TreePath> abiertos = getExpandedDescendants(new TreePath(getModel().getRoot()));
        while (abiertos != null && abiertos.hasMoreElements()) {
            Object obj = ((DefaultMutableTreeNode) abiertos.nextElement().getLastPathComponent()).getUserObject();
            if (obj instanceof File) expandidos.add((File) obj);
        }

        DefaultTreeModel modelo = construirModelo(raiz);
        setModel(modelo);
        DefaultMutableTreeNode nuevaRaiz = (DefaultMutableTreeNode) modelo.getRoot();

        @SuppressWarnings("unchecked")
        Enumeration<TreeNode> nodos = nuevaRaiz.breadthFirstEnumeration();
        while (nodos.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodos.nextElement();
            if (n == nuevaRaiz || expandidos.contains((File) n.getUserObject())) expandPath(new TreePath(n.getPath()));
        }
    }

    public void aplicarTema(Tema tema) {
        setBackground(tema.fondoArbol);
        setForeground(tema.textoArbol);
        DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) getCellRenderer();
        renderer.setTextNonSelectionColor(tema.textoArbol);
        renderer.setBackgroundNonSelectionColor(tema.fondoArbol);
        repaint();
    }
}
