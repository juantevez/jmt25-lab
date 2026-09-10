package com.tevez.lab;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

/**
 * Demo de ScopedValue (final en 25, JEP 506) y StableValue (preview, JEP 502).
 *
 * ScopedValue reemplaza a ThreadLocal para propagar contexto de request:
 *   - inmutable
 *   - vida acotada al bloque run()/call(), sin leaks
 *   - se hereda a los subtasks de un StructuredTaskScope SIN copiar el valor
 *
 * StableValue reemplaza al double-checked locking y al holder idiom:
 *   - inicializacion perezosa, thread-safe, una sola vez
 *   - el JIT lo trata como constante (constant folding)
 */
public final class ContextoDemo {

    // ---------- ScopedValue ----------
    public static final ScopedValue<String> TENANT = ScopedValue.newInstance();
    public static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

    // ---------- StableValue ----------
    // Antes esto habria sido un ThreadLocal<SimpleDateFormat> o un
    // synchronized getInstance(). Con un virtual thread por tarea, un
    // ThreadLocal como cache deja de cachear nada: hay un hilo por request.
    private static final StableValue<Configuracion> CONFIG = StableValue.of();

    private ContextoDemo() {
    }

    public static void ejecutar() throws Exception {
        System.out.println("\n--- ScopedValue + StructuredTaskScope ---\n");

        ScopedValue.where(TENANT, "credicoop")
                   .where(TRACE_ID, "trace-abc-123")
                   .run(ContextoDemo::manejarRequest);

        // Fuera del bloque el binding no existe. Nada que limpiar.
        System.out.println("\nFuera del scope -> TENANT.isBound() = " + TENANT.isBound());
        System.out.println("Con default     -> " + TENANT.orElse("(sin tenant)"));

        System.out.println("\n--- StableValue ---\n");
        // Se inicializa una sola vez aunque lo pidan 8 hilos a la vez
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                ex.submit(() -> System.out.println("  config = " + config().nombre()
                        + " desde " + Thread.currentThread()));
            }
        }
    }

    private static void manejarRequest() {
        System.out.println("Capa web    -> tenant=" + TENANT.get() + " trace=" + TRACE_ID.get());
        capaServicio();

        // Los subtasks heredan el binding automaticamente
        //try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessful())) {
        try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
            scope.fork(() -> {
                System.out.println("Subtask A   -> tenant=" + TENANT.get() + " (heredado)");
                return "a";
            });
            scope.fork(() -> {
                System.out.println("Subtask B   -> tenant=" + TENANT.get() + " (heredado)");
                return "b";
            });
            scope.join();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void capaServicio() {
        capaRepositorio();
    }

    private static void capaRepositorio() {
        // 3 frames mas abajo, sin haber pasado el tenant por parametro en ningun lado
        System.out.println("Repositorio -> SELECT ... WHERE tenant = '" + TENANT.get() + "'");
    }

    private static Configuracion config() {
        return CONFIG.orElseSet(ContextoDemo::cargarConfigCara);
    }

    private static Configuracion cargarConfigCara() {
        System.out.println("  >> cargando configuracion (deberia verse UNA sola vez)");
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Configuracion("config-produccion");
    }

    public record Configuracion(String nombre) {
    }
}
