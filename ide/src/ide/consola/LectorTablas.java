package ide.consola;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lee salida/tabla_tokens.txt y salida/tabla_tipos.txt (tabla de simbolos). No depende de Swing. */
public final class LectorTablas {
    public static final String[] COLUMNAS_TOKENS = { "No.", "Lexema", "Token", "Línea", "Columna" };
    public static final String[] COLUMNAS_SIMBOLOS = { "Nombre", "Categoría", "Tipo", "Dimensiones", "Ámbito", "Línea", "Usado", "Parámetros" };
    /** Indices de la linea y la columna dentro de una fila de tokens, y de la linea en una de simbolos. */
    public static final int TOKEN_LINEA = 3, TOKEN_COLUMNA = 4, SIMBOLO_LINEA = 5;

    /**
     * Una fila de tokens: "No. | Lexema | Token | Linea | Columna". El lexema puede contener " | " (una
     * cadena del programa) o ser mas ancho que su columna, asi que se toma con un grupo codicioso y
     * las tres columnas finales, que nunca tienen '|', se leen desde la derecha.
     */
    private static final Pattern FILA_TOKEN = Pattern.compile("^(\\d+)\\s*\\| (.*) \\| ([^|]*?)\\s*\\| (\\d+)\\s*\\| (\\d+)\\s*$");

    private LectorTablas() {}

    public static List<String[]> leerTokens(File f) throws IOException {
        List<String[]> filas = new ArrayList<>();
        for (String linea : LectorCuadruplos.leerTexto(f).split("\n")) {
            Matcher m = FILA_TOKEN.matcher(linea);
            if (!m.matches()) continue; // titulo, encabezado y separador
            filas.add(new String[] { m.group(1), m.group(2).stripTrailing(), m.group(3), m.group(4), m.group(5) });
        }
        return filas;
    }

    /** Filas entre la linea separadora ("----+----") y la linea final de solo guiones. */
    public static List<String[]> leerSimbolos(File f) throws IOException {
        String[] lineas = LectorCuadruplos.leerTexto(f).split("\n");
        int separador = -1;
        for (int i = 0; i < lineas.length && separador < 0; i++) {
            if (lineas[i].matches("-+(\\+-+)+")) separador = i;
        }
        if (separador < 0) throw new IOException("formato inesperado en " + f.getName());
        List<String[]> filas = new ArrayList<>();
        for (int i = separador + 1; i < lineas.length; i++) {
            if (lineas[i].matches("-+")) break;
            if (lineas[i].isBlank()) continue;
            String[] celdas = lineas[i].split("\\|", COLUMNAS_SIMBOLOS.length);
            String[] fila = new String[COLUMNAS_SIMBOLOS.length];
            for (int c = 0; c < fila.length; c++) fila[c] = c < celdas.length ? celdas[c].trim() : "";
            filas.add(fila);
        }
        return filas;
    }
}
