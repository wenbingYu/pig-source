/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pig.backend.hadoop.executionengine.tez;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.WritableComparator;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.JobControlCompiler;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPackage;
import org.apache.pig.data.AccumulativeBag;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.InternalCachedBag;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.io.NullableTuple;
import org.apache.pig.impl.io.PigNullableWritable;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.library.api.KeyValuesReader;
import org.apache.tez.runtime.library.common.ConfigUtils;

public class POShuffleTezLoad extends POPackage implements TezInput {

    private static final long serialVersionUID = 1L;

    protected List<String> inputKeys = new ArrayList<String>();
    protected List<LogicalInput> inputs = new ArrayList<LogicalInput>();
    protected List<KeyValuesReader> readers = new ArrayList<KeyValuesReader>();

    private boolean[] finished;
    private boolean[] readOnce;

    private WritableComparator comparator = null;
    private boolean isSkewedJoin = false;

    private transient Configuration conf;

    public POShuffleTezLoad(POPackage pack) {
        super(pack);
    }

    @Override
    public String[] getTezInputs() {
        return inputKeys.toArray(new String[inputKeys.size()]);
    }

    @Override
    public void replaceInput(String oldInputKey, String newInputKey) {
        if (inputKeys.remove(oldInputKey)) {
            inputKeys.add(newInputKey);
        }
    }

    @Override
    public void addInputsToSkip(Set<String> inputsToSkip) {
    }

    @Override
    public void attachInputs(Map<String, LogicalInput> inputs, Configuration conf)
            throws ExecException {
        this.conf = conf;
        comparator = (WritableComparator) ConfigUtils.getInputKeySecondaryGroupingComparator(conf);
        try {
            for (String key : inputKeys) {
                LogicalInput input = inputs.get(key);
                this.inputs.add(input);
                this.readers.add((KeyValuesReader)input.getReader());
            }

            // We need to adjust numInputs because it's possible for both
            // OrderedGroupedKVInput and non-OrderedGroupedKVInput to be attached
            // to the same vertex. If so, we're only interested in
            // OrderedGroupedKVInputs. So we ignore the others.
            this.numInputs = this.inputs.size();

            readOnce = new boolean[numInputs];
            for (int i = 0; i < numInputs; i++) {
                readOnce[i] = false;
            }

            finished = new boolean[numInputs];
            for (int i = 0; i < numInputs; i++) {
                finished[i] = !readers.get(i).next();
            }
        } catch (Exception e) {
            throw new ExecException(e);
        }
    }

    @Override
    public Result getNextTuple() throws ExecException {
        Result res = pkgr.getNext();

        while (res.returnStatus == POStatus.STATUS_EOP) {
            boolean hasData = false;
            Object cur = null;
            PigNullableWritable min = null;
            int minIndex = -1;

            try {
                for (int i = 0; i < numInputs; i++) {
                    if (!finished[i]) {
                        hasData = true;
                        cur = readers.get(i).getCurrentKey();
                        if (min == null || comparator.compare(min, cur) > 0) {
                            //Not a deep clone. Writable is referenced.
                            min = ((PigNullableWritable)cur).clone();
                            minIndex = i;
                        }
                    }
                }
            } catch (Exception e) {
                throw new ExecException(e);
            }

            if (!hasData) {
                // For certain operators (such as STREAM), we could still have some work
                // to do even after seeing the last input. These operators set a flag that
                // says all input has been sent and to run the pipeline one more time.
                if (Boolean.valueOf(conf.get(JobControlCompiler.END_OF_INP_IN_MAP, "false"))) {
                    this.parentPlan.endOfAllInput = true;
                }
                return new Result(POStatus.STATUS_EOP, null);
            }

            key = pkgr.getKey(min);
            keyWritable = min;

            DataBag[] bags = new DataBag[numInputs];
            POPackageTupleBuffer buffer = new POPackageTupleBuffer();
            List<NullableTuple> nTups = new ArrayList<NullableTuple>();

            try {
                for (int i = 0; i < numInputs; i++) {

                    DataBag bag = null;

                    if (!finished[i]) {
                        cur = readers.get(i).getCurrentKey();
                        // We need to loop in case of Grouping Comparators
                        while (comparator.compare(min, cur) == 0 && (!min.isNull() ||
                                min.isNull() && i==minIndex)) {
                            Iterable<Object> vals = readers.get(i).getCurrentValues();
                            if (isAccumulative()) {
                                // TODO: POPackageTupleBuffer expects the
                                // iterator for all the values from 1st to ith
                                // inputs. Ideally, we should directly pass
                                // iterators returned by getCurrentValues()
                                // instead of copying objects. But if we pass
                                // iterators directly, reuse of iterators causes
                                // a tez runtime error. For now, we copy objects
                                // into a new list and pass the iterator of this
                                // new list.
                                for (Object val : vals) {
                                    // Make a copy of key and val and avoid reference.
                                    // getCurrentKey() or value iterator resets value
                                    // on the same object by calling readFields() again.
                                    nTups.add(new NullableTuple((NullableTuple) val));
                                }
                                // Attach input to POPackageTupleBuffer
                                buffer.setIterator(nTups.iterator());
                                if(bags[i] == null) {
                                    buffer.setKey(cur);
                                    bag = new AccumulativeBag(buffer, i);
                                } else {
                                    bag = bags[i];
                                }
                            } else {
                                bag = bags[i] == null ? new InternalCachedBag(numInputs) : bags[i];
                                for (Object val : vals) {
                                    NullableTuple nTup = (NullableTuple) val;
                                    int index = nTup.getIndex();
                                    Tuple tup = pkgr.getValueTuple(keyWritable, nTup, index);
                                    bag.add(tup);
                                }
                            }
                            bags[i] = bag;
                            finished[i] = !readers.get(i).next();
                            if (finished[i]) {
                                break;
                            }
                            cur = readers.get(i).getCurrentKey();
                        }
                    }

                    if (bag == null) {
                        if (isAccumulative()) {
                            bags[i] = new AccumulativeBag(buffer, i);
                        } else {
                            bags[i] = new InternalCachedBag(numInputs);
                        }
                    }
                }
            } catch (IOException e) {
                throw new ExecException(e);
            }

            pkgr.attachInput(key, bags, readOnce);
            res = pkgr.getNext();
        }
        return res;
    }

    public void setInputKeys(List<String> inputKeys) {
        this.inputKeys = inputKeys;
    }

    public void addInputKey(String inputKey) {
        inputKeys.add(inputKey);
    }

    public void setSkewedJoins(boolean isSkewedJoin) {
        this.isSkewedJoin = isSkewedJoin;
    }

    public boolean isSkewedJoin() {
        return isSkewedJoin;
    }

    @Override
    public boolean supportsMultipleInputs() {
        return true;
    }

}
