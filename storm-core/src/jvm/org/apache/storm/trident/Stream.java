/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.trident;

import org.apache.storm.generated.Grouping;
import org.apache.storm.generated.NullStruct;
import org.apache.storm.trident.fluent.ChainedAggregatorDeclarer;
import org.apache.storm.grouping.CustomStreamGrouping;
import org.apache.storm.tuple.Fields;
import org.apache.storm.utils.Utils;
import org.apache.storm.trident.fluent.GlobalAggregationScheme;
import org.apache.storm.trident.fluent.GroupedStream;
import org.apache.storm.trident.fluent.IAggregatableStream;
import org.apache.storm.trident.operation.Aggregator;
import org.apache.storm.trident.operation.Assembly;
import org.apache.storm.trident.operation.CombinerAggregator;
import org.apache.storm.trident.operation.Filter;
import org.apache.storm.trident.operation.Function;
import org.apache.storm.trident.operation.ReducerAggregator;
import org.apache.storm.trident.operation.impl.CombinerAggStateUpdater;
import org.apache.storm.trident.operation.impl.FilterExecutor;
import org.apache.storm.trident.operation.impl.GlobalBatchToPartition;
import org.apache.storm.trident.operation.impl.ReducerAggStateUpdater;
import org.apache.storm.trident.operation.impl.IndexHashBatchToPartition;
import org.apache.storm.trident.operation.impl.SingleEmitAggregator.BatchToPartition;
import org.apache.storm.trident.operation.impl.TrueFilter;
import org.apache.storm.trident.partition.GlobalGrouping;
import org.apache.storm.trident.partition.IdentityGrouping;
import org.apache.storm.trident.partition.IndexHashGrouping;
import org.apache.storm.trident.planner.Node;
import org.apache.storm.trident.planner.NodeStateInfo;
import org.apache.storm.trident.planner.PartitionNode;
import org.apache.storm.trident.planner.ProcessorNode;
import org.apache.storm.trident.planner.processor.AggregateProcessor;
import org.apache.storm.trident.planner.processor.EachProcessor;
import org.apache.storm.trident.planner.processor.PartitionPersistProcessor;
import org.apache.storm.trident.planner.processor.ProjectedProcessor;
import org.apache.storm.trident.planner.processor.StateQueryProcessor;
import org.apache.storm.trident.state.QueryFunction;
import org.apache.storm.trident.state.StateFactory;
import org.apache.storm.trident.state.StateSpec;
import org.apache.storm.trident.state.StateUpdater;
import org.apache.storm.trident.util.TridentUtils;

/**
 * A Stream represents the core data model in Trident, and can be thought of as a "stream" of tuples that are processed
 * as a series of small batches. A stream is partitioned accross the nodes in the cluster, and operations are
 * applied to a stream in parallel accross each partition.
 *
 * There are five types of operations that can be performed on streams in Trident
 *
 * 1. **Partiton-Local Operations** - Operations that are applied locally to each partition and do not involve network
 * transfer
 * 2. **Repartitioning Operations** - Operations that change how tuples are partitioned across tasks(thus causing
 * network transfer), but do not change the content of the stream.
 * 3. **Aggregation Operations** - Operations that *may* repartition a stream (thus causing network transfer)
 * 4. **Grouping Operations** - Operations that may repartition a stream on specific fields and group together tuples whose
 * fields values are equal.
 * 5. **Merge and Join Operations** - Operations that combine different streams together.
 *
 */
// TODO: need to be able to replace existing fields with the function fields (like Cascading Fields.REPLACE)
public class Stream implements IAggregatableStream {
    Node _node;
    TridentTopology _topology;
    String _name;
    
    protected Stream(TridentTopology topology, String name, Node node) {
        _topology = topology;
        _node = node;
        _name = name;
    }

    /**
     * Applies a label to the stream. Naming a stream will append the label to the name of the bolt(s) created by
     * Trident and will be visible in the Storm UI.
     *
     * @param name - The label to apply to the stream
     * @return
     */
    public Stream name(String name) {
        return new Stream(_topology, name, _node);
    }

    /**
     * Applies a parallelism hint to a stream.
     *
     * @param hint
     * @return
     */
    public Stream parallelismHint(int hint) {
        _node.parallelismHint = hint;
        return this;
    }

