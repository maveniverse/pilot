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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class MavenCentralClient {

    static final String CENTRAL_BASE = "https://repo1.maven.org/maven2/";

    static class PomInfo {
        final String name;
        final String description;
        final String url;
        final String organization;
        final String license;
        final String licenseUrl;
        final String date;

        PomInfo(
                String name,
                String description,
                String url,
                String organization,
                String license,
                String licenseUrl,
                String date) {
            this.name = name;
            this.description = description;
            this.url = url;
            this.organization = organization;
            this.license = license;
            this.licenseUrl = licenseUrl;
            this.date = date;
        }
    }

    static PomInfo fetchPom(String groupId, String artifactId, String version) {
        PomInfo local = fetchPomFromLocal(groupId, artifactId, version);
        if (local != null) return local;

        try {
            String pomUrl = CENTRAL_BASE + pomPath(groupId, artifactId, version);
            HttpURLConnection conn = openConnection(pomUrl, "GET");
            try {
                if (conn.getResponseCode() != 200) {
                    return new PomInfo(null, null, null, null, null, null, null);
                }
                String date = lastModifiedDate(conn);
                try (InputStream is = conn.getInputStream()) {
                    return parsePomInfo(is, date);
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return new PomInfo(null, null, null, null, null, null, null);
        }
    }

    static List<String> fetchVersions(String groupId, String artifactId) {
        List<String> versions = new ArrayList<>();
        try {
            String path = groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
            String metaUrl = CENTRAL_BASE + path;
            HttpURLConnection conn = openConnection(metaUrl, "GET");
            try {
                if (conn.getResponseCode() != 200) {
                    return versions;
                }
                try (InputStream is = conn.getInputStream()) {
                    DocumentBuilder db = createSafeDocumentBuilder();
                    Document doc = db.parse(is);
                    NodeList versionNodes = doc.getElementsByTagName("version");
                    for (int i = 0; i < versionNodes.getLength(); i++) {
                        String v = versionNodes.item(i).getTextContent();
                        if (v != null && !v.trim().isEmpty()) {
                            versions.add(v.trim());
                        }
                    }
                }
                Collections.reverse(versions);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            // return whatever we have
        }
        return versions;
    }

    static LocalDate fetchReleaseDate(String groupId, String artifactId, String version) {
        try {
            String pomUrl = CENTRAL_BASE + pomPath(groupId, artifactId, version);
            HttpURLConnection conn = openConnection(pomUrl, "HEAD");
            try {
                if (conn.getResponseCode() != 200) {
                    return null;
                }
                long lastModified = conn.getLastModified();
                if (lastModified > 0) {
                    return Instant.ofEpochMilli(lastModified)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate();
                }
                return null;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // -- Internal helpers --

    private static String pomPath(String groupId, String artifactId, String version) {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";
    }

    private static HttpURLConnection openConnection(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        return conn;
    }

    private static String lastModifiedDate(HttpURLConnection conn) {
        long lastModified = conn.getLastModified();
        if (lastModified > 0) {
            return Instant.ofEpochMilli(lastModified)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString();
        }
        return null;
    }

    private static PomInfo fetchPomFromLocal(String groupId, String artifactId, String version) {
        try {
            String localRepo = System.getProperty("maven.repo.local");
            if (localRepo == null) {
                localRepo = System.getProperty("user.home") + "/.m2/repository";
            }
            Path pomFile = Path.of(
                    localRepo, groupId.replace('.', '/'), artifactId, version, artifactId + "-" + version + ".pom");
            if (!pomFile.toFile().isFile()) return null;

            String date = Instant.ofEpochMilli(pomFile.toFile().lastModified())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString();

            try (InputStream is = Files.newInputStream(pomFile)) {
                return parsePomInfo(is, date);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private record LicenseInfo(String name, String url) {}

    private static PomInfo parsePomInfo(InputStream is, String date) throws Exception {
        DocumentBuilder db = createSafeDocumentBuilder();
        Document doc = db.parse(is);
        Element root = doc.getDocumentElement();

        String name = getChildText(root, "name");
        String description = getChildText(root, "description");
        String url = getChildText(root, "url");

        String org = null;
        Element orgEl = getChildElement(root, "organization");
        if (orgEl != null) {
            org = getChildText(orgEl, "name");
        }

        LicenseInfo licInfo = extractLicenseInfo(root);
        if (licInfo == null) {
            licInfo = fetchLicenseInfoFromParent(root);
        }

        String license = licInfo != null ? licInfo.name : null;
        String licenseUrl = licInfo != null ? licInfo.url : null;

        return new PomInfo(name, description, url, org, license, licenseUrl, date);
    }

    private static LicenseInfo fetchLicenseInfoFromParent(Element pomRoot) {
        Element parentEl = getChildElement(pomRoot, "parent");
        if (parentEl == null) return null;
        String pGroupId = getChildText(parentEl, "groupId");
        String pArtifactId = getChildText(parentEl, "artifactId");
        String pVersion = getChildText(parentEl, "version");
        if (pGroupId == null || pArtifactId == null || pVersion == null) return null;

        Element parentRoot = loadPomElement(pGroupId, pArtifactId, pVersion);
        if (parentRoot == null) return null;

        LicenseInfo licInfo = extractLicenseInfo(parentRoot);
        if (licInfo != null) return licInfo;
        return fetchLicenseInfoFromParent(parentRoot);
    }

    private static Element loadPomElement(String groupId, String artifactId, String version) {
        String relPath = pomPath(groupId, artifactId, version);

        try {
            String localRepo = System.getProperty("maven.repo.local");
            if (localRepo == null) {
                localRepo = System.getProperty("user.home") + "/.m2/repository";
            }
            Path pomFile = Path.of(
                    localRepo, groupId.replace('.', '/'), artifactId, version, artifactId + "-" + version + ".pom");
            if (pomFile.toFile().isFile()) {
                try (InputStream is = Files.newInputStream(pomFile)) {
                    return createSafeDocumentBuilder().parse(is).getDocumentElement();
                }
            }
        } catch (Exception e) {
            // fall through to Central
        }

        try {
            String pomUrl = CENTRAL_BASE + relPath;
            HttpURLConnection conn = openConnection(pomUrl, "GET");
            try {
                if (conn.getResponseCode() != 200) return null;
                try (InputStream is = conn.getInputStream()) {
                    return createSafeDocumentBuilder().parse(is).getDocumentElement();
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static LicenseInfo extractLicenseInfo(Element pomRoot) {
        Element licensesEl = getChildElement(pomRoot, "licenses");
        if (licensesEl == null) return null;
        Element licenseEl = getChildElement(licensesEl, "license");
        if (licenseEl == null) return null;
        String name = getChildText(licenseEl, "name");
        if (name == null) return null;
        String url = getChildText(licenseEl, "url");
        return new LicenseInfo(name, url);
    }

    private static Element getChildElement(Element parent, String tagName) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element el && el.getTagName().equals(tagName)) {
                return el;
            }
        }
        return null;
    }

    static DocumentBuilder createSafeDocumentBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return dbf.newDocumentBuilder();
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element el) {
                if (el.getTagName().equals(tagName)) {
                    String text = el.getTextContent();
                    return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
                }
            }
        }
        return null;
    }
}
