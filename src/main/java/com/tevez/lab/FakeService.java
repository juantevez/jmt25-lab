package com.tevez.lab;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simula un backend remoto (DB, HTTP, broker) que bloquea el hilo llamador.
 *
 * Usamos Thread.sleep() a proposito: es el caso canonico de I/O bloqueante.
 * Con virtual threads la JVM lo intercepta y desmonta el hilo del carrier;
 * con platform threads el hilo del SO queda ocupado sin hacer nada.
 */
public final class FakeService {

    /** Latencia base de la "red" en milisegundos. */
    private static final int LATENCIA_BASE_MS = 80;

    /** Jitter aleatorio, para que las latencias no salgan todas identicas. */
    private static final int JITTER_MS = 40;

    private static final AtomicLong LLAMADAS = new AtomicLong();

    private FakeService() {
    }

    /**
     * Llamada I/O-bound. El hilo se bloquea, no consume CPU.
     */
    public static String consultar(String recurso, long id) throws InterruptedException {
        LLAMADAS.incrementAndGet();
        int espera = LATENCIA_BASE_MS + ThreadLocalRandom.current().nextInt(JITTER_MS);
        Thread.sleep(espera);
        return recurso + "#" + id + " (" + espera + "ms)";
    }

    /**
     * Variante que falla de forma deterministica, para probar la cancelacion
     * en cascada de StructuredTaskScope.
     */
    public static String consultarInestable(String recurso, long id) throws InterruptedException {
        String r = consultar(recurso, id);
        if (id % 1000 == 999) {
            throw new IllegalStateException("fallo simulado en " + recurso + "#" + id);
        }
        return r;
    }

    /**
     * Trabajo CPU-bound, para demostrar que ahi los virtual threads NO ayudan.
     */
    public static long calcular(long semilla) {
        long acc = semilla;
        for (int i = 0; i < 2_000_000; i++) {
            acc = acc * 6364136223846793005L + 1442695040888963407L;
            acc ^= (acc >>> 33);
        }
        return acc;
    }

    public static long llamadasTotales() {
        return LLAMADAS.get();
    }

    public static void resetContador() {
        LLAMADAS.set(0);
    }
}
