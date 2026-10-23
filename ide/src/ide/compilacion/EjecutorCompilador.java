package ide.compilacion;

import ide.consola.ConsolaSalida;

import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corre el compilador JER (`java -cp build JERCompiler <archivo>`) en la consola del sistema que
 * esta en la pestana Salida: el comando y su salida se ven ahi como en una terminal, y al terminar
 * se entrega el resultado. Los callbacks se llaman en el hilo de Swing.
 */
public class EjecutorCompilador {
    /** "... JERCompiler archivo" o "... JERCompiler \"archivo con espacios\"" escrito a mano en la consola. */
    private static final Pattern PATRON_COMANDO = Pattern.compile("\\bJERCompiler\\s+(?:\"([^\"]+)\"|(\\S+))");

    private final File carpetaBuild;
    private final File raiz;
    private final ConsolaSalida consola;

    /**
     * `carpetaBuild` es la carpeta con las clases del compilador (build/), `raiz` la carpeta donde
     * esta parada la consola (la raiz del proyecto).
     */
    public EjecutorCompilador(File carpetaBuild, File raiz, ConsolaSalida consola) {
        this.carpetaBuild = carpetaBuild;
        this.raiz = raiz;
        this.consola = consola;
    }

    /** `java -cp build JERCompiler pruebas\x.txt`, con rutas relativas a la raiz cuando se puede. */
    private String comando(File archivo) {
        return "java -cp " + ruta(carpetaBuild) + " JERCompiler " + ruta(archivo);
    }

    private String ruta(File f) {
        String r;
        try {
            r = raiz.getCanonicalFile().toPath().relativize(f.getCanonicalFile().toPath()).toString();
        } catch (IOException | IllegalArgumentException e) {
            r = f.getAbsolutePath();
        }
        return r.contains(" ") ? "\"" + r + "\"" : r;
    }

    /**
     * Hace que los comandos `java ... JERCompiler <archivo>` escritos a mano en la consola tambien
     * entreguen su resultado: `alIniciar` recibe el archivo al dar Enter, antes de mandar el comando
     * (si devuelve false, el comando no se ejecuta); `alTerminar`, el archivo y el resultado.
     */
    public void escucharComandosManuales(Predicate<File> alIniciar, BiConsumer<File, ResultadoCompilacion> alTerminar) {
        consola.setDetectorComandos(comando -> {
            Matcher m = PATRON_COMANDO.matcher(comando);
            if (!m.find()) return null;
            String nombre = m.group(1) != null ? m.group(1) : m.group(2);
            File archivo = new File(nombre);
            if (!archivo.isAbsolute()) archivo = new File(raiz, nombre);
            try {
                archivo = archivo.getCanonicalFile();
            } catch (IOException e) {
                archivo = archivo.getAbsoluteFile();
            }
            File compilado = archivo;
            long inicio = System.currentTimeMillis();
            if (!alIniciar.test(compilado)) return ConsolaSalida.NO_EJECUTAR;
            return (salida, codigo, duracion, cancelado) -> {
                String texto = cancelado ? salida + "El compilador no respondió a tiempo; se canceló.\n" : salida;
                alTerminar.accept(compilado, ResultadoCompilacion.analizar(texto, inicio, cancelado ? -1 : codigo, duracion));
            };
        });
    }

    /** Tiempo limite para compilar.bat (jjtree + javacc + javac tardan mas que compilar un archivo). */
    private static final int TIMEOUT_COMPILADOR_SEG = 120;

    /**
     * Corre compilar.bat (jjtree -> javacc -> javac) en la consola. `alTerminar` recibe true si
     * termino bien; `alFallar`, la excepcion si no se pudo usar la consola.
     */
    public void compilarCompilador(Consumer<Boolean> alTerminar, Consumer<Exception> alFallar) {
        File script = new File(raiz, "compilar.bat");
        try {
            consola.ejecutar("call \"" + script.getAbsolutePath() + "\"", TIMEOUT_COMPILADOR_SEG,
                    (salida, codigo, duracion, cancelado) -> alTerminar.accept(!cancelado && codigo == 0));
        } catch (IOException | IllegalStateException e) {
            alFallar.accept(e);
        }
    }

    /**
     * Compila `archivo`. `alTerminar` recibe el resultado (si se paso del tiempo, el codigo es -1 y
     * la salida trae el aviso); `alFallar`, la excepcion si no se pudo usar la consola.
     */
    public void compilar(File archivo, Consumer<ResultadoCompilacion> alTerminar, Consumer<Exception> alFallar) {
        long inicio = System.currentTimeMillis(); // para no mostrar archivos de salida de una compilacion anterior
        try {
            consola.ejecutar(comando(archivo), (salida, codigo, duracion, cancelado) -> {
                String texto = cancelado ? salida + "El compilador no respondió a tiempo; se canceló.\n" : salida;
                alTerminar.accept(ResultadoCompilacion.analizar(texto, inicio, cancelado ? -1 : codigo, duracion));
            });
        } catch (IOException | IllegalStateException e) {
            alFallar.accept(e);
        }
    }
}
