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
package eu.maveniverse.maven.pilot.mvn3;

import static org.assertj.core.api.Assertions.assertThat;

import eu.maveniverse.maven.pilot.*;
import java.util.IdentityHashMap;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.util.artifact.DefaultArtifactTypeRegistry;
import org.junit.jupiter.api.Test;

class MojoHelperTest {

    @Test
    void convertBasicDependency() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("org.slf4j");
        dep.setArtifactId("slf4j-api");
        dep.setVersion("2.0.9");
        dep.setScope("compile");

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.getArtifact().getGroupId()).isEqualTo("org.slf4j");
        assertThat(result.getArtifact().getArtifactId()).isEqualTo("slf4j-api");
        assertThat(result.getArtifact().getVersion()).isEqualTo("2.0.9");
        assertThat(result.getScope()).isEqualTo("compile");
        assertThat(result.isOptional()).isFalse();
    }

    @Test
    void convertDependencyWithNullClassifierAndType() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.getArtifact().getClassifier()).isEmpty();
        assertThat(result.getArtifact().getExtension()).isEqualTo("jar");
    }

    @Test
    void convertDependencyWithClassifierAndType() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");
        dep.setClassifier("sources");
        dep.setType("test-jar");

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.getArtifact().getClassifier()).isEqualTo("sources");
        assertThat(result.getArtifact().getExtension()).isEqualTo("test-jar");
    }

    @Test
    void convertOptionalDependency() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("optional-lib");
        dep.setVersion("1.0");
        dep.setOptional("true");

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.isOptional()).isTrue();
    }

    @Test
    void convertDependencyWithExclusions() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");

        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId("org.unwanted");
        exclusion.setArtifactId("bad-lib");
        dep.addExclusion(exclusion);

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.getExclusions()).hasSize(1);
        var ex = result.getExclusions().iterator().next();
        assertThat(ex.getGroupId()).isEqualTo("org.unwanted");
        assertThat(ex.getArtifactId()).isEqualTo("bad-lib");
        assertThat(ex.getClassifier()).isEqualTo("*");
        assertThat(ex.getExtension()).isEqualTo("*");
    }

    @Test
    void convertDependenciesReturnsList() {
        var dep1 = new org.apache.maven.model.Dependency();
        dep1.setGroupId("g1");
        dep1.setArtifactId("a1");
        dep1.setVersion("1.0");

        var dep2 = new org.apache.maven.model.Dependency();
        dep2.setGroupId("g2");
        dep2.setArtifactId("a2");
        dep2.setVersion("2.0");

        List<Dependency> result = MojoHelper.convertDependencies(List.of(dep1, dep2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getArtifact().getGroupId()).isEqualTo("g1");
        assertThat(result.get(1).getArtifact().getGroupId()).isEqualTo("g2");
    }

    @Test
    void convertDependenciesReturnsEmptyForNull() {
        assertThat(MojoHelper.convertDependencies(null)).isEmpty();
    }

    @Test
    void convertDependenciesReturnsEmptyForEmptyList() {
        assertThat(MojoHelper.convertDependencies(List.of())).isEmpty();
    }

    @Test
    void convertDependency_withRegistry_resolvesTestJarType() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");
        dep.setType("test-jar"); // no explicit classifier

        var registry = new DefaultArtifactTypeRegistry();
        registry.add(new DefaultArtifactType("test-jar", "jar", "tests", "java"));

        Dependency result = MojoHelper.convertDependency(dep, registry);

        assertThat(result.getArtifact().getExtension()).isEqualTo("jar");
        assertThat(result.getArtifact().getClassifier()).isEqualTo("tests");
    }

    @Test
    void convertDependency_withRegistry_explicitClassifierOverridesTypeDefault() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");
        dep.setType("test-jar");
        dep.setClassifier("android"); // explicit — must not be overridden by type default

        var registry = new DefaultArtifactTypeRegistry();
        registry.add(new DefaultArtifactType("test-jar", "jar", "tests", "java"));

        Dependency result = MojoHelper.convertDependency(dep, registry);

        assertThat(result.getArtifact().getExtension()).isEqualTo("jar");
        assertThat(result.getArtifact().getClassifier()).isEqualTo("android"); // explicit wins
    }

    @Test
    void convertDependency_withRegistry_unknownTypeFallsBackToExtensionAsType() {
        // Third path through convertDependency(dep, registry):
        // registry != null AND registry.get(type) == null → extension = type (backward-compat fallback).
        // This is the backward-compat guarantee for custom/unknown types not registered in the session registry.
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");
        dep.setType("custom-zip"); // not registered in the (empty) registry

        var registry = new DefaultArtifactTypeRegistry(); // empty — doesn't know custom-zip

        Dependency result = MojoHelper.convertDependency(dep, registry);

        // Falls through: extension = type, classifier = "" (no registry default applied)
        assertThat(result.getArtifact().getExtension()).isEqualTo("custom-zip");
        assertThat(result.getArtifact().getClassifier()).isEmpty();
    }

    // ── extractPlugins / extractManagedPlugins via toPilotProject ─────────────

    /** Creates a minimal MavenProject ready for toPilotProject — originalModel must be set. */
    private static MavenProject minimalProject() {
        MavenProject mp = new MavenProject();
        mp.setGroupId("com.example");
        mp.setArtifactId("app");
        mp.setVersion("1.0");
        // toPilotProject calls mp.getOriginalModel() — mirror the effective model so that
        // mp.setBuild() is visible via both getOriginalModel().getBuild() and getBuild().
        mp.setOriginalModel(mp.getModel());
        return mp;
    }

    /**
     * Creates a minimal MavenProject with root-level build plugins and no active profiles.
     * Verifies that extractPlugins picks them up with profileId = null.
     */
    @Test
    void extractPlugins_rootLevelPluginsHaveNullProfileId() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        plugin.setVersion("3.11.0");

        Build build = new Build();
        build.addPlugin(plugin);

        MavenProject mp = minimalProject();
        mp.setBuild(build);
        // No active profiles → only root plugins

        PilotProject pp = MojoHelper.toPilotProject(mp, new IdentityHashMap<>());

        assertThat(pp.getPlugins()).hasSize(1);
        assertThat(pp.getPlugins().get(0).groupId()).isEqualTo("org.apache.maven.plugins");
        assertThat(pp.getPlugins().get(0).artifactId()).isEqualTo("maven-compiler-plugin");
        assertThat(pp.getPlugins().get(0).profileId()).isNull();
    }

    /**
     * Creates a MavenProject with an active profile that has build plugins.
     * Verifies that extractPlugins picks them up with the correct profileId.
     */
    @Test
    void extractPlugins_activeProfilePluginsCarryProfileId() {
        // Root build has one plugin
        Plugin rootPlugin = new Plugin();
        rootPlugin.setGroupId("org.apache.maven.plugins");
        rootPlugin.setArtifactId("maven-jar-plugin");
        rootPlugin.setVersion("3.3.0");
        Build rootBuild = new Build();
        rootBuild.addPlugin(rootPlugin);

        // Active profile has one build plugin
        Plugin profPlugin = new Plugin();
        profPlugin.setGroupId("org.apache.maven.plugins");
        profPlugin.setArtifactId("maven-compiler-plugin");
        profPlugin.setVersion("3.11.0");
        Build profBuild = new Build();
        profBuild.addPlugin(profPlugin);

        Profile profile = new Profile();
        profile.setId("release");
        profile.setBuild(profBuild);

        MavenProject mp = minimalProject();
        mp.setBuild(rootBuild);
        mp.setActiveProfiles(List.of(profile));

        PilotProject pp = MojoHelper.toPilotProject(mp, new IdentityHashMap<>());

        // Root plugin + one profile plugin
        assertThat(pp.getPlugins()).hasSize(2);

        PilotProject.Plugin rootResult = pp.getPlugins().stream()
                .filter(p -> "maven-jar-plugin".equals(p.artifactId()))
                .findFirst()
                .orElseThrow();
        assertThat(rootResult.profileId()).isNull();

        PilotProject.Plugin profResult = pp.getPlugins().stream()
                .filter(p -> "maven-compiler-plugin".equals(p.artifactId()))
                .findFirst()
                .orElseThrow();
        assertThat(profResult.profileId()).isEqualTo("release");
    }

    /**
     * Profile with build section but no plugins — the inner loop body is skipped,
     * verifying the {@code profile.getBuild() != null && plugins != null} guard.
     */
    @Test
    void extractPlugins_activeProfileWithEmptyBuildContributesNoPlugins() {
        Build profBuild = new Build();
        // No plugins added to build

        Profile profile = new Profile();
        profile.setId("no-plugins");
        profile.setBuild(profBuild);

        MavenProject mp = minimalProject();
        mp.setActiveProfiles(List.of(profile));

        PilotProject pp = MojoHelper.toPilotProject(mp, new IdentityHashMap<>());
        assertThat(pp.getPlugins()).isEmpty();
    }

    /**
     * Profile with no build section — verifies the {@code profile.getBuild() != null} guard.
     */
    @Test
    void extractPlugins_activeProfileWithNoBuildContributesNoPlugins() {
        Profile profile = new Profile();
        profile.setId("no-build");
        // profile.setBuild() not called → getBuild() returns null

        MavenProject mp = minimalProject();
        mp.setActiveProfiles(List.of(profile));

        PilotProject pp = MojoHelper.toPilotProject(mp, new IdentityHashMap<>());
        assertThat(pp.getPlugins()).isEmpty();
    }

    /**
     * Active profile with plugin management — verifies extractManagedPlugins
     * picks up profile-scoped managed plugins with the correct profileId.
     *
     * <p><b>Test-model limitation:</b> {@code MavenProject.setActiveProfiles()} only sets the
     * {@code activeProfiles} list field; it does <em>not</em> merge the profile's
     * {@code &lt;pluginManagement&gt;} into the effective model's build (that merge is done by
     * {@code DefaultProfileInjector} during a real Maven build). As a result,
     * {@code mp.getPluginManagement()} in this test only sees the root PM set via
     * {@code mp.setBuild()}, and the profile PM remains disjoint. The two sources never overlap
     * here, so the test validates the profileId-tracking logic but does <em>not</em> reproduce
     * the real-world duplicate that the fix targets (where the effective model already contains
     * the merged profile PM before the profile loop runs). Integration tests with a real Maven
     * session are needed to fully cover that scenario.</p>
     */
    @Test
    void extractManagedPlugins_activeProfilePluginManagementCarriesProfileId() {
        // Root plugin management
        Plugin rootManaged = new Plugin();
        rootManaged.setGroupId("org.apache.maven.plugins");
        rootManaged.setArtifactId("maven-jar-plugin");
        rootManaged.setVersion("3.3.0");
        PluginManagement rootPM = new PluginManagement();
        rootPM.addPlugin(rootManaged);
        Build rootBuild = new Build();
        rootBuild.setPluginManagement(rootPM);

        // Profile plugin management
        Plugin profManaged = new Plugin();
        profManaged.setGroupId("org.apache.maven.plugins");
        profManaged.setArtifactId("maven-compiler-plugin");
        profManaged.setVersion("3.11.0");
        PluginManagement profPM = new PluginManagement();
        profPM.addPlugin(profManaged);
        Build profBuild = new Build();
        profBuild.setPluginManagement(profPM);

        Profile profile = new Profile();
        profile.setId("release");
        profile.setBuild(profBuild);

        MavenProject mp = minimalProject();
        mp.setBuild(rootBuild);
        mp.setActiveProfiles(List.of(profile));

        PilotProject pp = MojoHelper.toPilotProject(mp, new IdentityHashMap<>());

        // Root managed + one profile managed
        assertThat(pp.getManagedPlugins()).hasSize(2);

        PilotProject.Plugin rootResult = pp.getManagedPlugins().stream()
                .filter(p -> "maven-jar-plugin".equals(p.artifactId()))
                .findFirst()
                .orElseThrow();
        assertThat(rootResult.profileId()).isNull();

        PilotProject.Plugin profResult = pp.getManagedPlugins().stream()
                .filter(p -> "maven-compiler-plugin".equals(p.artifactId()))
                .findFirst()
                .orElseThrow();
        assertThat(profResult.profileId()).isEqualTo("release");
    }

    /**
     * Profile build with plugin management but no plugins in it — verifies the
     * {@code getPluginManagement() != null && getPlugins() != null} guard in extractManagedPlugins.
     */
    @Test
    void extractManagedPlugins_activeProfileWithEmptyPluginManagementContributesNoPlugins() {
        PluginManagement emptyPM = new PluginManagement();
        // No plugins added
        Build profBuild = new Build();
        profBuild.setPluginManagement(emptyPM);

        Profile profile = new Profile();
        profile.setId("empty-pm");
        profile.setBuild(profBuild);

        MavenProject mp = minimalProject();
        mp.setActiveProfiles(List.of(profile));

        PilotProject pp = MojoHelper.toPilotProject(mp, new IdentityHashMap<>());
        assertThat(pp.getManagedPlugins()).isEmpty();
    }

    /**
     * Profile with build section but null plugin management — verifies the null check.
     */
    @Test
    void extractManagedPlugins_activeProfileWithNullPluginManagementContributesNoPlugins() {
        Build profBuild = new Build();
        // No setPluginManagement() call → getPluginManagement() returns null

        Profile profile = new Profile();
        profile.setId("no-pm");
        profile.setBuild(profBuild);

        MavenProject mp = minimalProject();
        mp.setActiveProfiles(List.of(profile));

        PilotProject pp = MojoHelper.toPilotProject(mp, new IdentityHashMap<>());
        assertThat(pp.getManagedPlugins()).isEmpty();
    }

    /**
     * When a root-level managed plugin declares its version as a property expression
     * (e.g. {@code ${compiler.version}}), the original model retains that expression
     * literally.  {@code VersionComparator} cannot parse property references and treats
     * them as {@code 0.0.0}, causing spurious update suggestions.
     *
     * <p>{@code extractManagedPlugins} resolves property expressions by looking up the
     * same GA in the effective plugin management (which has already interpolated
     * properties).  The resolved concrete version is used for comparison, while the raw
     * property reference in the POM is left untouched for the domtrip editor.</p>
     *
     * <p><b>Test-model limitation:</b> In a real Maven build, {@code getModel()} (effective)
     * and {@code getOriginalModel()} are distinct objects.  Here we construct them separately
     * so that the effective build contains the concrete version while the original retains
     * the property expression.</p>
     */
    @Test
    void extractManagedPlugins_propertyVersionIsResolvedFromEffectiveModel() {
        // Original (file) model: version is a property expression
        Plugin origManaged = new Plugin();
        origManaged.setGroupId("org.apache.maven.plugins");
        origManaged.setArtifactId("maven-compiler-plugin");
        origManaged.setVersion("${compiler.version}");
        PluginManagement origPM = new PluginManagement();
        origPM.addPlugin(origManaged);
        org.apache.maven.model.Build origBuild = new org.apache.maven.model.Build();
        origBuild.setPluginManagement(origPM);
        org.apache.maven.model.Model origModel = new org.apache.maven.model.Model();
        origModel.setBuild(origBuild);

        // Effective model: property already resolved to a concrete version
        Plugin effManaged = new Plugin();
        effManaged.setGroupId("org.apache.maven.plugins");
        effManaged.setArtifactId("maven-compiler-plugin");
        effManaged.setVersion("3.11.0");
        PluginManagement effPM = new PluginManagement();
        effPM.addPlugin(effManaged);
        org.apache.maven.model.Build effBuild = new org.apache.maven.model.Build();
        effBuild.setPluginManagement(effPM);

        MavenProject mp = new MavenProject();
        mp.setGroupId("com.example");
        mp.setArtifactId("app");
        mp.setVersion("1.0");
        mp.setBuild(effBuild); // effective model's build (getPluginManagement() reads this)
        mp.setOriginalModel(origModel); // original model retains property expression

        PilotProject pp = MojoHelper.toPilotProject(mp, new IdentityHashMap<>());

        assertThat(pp.getManagedPlugins()).hasSize(1);
        PilotProject.Plugin result = pp.getManagedPlugins().get(0);
        // The resolved version is used for comparison — not the raw property expression
        assertThat(result.version()).isEqualTo("3.11.0");
        assertThat(result.profileId()).isNull();
    }
}
