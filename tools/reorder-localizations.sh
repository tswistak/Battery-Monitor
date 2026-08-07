#!/usr/bin/env bash

set -eo pipefail

cd ./reorder-localizations
./reorder.sh
node ./format-strings.mjs