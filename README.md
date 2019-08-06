This yet unnamed child is a fork of [buildviz](https://github.com/cburgmer/buildviz).
It only retains the logic necessary to read build data from different build
servers:

- Jenkins
- Go.cd
- Teamcity

Currently it just dumps all the data into the current working directory under
`./data`.

## Howto

Have a build server running, e.g. an example Jenkins shipped with this repo:

    $ cd examples/jenkins
    $ ./run.sh start

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

    $ cat data/Test/6.json | jp
    {
      "start": 1565122270460,
      "end": 1565122272939,
      "outcome": "fail",
      "inputs": [
        {
          "revision": "edfb6bd410a6fd4a5814e29acc7b2bad99c37ecc",
          "sourceId": "https://github.com/cburgmer/buildviz.git"
        }
      ]
    }

You can come back later and sync more builds, automatically resuming where you
left off before

    $ ./lein run -m buildviz.jenkins.sync http://localhost:8080
    Jenkins http://localhost:8080
    Finding all builds for syncing (starting from 2019-08-06T20:11:10.460Z)...
    Syncing [==================================================] 100% 1/1

## To do

This fork needs cleaning up:

- Go.cd and Teamcity code needs to be migrated
- Integration tests are broken
- Java namespaces
- DEVELOP and examples/ documentation is out of date
