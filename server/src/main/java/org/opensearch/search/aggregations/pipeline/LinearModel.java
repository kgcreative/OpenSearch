/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

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

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.aggregations.pipeline;


import org.opensearch.common.Nullable;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * Calculate a linearly weighted moving average, such that older values are
 * linearly less important.  "Time" is determined by position in collection
 */
public class LinearModel extends MovAvgModel {
    public static final String NAME = "linear";

    public LinearModel() {
    }

    /**
     * Read from a stream.
     */
    public LinearModel(StreamInput in) {
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        // No state to write
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public boolean canBeMinimized() {
        return false;
    }

    @Override
    public MovAvgModel neighboringModel() {
        return new LinearModel();
    }

    @Override
    public MovAvgModel clone() {
        return new LinearModel();
    }

    @Override
    protected double[] doPredict(Collection<Double> values, int numPredictions) {
        double[] predictions = new double[numPredictions];

        // EWMA just emits the same final prediction repeatedly.
        Arrays.fill(predictions, next(values));

        return predictions;
    }

    @Override
    public double next(Collection<Double> values) {
        return MovingFunctions.linearWeightedAvg(values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.field(MovAvgPipelineAggregationBuilder.MODEL.getPreferredName(), NAME);
        return builder;
    }

    public static final AbstractModelParser PARSER = new AbstractModelParser() {
        @Override
        public MovAvgModel parse(@Nullable Map<String, Object> settings, String pipelineName, int windowSize) throws ParseException {
            checkUnrecognizedParams(settings);
            return new LinearModel();
        }
    };

    public static class LinearModelBuilder implements MovAvgModelBuilder {
        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field(MovAvgPipelineAggregationBuilder.MODEL.getPreferredName(), NAME);
            return builder;
        }

        @Override
        public MovAvgModel build() {
            return new LinearModel();
        }
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        return true;
    }
}
