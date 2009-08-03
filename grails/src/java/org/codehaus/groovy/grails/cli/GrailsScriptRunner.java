/*
 * Copyright 2004-2005 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.groovy.grails.cli;

import gant.Gant;
import grails.util.BuildSettings;
import grails.util.BuildSettingsHolder;
import grails.util.GrailsNameUtils;
import grails.util.Environment;
import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.ExpandoMetaClass;
import org.codehaus.gant.GantBinding;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

import java.io.*;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class that handles Grails command line interface for running scripts
 *
 * @author Graeme Rocher
 *
 * @since 0.4
 */

public class GrailsScriptRunner {
    private static Map ENV_ARGS = new HashMap();
    // this map contains default environments for several scripts in form 'script-name':'env-code'
    private static Map DEFAULT_ENVS = new HashMap();

    static {
        ENV_ARGS.put("dev", Environment.DEVELOPMENT.getName());
        ENV_ARGS.put("prod", Environment.PRODUCTION.getName());
        ENV_ARGS.put("test", Environment.TEST.getName());
        DEFAULT_ENVS.put("War", Environment.PRODUCTION.getName());
        DEFAULT_ENVS.put("TestApp", Environment.TEST.getName());
        DEFAULT_ENVS.put("RunWebtest", Environment.TEST.getName());
        ExpandoMetaClass.enableGlobally();
    }

    private static final Pattern scriptFilePattern = Pattern.compile("^[^_]\\w+\\.groovy$");
    private static final Pattern pluginDescriptorPattern = Pattern.compile("^(\\S+)GrailsPlugin.groovy$");

