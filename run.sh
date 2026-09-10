#!/usr/bin/env bash
# jmt25-lab - runner
#
#   ./run.sh                     -> todos los escenarios, 20.000 tareas
#   ./run.sh virtual 100000      -> un escenario puntual
#   ./run.sh contexto            -> demo ScopedValue + StableValue
#   ./run.sh pinning 2000        -> demo JEP 491
#   ./run.sh listar              -> lista los escenarios
#
# Variables:
#   JFR=1        graba un .jfr POR ESCENARIO y lo analiza al terminar
#   JFR_FULL=1   ademas graba el proceso entero de punta a punta en un solo .jfr
#   NOANALISIS=1 graba pero no imprime el analisis (para revisarlo despues con JMC)
#   JMX=1        abre el puerto 9010 para VisualVM remoto
#   HEAP=2g      tamano de heap (default 2g)

set -euo pipefail

ESCENARIO="${1:-todos}"
TAREAS="${2:-20000}"
HEAP="${HEAP:-2g}"
JAR="target/jmt25-lab.jar"

if [[ ! -f "$JAR" ]]; then
  echo ">> compilando..."
  mvn -q clean package
fi

FLAGS=(
  --enable-preview
  -Xms"$HEAP" -Xmx"$HEAP"
  -XX:+UseCompactObjectHeaders          # JEP 519, final en 25
  -Djdk.tracePinnedThreads=full         # avisa por stderr si algo todavia pinea
  -XX:NativeMemoryTracking=summary
)

# Si queres forzar pocos carriers para ver el efecto del pinning mas claro:
# FLAGS+=( -Djdk.virtualThreadScheduler.parallelism=2 -Djdk.virtualThreadScheduler.maxPoolSize=2 )

# ---- JFR programatico: una grabacion por escenario, controlada desde el codigo ----
if [[ "${JFR:-0}" == "1" ]]; then
  mkdir -p recordings
  FLAGS+=( -Djfr=true )
  echo ">> JFR: un .jfr por escenario en ./recordings/"
fi

if [[ "${NOANALISIS:-0}" == "1" ]]; then
  FLAGS+=( -Djfr.analizar=false )
fi

# ---- JFR externo: el proceso entero en un solo archivo, incluyendo el arranque ----
# Sirve para ver la carga de clases y el warmup, que la grabacion programatica
# deliberadamente deja afuera. Las dos pueden convivir.
if [[ "${JFR_FULL:-0}" == "1" ]]; then
  mkdir -p recordings
  STAMP=$(date +%Y%m%d-%H%M%S)
  REC="recordings/proceso-completo-${STAMP}.jfr"
  if [[ -f jmt25.jfc ]]; then
    FLAGS+=( "-XX:StartFlightRecording=settings=jmt25.jfc,filename=${REC},dumponexit=true,name=completa" )
  else
    FLAGS+=( "-XX:StartFlightRecording=settings=profile,filename=${REC},dumponexit=true,name=completa" )
  fi
  echo ">> JFR full: proceso completo en ${REC}"
fi

if [[ "${JMX:-0}" == "1" ]]; then
  FLAGS+=(
    -Dcom.sun.management.jmxremote
    -Dcom.sun.management.jmxremote.port=9010
    -Dcom.sun.management.jmxremote.rmi.port=9010
    -Dcom.sun.management.jmxremote.local.only=false
    -Dcom.sun.management.jmxremote.authenticate=false
    -Dcom.sun.management.jmxremote.ssl=false
    -Djava.rmi.server.hostname=127.0.0.1
  )
  echo ">> JMX en localhost:9010 (sin auth, SOLO para lab local)"
fi

exec java "${FLAGS[@]}" -jar "$JAR" "$ESCENARIO" "$TAREAS"
