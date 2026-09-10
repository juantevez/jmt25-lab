# jmt25-lab

Comparativa medible de los modelos de concurrencia en **Java 25**: pool de platform
threads, virtual threads, structured concurrency, más demos de `ScopedValue`,
`StableValue` y el fin del pinning con `synchronized`.

## Requisitos

- JDK 25 (`sdk install java 25-tem`)
- Maven 3.9+
- Opcional: VisualVM 2.2+, JDK Mission Control 9+

## Compilar y correr

```bash
chmod +x run.sh analizar.sh
mvn clean package

./run.sh                    # todos los escenarios, 20.000 tareas
./run.sh virtual 100000     # solo virtual threads, 100.000 tareas
./run.sh listar             # ver escenarios disponibles
./run.sh contexto           # demo ScopedValue + StableValue
./run.sh pinning 2000       # demo JEP 491
```

Escenarios:

| nombre | qué hace |
|---|---|
| `platform` | pool fijo de 200 platform threads |
| `virtual` | un virtual thread por tarea, sin límite |
| `virtual-limitado` | virtual threads + `Semaphore(200)` |
| `structured` | `StructuredTaskScope`, fan-out a 3 backends por request |
| `structured-fallos` | ídem, con fallos simulados para ver la cancelación en cascada |
| `cpu-virtual` / `cpu-platform` | control negativo: CPU-bound, debe dar empate |

Cada escenario espera 5 segundos antes de arrancar para que enganches el profiler,
e imprime el PID en el banner inicial.

## Qué esperar

Con 20.000 tareas de 80-120 ms de latencia simulada, en una máquina de 8 cores:

| escenario | duración | hilos SO pico | comentario |
|---|---|---|---|
| `platform` | ~9-10 s | ~215 | techo de 200/0.1 = 2.000 tareas/s |
| `virtual` | ~1-2 s | ~15 | limitado por la latencia, no por los hilos |
| `virtual-limitado` | ~9-10 s | ~15 | mismo throughput que el pool, con 15 hilos del SO |
| `cpu-virtual` vs `cpu-platform` | casi igual | — | los virtual threads no compran nada acá |

La comparación `platform` vs `virtual-limitado` es la más didáctica: **mismo
throughput, un orden de magnitud menos de hilos del sistema operativo**. Lo que
limita no es el hilo, es el recurso remoto.

## Profiling con VisualVM

**Limitación que hay que tener clara de entrada:** VisualVM no ve los virtual
threads. Su pestaña Threads y el sampler se apoyan en `ThreadMXBean`, que solo
enumera platform threads. Vas a ver los ~8 carriers del `ForkJoinPool` y nada más.

Aun así sirve, y bien, para tres cosas:

1. **El contraste visual.** Corré `./run.sh platform 20000` y mirá el gráfico de
   hilos: 200 líneas apiladas, casi todas en *Park*. Después `./run.sh virtual 20000`:
   ~8 líneas. Esa imagen vale más que cualquier explicación.
2. **Memoria.** Heap, GC, retención. Acá sí ve todo.
3. **CPU del proceso.** Para confirmar que en el escenario I/O-bound la CPU está
   casi ociosa en ambos casos, y que el pool de platform threads no está lento por
   falta de CPU sino por falta de hilos.

Cómo engancharlo:

```bash
# local: aparece solo en el árbol de VisualVM
./run.sh platform 20000

# remoto / contenedor
JMX=1 ./run.sh platform 20000
# VisualVM -> File -> Add JMX Connection -> localhost:9010
```

Instalá también el plugin **Visual GC** (Tools → Plugins), que muestra Eden,
Survivor y Old separados. Con virtual threads vas a ver mucha más presión en
Eden: los stacks de los hilos desmontados viven en el heap.

## Profiling con JFR

JFR está integrado en el código, no solo por flags. Hay tres piezas:

**Eventos propios del lab** (`EventoTarea`, `EventoEscenario`). Uno por tarea, con
escenario, latencia, si corrió sobre virtual thread y si falló. Aparecen en JMC
bajo la categoría `jmt25-lab`.

El motivo de no usar `jdk.ThreadSleep` para esto: con 20.000 tareas durmiendo
~100 ms, ese evento genera 20.000 registros con stacktrace y la grabación pesa
cientos de MB. El evento propio pesa unos bytes, no lleva stacktrace y además
carga el nombre del escenario, así que podés filtrar dentro del mismo `.jfr`. Por
eso en `jmt25.jfc` el umbral de `ThreadSleep` está en 500 ms: ahí solo saltan las
anomalías.

