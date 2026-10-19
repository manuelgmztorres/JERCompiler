package ide.consola;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Lee los archivos de texto que el compilador deja en salida/. No depende de Swing. */
public final class LectorCuadruplos {
    public static final String[] COLUMNAS = { "Operador", "Arg1", "Arg2", "Resultado" };

    private LectorCuadruplos() {}

    /** El archivo completo en UTF-8, con saltos de linea "\n". */
    public static String leerTexto(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /**
     * Lee la tabla en columnas de cuadruplos.txt. Las columnas se cortan por POSICION, tomando los '+'
     * de la linea separadora ("------+------+..."), y no partiendo por " | ": asi una cadena del
     * programa que contenga " | " no rompe la fila. La tabla termina en la linea de solo guiones.
     */
    public static List<String[]> leer(File f) throws IOException {
        String[] lineas = leerTexto(f).split("\n");
        int separador = -1;
        for (int i = 0; i < lineas.length && separador < 0; i++) {
            if (lineas[i].matches("-+(\\+-+)+")) separador = i;
        }
        if (separador < 0) throw new IOException("formato inesperado en " + f.getName());

        String guia = lineas[separador];
        int[] inicios = new int[COLUMNAS.length];
        int[] fines = new int[COLUMNAS.length];
        int columna = 0;
        for (int p = 0; p < guia.length() && columna < inicios.length - 1; p++) {
            if (guia.charAt(p) == '+') {
                fines[columna] = p;
                inicios[++columna] = p + 2; // despues de "| "
            }
        }
        if (columna != inicios.length - 1) throw new IOException("columnas inesperadas en " + f.getName());
        fines[columna] = Integer.MAX_VALUE;

        List<String[]> filas = new ArrayList<>();
        for (int i = separador + 1; i < lineas.length; i++) {
            String linea = lineas[i];
            if (linea.matches("-+")) break; // fin de la tabla
            if (linea.isBlank()) continue;
            String[] celdas = new String[COLUMNAS.length];
            for (int c = 0; c < celdas.length; c++) {
                int desde = Math.min(inicios[c], linea.length());
                int hasta = Math.min(fines[c], linea.length());
                celdas[c] = linea.substring(desde, Math.max(desde, hasta)).trim();
            }
            filas.add(celdas);
        }
        return filas;
    }
}
