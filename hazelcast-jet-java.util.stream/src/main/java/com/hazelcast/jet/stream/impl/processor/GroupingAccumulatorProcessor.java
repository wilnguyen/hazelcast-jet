/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.stream.impl.processor;

import com.hazelcast.jet.runtime.JetPair;
import com.hazelcast.jet.runtime.InputChunk;
import com.hazelcast.jet.runtime.OutputCollector;
import com.hazelcast.jet.io.Pair;
import com.hazelcast.jet.Processor;
import com.hazelcast.jet.runtime.TaskContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collector;

public class GroupingAccumulatorProcessor<K, V, A, R> implements Processor<Pair<K, V>, Pair<K, A>> {

    private final Map<K, A> cache = new HashMap<>();
    private final Collector<V, A, R> collector;
    private Iterator<Map.Entry<K, A>> finalizationIterator;
    private int chunkSize;

    public GroupingAccumulatorProcessor(Collector<V, A, R> collector) {
        this.collector = collector;
    }

    @Override
    public void before(TaskContext context) {
        chunkSize = context.getJobContext().getJobConfig().getChunkSize();
    }

    @Override
    public boolean process(InputChunk<Pair<K, V>> inputChunk,
                           OutputCollector<Pair<K, A>> output,
                           String sourceName) throws Exception {
        for (Pair<K, V> input : inputChunk) {
            A value = this.cache.get(input.getKey());
            if (value == null) {
                value = collector.supplier().get();
                this.cache.put(input.getKey(), value);
            }
            collector.accumulator().accept(value, input.getValue());
        }
        return true;
    }

    @Override
    public boolean complete(OutputCollector<Pair<K, A>> output) throws Exception {
        boolean finalized = false;
        try {
            if (finalizationIterator == null) {
                this.finalizationIterator = this.cache.entrySet().iterator();
            }
            int idx = 0;
            while (this.finalizationIterator.hasNext()) {
                Map.Entry<K, A> next = this.finalizationIterator.next();
                output.collect(new JetPair<>(next.getKey(), next.getValue()));
                if (idx == chunkSize - 1) {
                    break;
                }
                idx++;
            }
            finalized = !this.finalizationIterator.hasNext();
        } finally {
            if (finalized) {
                this.finalizationIterator = null;
                this.cache.clear();
            }
        }
        return finalized;
    }
}