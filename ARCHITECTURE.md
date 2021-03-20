# Architecture of build-facts

(Motivated by https://matklad.github.io/2021/02/06/ARCHITECTURE.md.html)

A few design decisions that went into build-facts:

1. We map a very heterogeneous domain onto a simple yet hopefully powerful
   representation. We chose the abstractions of

    1. *jobs* which define how a build server executes a task, and
    2. *builds* representing instances of the *jobs* being run.

2. We assume the build server is used for Continuous Integration (CI) or even
   Continuous Delivery (CD) and so we typically find

    1. chains of *jobs* (also called pipelines), and
    2. *inputs* to *jobs* which determine the jobs outcome and the same inputs
       would under regular conditions lead to the exact same output.

3. Syncing is designed to be resumable, so will favour dropping off once an
   ongoing build is found, so it can be picked up in finalised state later.

4. We rely on the ordering of builds as returned by the build server (newest
   first). Builds are then processed in reverse order as reported. This allows
   us to stop at an ongoing build (see previous decision) but also makes us
   independent of any timestamps that may or may not be present for builds in
   different states.

5. An implementation integrating a build server with build-facts has to fulfill
   a simple interface:

    1. It returns a list of jobs as reported by the build server
    2. For each job the name of the job is returned and as second parameter the
       list of builds.

   For example: `[ ( "job A", ["A's build #2", "A's build #1"] ), ( "job B", ["B's build #1"] ) ]`

   This structure informs the sync job about any (new) builds, and also the jobs
   present in the build server so it can refresh the state if necessary.

   Jobs need to be reported whether builds exist or not so the state file can
   be kept up-to-date with the build server's configuration.

6. Build server specific implementations may return a lazy list so API calls can
   be made only if required by the sync job.

7. Test results are reported inline with the build information, so it is easier
   to handle a build as a single entity.

8. Test results are represented as a simple JSON structure motivated by
   parameters captured by JUnit. We do not propagate nested test suites as
   supported by some systems in favour of keeping a simple, uniform structure.
