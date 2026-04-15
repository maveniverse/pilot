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

import static org.assertj.core.api.Assertions.assertThat;

import eu.maveniverse.domtrip.Comment;
import eu.maveniverse.domtrip.Element;
import org.junit.jupiter.api.Test;

class XmlTreeModelTest {

    private static final String SIMPLE_POM = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>test</artifactId>
              <version>1.0.0</version>
            </project>
            """;

    private static final String NESTED_POM = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>2.0.9</version>
                </dependency>
                <dependency>
                  <groupId>junit</groupId>
                  <artifactId>junit</artifactId>
                  <version>4.13.2</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void parseSimplePom() {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        assertThat(model.root.name()).isEqualTo("project");
        var children = model.root.childElements().toList();
        assertThat(children).hasSize(3);
        assertThat(children.get(0).name()).isEqualTo("groupId");
        assertThat(children.get(0).textContentTrimmed()).isEqualTo("com.example");
        assertThat(children.get(1).name()).isEqualTo("artifactId");
        assertThat(children.get(2).name()).isEqualTo("version");
    }

    @Test
    void parseNestedPom() {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        assertThat(model.root.name()).isEqualTo("project");
        var children = model.root.childElements().toList();
        assertThat(children).hasSize(1);

        Element deps = children.get(0);
        assertThat(deps.name()).isEqualTo("dependencies");
        assertThat(deps.childElements().count()).isEqualTo(2);

        Element firstDep = deps.childElements().toList().get(0);
        assertThat(firstDep.name()).isEqualTo("dependency");
        assertThat(firstDep.childElements().count()).isEqualTo(3);
    }

    @Test
    void leafNodeDetection() {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        assertThat(XmlTreeModel.isLeaf(model.root)).isFalse();
        assertThat(XmlTreeModel.hasTreeChildren(model.root)).isTrue();

        Element groupId = model.root.childElements().toList().get(0);
        assertThat(XmlTreeModel.isLeaf(groupId)).isTrue();
        assertThat(XmlTreeModel.hasTreeChildren(groupId)).isFalse();
    }

    @Test
    void visibleNodesRespectsExpansion() {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        // Root is depth 0 (relative), expanded by default
        // Dependencies is depth 1 (relative), expanded by default
        // Dependency elements are depth 2, collapsed by default
        var visible = model.visibleNodes();

        // root + dependencies + 2 dependency elements (collapsed, so their children hidden)
        assertThat(visible).hasSize(4);
        assertThat(((Element) visible.get(0)).name()).isEqualTo("project");
        assertThat(((Element) visible.get(1)).name()).isEqualTo("dependencies");
        assertThat(((Element) visible.get(2)).name()).isEqualTo("dependency");
        assertThat(((Element) visible.get(3)).name()).isEqualTo("dependency");
    }

    @Test
    void expandingNodeRevealsChildren() {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        // Expand first dependency
        Element deps = model.root.childElements().toList().get(0);
        Element firstDep = deps.childElements().toList().get(0);
        model.setExpanded(firstDep, true);

        var visible = model.visibleNodes();
        // root + dependencies + dep1 + 3 children (g,a,v) + dep2
        assertThat(visible).hasSize(7);
    }

    @Test
    void collapsingNodeHidesChildren() {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        // Collapse root
        model.setExpanded(model.root, false);

        var visible = model.visibleNodes();
        assertThat(visible).hasSize(1);
        assertThat(((Element) visible.get(0)).name()).isEqualTo("project");
    }

    @Test
    void renderNodeProducesLine() {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        Element groupId = model.root.childElements().toList().get(0);
        var line = model.renderNode(groupId);
        assertThat(line).isNotNull();
        String text = line.spans().stream().map(s -> s.content()).reduce("", String::concat);
        assertThat(text).contains("groupId").contains("com.example");
    }

    @Test
    void renderNodeWithOriginAddsAnnotation() {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        Element groupId = model.root.childElements().toList().get(0);
        var line = model.renderNodeWithOrigin(groupId, "parent:15");
        String text = line.spans().stream().map(s -> s.content()).reduce("", String::concat);
        assertThat(text).contains("parent:15");
    }

    @Test
    void parseXmlWithComments() {
        String xml = """
                <project>
                  <!-- This is a comment -->
                  <groupId>com.example</groupId>
                </project>
                """;
        XmlTreeModel model = XmlTreeModel.parse(xml);

        var treeChildren = XmlTreeModel.treeChildren(model.root);
        assertThat(treeChildren).hasSize(2);
        assertThat(treeChildren.get(0)).isInstanceOf(Comment.class);
        assertThat(((Comment) treeChildren.get(0)).content().trim()).isEqualTo("This is a comment");
        assertThat(treeChildren.get(1)).isInstanceOf(Element.class);
    }

    @Test
    void expandAllExpandsEntireSubtree() {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        // First collapse root to hide everything
        model.setExpanded(model.root, false);
        assertThat(model.visibleNodes()).hasSize(1);

        // Now expand all
        model.expandAll(model.root);
        var visible = model.visibleNodes();
        // root + dependencies + 2 deps + their children (3 each for first, 4 for second)
        assertThat(visible.size()).isGreaterThan(4);

        // All elements should be expanded
        for (var node : visible) {
            if (node instanceof Element e && XmlTreeModel.hasTreeChildren(e)) {
                assertThat(model.isExpanded(e)).isTrue();
            }
        }
    }

    @Test
    void collapseAllCollapsesEntireSubtree() {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        // First expand all
        model.expandAll(model.root);

        // Now collapse all
        model.collapseAll(model.root);
        assertThat(model.visibleNodes()).hasSize(1); // only root visible
        assertThat(model.isExpanded(model.root)).isFalse();
    }

    @Test
    void renderNodeContainerCollapsed() {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        // Collapse dependencies element
        Element deps = model.root.childElements().toList().get(0);
        model.setExpanded(deps, false);

        var line = model.renderNode(deps);
        String text = line.spans().stream().map(s -> s.content()).reduce("", String::concat);
        // Collapsed container should have ellipsis
        assertThat(text).contains("dependencies").contains("…");
    }

    @Test
    void renderNodeEmptyElement() {
        String xml = """
                <project>
                  <modules/>
                </project>
                """;
        XmlTreeModel model = XmlTreeModel.parse(xml);
        Element modules = model.root.childElements().toList().get(0);

        var line = model.renderNode(modules);
        String text = line.spans().stream().map(s -> s.content()).reduce("", String::concat);
        assertThat(text).contains("modules").contains("/>");
    }

    @Test
    void renderNodeWithAttributes() {
        String xml = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <plugin>
                    <configuration combine.children="append"/>
                  </plugin>
                </project>
                """;
        XmlTreeModel model = XmlTreeModel.parse(xml);
        model.expandAll(model.root);

