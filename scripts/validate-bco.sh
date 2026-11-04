#!/bin/bash
#
# Validate a BioCompute object manifest against the IEEE 2791 schema.
#
# Prerequisites:
#
#   pip install check-jsonschema rfc3987  # rfc3987 enables uri format checks
#
# Usage:
#
#   ./validate-bco.sh <manifest>

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 <manifest>" >&2
    exit 1
fi

schema_dir=$(mktemp -d)
trap 'rm -rf "$schema_dir"' EXIT

# fetch schema with curl since the IEEE host rejects python-requests user agents
for f in 2791object provenance_domain usability_domain description_domain execution_domain parametric_domain io_domain error_domain; do
    curl -sfL -o "$schema_dir/$f.json" "https://w3id.org/ieee/ieee-2791-schema/$f.json"
done

check-jsonschema --base-uri "file://$schema_dir/" --schemafile "$schema_dir/2791object.json" "$1"
