(function (timespanSelection, graphFactory, runtimes, dataSource) {
    var timespanSelector = timespanSelection.create(timespanSelection.timespans.twoMonths),
        graph = graphFactory.create({
            id: 'jobRuntime',
            headline: "Job runtime",
            description: "<h3>Is the pipeline getting faster? Has a job gotten considerably slower?</h3><i>Color: job</i>",
            csvUrl: "/jobruntime.csv",
            noDataReason: "provided <code>start</code> and <code>end</code> times for your builds over at least two consecutive days",
            widgets: [timespanSelector.widget]
        });

    timespanSelector.load(function (selectedTimespan) {
        var fromTimestamp = timespanSelection.startingFromTimestamp(selectedTimespan);

        graph.loading();

        dataSource.loadCSV('/jobruntime?from=' + fromTimestamp, function (data) {
            graph.loaded();

            runtimes.renderData(data, graph.svg);
        });
    });
}(timespanSelection, graphFactory, runtimes, dataSource));
