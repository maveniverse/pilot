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
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import eu.maveniverse.domtrip.Comment;
import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.Element;
import eu.maveniverse.domtrip.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XML document as a collapsible tree with syntax coloring, backed by DomTrip.
 */
class XmlTreeModel {

    final Element root;
    private final Set<Node> expandedNodes = Collections.newSetFromMap(new IdentityHashMap<>());

    private XmlTreeModel(Document document) {
        this.root = document.root();
        initExpandState(root);
    }

    static XmlTreeModel parse(String xml) {
        return new XmlTreeModel(Document.of(xml));
    }

    int relativeDepth(Node node) {
        return node.depth() - root.depth();
    }

    boolean isExpanded(Node node) {
        return expandedNodes.contains(node);
    }

    void setExpanded(Node node, boolean expanded) {
        if (expanded) {
            expandedNodes.add(node);
        } else {
            expandedNodes.remove(node);
        }
    }

    void expandAll(Element node) {
        expandedNodes.add(node);
        node.childElements().forEach(this::expandAll);
    }

    void collapseAll(Element node) {
        expandedNodes.remove(node);
        node.childElements().forEach(this::collapseAll);
    }

    /**
     * Returns visible nodes respecting expand/collapse state.
     */
    List<Node> visibleNodes() {
        List<Node> visible = new ArrayList<>();
        collectVisible(root, visible);
        return visible;
    }

    private void collectVisible(Element element, List<Node> visible) {
        visible.add(element);
        if (isExpanded(element)) {
            for (Node child : treeChildren(element)) {
                if (child instanceof Element e) {
                    collectVisible(e, visible);
                } else {
                    visible.add(child);
                }
            }
        }
    }

    /**
     * Returns the children of an element that are visible in the tree
     * (elements and comments, skipping text nodes).
     */
    static List<Node> treeChildren(Element element) {
        return element.children()
                .filter(n -> n instanceof Element || n instanceof Comment)
                .toList();
    }

    static boolean hasTreeChildren(Element element) {
        return element.children().anyMatch(n -> n instanceof Element || n instanceof Comment);
    }

    static boolean isLeaf(Element element) {
        return !element.hasChildElements() && !element.textContentTrimmedOr("").isEmpty();
    }

    private void initExpandState(Element element) {
        if (relativeDepth(element) < 2) {
            expandedNodes.add(element);
        }
        element.childElements().forEach(this::initExpandState);
    }

    /**
     * Create a syntax-highlighted line for an XML node.
     */
    Line renderNode(Node node) {
        List<Span> spans = new ArrayList<>();
        String indent = "  ".repeat(relativeDepth(node));
        spans.add(Span.raw(indent));

        if (node instanceof Comment comment) {
            spans.add(Span.raw("<!-- " + comment.content().trim() + " -->").fg(Color.DARK_GRAY));
            return Line.from(spans);
        }

        Element element = (Element) node;

        // Expand/collapse indicator
        if (hasTreeChildren(element)) {
            spans.add(Span.raw(isExpanded(element) ? "\u25BE " : "\u25B8 ").bold());
        } else {
            spans.add(Span.raw("  "));
        }

        Style tagStyle = Style.create().fg(Color.BLUE);
        Style attrStyle = Style.create().fg(Color.CYAN);
        Style textStyle = Style.create();

        String attrs = formatAttributes(element);

        if (isLeaf(element)) {
            // Inline element: <tag>value</tag>
            spans.add(Span.styled("<", tagStyle));
            spans.add(Span.styled(element.name(), tagStyle));
            if (attrs != null) {
                spans.add(Span.raw(" "));
                spans.add(Span.styled(attrs, attrStyle));
            }
            spans.add(Span.styled(">", tagStyle));

            String text = element.textContentTrimmedOr("");
            if (text.startsWith("${")) {
                spans.add(Span.styled(text, Style.create().fg(Color.YELLOW)));
            } else {
                spans.add(Span.styled(text, textStyle));
            }

            spans.add(Span.styled("</", tagStyle));
            spans.add(Span.styled(element.name(), tagStyle));
            spans.add(Span.styled(">", tagStyle));
        } else if (hasTreeChildren(element)) {
            // Container element
            spans.add(Span.styled("<", tagStyle));
            spans.add(Span.styled(element.name(), tagStyle));
            if (attrs != null) {
                spans.add(Span.raw(" "));
                spans.add(Span.styled(attrs, attrStyle));
            }
            spans.add(Span.styled(">", tagStyle));

            if (!isExpanded(element)) {
                spans.add(Span.raw(" \u2026 ").dim());
                spans.add(Span.styled("</", tagStyle));
                spans.add(Span.styled(element.name(), tagStyle));
                spans.add(Span.styled(">", tagStyle));
            }
        } else {
            // Empty element
            spans.add(Span.styled("<", tagStyle));
            spans.add(Span.styled(element.name(), tagStyle));
            if (attrs != null) {
                spans.add(Span.raw(" "));
                spans.add(Span.styled(attrs, attrStyle));
            }
            spans.add(Span.styled("/>", tagStyle));
        }

        return Line.from(spans);
    }

    /**
     * Create a syntax-highlighted line with origin annotation.
     */
    Line renderNodeWithOrigin(Node node, String origin) {
        List<Span> spans = new ArrayList<>(renderNode(node).spans());

        if (origin != null && !origin.isEmpty()) {
            spans.add(Span.raw("  "));
            spans.add(Span.raw("\u2190 " + origin).fg(Color.YELLOW).dim());
        }

        return Line.from(spans);
    }

    private static String formatAttributes(Element element) {
        Map<String, String> attrs = element.attributes();
        if (attrs.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (var entry : attrs.entrySet()) {
            if (entry.getKey().startsWith("xmlns")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(entry.getKey()).append("=\"").append(entry.getValue()).append('"');
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
