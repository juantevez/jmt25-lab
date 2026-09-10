package com.tevez.lab;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Un PLATFORM thread por tarea. El espejo exacto de VirtualEscenario, cambiando
 * solo el executor.
 *
 * Este escenario esta pensado para FALLAR. Con ulimit -u en 62.918, alrededor de
 * esa cantidad de hilos la JVM tira OutOfMemoryError: unable to create native
 * thread. No es falta de memoria: es el limite de hilos del kernel.
 *
 * Correr con precaucion: puede dejar la maquina pesada unos segundos.
 *   ( ulimit -u 5000; ./run.sh platform-por-tarea 20000 )
 */
public final class PlatformPorTareaEscenario implements Escenario {

    @Override
    public String nombre() {
        return "platform-por-tarea";
    }

    @Override
    public String descripcion() {
        return "Un platform thread por tarea (esperar OutOfMemoryError)";
    }

    @Override
    public void ejecutar(int tareas, Metricas metricas) throws Exception {
        CountDownLatch listo = new CountDownLatch(tareas);
        int creados = 0;

        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            for (int i = 0; i < tareas; i++) {
                final long id = i;
                final long t0 = System.nanoTime();
                try {
                    executor.submit(() -> {
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
                    creados++;
                } catch (OutOfMemoryError e) {
                    System.out.printf("%n    >> OutOfMemoryError tras %,d hilos: %s%n",
                            creados, e.getMessage());
                    System.out.println("    >> Ese es el techo del kernel, no de la memoria.");
                    // El resto de las tareas no se van a ejecutar
                    for (int j = i; j < tareas; j++) {
                        listo.countDown();
                    }
                    break;
                }
            }
            listo.await();
        }
        System.out.printf("    hilos creados: %,d de %,d tareas%n", creados, tareas);
    }
}
