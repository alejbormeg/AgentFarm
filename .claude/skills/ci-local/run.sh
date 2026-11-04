#!/usr/bin/env bash
# A GitHub Actions runner with its own Docker, apart from this machine's. Usage: run.sh up|down|loop
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repository="$(gh repo view --json nameWithOwner --jq .nameWithOwner)" || exit 1
dind=agentfarm-ci-dind
state="${XDG_STATE_HOME:-$HOME/.local/state}/agentfarm-ci"
volumes=(agentfarm-ci-docker agentfarm-ci-tmp agentfarm-ci-work agentfarm-ci-m2)

case "${1:-}" in
  up)
    set -e
    docker build -q -t agentfarm-ci-runner:local "$here" >/dev/null
    for volume in "${volumes[@]}"; do docker volume create "$volume" >/dev/null; done
    docker run --rm -v agentfarm-ci-tmp:/t -v agentfarm-ci-work:/w -v agentfarm-ci-m2:/m ubuntu:24.04 \
      bash -c 'chmod 1777 /t && chown 1001:1001 /w /m'
    # Same /tmp and work paths in both: the jobs bind-mount them into containers this daemon starts.
    docker inspect "$dind" >/dev/null 2>&1 || docker run -d --name "$dind" --privileged --restart unless-stopped \
      -e DOCKER_TLS_CERTDIR= -v agentfarm-ci-docker:/var/lib/docker \
      -v agentfarm-ci-tmp:/tmp -v agentfarm-ci-work:/home/runner/_work \
      docker:27.5-dind dockerd --host=tcp://127.0.0.1:2375 >/dev/null
    mkdir -p "$state"; rm -f "$state/stop"
    nohup bash "$here/run.sh" loop >> "$state/runner.log" 2>&1 &
    echo $! > "$state/loop.pid"
    echo "Runner en marcha. Registro: $state/runner.log"
    ;;
  loop)
    # One new container per job.
    while [[ ! -e "$state/stop" ]]; do
      token="$(gh api -X POST "repos/$repository/actions/runners/registration-token" --jq .token)" || { sleep 30; continue; }
      printf '%s\n' "$token" | docker run -i --rm --name agentfarm-ci-runner --network "container:$dind" --shm-size 1g \
        -e DOCKER_HOST=tcp://127.0.0.1:2375 -e "REPOSITORY=$repository" \
        -v agentfarm-ci-tmp:/tmp -v agentfarm-ci-work:/home/runner/_work -v agentfarm-ci-m2:/home/runner/.m2 \
        agentfarm-ci-runner:local
      unset token
      sleep 3
    done
    ;;
  down)
    mkdir -p "$state"; touch "$state/stop"
    docker rm -f agentfarm-ci-runner "$dind" >/dev/null 2>&1
    [[ -f "$state/loop.pid" ]] && kill "$(cat "$state/loop.pid")" 2>/dev/null
    for id in $(gh api "repos/$repository/actions/runners" --jq '.runners[] | select(.name | startswith("agentfarm-local")) | .id'); do
      gh api -X DELETE "repos/$repository/actions/runners/$id"
    done
    docker volume rm "${volumes[@]}" >/dev/null 2>&1
    echo "Runner parado y dado de baja."
    ;;
  *) echo "Uso: run.sh up|down" >&2; exit 2 ;;
esac
