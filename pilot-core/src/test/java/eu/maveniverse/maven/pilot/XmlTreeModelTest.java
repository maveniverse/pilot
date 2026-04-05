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
    void parseSimplePom() throws Exception {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        assertThat(model.root.tagName).isEqualTo("project");
        assertThat(model.root.children).hasSize(3);
        assertThat(model.root.children.get(0).tagName).isEqualTo("groupId");
        assertThat(model.root.children.get(0).textContent).isEqualTo("com.example");
        assertThat(model.root.children.get(1).tagName).isEqualTo("artifactId");
        assertThat(model.root.children.get(2).tagName).isEqualTo("version");
    }

    @Test
    void parseNestedPom() throws Exception {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        assertThat(model.root.tagName).isEqualTo("project");
        assertThat(model.root.children).hasSize(1);

        var deps = model.root.children.get(0);
        assertThat(deps.tagName).isEqualTo("dependencies");
        assertThat(deps.children).hasSize(2);

        var firstDep = deps.children.get(0);
        assertThat(firstDep.tagName).isEqualTo("dependency");
        assertThat(firstDep.children).hasSize(3);
    }

    @Test
    void leafNodeDetection() throws Exception {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        assertThat(model.root.isLeaf()).isFalse();
        assertThat(model.root.hasChildren()).isTrue();
        assertThat(model.root.children.get(0).isLeaf()).isTrue();
        assertThat(model.root.children.get(0).hasChildren()).isFalse();
    }

    @Test
    void visibleNodesRespectsExpansion() throws Exception {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        // Root is depth 0, expanded by default (depth < 2)
        // Dependencies is depth 1, expanded by default
        // Dependency elements are depth 2, collapsed by default
        var visible = model.visibleNodes();

        // root + dependencies + 2 dependency elements (collapsed, so their children hidden)
        assertThat(visible).hasSize(4);
        assertThat(visible.get(0).tagName).isEqualTo("project");
        assertThat(visible.get(1).tagName).isEqualTo("dependencies");
        assertThat(visible.get(2).tagName).isEqualTo("dependency");
        assertThat(visible.get(3).tagName).isEqualTo("dependency");
    }

    @Test
    void expandingNodeRevealsChildren() throws Exception {
        XmlTreeModel model = XmlTreeModel.parse(NESTED_POM);

        // Expand first dependency
        var deps = model.root.children.get(0);
        var firstDep = deps.children.get(0);
        firstDep.expanded = true;

        var visible = model.visibleNodes();
        // root + dependencies + dep1 + 3 children (g,a,v) + dep2
        assertThat(visible).hasSize(7);
    }

    @Test
    void collapsingNodeHidesChildren() throws Exception {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        // Collapse root
        model.root.expanded = false;

        var visible = model.visibleNodes();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).tagName).isEqualTo("project");
    }

    @Test
    void renderNodeProducesLine() throws Exception {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        var line = XmlTreeModel.renderNode(model.root.children.get(0));
        assertThat(line).isNotNull();
        // The line should contain the tag name and text content
        String text = line.spans().stream().map(s -> s.content()).reduce("", String::concat);
        assertThat(text).contains("groupId").contains("com.example");
    }

    @Test
    void renderNodeWithOriginAddsAnnotation() throws Exception {
        XmlTreeModel model = XmlTreeModel.parse(SIMPLE_POM);

        var line = XmlTreeModel.renderNodeWithOrigin(model.root.children.get(0), "parent:15");
        String text = line.spans().stream().map(s -> s.content()).reduce("", String::concat);
        assertThat(text).contains("parent:15");
    }

    @Test
    void parseXmlWithComments() throws Exception {
        String xml = """
                <project>
                  <!-- This is a comment -->
                  <groupId>com.example</groupId>
                </project>
                """;
        XmlTreeModel model = XmlTreeModel.parse(xml);

        assertThat(model.root.children).hasSize(2);
        assertThat(model.root.children.get(0).isComment).isTrue();
        assertThat(model.root.children.get(0).textContent).isEqualTo("This is a comment");
        assertThat(model.root.children.get(1).isComment).isFalse();
    }

    @Test
    void propertyReferenceInTextContent() throws Exception {
        String xml = """
                <project>
                  <version>${project.version}</version>
                </project>
                """;
        XmlTreeModel model = XmlTreeModel.parse(xml);

        var versionNode = model.root.children.get(0);
        assertThat(versionNode.textContent).isEqualTo("${project.version}");
        assertThat(versionNode.textContent.startsWith("${")).isTrue();
    }
}
