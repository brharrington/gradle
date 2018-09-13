/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance.results;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import org.gradle.performance.measure.DataSeries;
import org.gradle.performance.measure.Duration;
import org.gradle.performance.util.Git;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingDouble;

public class IndexPageGenerator extends HtmlPageGenerator<ResultsStore> {
    public static final int DANGEROUS_REGRESSION_CONFIDENCE_THRESHOLD = 90;
    private final Set<ScenarioBuildResultData> scenarios;
    private final ResultsStore resultsStore;

    public IndexPageGenerator(ResultsStore resultsStore, File resultJson) {
        this.resultsStore = resultsStore;
        this.scenarios = readBuildResultData(resultJson);
    }

    @VisibleForTesting
    Set<ScenarioBuildResultData> readBuildResultData(File resultJson) {
        try {
            Comparator<ScenarioBuildResultData> comparator = comparing(ScenarioBuildResultData::isBuildFailed).reversed()
                .thenComparing(ScenarioBuildResultData::isSuccessful)
                .thenComparing(comparing(ScenarioBuildResultData::isAboutToRegress).reversed())
                .thenComparing(comparingDouble(ScenarioBuildResultData::getConfidencePercentage).reversed())
                .thenComparing(comparingDouble(ScenarioBuildResultData::getRegressionPercentage).reversed())
                .thenComparing(ScenarioBuildResultData::getScenarioName);

            List<ScenarioBuildResultData> list = new ObjectMapper().readValue(resultJson, new TypeReference<List<ScenarioBuildResultData>>() {
            });
            return list.stream().map(this::queryExecutionData).collect(() -> new TreeSet<>(comparator), TreeSet::add, TreeSet::addAll);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ScenarioBuildResultData queryExecutionData(ScenarioBuildResultData scenario) {
        PerformanceTestHistory history = resultsStore.getTestResults(scenario.getScenarioName(), 3, 2, ResultsStoreHelper.determineChannel());
        List<? extends PerformanceTestExecution> recentExecutions = history.getExecutions();
        List<? extends PerformanceTestExecution> currentCommitExecutions = filterForRequestedCommit(recentExecutions);
        if (currentCommitExecutions.isEmpty()) {
            scenario.setRecentExecutions(recentExecutions.stream().map(this::extractExecutionData).collect(Collectors.toList()));
        } else {
            scenario.setCurrentCommitExecutions(currentCommitExecutions.stream().map(this::extractExecutionData).collect(Collectors.toList()));
        }

        return scenario;
    }

    private ScenarioBuildResultData.ExecutionData extractExecutionData(PerformanceTestExecution performanceTestExecution) {
        List<MeasuredOperationList> nonEmptyExecutions = performanceTestExecution
            .getScenarios()
            .stream()
            .filter(testExecution -> !testExecution.getTotalTime().isEmpty())
            .collect(Collectors.toList());
        if (nonEmptyExecutions.size() > 1) {
            return new ScenarioBuildResultData.ExecutionData(performanceTestExecution.getStartTime(), performanceTestExecution.getVcsCommits().get(0), nonEmptyExecutions.get(0), nonEmptyExecutions.get(1));
        } else {
            return null;
        }
    }

    @Override
    public void render(final ResultsStore store, Writer writer) throws IOException {
        new MetricsHtml(writer) {
            // @formatter:off
            {
                html();
                    head();
                        headSection(this);
                        link().rel("stylesheet").type("text/css").href("https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0/css/bootstrap.min.css").end();
                        script().src("https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0/js/bootstrap.min.js").end();
                        title().text("Profile report for channel " + ResultsStoreHelper.determineChannel()).end();
                    end();
                    body();
                        div().id("acoordion").classAttr("mx-auto");
                        renderHeader();
                        renderTable();
                    end();
                footer(this);
                endAll();
            }

            private void renderHeader() {
                long successCount = scenarios.stream().filter(ScenarioBuildResultData::isSuccessful).count();
                long failureCount = scenarios.size() - successCount;
                div().classAttr("row alert alert-primary m-0");
                    div().classAttr("col").text("#").end();
                    div().classAttr("col-9 p-0");
                        text("Scenarios (" + successCount + " successful");
                        if (failureCount > 0) {
                            text(", " + failureCount + " failed");
                        }
                        text(")");
                        String commitId = Git.current().getCommitId();
                        a().target("_blank").href("https://github.com/gradle/gradle/commits/"+ commitId).small().classAttr("text-muted").text(commitId).end().end();
                    end();
                    div().classAttr("col").text("Difference").end();
                    div().classAttr("col").text("Confidence").end();
                end();
            }

            private void renderTable() {
                AtomicInteger index = new AtomicInteger(0);
                scenarios.forEach(scenario -> renderScenario(index.incrementAndGet(), scenario));
            }

            private String determineScenarioCss(ScenarioBuildResultData scenario) {
                if (!scenario.isSuccessful()) {
                    return "danger";
                } else if (scenario.isAboutToRegress()) {
                    return "warning";
                } else {
                    return "success";
                }
            }

            private String getTextColorCss(ScenarioBuildResultData.ExecutionData executionData) {
                if (executionData.getRegressionPercentage() <= 0 || executionData.getConfidencePercentage() < DANGEROUS_REGRESSION_CONFIDENCE_THRESHOLD) {
                    return "text-success";
                } else {
                    return "text-danger";
                }
            }

            private void renderScenario(int index, ScenarioBuildResultData scenario) {
                div().classAttr("card m-0 p-0 alert alert-" + determineScenarioCss(scenario));
                    div().id("heading" + index).classAttr("card-header");
                        div().classAttr("row align-items-center");
                            div().classAttr("col").text(String.valueOf(index)).end();
                            div().classAttr("col-9");
                                big().text(scenario.getScenarioName()).end();
                                a().target("_blank").classAttr("btn btn-primary btn-sm").href(scenario.getWebUrl()).text("To the build").end();
                                a().target("_blank").classAttr("btn btn-primary btn-sm").href("tests/" + urlEncode(scenario.getScenarioName().replaceAll("\\s+", "-") + ".html")).text("See the graph").end();
                                a().classAttr("btn btn-primary btn-sm collapsed").href("#").attr("data-toggle", "collapse", "data-target", "#collapse" + index).text("Show more details").end();
                            end();
                            div().classAttr("col-2");
                                if(scenario.isBuildFailed()) {
                                    text("N/A");
                                } else if(scenario.isFromCache()) {
                                    text("FROM-CACHE");
                                } else {
                                    scenario.getExecutions().forEach(execution -> {
                                        div().classAttr("row");
                                        div().classAttr("col " + getTextColorCss(execution)).text(execution.getFormattedRegression()).end();
                                        div().classAttr("col " + getTextColorCss(execution)).text(execution.getFormattedConfidence()).end();
                                        end();
                                    });
                                }
                            end();
                        end();
                    end();

                    div().id("collapse" + index).classAttr("collapse");
                        div().classAttr("card-body");
                            if(scenario.isBuildFailed()) {
                                pre().text(scenario.getTestFailure()).end();
                            } else {
                                renderDetailsTable(scenario);
                            }
                        end();
                    end();
                end();
            }

            private void renderDetailsTable(ScenarioBuildResultData scenario) {
                table().classAttr("table table-condensed table-bordered");
                    tr();
                        th().text("Date").end();
                        th().text("Commit").end();
                        th().colspan("2").text(scenario.getExecutions().get(0).getBaseVersion().getName()).end();
                        th().colspan("2").text(scenario.getExecutions().get(0).getCurrentVersion().getName()).end();
                    end();
                    scenario.getExecutions().forEach(execution -> {
                        tr();
                            DataSeries<Duration> baseVersion = execution.getBaseVersion().getTotalTime();
                            DataSeries<Duration> currentVersion = execution.getCurrentVersion().getTotalTime();
                            td().text(format.timestamp(execution.getTime())).end();
                            td().text(execution.getCommitId()).end();
                            td().classAttr(baseVersion.getMedian().compareTo(currentVersion.getMedian()) < 0 ? "text-success" : "text-danger").text(baseVersion.getMedian().format()).end();
                            td().classAttr("text-muted").text("se: " + baseVersion.getStandardError().format()).end();
                            td().classAttr(baseVersion.getMedian().compareTo(currentVersion.getMedian()) >= 0 ? "text-success" : "text-danger").text(currentVersion.getMedian().format()).end();
                            td().classAttr("text-muted").text("se: " + currentVersion.getStandardError().format()).end();
                        end();
                });
                end();
            }
            // @formatter:on
        };
    }
}
