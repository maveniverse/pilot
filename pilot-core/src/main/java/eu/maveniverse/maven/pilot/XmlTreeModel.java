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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * XML document as a collapsible tree with syntax coloring.
 */
public class XmlTreeModel {

    public static class XmlNode {
        public final String tagName;
        public final String textContent;
        public final String attributes;
        public final int depth;
        public final List<XmlNode> children;
        public final boolean isComment;
        public boolean expanded;

        public XmlNode(String tagName, String textContent, String attributes, int depth, boolean isComment) {
            this.tagName = tagName;
            this.textContent = textContent;
            this.attributes = attributes;
            this.depth = depth;
            this.isComment = isComment;
            this.children = new ArrayList<>();
            this.expanded = depth < 2;
        }

        boolean hasChildren() {
            return !children.isEmpty();
        }

        boolean isLeaf() {
            return children.isEmpty() && textContent != null && !textContent.isEmpty();
        }
    }

    public final XmlNode root;

    private XmlTreeModel(XmlNode root) {
        this.root = root;
    }

    public static XmlTreeModel parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        XmlNode root = convertNode(doc.getDocumentElement(), 0);
        return new XmlTreeModel(root);
    }

    private static XmlNode convertNode(Node node, int depth) {
        if (node.getNodeType() == Node.COMMENT_NODE) {
            return new XmlNode("", node.getTextContent().trim(), null, depth, true);
        }

        String tagName = node.getNodeName();
        String attributes = extractAttributes(node);

        // Check if this is a simple text element (no child elements)
        String directText = null;
        boolean hasChildElements = false;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                hasChildElements = true;
                break;
            }
        }
        if (!hasChildElements) {
            directText = node.getTextContent();
            if (directText != null) {
                directText = directText.trim();
                if (directText.isEmpty()) directText = null;
            }
        }

        XmlNode xmlNode = new XmlNode(tagName, directText, attributes, depth, false);

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE || child.getNodeType() == Node.COMMENT_NODE) {
                xmlNode.children.add(convertNode(child, depth + 1));
            }
        }

        return xmlNode;
    }

    private static String extractAttributes(Node node) {
        NamedNodeMap attrs = node.getAttributes();
        if (attrs == null || attrs.getLength() == 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String name = attr.getNodeName();
            // Skip xmlns attributes for cleaner display
            if (name.startsWith("xmlns")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(name).append("=\"").append(attr.getNodeValue()).append('"');
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Returns visible nodes respecting expand/collapse state.
     */
    List<XmlNode> visibleNodes() {
        List<XmlNode> visible = new ArrayList<>();
        collectVisible(root, visible);
        return visible;
    }

    private void collectVisible(XmlNode node, List<XmlNode> visible) {
        visible.add(node);
        if (node.expanded) {
            for (XmlNode child : node.children) {
                collectVisible(child, visible);
            }
        }
    }

    /**
     * Create a syntax-highlighted line for an XML node.
     */
    static Line renderNode(XmlNode node) {
        List<Span> spans = new ArrayList<>();
        String indent = "  ".repeat(node.depth);
        spans.add(Span.raw(indent));

        if (node.isComment) {
            spans.add(Span.raw("<!-- " + node.textContent + " -->").fg(Color.DARK_GRAY));
            return Line.from(spans);
        }

        // Expand/collapse indicator
        if (node.hasChildren()) {
            spans.add(Span.raw(node.expanded ? "\u25BE " : "\u25B8 ").bold());
        } else {
            spans.add(Span.raw("  "));
        }

        Style tagStyle = Style.create().fg(Color.CYAN);
        Style attrStyle = Style.create().fg(Color.CYAN);
        Style valueStyle = Style.create().fg(Color.GREEN);
        Style textStyle = Style.create();

        if (node.isLeaf()) {
            // Inline element: <tag>value</tag>
            spans.add(Span.styled("<", tagStyle));
            spans.add(Span.styled(node.tagName, tagStyle));
            if (node.attributes != null) {
                spans.add(Span.raw(" "));
                spans.add(Span.styled(node.attributes, attrStyle));
            }
            spans.add(Span.styled(">", tagStyle));

            String text = node.textContent;
            if (text.startsWith("${")) {
                spans.add(Span.styled(text, Style.create().fg(Color.YELLOW)));
            } else {
                spans.add(Span.styled(text, textStyle));
            }

            spans.add(Span.styled("</", tagStyle));
            spans.add(Span.styled(node.tagName, tagStyle));
            spans.add(Span.styled(">", tagStyle));
        } else if (node.hasChildren()) {
            // Container element
            spans.add(Span.styled("<", tagStyle));
            spans.add(Span.styled(node.tagName, tagStyle));
            if (node.attributes != null) {
                spans.add(Span.raw(" "));
                spans.add(Span.styled(node.attributes, attrStyle));
            }
            spans.add(Span.styled(">", tagStyle));

            if (!node.expanded) {
                spans.add(Span.raw(" \u2026 ").dim());
                spans.add(Span.styled("</", tagStyle));
                spans.add(Span.styled(node.tagName, tagStyle));
                spans.add(Span.styled(">", tagStyle));
            }
        } else {
            // Empty element
            spans.add(Span.styled("<", tagStyle));
            spans.add(Span.styled(node.tagName, tagStyle));
            if (node.attributes != null) {
                spans.add(Span.raw(" "));
                spans.add(Span.styled(node.attributes, attrStyle));
            }
            spans.add(Span.styled("/>", tagStyle));
        }

        return Line.from(spans);
    }

    /**
     * Create a syntax-highlighted line with origin annotation.
     */
    static Line renderNodeWithOrigin(XmlNode node, String origin) {
        List<Span> spans = new ArrayList<>(renderNode(node).spans());

        if (origin != null && !origin.isEmpty()) {
            spans.add(Span.raw("  "));
            spans.add(Span.raw("\u2190 " + origin).fg(Color.YELLOW).dim());
        }

        return Line.from(spans);
    }
}
