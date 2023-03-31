/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi,
 * Erik Ramfelt, Koichi Fujikawa, Red Hat, Inc., Seiji Sogabe,
 * Stephen Connolly, Tom Huybrechts, Yahoo! Inc., Alan Harder, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkins.tools.test;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.VersionNumber;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkins.tools.test.exception.PluginCompatibilityTesterException;
import org.jenkins.tools.test.exception.PluginSourcesUnavailableException;
import org.jenkins.tools.test.exception.PomExecutionException;
import org.jenkins.tools.test.exception.WrappedPluginCompatabilityException;
import org.jenkins.tools.test.maven.ExternalMavenRunner;
import org.jenkins.tools.test.maven.MavenRunner;
import org.jenkins.tools.test.model.PluginCompatTesterConfig;
import org.jenkins.tools.test.model.UpdateSite;
import org.jenkins.tools.test.model.hook.BeforeCheckoutContext;
import org.jenkins.tools.test.model.hook.BeforeCompilationContext;
import org.jenkins.tools.test.model.hook.BeforeExecutionContext;
import org.jenkins.tools.test.model.hook.PluginCompatTesterHooks;
import org.jenkins.tools.test.model.plugin_metadata.LocalCheckoutMetadataExtractor;
import org.jenkins.tools.test.model.plugin_metadata.PluginMetadata;
import org.jenkins.tools.test.model.plugin_metadata.PluginMetadataHooks;
import org.jenkins.tools.test.util.StreamGobbler;
import org.jenkins.tools.test.util.WarUtils;

/**
 * Frontend for plugin compatibility tests
 *
 * @author Frederic Camblor, Olivier Lamy
 */
@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "intended behavior")
public class PluginCompatTester {

    private static final Logger LOGGER = Logger.getLogger(PluginCompatTester.class.getName());

    /** First version with new parent POM. */
    public static final String JENKINS_CORE_FILE_REGEX =
            "WEB-INF/lib/jenkins-core-([0-9.]+(?:-[0-9a-f.]+)*(?:-(?i)([a-z]+)(-)?([0-9a-f.]+)?)?(?:-(?i)([a-z]+)(-)?([0-9a-f_.]+)?)?(?:-SNAPSHOT)?)[.]jar";

    private final PluginCompatTesterConfig config;
    private final ExternalMavenRunner runner;

    public PluginCompatTester(PluginCompatTesterConfig config) {
        this.config = config;
        runner =
                new ExternalMavenRunner(
                        config.getExternalMaven(),
                        config.getMavenSettings(),
                        config.getMavenArgs());
    }

    public void testPlugins() throws PluginCompatibilityTesterException {
        PluginCompatTesterHooks pcth =
                new PluginCompatTesterHooks(
                        config.getExternalHooksJars(), config.getExcludeHooks());
        // Determine the plugin data

        String coreVersion = WarUtils.extractCoreVersionFromWar(config.getWar());
        List<PluginMetadata> pluginMetadataList =
                WarUtils.extractPluginMetadataFromWar(
                        config.getWar(),
                        PluginMetadataHooks.loadExtractors(config.getExternalHooksJars()));

        // filter any plugins that are not being tested and group by git URL
        // and run through the pre-checkout hooks
        // and group by Git URL
        Map<String, List<PluginMetadata>> pluginsByrepo;
        try {
            pluginsByrepo =
                    filterPluginList(pluginMetadataList)
                            .map(new RunAndMapBeforeCheckoutHooks(pcth, coreVersion, config))
                            .collect(
                                    Collectors.groupingBy(
                                            PluginMetadata::getScmUrl,
                                            HashMap::new,
                                            Collectors.toList()));
        } catch (WrappedPluginCompatabilityException e) {
            throw e.getCause();
        }
        if (localCheckoutProvided()) {
            // do not no BeforeCheckoutHooks on a local checkout
            List<PluginMetadata> localMetaData =
                    LocalCheckoutMetadataExtractor.extractMetadata(
                            config.getLocalCheckoutDir(), config);
            pluginsByrepo.put(null, localMetaData);
        }

        LOGGER.log(
                Level.INFO,
                "Starting plugin tests on core coordinates org.jenkins-ci.main:jenkins-war:{0}:executable-war",
                coreVersion);
        PluginCompatibilityTesterException lastException = null;

        for (Map.Entry<String, List<PluginMetadata>> entry : pluginsByrepo.entrySet()) {
            // construct a single working directory for the clone.
            String gitUrl = entry.getKey();

            File cloneDir;
            if (gitUrl == null) {
                cloneDir = config.getLocalCheckoutDir();
            } else {
                cloneDir = new File(config.getWorkingDir(), getRepoNameFromGitURL(gitUrl));
                // all plugins from the same reactor are assumed to be of the same version
                String tag = entry.getValue().get(0).getGitCommit();

                try {
                    cloneFromScm(gitUrl, config.getFallbackGitHubOrganization(), tag, cloneDir);
                } catch (PluginCompatibilityTesterException e) {
                    if (config.isFailFast()) {
                        throw e;
                    }
                    if (lastException != null) {
                        e.addSuppressed(lastException);
                    }
                    lastException = e;
                    continue;
                }
            }
            // for each of the PluginMetadataEntries....
            for (PluginMetadata pm : entry.getValue()) {
                try {
                    testPluginAgainst(coreVersion, pm, cloneDir, pcth);
                } catch (PluginCompatibilityTesterException e) {
                    if (config.isFailFast()) {
                        throw e;
                    }
                    if (lastException != null) {
                        e.addSuppressed(lastException);
                    }
                    lastException = e;
                }
            }
        }
        if (lastException != null) {
            throw lastException;
        }
    }

