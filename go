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
    echo "Testing GoCD sync"
    "${SCRIPT_DIR}/test/smoke/test_gocd.sh"
    echo "Testing Jenkins sync"
    "${SCRIPT_DIR}/test/smoke/test_jenkins.sh"
    echo "Testing TeamCity sync"
    "${SCRIPT_DIR}/test/smoke/test_teamcity.sh"
    echo "Testing Concourse sync"
    "${SCRIPT_DIR}/test/smoke/test_concourse.sh"
}

goal_test() {
    goal_lint
    goal_test_unit
    goal_test_smoke
}

goal_make_release() {
    local NEW_VERSION=$1
    local OLD_VERSION_ESCAPED

    if [ -z "$NEW_VERSION" ]; then
        echo "Provide a new version number"
        exit 1
    fi

    (
        cd "$SCRIPT_DIR"

        OLD_VERSION_ESCAPED=$(git tag --sort=-version:refname | head -1 | sed 's/\./\\./')

        sed -i "" "s/$OLD_VERSION_ESCAPED/$NEW_VERSION/g" README.md
        sed -i "" "s/$OLD_VERSION_ESCAPED/$NEW_VERSION/g" examples/README.md
        sed -i "" "s/build-facts \"$OLD_VERSION_ESCAPED\"/build-facts \"$NEW_VERSION\"/" project.clj

        git add README.md project.clj
        git commit -m "Bump version"
        git show
        git tag "$NEW_VERSION"
        echo
        echo "You now want to"
        echo "$ git push origin master --tags"
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
