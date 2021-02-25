This yet unnamed child is a fork of [buildviz](https://github.com/cburgmer/buildviz).
It only retains the logic necessary to read build data from different build
servers:

- Jenkins
- Go.cd
- Teamcity

Currently it just dumps all the data into the current working directory under
`./data`.

## Goal

Provide a simple way to download build and test metadata from CI/CD servers in a
JSON structure for analysis. The format shall aim for consistency across build
server implementations.

## Howto

Have a build server running, e.g. an example Jenkins shipped with this repo:

    $ ./examples/jenkins/run.sh start

Now start the sync pointing to this instance

    $ ./lein run -m buildviz.jenkins.sync http://localhost:8080
    Jenkins http://localhost:8080
    Finding all builds for syncing (starting from 2019-06-05T22:00:00.000Z)...
    Syncing [==================================================] 100% 11/11

You'll receive the last two months of builds (use `--help` for options). Voila:

    $ ls data/*
    data/Deploy:
    1.json  2.json  3.json

    data/SmokeTest:
    1.json  2.json

    data/Test:
    1.json  2.json  3.json  4.json  5.json  6.json

Each file representing a build with the given job name and build id:

    $ cat data/Deploy/2.json | jp
    {
      "start": 1565121687863,
      "end": 1565121702840,
      "outcome": "fail",
      "inputs": [
        {
          "revision": "edfb6bd410a6fd4a5814e29acc7b2bad99c37ecc",
          "sourceId": "TEST_GIT_COMMIT"
        }
      ],
      "triggeredBy": [
        {
          "jobName": "Test",
          "buildId": "4"
        }
      ]
    }

If you happen to receive JUnit XML test results, you can inspect them via

    $ ./lein run -m buildviz.data.junit-xml data/Example\ %3a%3a\ test/4.xml
    Another Test Suite
      Nested Test Suite
        some.class.A Test	0.002	:fail
        some.class.Some Test	0.005	:error
        some.class.Another Test	0.003	:pass
        some.class.Skipped Test	0.004	:skipped

You can come back later and sync more builds, automatically resuming where you
left off before

    $ ./lein run -m buildviz.jenkins.sync http://localhost:8080
    Jenkins http://localhost:8080
    Finding all builds for syncing (starting from 2019-08-06T20:11:10.460Z)...
    Syncing [==================================================] 100% 1/1

## To do

This fork needs cleaning up:

- Teamcity code needs to be migrated
- Java namespaces
- DEVELOP and examples/ documentation is out of date
- ./go make_release is broken
