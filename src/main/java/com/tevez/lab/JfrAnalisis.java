package com.tevez.lab;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Lee un .jfr y saca las conclusiones que nos importan, sin abrir JMC ni depender
 * del CLI 'jfr'.
 *
 * La API jdk.jfr.consumer es la misma que usa JMC por debajo. Que esto sea codigo
 * y no un script es lo que te permite meterlo en un test de regresion: por
 * ejemplo, fallar el build si aparece UN solo evento jdk.VirtualThreadPinned.
 *
 * Uso independiente:
 *   java --enable-preview -cp target/jmt25-lab.jar \
 *        com.tevez.lab.JfrAnalisis recordings/virtual-20260909-101500.jfr
 */
public final class JfrAnalisis {

    private JfrAnalisis() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("uso: JfrAnalisis <archivo.jfr> [<archivo.jfr> ...]");
            System.exit(1);
        }
        for (String a : args) {
            analizar(Path.of(a)).imprimir();
        }
    }

    public static Resultado analizar(Path jfr) throws Exception {
        Map<String, Long> conteo = new TreeMap<>();
        List<Pinning> pinnings = new ArrayList<>();
        List<Long> latenciasNs = new ArrayList<>();
        Map<String, Integer> tareasPorEscenario = new LinkedHashMap<>();

        long virtualStart = 0;
        long virtualEnd = 0;
        long osThreadStart = 0;
        long submitFailed = 0;
        long errores = 0;
        double cpuMax = 0;

        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String tipo = e.getEventType().getName();
                conteo.merge(tipo, 1L, Long::sum);

                switch (tipo) {
                    case "jdk.VirtualThreadStart" -> virtualStart++;
                    case "jdk.VirtualThreadEnd" -> virtualEnd++;
                    case "jdk.ThreadStart" -> osThreadStart++;
                    case "jdk.VirtualThreadSubmitFailed" -> submitFailed++;

                    case "jdk.VirtualThreadPinned" ->
                            pinnings.add(new Pinning(e.getDuration(), cima(e.getStackTrace(), 6)));

                    case "com.tevez.lab.Tarea" -> {
                        latenciasNs.add(e.getLong("latencia"));
                        String esc = e.getString("escenario");
                        tareasPorEscenario.merge(esc, 1, Integer::sum);
                        if (e.getBoolean("error")) {
                            errores++;
                        }
                    }

                    case "jdk.CPULoad" -> {
                        double v = e.getDouble("machineTotal");
                        if (v > cpuMax) {
                            cpuMax = v;
                        }
                    }

                    default -> { }
                }
            }
        }

        latenciasNs.sort(Comparator.naturalOrder());

        return new Resultado(
                jfr,
                conteo,
                virtualStart,
                virtualEnd,
                osThreadStart,
                submitFailed,
                errores,
                cpuMax,
                pinnings,
                latenciasNs,
                tareasPorEscenario);
    }

    private static String cima(RecordedStackTrace st, int frames) {
        if (st == null) {
            return "(sin stacktrace)";
        }
        StringBuilder sb = new StringBuilder();
        List<RecordedFrame> lista = st.getFrames();
        for (int i = 0; i < Math.min(frames, lista.size()); i++) {
            RecordedFrame f = lista.get(i);
            sb.append("        ")
              .append(f.getMethod().getType().getName())
              .append('.')
              .append(f.getMethod().getName())
              .append(':')
              .append(f.getLineNumber())
              .append('\n');
        }
        return sb.toString();
    }

    public record Pinning(Duration duracion, String stack) {
    }

    public record Resultado(
            Path archivo,
            Map<String, Long> conteoEventos,
            long virtualThreadsCreados,
            long virtualThreadsTerminados,
            long hilosSoCreados,
            long submitFallidos,
            long errores,
            double cpuMaquinaMax,
            List<Pinning> pinnings,
            List<Long> latenciasNs,
            Map<String, Integer> tareasPorEscenario) {

        public void imprimir() {
            System.out.println("\n==========================================================");
            System.out.println(" JFR: " + archivo.getFileName());
            System.out.println("==========================================================");

            System.out.printf("""
                     Virtual threads creados ... %,d
                     Virtual threads cerrados .. %,d
                     Hilos del SO creados ...... %,d   <-- la comparacion que importa
                     Submit fallidos ........... %,d
                     Errores de tarea .......... %,d
                     CPU maquina (pico) ........ %.1f%%
                    %n""",
                    virtualThreadsCreados, virtualThreadsTerminados,
                    hilosSoCreados, submitFallidos, errores,
                    cpuMaquinaMax * 100);

            if (!tareasPorEscenario.isEmpty()) {
                System.out.println(" Tareas por escenario:");
                tareasPorEscenario.forEach((k, v) ->
                        System.out.printf("   %-20s %,d%n", k, v));
                System.out.println();
            }

            imprimirLatencias();
            imprimirPinning();
            imprimirTopEventos();
        }

        private void imprimirLatencias() {
            if (latenciasNs.isEmpty()) {
                return;
            }
            System.out.printf("""
                     Latencia segun JFR (n=%,d)
                       p50 ... %d ms
                       p95 ... %d ms
                       p99 ... %d ms
                       max ... %d ms
                    %n""",
                    latenciasNs.size(),
                    pMs(50), pMs(95), pMs(99), pMs(100));
        }

        private long pMs(int p) {
            if (latenciasNs.isEmpty()) {
                return 0;
            }
            int idx = (int) Math.ceil(p / 100.0 * latenciasNs.size()) - 1;
            idx = Math.max(0, Math.min(idx, latenciasNs.size() - 1));
            return latenciasNs.get(idx) / 1_000_000;
        }

        private void imprimirPinning() {
            System.out.println(" ---- Pinning ----");
            if (pinnings.isEmpty()) {
                System.out.println("   Ningun evento jdk.VirtualThreadPinned.");
                System.out.println("   En Java 25 esto es lo esperado: JEP 491 saco el pinning");
                System.out.println("   de synchronized. Si corres lo mismo en JDK 21 vas a ver");
                System.out.println("   miles de eventos aca.\n");
                return;
            }
            long totalMs = pinnings.stream()
                    .mapToLong(p -> p.duracion().toMillis())
                    .sum();
            System.out.printf("   %,d eventos, %,d ms acumulados%n", pinnings.size(), totalMs);
            System.out.println("   Los 5 mas largos:\n");
            pinnings.stream()
                    .sorted(Comparator.comparing(Pinning::duracion).reversed())
                    .limit(5)
                    .forEach(p -> {
                        System.out.printf("     %d ms%n", p.duracion().toMillis());
                        System.out.print(p.stack());
                        System.out.println();
                    });
        }

        private void imprimirTopEventos() {
            System.out.println(" ---- Eventos mas frecuentes ----");
            conteoEventos.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(12)
                    .forEach(en -> System.out.printf("   %-45s %,10d%n", en.getKey(), en.getValue()));
            System.out.println();
        }

        /** Para usar en un test: falla si hubo pinning. */
        public void exigirSinPinning() {
            if (!pinnings.isEmpty()) {
                throw new AssertionError("Se detectaron " + pinnings.size()
                        + " eventos de pinning en " + archivo);
            }
        }
    }
}
