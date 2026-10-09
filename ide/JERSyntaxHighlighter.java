import javax.swing.text.*;
import java.awt.*;
import java.util.regex.*;

/** Resaltado de sintaxis JER sobre un JTextPane, via regex sobre las palabras clave reales del .jjt. */
final class JERSyntaxHighlighter {

    private static final String[] PALABRAS_CLAVE = {
            "ENT", "DEC", "CAD", "CAR", "BOO", "VACIO", "CONST",
            "SI", "SINO", "MIENTRAS", "REPETIR", "HACER",
            "EVALUAR", "CUANDO", "PRED", "TERMINAR",
            "FUN", "RET", "OBT", "IMP", "AND", "OR", "NOT"
    };

    private static final Pattern PATRON = Pattern.compile(
            "(?<KEYWORD>\\b(?:" + String.join("|", PALABRAS_CLAVE) + ")\\b)"
                    + "|(?<BOOL>\\bVERDADERO\\b|\\bFALSO\\b)"
                    + "|(?<COMMENT>#[^\\n]*|(?s:/\\*\\*.*?\\*\\*/))"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')"
                    + "|(?<NUMBER>\\b\\d+(?:\\.\\d+)?\\b)");

    private static Style estiloKeyword, estiloBool, estiloComment, estiloString, estiloNumber, estiloNormal;
    private static boolean oscuro = false;

    static {
        aplicarPaleta(false);
    }

    private static Style crear(Color color, boolean bold, boolean italic) {
        Style s = new StyleContext().addStyle(null, null);
        StyleConstants.setForeground(s, color);
        StyleConstants.setBold(s, bold);
        StyleConstants.setItalic(s, italic);
        return s;
    }

    private static void aplicarPaleta(boolean modoOscuro) {
        if (modoOscuro) {
            estiloKeyword = crear(new Color(0x6AAEF5), true, false);
            estiloBool = crear(new Color(0xC792EA), true, false);
            estiloComment = crear(new Color(0x808080), false, true);
            estiloString = crear(new Color(0x6ABF69), false, false);
            estiloNumber = crear(new Color(0xE0A458), false, false);
            estiloNormal = crear(new Color(0xE0E0E0), false, false);
        } else {
            estiloKeyword = crear(new Color(0x2A6DB8), true, false);
            estiloBool = crear(new Color(0x9A4EAE), true, false);
            estiloComment = crear(new Color(0x808080), false, true);
            estiloString = crear(new Color(0x2E8B57), false, false);
            estiloNumber = crear(new Color(0xB5651D), false, false);
            estiloNormal = crear(Color.BLACK, false, false);
        }
    }

    static void setModoOscuro(boolean modoOscuro) {
        oscuro = modoOscuro;
        aplicarPaleta(modoOscuro);
    }

    static boolean isModoOscuro() { return oscuro; }

    private JERSyntaxHighlighter() {}

    static void aplicar(StyledDocument doc) {
        String texto;
        try {
            texto = doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return;
        }
        doc.setCharacterAttributes(0, texto.length(), estiloNormal, true);
        Matcher m = PATRON.matcher(texto);
        while (m.find()) {
            Style estilo;
            if (m.group("KEYWORD") != null) estilo = estiloKeyword;
            else if (m.group("BOOL") != null) estilo = estiloBool;
            else if (m.group("COMMENT") != null) estilo = estiloComment;
            else if (m.group("STRING") != null) estilo = estiloString;
            else estilo = estiloNumber;
            doc.setCharacterAttributes(m.start(), m.end() - m.start(), estilo, true);
        }
    }
}
