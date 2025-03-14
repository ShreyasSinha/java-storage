/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.storage;

import com.google.storage.v2.BidiReadObjectResponse;
import com.google.storage.v2.ReadObjectResponse;
import java.io.Closeable;
import java.io.IOException;

interface ResponseContentLifecycleManager<Response> extends Closeable {
  ResponseContentLifecycleHandle get(Response response);

  @Override
  default void close() throws IOException {}

  static ResponseContentLifecycleManager<ReadObjectResponse> noop() {
    return response ->
        ResponseContentLifecycleHandle.create(
            response,
            StorageV2ProtoUtils.READ_OBJECT_RESPONSE_TO_BYTE_BUFFERS_FUNCTION,
            () -> {
              // no-op
            });
  }

  static ResponseContentLifecycleManager<BidiReadObjectResponse> noopBidiReadObjectResponse() {
    return response ->
        ResponseContentLifecycleHandle.create(
            response,
            StorageV2ProtoUtils.BIDI_READ_OBJECT_RESPONSE_TO_BYTE_BUFFERS_FUNCTION,
            () -> {
              // no-op
            });
  }
}
