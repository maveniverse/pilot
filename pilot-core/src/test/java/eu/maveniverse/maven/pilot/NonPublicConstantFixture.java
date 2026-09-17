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
package eu.maveniverse.maven.pilot;

/**
 * Fixture class used by {@link ClassFileScannerTest} to verify that the bytecode scanner
 * correctly ignores JARs whose constants are not {@code public}.
 *
 * <p>All fields here are non-public {@code static final} with primitive/String initializers.
 * javac still emits a {@code ConstantValue} attribute for each (JVMS §4.7.2 requires it for
 * <em>all</em> {@code static final} compile-time constants regardless of access modifier),
 * but they cannot be inlined by code in other JARs because they lack the {@code public}
 * modifier.  The scanner's {@code ACC_PUBLIC} guard must reject them.</p>
 *
 * <p>This fixture covers a distinct boundary from {@link AnnotationFixture} (which has no
 * static fields at all): a regression that accidentally removed the {@code ACC_PUBLIC} mask
 * check in {@link ClassFileScanner} would still pass an {@code AnnotationFixture}-based test
 * but would fail against this fixture.</p>
 */
@SuppressWarnings("unused")
final class NonPublicConstantFixture {

    /** Package-private constant — javac emits {@code ConstantValue}, but {@code ACC_PUBLIC} is absent. */
    static final String PACKAGE_PRIVATE_CONSTANT = "internal.key";

    /** Private constant — also carries {@code ConstantValue} but definitely not public. */
    private static final int PRIVATE_CONSTANT = 99;

    private NonPublicConstantFixture() {}
}
