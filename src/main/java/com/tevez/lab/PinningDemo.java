package com.tevez.lab;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

/**
 * Demo del cambio mas practico de Java 24/25: JEP 491.
 *
 * Hasta Java 23, un virtual thread que se bloqueaba DENTRO de un bloque
 * synchronized quedaba "pinned" al carrier thread: no podia desmontarse.
 * Con pocos carriers (uno por core) y muchos hilos pinneados, la app se
 * congelaba. Por eso la recomendacion era migrar todo a ReentrantLock.
 *
 * Desde 24/25 eso ya no pasa: el monitor se libera al desmontar. Correr esta
 * demo en un JDK 21 y en un JDK 25 y comparar el tiempo total es la forma mas
 * clara de ver el cambio.
 *
 *   JDK 21: ~ (tareas / cores) * 100ms      -> serializado por los carriers
 *   JDK 25: ~ 100ms + overhead              -> todo en paralelo
 *
 * Para detectar pinning residual (JNI, casos limite):
 *   java -Djdk.tracePinnedThreads=full ...
 * o mejor, el evento jdk.VirtualThreadPinned en JFR.
 */
public final class PinningDemo {

    private static final Object CANDADO = new Object();

    private PinningDemo() {
    }

    public static void ejecutar(int tareas) throws Exception {
        System.out.println("\n--- Pinning con synchronized (JEP 491) ---\n");
        System.out.println("JDK en uso: " + Runtime.version());
        System.out.println("Cores/carriers: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Tareas: " + tareas + ", cada una duerme 100ms dentro de synchronized\n");

        CountDownLatch listo = new CountDownLatch(tareas);
        long t0 = System.nanoTime();

        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tareas; i++) {
                ex.submit(() -> {
                    try {
                        bloqueanteEnSynchronized();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        listo.countDown();
                    }
                });
            }
            listo.await();
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("Total: %d ms%n", ms);
        System.out.println(ms < 1_000
                ? ">> Sin pinning: los hilos cedieron el carrier. Java 24+."
                : ">> Con pinning: los hilos quedaron atados al carrier. Java <= 23.");
    }

    /**
     * OJO: aca hay DOS cosas distintas mezcladas a proposito, no confundirlas.
     *
     * El objeto CANDADO es compartido, asi que esto igual serializa por
     * exclusion mutua, que es lo que uno pide al escribir synchronized.
     * Para aislar SOLO el efecto del pinning usamos un candado por tarea.
     */
    private static void bloqueanteEnSynchronized() throws InterruptedException {
        Object candadoLocal = new Object();   // sin contencion real
        synchronized (candadoLocal) {
            Thread.sleep(100);                // I/O bloqueante dentro del monitor
        }
    }

    /** Version con contencion real, para contrastar. */
    static void conContencionReal() throws InterruptedException {
        synchronized (CANDADO) {
            Thread.sleep(100);
        }
    }
}
