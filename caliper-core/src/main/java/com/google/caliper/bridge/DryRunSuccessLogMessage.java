/*
 * Copyright (C) 2017 Google Inc.
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

package com.google.caliper.bridge;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import java.io.Serializable;

/** A log message listing the IDs of successful dry-run experiments. */
@AutoValue
public abstract class DryRunSuccessLogMessage extends LogMessage implements Serializable {
  private static final long serialVersionUID = 1L;

  /** Creates a new dry-run success log message for the given experiment IDs. */
  public static DryRunSuccessLogMessage create(Iterable<Integer> ids) {
    return new AutoValue_DryRunSuccessLogMessage(ImmutableSet.copyOf(ids));
  }

  /** Returns the IDs of the experiments that were successfully dry-run and not skipped. */
  public abstract ImmutableSet<Integer> ids();

  @Override
  public void accept(LogMessageVisitor visitor) {
    visitor.visit(this);
  }
}
