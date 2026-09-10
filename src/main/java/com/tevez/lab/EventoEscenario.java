package com.tevez.lab;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Evento de duracion que envuelve un escenario completo.
 *
 * Sirve para que en la linea de tiempo de JMC veas bloques nombrados
 * ("platform", "virtual", ...) y puedas correlacionar visualmente el pico de
 * hilos del SO o de GC con el escenario que lo produjo. Sin esto, una corrida
 * de "todos" es una sopa indistinguible de 7 fases.
 *
 * Uso tipico de un evento de duracion:
 *   var e = new EventoEscenario();
 *   e.begin();
 *   ... trabajo ...
 *   e.end();
 *   e.commit();
 */
@Name("com.tevez.lab.Escenario")
@Label("Escenario del lab")
@Category({"jmt25-lab"})
@Description("Ventana temporal de un escenario completo, para correlacionar en la timeline")
@StackTrace(false)
public class EventoEscenario extends Event {

    @Label("Escenario")
    public String escenario;

    @Label("Descripcion")
    public String descripcion;

    @Label("Tareas")
    public int tareas;

    @Label("Hilos SO al pico")
    public int hilosSoPico;
}
