package com.tevez.lab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/**
 * Control programatico de JFR: una grabacion por escenario, en su propio archivo.
 *
 * Por que hacerlo desde el codigo y no solo con -XX:StartFlightRecording:
 *
 *  1. Con el flag, una corrida de "todos" queda en UN solo .jfr donde los 7
 *     escenarios se pisan. Aca cada uno tiene su archivo y se comparan de a pares.
 *  2. La grabacion arranca DESPUES del warmup y de los 5 segundos de espera,
 *     asi no medis la carga de clases ni la inicializacion de la JVM.
 *  3. Podes activar eventos por escenario. El escenario CPU-bound no necesita
 *     los eventos de virtual threads, y al reves.
 *
 * El flag externo sigue sirviendo para grabar el proceso entero de punta a punta;
 * las dos cosas conviven.
 */
public final class JfrControl implements AutoCloseable {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Path DIRECTORIO = Path.of("recordings");

    /**
     * Eventos que habilitamos siempre, con su threshold.
     *
     * Nota sobre jdk.ThreadSleep: lo dejamos en 500ms a proposito. Con 20.000
     * tareas durmiendo ~100ms, un threshold bajo genera decenas de miles de
     * eventos con stacktrace y la grabacion pesa cientos de MB sin aportar nada
     * que EventoTarea no diga mejor. Con 500ms solo saltan las anomalias.
     */
    private static final Map<String, String> UMBRALES = Map.of(
            "jdk.ThreadPark", "20 ms",
            "jdk.JavaMonitorEnter", "20 ms",
            "jdk.JavaMonitorWait", "20 ms",
            "jdk.ThreadSleep", "500 ms",
            "jdk.VirtualThreadPinned", "1 ms"
    );

    private final Recording recording;
    private final Path archivo;
    private final boolean activo;

    private JfrControl(Recording recording, Path archivo, boolean activo) {
        this.recording = recording;
        this.archivo = archivo;
        this.activo = activo;
    }

    /** Instancia inerte, para cuando el usuario no pidio JFR. */
    public static JfrControl desactivado() {
        return new JfrControl(null, null, false);
    }

    /**
     * Arranca una grabacion para el escenario dado.
     *
     * Intenta partir de la configuracion jmt25.jfc del proyecto; si no la
     * encuentra cae a "profile", que viene con el JDK.
     */
    public static JfrControl iniciar(String escenario) {
        try {
            Files.createDirectories(DIRECTORIO);

            Configuration config = cargarConfiguracion();
            Recording rec = new Recording(config);

            rec.setName("jmt25-" + escenario);
            rec.setToDisk(true);
            rec.setMaxSize(512L * 1024 * 1024);
            rec.setMaxAge(Duration.ofHours(1));
            rec.setDumpOnExit(true);

            // Eventos del lab.
            // El register() explicito importa: enable(String) por nombre solo
            // aplica si el tipo de evento ya esta registrado en JFR, y eso
            // normalmente recien pasa al primer commit(). Sin esto, los eventos
            // de las primeras tareas se pierden.
            FlightRecorder.register(EventoTarea.class);
            FlightRecorder.register(EventoEscenario.class);
            rec.enable("com.tevez.lab.Tarea");
            rec.enable("com.tevez.lab.Escenario");

            // Virtual threads: en las configuraciones estandar del JDK vienen
            // apagados o con threshold alto. Los prendemos explicitamente.
            rec.enable("jdk.VirtualThreadStart").withoutStackTrace();
            rec.enable("jdk.VirtualThreadEnd");
            rec.enable("jdk.VirtualThreadPinned").withStackTrace();
            rec.enable("jdk.VirtualThreadSubmitFailed").withStackTrace();

            UMBRALES.forEach((evento, umbral) -> {
                try {
                    rec.enable(evento).withThreshold(Duration.ofMillis(parsearMs(umbral)));
                } catch (RuntimeException e) {
                    // Un evento inexistente en este JDK no debe tumbar el lab
                    System.err.println("    (evento no disponible: " + evento + ")");
                }
            });

            String nombre = escenario + "-" + LocalDateTime.now().format(STAMP) + ".jfr";
            Path destino = DIRECTORIO.resolve(nombre);
            rec.setDestination(destino);
            rec.start();

            System.out.println("    JFR grabando -> " + destino);
            return new JfrControl(rec, destino, true);

        } catch (Exception e) {
            System.err.println("    no se pudo iniciar JFR: " + e.getMessage());
            return desactivado();
        }
    }

    private static Configuration cargarConfiguracion() throws Exception {
        Path local = Path.of("jmt25.jfc");
        if (Files.exists(local)) {
            return Configuration.create(local);
        }
        System.out.println("    (jmt25.jfc no encontrado, uso la config 'profile' del JDK)");
        return Configuration.getConfiguration("profile");
    }

    private static long parsearMs(String s) {
        return Long.parseLong(s.replace(" ms", "").trim());
    }

    /** Detiene y vuelca la grabacion. Devuelve el archivo, o null si estaba desactivado. */
    public Path detener() {
        if (!activo) {
            return null;
        }
        try {
            recording.stop();
            recording.close();
            long kb = Files.size(archivo) / 1024;
            System.out.printf("    JFR volcado: %s (%,d KB)%n", archivo, kb);
            return archivo;
        } catch (IOException e) {
            System.err.println("    error al volcar JFR: " + e.getMessage());
            return archivo;
        }
    }

    public boolean estaActivo() {
        return activo;
    }

    @Override
    public void close() {
        if (activo && recording.getState() == jdk.jfr.RecordingState.RUNNING) {
            detener();
        }
    }
}
