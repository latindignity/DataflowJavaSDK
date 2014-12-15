/*******************************************************************************
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/

package com.google.cloud.dataflow.sdk.runners.worker;

import static com.google.cloud.dataflow.sdk.util.Structs.getLong;
import static com.google.cloud.dataflow.sdk.util.Structs.getStrings;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.CloudObject;
import com.google.cloud.dataflow.sdk.util.ExecutionContext;
import com.google.cloud.dataflow.sdk.util.PropertyNames;

import java.util.Collections;

/**
 * Creates an InMemorySource from a CloudObject spec.
 */
public class InMemorySourceFactory {
  // Do not instantiate.
  private InMemorySourceFactory() {}

  public static <T> InMemorySource<T> create(PipelineOptions options,
                                             CloudObject spec,
                                             Coder<T> coder,
                                             ExecutionContext executionContext)
      throws Exception {
    return create(spec, coder);
  }

  static <T> InMemorySource<T> create(CloudObject spec,
                                      Coder<T> coder) throws Exception {
    return new InMemorySource<>(
        getStrings(spec,
            PropertyNames.ELEMENTS, Collections.<String>emptyList()),
        getLong(spec, PropertyNames.START_INDEX, null),
        getLong(spec, PropertyNames.END_INDEX, null),
        coder);
  }
}