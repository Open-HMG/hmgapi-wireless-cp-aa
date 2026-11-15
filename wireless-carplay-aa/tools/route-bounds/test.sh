#!/bin/sh
# Pure host bounds checks; outputs stay in ignored storage.
set -e
cd "$(dirname "$0")/../../.."
python3 tools/palisade/test_iap2_route_trace.py --output private/test-route-trace
python3 tools/palisade/test_iap2_route_normalize.py --output private/test-route-normalize
