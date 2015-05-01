/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.reducers.moving.avg;


import com.google.common.collect.EvictingQueue;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.RangeFilterBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilter;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram.Bucket;
import org.elasticsearch.search.aggregations.metrics.ValuesSourceMetricsAggregationBuilder;
import org.elasticsearch.search.aggregations.reducers.BucketHelpers;
import org.elasticsearch.search.aggregations.reducers.ReducerHelperTests;
import org.elasticsearch.search.aggregations.reducers.SimpleValue;
import org.elasticsearch.search.aggregations.reducers.movavg.models.DoubleExpModel;
import org.elasticsearch.search.aggregations.reducers.movavg.models.LinearModel;
import org.elasticsearch.search.aggregations.reducers.movavg.models.MovAvgModelBuilder;
import org.elasticsearch.search.aggregations.reducers.movavg.models.SimpleModel;
import org.elasticsearch.search.aggregations.reducers.movavg.models.SingleExpModel;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.search.aggregations.AggregationBuilders.avg;
import static org.elasticsearch.search.aggregations.AggregationBuilders.filter;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.max;
import static org.elasticsearch.search.aggregations.AggregationBuilders.min;
import static org.elasticsearch.search.aggregations.AggregationBuilders.range;
import static org.elasticsearch.search.aggregations.reducers.ReducerBuilders.movingAvg;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;

@ElasticsearchIntegrationTest.SuiteScopeTest
public class MovAvgTests extends ElasticsearchIntegrationTest {

    private static final String INTERVAL_FIELD = "l_value";
    private static final String VALUE_FIELD = "v_value";
    private static final String GAP_FIELD = "g_value";

    static int interval;
    static int numBuckets;
    static int windowSize;
    static double alpha;
    static double beta;
    static BucketHelpers.GapPolicy gapPolicy;
    static ValuesSourceMetricsAggregationBuilder metric;
    static List<ReducerHelperTests.MockBucket> mockHisto;

    static Map<String, ArrayList<Double>> testValues;


    enum MovAvgType {
        SIMPLE ("simple"), LINEAR("linear"), SINGLE("single"), DOUBLE("double");

        private final String name;

        MovAvgType(String s) {
            name = s;
        }

        public String toString(){
            return name;
        }
    }

    enum MetricTarget {
        VALUE ("value"), COUNT("count");

        private final String name;

        MetricTarget(String s) {
            name = s;
        }

        public String toString(){
            return name;
        }
    }


    @Override
    public void setupSuiteScopeCluster() throws Exception {
        createIndex("idx");
        createIndex("idx_unmapped");
        List<IndexRequestBuilder> builders = new ArrayList<>();


        interval = 5;
        numBuckets = randomIntBetween(6, 80);
        windowSize = randomIntBetween(3, 10);
        alpha = randomDouble();
        beta = randomDouble();

        gapPolicy = randomBoolean() ? BucketHelpers.GapPolicy.SKIP : BucketHelpers.GapPolicy.INSERT_ZEROS;
        metric = randomMetric("the_metric", VALUE_FIELD);
        mockHisto = ReducerHelperTests.generateHistogram(interval, numBuckets, randomDouble(), randomDouble());

        testValues = new HashMap<>(8);

        for (MovAvgType type : MovAvgType.values()) {
            for (MetricTarget target : MetricTarget.values()) {
                setupExpected(type, target);
            }
        }

        for (ReducerHelperTests.MockBucket mockBucket : mockHisto) {
            for (double value : mockBucket.docValues) {
                builders.add(client().prepareIndex("idx", "type").setSource(jsonBuilder().startObject()
                        .field(INTERVAL_FIELD, mockBucket.key)
                        .field(VALUE_FIELD, value).endObject()));
            }
        }

        // Used for specially crafted gap tests
        builders.add(client().prepareIndex("idx", "gap_type").setSource(jsonBuilder().startObject()
                .field(INTERVAL_FIELD, 0)
                .field(GAP_FIELD, 1).endObject()));

        builders.add(client().prepareIndex("idx", "gap_type").setSource(jsonBuilder().startObject()
                .field(INTERVAL_FIELD, 49)
                .field(GAP_FIELD, 1).endObject()));

        indexRandom(true, builders);
        ensureSearchable();
    }

