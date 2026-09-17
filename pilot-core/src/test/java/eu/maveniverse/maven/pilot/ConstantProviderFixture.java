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
 * correctly detects JARs containing inlineable compile-time constants.
 *
 * <p>All fields here are {@code public static final} with primitive/String initializers,
 * which causes the Java compiler to emit a {@code ConstantValue} attribute in the class file.
 * When another class references these fields, the compiler inlines the literal value and emits
 * {@code LDC} instead of {@code GETSTATIC} — leaving no reference to this class in the
 * consumer's bytecode.</p>
 *
 * <p>This is the exact pattern used by {@code org.apache.maven.api.Constants}: all fields are
 * {@code public static final String} initialized with string literals, so any consumer that
 * uses e.g. {@code Constants.MAVEN_USER_CONF} compiles to {@code LDC "maven.user.conf"} with
 * no reference to the {@code Constants} class.</p>
 */
@SuppressWarnings("unused")
public final class ConstantProviderFixture {

    /** String constant — the canonical compile-time-constant case (e.g. maven.api.Constants). */
    public static final String STRING_CONSTANT = "some.config.key";

    /** Integer constant — also inlineable. */
    public static final int INT_CONSTANT = 42;

    /** Non-public constant — NOT detected by the scanner.
     *  The scanner filters on {@code ACC_PUBLIC}: non-public constants cannot be
     *  accessed (and therefore inlined) by code in other JARs, so they are excluded
     *  by design. Note: javac still emits a {@code ConstantValue} attribute for this
     *  field (JVMS §4.7.2 requires it for <em>all</em> {@code static final} compile-time
     *  constants regardless of access modifier); the {@code ACC_PUBLIC} guard in
     *  {@link ClassFileScanner} is what prevents it from triggering {@code UNDETERMINED}. */
    static final String PACKAGE_PRIVATE_CONSTANT = "internal";

    private ConstantProviderFixture() {}
}
