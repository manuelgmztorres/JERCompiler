package ide.editor;

import ide.Tema;

import javax.swing.text.*;
import java.awt.*;
import java.util.regex.*;

/** Resaltado de sintaxis JER sobre un JTextPane, via regex sobre las palabras clave reales del .jjt. */
public final class JERSyntaxHighlighter {

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
    private static final StyleContext CONTEXTO = new StyleContext();

    static {
        aplicarTema(Tema.CLARO);
    }

    private static Style crear(Color color, boolean bold, boolean italic) {
        Style s = CONTEXTO.addStyle(null, null);
        StyleConstants.setForeground(s, color);
        StyleConstants.setBold(s, bold);
        StyleConstants.setItalic(s, italic);
        return s;
    }

    /** Cambia la paleta de todos los editores; cada uno se vuelve a pintar en su aplicarTema(). */
    public static void aplicarTema(Tema tema) {
        estiloKeyword = crear(tema.sintaxisPalabraClave, true, false);
        estiloBool = crear(tema.sintaxisBooleano, true, false);
        estiloComment = crear(tema.sintaxisComentario, false, true);
        estiloString = crear(tema.sintaxisCadena, false, false);
        estiloNumber = crear(tema.sintaxisNumero, false, false);
        estiloNormal = crear(tema.sintaxisNormal, false, false);
    }

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
