package com.tevez.lab;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * Structured concurrency: cada "request" abre un scope y hace fan-out a 3 backends.
 *
 * API de Java 25 (JEP 505, quinto preview). Cambio importante respecto de 21-23:
 * ya no existen ni el constructor publico ni ShutdownOnFailure / ShutdownOnSuccess.
 * Ahora es StructuredTaskScope.open(...) + un Joiner.
 *
 * Lo que se gana:
 *  - si una subtarea falla, las otras se cancelan solas
 *  - el try-with-resources garantiza que no queda nada corriendo al salir del metodo
 *  - el arbol padre/hijo aparece en el thread dump JSON (jcmd Thread.dump_to_file -format=json)
 */
public final class StructuredEscenario implements Escenario {

    private static final int SUBTAREAS_POR_REQUEST = 3;

    private final boolean conFallos;

    public StructuredEscenario(boolean conFallos) {
        this.conFallos = conFallos;
    }

    @Override
    public String nombre() {
        return conFallos ? "structured-fallos" : "structured";
    }

    @Override
    public String descripcion() {
        return "StructuredTaskScope: fan-out a " + SUBTAREAS_POR_REQUEST
                + " backends por request" + (conFallos ? " (con fallos simulados)" : "");
    }

    @Override
    public void ejecutar(int tareas, Metricas metricas) throws Exception {
        CountDownLatch listo = new CountDownLatch(tareas);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tareas; i++) {
                final long id = i;
                executor.submit(() -> {
                    long t0 = System.nanoTime();
                    boolean fallo = false;
                    try {
                        agregarDatos(id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fallo = true;
                    } catch (Exception e) {
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

    /**
     * El corazon del ejemplo: 3 llamadas concurrentes, una unidad de fallo,
     * timeout global, y todo confinado al bloque.
     */
    private Vista agregarDatos(long id) throws Exception {
        try (var scope = StructuredTaskScope.open(
                Joiner.<Object>awaitAllSuccessfulOrThrow(),
                cf -> cf.withName("request-" + id)
                        .withTimeout(Duration.ofSeconds(5)))) {

            Subtask<String> perfil = scope.fork(() ->
                    conFallos ? FakeService.consultarInestable("perfil", id)
                              : FakeService.consultar("perfil", id));

            Subtask<String> ordenes = scope.fork(() ->
                    FakeService.consultar("ordenes", id));

            Subtask<String> saldo = scope.fork(() ->
                    FakeService.consultar("saldo", id));

            // Espera a las tres. Si una lanza, cancela las otras dos y propaga.
            scope.join();

            return new Vista(perfil.get(), ordenes.get(), saldo.get());
        }
    }

    public record Vista(String perfil, String ordenes, String saldo) {
    }
}