    /**
     * Filters out fields from a stream, resulting in a Stream containing only the fields specified by `keepFields`.
     *
     * For example, if you had a Stream `mystream` containing the fields `["a", "b", "c","d"]`, calling"
     *
     * ```java
     * mystream.project(new Fields("b", "d"))
     * ```
     *
     * would produce a stream containing only the fields `["b", "d"]`.
     *
     *
     * @param keepFields The fields in the Stream to keep
     * @return
     */
    public Stream project(Fields keepFields) {
        projectionValidation(keepFields);
        return _topology.addSourcedNode(this, new ProcessorNode(_topology.getUniqueStreamId(), _name, keepFields, new Fields(), new ProjectedProcessor(keepFields)));
    }

    /**
     * ## Grouping Operation
     *
     * @param fields
     * @return
     */
    public GroupedStream groupBy(Fields fields) {
        projectionValidation(fields);
        return new GroupedStream(this, fields);        
    }

    /**
     * ## Repartitioning Operation
     *
     * @param fields
     * @return
     */
    public Stream partitionBy(Fields fields) {
        projectionValidation(fields);
        return partition(Grouping.fields(fields.toList()));
    }

    /**
     * ## Repartitioning Operation
     *
     * @param partitioner
     * @return
     */
    public Stream partition(CustomStreamGrouping partitioner) {
        return partition(Grouping.custom_serialized(Utils.javaSerialize(partitioner)));
    }

    /**
     * ## Repartitioning Operation
     *
     * Use random round robin algorithm to evenly redistribute tuples across all target partitions
     *
     * @return
     */
    public Stream shuffle() {
        return partition(Grouping.shuffle(new NullStruct()));
    }

    /**
     * ## Repartitioning Operation
     *
     * Use random round robin algorithm to evenly redistribute tuples across all target partitions, with a preference
     * for local tasks.
     *
     * @return
     */
    public Stream localOrShuffle() {
        return partition(Grouping.local_or_shuffle(new NullStruct()));
    }


    /**
     * ## Repartitioning Operation
     *
     * All tuples are sent to the same partition. The same partition is chosen for all batches in the stream.
     * @return
     */
    public Stream global() {
        // use this instead of storm's built in one so that we can specify a singleemitbatchtopartition
        // without knowledge of storm's internals
        return partition(new GlobalGrouping());
    }

    /**
     * ## Repartitioning Operation
     *
     *  All tuples in the batch are sent to the same partition. Different batches in the stream may go to different
     *  partitions.
     *
     * @return
     */
    public Stream batchGlobal() {
        // the first field is the batch id
        return partition(new IndexHashGrouping(0));
    }

    /**
     * ## Repartitioning Operation
     *
     * Every tuple is replicated to all target partitions. This can useful during DRPC – for example, if you need to do
     * a stateQuery on every partition of data.
     *
     * @return
     */
    public Stream broadcast() {
        return partition(Grouping.all(new NullStruct()));
    }

    /**
     * ## Repartitioning Operation
     *
     * @return
     */
    public Stream identityPartition() {
        return partition(new IdentityGrouping());
    }

    /**
     * ## Repartitioning Operation
     *
     * This method takes in a custom partitioning function that implements
     * {@link org.apache.storm.grouping.CustomStreamGrouping}
     *
     * @param grouping
     * @return
     */
    public Stream partition(Grouping grouping) {
        if(_node instanceof PartitionNode) {
            return each(new Fields(), new TrueFilter()).partition(grouping);
        } else {
            return _topology.addSourcedNode(this, new PartitionNode(_node.streamId, _name, getOutputFields(), grouping));       
        }
    }

    /**
     * Applies an `Assembly` to this `Stream`.
     *
     * @see org.apache.storm.trident.operation.Assembly
     * @param assembly
     * @return
     */
    public Stream applyAssembly(Assembly assembly) {
        return assembly.apply(this);
    }

    @Override
    public Stream each(Fields inputFields, Function function, Fields functionFields) {
        projectionValidation(inputFields);
        return _topology.addSourcedNode(this,
                new ProcessorNode(_topology.getUniqueStreamId(),
                        _name,
                        TridentUtils.fieldsConcat(getOutputFields(), functionFields),
                        functionFields,
                        new EachProcessor(inputFields, function)));
    }
    //creates brand new tuples with brand new fields
    @Override
    public Stream partitionAggregate(Fields inputFields, Aggregator agg, Fields functionFields) {
        projectionValidation(inputFields);
        return _topology.addSourcedNode(this,
                new ProcessorNode(_topology.getUniqueStreamId(),
                    _name,
                    functionFields,
                    functionFields,
                    new AggregateProcessor(inputFields, agg)));
    }
    
