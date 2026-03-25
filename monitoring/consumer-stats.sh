#!/bin/bash
# Tails the consumer-v3 log and extracts ConsumerMetrics output.
# Usage: LOG_FILE=/path/to/consumer.log ./consumer-stats.sh
#        or pipe directly: java -jar consumer-v3.jar 2>&1 | ./consumer-stats.sh

LOG_FILE="${LOG_FILE:-}"

if [ -n "$LOG_FILE" ]; then
  echo "Tailing $LOG_FILE for ConsumerMetrics..."
  tail -f "$LOG_FILE" | grep --line-buffered -E "(ConsumerMetrics|DB inserts|Throughput|circuit)"
else
  echo "Reading from stdin (pipe consumer output here)..."
  grep --line-buffered -E "(ConsumerMetrics|DB inserts|Throughput|circuit)"
fi