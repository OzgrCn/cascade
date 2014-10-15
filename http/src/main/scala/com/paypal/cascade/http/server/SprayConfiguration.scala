/**
 * Copyright 2013-2014 PayPal
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
package com.paypal.cascade.http.server

import spray.routing.Route

/**
 * This class provides configuration information for a spray service
 */
class SprayConfiguration(val serviceName: String, val port: Int, val backlog: Int, val route: Route)

object SprayConfiguration {
  def apply(serviceName: String, port: Int, backlog: Int)(route: Route): SprayConfiguration = {
    new SprayConfiguration(serviceName, port, backlog, route)
  }
}