    public static void main(String[] args) throws MalformedURLException {
        // Evaluate the arguments to get the name of the script to
        // execute, which environment to run it in, and the arguments
        // to pass to the script. This also evaluates arguments of the
        // form "-Dprop=value" and creates system properties from each
        // one.
        String allArgs = args.length > 0 ? args[0].trim() : "";
        ScriptAndArgs script = processArgumentsAndReturnScriptName(allArgs);

        // Get hold of the GRAILS_HOME environment variable if it is
        // available.
        String grailsHome = System.getProperty("grails.home");

        // Now we can pick up the Grails version from the Ant project
        // properties.
        BuildSettings build = new BuildSettings(new File(grailsHome));

        // Check that Grails' home actually exists.
        final File grailsHomeInSettings = build.getGrailsHome();
        if (grailsHomeInSettings == null || !grailsHomeInSettings.exists()) {
            System.out.println("Grails' installation directory not found: " + build.getGrailsHome());
            System.exit(1);
        }

        // Show a nice header in the console when running commands.
        System.out.println(
"Welcome to Grails " + build.getGrailsVersion() + " - http://grails.org/" + '\n' +
"Licensed under Apache Standard License 2.0" + '\n' +
"Grails home is " + (grailsHome == null ? "not set" : "set to: " + grailsHome) + '\n');

        // If there aren't any arguments, then we don't have a command
        // to execute. So we have to exit.
        if (script.name == null) {
            System.out.println("No script name specified. Use 'grails help' for more info or 'grails interactive' to enter interactive mode");
            System.exit(0);
        }

        System.out.println("Base Directory: " + build.getBaseDir().getPath());

        try {
            GrailsScriptRunner runner = new GrailsScriptRunner(build);
            int exitCode = runner.executeCommand(script.name, script.args, script.env);
            System.exit(exitCode);
        }
        catch (ScriptNotFoundException ex) {
            System.out.println("Script not found: " + ex.getScriptName());
        }
        catch (Throwable t) {
            System.out.println("Error executing script " + script.name + ": " + t.getMessage());
            sanitizeStacktrace(t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static ScriptAndArgs processArgumentsAndReturnScriptName(String allArgs) {
        ScriptAndArgs info = new ScriptAndArgs();

        // Check that we actually have some arguments to process.
        if (allArgs == null || allArgs.length() == 0) return info;

        String[] splitArgs = processSystemArguments(allArgs).trim().split(" ");
        int currentParamIndex = 0;
        if (isEnvironmentArgs(splitArgs[currentParamIndex])) {
            // use first argument as environment name and step further
            String env = splitArgs[currentParamIndex++];
            info.env = (String) ENV_ARGS.get(env);
        } else if(Environment.isSystemSet()) {
            info.env = Environment.getCurrent().getName();
        }

        if (currentParamIndex >= splitArgs.length) {
            System.out.println("You should specify a script to run. Run 'grails help' for a complete list of available scripts.");
            System.exit(0);
        }

        // use current argument as script name and step further
        String paramName = splitArgs[currentParamIndex++];
        if (paramName.charAt(0) == '-') {
            paramName = paramName.substring(1);
        }
        info.name = GrailsNameUtils.getNameFromScript(paramName);

        if (currentParamIndex < splitArgs.length) {
            // if we have additional params provided - store it in system property
            StringBuilder b = new StringBuilder(splitArgs[currentParamIndex]);
            for (int i = currentParamIndex + 1; i < splitArgs.length; i++) {
                b.append(' ').append(splitArgs[i]);
            }
            info.args = b.toString();
        }
        return info;
    }

    private static String processSystemArguments(String allArgs) {
        String lastMatch = null;
        Pattern sysPropPattern = Pattern.compile("-D(.+?)=(.+?)\\s+?");
        Matcher m = sysPropPattern.matcher(allArgs);
        while (m.find()) {
            System.setProperty(m.group(1).trim(), m.group(2).trim().replaceAll("%20", " "));
            lastMatch = m.group();
        }

        if (lastMatch != null) {
            int i = allArgs.lastIndexOf(lastMatch) + lastMatch.length();
            allArgs = allArgs.substring(i);
        }
        return allArgs;
    }

    private static boolean isEnvironmentArgs(String env) {
        return ENV_ARGS.containsKey(env);
    }

    private BuildSettings settings;
    private PrintStream out = System.out;
    private boolean isInteractive = true;

    public GrailsScriptRunner() {
        this(new BuildSettings());
    }

    public GrailsScriptRunner(String grailsHome) {
        this(new BuildSettings(new File(grailsHome)));
    }

    public GrailsScriptRunner(BuildSettings settings) {
        this.settings = settings;
    }

    public PrintStream getOut() {
        return this.out;
    }

    public void setOut(PrintStream outputStream) {
        this.out = outputStream;
    }

    public int executeCommand(String scriptName, String args) {
        return executeCommand(scriptName, args, null);
    }

    public int executeCommand(String scriptName, String args, String env) {
        // Populate the root loader with all libraries that this app
        // depends on. If a root loader doesn't exist yet, create it
        // now.
        if (settings.getRootLoader() == null) {
            settings.setRootLoader((URLClassLoader) GrailsScriptRunner.class.getClassLoader());
        }

        // Get the default environment if one hasn't been set.
        boolean useDefaultEnv = env == null;
        if (useDefaultEnv) {
            env = (String) DEFAULT_ENVS.get(scriptName);
            env = env != null ? env : Environment.DEVELOPMENT.getName();
        }

        System.setProperty("base.dir", settings.getBaseDir().getPath());
        System.setProperty(Environment.KEY, env);
        System.setProperty(Environment.DEFAULT, useDefaultEnv ? "true" : "");

        if (args != null) {
            // Check whether we are running in non-interactive mode
            // by looking for a "non-interactive" argument.
            String[] argArray = args.split("\\s+");
            Pattern pattern = Pattern.compile("^(?:-)?-non-interactive$");
            for (String arg : argArray) {
                if (pattern.matcher(arg).matches()) {
                    isInteractive = false;
                    break;
                }
            }

            System.setProperty("grails.cli.args", args.replace(' ', '\n'));
        }
        else {
            // If GrailsScriptRunner is executed more than once in a
            // single JVM, we have to make sure that the CLI args are
            // reset.
            System.setProperty("grails.cli.args", "");
        }

        // Load the BuildSettings file for this project if it exists. Note
        // that this does not load any environment-specific settings.
        try {
            settings.loadConfig();
        }
        catch (Exception e) {
            System.err.println("WARNING: There was an error loading the BuildConfig: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        // Add some extra binding variables that are now available.
        settings.setGrailsEnv(env);
        settings.setDefaultEnv(useDefaultEnv);

        BuildSettingsHolder.setSettings(settings);


        // Either run the script or enter interactive mode.
        if (scriptName.equalsIgnoreCase("interactive")) {
            // Can't operate interactively in non-interactive mode!
            if (!isInteractive) {
                out.println("You cannot use '--non-interactive' with interactive mode.");
                return 1;
            }

            // This never exits unless an exception is thrown or
            // the process is interrupted via a signal.
            runInteractive();
            return 0;
        }
        else {
            return callPluginOrGrailsScript(scriptName);
        }
    }

    /**
     * Runs Grails in interactive mode.
     */
    private void runInteractive() {
        String message = "Interactive mode ready, type your command name in to continue (hit ENTER to run the last command or 'exit' to quit):\n";
        //disable exiting
//        System.metaClass.static.exit = {int code ->}
        System.setProperty("grails.interactive.mode", "true");
        if (System.getProperty(Environment.DEFAULT).equals("true")) {
            System.clearProperty(Environment.KEY);
            System.clearProperty(Environment.DEFAULT);
        }
        // save initial system properties
        Properties originalProperties = new Properties();
        originalProperties.putAll(System.getProperties());

        int messageNumber = 0;
        ScriptAndArgs script = new ScriptAndArgs();
        while (true) {
            out.println("--------------------------------------------------------");
            String commandProperty = System.getProperty("grails.script.name" + (messageNumber++));
            String enteredName = commandProperty != null ? commandProperty : userInput(message);

            if (enteredName != null && enteredName.trim().length() > 0) {
                // restore initial system properties
                Properties revertProperties = new Properties();
                revertProperties.putAll(originalProperties);
                System.setProperties(revertProperties);

                if (enteredName.equals("exit")) {
                    break;
                }

                script = processArgumentsAndReturnScriptName(enteredName);
            }

            if (script.name == null) {
                out.println("You must enter a command.\n");
                continue;
            }

            long now = System.currentTimeMillis();
            int exitCode = executeCommand(script.name, script.args, script.env);
            long end = System.currentTimeMillis();
            out.println("--------------------------------------------------------");
            out.println("Command [" + script.name + " completed in " + (end - now) + "ms with exit code " + exitCode);
        }
    }

    private final Map scriptCache = new HashMap();
    private final List scriptsAllowedOutsideOfProject = new ArrayList();

    private int callPluginOrGrailsScript(String scriptName) {
        // The directory where scripts are cached.
        File scriptCacheDir = new File(settings.getGrailsWorkDir(), "scriptCache");

        // The class loader we will use to run Gant. It's the root
        // loader plus all the application's compiled classes.
        URLClassLoader classLoader;
        try {
            // JARs already on the classpath should be excluded.
            Set existingJars = new HashSet();
            for (URL url : settings.getRootLoader().getURLs()) {
                existingJars.add(url.getFile());
            }

            // Add the remaining JARs (from 'grailsHome', the app, and
            // the plugins) to the root loader.
            boolean skipPlugins = "UninstallPlugin".equals(scriptName)||"InstallPlugin".equals(scriptName);

            URL[] urls = getClassLoaderUrls(settings, scriptCacheDir, existingJars, skipPlugins);
            addUrlsToRootLoader(settings.getRootLoader(), urls);

            // The compiled classes of the application!
            urls = new URL[] { settings.getClassesDir().toURI().toURL() };
            classLoader = new URLClassLoader(urls, settings.getRootLoader());
            Thread.currentThread().setContextClassLoader(classLoader);
        }
        catch (MalformedURLException ex) {
            throw new RuntimeException("Invalid classpath URL", ex);
        }

        List potentialScripts;
        GantBinding binding;
        if (scriptCache.get(scriptName) != null) {
            CachedScript cachedScript = (CachedScript) scriptCache.get(scriptName);
            potentialScripts = cachedScript.potentialScripts;
            binding = cachedScript.binding;
        }
        else {
            binding = new GantBinding();
            List list = getAvailableScripts(settings);

            potentialScripts = new ArrayList();
            for (Iterator iter = list.iterator(); iter.hasNext();) {
                File scriptPath = (File) iter.next();
                String scriptFileName = scriptPath.getName().substring(0,scriptPath.getName().length()-7); // trim .groovy extension
                if(scriptFileName.endsWith("_")) {
                    scriptsAllowedOutsideOfProject.add(scriptPath);
                    scriptFileName = scriptFileName.substring(0, scriptFileName.length()-1);
                }

                if (scriptFileName.equals(scriptName)) potentialScripts.add(scriptPath);
            }

            if (!potentialScripts.isEmpty()) {
                CachedScript cachedScript = new CachedScript();
                cachedScript.binding = binding;
                cachedScript.potentialScripts = potentialScripts;
                scriptCache.put("scriptName", cachedScript);
            }
        }

        // Prep the binding with important variables.
        initBinding(binding);

        // First try to load the script from its file. If there is no
        // file, then attempt to load it as a pre-compiled script. If
        // that fails, then let the user know and then exit.
        if (potentialScripts.size() > 0) {
            potentialScripts = (List) DefaultGroovyMethods.unique(potentialScripts);
            if (potentialScripts.size() == 1) {
                final File scriptFile = (File) potentialScripts.get(0);
                if(!isGrailsProject() && !isExternalScript(scriptFile)) {
                    out.println(settings.getBaseDir().getPath() + " does not appear to be part of a Grails application.");
                    out.println("The following commands are supported outside of a project:");
                    Collections.sort(scriptsAllowedOutsideOfProject);
                    for (Iterator iter = scriptsAllowedOutsideOfProject.iterator(); iter.hasNext();) {
                        File file = (File) iter.next();
                        out.println("\t" + GrailsNameUtils.getScriptName(file.getName()));
                    }
                    out.println("Run 'grails help' for a complete list of available scripts.");
                    return -1;
                }
                else {
                    out.println("Running script " + scriptFile.getAbsolutePath());

                    // Setup the script to call.
                    Gant gant = new Gant(binding, classLoader);
                    gant.setUseCache(true);
                    gant.setCacheDirectory(scriptCacheDir);
                    gant.loadScript(scriptFile);

                    // Invoke the default target.
                    return gant.processTargets().intValue();
                }
            }
            else {
                // If there are multiple scripts to choose from and we
                // are in non-interactive mode, then exit with an error
                // code. Otherwise the code will enter an infinite loop.
                if (!isInteractive) {
                    out.println("More than one script with the given name is available - " +
                            "cannot continue in non-interactive mode.");
                    return 1;
                }

                out.println("Multiple options please select:");
                String[] validArgs = new String[potentialScripts.size()];
                for (int i = 0; i < validArgs.length; i++) {
                    out.println("[" + (i + 1) + "] " + potentialScripts.get(i));
                    validArgs[i] = String.valueOf(i + 1);
                }

                String enteredValue = userInput("Enter #", validArgs);
                if (enteredValue == null) return 1;

                int number = Integer.valueOf(enteredValue);

                out.println("Running script "+ ((File) potentialScripts.get(number - 1)).getAbsolutePath());

                // Set up the script to call.
                Gant gant = new Gant(binding, classLoader);
                gant.loadScript((File) potentialScripts.get(number - 1));

                // Invoke the default target.
                return gant.processTargets().intValue();
            }
        }
        else {
            out.println("Running pre-compiled script");

            // Get Gant to load the class by name using our class loader.
            Gant gant = new Gant(binding, classLoader);
            try {
                gant.loadScriptClass(scriptName+"_");
                // try externalized script first
            }
            catch (Exception e) {
                try {
                    gant.loadScriptClass(scriptName);
                }
                catch (Exception ex) {
                    if (ex instanceof ClassNotFoundException &&
                            ex.getMessage() != null &&
                            ex.getMessage().contains(scriptName)) {
                        throw new ScriptNotFoundException(scriptName);
                    }
                }
            }

            return gant.processTargets().intValue();
        }
    }

    private boolean isGrailsProject() {
        return new File(settings.getBaseDir(), "grails-app").exists();
    }

    private boolean isExternalScript(File scriptFile) {
        return scriptsAllowedOutsideOfProject.contains(scriptFile);
    }

    /**
     * Prep the binding. We add the location of GRAILS_HOME under
     * the variable name "grailsHome". We also add a closure that
     * should be used with "includeTargets <<" - it takes a string
     * and returns either a file containing the named Grails script
     * or the script class.
     *
     * So, this:
     *
     *   includeTargets << grailsScript("Init")
     *
     * will load the "Init" script from $GRAILS_HOME/scripts if it
     * exists there; otherwise it will load the Init class.
     */
    private void initBinding(Binding binding) {
        Closure c = settings.getGrailsScriptClosure();
        c.setDelegate(binding);
        binding.setVariable("grailsScript", c);
        binding.setVariable("grailsSettings", settings);

        // Add other binding variables, such as Grails version and
        // environment.
        binding.setVariable("basedir", settings.getBaseDir().getPath());
        binding.setVariable("baseFile", settings.getBaseDir());
        binding.setVariable("baseName", settings.getBaseDir().getName());
        binding.setVariable("grailsHome", (settings.getGrailsHome() != null ? settings.getGrailsHome().getPath() : null));
        binding.setVariable("grailsVersion", settings.getGrailsVersion());
        binding.setVariable("userHome", settings.getUserHome());
        binding.setVariable("grailsEnv", settings.getGrailsEnv());
        binding.setVariable("defaultEnv", Boolean.valueOf(settings.getDefaultEnv()));
        binding.setVariable("buildConfig", settings.getConfig());
        binding.setVariable("rootLoader", settings.getRootLoader());

        // Add the project paths too!
        binding.setVariable("grailsWorkDir", settings.getGrailsWorkDir().getPath());
        binding.setVariable("projectWorkDir", settings.getProjectWorkDir().getPath());
        binding.setVariable("classesDirPath", settings.getClassesDir().getPath());
        binding.setVariable("testDirPath", settings.getTestClassesDir().getPath());
        binding.setVariable("resourcesDirPath", settings.getResourcesDir().getPath());
        binding.setVariable("pluginsDirPath", settings.getProjectPluginsDir().getPath());
        binding.setVariable("globalPluginsDirPath", settings.getGlobalPluginsDir().getPath());

        // Hide the deprecation warnings that occur with plugins that
        // use "Ant" instead of "ant".
        // TODO Remove this after 1.1 is released. Plugins should be
        // able to safely switch to "ant" by then (few people should
        // still be on 1.0.3 or earlier).
        binding.setVariable("Ant", binding.getVariable("ant"));

        // Create binding variables that contain the locations of each of the
        // plugins loaded by the application. The name of each variable is of
        // the form <pluginName>PluginDir.
        try {
            // First, if this is a plugin project, we need to add its
            // descriptor.
            List descriptors = new ArrayList();
            File desc = getPluginDescriptor(settings.getBaseDir());
            if (desc != null) descriptors.add(desc);

            // Next add all those of installed plugins.
            for (File dir : listKnownPluginDirs(settings)) {
                File pluginDescriptor = getPluginDescriptor(dir);
                if (pluginDescriptor != null) descriptors.add(pluginDescriptor);
            }

            // Go through all the descriptors and add the appropriate
            // binding variable for each one that contains the location
            // of its plugin directory.
            for (int i = 0, n = descriptors.size(); i < n; i++) {
                desc = (File) descriptors.get(i);
                Matcher matcher = pluginDescriptorPattern.matcher(desc.getName());
                matcher.find();
                String pluginName = GrailsNameUtils.getPropertyName(matcher.group(1));

                // Add the plugin path to the binding.
                binding.setVariable(pluginName + "PluginDir", desc.getParentFile());
            }
        }
        catch (Exception e) {
            // No plugins found.
        }
    }

    /**
     * Returns a list of all the executable Gant scripts available to
     * this application.
     */
    private static List getAvailableScripts(BuildSettings settings) {
        List scripts = new ArrayList();
        if (settings.getGrailsHome() != null) {
            addCommandScripts(new File(settings.getGrailsHome(), "scripts"), scripts);
        }
        addCommandScripts(new File(settings.getBaseDir(), "scripts"), scripts);
        addCommandScripts(new File(settings.getUserHome(), ".grails/scripts"), scripts);

        for (File dir : listKnownPluginDirs(settings)) {
            addPluginScripts(dir, scripts);
        }

        return scripts;
    }

    /**
     * Collects all the command scripts provided by the plugin contained
     * in the given directory and adds them to the given list.
     */
    private static void addPluginScripts(File pluginDir, List scripts) {
        if (!pluginDir.exists()) return;

        File scriptDir = new File(pluginDir, "scripts");
        if (scriptDir.exists()) addCommandScripts(scriptDir, scripts);
    }

    /**
     * Adds all the command scripts (i.e. those whose name does *not*
     * start with an underscore, '_') found in the given directory to
     * the given list.
     */
    private static void addCommandScripts(File dir, List scripts) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            for (int i = 0; i < files.length; i++) {
                if (scriptFilePattern.matcher(files[i].getName()).matches()) {
                    scripts.add(files[i]);
                }
            }
        }
    }

    /**
     * Creates a new root loader with the Grails libraries and the
     * application's plugin libraries on the classpath.
     */
    private static URL[] getClassLoaderUrls(BuildSettings settings, File cacheDir, Set excludes, boolean skipPlugins) throws MalformedURLException {
        List<URL> urls = new ArrayList<URL>();

        // If 'grailsHome' is set, make sure the script cache
        // directory takes precedence over the "grails-scripts"
        // JAR by adding it first.
        if (settings.getGrailsHome() != null) {
            urls.add(cacheDir.toURI().toURL());
        }

        // Add the "resources" directory so that config files and the
        // like can be picked up off the classpath.
        if (settings.getResourcesDir() != null && settings.getResourcesDir().exists()) {
            urls.add(settings.getResourcesDir().toURI().toURL());
        }

        // Add compilation dependencies
        List compileDependencies = settings.getCompileDependencies();
        for (Object compileDependency : compileDependencies) {
            File file = (File) compileDependency;
            if (!excludes.contains(file.getName())) {
                urls.add(file.toURI().toURL());
                excludes.add(file.getName());
            }

        }
        // Add the project's runtime dependencies because most of them
        // will be required for the build to work.
        List runtimeDeps = settings.getRuntimeDependencies();
        for (Iterator iter = runtimeDeps.iterator(); iter.hasNext();) {
            File file = (File) iter.next();
            if(urls.contains(file)) continue;
            if (!excludes.contains(file.getName())) {
                urls.add(file.toURI().toURL());
                excludes.add(file.getName());
            }
        }


        // If we're using a Grails installation, add any remaining JARs
        // from its "lib" directory.
        if (settings.getGrailsHome() != null) {
            addLibs(new File(settings.getGrailsHome(), "lib"), urls, excludes);
        }

        // Add the libraries of both project and global plugins.
        if (!skipPlugins) {
            for (File dir : listKnownPluginDirs(settings)) {
                addPluginLibs(dir, urls);
            }
        }
        return urls.toArray(new URL[urls.size()]);
    }

    /**
     * List all plugin directories that we know about: those in the
     * project's "plugins" directory, those in the global "plugins"
     * dir, and those declared explicitly in the build config.
     * @param settings The build settings for this project.
     * @return A list of all known plugin directories, or an empty
     * list if there are none.
     */
    private static List<File> listKnownPluginDirs(BuildSettings settings) {
        List<File> dirs = new ArrayList<File>();

        // First look in the global plugins directory.
        dirs.addAll(Arrays.asList(listPluginDirs(settings.getGlobalPluginsDir())));

        // Next up, the project's plugins directory.
        dirs.addAll(Arrays.asList(listPluginDirs(settings.getProjectPluginsDir())));

        // Finally, pick up any explicit plugin directories declared
        // in the build config.
        Map<String, ?> buildConfig = settings.getConfig().flatten();
        for (Map.Entry<String,?> entry : buildConfig.entrySet()) {
            if (entry.getKey().startsWith("grails.plugin.location.")) {
                dirs.add(new File(entry.getValue().toString()));
            }
        }

        return dirs;
    }

    /**
     * Adds all the libraries in a plugin to the given list of URLs.
     * @param pluginDir The directory containing the plugin.
     * @param urls The list of URLs to add the plugin JARs to.
     */
    private static void addPluginLibs(File pluginDir, List urls) throws MalformedURLException {
        if (!pluginDir.exists()) return;

        File libDir = new File(pluginDir, "lib");
        if (libDir.exists()) addLibs(libDir, urls, Collections.EMPTY_SET);
    }

    /**
     * Adds all the JAR files in the given directory to the list of
     * URLs. Excludes any "standard-*.jar" and "jstl-*.jar" because
     * these are added to the classpath in another place. They depend
     * on the servlet version of the app and so need to be treated
     * specially.
     */
    private static void addLibs(File dir, List urls, Set excludes) throws MalformedURLException {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                if (file.getName().matches("^.*\\.jar$")
                        && !excludes.contains(file.getName())
                        && !file.getName().matches("^(standard|jstl)-\\d.*$")) {
                    urls.add(file.toURI().toURL());
                }
            }
        }
    }

    /**
     * Lists all the sub-directories (non-recursively) of the given
     * directory that look like directories that contain a plugin.
     * If there are no directories, an empty array is returned. We
     * basically check that the name of each directory looks about
     * right.
     */
    private static File[] listPluginDirs(File dir) {
        File[] dirs = dir.listFiles(new FileFilter() {
            public boolean accept(File path) {
                return path.isDirectory() &&
                        (!path.getName().startsWith(".") &&
                                path.getName().indexOf('-') > -1);
            }
        });

        return dirs == null ? new File[0] : dirs;
    }

    /**
     * Retrieves the first plugin descriptor it finds in the given
     * directory. The search is not recursive.
     * @param dir The directory to search in.
     * @return The location of the plugin descriptor, or <code>null</code>
     * if none can be found.
     */
    private static File getPluginDescriptor(File dir) {
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File file, String s) {
                return s.endsWith("GrailsPlugin.groovy");
            }
        });

        if (files.length > 0) return files[0];
        else return null;
    }

    /**
     * Sanitizes a stack trace using GrailsUtil.deepSanitize(). We use
     * this method so that the GrailsUtil class is loaded from the
     * context class loader. Basically, we don't want this class to
     * have a direct dependency on GrailsUtil otherwise the class loader
     * used to load this class (GrailsScriptRunner) would have to have
     * far more libraries on its classpath than we want.
     */
    private static void sanitizeStacktrace(Throwable t) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class clazz = loader.loadClass("grails.util.GrailsUtil");
            Method method = clazz.getMethod("deepSanitize", new Class[] {Throwable.class});
            method.invoke(null, new Object[] {t});
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * <p>A Groovy RootLoader should be used to load GrailsScriptRunner,
     * but this leaves us with a problem. If we want to extend its
     * classpath by adding extra URLs, we have to use the addURL()
     * method that is only public on RootLoader (it's protected on
     * URLClassLoader). Unfortunately, due to the nature of Groovy's
     * RootLoader a declared type of RootLoader in this class is not
     * the same type as GrailsScriptRunner's class loader <i>because
     * the two are loaded by different class loaders</i>.</p>
     * <p>In other words, we can't add URLs via the addURL() method
     * because we can't "see" it from Java. Instead, we use reflection
     * to invoke it.</p>
     * @param loader The root loader whose classpath we want to extend.
     * @param urls The URLs to add to the root loader's classpath.
     */
    private static void addUrlsToRootLoader(URLClassLoader loader, URL[] urls) {
        try {
            Class loaderClass = loader.getClass();
            Method method = loaderClass.getMethod("addURL", URL.class);
            for (URL url : urls) {
                method.invoke(loader, url);
            }
        }
        catch (Exception ex) {
            throw new RuntimeException(
                    "Cannot dynamically add URLs to GrailsScriptRunner's" +
                    " class loader - make sure that it is loaded by Groovy's" +
                    " RootLoader or a sub-class.");
        }
    }

    /**
     * Replacement for AntBuilder.input() to eliminate dependency of
     * GrailsScriptRunner on the Ant libraries. Prints a message and
     * returns whatever the user enters (once they press &lt;return&gt;).
     * @param message The message/question to display.
     * @return The line of text entered by the user. May be a blank
     * string.
     */
    private String userInput(String message) {
        return userInput(message, null);
    }

    /**
     * Replacement for AntBuilder.input() to eliminate dependency of
     * GrailsScriptRunner on the Ant libraries. Prints a message and
     * list of valid responses, then returns whatever the user enters
     * (once they press &lt;return&gt;). If the user enters something
     * that is not in the array of valid responses, the message is
     * displayed again and the method waits for more input. It will
     * display the message a maximum of three times before it gives up
     * and returns <code>null</code>.
     * @param message The message/question to display.
     * @param validResponses An array of responses that the user is
     * allowed to enter. Displayed after the message.
     * @return The line of text entered by the user, or <code>null</code>
     * if the user never entered a valid string.
     */
    private String userInput(String message, String[] validResponses) {
        String responsesString = null;
        if (validResponses != null) {
            responsesString = DefaultGroovyMethods.join(validResponses, ",");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        for (int it = 0; it < 3; it++) {
            out.print(message);
            if (responsesString != null) {
                out.print(" [");
                out.print(responsesString);
                out.print("] ");
            }

            try {
                String line = reader.readLine();

                if (validResponses == null) return line;

                for (String validResponse : validResponses) {
                    if (line != null && line.equals(validResponse)) {
                        return line;
                    }
                }

                out.println();
                out.println("Invalid option '" + line + "' - must be one of: [" + responsesString + "]");
                out.println();
            }
            catch (IOException ex) {
                ex.printStackTrace();
                return null;
            }
        }

        // No valid response given.
        out.println("No valid response entered - giving up asking.");
        return null;
    }

    /**
     * Contains details about a Grails command invocation such as the
     * name of the corresponding script, the environment (if specified),
     * and the arguments to the command.
     */
    private static class ScriptAndArgs {
        public String name;
        public String env;
        public String args;
    }
}