    public Stream stateQuery(TridentState state, Fields inputFields, QueryFunction function, Fields functionFields) {
        projectionValidation(inputFields);
        String stateId = state._node.stateInfo.id;
        Node n = new ProcessorNode(_topology.getUniqueStreamId(),
                        _name,
                        TridentUtils.fieldsConcat(getOutputFields(), functionFields),
                        functionFields,
                        new StateQueryProcessor(stateId, inputFields, function));
        _topology._colocate.get(stateId).add(n);
        return _topology.addSourcedNode(this, n);
    }
    
    public TridentState partitionPersist(StateFactory stateFactory, Fields inputFields, StateUpdater updater, Fields functionFields) {
      return partitionPersist(new StateSpec(stateFactory), inputFields, updater, functionFields);
    }
    
    public TridentState partitionPersist(StateSpec stateSpec, Fields inputFields, StateUpdater updater, Fields functionFields) {
        projectionValidation(inputFields);
        String id = _topology.getUniqueStateId();
        ProcessorNode n = new ProcessorNode(_topology.getUniqueStreamId(),
                    _name,
                    functionFields,
                    functionFields,
                    new PartitionPersistProcessor(id, inputFields, updater));
        n.committer = true;
        n.stateInfo = new NodeStateInfo(id, stateSpec);
        return _topology.addSourcedStateNode(this, n);
    }
    
    public TridentState partitionPersist(StateFactory stateFactory, Fields inputFields, StateUpdater updater) {
      return partitionPersist(stateFactory, inputFields, updater, new Fields());
    }
    
    public TridentState partitionPersist(StateSpec stateSpec, Fields inputFields, StateUpdater updater) {
      return partitionPersist(stateSpec, inputFields, updater, new Fields());        
    }
    
    public Stream each(Function function, Fields functionFields) {
        return each(null, function, functionFields);
    }
    
    public Stream each(Fields inputFields, Filter filter) {
        return each(inputFields, new FilterExecutor(filter), new Fields());
    }    
    
    public ChainedAggregatorDeclarer chainedAgg() {
        return new ChainedAggregatorDeclarer(this, new BatchGlobalAggScheme());
    }
    
    public Stream partitionAggregate(Aggregator agg, Fields functionFields) {
        return partitionAggregate(null, agg, functionFields);
    }

    public Stream partitionAggregate(CombinerAggregator agg, Fields functionFields) {
        return partitionAggregate(null, agg, functionFields);
    }

    public Stream partitionAggregate(Fields inputFields, CombinerAggregator agg, Fields functionFields) {
        projectionValidation(inputFields);
        return chainedAgg()
               .partitionAggregate(inputFields, agg, functionFields)
               .chainEnd();
    }  
    
    public Stream partitionAggregate(ReducerAggregator agg, Fields functionFields) {
        return partitionAggregate(null, agg, functionFields);
    }

    public Stream partitionAggregate(Fields inputFields, ReducerAggregator agg, Fields functionFields) {
        projectionValidation(inputFields);
        return chainedAgg()
               .partitionAggregate(inputFields, agg, functionFields)
               .chainEnd();
    }  
    
    public Stream aggregate(Aggregator agg, Fields functionFields) {
        return aggregate(null, agg, functionFields);
    }
    
    public Stream aggregate(Fields inputFields, Aggregator agg, Fields functionFields) {
        projectionValidation(inputFields);
        return chainedAgg()
               .aggregate(inputFields, agg, functionFields)
               .chainEnd();
    }

    public Stream aggregate(CombinerAggregator agg, Fields functionFields) {
        return aggregate(null, agg, functionFields);
    }

    public Stream aggregate(Fields inputFields, CombinerAggregator agg, Fields functionFields) {
        projectionValidation(inputFields);
        return chainedAgg()
               .aggregate(inputFields, agg, functionFields)
               .chainEnd();
    }

    public Stream aggregate(ReducerAggregator agg, Fields functionFields) {
        return aggregate(null, agg, functionFields);
    }

