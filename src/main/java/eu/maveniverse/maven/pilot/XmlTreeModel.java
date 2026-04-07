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

    /**
     * Create an XmlTreeModel backed by the provided XML document and initialize its expansion state.
     *
     * @param document the parsed XML document whose root element will be used as the model's root
     */
    private XmlTreeModel(Document document) {
        this.root = document.root();
        initExpandState(root);
    }

    /**
     * Create an XmlTreeModel from the given XML string.
     *
     * @param xml the XML document as a string
     * @return an XmlTreeModel representing the parsed document root with initial expansion state
     */
    static XmlTreeModel parse(String xml) {
        return new XmlTreeModel(Document.of(xml));
    }

    /**
     * Compute a node's depth relative to the tree model root.
     *
     * @param node the node whose relative depth to compute
     * @return the node's depth minus the root's depth (0 for the root)
     */
    int relativeDepth(Node node) {
        return node.depth() - root.depth();
    }

    /**
     * Checks whether a node is currently expanded in the tree model.
     *
     * @param node the node whose expansion state to query
     * @return `true` if the node is currently expanded in the model, `false` otherwise
     */
    boolean isExpanded(Node node) {
        return expandedNodes.contains(node);
    }

    /**
     * Sets the expansion state for the given node in the tree model.
     *
     * @param node the node whose expansion state will be updated
     * @param expanded `true` to mark the node as expanded, `false` to mark it as collapsed
     */
    void setExpanded(Node node, boolean expanded) {
        if (expanded) {
            expandedNodes.add(node);
        } else {
            expandedNodes.remove(node);
        }
    }

    /**
     * Mark the given element and all of its descendant elements as expanded.
     *
     * @param node the root element whose subtree should be expanded
     */
    void expandAll(Element node) {
        expandedNodes.add(node);
        node.childElements().forEach(this::expandAll);
    }

    /**
     * Collapses the given element and all of its descendant elements in the tree.
     *
     * Removes the element and every descendant from the model's expansion state so they will render as collapsed.
     *
     * @param node the element to collapse along with its child elements
     */
    void collapseAll(Element node) {
        expandedNodes.remove(node);
        node.childElements().forEach(this::collapseAll);
    }

    /**
     * Produce the list of document nodes that are currently visible in the tree view.
     *
     * The list is in tree/document order and includes an element node followed by its
     * visible descendants (comments and elements) according to the model's expand/collapse state.
     *
     * @return a list of visible {@code Node} instances in tree/document order
     */
    List<Node> visibleNodes() {
        List<Node> visible = new ArrayList<>();
        collectVisible(root, visible);
        return visible;
    }

    /**
     * Appends the provided element and any of its visible descendant nodes to the given list,
     * respecting the model's expansion state.
     *
     * @param element the element whose visible subtree should be collected
     * @param visible the list to append visible nodes to
     */
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
     * List the element's child nodes that should be shown in the tree.
     *
     * <p>Includes only child nodes that are Element or Comment; text nodes and other node types are omitted.</p>
     *
     * @param element the parent element whose children are being queried
     * @return a list of the element's children that are either `Element` or `Comment`, in document order
     */
    static List<Node> treeChildren(Element element) {
        return element.children()
                .filter(n -> n instanceof Element || n instanceof Comment)
                .toList();
    }

    /**
     * Determines whether the given element has any child elements or comment nodes.
     *
     * @param element the element to inspect
     * @return {@code true} if the element has at least one child that is an {@code Element} or {@code Comment}, {@code false} otherwise
     */
    static boolean hasTreeChildren(Element element) {
        return element.children().anyMatch(n -> n instanceof Element || n instanceof Comment);
    }

    /**
     * Determines whether an element is a leaf node: it has no child elements and contains non-empty trimmed text.
     *
     * @param element the element to check
     * @return `true` if the element has no child elements and its trimmed text content is not empty, `false` otherwise
     */
    static boolean isLeaf(Element element) {
        return !element.hasChildElements() && !element.textContentTrimmedOr("").isEmpty();
    }

    /**
     * Initializes expansion state for an element subtree.
     *
     * Adds the element to the model's expanded set when its depth relative to the model root is less than 2,
     * then applies the same rule recursively to each child element.
     *
     * @param element the subtree root whose expansion state should be initialized
     */
    private void initExpandState(Element element) {
        if (relativeDepth(element) < 2) {
            expandedNodes.add(element);
        }
        element.childElements().forEach(this::initExpandState);
    }

    /**
     * Render an XML node as a syntax-colored Line suitable for display in the tree.
     *
     * Produces a Line composed of styled spans representing the given node:
     * - Comment nodes render as "<!-- ... -->" in dark gray.
     * - Element nodes render with indentation, an expand/collapse glyph when they have tree children,
     *   blue tag names, cyan attributes, and plain text content styling. Leaf elements are rendered
     *   inline as "<tag ...>text</tag>" and include the element's trimmed text content; if the text
     *   begins with "${" it is colored yellow. Container elements render an opening tag and, when
     *   collapsed, a dim ellipsis ("…") followed by the closing tag. Empty elements render as self-closing.
     *
     * @param node the DOM node to render; expected to be an Element or a Comment
     * @return a Line containing styled spans that visually represent the node
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

        if (isLeaf(element)) {
            renderLeafElement(spans, element, tagStyle, attrStyle);
        } else if (hasTreeChildren(element)) {
            renderContainerElement(spans, element, tagStyle, attrStyle);
        } else {
            renderEmptyElement(spans, element, tagStyle, attrStyle);
        }

        return Line.from(spans);
    }

    private void renderLeafElement(List<Span> spans, Element element, Style tagStyle, Style attrStyle) {
        addTagOpen(spans, element, tagStyle, attrStyle, ">");
        String text = element.textContentTrimmedOr("");
        if (text.startsWith("${")) {
            spans.add(Span.styled(text, Style.create().fg(Color.YELLOW)));
        } else {
            spans.add(Span.styled(text, Style.create()));
        }
        addTagClose(spans, element, tagStyle);
    }

    private void renderContainerElement(List<Span> spans, Element element, Style tagStyle, Style attrStyle) {
        addTagOpen(spans, element, tagStyle, attrStyle, ">");
        if (!isExpanded(element)) {
            spans.add(Span.raw(" \u2026 ").dim());
            addTagClose(spans, element, tagStyle);
        }
    }

    private void renderEmptyElement(List<Span> spans, Element element, Style tagStyle, Style attrStyle) {
        addTagOpen(spans, element, tagStyle, attrStyle, "/>");
    }

    private void addTagOpen(List<Span> spans, Element element, Style tagStyle, Style attrStyle, String closeToken) {
        spans.add(Span.styled("<", tagStyle));
        spans.add(Span.styled(element.name(), tagStyle));
        String attrs = formatAttributes(element);
        if (attrs != null) {
            spans.add(Span.raw(" "));
            spans.add(Span.styled(attrs, attrStyle));
        }
        spans.add(Span.styled(closeToken, tagStyle));
    }

    private void addTagClose(List<Span> spans, Element element, Style tagStyle) {
        spans.add(Span.styled("</", tagStyle));
        spans.add(Span.styled(element.name(), tagStyle));
        spans.add(Span.styled(">", tagStyle));
    }

    /**
     * Render the given node as a syntax-highlighted line and, when provided,
     * append an origin annotation.
     *
     * @param node   the DOM node to render
     * @param origin optional origin text to append; when non-empty it is added as "← {origin}" in yellow and dim after two spaces
     * @return       a Line representing the rendered node with the optional origin annotation
     */
    Line renderNodeWithOrigin(Node node, String origin) {
        List<Span> spans = new ArrayList<>(renderNode(node).spans());

        if (origin != null && !origin.isEmpty()) {
            spans.add(Span.raw("  "));
            spans.add(Span.raw("\u2190 " + origin).fg(Color.YELLOW).dim());
        }

        return Line.from(spans);
    }

    /**
     * Builds a space-separated attribute string for an element, excluding XML namespace declarations.
     *
     * <p>Attributes are formatted as `key="value"` and joined with single spaces. Attributes whose
     * names start with `"xmlns"` are omitted.</p>
     *
     * @param element the element whose attributes to format
     * @return a space-separated `key="value"` string of attributes, or `null` if no attributes remain after filtering
     */
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
