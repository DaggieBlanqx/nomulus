// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.model.index;

import static com.google.domain.registry.util.TypeUtils.instantiate;

import com.google.common.annotations.VisibleForTesting;
import com.google.domain.registry.model.BackupGroupRoot;
import com.google.domain.registry.model.EppResource;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;

/** An index that allows for quick enumeration of all EppResource entities (e.g. via map reduce). */
@Entity
public class EppResourceIndex extends BackupGroupRoot {

  @Id
  String id;

  @Parent
  Key<EppResourceIndexBucket> bucket;

  Ref<? extends EppResource> reference;

  @Index
  String kind;

  public Ref<? extends EppResource> getReference() {
    return reference;
  }

  @VisibleForTesting
  public Key<EppResourceIndexBucket> getBucket() {
    return bucket;
  }

  @VisibleForTesting
  public static <T extends EppResource> EppResourceIndex create(
      Key<EppResourceIndexBucket> bucket, Key<T> resourceKey) {
    EppResourceIndex instance = instantiate(EppResourceIndex.class);
    instance.reference = Ref.create(resourceKey);
    instance.kind = resourceKey.getKind();
    instance.id = resourceKey.getString();  // creates a web-safe key string
    instance.bucket = bucket;
    return instance;
  }

  public static <T extends EppResource> EppResourceIndex create(Key<T> resourceKey) {
    return create(EppResourceIndexBucket.getBucketKey(resourceKey), resourceKey);
  }
}