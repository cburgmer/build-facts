#!/usr/bin/env bash
set -eo pipefail

readonly SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

readonly TMP_LOG="/tmp/run.$$.log"
readonly BASE_URL="http://localhost:8080"

wait_for_server() {
    local url=$1
    echo -n " waiting for ${url}"
    until curl --output /dev/null --silent --head --fail "$url"; do
        printf '.'
        sleep 5
    done
}

announce() {
    local text="$1"
    echo -ne "\033[1;30m"
    echo -n "$text"
    echo -ne "\033[0m"
}

hint_at_logs() {
    # shellcheck disable=SC2181
    if [[ "$?" -ne 0 && -f "$TMP_LOG" ]]; then
        echo
        echo "Logs are in ${TMP_LOG}"
    else
        rm -f "$TMP_LOG"
    fi
}

docker_compose() {
    (
        cd "$SCRIPT_DIR"
        docker-compose "$@"
    )
}

container_exists() {
    if [[ -z $(docker_compose ps -q) ]]; then
        return 1
    else
        return 0
    fi
}

provision_container() {
    docker_compose up --no-start
}

start_server() {
    announce "Starting docker image"
    docker_compose up -d &>> "$TMP_LOG"

    wait_for_server "$BASE_URL"
    echo " done"
}

has_started_builds() {
    "$fly_bin" -t buildviz builds | grep started > /dev/null
}

wait_for_pipeline_to_be_schedulable() {
    sleep 10
    until ! has_started_builds ; do
        printf '.'
        sleep 5
    done
}

provision_pipeline() {
    local fly_bin="/tmp/fly.$$"
    local os_name
    os_name="$(uname -s)"
    {
        curl -vL "${BASE_URL}/api/v1/cli?arch=amd64&platform=${os_name}" -o "$fly_bin"
        chmod a+x "$fly_bin"

        "$fly_bin" -t buildviz login -c "$BASE_URL" -u user -p password

        "$fly_bin" -t buildviz set-pipeline -p anotherpipeline -c anotherpipeline.yml -n
        "$fly_bin" -t buildviz unpause-pipeline -p anotherpipeline
        "$fly_bin" -t buildviz unpause-job -j anotherpipeline/hello-world
        "$fly_bin" -t buildviz trigger-job -j anotherpipeline/hello-world

        "$fly_bin" -t buildviz set-pipeline -p pipeline -c pipeline.yml -n
        "$fly_bin" -t buildviz unpause-pipeline -p pipeline
        "$fly_bin" -t buildviz unpause-job -j pipeline/build
        "$fly_bin" -t buildviz unpause-job -j pipeline/deploy
        "$fly_bin" -t buildviz unpause-job -j pipeline/smoketest
    } &>> "$TMP_LOG"

    for run in 1 2 3 4 5; do
        announce "Triggering build run ${run}"
        "$fly_bin" -t buildviz trigger-job -j pipeline/build &>> "$TMP_LOG"
        wait_for_pipeline_to_be_schedulable
        echo
    done

    rm "$fly_bin" &>> "$TMP_LOG"
}

goal_start() {
    if ! container_exists; then
        announce "Provisioning docker image"
        echo
        provision_container
        start_server
        provision_pipeline
    else
        start_server
    fi
}

goal_stop() {
    announce "Stopping docker image"
    docker_compose stop &>> "$TMP_LOG"
    echo " done"
}

goal_destroy() {
    announce "Destroying docker container"
    docker_compose down &>> "$TMP_LOG"
    echo " done"
}

goal_purge() {
    announce "Purging docker images"
    docker images -q concourse/concourse | xargs docker rmi &>> "$TMP_LOG"
    docker images -q postgres | xargs docker rmi &>> "$TMP_LOG"
    echo " done"
}

main() {
    trap hint_at_logs EXIT

    if type -t "goal_$1" &>/dev/null; then
        "goal_$1"
    else
        echo "usage: $0 (start|stop|destroy|purge)"
    fi
}

main "$@"
