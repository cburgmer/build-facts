## Status

This yet unnamed child is a fork of [buildviz](https://github.com/cburgmer/buildviz).
It only retains the logic necessary to read build data from different build
servers.

## Goal

Download build facts from different CI/CD servers in a standard JSON schema.
You then take it from there with your own analytics. See
https://github.com/cburgmer/buildviz for an example on what is possible with a
small set of build properties.

## Features

- Streams standardized build data
- Splunk HEC format
- Resume from previous sync
- Output build data to files
- Supports the following build servers:
  - Jenkins
  - Go.cd
  - TeamCity
  - Concourse

## Build JSON schema

    {
      "jobName": "Deploy",
      "buildId": "21",
      "start": 1451449853542,
      "end": 1451449870555,
      "outcome": "pass", /* or "fail" */
      "inputs": [{
        "revision": "1eadcdd4d35f9a",
        "sourceId": "git@github.com:cburgmer/buildviz.git"
      }],
      "triggeredBy": [{
        "jobName": "Test",
        "buildId": "42"
      }],
      "testResults": [{
        "name": "Test Suite",
        "children": [{
          "classname": "some.class",
          "name": "A Test",
          "runtime": 2,
          "status": "pass"
        }]
      }]
    }

## Howto

Have a build server running, e.g. an example Jenkins shipped with this repo:

    $ ./examples/jenkins/run.sh start

Now start the sync pointing to this instance

    $ ./lein run -m buildviz.main jenkins http://localhost:8080
    Finding all builds for syncing from http://localhost:8080 (starting from 2021-01-06T23:00:00.000Z)...
    {"jobName":"Test","buildId":"1","start":1615151319678,"end":1615151342243,"outcome":"pass","inputs":[{"revision":"9bb731de4f4372a8c3b4e53e7d70cd729b32419c","sourceId":"https://github.com/cburgmer/buildviz.git"}]}
    {"jobName":"Test","buildId":"2","start":1615151342348,"end":1615151344854,"outcome":"pass","inputs":[{"revision":"9bb731de4f4372a8c3b4e53e7d70cd729b32419c","sourceId":"https://github.com/cburgmer/buildviz.git"}]}
    {"jobName":"Deploy","buildId":"1","start":1615151349657,"end":1615151361672,"outcome":"pass","inputs":[{"revision":"9bb731de4f4372a8c3b4e53e7d70cd729b32419c","sourceId":"TEST_GIT_COMMIT"}],"triggeredBy":[{"jobName":"Test","buildId":"1"},{"jobName":"Test","buildId":"2"}]}
    [...]

If you happen to receive JUnit XML test results, you can inspect them via

    $ ./lein run -m buildviz.data.junit-xml data/Example\ %3a%3a\ test/4.xml
    Another Test Suite
      Nested Test Suite
        some.class.A Test	0.002	:fail
        some.class.Some Test	0.005	:error
        some.class.Another Test	0.003	:pass
        some.class.Skipped Test	0.004	:skipped

## To do

This fork needs cleaning up:

- Teamcity syncs test results as JSON with wrong file ending
- Remove code reference to buildviz to avoid confusion
- DEVELOP and examples/ documentation is out of date
- ./go make_release is broken
- Move over https://github.com/cburgmer/buildviz/wiki/CI-tool-integration
- Don't sync past the earliest running build
