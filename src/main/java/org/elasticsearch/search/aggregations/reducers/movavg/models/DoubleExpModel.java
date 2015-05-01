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

package org.elasticsearch.search.aggregations.reducers.movavg.models;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.reducers.movavg.MovAvgParser;

import java.io.IOException;
import java.util.*;

/**
 * Calculate a doubly exponential weighted moving average
 */
public class DoubleExpModel extends MovAvgModel {

    protected static final ParseField NAME_FIELD = new ParseField("double_exp");

    /**
     * Controls smoothing of data. Alpha = 1 retains no memory of past values
     * (e.g. random walk), while alpha = 0 retains infinite memory of past values (e.g.
     * mean of the series).  Useful values are somewhere in between
     */
    private double alpha;

    /**
     * Equivalent to <code>alpha</code>, but controls the smoothing of the trend instead of the data
     */
    private double beta;

    public DoubleExpModel(double alpha, double beta) {
        this.alpha = alpha;
        this.beta = beta;
    }

    /**
     * Predicts the next `n` values in the series, using the smoothing model to generate new values.
     * Unlike the other moving averages, double-exp has forecasting/prediction built into the algorithm.
     * Prediction is more than simply adding the next prediction to the window and repeating.  Double-exp
     * will extrapolate into the future by applying the trend information to the smoothed data.
     *
     * @param values            Collection of numerics to movingAvg, usually windowed
     * @param numPredictions    Number of newly generated predictions to return
     * @param <T>               Type of numeric
     * @return                  Returns an array of doubles, since most smoothing methods operate on floating points
     */
    @Override
    public <T extends Number> double[] predict(Collection<T> values, int numPredictions) {
        return next(values, numPredictions);
    }

    @Override
    public <T extends Number> double next(Collection<T> values) {
        return next(values, 1)[0];
    }

    /**
     * Calculate a doubly exponential weighted moving average
     *
     * @param values Collection of values to calculate avg for
     * @param numForecasts number of forecasts into the future to return
     *
     * @param <T>    Type T extending Number
     * @return       Returns a Double containing the moving avg for the window
     */
    public <T extends Number> double[] next(Collection<T> values, int numForecasts) {

        if (values.size() == 0) {
            return emptyPredictions(numForecasts);
        }

        // Smoothed value
        double s = 0;
        double last_s = 0;

        // Trend value
        double b = 0;
        double last_b = 0;

        int counter = 0;

        //TODO bail if too few values

        T last;
        for (T v : values) {
            last = v;
            if (counter == 1) {
                s = v.doubleValue();
                b = v.doubleValue() - last.doubleValue();
            } else {
                s = alpha * v.doubleValue() + (1.0d - alpha) * (last_s + last_b);
                b = beta * (s - last_s) + (1 - beta) * last_b;
            }

            counter += 1;
            last_s = s;
            last_b = b;
        }

        double[] forecastValues = new double[numForecasts];
        for (int i = 0; i < numForecasts; i++) {
            forecastValues[i] = s + (i * b);
        }

        return forecastValues;
    }

    public static final MovAvgModelStreams.Stream STREAM = new MovAvgModelStreams.Stream() {
        @Override
        public MovAvgModel readResult(StreamInput in) throws IOException {
            return new DoubleExpModel(in.readDouble(), in.readDouble());
        }

        @Override
        public String getName() {
            return NAME_FIELD.getPreferredName();
        }
    };

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(STREAM.getName());
        out.writeDouble(alpha);
        out.writeDouble(beta);
    }

    public static class DoubleExpModelParser implements MovAvgModelParser {

        @Override
        public String getName() {
            return NAME_FIELD.getPreferredName();
        }

        @Override
        public MovAvgModel parse(@Nullable Map<String, Object> settings) {

            Double alpha;
            Double beta;

            if (settings == null || (alpha = (Double)settings.get("alpha")) == null) {
                alpha = 0.5;
            }

            if (settings == null || (beta = (Double)settings.get("beta")) == null) {
                beta = 0.5;
            }

            return new DoubleExpModel(alpha, beta);
        }
    }

    public static class DoubleExpModelBuilder implements MovAvgModelBuilder {

        private double alpha = 0.5;
        private double beta = 0.5;

        /**
         * Alpha controls the smoothing of the data.  Alpha = 1 retains no memory of past values
         * (e.g. a random walk), while alpha = 0 retains infinite memory of past values (e.g.
         * the series mean).  Useful values are somewhere in between.  Defaults to 0.5.
         *
         * @param alpha A double between 0-1 inclusive, controls data smoothing
         *
         * @return The builder to continue chaining
         */
        public DoubleExpModelBuilder alpha(double alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * Equivalent to <code>alpha</code>, but controls the smoothing of the trend instead of the data
         *
         * @param beta a double between 0-1 inclusive, controls trend smoothing
         *
         * @return The builder to continue chaining
         */
        public DoubleExpModelBuilder beta(double beta) {
            this.beta = beta;
            return this;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field(MovAvgParser.MODEL.getPreferredName(), NAME_FIELD.getPreferredName());
            builder.startObject(MovAvgParser.SETTINGS.getPreferredName());
                builder.field("alpha", alpha);
                builder.field("beta", beta);
            builder.endObject();
            return builder;
        }
    }
}

