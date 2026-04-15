/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package eu.maveniverse.maven.pilot.mvn4;

import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Provides;
import org.codehaus.plexus.components.secdispatcher.Dispatcher;
import org.codehaus.plexus.components.secdispatcher.internal.dispatchers.LegacyDispatcher;

/**
 * Bridges plexus-sec-dispatcher classes (annotated with {@code javax.inject.Named})
 * into Maven 4's DI framework (which only scans for {@code org.apache.maven.api.di.Named}).
 */
@Named
class SecDispatcherBindings {

    @Provides
    @Named("legacy")
    Dispatcher legacyDispatcher() {
        return new LegacyDispatcher();
    }
}
