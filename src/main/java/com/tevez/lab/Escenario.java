package com.tevez.lab;

public interface Escenario {

    /** Nombre corto, el que se pasa por linea de comandos. */
    String nombre();

    /** Descripcion para el reporte. */
    String descripcion();

    /** Ejecuta {@code tareas} unidades de trabajo y registra las metricas. */
    void ejecutar(int tareas, Metricas metricas) throws Exception;
}
