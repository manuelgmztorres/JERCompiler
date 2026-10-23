package ide;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.metal.OceanTheme;
import java.awt.Component;
import java.awt.Window;

/**
 * Colores de los componentes estandar de Swing (botones, pestanas, barras de desplazamiento,
 * divisores, separadores, bordes, dialogos y JFileChooser): Tema solo pinta los componentes propios
 * del IDE. En modo claro se usa Metal con su tema Ocean (el predeterminado de Java); en modo oscuro,
 * Metal con la paleta de MetalOscuro, sin degradados.
 */
public final class TemaSwing {
    private TemaSwing() { }

    /**
     * Cambia el look and feel y lo reaplica a todas las ventanas abiertas y a `extra` (componentes que
     * no estan dentro de una ventana, como un JFileChooser o un panel oculto). Despues hay que volver a
     * llamar a los aplicarTema(Tema) de los componentes propios, porque se reinstala su UI.
     */
    public static void aplicar(boolean oscuro, Component... extra) {
        MetalLookAndFeel.setCurrentTheme(oscuro ? new MetalOscuro() : new OceanTheme());
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (UnsupportedLookAndFeelException e) {
            return; // Metal siempre esta disponible
        }
        for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w);
        for (Component c : extra) {
            if (c != null) SwingUtilities.updateComponentTreeUI(c);
        }
    }

    /** Paleta oscura de Metal, en los mismos tonos que Tema.OSCURO. */
    private static final class MetalOscuro extends DefaultMetalTheme {
        private static final ColorUIResource
                BORDE_SELECCION = new ColorUIResource(0x4A6FA5),
                FOCO = new ColorUIResource(0x3D6299),
                CONTROL_ACTIVO = new ColorUIResource(0x5A5E61),
                SOMBRA_OSCURA = new ColorUIResource(0x232323),
                SOMBRA = new ColorUIResource(0x55585B),
                CONTROL = new ColorUIResource(0x3C3F41),
                RESALTE = new ColorUIResource(0x4B4E51),
                FONDO = new ColorUIResource(0x2B2B2B),
                TEXTO = new ColorUIResource(0xDDDDDD),
                TEXTO_INACTIVO = new ColorUIResource(0x8A8A8A),
                SELECCION_TEXTO = new ColorUIResource(0x214283);

        @Override public String getName() { return "JER oscuro"; }

        @Override protected ColorUIResource getPrimary1() { return BORDE_SELECCION; }
        @Override protected ColorUIResource getPrimary2() { return FOCO; }
        @Override protected ColorUIResource getPrimary3() { return CONTROL_ACTIVO; }
        @Override protected ColorUIResource getSecondary1() { return SOMBRA_OSCURA; }
        @Override protected ColorUIResource getSecondary2() { return SOMBRA; }
        @Override protected ColorUIResource getSecondary3() { return CONTROL; }
        @Override protected ColorUIResource getWhite() { return FONDO; }
        @Override protected ColorUIResource getBlack() { return TEXTO; }

        // Metal usa getWhite() para los bordes 3D "iluminados": aqui irian en negro sobre gris.
        @Override public ColorUIResource getControlHighlight() { return RESALTE; }
        @Override public ColorUIResource getPrimaryControlHighlight() { return RESALTE; }
        @Override public ColorUIResource getSeparatorBackground() { return CONTROL; }
        @Override public ColorUIResource getSeparatorForeground() { return SOMBRA; }
        @Override public ColorUIResource getInactiveControlTextColor() { return TEXTO_INACTIVO; }
        @Override public ColorUIResource getInactiveSystemTextColor() { return TEXTO_INACTIVO; }
        @Override public ColorUIResource getMenuDisabledForeground() { return TEXTO_INACTIVO; }
        @Override public ColorUIResource getTextHighlightColor() { return SELECCION_TEXTO; }
        @Override public ColorUIResource getHighlightedTextColor() { return TEXTO; }
        @Override public ColorUIResource getMenuSelectedBackground() { return SELECCION_TEXTO; }
        @Override public ColorUIResource getMenuSelectedForeground() { return TEXTO; }
        @Override public ColorUIResource getWindowTitleInactiveBackground() { return CONTROL; }
        @Override public ColorUIResource getDesktopColor() { return FONDO; }

        @Override public void addCustomEntriesToTable(UIDefaults tabla) {
            super.addCustomEntriesToTable(tabla);
            tabla.putDefaults(new Object[] {
                    "ToggleButton.select", CONTROL_ACTIVO,
                    "Button.select", CONTROL_ACTIVO,
                    "TabbedPane.selected", CONTROL_ACTIVO,
                    "TabbedPane.selectHighlight", RESALTE,
                    "TabbedPane.unselectedBackground", CONTROL,
                    "TabbedPane.tabAreaBackground", CONTROL,
                    "TabbedPane.contentAreaColor", CONTROL,
                    "TabbedPane.borderHightlightColor", SOMBRA,
                    "TabbedPane.highlight", RESALTE,
                    "TabbedPane.light", SOMBRA,
                    "TabbedPane.focus", FOCO,
                    "ScrollBar.background", FONDO,
                    "ScrollBar.track", FONDO,
                    "ScrollBar.trackHighlight", SOMBRA_OSCURA,
                    "ScrollBar.thumb", CONTROL_ACTIVO,
                    "ScrollBar.thumbHighlight", RESALTE,
                    "ScrollBar.thumbShadow", SOMBRA_OSCURA,
                    "SplitPane.background", CONTROL,
                    "SplitPane.dividerFocusColor", FOCO,
                    "SplitPaneDivider.draggingColor", SOMBRA,
                    "Table.gridColor", SOMBRA,
                    "TableHeader.background", CONTROL,
                    "TableHeader.foreground", TEXTO,
                    "ToolTip.background", CONTROL,
                    "ToolTip.foreground", TEXTO,
                    "Tree.line", SOMBRA,
                    "Tree.hash", SOMBRA,
                    "ComboBox.selectionBackground", SELECCION_TEXTO,
                    "List.selectionBackground", SELECCION_TEXTO,
                    "Tree.selectionBackground", SELECCION_TEXTO,
                    "Table.selectionBackground", SELECCION_TEXTO,
            });
        }
    }
}
