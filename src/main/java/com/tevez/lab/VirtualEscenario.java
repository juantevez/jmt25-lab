package com.tevez.lab;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Un virtual thread por tarea.
 *
 * El codigo es identico al del pool de platform threads: bloqueante, secuencial,
 * con try/catch normal. Lo unico que cambia es el executor. Esa es toda la gracia.
 *
 * El Semaphore opcional muestra como se limita la concurrencia contra un recurso
 * escaso (ej. 30 conexiones de HikariCP) SIN volver a dimensionar un pool de hilos.
 */
public final class VirtualEscenario implements Escenario {

    /** 0 = sin limite. */
    private final int limiteConcurrencia;

    public VirtualEscenario(int limiteConcurrencia) {
        this.limiteConcurrencia = limiteConcurrencia;
    }

    @Override
    public String nombre() {
        return limiteConcurrencia > 0 ? "virtual-limitado" : "virtual";
    }

    @Override
    public String descripcion() {
        return limiteConcurrencia > 0
                ? "Un virtual thread por tarea, con Semaphore(" + limiteConcurrencia + ")"
                : "Un virtual thread por tarea, sin limite";
    }

    @Override
    public void ejecutar(int tareas, Metricas metricas) throws Exception {
        Semaphore permisos = limiteConcurrencia > 0 ? new Semaphore(limiteConcurrencia) : null;
        CountDownLatch listo = new CountDownLatch(tareas);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tareas; i++) {
                final long id = i;
                executor.submit(() -> {
                    long t0 = System.nanoTime();
                    boolean fallo = false;
                    try {
                        if (permisos != null) {
                            permisos.acquire();
                        }
                        try {
                            FakeService.consultar("perfil", id);
                        } finally {
                            if (permisos != null) {
                                permisos.release();
                            }
                        }
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
