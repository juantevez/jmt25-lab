package com.tevez.lab;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/**
 * Evento propio del lab, emitido una vez por tarea.
 *
 * Por que un evento custom y no confiar en jdk.ThreadSleep: con 20.000 tareas
 * durmiendo 80ms, ThreadSleep genera 20.000 eventos con stacktrace y ahoga la
 * grabacion. Este evento pesa unos pocos bytes, no lleva stacktrace, y ademas
 * carga el nombre del escenario, asi que podes filtrar y comparar dentro del
 * mismo .jfr.
 *
 * Se ve en JMC bajo la categoria "jmt25-lab", y se puede consultar con:
 *   jfr print --events com.tevez.lab.Tarea grabacion.jfr
 *   jfr summary grabacion.jfr
 */
@Name("com.tevez.lab.Tarea")
@Label("Tarea del lab")
@Category({"jmt25-lab"})
@Description("Una unidad de trabajo completada, con su latencia y el modelo de hilo usado")
@StackTrace(false)
@Enabled(true)
public class EventoTarea extends Event {

    @Label("Escenario")
    public String escenario;

    @Label("Latencia")
    @Timespan(Timespan.NANOSECONDS)
    public long latencia;

    @Label("Virtual thread")
    @Description("true si corrio sobre un virtual thread")
    public boolean virtual;

    @Label("Fallo")
    public boolean error;

    @Label("Hilo")
    @Description("Nombre o id del hilo; los virtual threads no tienen nombre por defecto")
    public String hilo;
}
