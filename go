#!/bin/bash
set -e

readonly SCRIPT_DIR=$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd)

goal_lint() {
    find "$SCRIPT_DIR" -name "*.sh" -exec shellcheck {} +
    shellcheck "$SCRIPT_DIR"/go
}

goal_test_unit() {
    # shellcheck disable=SC1010
    "${SCRIPT_DIR}/lein" do clean, test
}

goal_test_smoke() {
    echo
    echo "Running smoke test against recorded endpoints."
    echo "If this fails you might have changed how the endpoints are requested, and might want to record from scratch."
    echo "Testing buildviz.go.sync"
    "${SCRIPT_DIR}/test/smoke/test_gocd.sh"
    echo "Testing buildviz.jenkins.sync"
    "${SCRIPT_DIR}/test/smoke/test_jenkins.sh"
    echo "Testing buildviz.teamcity.sync"
    "${SCRIPT_DIR}/test/smoke/test_teamcity.sh"
}

goal_test() {
    goal_lint
    goal_test_unit
    goal_test_smoke
}

goal_make_release() {
    local NEW_VERSION=$1
    local OLD_VERSION

    if [ -z "$NEW_VERSION" ]; then
        echo "Provide a new version number"
        exit 1
    fi

    (
        cd "$SCRIPT_DIR"

        OLD_VERSION=$(git tag --sort=-version:refname | head -1)

        sed -i "" "s/$OLD_VERSION/$NEW_VERSION/g" README.md
        sed -i "" "s/buildviz \"$OLD_VERSION\"/buildviz \"$NEW_VERSION\"/" project.clj

        # shellcheck disable=SC1010
        ./lein do deps # force package-lock.json to be updated now

        git add README.md project.clj resources/public/package-lock.json
        git commit -m "Bump version"
        git show
        git tag "$NEW_VERSION"
        echo
        echo "You now want to"
        echo "$ git push origin master --tags"
        echo "$ ./go publish_docker ${NEW_VERSION} # once travis has built the jar"
    )
}

print_usage() {
    local GOALS
    GOALS=$(set | grep -e "^goal_" | sed "s/^goal_\(.*\)().*/\1/" | xargs | sed "s/ / | /g")
    echo "Usage: $0 [ ${GOALS} ]"
}

main() {
    local GOAL

    if [[ -z "$1" ]]; then
        GOAL="test"
    else
        GOAL="$1"
        shift
    fi

    if ! type -t "goal_${GOAL}" &>/dev/null; then
        print_usage
        exit 1
    fi

    "goal_${GOAL}" "$@"
}

main "$@"
