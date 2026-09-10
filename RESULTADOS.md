# Resultados — modelos de concurrencia en Java 25

Mediciones reales sobre `jmt25-lab`. El objetivo no fue producir un benchmark
publicable sino entender dónde está el límite de cada modelo y qué se gana
realmente al migrar a virtual threads.

**Conclusión corta:** virtual threads no ahorran memoria de forma dramática ni
aumentan el throughput cuando la concurrencia está limitada por un recurso
externo. Lo que hacen es sacar un techo duro que impone el kernel, y mover el
cuello de botella a un lugar donde sí podés gestionarlo.

---

## Entorno

| | |
|---|---|
| JDK | 25.0.4.1+8-LTS |
| Cores | 4 |
| Heap | 2048 MB (`-Xms` = `-Xmx`) |
| `ulimit -u` | 62.918 |
| `/proc/sys/kernel/threads-max` | 125.837 |
| Carga simulada | I/O bloqueante, 80–120 ms por llamada |

Todos los escenarios corrieron en la misma JVM, con warmup de 2.000 tareas por
escenario. Ver [Limitaciones](#limitaciones-de-estas-mediciones) antes de citar
cualquier número.

---

## 1. Comparativa base — 20.000 tareas

| escenario | total (s) | tareas/s | p50 (ms) | p99 (ms) | hilos SO | heap (MB) |
|---|---|---|---|---|---|---|
| `platform` (pool 200) | 10,09 | 1.982 | 4.993 | 9.870 | **207** | 11 |
| `virtual` (sin límite) | 0,41 | 48.693 | 114 | 176 | **13** | 37 |
| `virtual-limitado` (sem. 200) | 10,03 | 1.993 | 5.011 | 9.882 | **13** | 45 |
| `structured` (fan-out ×3) | 0,75 | 26.614 | 350 | 456 | 14 | 79 |
| `cpu-virtual` | 40,76 | 491 | 7 | 17 | 14 | 10 |
| `cpu-platform` | 40,42 | 495 | 7 | 17 | 18 | 5 |

### El par que importa

`platform` y `virtual-limitado` dan **el mismo throughput** (1.982 vs 1.993
tareas/s, diferencia bajo el 1%) y las mismas latencias. La única diferencia es
que uno hace que el kernel gestione 207 hilos y el otro 13.

Esto es lo contrario de lo que promete el discurso habitual. Cuando limitás la
concurrencia por el recurso real — el pool de conexiones, el rate limit del
proveedor — el modelo de hilo **no cambia el rendimiento**. Cambia el costo de
sostenerlo.

### El techo del pool fijo

`virtual` sin límite: 48.693 tareas/s contra 1.982. El pool de 200 estaba
dejando 25× de throughput sin usar, y convirtiendo 114 ms de servicio en 5
segundos de cola.

La cuenta es directa: 200 hilos ÷ 0,1 s de latencia = 2.000 tareas/s. El pool no
estaba lento por falta de CPU sino por falta de hilos disponibles.

### El control negativo

`cpu-virtual` y `cpu-platform`: 40,76 vs 40,42 s, 0,8% de diferencia. En trabajo
CPU-bound los virtual threads **no aportan nada** — el cuello son los 4 cores.
Que este empate aparezca valida el resto de la instrumentación.

---

## 2. Escalado — 200.000 tareas

| | 20.000 tareas | 200.000 tareas |
|---|---|---|
| throughput | ~26.000/s | 16.969/s |
| p50 | 180 ms | 3.314 ms |
| heap pico | 77 MB | 408 MB |
| hilos SO | 15 | **15** |
| CPU máquina (pico) | no medida | **100%** |

Los 200.000 virtual threads corrieron sobre los mismos 15 hilos del SO. Pero el
throughput **bajó** y la latencia se multiplicó por 18.

La causa está en el 100% de CPU: con esa cantidad de hilos, montar y desmontar
continuaciones, el scheduling y el GC dejaron de ser despreciables. El límite ya
no es el I/O simulado, son los 4 cores gestionando hilos.

**El techo se movió, no desapareció.** Antes eran los 200 hilos del pool; ahora
es la CPU. La diferencia práctica es que este techo sí lo controlás vos, con un
`Semaphore`.

### Memoria: no se ahorra, se mueve

408 MB de heap para 200.000 hilos en vuelo (~2 KB cada uno), contra 11 MB del
escenario `platform`.

Los stacks de los virtual threads desmontados viven en el **heap**, donde el GC
los ve. Los de los platform threads viven en memoria **nativa**, fuera de lo que
mide `MemoryMXBean`.

Consecuencia para capacity planning: si dimensionaste un contenedor mirando solo
`-Xmx`, migrar a virtual threads puede empujarte contra el límite aunque la
memoria total baje. Hay que medir con `-XX:NativeMemoryTracking=summary` de los
dos lados.

---

## 3. Memoria nativa real (NMT)

Muestras tomadas con `jcmd <pid> VM.native_memory summary` durante el pico de
cada escenario.

| | `virtual` | `platform` (pool 200) |
|---|---|---|
| hilos | 30 | 224 |
| stack reservado | 30.720 KB | 227.328 KB |
| stack **committed** | 1.732 KB | 21.828 KB |
| KB residentes por hilo | ~58 | **~98** |

### Esto corrige un mito

La cifra que se repite en todas las charlas es "1 MB por platform thread". Es el
valor **reservado**: espacio de direcciones virtuales que la JVM le pide al SO.

Lo que realmente ocupa páginas físicas es el **committed**: ~98 KB por hilo. Un
orden de magnitud menos.

La cuenta de "200.000 hilos × 1 MB = 200 GB, imposible" es incorrecta. El costo
residente real sería del orden de 20 GB — mucho, pero no imposible por memoria.

Lo que sí te frena está en la sección siguiente.

---

## 4. El techo duro: el kernel

Escenario `platform-por-tarea` (un platform thread por tarea, sin pool),
ejecutado con `ulimit -u` acotado:

```
[5,744s][warning][os,thread] Failed to start thread "Unknown thread" -
  pthread_create failed (EAGAIN) for attributes: stacksize: 1024k

>> OutOfMemoryError tras 365 hilos:
   unable to create native thread: possibly out of memory or
   process/resource limits reached
```

Heap en ese momento: **9 MB**. No fue falta de memoria. `EAGAIN` de
`pthread_create` es el kernel diciendo que no hay más hilos disponibles.

### Dos detalles que importan en producción

**El límite es por usuario, no por proceso.** Con `ulimit -u 1500` la JVM reventó
a los 365 hilos, no a los 1.500: el resto del límite lo consumía todo lo demás de
la sesión. En un servidor o contenedor compartido, el techo real es siempre más
bajo que el número del `ulimit`.

**El techo lo marca la concurrencia en vuelo, no el total de tareas.** Con
`ulimit -u 5000`, las mismas 20.000 tareas terminaron sin fallar usando 2.579
hilos pico. La cuenta es `latencia × tasa de llegada`, el mismo cálculo que
dimensiona un pool.

### El contraste

| | `platform-por-tarea` | `virtual` |
|---|---|---|
| tareas completadas | 284 de 20.000 | 200.000 de 200.000 |
| resultado | `EAGAIN` del kernel | sin incidentes |
| hilos del SO | 291 (el techo) | 15 |

Este es el argumento real a favor de virtual threads: **hay cargas que
simplemente no podés expresar con platform threads**, sin importar cuánta memoria
tengas.

---

## 5. Verificación con JFR

Corrida `virtual` con 20.000 tareas, grabación programática arrancada después del
warmup.

```
 Virtual threads creados ... 20,000
 Virtual threads cerrados .. 20,000
 Submit fallidos ........... 0
 Latencia p50 .............. 180 ms   (reporte en memoria: 180 ms)
 Latencia p99 .............. 219 ms   (reporte en memoria: 219 ms)
```

Las dos vías de medición — el `AtomicIntegerArray` en memoria y el `.jfr` —
coinciden exactamente. Eso descarta que la instrumentación esté inventando
resultados.

**El conteo real de hilos solo lo da JFR.** `ThreadMXBean` reporta 15 porque solo
ve platform threads; `jdk.VirtualThreadStart` reporta 20.000. VisualVM tiene la
misma limitación: su pestaña Threads muestra los carriers del ForkJoinPool y nada
más.

### Pinning

Cero eventos de `jdk.VirtualThreadPinned` en las corridas de 20.000.

En la de 200.000 apareció uno, de 7 ms, con este stack:

```
jdk.internal.event.VirtualThreadStartEvent.commit
java.lang.VirtualThread.run:455
```

Es un **falso positivo**: el pinning ocurre en el commit del propio evento de
JFR, no en código de la aplicación. El observador perturbando lo observado.
Aparece a esa escala porque crece la probabilidad de pegarle a ese instante.

Que el resto esté en cero confirma el efecto de JEP 491 (Java 24+): `synchronized`
ya no ata el virtual thread al carrier. La misma corrida en JDK 21 mostraría miles
de eventos.

### Tamaño de la grabación

30 MB para 200.000 tareas, con `jdk.ThreadSleep` en 185.198 eventos pese al
umbral de 500 ms. Que ese umbral se supere es en sí un dato: confirma que los
`sleep` de 80–120 ms estaban tardando segundos por contención de CPU, coherente
con el p50 de 3.314 ms.

Con la configuración por defecto de JFR el archivo habría pesado cientos de MB.
De ahí el evento propio `com.tevez.lab.Tarea` sin stacktrace.

---

## Limitaciones de estas mediciones

Esto es un lab ilustrativo, no un benchmark. Vale para entender el
comportamiento, no para citar cifras absolutas.

**Todos los escenarios comparten JVM.** El JIT desoptimiza y recompila cuando ve
tipos nuevos en los mismos call sites, así que el perfil de un escenario influye
en el siguiente. `structured-fallos` da consistentemente más rápido que
`structured` haciendo el mismo trabajo — es ordenamiento, no un efecto real de la
cancelación en cascada.

Para números defendibles, cada escenario en su propia JVM:

```bash
for e in platform virtual virtual-limitado structured; do ./run.sh $e 200000; done
```

**Las corridas cortas tienen mucha varianza.** El escenario `virtual` con 20.000
tareas dio 20.869, 25.003, 26.061, 42.181 y 48.693 tareas/s en corridas
equivalentes. Con menos de 1 segundo de duración, una pausa de GC mueve el
resultado un 50%. Cualquier cifra citada debería venir de corridas de ≥10 s.

**El escenario CPU-bound necesitaba un sumidero.** En la primera versión, C2
eliminaba el loop entero porque el resultado no se usaba (0,05 s para 20.000
tareas de 2M iteraciones). Para medir cómputo en serio hace falta JMH, no
`System.nanoTime()`.

**El I/O está simulado con `Thread.sleep()`.** Es el caso canónico de bloqueo,
pero un driver real puede comportarse distinto — especialmente si tiene código
nativo, que es el caso que JEP 491 no cubre.

---

## Qué me llevo para producción

**El hilo casi nunca es el cuello.** Si limitás la concurrencia por el recurso
real, platform y virtual dan lo mismo. La migración se justifica por el techo del
kernel y por el modelo de programación, no por rendimiento.

**El `Semaphore` no es opcional.** Virtual threads sacan el límite de hilos y
dejan uno nuevo — CPU, conexiones, rate limits — que hay que gestionar
explícitamente. Sin límite, 200.000 hilos concurrentes degradaron el throughput
un 35%.

**No poolear virtual threads.** Son baratos y desechables: uno por tarea. Para
limitar concurrencia va un `Semaphore`, no el tamaño del pool.

**`ThreadLocal` como caché deja de funcionar.** Con un hilo por tarea no cachea
nada. Migrar a `StableValue` o a un pool explícito de objetos caros.

**Medir memoria de los dos lados.** El heap sube, la nativa baja. Solo el RSS
total (`grep VmRSS /proc/<pid>/status`) dice si el contenedor sobrevive.

**Vigilar `jdk.VirtualThreadPinned` con el stack real.** Es el chequeo que hay que
correr contra los drivers de producción antes de migrar. El código propio ya no
pinea con `synchronized`; las librerías con JNI sí pueden.