    /**
     * Calculates the moving averages for a specific (model, target) tuple based on the previously generated mock histogram.
     * Computed values are stored in the testValues map.
     *
     * @param type      The moving average model to use
     * @param target    The document field "target", e.g. _count or a field value
     */
    private void setupExpected(MovAvgType type, MetricTarget target) {
        ArrayList<Double> values = new ArrayList<>(numBuckets);
        EvictingQueue<Double> window = EvictingQueue.create(windowSize);

        for (ReducerHelperTests.MockBucket mockBucket : mockHisto) {
            double metricValue;
            double[] docValues = mockBucket.docValues;

            // Gaps only apply to metric values, not doc _counts
            if (mockBucket.count == 0 && target.equals(MetricTarget.VALUE)) {
                // If there was a gap in doc counts and we are ignoring, just skip this bucket
                if (gapPolicy.equals(BucketHelpers.GapPolicy.SKIP)) {
                    values.add(null);
                    continue;
                } else if (gapPolicy.equals(BucketHelpers.GapPolicy.INSERT_ZEROS)) {
                    // otherwise insert a zero instead of the true value
                    metricValue = 0.0;
                } else {
                    metricValue = ReducerHelperTests.calculateMetric(docValues, metric);
                }

            } else {
                // If this isn't a gap, or is a _count, just insert the value
                metricValue = target.equals(MetricTarget.VALUE) ? ReducerHelperTests.calculateMetric(docValues, metric) : mockBucket.count;
            }

            window.offer(metricValue);
            switch (type) {
                case SIMPLE:
                    values.add(simple(window));
                    break;
                case LINEAR:
                    values.add(linear(window));
                    break;
                case SINGLE:
                    values.add(singleExp(window));
                    break;
                case DOUBLE:
                    values.add(doubleExp(window));
                    break;
            }

        }
        testValues.put(type.toString() + "_" + target.toString(), values);
    }

    /**
     * Simple, unweighted moving average
     *
     * @param window Window of values to compute movavg for
     * @return
     */
    private double simple(Collection<Double> window) {
        double movAvg = 0;
        for (double value : window) {
            movAvg += value;
        }
        movAvg /= window.size();
        return movAvg;
    }

    /**
     * Linearly weighted moving avg
     *
     * @param window Window of values to compute movavg for
     * @return
     */
    private double linear(Collection<Double> window) {
        double avg = 0;
        long totalWeight = 1;
        long current = 1;

        for (double value : window) {
            avg += value * current;
            totalWeight += current;
            current += 1;
        }
        return avg / totalWeight;
    }

    /**
     * Single exponential moving avg
     *
     * @param window Window of values to compute movavg for
     * @return
     */
    private double singleExp(Collection<Double> window) {
        double avg = 0;
        boolean first = true;

        for (double value : window) {
            if (first) {
                avg = value;
                first = false;
            } else {
                avg = (value * alpha) + (avg * (1 - alpha));
            }
        }
        return avg;
    }

    /**
     * Double exponential moving avg
     * @param window Window of values to compute movavg for
     * @return
     */
    private double doubleExp(Collection<Double> window) {
        double s = 0;
        double last_s = 0;

        // Trend value
        double b = 0;
        double last_b = 0;

        int counter = 0;

        double last;
        for (double value : window) {
            last = value;
            if (counter == 1) {
                s = value;
                b = value - last;
            } else {
                s = alpha * value + (1.0d - alpha) * (last_s + last_b);
                b = beta * (s - last_s) + (1 - beta) * last_b;
            }

            counter += 1;
            last_s = s;
            last_b = b;
        }

        return s + (0 * b) ;
    }




