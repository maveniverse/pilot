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

import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

class SearchController {

    @FunctionalInterface
    interface MatchFinder {
        List<Integer> findMatches(String query);
    }

    @FunctionalInterface
    interface MatchSelector {
        void selectMatch(int rowIndex);
    }

    private boolean searchMode;
    private final StringBuilder searchBuffer = new StringBuilder();
    private String activeSearch;
    private List<Integer> searchMatches = List.of();
    private int searchMatchIndex = -1;

    private final MatchFinder matchFinder;
    private final MatchSelector matchSelector;

    SearchController(MatchFinder matchFinder, MatchSelector matchSelector) {
        this.matchFinder = matchFinder;
        this.matchSelector = matchSelector;
    }

    boolean handleSearchInput(KeyEvent key) {
        if (searchMode) {
            if (key.isKey(KeyCode.ESCAPE)) {
                searchMode = false;
                activeSearch = null;
                searchMatches = List.of();
                searchMatchIndex = -1;
                return true;
            }
            if (key.isKey(KeyCode.ENTER)) {
                searchMode = false;
                if (!searchBuffer.isEmpty()) {
                    activeSearch = searchBuffer.toString().toLowerCase();
                }
                return true;
            }
            if (key.isKey(KeyCode.BACKSPACE) && !searchBuffer.isEmpty()) {
                searchBuffer.deleteCharAt(searchBuffer.length() - 1);
                updateMatches();
                return true;
            }
            if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                searchBuffer.append(key.character());
                updateMatches();
                return true;
            }
            return true;
        }

        if (key.isCharIgnoreCase('/')) {
            searchMode = true;
            searchBuffer.setLength(0);
            activeSearch = null;
            searchMatches = List.of();
            searchMatchIndex = -1;
            return true;
        }
        if (key.isKey(KeyCode.ESCAPE) && activeSearch != null) {
            activeSearch = null;
            searchMatches = List.of();
            searchMatchIndex = -1;
            return true;
        }
        if (key.isChar('n') && activeSearch != null && !searchMatches.isEmpty()) {
            searchMatchIndex = (searchMatchIndex + 1) % searchMatches.size();
            matchSelector.selectMatch(searchMatches.get(searchMatchIndex));
            return true;
        }
        if (key.isChar('N') && activeSearch != null && !searchMatches.isEmpty()) {
            searchMatchIndex = (searchMatchIndex - 1 + searchMatches.size()) % searchMatches.size();
            matchSelector.selectMatch(searchMatches.get(searchMatchIndex));
            return true;
        }
        return false;
    }

    private void updateMatches() {
        String query = searchBuffer.toString().toLowerCase();
        if (query.isEmpty()) {
            searchMatches = List.of();
            searchMatchIndex = -1;
            return;
        }
        searchMatches = matchFinder.findMatches(query);
        if (!searchMatches.isEmpty()) {
            searchMatchIndex = 0;
            matchSelector.selectMatch(searchMatches.get(0));
        } else {
            searchMatchIndex = -1;
        }
    }

    String searchStatus() {
        if (searchMode) {
            return "Search: " + searchBuffer + "█";
        }
        if (activeSearch != null && !searchMatches.isEmpty()) {
            return (searchMatchIndex + 1) + "/" + searchMatches.size() + " matches";
        }
        if (activeSearch != null) {
            return "No matches for: " + activeSearch;
        }
        return null;
    }

    List<Span> searchKeyHints() {
        List<Span> spans = new ArrayList<>();
        if (searchMode) {
            spans.add(Span.raw("Type").bold());
            spans.add(Span.raw(":Search  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Confirm  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Cancel"));
        } else if (activeSearch != null) {
            spans.add(Span.raw("n/N").bold());
            spans.add(Span.raw(":Next/Prev  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Clear"));
        }
        return spans;
    }

    String currentSearchQuery() {
        if (searchMode && !searchBuffer.isEmpty()) {
            return searchBuffer.toString().toLowerCase();
        }
        return activeSearch;
    }

    boolean isSearchMatch(int rowIndex) {
        return searchMatches.contains(rowIndex);
    }

    boolean isSearchMode() {
        return searchMode;
    }

    void clear() {
        searchMode = false;
        searchBuffer.setLength(0);
        activeSearch = null;
        searchMatches = List.of();
        searchMatchIndex = -1;
    }
}
