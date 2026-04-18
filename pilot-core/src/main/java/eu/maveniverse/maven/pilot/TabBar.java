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

import dev.tamboui.style.Color;
import dev.tamboui.text.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shared tab bar rendering and cycling for TUI views.
 */
class TabBar {

    private TabBar() {}

    /**
     * Render tab indicators for the given views using the default active color (YELLOW).
     */
    static <V extends Enum<V>> List<Span> render(V currentView, V[] allViews, Function<V, String> labelFn) {
        return render(currentView, allViews, labelFn, v -> Color.YELLOW);
    }

    /**
     * Render tab indicators with a custom active color per view.
     */
    static <V extends Enum<V>> List<Span> render(
            V currentView, V[] allViews, Function<V, String> labelFn, Function<V, Color> activeColorFn) {
        List<Span> spans = new ArrayList<>();
        for (V v : allViews) {
            spans.add(Span.raw("  "));
            boolean active = v == currentView;
            String label = labelFn.apply(v);
            Color fg = active ? activeColorFn.apply(v) : Color.DARK_GRAY;
            spans.add(Span.raw("[" + (active ? "\u25B8 " : "  ") + label + "]").fg(fg));
        }
        return spans;
    }

    /**
     * Cycle to the next enum value, wrapping around from last to first.
     */
    static <V extends Enum<V>> V next(V current, V[] values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }
}
