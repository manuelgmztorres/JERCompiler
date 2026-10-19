package ide;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Paleta del IDE en un solo lugar. Hay dos instancias, CLARO y OSCURO; cada componente recibe la
 * que corresponde en su aplicarTema(Tema) y toma de aqui sus colores, en vez de repetirlos.
 */
public final class Tema {

    public final boolean oscuro;

    // Ventana: toolbar, barra de estado, pestanas y etiquetas.
    public final Color fondoPanel, textoPanel;
    // Arbol de proyecto.
    public final Color fondoArbol, textoArbol;
    // Consolas de texto (Salida, Errores, Tres direcciones, Tabla de simbolos).
    public final Color fondoConsola, textoConsola;
    // Editor y gutter de numeros de linea.
    public final Color fondoEditor, cursorEditor, fondoNumeros, textoNumeros;
    // Resaltado de sintaxis JER.
    public final Color sintaxisPalabraClave, sintaxisBooleano, sintaxisComentario, sintaxisCadena, sintaxisNumero, sintaxisNormal;
    // Tabla de cuadruplos.
    public final Color fondoTabla, textoTabla, rejillaTabla, fondoSeleccion, textoSeleccion, fondoEncabezado, textoEncabezado;
    public final Color fondoFilaFun, fondoFilaEtiq, textoFilaEtiq, textoSalto;

    public static final Tema CLARO = new Tema(false);
    public static final Tema OSCURO = new Tema(true);

    public static Tema de(boolean oscuro) { return oscuro ? OSCURO : CLARO; }

    private Tema(boolean oscuro) {
        this.oscuro = oscuro;
        if (oscuro) {
            fondoPanel = new Color(0x3C3F41);
            textoPanel = Color.WHITE;
            fondoArbol = new Color(0x2B2B2B);
            textoArbol = Color.WHITE;
            fondoConsola = new Color(0x1E1E1E);
            textoConsola = Color.GREEN;
            fondoEditor = new Color(0x2B2B2B);
            cursorEditor = Color.WHITE;
            fondoNumeros = new Color(0x333333);
            textoNumeros = new Color(0x808080);
            sintaxisPalabraClave = new Color(0x6AAEF5);
            sintaxisBooleano = new Color(0xC792EA);
            sintaxisComentario = new Color(0x808080);
            sintaxisCadena = new Color(0x6ABF69);
            sintaxisNumero = new Color(0xE0A458);
            sintaxisNormal = new Color(0xE0E0E0);
            fondoTabla = new Color(0x2B2B2B);
            textoTabla = new Color(0xDDDDDD);
            rejillaTabla = new Color(0x4A4A4A);
            fondoSeleccion = new Color(0x214283);
            textoSeleccion = Color.WHITE;
            fondoEncabezado = new Color(0x3C3F41);
            textoEncabezado = Color.WHITE;
            fondoFilaFun = new Color(0x2F3A48);
            fondoFilaEtiq = new Color(0x4A4426);
            textoFilaEtiq = new Color(0xFFD866);
            textoSalto = new Color(0x6CB4FF);
        } else {
            fondoPanel = UIManager.getColor("Panel.background");
            textoPanel = UIManager.getColor("Label.foreground");
            fondoArbol = Color.WHITE;
            textoArbol = Color.BLACK;
            fondoConsola = Color.BLACK;
            textoConsola = Color.GREEN;
            fondoEditor = Color.WHITE;
            cursorEditor = Color.BLACK;
            fondoNumeros = new Color(0xF0F0F0);
            textoNumeros = Color.GRAY;
            sintaxisPalabraClave = new Color(0x2A6DB8);
            sintaxisBooleano = new Color(0x9A4EAE);
            sintaxisComentario = new Color(0x808080);
            sintaxisCadena = new Color(0x2E8B57);
            sintaxisNumero = new Color(0xB5651D);
            sintaxisNormal = Color.BLACK;
            fondoTabla = Color.WHITE;
            textoTabla = Color.BLACK;
            rejillaTabla = new Color(0xD0D0D0);
            fondoSeleccion = new Color(0xB8CFE5);
            textoSeleccion = Color.BLACK;
            fondoEncabezado = UIManager.getColor("TableHeader.background");
            textoEncabezado = UIManager.getColor("TableHeader.foreground");
            fondoFilaFun = new Color(0xE3ECF7);
            fondoFilaEtiq = new Color(0xFFF3C4);
            textoFilaEtiq = new Color(0x8A5A00);
            textoSalto = new Color(0x0B5CAD);
        }
    }
}