        Element plugin = model.root.childElements().toList().get(0);
        Element config = plugin.childElements().toList().get(0);

        var line = model.renderNode(config);
        String text = line.spans().stream().map(s -> s.content()).reduce("", String::concat);
        assertThat(text).contains("combine.children=\"append\"");
    }

    @Test
    void renderNodeWithOriginEmptyOrigin() {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);
        Element groupId = model.root.childElements().toList().get(0);

        var lineWithNull = model.renderNodeWithOrigin(groupId, null);
        var lineWithEmpty = model.renderNodeWithOrigin(groupId, "");
        var lineWithoutOrigin = model.renderNode(groupId);

        // Should have same number of spans (no origin annotation added)
        assertThat(lineWithNull.spans().size())
                .isEqualTo(lineWithoutOrigin.spans().size());
        assertThat(lineWithEmpty.spans().size())
                .isEqualTo(lineWithoutOrigin.spans().size());
    }

    @Test
    void renderCommentNode() {
        String xml = """
                <project>
                  <!-- This is a comment -->
                  <groupId>com.example</groupId>
                </project>
                """;
        XmlTreeModel model = XmlTreeModel.parse(xml);
        var children = XmlTreeModel.treeChildren(model.root);
        var commentNode = children.get(0);

        var line = model.renderNode(commentNode);
        String text = line.spans().stream().map(s -> s.content()).reduce("", String::concat);
        assertThat(text).contains("<!--").contains("This is a comment").contains("-->");
    }

    @Test
    void relativeDepthIsZeroForRoot() {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);
        assertThat(model.relativeDepth(model.root)).isZero();
    }

    @Test
    void propertyReferenceInTextContent() {
        String xml = """
                <project>
                  <version>${project.version}</version>
                </project>
                """;
        XmlTreeModel model = XmlTreeModel.parse(xml);

        Element versionNode = model.root.childElements().toList().get(0);
        assertThat(versionNode.textContentTrimmed()).isEqualTo("${project.version}");
        assertThat(versionNode.textContentTrimmed().startsWith("${")).isTrue();
    }
}
