package com.tevez.lab;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Control negativo del experimento.
 *
 * Trabajo CPU-bound puro. Aca los virtual threads NO dan ninguna ventaja: el
 * cuello de botella son los cores, no los hilos. Si el resultado de este
 * escenario es parecido con "virtual" y con "platform", el lab esta bien hecho.
 *
 * Correlo con: cpu-virtual  y  cpu-platform
 */
public final class CpuEscenario implements Escenario {

    private final boolean virtual;

    public CpuEscenario(boolean virtual) {
        this.virtual = virtual;
    }

    private static final java.util.concurrent.atomic.AtomicLong SUMIDERO =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public String nombre() {
        return virtual ? "cpu-virtual" : "cpu-platform";
    }

    @Override
    public String descripcion() {
        return "Trabajo CPU-bound sobre "
                + (virtual ? "virtual threads" : "pool = nro de cores")
                + " (esperar EMPATE)";
    }

    @Override
    public void ejecutar(int tareas, Metricas metricas) throws Exception {
        CountDownLatch listo = new CountDownLatch(tareas);

        ExecutorService executor = virtual
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        try (executor) {
            for (int i = 0; i < tareas; i++) {
                final long semilla = i;
                executor.submit(() -> {
                    long t0 = System.nanoTime();
                    try {
                        //FakeService.calcular(semilla);
                        SUMIDERO.addAndGet(FakeService.calcular(semilla));
                    } finally {
                        metricas.registrarLatencia(System.nanoTime() - t0);
                        listo.countDown();
                    }
                });
            }
            listo.await();
            System.out.println("    (sumidero: " + SUMIDERO.get() + ")");
        }
    }
}