**Grabación programática** (`JfrControl`). Una grabación por escenario, en su
propio archivo, que arranca *después* de los 5 segundos de espera. Así no medís
carga de clases ni warmup de la JVM. Una corrida de `todos` con el flag externo
deja los 7 escenarios pisados en un solo archivo; así quedan separados y se
comparan de a pares.

**Análisis desde Java** (`JfrAnalisis`), con `jdk.jfr.consumer` — la misma API que
usa JMC por debajo. Saca conteo de virtual threads reales, hilos del SO creados,
percentiles y los stacktraces de pinning. Que sea código y no un script es lo que
te permite meterlo en un test de regresión: `Resultado.exigirSinPinning()` falla
si aparece un solo evento de pinning.

```bash
JFR=1 ./run.sh todos 20000        # graba y analiza cada escenario
JFR=1 NOANALISIS=1 ./run.sh       # graba sin imprimir, para revisar en JMC
JFR_FULL=1 ./run.sh virtual       # además, el proceso entero en un solo .jfr
```

Las dos formas conviven a propósito: la programática deja afuera el arranque de
la JVM, la externa lo incluye. Si querés ver la carga de clases y el warmup, usá
`JFR_FULL=1`.

Análisis posterior:

```bash
./analizar.sh recordings/virtual-*.jfr             # analizador Java
./analizar.sh --cli recordings/virtual-*.jfr       # con el CLI jfr del JDK
./analizar.sh --comparar recordings/*.jfr          # tabla comparativa
```

Eventos que importan:

- `com.tevez.lab.Tarea` — latencia por tarea, etiquetada por escenario
- `jdk.VirtualThreadStart` / `End` — cuántos virtual threads se crearon de verdad
  (`ThreadMXBean` no los ve, esta es la única forma de contarlos)
- `jdk.VirtualThreadPinned` — **el que hay que vigilar.** En Java 25 con
  `synchronized` normal debería estar vacío. Si aparece, el stacktrace te dice
  quién: casi siempre JNI o una librería nativa.
- `jdk.VirtualThreadSubmitFailed` — el scheduler no aceptó la tarea, señal de
  saturación
- `jdk.ThreadStart` — cuántos hilos del SO de verdad se crearon

Grabar en caliente sobre un proceso ya corriendo:

```bash
jcmd <pid> JFR.start settings=jmt25.jfc name=vivo
jcmd <pid> JFR.dump name=vivo filename=vivo.jfr
jcmd <pid> JFR.stop name=vivo
```

### Overhead

El `commit()` cuesta unos nanosegundos y escribe a un buffer por hilo, así que no
distorsiona una medición con latencias de 80-120 ms. `isEnabled()` evita incluso
construir el objeto cuando no hay grabación activa. Aun así, si vas a comparar
números finos, corré una vez con `JFR=1` y otra sin: la diferencia debería estar
en el ruido. Si no lo está, el escenario es demasiado corto para medir.

Como control cruzado, el reporte en memoria y el que sale del `.jfr` miden lo
mismo por dos caminos distintos. Si los percentiles no coinciden, hay algo mal
instrumentado.

## Thread dumps que sí muestran virtual threads

El `jstack` de siempre tampoco los ve. El formato JSON de `jcmd` sí, y además
muestra el árbol padre/hijo de los `StructuredTaskScope`:

```bash
jcmd <pid> Thread.dump_to_file -format=json dump.json
jq '.threadDump.threadContainers[] | {container, threadCount: (.threads|length)}' dump.json
```

Corré esto mientras el escenario `structured` está en vuelo: vas a ver un
contenedor por cada scope abierto, con sus tres subtasks adentro.

## Flags útiles

```bash
# forzar pocos carriers para exagerar los efectos de scheduling
-Djdk.virtualThreadScheduler.parallelism=2
-Djdk.virtualThreadScheduler.maxPoolSize=2

# avisar por stderr cuando algo pinea
-Djdk.tracePinnedThreads=full

# headers de 8 bytes en vez de 12 (JEP 519, final en 25)
-XX:+UseCompactObjectHeaders
```

Probá correr el escenario `pinning` con `parallelism=2` en un JDK 21 y en un
JDK 25. La diferencia de tiempo total es la demostración más limpia del JEP 491.

## Nota sobre preview

`StructuredTaskScope` (JEP 505) y `StableValue` (JEP 502) siguen en preview en
Java 25, así que el `--enable-preview` es obligatorio tanto al compilar como al
ejecutar. Ya está puesto en el `pom.xml` y en `run.sh`.

La API de `StructuredTaskScope` **cambió** respecto de los previews de Java 21-23:
desaparecieron el constructor público, `ShutdownOnFailure` y `ShutdownOnSuccess`.
Si copiás código de un tutorial viejo no va a compilar. Ahora es
`StructuredTaskScope.open(Joiner...)`.
