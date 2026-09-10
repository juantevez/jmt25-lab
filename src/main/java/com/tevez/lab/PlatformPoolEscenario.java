package com.tevez.lab;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * El modelo clasico: pool fijo de platform threads.
 *
 * Cada tarea toma un hilo del SO y lo mantiene bloqueado durante toda la
 * latencia de I/O. Con 200 hilos y 80ms de latencia el techo teorico es
 * 200 / 0.08 = 2.500 tareas/s, sin importar cuantos cores tengas.
 */
public final class PlatformPoolEscenario implements Escenario {

    private final int tamanoPool;

    public PlatformPoolEscenario(int tamanoPool) {
        this.tamanoPool = tamanoPool;
    }

    @Override
    public String nombre() {
        return "platform";
    }

    @Override
    public String descripcion() {
        return "Pool fijo de " + tamanoPool + " platform threads (modelo tradicional)";
    }

    @Override
    public void ejecutar(int tareas, Metricas metricas) throws Exception {
        CountDownLatch listo = new CountDownLatch(tareas);

        try (ExecutorService pool = Executors.newFixedThreadPool(tamanoPool)) {
            for (int i = 0; i < tareas; i++) {
                final long id = i;
                long t0 = System.nanoTime();
                pool.submit(() -> {
                    boolean fallo = false;
                    try {
                        FakeService.consultar("perfil", id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fallo = true;
                    } catch (RuntimeException e) {
                        fallo = true;
                    } finally {
                        metricas.registrarLatencia(System.nanoTime() - t0, fallo);
                        listo.countDown();
                    }
                });
            }
            listo.await();
        }
    }
}
