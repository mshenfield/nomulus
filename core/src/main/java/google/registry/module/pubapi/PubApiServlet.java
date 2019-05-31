// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.module.pubapi;

import com.google.monitoring.metrics.MetricReporter;
import dagger.Lazy;
import google.registry.module.ServletBase;

/** Servlet that should handle all requests to our "default" App Engine module. */
public final class PubApiServlet extends ServletBase {

  private static final PubApiComponent component = DaggerPubApiComponent.create();
  private static final PubApiRequestHandler requestHandler = component.requestHandler();
  private static final Lazy<MetricReporter> metricReporter = component.metricReporter();

  public PubApiServlet() {
    super(requestHandler, metricReporter);
  }
}