    public Stream aggregate(Fields inputFields, ReducerAggregator agg, Fields functionFields) {
        projectionValidation(inputFields);
        return chainedAgg()
                .aggregate(inputFields, agg, functionFields)
                .chainEnd();
    }
    
    public TridentState partitionPersist(StateFactory stateFactory, StateUpdater updater, Fields functionFields) {
        return partitionPersist(new StateSpec(stateFactory), updater, functionFields);
    }
    
    public TridentState partitionPersist(StateSpec stateSpec, StateUpdater updater, Fields functionFields) {
        return partitionPersist(stateSpec, null, updater, functionFields);
    }
    
    public TridentState partitionPersist(StateFactory stateFactory, StateUpdater updater) {
        return partitionPersist(stateFactory, updater, new Fields());
    }
    
    public TridentState partitionPersist(StateSpec stateSpec, StateUpdater updater) {
        return partitionPersist(stateSpec, updater, new Fields());
    }

    public TridentState persistentAggregate(StateFactory stateFactory, CombinerAggregator agg, Fields functionFields) {
        return persistentAggregate(new StateSpec(stateFactory), agg, functionFields);
    }

    public TridentState persistentAggregate(StateSpec spec, CombinerAggregator agg, Fields functionFields) {
        return persistentAggregate(spec, null, agg, functionFields);
    }

    public TridentState persistentAggregate(StateFactory stateFactory, Fields inputFields, CombinerAggregator agg, Fields functionFields) {
        return persistentAggregate(new StateSpec(stateFactory), inputFields, agg, functionFields);
    }
    
    public TridentState persistentAggregate(StateSpec spec, Fields inputFields, CombinerAggregator agg, Fields functionFields) {
        projectionValidation(inputFields);
        // replaces normal aggregation here with a global grouping because it needs to be consistent across batches 
        return new ChainedAggregatorDeclarer(this, new GlobalAggScheme())
                .aggregate(inputFields, agg, functionFields)
                .chainEnd()
               .partitionPersist(spec, functionFields, new CombinerAggStateUpdater(agg), functionFields);
    }

    public TridentState persistentAggregate(StateFactory stateFactory, ReducerAggregator agg, Fields functionFields) {
        return persistentAggregate(new StateSpec(stateFactory), agg, functionFields);
    }

    public TridentState persistentAggregate(StateSpec spec, ReducerAggregator agg, Fields functionFields) {
        return persistentAggregate(spec, null, agg, functionFields);
    }

    public TridentState persistentAggregate(StateFactory stateFactory, Fields inputFields, ReducerAggregator agg, Fields functionFields) {
        return persistentAggregate(new StateSpec(stateFactory), inputFields, agg, functionFields);
    }

    public TridentState persistentAggregate(StateSpec spec, Fields inputFields, ReducerAggregator agg, Fields functionFields) {
        projectionValidation(inputFields);
        return global().partitionPersist(spec, inputFields, new ReducerAggStateUpdater(agg), functionFields);
    }
    
    public Stream stateQuery(TridentState state, QueryFunction function, Fields functionFields) {
        return stateQuery(state, null, function, functionFields);
    }

    @Override
    public Stream toStream() {
        return this;
    }

    @Override
    public Fields getOutputFields() {
        return _node.allOutputFields;
    }
    
    static class BatchGlobalAggScheme implements GlobalAggregationScheme<Stream> {

        @Override
        public IAggregatableStream aggPartition(Stream s) {
            return s.batchGlobal();
        }

        @Override
        public BatchToPartition singleEmitPartitioner() {
            return new IndexHashBatchToPartition();
        }
        
    }
    
    static class GlobalAggScheme implements GlobalAggregationScheme<Stream> {

        @Override
        public IAggregatableStream aggPartition(Stream s) {
            return s.global();
        }

        @Override
        public BatchToPartition singleEmitPartitioner() {
            return new GlobalBatchToPartition();
        }
        
    }

    private void projectionValidation(Fields projFields) {
        if (projFields == null) {
            return;
        }

        Fields allFields = this.getOutputFields();
        for (String field : projFields) {
            if (!allFields.contains(field)) {
                throw new IllegalArgumentException("Trying to select non-existent field: '" + field + "' from stream containing fields fields: <" + allFields + ">");
            }
        }
    }
}
