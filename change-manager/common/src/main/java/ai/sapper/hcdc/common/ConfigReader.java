package ai.sapper.hcdc.common;

import ai.sapper.hcdc.common.model.services.EConfigFileType;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.ex.ConfigurationRuntimeException;
import org.apache.commons.configuration2.io.ClasspathLocationStrategy;
import org.apache.commons.configuration2.io.CombinedLocationStrategy;
import org.apache.commons.configuration2.io.FileLocationStrategy;
import org.apache.commons.configuration2.io.ProvidedURLLocationStrategy;
import org.apache.commons.configuration2.tree.ImmutableNode;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Accessors(fluent = true)
public class ConfigReader {
    public static final String __NODE_PARAMETERS = "parameters";
    public static final String __NODE_PARAMETER = "parameter";
    public static final String __PARAM_NAME = "name";
    public static final String __PARAM_VALUE = "value";

    private final HierarchicalConfiguration<ImmutableNode> config;

    public ConfigReader(@NonNull HierarchicalConfiguration<ImmutableNode> config, @NonNull String path) {
        this.config = config.configurationAt(path);
    }

    public boolean checkIfNodeExists(String path, @NonNull String name) {
        String key = name;
        if (!Strings.isNullOrEmpty(path)) {
            key = String.format("%s.%s", path, key);
        }
        if (!Strings.isNullOrEmpty(key)) {
            try {
                List<HierarchicalConfiguration<ImmutableNode>> nodes = get().configurationsAt(name);
                if (nodes != null) return !nodes.isEmpty();
            } catch (ConfigurationRuntimeException e) {
                // Ignore Exception
            }
        }
        return false;
    }

    public HierarchicalConfiguration<ImmutableNode> get() {
        return config;
    }

    public HierarchicalConfiguration<ImmutableNode> get(@NonNull String name) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(name));
        if (checkIfNodeExists((String) null, name))
            return config.configurationAt(name);
        return null;
    }

    public List<HierarchicalConfiguration<ImmutableNode>> getCollection(@NonNull String name) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(name));
        if (checkIfNodeExists((String) null, name))
            return config.configurationsAt(name);
        return null;
    }

    protected Map<String, String> readParameters() throws ConfigurationException {
        if (checkIfNodeExists((String) null, __NODE_PARAMETERS)) {
            HierarchicalConfiguration<ImmutableNode> pc = config.configurationAt(__NODE_PARAMETERS);
            if (pc != null) {
                List<HierarchicalConfiguration<ImmutableNode>> pl = pc.configurationsAt(__NODE_PARAMETER);
                if (pl != null && !pl.isEmpty()) {
                    Map<String, String> params = new HashMap<>(pl.size());
                    for (HierarchicalConfiguration<ImmutableNode> p : pl) {
                        String name = p.getString(__PARAM_NAME);
                        if (!Strings.isNullOrEmpty(name)) {
                            String value = p.getString(__PARAM_VALUE);
                            params.put(name, value);
                        }
                    }
                    return params;
                }
            }
        }
        return null;
    }

    public static XMLConfiguration readFromFile(@NonNull String filename) throws ConfigurationException {
        File cf = new File(filename);
        if (!cf.exists()) {
            throw new ConfigurationException(String.format("Specified configuration file not found. [file=%s]", cf.getAbsolutePath()));
        }
        if (!cf.canRead()) {
            throw new ConfigurationException(String.format("Cannot read configuration file. [file=%s]", cf.getAbsolutePath()));
        }
        Parameters params = new Parameters();
        FileBasedConfigurationBuilder<XMLConfiguration> builder =
                new FileBasedConfigurationBuilder<XMLConfiguration>(XMLConfiguration.class)
                        .configure(params.xml()
                                .setFileName(cf.getAbsolutePath()));
        return builder.getConfiguration();
    }

    public static XMLConfiguration readFromClasspath(@NonNull String path) throws ConfigurationException {
        List<FileLocationStrategy> subs = List.of(
                new ProvidedURLLocationStrategy());
        FileLocationStrategy strategy = new CombinedLocationStrategy(subs);
        Parameters params = new Parameters();
        FileBasedConfigurationBuilder<XMLConfiguration> builder =
                new FileBasedConfigurationBuilder<XMLConfiguration>(XMLConfiguration.class)
                        .configure(params.xml()
                                .setLocationStrategy(strategy).setFileName(path));
        return builder.getConfiguration();
    }

    public static XMLConfiguration readFromURI(@NonNull String path) throws ConfigurationException {
        List<FileLocationStrategy> subs = List.of(
                new ClasspathLocationStrategy());
        FileLocationStrategy strategy = new CombinedLocationStrategy(subs);
        Parameters params = new Parameters();
        FileBasedConfigurationBuilder<XMLConfiguration> builder =
                new FileBasedConfigurationBuilder<XMLConfiguration>(XMLConfiguration.class)
                        .configure(params.xml()
                                .setLocationStrategy(strategy).setFileName(path));
        return builder.getConfiguration();
    }

    public static XMLConfiguration read(@NonNull String path, @NonNull EConfigFileType type) throws ConfigurationException {
        switch (type) {
            case File:
                return readFromFile(path);
            case Remote:
                return readFromURI(path);
            case Resource:
                return readFromClasspath(path);
        }
        throw new ConfigurationException(String.format("Invalid Config File type. [type=%s]", type.name()));
    }

    public static boolean checkIfNodeExists(@NonNull HierarchicalConfiguration<ImmutableNode> node, @NonNull String name) {
        if (!Strings.isNullOrEmpty(name)) {
            try {
                List<HierarchicalConfiguration<ImmutableNode>> nodes = node.configurationsAt(name);
                if (nodes != null) return !nodes.isEmpty();
            } catch (ConfigurationRuntimeException e) {
                // Ignore Exception
            }
        }
        return false;
    }
}
