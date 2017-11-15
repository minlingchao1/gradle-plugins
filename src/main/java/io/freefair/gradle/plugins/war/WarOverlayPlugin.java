package io.freefair.gradle.plugins.war;

import groovy.lang.Closure;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.WarPlugin;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.War;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * @author Lars Grefer
 */
public class WarOverlayPlugin implements Plugin<Project> {

    private Project project;

    @Override
    public void apply(Project project) {
        this.project = project;
        project.getPluginManager().apply(WarPlugin.class);

        project.getTasks().withType(War.class, warTask -> {

            NamedDomainObjectContainer<WarOverlay> warOverlays = project.container(WarOverlay.class);

            warTask.getExtensions().add("overlays", warOverlays);

            project.afterEvaluate(p -> warOverlays.all(overlay -> {

                if (overlay.isEnabled()) {
                    configureOverlay(warTask, overlay);
                }

            }));
        });

    }

    private void configureOverlay(War warTask, WarOverlay overlay) {
        Object source = overlay.getSource();

        if (source instanceof AbstractArchiveTask) {
            configureOverlay(warTask, overlay, (AbstractArchiveTask) source);
        } else if (source instanceof Project && overlay.getConfigureClosure() == null) {
            configureOverlay(warTask, overlay, (Project) source);
        } else {
            Closure configClosure = overlay.getConfigureClosure();
            Dependency dependency;
            if (configClosure == null) {
                dependency = project.getDependencies().create(source);
            } else {
                dependency = project.getDependencies().create(source, configClosure);
            }

            if (dependency instanceof ProjectDependency) {
                configureOverlay(warTask, overlay, ((ProjectDependency) dependency).getDependencyProject());
            } else if (dependency instanceof ExternalDependency) {
                configureOverlay(warTask, overlay, (ExternalDependency) dependency);
            } else {
                throw new GradleException("Unsupported dependency type: " + dependency.getClass().getName());
            }
        }
    }

    private void configureOverlay(War warTask, WarOverlay overlay, ExternalDependency dependency) {

        String capitalizedWarTaskName = StringGroovyMethods.capitalize((CharSequence) warTask.getName());
        String capitalizedOverlayName = StringGroovyMethods.capitalize((CharSequence) overlay.getName());

        dependency.setTransitive(false);

        Configuration detachedConfiguration = project.getConfigurations().detachedConfiguration(dependency);

        Sync extractOverlay = project.getTasks().create("extract" + capitalizedWarTaskName + capitalizedOverlayName + "Overlay", Sync.class);

        File destinationDir = new File(project.getBuildDir(), String.format("overlays/%s/%s", warTask.getName(), overlay.getName()));
        extractOverlay.setDestinationDir(destinationDir);

        extractOverlay.from((Callable<FileTree>) () -> project.zipTree(detachedConfiguration.getSingleFile()));

        warTask.into(overlay.getInto(), copySpec -> {
            copySpec.from(extractOverlay);
            configureOverlayCopySpec(warTask, overlay, copySpec);
        });

        if (overlay.isEnableCompilation()) {

            project.getTasks().create(extractOverlay.getName() + "Internal", Sync.class, t -> {
                t.setDestinationDir(extractOverlay.getDestinationDir());
                t.from(project.zipTree(detachedConfiguration.getSingleFile()));
            }).execute();

            project.getTasks().getByName("clean").finalizedBy(extractOverlay);

            ConfigurableFileCollection classes = project.files(
                    (Callable<File>) () -> new File(extractOverlay.getDestinationDir(), "WEB-INF/classes")
            )
                    .builtBy(extractOverlay);

            project.getDependencies().add(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME, classes);
            project.getDependencies().add(JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME, classes);

            FileTree libs = project.files(extractOverlay).builtBy(extractOverlay).getAsFileTree()
                    .matching(patternFilterable -> patternFilterable.include("WEB-INF/lib/**"));

            project.getDependencies().add(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME, libs);
            project.getDependencies().add(JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME, libs);
        }
    }

    private void configureOverlayCopySpec(War warTask, WarOverlay overlay, CopySpec copySpec) {
        copySpec.exclude(overlay.getExcludes());
        copySpec.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
        //Delay this to trick IntelliJ
        warTask.doFirst(w -> copySpec.exclude(element -> overlay.isProvided()));
    }

    private void configureOverlay(War warTask, WarOverlay overlay, Project otherProject) {
        project.evaluationDependsOn(otherProject.getPath());

        War otherWar = (War) otherProject.getTasks().getByName("war");

        configureOverlay(warTask, overlay, otherWar);

        if (overlay.isEnableCompilation()) {
            project.getDependencies().add("compileClasspath", otherProject);
            project.getDependencies().add("testCompileClasspath", otherProject);
        }
    }

    private void configureOverlay(War warTask, WarOverlay overlay, AbstractArchiveTask from) {
        warTask.into(overlay.getInto(), copySpec -> {
            copySpec.with(from.getRootSpec());
            configureOverlayCopySpec(warTask, overlay, copySpec);
        });
    }

}