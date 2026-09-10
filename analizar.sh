#!/usr/bin/env bash
# Analisis de grabaciones JFR.
#
#   ./analizar.sh recordings/virtual-*.jfr           -> analizador Java del proyecto
#   ./analizar.sh --cli recordings/virtual-*.jfr     -> con el CLI 'jfr' del JDK
#   ./analizar.sh --comparar recordings/*.jfr        -> tabla comparativa
#
# El analizador Java (com.tevez.lab.JfrAnalisis) usa jdk.jfr.consumer, la misma
# API que JMC por debajo. Ventaja sobre el CLI: entiende los eventos propios del
# lab y saca percentiles.

set -euo pipefail

JAR="target/jmt25-lab.jar"
MODO="java"

if [[ "${1:-}" == "--cli" ]]; then MODO="cli"; shift; fi
if [[ "${1:-}" == "--comparar" ]]; then MODO="comparar"; shift; fi

if [[ $# -eq 0 ]]; then
  echo "uso: ./analizar.sh [--cli|--comparar] <archivo.jfr> [...]"
  exit 1
fi

case "$MODO" in

  java)
    [[ -f "$JAR" ]] || mvn -q clean package
    exec java --enable-preview -cp "$JAR" com.tevez.lab.JfrAnalisis "$@"
    ;;

  comparar)
    printf "%-28s %12s %12s %10s %10s\n" "grabacion" "virt.threads" "hilos SO" "pinning" "tareas"
    printf "%s\n" "--------------------------------------------------------------------------------"
    for REC in "$@"; do
      VT=$(jfr summary "$REC" 2>/dev/null | awk '/VirtualThreadStart/ {print $2}' | head -1)
      OS=$(jfr summary "$REC" 2>/dev/null | awk '/jdk.ThreadStart/ {print $2}' | head -1)
      PIN=$(jfr summary "$REC" 2>/dev/null | awk '/VirtualThreadPinned/ {print $2}' | head -1)
      TAR=$(jfr summary "$REC" 2>/dev/null | awk '/com.tevez.lab.Tarea/ {print $2}' | head -1)
      printf "%-28s %12s %12s %10s %10s\n" \
        "$(basename "$REC")" "${VT:-0}" "${OS:-0}" "${PIN:-0}" "${TAR:-0}"
    done
    ;;

  cli)
    for REC in "$@"; do
      echo "=========================================================="
      echo " $REC"
      echo "=========================================================="
      jfr summary "$REC"

      echo
      echo "---- Pinning (deberia estar vacio en Java 25) ----"
      jfr print --events jdk.VirtualThreadPinned "$REC" | head -80

      echo
      echo "---- Tareas del lab (muestra) ----"
      jfr print --events com.tevez.lab.Tarea "$REC" | head -40

      echo
      echo "---- Ventanas de escenario ----"
      jfr print --events com.tevez.lab.Escenario "$REC"

      echo
      echo "---- Submit fallidos (saturacion del scheduler) ----"
      jfr print --events jdk.VirtualThreadSubmitFailed "$REC" | head -40
      echo
    done
    ;;
esac
