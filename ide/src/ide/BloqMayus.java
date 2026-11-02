package ide;

import java.awt.AWTEvent;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;

/**
 * Corrige Bloq Mayus en Linux cuando el IDE corre sobre XWayland (por ejemplo en COSMIC o KDE con
 * Wayland): ahi el compositor avisa tarde el cambio de Bloq Mayus a las aplicaciones X11, y el
 * cambio no se aplica hasta presionar Shift/Ctrl/Alt o cambiar de ventana.
 *
 * El IDE lleva su propio estado de Bloq Mayus: se lee del sistema cada vez que una ventana del IDE
 * recibe el foco (al cambiar de ventana el sistema ya lo tiene sincronizado), cada pulsacion de la
 * tecla lo invierte, y cada letra que se escribe sale en mayuscula si (Bloq Mayus XOR Shift), si no
 * en minuscula. Solo toca letras escritas sin Ctrl, Alt ni Meta.
 */
public final class BloqMayus implements KeyEventDispatcher {

    /** Estado propio de Bloq Mayus; null mientras no se conoce (no se corrige nada). */
    private Boolean activo;

    private BloqMayus() {}

    /** Lo instala para toda la aplicacion. En Windows y macOS no hace nada (no tienen el problema). */
    public static void instalar() {
        String so = System.getProperty("os.name", "").toLowerCase();
        if (so.contains("win") || so.contains("mac")) return;
        BloqMayus b = new BloqMayus();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(b);
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e.getID() == WindowEvent.WINDOW_GAINED_FOCUS) b.activo = estadoDelSistema();
        }, AWTEvent.WINDOW_FOCUS_EVENT_MASK);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_CAPS_LOCK) {
            if (activo != null) activo = !activo;
        } else if (e.getID() == KeyEvent.KEY_TYPED && activo != null && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
            char c = e.getKeyChar();
            if (Character.isLetter(c) && Character.isUpperCase(c) != Character.isLowerCase(c)) {
                boolean mayuscula = activo ^ e.isShiftDown();
                e.setKeyChar(mayuscula ? Character.toUpperCase(c) : Character.toLowerCase(c));
            }
        }
        return false; // el evento sigue su camino normal (ya con la letra corregida)
    }

    /** Bloq Mayus segun el sistema. */
    private static Boolean estadoDelSistema() {
        try {
            return Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK);
        } catch (UnsupportedOperationException e) {
            return null; // no se puede saber: no se corrige nada
        }
    }
}
