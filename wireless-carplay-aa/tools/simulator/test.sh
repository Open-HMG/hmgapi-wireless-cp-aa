#!/bin/sh
# Replay the production bridge and independently decode its display calls.
set -e
cd "$(dirname "$0")/../../.."
python3 -m unittest discover -s tools/palisade/simulator -p 'test_*.py' -v
