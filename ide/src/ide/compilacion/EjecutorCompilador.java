package ide.compilacion;

import javax.swing.SwingWorker;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Corre el compilador JER (`java -cp build JERCompiler <archivo>`) en un proceso aparte y en segundo
 * plano, para que la ventana no se congele. Los callbacks se llaman en el hilo de Swing.
 */
public class EjecutorCompilador {
    private static final int TIMEOUT_SEG = 30;

    private final File carpetaBuild;

    /** `carpetaBuild` es la carpeta con las clases del compilador (build/ en la raiz del proyecto). */
    public EjecutorCompilador(File carpetaBuild) {
        this.carpetaBuild = carpetaBuild;
    }

    /**
     * Compila `archivo`. `alTerminar` recibe el resultado (si se paso del tiempo, la salida es el aviso
     * de cancelacion); `alFallar`, la excepcion si no se pudo ni lanzar el proceso.
     */
    public void compilar(File archivo, Consumer<ResultadoCompilacion> alTerminar, Consumer<Exception> alFallar) {
        long inicio = System.currentTimeMillis(); // para no mostrar archivos de salida de una compilacion anterior
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                File tmp = File.createTempFile("jer", ".out");
                try {
                    Process p = new ProcessBuilder(javaBin, "-cp", carpetaBuild.getAbsolutePath(), "JERCompiler", archivo.getAbsolutePath())
                            .redirectErrorStream(true).redirectOutput(tmp).start();
                    if (!p.waitFor(TIMEOUT_SEG, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        return "El compilador no respondio en " + TIMEOUT_SEG + " s; se cancelo.";
                    }
                    return new String(Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8);
                } finally {
                    tmp.delete();
                }
            }

            @Override protected void done() {
                try {
                    alTerminar.accept(ResultadoCompilacion.analizar(get(), inicio));
                } catch (Exception e) {
                    alFallar.accept(e);
                }
            }
        }.execute();
    }
}
