package com.tevez.lab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {

    private static final int TAREAS_POR_DEFECTO = 20_000;
    private static final int POOL_PLATFORM = 200;
    private static final int LIMITE_SEMAFORO = 200;

    /** Se activa con -Djfr=true o con JFR=1 ./run.sh */
    private static final boolean JFR_ACTIVO =
            Boolean.parseBoolean(System.getProperty("jfr", "false"));

    /** Analizar el .jfr automaticamente al terminar cada escenario. */
    private static final boolean JFR_ANALIZAR =
            Boolean.parseBoolean(System.getProperty("jfr.analizar", "true"));

    public static void main(String[] args) throws Exception {
        Map<String, Escenario> registro = new LinkedHashMap<>();
        registrar(registro, new PlatformPoolEscenario(POOL_PLATFORM));
        registrar(registro, new VirtualEscenario(0));
        registrar(registro, new VirtualEscenario(LIMITE_SEMAFORO));
        registrar(registro, new StructuredEscenario(false));
        registrar(registro, new StructuredEscenario(true));
        registrar(registro, new CpuEscenario(true));
        registrar(registro, new CpuEscenario(false));
        registrar(registro, new PlatformPorTareaEscenario());

        String comando = args.length > 0 ? args[0] : "todos";
        int tareas = args.length > 1 ? Integer.parseInt(args[1]) : TAREAS_POR_DEFECTO;

        banner(tareas);

        switch (comando) {
            case "contexto" -> {
                ContextoDemo.ejecutar();
                return;
            }
            case "pinning" -> {
                correrPinning(Math.min(tareas, 2_000));
                return;
            }
            case "listar" -> {
                registro.values().forEach(e ->
                        System.out.printf("  %-20s %s%n", e.nombre(), e.descripcion()));
                return;
            }
            default -> { /* sigue abajo */ }
        }

        List<Escenario> aCorrer = new ArrayList<>();
        if ("todos".equals(comando)) {
            aCorrer.addAll(registro.values());
        } else {
            Escenario e = registro.get(comando);
            if (e == null) {
                System.err.println("Escenario desconocido: " + comando);
                System.err.println("Opciones: " + String.join(", ", registro.keySet())
                        + ", todos, contexto, pinning, listar");
                System.exit(1);
            }
            aCorrer.add(e);
        }

        List<Metricas.Reporte> reportes = new ArrayList<>();
        List<Path> grabaciones = new ArrayList<>();

        for (Escenario escenario : aCorrer) {
            Resultado r = correr(escenario, tareas);
            reportes.add(r.reporte());
            if (r.grabacion() != null) {
                grabaciones.add(r.grabacion());
            }
            // Pausa entre escenarios: deja que VisualVM/JFR muestren el valle
            // y que el GC limpie antes de la siguiente medicion.
            System.gc();
            Thread.sleep(3_000);
        }

        resumen(reportes);
        guardarCsv(reportes);

        if (!grabaciones.isEmpty()) {
            System.out.println("\nGrabaciones JFR generadas:");
            grabaciones.forEach(p -> System.out.println("  " + p.toAbsolutePath()));
            System.out.println("\nPara reanalizarlas despues:");
            System.out.println("  java -cp target/jmt25-lab.jar com.tevez.lab.JfrAnalisis "
                    + grabaciones.getFirst());
        }
    }

    private record Resultado(Metricas.Reporte reporte, Path grabacion) {
    }

    private static Resultado correr(Escenario escenario, int tareas) throws Exception {
        System.out.printf(">>> %s :: %s%n", escenario.nombre(), escenario.descripcion());
        System.out.println("    (esperando 5s para que enganches el profiler...)");
        Thread.sleep(5_000);

        FakeService.resetContador();
        Metricas metricas = new Metricas();

        // Muestreador de recursos en un platform thread daemon: tiene que seguir
        // vivo aunque los virtual threads se desmonten.
        Thread muestreador = Thread.ofPlatform()
                .daemon()
                .name("muestreador")
                .start(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        metricas.muestrearRecursos();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });

        // Evento de duracion que envuelve todo el escenario, para verlo como un
        // bloque nombrado en la timeline de JMC.
        EventoEscenario ventana = new EventoEscenario();
        ventana.escenario = escenario.nombre();
        ventana.descripcion = escenario.descripcion();
        ventana.tareas = tareas;
        ventana.begin();

        // Warmup: mismo codigo, metricas descartadas. Sin esto, el primer escenario
        // paga la compilacion JIT y el ultimo se beneficia de ella.
        int tareasWarmup = Math.min(tareas / 10, 2_000);
        System.out.println("    warmup: " + tareasWarmup + " tareas...");
        escenario.ejecutar(tareasWarmup, new Metricas());
        System.gc();
        Thread.sleep(1_000);

        // recien ahora arranca la grabacion
        JfrControl jfr = JFR_ACTIVO
                ? JfrControl.iniciar(escenario.nombre())
                : JfrControl.desactivado();

        metricas.iniciar(escenario.nombre());
        escenario.ejecutar(tareas, metricas);
        metricas.finalizar();

        ventana.hilosSoPico = metricas.hilosSoPico();
        ventana.end();
        ventana.commit();

        muestreador.interrupt();
        Path grabacion = jfr.detener();

        Metricas.Reporte reporte = metricas.reporte(escenario.nombre(), tareas);
        reporte.imprimir();
        System.out.printf("    llamadas al backend simulado: %,d%n", FakeService.llamadasTotales());

        if (grabacion != null && JFR_ANALIZAR) {
            try {
                JfrAnalisis.analizar(grabacion).imprimir();
            } catch (Exception e) {
                System.err.println("    no se pudo analizar el .jfr: " + e.getMessage());
            }
        }

        return new Resultado(reporte, grabacion);
    }

    private static void correrPinning(int tareas) throws Exception {
        JfrControl jfr = JFR_ACTIVO
                ? JfrControl.iniciar("pinning")
                : JfrControl.desactivado();

        PinningDemo.ejecutar(tareas);

        Path grabacion = jfr.detener();
        if (grabacion != null && JFR_ANALIZAR) {
            var r = JfrAnalisis.analizar(grabacion);
            r.imprimir();
            System.out.println("""
                    Lectura del resultado:
                      - lista vacia de pinning  -> JDK 24+, el monitor se libera al desmontar
                      - miles de eventos aca    -> JDK <= 23, cada synchronized ata el carrier
                    """);
        }
    }

    private static void registrar(Map<String, Escenario> m, Escenario e) {
        m.put(e.nombre(), e);
    }

    private static void banner(int tareas) {
        System.out.printf("""
                ==========================================================
                 jmt25-lab  -  modelos de concurrencia en Java 25
                ==========================================================
                 JDK ........... %s
                 Cores ......... %d
                 Heap max ...... %d MB
                 Tareas ........ %,d
                 JFR ........... %s
                 PID ........... %d      <-- para engancharlo desde VisualVM
                ==========================================================
                %n""",
                Runtime.version(),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                tareas,
                JFR_ACTIVO ? "activo (un .jfr por escenario)" : "desactivado (-Djfr=true)",
                ProcessHandle.current().pid());
    }

    private static void resumen(List<Metricas.Reporte> reportes) {
        if (reportes.size() < 2) {
            return;
        }
        System.out.println("\n=================== RESUMEN ===================");
        System.out.printf("%-20s %10s %10s %8s %8s %10s %8s%n",
                "escenario", "total(s)", "tareas/s", "p50", "p99", "hilos SO", "heap MB");
        for (var r : reportes) {
            System.out.printf("%-20s %10.2f %10.0f %8d %8d %10d %8d%n",
                    r.escenario(),
                    r.duracionTotal().toMillis() / 1000.0,
                    r.throughputPorSegundo(),
                    r.p50Ms(), r.p99Ms(), r.hilosSoPico(), r.heapPicoMb());
        }
        System.out.println("===============================================\n");
    }

    private static void guardarCsv(List<Metricas.Reporte> reportes) {
        try {
            Path salida = Path.of("resultados.csv");
            List<String> lineas = new ArrayList<>();
            lineas.add(Metricas.Reporte.cabeceraCsv());
            reportes.forEach(r -> lineas.add(r.lineaCsv()));
            Files.write(salida, lineas);
            System.out.println("Resultados en " + salida.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("No se pudo escribir el CSV: " + e.getMessage());
        }
    }
}
