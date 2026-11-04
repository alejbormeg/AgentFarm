#!/usr/bin/env bash
# The registration token arrives on stdin, so it is in no process environment a job could read.
set -euo pipefail
read -r token
./config.sh --unattended --ephemeral --disableupdate --url "https://github.com/$REPOSITORY" --token "$token" \
  --name "agentfarm-local-$(hostname)" --labels agentfarm-local --no-default-labels --replace --work /home/runner/_work >/dev/null
unset token
exec ./run.sh
