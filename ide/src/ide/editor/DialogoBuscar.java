package ide.editor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Dialogo no modal de buscar y reemplazar (Ctrl+F / Ctrl+H); siempre actua sobre la pestana activa. */
public class DialogoBuscar extends JDialog {
    private final JTextField campoBuscar = new JTextField(18);
    private final JTextField campoReemplazar = new JTextField(18);
    private final Supplier<EditorTab> pestanaActual;
    private final Consumer<String> alEstado;

    /** `pestanaActual` da el editor activo (o null); `alEstado` recibe los mensajes para la barra de estado. */
    public DialogoBuscar(JFrame duenio, Supplier<EditorTab> pestanaActual, Consumer<String> alEstado) {
        super(duenio, "Buscar y reemplazar", false);
        this.pestanaActual = pestanaActual;
        this.alEstado = alEstado;

        JButton siguiente = new JButton("Siguiente");
        JButton anterior = new JButton("Anterior");
        JButton reemplazar = new JButton("Reemplazar");
        JButton todo = new JButton("Reemplazar todo");
        siguiente.addActionListener(e -> buscar(true));
        campoBuscar.addActionListener(e -> buscar(true));
        anterior.addActionListener(e -> buscar(false));
        reemplazar.addActionListener(e -> {
            EditorTab tab = pestanaActual.get();
            if (tab != null && !tab.reemplazar(campoBuscar.getText(), campoReemplazar.getText())) alEstado.accept(" Sin coincidencias");
        });
        todo.addActionListener(e -> {
            EditorTab tab = pestanaActual.get();
            if (tab != null) alEstado.accept(" " + tab.reemplazarTodo(campoBuscar.getText(), campoReemplazar.getText()) + " reemplazo(s)");
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
        setContentPane(contenido);
        getRootPane().registerKeyboardAction(e -> setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(duenio);
    }

    /** Muestra el dialogo con el foco y el texto de busqueda seleccionados. */
    public void mostrar() {
        setVisible(true);
        campoBuscar.requestFocusInWindow();
        campoBuscar.selectAll();
    }

    private void buscar(boolean adelante) {
        EditorTab tab = pestanaActual.get();
        if (tab != null && !tab.buscar(campoBuscar.getText(), adelante)) alEstado.accept(" Sin coincidencias");
    }
}