    /**
     * test simple moving average on single value field
     */
    @Test
    @AwaitsFix(bugUrl = "Fails with certain seeds including -Dtests.seed=D9EF60095522804F")
    public void simpleSingleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.SIMPLE.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.SIMPLE.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<ReducerHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            ReducerHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    @AwaitsFix(bugUrl = "Fails with certain seeds including -Dtests.seed=D9EF60095522804F")
    public void linearSingleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new LinearModel.LinearModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new LinearModel.LinearModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.LINEAR.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.LINEAR.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<ReducerHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            ReducerHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    @AwaitsFix(bugUrl = "Fails with certain seeds including -Dtests.seed=D9EF60095522804F")
    public void singleSingleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new SingleExpModel.SingleExpModelBuilder().alpha(alpha))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new SingleExpModel.SingleExpModelBuilder().alpha(alpha))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.SINGLE.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.SINGLE.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<ReducerHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            ReducerHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    @AwaitsFix(bugUrl = "Fails with certain seeds including -Dtests.seed=D9EF60095522804F")
    public void doubleSingleValuedField() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(metric)
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new DoubleExpModel.DoubleExpModelBuilder().alpha(alpha).beta(beta))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("_count"))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(new DoubleExpModel.DoubleExpModelBuilder().alpha(alpha).beta(beta))
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(mockHisto.size()));

        List<Double> expectedCounts = testValues.get(MovAvgType.DOUBLE.toString() + "_" + MetricTarget.COUNT.toString());
        List<Double> expectedValues = testValues.get(MovAvgType.DOUBLE.toString() + "_" + MetricTarget.VALUE.toString());

        Iterator<? extends Histogram.Bucket> actualIter = buckets.iterator();
        Iterator<ReducerHelperTests.MockBucket> expectedBucketIter = mockHisto.iterator();
        Iterator<Double> expectedCountsIter = expectedCounts.iterator();
        Iterator<Double> expectedValuesIter = expectedValues.iterator();

        while (actualIter.hasNext()) {
            assertValidIterators(expectedBucketIter, expectedCountsIter, expectedValuesIter);

            Histogram.Bucket actual = actualIter.next();
            ReducerHelperTests.MockBucket expected = expectedBucketIter.next();
            Double expectedCount = expectedCountsIter.next();
            Double expectedValue = expectedValuesIter.next();

            assertThat("keys do not match", ((Number) actual.getKey()).longValue(), equalTo(expected.key));
            assertThat("doc counts do not match", actual.getDocCount(), equalTo((long)expected.count));

            assertBucketContents(actual, expectedCount, expectedValue);
        }
    }

    @Test
    public void testSizeZeroWindow() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(0)
                                            .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept a window that is zero");

        } catch (SearchPhaseExecutionException exception) {
           // All good
        }
    }

    @Test
    public void testBadParent() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            range("histo").field(INTERVAL_FIELD).addRange(0, 10)
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(0)
                                            .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept non-histogram as parent");

        } catch (SearchPhaseExecutionException exception) {
            // All good
        }
    }

    @Test
    public void testNegativeWindow() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(-10)
                                            .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .setBucketsPaths("_count"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept a window that is negative");

        } catch (SearchPhaseExecutionException exception) {
            //Throwable rootCause = exception.unwrapCause();
            //assertThat(rootCause, instanceOf(SearchParseException.class));
            //assertThat("[window] value must be a positive, non-zero integer.  Value supplied was [0] in [movingAvg].", equalTo(exception.getMessage()));
        }
    }

    @Test
    public void testNoBucketsInHistogram() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field("test").interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat(buckets.size(), equalTo(0));
    }

    @Test
    public void testNoBucketsInHistogramWithPredict() {
        int numPredictions = randomIntBetween(1,10);
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("type")
                .addAggregation(
                        histogram("histo").field("test").interval(interval)
                                .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                .subAggregation(movingAvg("movavg_counts")
                                        .window(windowSize)
                                        .modelBuilder(new SimpleModel.SimpleModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric")
                                        .predict(numPredictions))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat(buckets.size(), equalTo(0));
    }

    @Test
    public void testZeroPrediction() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(windowSize)
                                            .modelBuilder(randomModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .predict(0)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept a prediction size that is zero");

        } catch (SearchPhaseExecutionException exception) {
            // All Good
        }
    }

    @Test
    public void testNegativePrediction() {
        try {
            client()
                    .prepareSearch("idx").setTypes("type")
                    .addAggregation(
                            histogram("histo").field(INTERVAL_FIELD).interval(interval)
                                    .extendedBounds(0L, (long) (interval * (numBuckets - 1)))
                                    .subAggregation(randomMetric("the_metric", VALUE_FIELD))
                                    .subAggregation(movingAvg("movavg_counts")
                                            .window(windowSize)
                                            .modelBuilder(randomModelBuilder())
                                            .gapPolicy(gapPolicy)
                                            .predict(-10)
                                            .setBucketsPaths("the_metric"))
                    ).execute().actionGet();
            fail("MovingAvg should not accept a prediction size that is negative");

        } catch (SearchPhaseExecutionException exception) {
            // All Good
        }
    }

    /**
     * This test uses the "gap" dataset, which is simply a doc at the beginning and end of
     * the INTERVAL_FIELD range.  These docs have a value of 1 in GAP_FIELD.
     * This test verifies that large gaps don't break things, and that the mov avg roughly works
     * in the correct manner (checks direction of change, but not actual values)
     */
    @Test
    public void testGiantGap() {

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                .subAggregation(min("the_metric").field(GAP_FIELD))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(randomModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric"))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50));

        double lastValue = ((SimpleValue)(buckets.get(0).getAggregations().get("movavg_values"))).value();
        assertThat(Double.compare(lastValue, 0.0d), greaterThanOrEqualTo(0));

        double currentValue;
        for (int i = 1; i < 49; i++) {
            SimpleValue current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                // Since there are only two values in this test, at the beginning and end, the moving average should
                // decrease every step (until it reaches zero).  Crude way to check that it's doing the right thing
                // without actually verifying the computed values.  Should work for all types of moving avgs and
                // gap policies
                assertThat(Double.compare(lastValue, currentValue), greaterThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }


        SimpleValue current = buckets.get(49).getAggregations().get("movavg_values");
        assertThat(current, notNullValue());
        currentValue = current.value();

        if (gapPolicy.equals(BucketHelpers.GapPolicy.SKIP)) {
            // if we are ignoring, movavg could go up (double_exp) or stay the same (simple, linear, single_exp)
            assertThat(Double.compare(lastValue, currentValue), lessThanOrEqualTo(0));
        } else if (gapPolicy.equals(BucketHelpers.GapPolicy.INSERT_ZEROS)) {
            // If we insert zeros, this should always increase the moving avg since the last bucket has a real value
            assertThat(Double.compare(lastValue, currentValue), equalTo(-1));
        }
    }

    /**
     * Big gap, but with prediction at the end.
     */
    @Test
    public void testGiantGapWithPredict() {
        int numPredictions = randomIntBetween(1, 10);

        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                .subAggregation(min("the_metric").field(GAP_FIELD))
                                .subAggregation(movingAvg("movavg_values")
                                        .window(windowSize)
                                        .modelBuilder(randomModelBuilder())
                                        .gapPolicy(gapPolicy)
                                        .setBucketsPaths("the_metric")
                                        .predict(numPredictions))
                ).execute().actionGet();

        assertSearchResponse(response);

        InternalHistogram<Bucket> histo = response.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50 + numPredictions));

        double lastValue = ((SimpleValue)(buckets.get(0).getAggregations().get("movavg_values"))).value();
        assertThat(Double.compare(lastValue, 0.0d), greaterThanOrEqualTo(0));

        double currentValue;
        for (int i = 1; i < 49; i++) {
            SimpleValue current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                // Since there are only two values in this test, at the beginning and end, the moving average should
                // decrease every step (until it reaches zero).  Crude way to check that it's doing the right thing
                // without actually verifying the computed values.  Should work for all types of moving avgs and
                // gap policies
                assertThat(Double.compare(lastValue, currentValue), greaterThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }

        SimpleValue current = buckets.get(49).getAggregations().get("movavg_values");
        assertThat(current, notNullValue());
        currentValue = current.value();

        if (gapPolicy.equals(BucketHelpers.GapPolicy.SKIP)) {
            // if we are ignoring, movavg could go up (double_exp) or stay the same (simple, linear, single_exp)
            assertThat(Double.compare(lastValue, currentValue), lessThanOrEqualTo(0));
        } else if (gapPolicy.equals(BucketHelpers.GapPolicy.INSERT_ZEROS)) {
            // If we insert zeros, this should always increase the moving avg since the last bucket has a real value
            assertThat(Double.compare(lastValue, currentValue), equalTo(-1));
        }

        // Now check predictions
        for (int i = 50; i < 50 + numPredictions; i++) {
            // Unclear at this point which direction the predictions will go, just verify they are
            // not null, and that we don't have the_metric anymore
            assertThat((buckets.get(i).getAggregations().get("movavg_values")), notNullValue());
            assertThat((buckets.get(i).getAggregations().get("the_metric")), nullValue());
        }
    }

    /**
     * This test filters the "gap" data so that the first doc is excluded.  This leaves a long stretch of empty
     * buckets until the final bucket.  The moving avg should be zero up until the last bucket, and should work
     * regardless of mov avg type or gap policy.
     */
    @Test
    public void testLeftGap() {
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        filter("filtered").filter(new RangeFilterBuilder(INTERVAL_FIELD).from(1)).subAggregation(
                                histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                        .subAggregation(randomMetric("the_metric", GAP_FIELD))
                                        .subAggregation(movingAvg("movavg_values")
                                                .window(windowSize)
                                                .modelBuilder(randomModelBuilder())
                                                .gapPolicy(gapPolicy)
                                                .setBucketsPaths("the_metric"))
                        ))
                .execute().actionGet();

        assertSearchResponse(response);

        InternalFilter filtered = response.getAggregations().get("filtered");
        assertThat(filtered, notNullValue());
        assertThat(filtered.getName(), equalTo("filtered"));

        InternalHistogram<Bucket> histo = filtered.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50));

        double lastValue = 0;

        double currentValue;
        for (int i = 0; i < 50; i++) {
            SimpleValue current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                assertThat(Double.compare(lastValue, currentValue), lessThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }
    }

    @Test
    public void testLeftGapWithPredict() {
        int numPredictions = randomIntBetween(1, 10);
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        filter("filtered").filter(new RangeFilterBuilder(INTERVAL_FIELD).from(1)).subAggregation(
                                histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                        .subAggregation(randomMetric("the_metric", GAP_FIELD))
                                        .subAggregation(movingAvg("movavg_values")
                                                .window(windowSize)
                                                .modelBuilder(randomModelBuilder())
                                                .gapPolicy(gapPolicy)
                                                .setBucketsPaths("the_metric")
                                                .predict(numPredictions))
                        ))
                .execute().actionGet();

        assertSearchResponse(response);

        InternalFilter filtered = response.getAggregations().get("filtered");
        assertThat(filtered, notNullValue());
        assertThat(filtered.getName(), equalTo("filtered"));

        InternalHistogram<Bucket> histo = filtered.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50 + numPredictions));

        double lastValue = 0;

        double currentValue;
        for (int i = 0; i < 50; i++) {
            SimpleValue current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                assertThat(Double.compare(lastValue, currentValue), lessThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }

        // Now check predictions
        for (int i = 50; i < 50 + numPredictions; i++) {
            // Unclear at this point which direction the predictions will go, just verify they are
            // not null, and that we don't have the_metric anymore
            assertThat((buckets.get(i).getAggregations().get("movavg_values")), notNullValue());
            assertThat((buckets.get(i).getAggregations().get("the_metric")), nullValue());
        }
    }

    /**
     * This test filters the "gap" data so that the last doc is excluded.  This leaves a long stretch of empty
     * buckets after the first bucket.  The moving avg should be one at the beginning, then zero for the rest
     * regardless of mov avg type or gap policy.
     */
    @Test
    public void testRightGap() {
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        filter("filtered").filter(new RangeFilterBuilder(INTERVAL_FIELD).to(1)).subAggregation(
                                histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                        .subAggregation(randomMetric("the_metric", GAP_FIELD))
                                        .subAggregation(movingAvg("movavg_values")
                                                .window(windowSize)
                                                .modelBuilder(randomModelBuilder())
                                                .gapPolicy(gapPolicy)
                                                .setBucketsPaths("the_metric"))
                        ))
                .execute().actionGet();

        assertSearchResponse(response);

        InternalFilter filtered = response.getAggregations().get("filtered");
        assertThat(filtered, notNullValue());
        assertThat(filtered.getName(), equalTo("filtered"));

        InternalHistogram<Bucket> histo = filtered.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50));


        SimpleValue current = buckets.get(0).getAggregations().get("movavg_values");
        assertThat(current, notNullValue());

        double lastValue = current.value();

        double currentValue;
        for (int i = 1; i < 50; i++) {
            current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                assertThat(Double.compare(lastValue, currentValue), greaterThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }
    }

    @Test
    public void testRightGapWithPredict() {
        int numPredictions = randomIntBetween(1, 10);
        SearchResponse response = client()
                .prepareSearch("idx").setTypes("gap_type")
                .addAggregation(
                        filter("filtered").filter(new RangeFilterBuilder(INTERVAL_FIELD).to(1)).subAggregation(
                                histogram("histo").field(INTERVAL_FIELD).interval(1).extendedBounds(0L, 49L)
                                        .subAggregation(randomMetric("the_metric", GAP_FIELD))
                                        .subAggregation(movingAvg("movavg_values")
                                                .window(windowSize)
                                                .modelBuilder(randomModelBuilder())
                                                .gapPolicy(gapPolicy)
                                                .setBucketsPaths("the_metric")
                                                .predict(numPredictions))
                        ))
                .execute().actionGet();

        assertSearchResponse(response);

        InternalFilter filtered = response.getAggregations().get("filtered");
        assertThat(filtered, notNullValue());
        assertThat(filtered.getName(), equalTo("filtered"));

        InternalHistogram<Bucket> histo = filtered.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        assertThat(histo.getName(), equalTo("histo"));
        List<? extends Bucket> buckets = histo.getBuckets();
        assertThat("Size of buckets array is not correct.", buckets.size(), equalTo(50 + numPredictions));


        SimpleValue current = buckets.get(0).getAggregations().get("movavg_values");
        assertThat(current, notNullValue());

        double lastValue = current.value();

        double currentValue;
        for (int i = 1; i < 50; i++) {
            current = buckets.get(i).getAggregations().get("movavg_values");
            if (current != null) {
                currentValue = current.value();

                assertThat(Double.compare(lastValue, currentValue), greaterThanOrEqualTo(0));
                lastValue = currentValue;
            }
        }

        // Now check predictions
        for (int i = 50; i < 50 + numPredictions; i++) {
            // Unclear at this point which direction the predictions will go, just verify they are
            // not null, and that we don't have the_metric anymore
            assertThat((buckets.get(i).getAggregations().get("movavg_values")), notNullValue());
            assertThat((buckets.get(i).getAggregations().get("the_metric")), nullValue());
        }
    }


    private void assertValidIterators(Iterator expectedBucketIter, Iterator expectedCountsIter, Iterator expectedValuesIter) {
        if (!expectedBucketIter.hasNext()) {
            fail("`expectedBucketIter` iterator ended before `actual` iterator, size mismatch");
        }
        if (!expectedCountsIter.hasNext()) {
            fail("`expectedCountsIter` iterator ended before `actual` iterator, size mismatch");
        }
        if (!expectedValuesIter.hasNext()) {
            fail("`expectedValuesIter` iterator ended before `actual` iterator, size mismatch");
        }
    }

    private void assertBucketContents(Histogram.Bucket actual, Double expectedCount, Double expectedValue) {
        // This is a gap bucket
        SimpleValue countMovAvg = actual.getAggregations().get("movavg_counts");
        if (expectedCount == null) {
            assertThat("[_count] movavg is not null", countMovAvg, nullValue());
        } else {
            assertThat("[_count] movavg is null", countMovAvg, notNullValue());
            assertThat("[_count] movavg does not match expected ["+countMovAvg.value()+" vs "+expectedCount+"]",
                    Math.abs(countMovAvg.value() - expectedCount) <= 0.000001, equalTo(true));
        }

        // This is a gap bucket
        SimpleValue valuesMovAvg = actual.getAggregations().get("movavg_values");
        if (expectedValue == null) {
            assertThat("[value] movavg is not null", valuesMovAvg, Matchers.nullValue());
        } else {
            assertThat("[value] movavg is null", valuesMovAvg, notNullValue());
            assertThat("[value] movavg does not match expected ["+valuesMovAvg.value()+" vs "+expectedValue+"]", Math.abs(valuesMovAvg.value() - expectedValue) <= 0.000001, equalTo(true));
        }
    }

    private MovAvgModelBuilder randomModelBuilder() {
        int rand = randomIntBetween(0,3);

        switch (rand) {
            case 0:
                return new SimpleModel.SimpleModelBuilder();
            case 1:
                return new LinearModel.LinearModelBuilder();
            case 2:
                return new SingleExpModel.SingleExpModelBuilder().alpha(alpha);
            case 3:
                return new DoubleExpModel.DoubleExpModelBuilder().alpha(alpha).beta(beta);
            default:
                return new SimpleModel.SimpleModelBuilder();
        }
    }
    
    private ValuesSourceMetricsAggregationBuilder randomMetric(String name, String field) {
        int rand = randomIntBetween(0,3);

        switch (rand) {
            case 0:
                return min(name).field(field);
            case 2:
                return max(name).field(field);
            case 3:
                return avg(name).field(field);
            default:
                return avg(name).field(field);
        }    
    }

}
