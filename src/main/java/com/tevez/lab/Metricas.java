package com.tevez.lab;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Recoleccion de metricas del escenario: latencias, throughput, hilos del SO y heap.
 *
 * Ademas de acumular en memoria, emite un EventoTarea a JFR por cada tarea. Las
 * dos vias miden lo mismo a proposito: si los percentiles del reporte y los que
 * saca JfrAnalisis del .jfr no coinciden, algo esta mal instrumentado.
 *
 * OJO: ThreadMXBean solo cuenta PLATFORM threads. Un escenario con 50.000 virtual
 * threads va a reportar ~10 hilos. Eso NO es un bug, es exactamente el punto de
 * la comparacion: los virtual threads no consumen hilos del sistema operativo.
 * Para contar los virtual threads de verdad hace falta JFR (jdk.VirtualThreadStart).
 */
public final class Metricas {

    private static final int MAX_MUESTRAS = 200_000;

    private final AtomicIntegerArray latenciasMs = new AtomicIntegerArray(MAX_MUESTRAS);
    private final AtomicInteger cursor = new AtomicInteger();
    private final LongAdder muestras = new LongAdder();
    private final LongAdder errores = new LongAdder();

    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    /** Nombre del escenario en curso, para etiquetar los eventos JFR. */
    private volatile String escenarioActual = "?";

    private long nanosInicio;
    private long nanosFin;
    private int hilosSoInicio;
    private int hilosSoPico;
    private long heapPicoBytes;

    public void iniciar(String escenario) {
        this.escenarioActual = escenario;
        threadBean.resetPeakThreadCount();
        hilosSoInicio = threadBean.getThreadCount();
        hilosSoPico = hilosSoInicio;
        heapPicoBytes = 0;
        nanosInicio = System.nanoTime();
    }

    public void finalizar() {
        nanosFin = System.nanoTime();
        int pico = threadBean.getPeakThreadCount();
        if (pico > hilosSoPico) {
            hilosSoPico = pico;
        }
    }

    /** Se llama desde un platform thread aparte cada 100ms. */
    public void muestrearRecursos() {
        int hilos = threadBean.getThreadCount();
        if (hilos > hilosSoPico) {
            hilosSoPico = hilos;
        }
        long heap = memoryBean.getHeapMemoryUsage().getUsed();
        if (heap > heapPicoBytes) {
            heapPicoBytes = heap;
        }
    }

    /**
     * Registra la latencia de una tarea, en memoria y en JFR.
     *
     * El commit() de JFR es barato (unos ns si el evento esta deshabilitado, y
     * la escritura va a un buffer por hilo), asi que no distorsiona la medicion.
     * isEnabled() evita incluso construir el objeto cuando no hay grabacion.
     */
    public void registrarLatencia(long nanos, boolean error) {
        int pos = cursor.getAndIncrement();
        if (pos < MAX_MUESTRAS) {
            latenciasMs.set(pos, (int) (nanos / 1_000_000));
            muestras.increment();
        }
        if (error) {
            errores.increment();
        }

        EventoTarea evento = new EventoTarea();
        if (evento.isEnabled()) {
            evento.escenario = escenarioActual;
            evento.latencia = nanos;
            evento.virtual = Thread.currentThread().isVirtual();
            evento.error = error;
            evento.hilo = Thread.currentThread().toString();
            evento.commit();
        }
    }

    public void registrarLatencia(long nanos) {
        registrarLatencia(nanos, false);
    }

    public void registrarError() {
        errores.increment();
    }

    public int hilosSoPico() {
        return hilosSoPico;
    }

    public Reporte reporte(String escenario, int tareas) {
        int n = (int) Math.min(muestras.sum(), MAX_MUESTRAS);
        int[] copia = new int[n];
        for (int i = 0; i < n; i++) {
            copia[i] = latenciasMs.get(i);
        }
        Arrays.sort(copia);

        Duration total = Duration.ofNanos(nanosFin - nanosInicio);
        double segundos = total.toNanos() / 1_000_000_000.0;

        return new Reporte(
                escenario,
                tareas,
                total,
                segundos > 0 ? tareas / segundos : 0,
                percentil(copia, 50),
                percentil(copia, 95),
                percentil(copia, 99),
                copia.length > 0 ? copia[copia.length - 1] : 0,
                hilosSoInicio,
                hilosSoPico,
                heapPicoBytes / (1024 * 1024),
                errores.sum());
    }

    private static int percentil(int[] ordenado, int p) {
        if (ordenado.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * ordenado.length) - 1;
        return ordenado[Math.max(0, Math.min(idx, ordenado.length - 1))];
    }

    public record Reporte(
            String escenario,
            int tareas,
            Duration duracionTotal,
            double throughputPorSegundo,
            int p50Ms,
            int p95Ms,
            int p99Ms,
            int maxMs,
            int hilosSoInicio,
            int hilosSoPico,
            long heapPicoMb,
            long errores) {

        public void imprimir() {
            System.out.printf("""

                    ==========================================================
                     Escenario ......... %s
                     Tareas ............ %,d
                    ----------------------------------------------------------
                     Duracion total .... %.2f s
                     Throughput ........ %,.0f tareas/s
                     Latencia p50 ...... %d ms
                     Latencia p95 ...... %d ms
                     Latencia p99 ...... %d ms
                     Latencia max ...... %d ms
                    ----------------------------------------------------------
                     Hilos SO (inicio) . %d
                     Hilos SO (pico) ... %d      <-- lo interesante
                     Heap pico ......... %d MB
                     Errores ........... %d
                    ==========================================================
                    %n""",
                    escenario, tareas,
                    duracionTotal.toMillis() / 1000.0,
                    throughputPorSegundo,
                    p50Ms, p95Ms, p99Ms, maxMs,
                    hilosSoInicio, hilosSoPico, heapPicoMb, errores);
        }

        public String lineaCsv() {
            return String.format("%s,%d,%d,%.0f,%d,%d,%d,%d,%d,%d,%d",
                    escenario, tareas, duracionTotal.toMillis(), throughputPorSegundo,
                    p50Ms, p95Ms, p99Ms, maxMs, hilosSoPico, heapPicoMb, errores);
        }

        public static String cabeceraCsv() {
            return "escenario,tareas,duracion_ms,throughput_s,p50,p95,p99,max,hilos_so_pico,heap_mb,errores";
        }
    }
}