    /**
     * create a stream of pluginMetadata where any excluded plugins are ommited, and iff provided
     * only included plugins are included
     */
    private Stream<PluginMetadata> filterPluginList(List<PluginMetadata> pluginMetadataList) {
        return pluginMetadataList.stream()
                .filter(
                        t -> {
                            if (config.getExcludePlugins().contains(t.getPluginId())) {
                                LOGGER.log(
                                        Level.INFO,
                                        "Plugin ''{0}'' ({1}) in excluded plugins; skipping",
                                        new Object[] {t.getName(), t.getPluginId()});
                                return false;
                            }
                            return true;
                        })
                .filter(
                        t -> {
                            if (!config.getIncludePlugins().isEmpty()
                                    && config.getIncludePlugins().contains(t.getPluginId())) {
                                LOGGER.log(
                                        Level.INFO,
                                        "Plugin ''{0}'' ({1}) not in included plugins; skipping",
                                        new Object[] {t.getName(), t.getPluginId()});
                                return false;
                            }
                            return true;
                        });
    }

    private static File createBuildLogFile(
            File workDirectory, PluginMetadata metadata, String coreVersion) {

        File f =
                new File(
                        workDirectory.getAbsolutePath()
                                + File.separator
                                + createBuildLogFilePathFor(
                                        metadata.getPluginId(),
                                        metadata.getVersion(),
                                        coreVersion));
        try {
            Files.createDirectories(f.getParentFile().toPath());
            Files.deleteIfExists(f.toPath());
            Files.createFile(f.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create build log file", e);
        }
        return f;
    }

    private static String createBuildLogFilePathFor(
            String pluginId, String pluginVersion, String coreVersion) {
        return String.format(
                "logs/%s/v%s_against_jenkins_%s.log", pluginId, pluginVersion, coreVersion);
    }

    private void testPluginAgainst(
            String coreVersion,
            PluginMetadata pluginMetadata,
            File cloneLocation,
            PluginCompatTesterHooks pcth)
            throws PluginCompatibilityTesterException {
        LOGGER.log(
                Level.INFO,
                "\n\n\n\n\n\n"
                        + "#############################################\n"
                        + "#############################################\n"
                        + "##\n"
                        + "## Starting to test {0} {1} against Jenkins {2}\n"
                        + "##\n"
                        + "#############################################\n"
                        + "#############################################\n\n\n\n\n",
                new Object[] {pluginMetadata.getName(), pluginMetadata.getVersion(), coreVersion});

        File buildLogFile = createBuildLogFile(config.getWorkingDir(), pluginMetadata, coreVersion);

        // Run the BeforeCompileHooks
        BeforeCompilationContext beforeCompile =
                new BeforeCompilationContext(pluginMetadata, coreVersion, config, cloneLocation);
        pcth.runBeforeCompilation(beforeCompile);

        // First build against the original POM. This defends against source incompatibilities
        // (which we do not care about for this purpose); and ensures that we are testing a
        // plugin binary as close as possible to what was actually released. We also skip
        // potential javadoc execution to avoid general test failure.
        runner.run(
                Map.of("maven.javadoc.skip", "true"),
                cloneLocation,
                pluginMetadata.getModulePath(),
                buildLogFile,
                "clean",
                "process-test-classes");

        List<String> args = new ArrayList<>();
        args.add("hpi:resolve-test-dependencies");
        args.add("hpi:test-hpl");
        args.add("surefire:test");

        // Run preexecution hooks
        BeforeExecutionContext forExecutionHooks =
                new BeforeExecutionContext(
                        pluginMetadata, coreVersion, config, cloneLocation, args);
        pcth.runBeforeExecution(forExecutionHooks);

        Map<String, String> properties = new LinkedHashMap<>(config.getMavenProperties());
        properties.put("overrideWar", config.getWar().toString());
        properties.put("jenkins.version", coreVersion);
        properties.put("useUpperBounds", "true");
        if (new VersionNumber(coreVersion).isOlderThan(new VersionNumber("2.382"))) {
            /*
             * Versions of Jenkins prior to 2.382 are susceptible to JENKINS-68696, in which
             * javax.servlet:servlet-api comes from core at version 0. This is an intentional trick
             * to prevent this library from being used, and we do not want it to be upgraded to a
             * nonzero version (which is not a realistic test scenario) just because it happens to
             * be on the class path of some plugin and triggers an upper bounds violation.
             */
            properties.put("upperBoundsExcludes", "javax.servlet:servlet-api");
        }

        // Execute with tests
        runner.run(
                Collections.unmodifiableMap(properties),
                cloneLocation,
                pluginMetadata.getModulePath(),
                buildLogFile,
                args.toArray(new String[0]));
    }

    public static void cloneFromScm(
            String url, String fallbackGitHubOrganization, String scmTag, File checkoutDirectory)
            throws PluginSourcesUnavailableException {
        List<String> connectionURLs = new ArrayList<>();
        connectionURLs.add(url);
        if (fallbackGitHubOrganization != null) {
            connectionURLs =
                    getFallbackConnectionURL(connectionURLs, url, fallbackGitHubOrganization);
        }

        PluginSourcesUnavailableException lastException = null;
        for (String connectionURL : connectionURLs) {
            if (connectionURL != null) {
                if (StringUtils.startsWith(connectionURL, "scm:git:")) {
                    connectionURL = StringUtils.substringAfter(connectionURL, "scm:git:");
                }
                // See: https://github.blog/2021-09-01-improving-git-protocol-security-github/
                connectionURL = connectionURL.replace("git://", "https://");
            }
            try {
                cloneImpl(connectionURL, scmTag, checkoutDirectory);
                return; // checkout was ok
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (PluginSourcesUnavailableException e) {
                if (lastException != null) {
                    e.addSuppressed(lastException);
                }
                lastException = e;
            }
        }

        if (lastException != null) {
            throw lastException;
        }
    }

    /**
     * Clone the given Git repository in the given checkout directory by running, in order, the
     * following CLI operations:
     *
     * <ul>
     *   <li><code>git init</code>
     *   <li><code>git remote add origin url</code>
     *   <li><code>git fetch origin ${SCM_TAG}</code>
     *   <li><code>git checkout FETCH_HEAD</code>
     * </ul>
     *
     * @param gitUrl The git native URL, see the <a
     *     href="https://git-scm.com/docs/git-clone#_git_urls">git documentation</a> for the
     *     supported syntax
     * @param scmTag the tag or sha1 hash to clone
     * @param checkoutDirectory the directory in which to clone the Git repository
     * @throws IOException if an error occurs
     */
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "intended behavior")
    private static void cloneImpl(String gitUrl, String scmTag, File checkoutDirectory)
            throws IOException, PluginSourcesUnavailableException {
        LOGGER.log(
                Level.INFO,
                "Checking out from git repository {0} at {1}",
                new Object[] {gitUrl, scmTag});

        /*
         * We previously used the Maven SCM API to clone the repository, which ran the following
         * commands:
         *
         *     git clone --depth 1 --branch ${SCM_TAG} ${CONNECTION_URL}
         *     git ls-remote ${CONNECTION_URL}
         *     git fetch ${CONNECTION_URL}
         *     git checkout ${SCM_TAG}
         *     git ls-files
         *
         * This proved to be inefficient, so we instead run only the commands we need to run:
         *
         *     git init
         *     git fetch ${CONNECTION_URL} ${SCM_TAG} (this will work with a SHA1 hash or a tag)
         *     git checkout FETCH_HEAD
         */
        if (checkoutDirectory.isDirectory()) {
            FileUtils.deleteDirectory(checkoutDirectory);
        }
        Files.createDirectories(checkoutDirectory.toPath());

        // git init
        Process p =
                new ProcessBuilder()
                        .directory(checkoutDirectory)
                        .command("git", "init")
                        .redirectErrorStream(true)
                        .start();
        StreamGobbler gobbler = new StreamGobbler(p.getInputStream());
        gobbler.start();
        try {
            int exitStatus = p.waitFor();
            gobbler.join();
            String output = gobbler.getOutput().trim();
            if (exitStatus != 0) {
                throw new PluginSourcesUnavailableException(
                        "git init failed with exit status " + exitStatus + ": " + output);
            }
        } catch (InterruptedException e) {
            throw new PluginSourcesUnavailableException("git init was interrupted", e);
        }

        p =
                new ProcessBuilder()
                        .directory(checkoutDirectory)
                        .command("git", "fetch", gitUrl, scmTag)
                        .redirectErrorStream(true)
                        .start();
        gobbler = new StreamGobbler(p.getInputStream());
        gobbler.start();
        try {
            int exitStatus = p.waitFor();
            gobbler.join();
            String output = gobbler.getOutput().trim();
            if (exitStatus != 0) {
                throw new PluginSourcesUnavailableException(
                        "git fetch origin failed with exit status " + exitStatus + ": " + output);
            }
        } catch (InterruptedException e) {
            throw new PluginSourcesUnavailableException("git fetch origin was interrupted", e);
        }

        // git checkout FETCH_HEAD
        p =
                new ProcessBuilder()
                        .directory(checkoutDirectory)
                        .command("git", "checkout", "FETCH_HEAD")
                        .redirectErrorStream(true)
                        .start();
        gobbler = new StreamGobbler(p.getInputStream());
        gobbler.start();
        try {
            int exitStatus = p.waitFor();
            gobbler.join();
            String output = gobbler.getOutput().trim();
            if (exitStatus != 0) {
                throw new PluginSourcesUnavailableException(
                        "git checkout FETCH_HEAD failed with exit status "
                                + exitStatus
                                + ": "
                                + output);
            }
        } catch (InterruptedException e) {
            throw new PluginSourcesUnavailableException(
                    "git checkout FETCH_HEAD was interrupted", e);
        }
    }

    public static List<String> getFallbackConnectionURL(
            List<String> connectionURLs,
            String connectionURLPomData,
            String fallbackGitHubOrganization) {
        Pattern pattern = Pattern.compile("(.*github.com[:|/])([^/]*)(.*)");
        Matcher matcher = pattern.matcher(connectionURLPomData);
        matcher.find();
        connectionURLs.add(
                matcher.replaceFirst(
                        "scm:git:git@github.com:" + fallbackGitHubOrganization + "$3"));
        pattern = Pattern.compile("(.*github.com[:|/])([^/]*)(.*)");
        matcher = pattern.matcher(connectionURLPomData);
        matcher.find();
        connectionURLs.add(matcher.replaceFirst("$1" + fallbackGitHubOrganization + "$3"));
        return connectionURLs;
    }

    private boolean localCheckoutProvided() {
        File localCheckoutDir = config.getLocalCheckoutDir();
        return localCheckoutDir != null && localCheckoutDir.exists();
    }

    /**
     * Scans through a WAR file, accumulating plugin information
     *
     * @param war WAR to scan
     * @param pluginRegExp The plugin regexp to use, can be used to differentiate between detached
     *     or "normal" plugins in the war file
     * @return Update center data
     */
    @SuppressFBWarnings(value = "REDOS", justification = "intended behavior")
    static UpdateSite.Data scanWAR(File war, String pluginRegExp) {
        UpdateSite.Entry core = null;
        List<UpdateSite.Plugin> plugins = new ArrayList<>();
        try (JarFile jf = new JarFile(war)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                Matcher m = Pattern.compile(JENKINS_CORE_FILE_REGEX).matcher(name);
                if (m.matches()) {
                    if (core != null) {
                        throw new IllegalStateException(">1 jenkins-core.jar in " + war);
                    }
                    // http://foobar is used to workaround the check in
                    // https://github.com/jenkinsci/jenkins/commit/f8daafd0327081186c06555f225e84c420261b4c
                    // We do not really care about the value
                    core = new UpdateSite.Entry("core", m.group(1), "https://foobar");
                }

                m = Pattern.compile(pluginRegExp).matcher(name);
                if (m.matches()) {
                    try (InputStream is = jf.getInputStream(entry);
                            JarInputStream jis = new JarInputStream(is)) {
                        Manifest manifest = jis.getManifest();
                        String shortName = manifest.getMainAttributes().getValue("Short-Name");
                        if (shortName == null) {
                            shortName = manifest.getMainAttributes().getValue("Extension-Name");
                            if (shortName == null) {
                                shortName = m.group(1);
                            }
                        }
                        String longName = manifest.getMainAttributes().getValue("Long-Name");
                        String version = manifest.getMainAttributes().getValue("Plugin-Version");
                        // Remove extra build information from the version number
                        final Matcher matcher =
                                Pattern.compile("^(.+-SNAPSHOT)(.+)$").matcher(version);
                        if (matcher.matches()) {
                            version = matcher.group(1);
                        }
                        String url = "jar:" + war.toURI() + "!/" + name;
                        UpdateSite.Plugin plugin =
                                new UpdateSite.Plugin(shortName, version, url, longName);
                        plugins.add(plugin);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (core == null) {
            throw new IllegalStateException("no jenkins-core.jar in " + war);
        }
        LOGGER.log(
                Level.INFO,
                "Scanned contents of {0} with {1} plugins",
                new Object[] {war, plugins.size()});
        return new UpdateSite.Data(core, plugins);
    }

    public static String getGitURLFromLocalCheckout(
            File workingDirectory, File localCheckout, MavenRunner runner)
            throws PluginSourcesUnavailableException, PomExecutionException {
        try {
            File log = new File(workingDirectory, "localcheckout-scm-connection.log");
            runner.run(
                    Map.of("expression", "project.scm.connection", "output", log.getAbsolutePath()),
                    localCheckout,
                    null,
                    null,
                    "-q",
                    "help:evaluate");
            List<String> output = Files.readAllLines(log.toPath(), Charset.defaultCharset());
            String scm = output.get(output.size() - 1);
            if (scm.startsWith("scm:git:")) {
                return scm.substring(8);
            }
            throw new PluginSourcesUnavailableException(
                    "SCM " + scm + " is not a supported URL, only git is supported by the PCT");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String getRepoNameFromGitURL(String gitURL)
            throws PluginSourcesUnavailableException {
        // obtain the the last path component (and strip any trailing .git)
        int index = gitURL.lastIndexOf("/");
        if (index < 0) {
            throw new PluginSourcesUnavailableException(
                    "Failed to obtain local directory for " + gitURL);
        }
        String name = gitURL.substring(++index);
        if (name.endsWith(".git")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    static class RunAndMapBeforeCheckoutHooks implements Function<PluginMetadata, PluginMetadata> {

        private PluginCompatTesterHooks pcth;
        private String coreVersion;
        private PluginCompatTesterConfig config;

        RunAndMapBeforeCheckoutHooks(
                PluginCompatTesterHooks pcth, String coreVersion, PluginCompatTesterConfig config) {
            this.pcth = pcth;
            this.coreVersion = coreVersion;
            this.config = config;
        }

        @Override
        public PluginMetadata apply(PluginMetadata pluginMetadata)
                throws WrappedPluginCompatabilityException {
            BeforeCheckoutContext c =
                    new BeforeCheckoutContext(pluginMetadata, coreVersion, config);
            try {
                pcth.runBeforeCheckout(c);
            } catch (PluginCompatibilityTesterException e) {
                throw new WrappedPluginCompatabilityException(e);
            }
            return c.getPluginMetadata();
        }
    }
}
